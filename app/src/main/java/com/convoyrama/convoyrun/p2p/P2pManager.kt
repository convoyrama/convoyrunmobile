package com.convoyrama.convoyrun.p2p

import android.content.Context
import com.convoyrama.convoyrun.data.EventStore
import com.convoyrama.convoyrun.data.isValidIncomingEvent
import com.convoyrama.convoyrun.data.PreferencesManager
import com.convoyrama.convoyrun.data.shouldDisplayEvent
import com.convoyrama.convoyrun.data.ProfileStore
import com.convoyrama.convoyrun.data.VoteStore
import com.convoyrama.convoyrun.model.*
import uniffi.convoyrun_mobile_ffi.P2pNodeWrapper
import uniffi.convoyrun_mobile_ffi.GossipSubscriptionWrapper
import uniffi.convoyrun_mobile_ffi.verifyConvoySignature
import uniffi.convoyrun_mobile_ffi.verifyVoteSignature
import uniffi.convoyrun_mobile_ffi.verifyBlacklistSignature
import uniffi.convoyrun_mobile_ffi.verifyDeleteSignature
import uniffi.convoyrun_mobile_ffi.signVote
import uniffi.convoyrun_mobile_ffi.signProfile
import uniffi.convoyrun_mobile_ffi.verifyProfileSignature
import uniffi.convoyrun_mobile_ffi.createP2pNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * P2P Manager - wraps the Rust FFI layer for Kotlin
 *
 * This class manages the P2P node lifecycle and provides
 * a clean Kotlin API for the UI layer.
 */
class P2pManager(
    private val context: Context,
    private val prefs: PreferencesManager
) {

    /**
     * Connection status
     */
    enum class Status {
        OFFLINE,      // Node not started
        SEARCHING,    // Node started, looking for peers
        ONLINE        // Connected to peers
    }

    private val _status = MutableStateFlow(Status.OFFLINE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    private val _events = MutableStateFlow<List<ConvoyEvent>>(emptyList())
    val events: StateFlow<List<ConvoyEvent>> = _events.asStateFlow()

    private val _votes = MutableStateFlow<Map<String, List<VoteRecord>>>(emptyMap())
    val votes: StateFlow<Map<String, List<VoteRecord>>> = _votes.asStateFlow()

    private val _myVotes = MutableStateFlow<Map<String, Int>>(emptyMap())
    val myVotes: StateFlow<Map<String, Int>> = _myVotes.asStateFlow()

    private val _profiles = MutableStateFlow<Map<String, ProfileRecord>>(emptyMap())
    val profiles: StateFlow<Map<String, ProfileRecord>> = _profiles.asStateFlow()

    private var node: P2pNodeWrapper? = null
    private var subscription: GossipSubscriptionWrapper? = null
    private var receiverJob: Job? = null
    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)

    // Persistent event store (disk-backed)
    private lateinit var eventStore: EventStore

    // Persistent vote store (disk-backed)
    private lateinit var voteStore: VoteStore
    private lateinit var profileStore: ProfileStore

    // Deduplication for gossip messages
    private val seenMessages = HashSet<String>()
    private val seenMessagesOrder = ArrayList<String>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initialize and start the P2P node
     */
    fun start() {
        if (node != null || starting.get()) return
        starting.set(true)

        try {
            android.util.Log.i("P2pManager", "Starting P2P node...")
            
            // Get data directory
            val dataDir = File(context.filesDir, "p2p")
            dataDir.mkdirs()
            android.util.Log.i("P2pManager", "Data dir: ${dataDir.absolutePath}")

            // Initialize event store (loads from disk, purges expired)
            eventStore = EventStore(dataDir)
            val loadedEvents = eventStore.load()
            eventStore.purgeExpired()
            if (eventStore.size() != loadedEvents.size) {
                eventStore.save()
            }
            _events.value = eventStore.getAllIncludingDeleted()
            android.util.Log.i("P2pManager", "Loaded ${eventStore.size()} events from disk")

            // Initialize vote store (loads from disk, purges expired)
            voteStore = VoteStore(dataDir)
            voteStore.load()
            voteStore.purgeExpired()
            voteStore.save()
            _votes.value = voteStore.getAll()
            android.util.Log.i("P2pManager", "Loaded votes from disk")

            profileStore = ProfileStore(dataDir)
            _profiles.value = profileStore.load()

            // Initialize Android context (for DNS resolver)
            ConvoyRunP2p.installAndroidContext(context.applicationContext)

            // Create P2P node (sync function)
            android.util.Log.i("P2pManager", "Creating P2P node...")
            val nodeWrapper = createP2pNode(dataDir.absolutePath)
            node = nodeWrapper
            android.util.Log.i("P2pManager", "P2P node created, peerId: ${nodeWrapper.peerId()}")

            // Initialize myVotes now that we have the peer ID
            _myVotes.value = voteStore.getMyVotes(otesAuthorId(nodeWrapper.peerId()))

            // Join the gossip topic
            android.util.Log.i("P2pManager", "Joining gossip topic...")
            val sub = nodeWrapper.joinTopic()
            subscription = sub
            android.util.Log.i("P2pManager", "Joined gossip topic successfully")

            publishLocalProfile(sub)

            _status.value = Status.SEARCHING

            // Start receiving events
            startReceivingEvents()

            // Start periodic purge (every 1 hour, matches desktop)
            startPeriodicPurge()

            android.util.Log.i("P2pManager", "P2P manager started successfully")
        } catch (e: Exception) {
            android.util.Log.e("P2pManager", "Failed to start: ${e.message}", e)
            _status.value = Status.OFFLINE
        } finally {
            starting.set(false)
        }
    }

    /**
     * Start receiving events from the gossip network
     */
    private fun startReceivingEvents() {
        receiverJob = scope.launch {
            val sub = subscription ?: return@launch
            android.util.Log.i("P2pManager", "Event receiver loop started")
            var loopCount = 0

            // Separate coroutine for periodic peer count updates + re-broadcast on new peers
            var lastPeerCount = 0
            launch {
                while (isActive) {
                    delay(5000)
                    try {
                        val pc = sub.peerCount().toInt()
                        _peerCount.value = pc
                        val newStatus = if (pc > 0) Status.ONLINE else Status.SEARCHING
                        if (_status.value != newStatus) {
                            android.util.Log.i("P2pManager", "Status changed: ${_status.value} -> $newStatus (peers: $pc)")
                            _status.value = newStatus
                        }
                        // Re-broadcast on ANY peer count increase (new peer connected)
                        if (pc > lastPeerCount && pc > 0) {
                            android.util.Log.i("P2pManager", "New peer(s) detected ($lastPeerCount -> $pc), re-broadcasting events...")
                            reBroadcastAll(sub)
                        }
                        lastPeerCount = pc
                    } catch (e: Exception) {
                        android.util.Log.e("P2pManager", "Error updating peer count: ${e.message}")
                    }
                }
            }

            // Main loop: blocking read of next event (runs on IO dispatcher)
            android.util.Log.i("P2pManager", "Entering main event loop...")
            while (isActive) {
                try {
                    // This is a BLOCKING call (FFI block_on) - must run on IO
                    android.util.Log.d("P2pManager", "Waiting for nextEvent()...")
                    val event = sub.nextEvent()

                    // Update peer count after each event
                    val peerCount = sub.peerCount().toInt()
                    _peerCount.value = peerCount
                    val newStatus = if (peerCount > 0) Status.ONLINE else Status.SEARCHING
                    if (_status.value != newStatus) {
                        android.util.Log.i("P2pManager", "Status changed: ${_status.value} -> $newStatus (peers: $peerCount)")
                        _status.value = newStatus

                        // Re-broadcast all known events when coming online (reads from disk store)
                        if (newStatus == Status.ONLINE) {
                            val storedEvents = eventStore.getAllIncludingDeleted()
                            android.util.Log.i("P2pManager", "Coming ONLINE: re-broadcasting ${storedEvents.size} events from store...")
                            var onlineSuccess = 0
                            for (stored in storedEvents) {
                                try {
                                    broadcastStoredEvent(sub, stored)
                                    android.util.Log.d("P2pManager", "Online-broadcast event ${stored.id}")
                                    onlineSuccess++
                                } catch (e: Exception) {
                                    android.util.Log.e("P2pManager", "Re-broadcast failed for event ${stored.id}: ${e.message}")
                                }
                            }
                            android.util.Log.i("P2pManager", "Online re-broadcast done: $onlineSuccess/${storedEvents.size}")
                        }
                    }

                    if (event == null) {
                        android.util.Log.w("P2pManager", "nextEvent returned null - channel closed")
                        break
                    }

                    loopCount++
                    android.util.Log.i("P2pManager", "Event #$loopCount from ${event.sender()}, content length=${event.content().length}")
                    android.util.Log.d("P2pManager", "Event content preview: ${event.content().take(200)}")

                    // Parse the gossip message
                    val message = parseGossipMessage(event.content())
                    if (message == null) {
                        android.util.Log.w("P2pManager", "Failed to parse gossip message")
                        continue
                    }
                    android.util.Log.d("P2pManager", "Parsed message type: ${message::class.simpleName}")

                    when (message) {
                        is GossipMessage.Convoy -> {
                            // Dedup check
                            val dedupKey = "convoy:${parseConvoyEventId(message.data)}"
                            if (!seenMessages.add(dedupKey)) {
                                android.util.Log.d("P2pManager", "Skipping duplicate convoy")
                                continue
                            }
                            seenMessagesOrder.add(dedupKey)
                            trimSeenMessages()

                            android.util.Log.d("P2pManager", "Verifying signature for convoy...")
                            if (!verifyConvoySignature(message.data)) {
                                android.util.Log.w("P2pManager", "Dropping event with invalid signature. Data preview: ${message.data.take(200)}")
                                continue
                            }
                            android.util.Log.d("P2pManager", "Signature OK, parsing convoy event...")
                            val convoyEvent = parseConvoyEvent(message.data)
                            if (convoyEvent != null) {
                                android.util.Log.i("P2pManager", "Adding convoy event: '${convoyEvent.event.title}' (id=${convoyEvent.id}, peer=${convoyEvent.peerId.take(8)})")
                                addEvent(convoyEvent)
                            } else {
                                android.util.Log.e("P2pManager", "Failed to parse ConvoyEvent from data")
                            }
                        }
                        is GossipMessage.Tombstone -> {
                            // Dedup check
                            val dedupKey = "delete:${message.convoyId}:${message.peerId}:${message.revision}"
                            if (!seenMessages.add(dedupKey)) {
                                android.util.Log.d("P2pManager", "Skipping duplicate delete")
                                continue
                            }
                            seenMessagesOrder.add(dedupKey)
                            trimSeenMessages()

                            // Verify delete signature (matches desktop lib.rs:340-363)
                            if (!verifyDeleteSignature(message.peerId, message.convoyId, message.revision.toULong(), message.signature)) {
                                android.util.Log.w("P2pManager", "Dropping delete with invalid signature")
                                continue
                            }

                            // Verify the peer_id matches the convoy author
                            val convoy = _events.value.find { it.id == message.convoyId }
                            if (convoy != null && convoy.peerId != message.peerId) {
                                android.util.Log.w("P2pManager", "Delete rejected: peer_id doesn't match convoy author")
                                continue
                            }

                            android.util.Log.i("P2pManager", "Received delete for convoy: ${message.convoyId}")
                            removeEvent(message.convoyId, message.revision, message.peerId, message.signature)
                        }
                        is GossipMessage.Vote -> {
                            android.util.Log.d("P2pManager", "Verifying signature for vote...")
                            if (!verifyVoteSignature(message.data)) {
                                android.util.Log.w("P2pManager", "Dropping vote with invalid signature")
                                continue
                            }
                            val voteRecord = parseVoteRecord(message.data)
                            if (voteRecord == null || voteRecord.specVersion != "1.0" ||
                                voteRecord.kind != "vote" || voteRecord.vote !in -1..1 ||
                                voteRecord.revision < 1) {
                                android.util.Log.w("P2pManager", "Invalid vote record")
                                continue
                            }
                            // Validate field lengths (matches desktop convoy.rs validation)
                            if (voteRecord.eventId.length > 64 || voteRecord.authorId.length > 64) {
                                android.util.Log.w("P2pManager", "Vote REJECTED: fields too long")
                                continue
                            }
                            // Verify convoy exists locally (matches desktop lib.rs:1059-1061)
                            if (_events.value.none { it.id == voteRecord.eventId }) {
                                android.util.Log.d("P2pManager", "Vote for unknown convoy ${voteRecord.eventId}, storing anyway")
                            }
                            android.util.Log.i("P2pManager", "Received vote: ${voteRecord.vote} for convoy ${voteRecord.eventId}")
                            addVote(voteRecord)
                        }
                        is GossipMessage.Profile -> {
                            if (!verifyProfileSignature(message.data)) continue
                            val profile = parseProfileRecord(message.data) ?: continue
                            if (profileStore.upsert(profile)) {
                                profileStore.save()
                                _profiles.value = profileStore.getAll()
                            }
                        }
                        is GossipMessage.Blacklist -> {
                            android.util.Log.i("P2pManager", "Received blacklist")
                            applyBlacklist(message.data)
                        }
                        else -> {
                            android.util.Log.d("P2pManager", "Ignoring message type: ${message::class.simpleName}")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("P2pManager", "Error receiving event: ${e.message}", e)
                    delay(1000) // Back off on error
                }
            }
        }
    }

    /**
     * Add an event to the local cache and persist to disk.
     */
    private fun addEvent(event: ConvoyEvent) {
        val now = kotlinx.datetime.Clock.System.now().epochSeconds
        if (!isValidIncomingEvent(event, now)) {
            android.util.Log.w("P2pManager", "Event REJECTED: invalid document or retention window '${event.event.title}'")
            return
        }

        if (!eventStore.upsert(event)) {
            android.util.Log.d("P2pManager", "Skipping stale convoy ${event.id} @ r${event.revision}")
            return
        }

        eventStore.save()
        _events.value = eventStore.getAllIncludingDeleted()

        val totalEvents = _events.value.size
        if (shouldDisplayEvent(event, prefs.blockedAuthors.value.keys, prefs.filteredLanguages.value)) {
            android.util.Log.i("P2pManager", "Event stored. Total events: $totalEvents (on disk: ${eventStore.size()})")
        } else {
            android.util.Log.i("P2pManager", "Event stored but hidden by local preferences. Total events: $totalEvents (on disk: ${eventStore.size()})")
        }

        // Periodic purge every 50 events
        if (_events.value.size % 50 == 0) {
            purgeExpiredEvents()
        }
    }

    private fun removeEvent(convoyId: String, revision: Long, authorPeerId: String = "", signature: String = "") {
        val existing = _events.value.find { it.id == convoyId }
        val deletedEvent = if (existing != null) {
            existing.copy(
                revision = revision,
                deleted = true,
                deleteSignature = signature,
                signature = existing.signature
            )
        } else {
            ConvoyEvent(
                id = convoyId,
                peerId = authorPeerId,
                revision = revision,
                publishedAt = 0,
                event = com.convoyrama.convoyrun.model.EventData(),
                schedule = com.convoyrama.convoyrun.model.Schedule(),
                signature = "",
                deleteSignature = signature,
                deleted = true
            )
        }

        if (eventStore.upsert(deletedEvent)) {
            eventStore.save()
            _events.value = eventStore.getAllIncludingDeleted()
        }
    }

    /**
     * Add a vote from the network to the local store.
     */
    private fun addVote(vote: VoteRecord) {
        // Filter: skip votes from blocked peers
        if (prefs.isBlocked(vote.authorId)) {
            android.util.Log.d("P2pManager", "Vote FILTERED: blocked peer ${vote.authorId.take(8)}")
            return
        }
        if (!voteStore.upsert(vote)) return
        voteStore.save()
        _votes.value = voteStore.getAll()
        // Update myVotes if it's from this peer
        val myPeerId = node?.peerId()?.let(::otesAuthorId) ?: return
        if (vote.authorId == myPeerId) {
            _myVotes.value = voteStore.getMyVotes(myPeerId)
        }
    }

    /**
     * Vote on a convoy event.
     * Validates, signs, stores locally, and broadcasts to the network.
     */
    suspend fun vote(convoyId: String, vote: Int) {
        if (vote !in -1..1) return

        val sub = subscription ?: return
        if (prefs.nickname.value.isBlank()) {
            android.util.Log.w("P2pManager", "Nickname required before voting")
            return
        }
        val peerId = node?.peerId() ?: return
        val authorId = otesAuthorId(peerId)

        // Prevent self-vote
        val convoy = _events.value.find { it.id == convoyId }
        if (convoy != null && convoy.peerId == peerId) {
            android.util.Log.w("P2pManager", "Cannot vote on own convoy")
            return
        }
        // Prevent vote on deleted convoy
        if (convoy != null && convoy.deleted) {
            android.util.Log.w("P2pManager", "Cannot vote on deleted convoy")
            return
        }

        // Sign vote via FFI
        val dataDir = File(context.filesDir, "p2p").absolutePath
        val voteJson = try {
            val previous = voteStore.getVotesForConvoy(convoyId).find { it.authorId == authorId }
            signVote(
                dataDir,
                convoyId,
                vote,
                ((previous?.revision ?: 0) + 1).toULong(),
                previous?.createdAt
            )
        } catch (e: Exception) {
            android.util.Log.e("P2pManager", "Failed to sign vote: ${e.message}")
            return
        }

        val voteRecord = parseVoteRecord(voteJson)
        if (voteRecord == null) {
            android.util.Log.e("P2pManager", "Failed to parse signed vote")
            return
        }

        // Store locally
        voteStore.upsert(voteRecord)
        voteStore.save()
        _votes.value = voteStore.getAll()
        _myVotes.value = voteStore.getMyVotes(authorId)

        // Broadcast to network
        try {
            val envelope = buildCtesEnvelope(voteJson)
            sub.broadcast(envelope)
            android.util.Log.i("P2pManager", "Vote broadcasted: $vote for convoy $convoyId")
        } catch (e: Exception) {
            android.util.Log.e("P2pManager", "Failed to broadcast vote: ${e.message}")
        }
    }

    /**
     * Extract convoy ID from vote JSON without full parsing (for dedup).
     */
    private fun otesAuthorId(peerId: String): String {
        if (peerId.startsWith("ed25519:")) return peerId
        val bytes = peerId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val encoded = android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        return "ed25519:$encoded"
    }

    fun setNickname(value: String): Boolean {
        val nickname = value.trim()
        if (!prefs.saveNickname(nickname)) return false
        val sub = subscription ?: return true
        return publishLocalProfile(sub)
    }

    private fun publishLocalProfile(sub: GossipSubscriptionWrapper): Boolean {
        val nickname = prefs.nickname.value.trim()
        if (nickname.isEmpty()) return true
        val authorId = node?.peerId()?.let(::otesAuthorId) ?: return false
        val previous = profileStore.get(authorId)
        val profileJson = if (previous?.data?.nickname == nickname) {
            broadcastJson.encodeToString(ProfileRecord.serializer(), previous)
        } else try {
            signProfile(
                File(context.filesDir, "p2p").absolutePath,
                nickname,
                ((previous?.revision ?: 0) + 1).toULong(),
                previous?.createdAt
            )
        } catch (e: Exception) {
            return false
        }
        val profile = parseProfileRecord(profileJson) ?: return false
        if (previous?.signature != profile.signature) {
            profileStore.upsert(profile)
            if (!profileStore.save()) return false
            _profiles.value = profileStore.getAll()
        }
        return runCatching {
            val envelope = buildCtesEnvelope(profileJson)
            sub.broadcast(envelope)
        }.isSuccess
    }

    private fun purgeExpiredEvents() {
        val cutoff = kotlinx.datetime.Clock.System.now().epochSeconds - (3 * 86400)
        _events.update { current ->
            current.filter { it.schedule.meetingTimestamp >= cutoff }
        }
        eventStore.purgeExpired()
        eventStore.save()
    }

    /**
     * Get events for a specific date (start of day timestamp)
     */
    fun getEventsForDate(dayTimestamp: Long): List<ConvoyEvent> {
        val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
        val date = kotlinx.datetime.Instant.fromEpochSeconds(dayTimestamp)
            .toLocalDateTime(tz).date
        val dayStart = date.atStartOfDayIn(tz).epochSeconds
        val dayEnd = date.plus(1, kotlinx.datetime.DateTimeUnit.DAY)
            .atStartOfDayIn(tz).epochSeconds

        return filteredEvents().filter { event ->
            event.schedule.meetingTimestamp in dayStart until dayEnd
        }
    }

    /**
     * Get upcoming events from today for the next N days (excluding today)
     */
    fun getUpcomingEvents(days: Int = 7): List<ConvoyEvent> {
        val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
        val today = kotlinx.datetime.Clock.System.todayIn(tz)
        val tomorrow = today.plus(1, kotlinx.datetime.DateTimeUnit.DAY)
        val weekEnd = today.plus(days, kotlinx.datetime.DateTimeUnit.DAY)
        val startTimestamp = tomorrow.atStartOfDayIn(tz).epochSeconds
        val endTimestamp = weekEnd.atStartOfDayIn(tz).epochSeconds

        return filteredEvents().filter { event ->
            event.schedule.meetingTimestamp in startTimestamp until endTimestamp
        }.sortedBy { it.schedule.meetingTimestamp }
    }

    fun getAllEvents(): List<ConvoyEvent> = filteredEvents()

    /**
     * Get the local node's peer ID.
     */
    fun getMyPeerId(): String {
        return node?.peerId() ?: ""
    }

    private fun filteredEvents(): List<ConvoyEvent> {
        return _events.value.filter { event ->
            shouldDisplayEvent(event, prefs.blockedAuthors.value.keys, prefs.filteredLanguages.value)
        }
    }

    fun blockAuthor(peerId: String, nick: String) {
        prefs.blockAuthor(peerId, nick)
    }

    private fun applyBlacklist(data: String) {
        try {
            // Verify blacklist signature (matches desktop lib.rs:385-395)
            if (!verifyBlacklistSignature(data)) {
                android.util.Log.w("P2pManager", "Dropping blacklist with invalid signature")
                return
            }
            android.util.Log.i("P2pManager", "Ignoring shared blacklist gossip on mobile; only local blocks are applied")
        } catch (_: Exception) { /* malformed blacklist */ }
    }

    /**
     * Start periodic purge of expired events (every 1 hour, matches desktop).
     */
    private fun startPeriodicPurge() {
        scope.launch {
            while (isActive) {
                delay(PURGE_INTERVAL_MS)
                try {
                    eventStore.purgeExpired()
                    eventStore.save()
                    _events.value = eventStore.getAllIncludingDeleted()
                    android.util.Log.d("P2pManager", "Periodic purge done, ${eventStore.size()} events remaining")
                } catch (e: Exception) {
                    android.util.Log.e("P2pManager", "Periodic purge error: ${e.message}")
                }
            }
        }
    }

    /**
     * Evict oldest entries when dedup set exceeds capacity.
     */
    private fun trimSeenMessages() {
        if (seenMessages.size > MAX_SEEN_MESSAGES) {
            val toRemove = seenMessagesOrder.take(MAX_SEEN_MESSAGES / 2)
            toRemove.forEach { seenMessages.remove(it) }
            seenMessagesOrder.subList(0, MAX_SEEN_MESSAGES / 2).clear()
        }
    }

    /**
     * Re-broadcast all stored events and votes to connected peers.
     * Called when new peers connect to ensure they receive all known data.
     */
    private fun buildCtesEnvelope(documentJson: String): String {
        return buildJsonObject {
            put("protocol", "ctes-gossip/1")
            put("type", "document")
            put("document", Json.parseToJsonElement(documentJson))
        }.toString()
    }

    private suspend fun reBroadcastAll(sub: GossipSubscriptionWrapper) {
        val storedEvents = eventStore.getAllIncludingDeleted()
        android.util.Log.i("P2pManager", "Re-broadcasting ${storedEvents.size} events to new peer(s)...")
        var successCount = 0
        for (stored in storedEvents) {
            try {
                val envelope = broadcastStoredEvent(sub, stored)
                android.util.Log.d("P2pManager", "Broadcasting event ${stored.id} (${envelope.length} bytes)")
                successCount++
            } catch (e: Exception) {
                android.util.Log.e("P2pManager", "Re-broadcast failed for event ${stored.id}: ${e.message}")
            }
        }
        android.util.Log.i("P2pManager", "Event re-broadcast done: $successCount/${storedEvents.size} sent")

        // Re-broadcast votes for active convoys (last 3 days)
        val allVotes = voteStore.getAll()
        val now = kotlinx.datetime.Clock.System.now().epochSeconds
        val cutoff = now - (3 * 86400)
        val activeConvoyIds = storedEvents.filter { it.schedule.meetingTimestamp >= cutoff }.map { it.id }.toSet()
        val activeVotes = allVotes.filter { (convoyId, _) -> convoyId in activeConvoyIds }

        if (activeVotes.isNotEmpty()) {
            var voteSuccess = 0
            val totalVotes = activeVotes.values.sumOf { it.size }
            for ((_, voteList) in activeVotes) {
                for (vote in voteList) {
                    try {
                        val innerJson = broadcastJson.encodeToString(VoteRecord.serializer(), vote)
                        val envelope = buildCtesEnvelope(innerJson)
                        sub.broadcast(envelope)
                        voteSuccess++
                    } catch (e: Exception) {
                        android.util.Log.e("P2pManager", "Re-broadcast failed for vote: ${e.message}")
                    }
                }
            }
            android.util.Log.i("P2pManager", "Vote re-broadcast done: $voteSuccess/$totalVotes sent")
        }

        for (profile in profileStore.getAll().values) {
            val profileJson = broadcastJson.encodeToString(ProfileRecord.serializer(), profile)
            val envelope = buildCtesEnvelope(profileJson)
            runCatching { sub.broadcast(envelope) }
        }
    }

    /**
     * Extract convoy ID from JSON data without full parsing (for dedup).
     */
    private fun parseConvoyEventId(data: String): String {
        return try {
            val json = Json.parseToJsonElement(data).jsonObject
            json["id"]?.toString()?.trim('"') ?: data.hashCode().toString()
        } catch (_: Exception) {
            data.hashCode().toString()
        }
    }

    private fun broadcastStoredEvent(sub: GossipSubscriptionWrapper, stored: ConvoyEvent): String {
        return if (stored.deleted && stored.deleteSignature.isNotBlank()) {
            val signerId = stored.authorId.ifBlank { stored.peerId }
            val tombstone = buildJsonObject {
                put("specVersion", stored.specVersion)
                put("kind", "tombstone")
                put("eventId", stored.id)
                put("revision", stored.revision)
                put("authorId", signerId)
                put("createdAt", stored.createdAt)
                put("updatedAt", stored.updatedAt)
                put("signature", stored.deleteSignature)
            }
            val envelope = buildCtesEnvelope(tombstone.toString())
            sub.broadcast(envelope)
            envelope
        } else {
            val innerJson = broadcastJson.encodeToString(ConvoyEvent.serializer(), stored)
            val envelope = buildCtesEnvelope(innerJson)
            sub.broadcast(envelope)
            envelope
        }
    }

    /**
     * Stop the P2P node
     */
    fun stop() {
        starting.set(false)
        receiverJob?.cancel()
        receiverJob = null

        // Flush event store and vote store to disk before shutdown
        try {
            if (::eventStore.isInitialized) {
                eventStore.save()
            }
            if (::voteStore.isInitialized) {
                voteStore.save()
            }
        } catch (e: Exception) {
            android.util.Log.e("P2pManager", "Error saving stores: ${e.message}")
        }

        try {
            node?.shutdown()
        } catch (e: Exception) {
            android.util.Log.e("P2pManager", "Error closing node: ${e.message}", e)
        }
        node = null
        subscription = null
        _status.value = Status.OFFLINE
        _peerCount.value = 0
    }

    /**
     * Cleanup resources
     */
    fun destroy() {
        scope.cancel()
    }

    companion object {
        private const val PURGE_INTERVAL_MS = 3600_000L // 1 hour
        private const val MAX_SEEN_MESSAGES = 10_000

        /** Json config for gossip re-broadcast: encodeDefaults=true ensures all fields
         *  (schema, game, mode) are included; explicitNulls=false omits null flyer,
         *  matching Rust's skip_serializing_if behavior so signatures remain valid. */
        private val broadcastJson = Json {
            encodeDefaults = true
            explicitNulls = false
        }

        var nativeLoaded = false
        init {
            try {
                System.loadLibrary("convoyrun_mobile_ffi")
                nativeLoaded = true
                android.util.Log.i("P2pManager", "Native library loaded successfully")
            } catch (e: Exception) {
                android.util.Log.e("P2pManager", "Failed to load native library: ${e.message}", e)
            }
        }
    }
}

/**
 * JNI bridge to the Rust FFI
 * This class provides the JNI entry points for the Rust library
 */
object ConvoyRunP2p {
    @JvmStatic
    external fun installAndroidContext(context: Context)
}

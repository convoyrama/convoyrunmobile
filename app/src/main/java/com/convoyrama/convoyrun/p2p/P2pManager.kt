package com.convoyrama.convoyrun.p2p

import android.content.Context
import com.convoyrama.convoyrun.data.EventStore
import com.convoyrama.convoyrun.data.PreferencesManager
import com.convoyrama.convoyrun.model.*
import uniffi.convoyrun_mobile_ffi.P2pNodeWrapper
import uniffi.convoyrun_mobile_ffi.GossipSubscriptionWrapper
import uniffi.convoyrun_mobile_ffi.verifyConvoySignature
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

    private var node: P2pNodeWrapper? = null
    private var subscription: GossipSubscriptionWrapper? = null
    private var receiverJob: Job? = null
    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)

    // Persistent event store (disk-backed)
    private lateinit var eventStore: EventStore

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
            _events.value = eventStore.getAll()
            android.util.Log.i("P2pManager", "Loaded ${eventStore.size()} events from disk")

            // Initialize Android context (for DNS resolver)
            ConvoyRunP2p.installAndroidContext(context.applicationContext)

            // Create P2P node (sync function)
            android.util.Log.i("P2pManager", "Creating P2P node...")
            val nodeWrapper = createP2pNode(dataDir.absolutePath)
            node = nodeWrapper
            android.util.Log.i("P2pManager", "P2P node created, peerId: ${nodeWrapper.peerId()}")

            // Join the gossip topic
            android.util.Log.i("P2pManager", "Joining gossip topic...")
            val sub = nodeWrapper.joinTopic()
            subscription = sub
            android.util.Log.i("P2pManager", "Joined gossip topic successfully")

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
                            val storedEvents = eventStore.getAll()
                            android.util.Log.i("P2pManager", "Coming ONLINE: re-broadcasting ${storedEvents.size} events from store...")
                            var onlineSuccess = 0
                            for (stored in storedEvents) {
                                try {
                                    val innerJson = broadcastJson.encodeToString(ConvoyEvent.serializer(), stored)
                                    val envelope = buildJsonObject {
                                        put("type", "convoy")
                                        put("data", innerJson)
                                    }.toString()
                                    android.util.Log.d("P2pManager", "Online-broadcast event ${stored.id}")
                                    sub.broadcast(envelope)
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
                                android.util.Log.i("P2pManager", "Adding convoy event: '${convoyEvent.event.name}' (id=${convoyEvent.id}, peer=${convoyEvent.peerId.take(8)})")
                                addEvent(convoyEvent)
                            } else {
                                android.util.Log.e("P2pManager", "Failed to parse ConvoyEvent from data")
                            }
                        }
                        is GossipMessage.DeleteConvoy -> {
                            // Dedup check
                            val dedupKey = "delete:${message.convoyId}:${message.peerId}"
                            if (!seenMessages.add(dedupKey)) {
                                android.util.Log.d("P2pManager", "Skipping duplicate delete")
                                continue
                            }
                            seenMessagesOrder.add(dedupKey)
                            trimSeenMessages()

                            android.util.Log.i("P2pManager", "Received delete for convoy: ${message.convoyId}")
                            removeEvent(message.convoyId)
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
        if (prefs.isBlocked(event.peerId)) {
            android.util.Log.w("P2pManager", "Event FILTERED: blocked peer ${event.peerId.take(8)} '${event.event.name}'")
            return
        }
        if (!prefs.matchesLanguageFilter(event.event.languages)) {
            android.util.Log.w("P2pManager", "Event FILTERED: language mismatch. eventLangs=${event.event.languages}, filter=${prefs.filteredLanguages.value} '${event.event.name}'")
            return
        }

        // Validate field lengths (matches desktop lib.rs:276-278)
        if (event.nickname.length > 64) {
            android.util.Log.w("P2pManager", "Event REJECTED: nickname too long (${event.nickname.length})")
            return
        }
        if (event.event.name.length > 200) {
            android.util.Log.w("P2pManager", "Event REJECTED: name too long (${event.event.name.length})")
            return
        }
        if (event.event.description.length > 5000) {
            android.util.Log.w("P2pManager", "Event REJECTED: description too long (${event.event.description.length})")
            return
        }
        if (event.event.server.length > 100) {
            android.util.Log.w("P2pManager", "Event REJECTED: server too long (${event.event.server.length})")
            return
        }

        // Validate publish window (within 90 days ahead)
        val now = kotlinx.datetime.Clock.System.now().epochSeconds
        if (event.schedule.meetingTimestamp > now + (90 * 86400)) {
            android.util.Log.w("P2pManager", "Event REJECTED: meeting too far in future")
            return
        }

        // Validate retention (not older than 3 days, matches desktop)
        if (event.schedule.meetingTimestamp < now - (3 * 86400)) {
            android.util.Log.w("P2pManager", "Event REJECTED: already expired")
            return
        }

        _events.update { current ->
            val mutable = current.toMutableList()
            val existingIndex = mutable.indexOfFirst { it.id == event.id }
            if (existingIndex >= 0) {
                mutable[existingIndex] = event
            } else {
                mutable.add(event)
            }
            mutable.sortBy { it.schedule.meetingTimestamp }
            mutable
        }

        // Persist to disk
        eventStore.upsert(event)
        eventStore.save()

        val totalEvents = _events.value.size
        android.util.Log.i("P2pManager", "Event stored. Total events: $totalEvents (on disk: ${eventStore.size()})")

        // Periodic purge every 50 events
        if (_events.value.size % 50 == 0) {
            purgeExpiredEvents()
        }
    }

    private fun removeEvent(convoyId: String) {
        _events.update { current ->
            current.filterNot { it.id == convoyId }
        }
        eventStore.remove(convoyId)
        eventStore.save()
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

    private fun filteredEvents(): List<ConvoyEvent> {
        val blocked = prefs.blockedAuthors.value.keys
        return _events.value.filter { event ->
            event.peerId !in blocked && prefs.matchesLanguageFilter(event.event.languages)
        }
    }

    fun blockAuthor(peerId: String, nick: String) {
        prefs.blockAuthor(peerId, nick)
        _events.update { current ->
            current.filterNot { it.peerId == peerId }
        }
        eventStore.removeByPeer(setOf(peerId))
        eventStore.save()
    }

    private fun applyBlacklist(data: String) {
        try {
            val json = Json.parseToJsonElement(data).jsonObject
            val peerIds = json["peerIds"]?.jsonArray
            val blockedSet = mutableSetOf<String>()
            peerIds?.forEach { entry ->
                val pid = entry.jsonPrimitive.content
                if (!prefs.isBlocked(pid)) {
                    prefs.blockAuthor(pid, pid.take(8))
                    blockedSet.add(pid)
                }
            }
            val blocked = prefs.blockedAuthors.value.keys
            _events.update { current ->
                current.filterNot { it.peerId in blocked }
            }
            if (blockedSet.isNotEmpty()) {
                eventStore.removeByPeer(blockedSet)
                eventStore.save()
            }
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
                    _events.value = eventStore.getAll()
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
     * Re-broadcast all stored events to connected peers.
     * Called when new peers connect to ensure they receive all known events.
     */
    private suspend fun reBroadcastAll(sub: GossipSubscriptionWrapper) {
        val storedEvents = eventStore.getAll()
        if (storedEvents.isEmpty()) {
            android.util.Log.w("P2pManager", "reBroadcastAll: eventStore is empty, nothing to broadcast")
            return
        }
        android.util.Log.i("P2pManager", "Re-broadcasting ${storedEvents.size} events to new peer(s)...")
        var successCount = 0
        for (stored in storedEvents) {
            try {
                val innerJson = broadcastJson.encodeToString(ConvoyEvent.serializer(), stored)
                val envelope = buildJsonObject {
                    put("type", "convoy")
                    put("data", innerJson)
                }.toString()
                android.util.Log.d("P2pManager", "Broadcasting event ${stored.id} (${envelope.length} bytes)")
                sub.broadcast(envelope)
                successCount++
            } catch (e: Exception) {
                android.util.Log.e("P2pManager", "Re-broadcast failed for event ${stored.id}: ${e.message}")
            }
        }
        android.util.Log.i("P2pManager", "Re-broadcast done: $successCount/${storedEvents.size} sent")
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

    /**
     * Stop the P2P node
     */
    fun stop() {
        starting.set(false)
        receiverJob?.cancel()
        receiverJob = null

        // Flush event store to disk before shutdown
        try {
            if (::eventStore.isInitialized) {
                eventStore.save()
            }
        } catch (e: Exception) {
            android.util.Log.e("P2pManager", "Error saving event store: ${e.message}")
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

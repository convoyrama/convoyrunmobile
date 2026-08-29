package com.convoyrun.mobile.p2p

import android.content.Context
import com.convoyrun.mobile.data.PreferencesManager
import com.convoyrun.mobile.model.*
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
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private var starting = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initialize and start the P2P node
     */
    suspend fun start() {
        if (node != null || starting) return
        starting = true

        try {
            // Get data directory
            val dataDir = File(context.filesDir, "p2p")
            dataDir.mkdirs()

            // Initialize Android context (for DNS resolver)
            ConvoyRunP2p.installAndroidContext(context.applicationContext)

            // Create P2P node (factory function via UniFFI)
            val nodeWrapper = createP2pNode(dataDir.absolutePath)
            node = nodeWrapper

            // Join the gossip topic
            val sub = nodeWrapper.joinTopic()
            subscription = sub

            _status.value = Status.SEARCHING

            // Start receiving events
            startReceivingEvents()

            println("[P2P] Manager started, peerId: ${nodeWrapper.peerId()}")
        } catch (e: Exception) {
            println("[P2P] Failed to start: ${e.message}")
            _status.value = Status.OFFLINE
        } finally {
            starting = false
        }
    }

    /**
     * Start receiving events from the gossip network
     */
    private fun startReceivingEvents() {
        receiverJob = scope.launch {
            val sub = subscription ?: return@launch

            while (isActive) {
                try {
                    // Update peer count
                    _peerCount.value = sub.peerCount().toInt()

                    // Update status based on peer count
                    _status.value = if (_peerCount.value > 0) {
                        Status.ONLINE
                    } else {
                        Status.SEARCHING
                    }

                    // Wait for next event
                    val event = sub.nextEvent() ?: continue

                    // Parse the gossip message
                    val message = parseGossipMessage(event.content()) ?: continue

                    when (message) {
                        is GossipMessage.Convoy -> {
                            if (!verifyConvoySignature(message.data)) {
                                println("[P2P] Dropping event with invalid signature")
                                continue
                            }
                            val convoyEvent = parseConvoyEvent(message.data)
                            if (convoyEvent != null) {
                                addEvent(convoyEvent)
                            }
                        }
                        is GossipMessage.DeleteConvoy -> {
                            removeEvent(message.convoyId)
                        }
                        is GossipMessage.Blacklist -> {
                            applyBlacklist(message.data)
                        }
                        else -> { /* Read-only: ignore vote, channel, trustlist */ }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("[P2P] Error receiving event: ${e.message}")
                    delay(1000) // Back off on error
                }
            }
        }
    }

    /**
     * Add an event to the local cache
     */
    private fun addEvent(event: ConvoyEvent) {
        if (prefs.isBlocked(event.peerId)) return
        if (!prefs.matchesLanguageFilter(event.event.languages)) return

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

        // Periodic purge every 50 events
        if (_events.value.size % 50 == 0) {
            purgeExpiredEvents()
        }
    }

    private fun removeEvent(convoyId: String) {
        _events.update { current ->
            current.filterNot { it.id == convoyId }
        }
    }

    private fun purgeExpiredEvents() {
        val cutoff = kotlinx.datetime.Clock.System.now().epochSeconds - (3 * 86400)
        _events.update { current ->
            current.filter { it.schedule.meetingTimestamp >= cutoff }
        }
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
    }

    private fun applyBlacklist(data: String) {
        try {
            val json = Json.parseToJsonElement(data).jsonObject
            val peerIds = json["peerIds"]?.jsonArray
            peerIds?.forEach { entry ->
                val pid = entry.jsonPrimitive.content
                if (!prefs.isBlocked(pid)) {
                    prefs.blockAuthor(pid, pid.take(8))
                }
            }
            val blocked = prefs.blockedAuthors.value.keys
            _events.update { current ->
                current.filterNot { it.peerId in blocked }
            }
        } catch (_: Exception) { /* malformed blacklist */ }
    }

    /**
     * Stop the P2P node
     */
    suspend fun stop() {
        starting = false
        receiverJob?.cancel()
        receiverJob?.join()
        receiverJob = null

        try {
            node?.shutdown()
        } catch (e: Exception) {
            println("[P2P] Error closing node: ${e.message}")
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

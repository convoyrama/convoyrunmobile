package com.convoyrun.mobile.p2p

import android.content.Context
import com.convoyrun.mobile.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * P2P Manager - wraps the Rust FFI layer for Kotlin
 *
 * This class manages the P2P node lifecycle and provides
 * a clean Kotlin API for the UI layer.
 */
class P2pManager(private val context: Context) {

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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initialize and start the P2P node
     */
    suspend fun start() {
        if (node != null) return

        try {
            // Get data directory
            val dataDir = File(context.filesDir, "p2p")
            dataDir.mkdirs()

            // Initialize Android context (for DNS resolver)
            ConvoyRunP2p.installAndroidContext(context.applicationContext)

            // Create P2P node
            val nodeWrapper = P2pNodeWrapper(dataDir.absolutePath)
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

                    // Only process convoy events (read-only mode)
                    when (message) {
                        is GossipMessage.Convoy -> {
                            val convoyEvent = parseConvoyEvent(message.data)
                            if (convoyEvent != null) {
                                addEvent(convoyEvent)
                            }
                        }
                        // Ignore other message types (vote, delete, channel, etc.)
                        else -> { /* Read-only: ignore */ }
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
        val currentEvents = _events.value.toMutableList()

        // Check if event already exists (by ID)
        val existingIndex = currentEvents.indexOfFirst { it.id == event.id }
        if (existingIndex >= 0) {
            // Update existing event
            currentEvents[existingIndex] = event
        } else {
            // Add new event
            currentEvents.add(event)
        }

        // Sort by meeting time
        currentEvents.sortBy { it.schedule.meetingTimestamp }

        _events.value = currentEvents
    }

    /**
     * Get events for a specific date (start of day timestamp)
     */
    fun getEventsForDate(dayTimestamp: Long): List<ConvoyEvent> {
        val dayStart = dayTimestamp
        val dayEnd = dayStart + 86400 // 24 hours in seconds

        return _events.value.filter { event ->
            event.schedule.meetingTimestamp in dayStart until dayEnd
        }
    }

    /**
     * Get all events
     */
    fun getAllEvents(): List<ConvoyEvent> = _events.value

    /**
     * Stop the P2P node
     */
    fun stop() {
        receiverJob?.cancel()
        receiverJob = null

        scope.launch {
            try {
                node?.close()
            } catch (e: Exception) {
                println("[P2P] Error closing node: ${e.message}")
            }
            node = null
            subscription = null
            _status.value = Status.OFFLINE
            _peerCount.value = 0
        }
    }

    /**
     * Cleanup resources
     */
    fun destroy() {
        stop()
        scope.cancel()
    }

    companion object {
        // JNI initialization
        init {
            System.loadLibrary("convoyrun_mobile_ffi")
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

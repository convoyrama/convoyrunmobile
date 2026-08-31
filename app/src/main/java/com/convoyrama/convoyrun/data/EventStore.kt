package com.convoyrama.convoyrun.data

import com.convoyrama.convoyrun.model.ConvoyEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent event store — JSON file on disk with atomic writes.
 *
 * Follows the same pattern as the desktop ConvoyStore (convoy.rs):
 * - Single JSON file: event_store.json
 * - Atomic writes: write to .tmp, then rename
 * - Retention: 24 hours after meeting timestamp
 * - Upsert by convoy ID
 */
class EventStore(private val dataDir: File) {

    private val storeFile = File(dataDir, "event_store.json")
    private val tmpFile = File(dataDir, "event_store.json.tmp")

    // In-memory cache keyed by convoy ID
    private val events = LinkedHashMap<String, ConvoyEvent>()
    private val dirty = AtomicBoolean(false)

    init {
        dataDir.mkdirs()
    }

    /**
     * Load events from disk into memory.
     * Returns the loaded events. Silently returns empty on first run or corrupt file.
     */
    fun load(): List<ConvoyEvent> {
        synchronized(events) {
            events.clear()
            if (!storeFile.exists()) return emptyList()
            return try {
                val json = storeFile.readText()
                val map: Map<String, ConvoyEvent> = lenientJson.decodeFromString(json)
                events.putAll(map)
                events.values.sortedBy { it.schedule.meetingTimestamp }
            } catch (e: Exception) {
                android.util.Log.e("EventStore", "Failed to load, starting fresh: ${e.message}")
                events.clear()
                emptyList()
            }
        }
    }

    /**
     * Persist current in-memory state to disk atomically.
     */
    fun save() {
        synchronized(events) {
            if (!dirty.get()) return
            try {
                val json = lenientJson.encodeToString(events)
                tmpFile.writeText(json)
                tmpFile.renameTo(storeFile)
                dirty.set(false)
            } catch (e: Exception) {
                android.util.Log.e("EventStore", "Failed to save: ${e.message}")
            }
        }
    }

    /**
     * Insert or replace an event by its convoy ID.
     */
    fun upsert(event: ConvoyEvent) {
        synchronized(events) {
            events[event.id] = event
            dirty.set(true)
        }
    }

    /**
     * Remove an event by convoy ID.
     * Returns true if the event existed.
     */
    fun remove(convoyId: String): Boolean {
        synchronized(events) {
            val removed = events.remove(convoyId) != null
            if (removed) dirty.set(true)
            return removed
        }
    }

    /**
     * Remove all events from blocked peers.
     */
    fun removeByPeer(peerIds: Set<String>) {
        synchronized(events) {
            val before = events.size
            events.entries.removeIf { it.value.peerId in peerIds }
            if (events.size != before) dirty.set(true)
        }
    }

    /**
     * Remove events older than 24 hours after their meeting timestamp.
     */
    fun purgeExpired() {
        val now = kotlinx.datetime.Clock.System.now().epochSeconds
        val cutoff = now - (RETENTION_DAYS * 86400)
        synchronized(events) {
            val before = events.size
            events.entries.removeIf { it.value.schedule.meetingTimestamp < cutoff }
            if (events.size != before) {
                dirty.set(true)
                android.util.Log.i("EventStore", "Purged ${before - events.size} expired events")
            }
        }
    }

    /**
     * Get all stored events, sorted by meeting timestamp.
     */
    fun getAll(): List<ConvoyEvent> {
        synchronized(events) {
            return events.values.sortedBy { it.schedule.meetingTimestamp }
        }
    }

    /**
     * Get the number of stored events.
     */
    fun size(): Int {
        synchronized(events) {
            return events.size
        }
    }

    companion object {
        private const val RETENTION_DAYS = 1L
        private val lenientJson = Json { ignoreUnknownKeys = true }
    }
}

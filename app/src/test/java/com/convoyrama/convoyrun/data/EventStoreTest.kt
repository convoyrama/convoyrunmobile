package com.convoyrama.convoyrun.data

import com.convoyrama.convoyrun.model.ConvoyEvent
import com.convoyrama.convoyrun.model.EventData
import com.convoyrama.convoyrun.model.Schedule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class EventStoreTest {

    @Test
    fun tombstoneWithHigherRevisionReplacesLiveEvent() {
        val store = EventStore(Files.createTempDirectory("convoyrun-event-store").toFile())
        val live = convoyEvent(revision = 1, deleted = false, signature = "aaa")
        val tombstone = convoyEvent(revision = 2, deleted = true, signature = "bbb")

        assertTrue(store.upsert(live))
        assertTrue(store.upsert(tombstone))

        val stored = store.getAllIncludingDeleted().single()
        assertTrue(stored.deleted)
        assertTrue(stored.revision == 2L)
    }

    @Test
    fun staleLiveEventDoesNotReplaceStoredTombstone() {
        val store = EventStore(Files.createTempDirectory("convoyrun-event-store").toFile())
        val tombstone = convoyEvent(revision = 2, deleted = true, signature = "bbb")
        val staleLive = convoyEvent(revision = 1, deleted = false, signature = "zzz")

        assertTrue(store.upsert(tombstone))
        assertFalse(store.upsert(staleLive))

        val stored = store.getAllIncludingDeleted().single()
        assertTrue(stored.deleted)
        assertTrue(stored.revision == 2L)
    }

    @Test
    fun liveEditThenDeleteConvergesAfterOfflineReconnect() {
        val store = EventStore(Files.createTempDirectory("convoyrun-event-store").toFile())
        val original = convoyEvent(revision = 1, deleted = false, signature = "aaa")
        val edited = convoyEvent(revision = 2, deleted = false, signature = "bbb")
        val tombstone = convoyEvent(revision = 3, deleted = true, signature = "ccc")
        val staleEdit = convoyEvent(revision = 2, deleted = false, signature = "zzz")

        assertTrue(store.upsert(original))
        assertTrue(store.upsert(edited))
        assertTrue(store.upsert(tombstone))
        assertFalse(store.upsert(staleEdit))

        val stored = store.getAllIncludingDeleted().single()
        assertTrue(stored.deleted)
        assertTrue(stored.revision == 3L)
        assertTrue(stored.signature == "ccc")
    }

    private fun convoyEvent(
        revision: Long,
        deleted: Boolean,
        signature: String
    ) = ConvoyEvent(
        id = "convoy-test",
        peerId = "peer-a",
        revision = revision,
        nickname = "Driver",
        publishedAt = 1_700_000_000,
        event = EventData(name = "Ruta estable"),
        schedule = Schedule(
            meetingTimestamp = 1_700_000_100,
            ianaTimeZone = "UTC"
        ),
        signature = signature,
        deleted = deleted
    )
}

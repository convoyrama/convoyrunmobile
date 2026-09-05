package com.convoyrama.convoyrun.data

import com.convoyrama.convoyrun.model.ProfileData
import com.convoyrama.convoyrun.model.ProfileRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProfileStoreTest {

    @Test
    fun newestProfileWinsAcrossRestart() {
        val dataDir = Files.createTempDirectory("convoyrun-profile-store").toFile()
        val authorId = "ed25519:6kpsY-KcUgq-9VB7Ey7F-ZVHdq6-vnuSQh7qaRRG0iw"
        val older = profile(authorId, 1, "Ruta Ñ")
        val newer = profile(authorId, 2, "Ruta Ñ 2")

        val store = ProfileStore(dataDir)
        assertTrue(store.upsert(older))
        assertTrue(store.upsert(newer))
        assertFalse(store.upsert(older))
        assertTrue(store.save())

        val reloaded = ProfileStore(dataDir)
        val loaded = reloaded.load()
        assertEquals("Ruta Ñ 2", loaded[authorId]?.data?.nickname)
        assertEquals(2L, loaded[authorId]?.revision)
    }

    @Test
    fun staleProfileIsIgnoredAfterNewerOneIsStored() {
        val dataDir = Files.createTempDirectory("convoyrun-profile-store-stale").toFile()
        val authorId = "ed25519:6kpsY-KcUgq-9VB7Ey7F-ZVHdq6-vnuSQh7qaRRG0iw"
        val older = profile(authorId, 1, "Ruta Ñ")
        val newer = profile(authorId, 2, "Ruta Ñ 2")

        val store = ProfileStore(dataDir)
        assertTrue(store.upsert(newer))
        assertFalse(store.upsert(older))
        assertEquals("Ruta Ñ 2", store.get(authorId)?.data?.nickname)
        assertEquals(2L, store.get(authorId)?.revision)
    }

    private fun profile(authorId: String, revision: Long, nickname: String) = ProfileRecord(
        revision = revision,
        authorId = authorId,
        createdAt = "2026-09-04T15:00:00Z",
        updatedAt = if (revision == 1L) "2026-09-04T15:00:00Z" else "2026-09-04T15:10:00Z",
        data = ProfileData(nickname = nickname),
        signature = "sig-$revision-$nickname"
    )
}

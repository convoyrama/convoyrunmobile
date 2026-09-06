package com.convoyrama.convoyrun.data

import com.convoyrama.convoyrun.model.ProfileRecord
import com.convoyrama.convoyrun.model.winsOver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ProfileStore(dataDir: File) {
    private val storeFile = File(dataDir, "profile_store.json")
    private val tmpFile = File(dataDir, "profile_store.json.tmp")
    private val profiles = LinkedHashMap<String, ProfileRecord>()
    private var dirty = false

    fun load(): Map<String, ProfileRecord> = synchronized(profiles) {
        profiles.clear()
        if (storeFile.exists()) runCatching {
            profiles.putAll(json.decodeFromString<Map<String, ProfileRecord>>(storeFile.readText()))
        }.onFailure {
            quarantineCorruptStore(storeFile, "profile-store")
            profiles.clear()
        }
        profiles.toMap()
    }

    fun upsert(profile: ProfileRecord): Boolean = synchronized(profiles) {
        val current = profiles[profile.authorId]
        if (current != null && !profile.winsOver(current)) return false
        profiles[profile.authorId] = profile
        dirty = true
        true
    }

    fun get(authorId: String): ProfileRecord? = synchronized(profiles) { profiles[authorId] }

    fun getAll(): Map<String, ProfileRecord> = synchronized(profiles) { profiles.toMap() }

    fun save(): Boolean = synchronized(profiles) {
        if (!dirty) return true
        runCatching {
            tmpFile.writeText(json.encodeToString(profiles))
            replaceStoreFile(tmpFile, storeFile)
            dirty = false
        }.isSuccess
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = false }
    }
}

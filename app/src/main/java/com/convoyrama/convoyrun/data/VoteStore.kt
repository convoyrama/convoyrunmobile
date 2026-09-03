package com.convoyrama.convoyrun.data

import com.convoyrama.convoyrun.model.VoteRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent vote store — JSON file on disk with atomic writes.
 *
 * Follows the same pattern as EventStore:
 * - Single JSON file: vote_store.json
 * - Atomic writes: write to .tmp, then rename
 * - Upsert by convoyId + voterPeerId (one vote per user per convoy)
 */
class VoteStore(private val dataDir: File) {

    private val storeFile = File(dataDir, "vote_store.json")
    private val tmpFile = File(dataDir, "vote_store.json.tmp")

    // convoyId -> (voterPeerId -> VoteRecord)
    private val votes = LinkedHashMap<String, LinkedHashMap<String, VoteRecord>>()
    private val dirty = AtomicBoolean(false)

    init {
        dataDir.mkdirs()
    }

    /**
     * Load votes from disk into memory.
     * Returns the loaded votes. Silently returns empty on first run or corrupt file.
     */
    fun load(): Map<String, List<VoteRecord>> {
        synchronized(votes) {
            votes.clear()
            if (!storeFile.exists()) return emptyMap()
            return try {
                val json = storeFile.readText()
                val outer: Map<String, Map<String, VoteRecord>> = lenientJson.decodeFromString(json)
                for ((convoyId, inner) in outer) {
                    votes[convoyId] = LinkedHashMap(inner)
                }
                toListMap()
            } catch (e: Exception) {
                android.util.Log.e("VoteStore", "Failed to load, starting fresh: ${e.message}")
                votes.clear()
                emptyMap()
            }
        }
    }

    /**
     * Persist current in-memory state to disk atomically.
     */
    fun save() {
        synchronized(votes) {
            if (!dirty.get()) return
            try {
                val json = lenientJson.encodeToString(votes)
                tmpFile.writeText(json)
                require(tmpFile.renameTo(storeFile)) { "Failed to rename temp votes file" }
                dirty.set(false)
            } catch (e: Exception) {
                android.util.Log.e("VoteStore", "Failed to save: ${e.message}")
            }
        }
    }

    /**
     * Insert or replace a vote (one vote per voter per convoy).
     */
    fun upsert(vote: VoteRecord) {
        synchronized(votes) {
            val convoyVotes = votes.getOrPut(vote.convoyId) { LinkedHashMap() }
            convoyVotes[vote.voterPeerId] = vote
            dirty.set(true)
        }
    }

    /**
     * Get all votes for a specific convoy.
     */
    fun getVotesForConvoy(convoyId: String): List<VoteRecord> {
        synchronized(votes) {
            return votes[convoyId]?.values?.toList() ?: emptyList()
        }
    }

    /**
     * Get the user's own votes (convoyId -> vote direction).
     */
    fun getMyVotes(peerId: String): Map<String, Int> {
        synchronized(votes) {
            val result = mutableMapOf<String, Int>()
            for ((convoyId, convoyVotes) in votes) {
                val myVote = convoyVotes[peerId]
                if (myVote != null) {
                    result[convoyId] = myVote.vote
                }
            }
            return result
        }
    }

    /**
     * Compute the net score for a convoy (+1 count - -1 count).
     */
    fun computeScore(convoyId: String): Int {
        synchronized(votes) {
            return votes[convoyId]?.values?.sumOf { it.vote } ?: 0
        }
    }

    /**
     * Get vote counts (upvotes, downvotes) for a convoy.
     */
    fun getVoteCounts(convoyId: String): Pair<Int, Int> {
        synchronized(votes) {
            val convoyVotes = votes[convoyId]?.values ?: return Pair(0, 0)
            val up = convoyVotes.count { it.vote == 1 }
            val down = convoyVotes.count { it.vote == -1 }
            return Pair(up, down)
        }
    }

    /**
     * Get all votes as a map.
     */
    fun getAll(): Map<String, List<VoteRecord>> {
        synchronized(votes) {
            return toListMap()
        }
    }

    /**
     * Remove votes for convoys older than maxAgeDays.
     */
    fun purgeExpired(maxAgeDays: Long = 90) {
        val now = kotlinx.datetime.Clock.System.now().epochSeconds
        val cutoff = now - (maxAgeDays * 86400)
        synchronized(votes) {
            val before = votes.size
            // We don't have meeting timestamps here, so we rely on ts field
            votes.entries.removeIf { (_, convoyVotes) ->
                convoyVotes.values.all { it.ts < cutoff }
            }
            if (votes.size != before) {
                dirty.set(true)
                android.util.Log.i("VoteStore", "Purged ${before - votes.size} expired vote groups")
            }
        }
    }

    private fun toListMap(): Map<String, List<VoteRecord>> {
        return votes.mapValues { (_, inner) -> inner.values.toList() }
    }

    companion object {
        private val lenientJson = Json { ignoreUnknownKeys = true }
    }
}

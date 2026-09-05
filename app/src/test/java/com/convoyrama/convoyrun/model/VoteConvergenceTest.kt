package com.convoyrama.convoyrun.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class VoteConvergenceTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun newestRevisionWinsInEveryDeliveryOrder() {
        val up = json.decodeFromString<VoteRecord>(
            checkNotNull(javaClass.getResource("/ctes-v1/vote-up-r1.json")).readText()
        )
        val down = json.decodeFromString<VoteRecord>(
            checkNotNull(javaClass.getResource("/ctes-v1/vote-down-r2.json")).readText()
        )

        for (order in listOf(listOf(up, down), listOf(down, up))) {
            var current: VoteRecord? = null
            for (candidate in order) {
                if (current == null || candidate.winsOver(current)) current = candidate
            }
            assertEquals(2, current?.revision)
            assertEquals(-1, current?.vote)
        }
    }
}

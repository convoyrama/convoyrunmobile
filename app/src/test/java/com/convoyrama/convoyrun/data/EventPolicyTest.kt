package com.convoyrama.convoyrun.data

import com.convoyrama.convoyrun.model.EventDocument
import com.convoyrama.convoyrun.model.EventData
import com.convoyrama.convoyrun.model.Schedule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPolicyTest {

    @Test
    fun validEventIsStoredEvenWhenHiddenByLocalFilters() {
        val event = convoyEvent(peerId = "peer-a", languages = listOf("en"))

        assertTrue(isValidIncomingEvent(event, nowEpochSeconds = 1_700_000_000))
        assertFalse(
            shouldDisplayEvent(
                event = event,
                blockedAuthors = setOf("peer-a"),
                filteredLanguages = setOf("es")
            )
        )
    }

    @Test
    fun emptyLanguageFilterDoesNotHideValidEvent() {
        val event = convoyEvent(peerId = "peer-b", languages = listOf("pt"))

        assertTrue(
            shouldDisplayEvent(
                event = event,
                blockedAuthors = emptySet(),
                filteredLanguages = emptySet()
            )
        )
    }

    @Test
    fun deletedEventRemainsValidForStorageButIsNotDisplayed() {
        val event = convoyEvent(peerId = "peer-c", languages = listOf("en"), deleted = true)

        assertTrue(isValidIncomingEvent(event, nowEpochSeconds = 1_700_000_000))
        assertFalse(
            shouldDisplayEvent(
                event = event,
                blockedAuthors = emptySet(),
                filteredLanguages = emptySet()
            )
        )
    }

    private fun convoyEvent(
        peerId: String,
        languages: List<String>,
        deleted: Boolean = false
    ) = EventDocument(
        id = "convoy-${peerId.takeLast(2)}-${languages.joinToString("-")}",
        peerId = peerId,
        nickname = "Driver",
        publishedAt = 1_700_000_000,
        event = EventData(
            name = "Ruta estable",
            description = "Evento de prueba",
            languages = languages
        ),
        schedule = Schedule(
            meetingTimestamp = 1_700_000_100,
            ianaTimeZone = "UTC"
        ),
        deleted = deleted
    )
}

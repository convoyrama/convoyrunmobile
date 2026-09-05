package com.convoyrama.convoyrun.data

import com.convoyrama.convoyrun.model.ConvoyEvent

fun matchesLanguageFilter(filteredLanguages: Set<String>, eventLanguages: List<String>): Boolean {
    if (filteredLanguages.isEmpty()) return true
    if (eventLanguages.isEmpty()) return true
    return eventLanguages.any { it in filteredLanguages }
}

fun shouldDisplayEvent(
    event: ConvoyEvent,
    blockedAuthors: Set<String>,
    filteredLanguages: Set<String>
): Boolean {
    if (event.deleted) return false
    if (event.peerId in blockedAuthors) return false
    return matchesLanguageFilter(filteredLanguages, listOf(event.event.language).filter { it.isNotBlank() })
}

fun isValidIncomingEvent(event: ConvoyEvent, nowEpochSeconds: Long): Boolean {
    if (event.nickname.length > 64) return false
    if (event.event.title.length > 200) return false
    if (event.event.description.length > 5000) return false
    if (event.event.server.length > 100) return false

    if (event.deleted) return true

    if (event.schedule.meetingTimestamp > nowEpochSeconds + (90 * 86400)) return false
    if (event.schedule.meetingTimestamp < nowEpochSeconds - (3 * 86400)) return false
    return true
}

package com.convoyrama.convoyrun.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class VisualTokensTest {
    @Test
    fun voteSymbolsAndEventPaletteStayAligned() {
        assertEquals("▲", VoteSymbolUp)
        assertEquals("▼", VoteSymbolDown)
        assertEquals(0xFF00AAFF.toInt(), Accent.value.toInt())
        assertEquals(0xFFEF5350.toInt(), EventTypeCompetition.value.toInt())
        assertEquals(0xFF78909C.toInt(), EventTypeOther.value.toInt())
    }
}

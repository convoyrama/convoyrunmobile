package com.convoyrama.convoyrun.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class VisualTokensTest {
    @Test
    fun visualTokensStayAlignedWithDesktop() {
        assertEquals("▲", VoteSymbolUp)
        assertEquals("▼", VoteSymbolDown)
        assertEquals(0xFF00AAFF.toInt(), Accent.value.toInt())
        assertEquals(0xFF1A1A2E.toInt(), BgPrimary.value.toInt())
        assertEquals(0xFF16213E.toInt(), BgSecondary.value.toInt())
        assertEquals(0xFF252525.toInt(), BgCard.value.toInt())
        assertEquals(0xFFFFFFFF.toInt(), TextPrimary.value.toInt())
        assertEquals(0xFFAAAAAA.toInt(), TextSecondary.value.toInt())
        assertEquals(0xFF666666.toInt(), TextMuted.value.toInt())
        assertEquals(0xFF444444.toInt(), Divider.value.toInt())
        assertEquals(0xFF444444.toInt(), Border.value.toInt())
        assertEquals(0xFFEF5350.toInt(), EventTypeCompetition.value.toInt())
        assertEquals(0xFF78909C.toInt(), EventTypeOther.value.toInt())
        assertEquals(0xFF4ADE80.toInt(), StatusOnline.value.toInt())
        assertEquals(0xFFFACC15.toInt(), StatusSearching.value.toInt())
        assertEquals(0xFF666666.toInt(), StatusOffline.value.toInt())
    }
}

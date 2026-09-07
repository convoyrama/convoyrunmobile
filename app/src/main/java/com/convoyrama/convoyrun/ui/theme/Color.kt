package com.convoyrama.convoyrun.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

const val DEFAULT_THEME_NAME = "ocean"

data class ThemePalette(
    val accent: Color,
    val accentDark: Color,
    val accentLight: Color,
    val bgPrimary: Color,
    val bgSecondary: Color,
    val bgCard: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val divider: Color,
    val border: Color,
    val isLight: Boolean,
)

private val OceanPalette = ThemePalette(
    accent = Color(0xFF00AAFF),
    accentDark = Color(0xFF0088CC),
    accentLight = Color(0xFF33BBFF),
    bgPrimary = Color(0xFF202B39),
    bgSecondary = Color(0xFF273244),
    bgCard = Color(0xFF2F3B4E),
    textPrimary = Color(0xFFF7FBFF),
    textSecondary = Color(0xFFC7D0DB),
    textMuted = Color(0xFF90A0AF),
    divider = Color(0xFF405166),
    border = Color(0xFF405166),
    isLight = false,
)

private val GraphitePalette = ThemePalette(
    accent = Color(0xFF75D1FF),
    accentDark = Color(0xFF4CAEDB),
    accentLight = Color(0xFF9BE2FF),
    bgPrimary = Color(0xFF171C22),
    bgSecondary = Color(0xFF1D232B),
    bgCard = Color(0xFF252D37),
    textPrimary = Color(0xFFF3F6FA),
    textSecondary = Color(0xFFC4CAD3),
    textMuted = Color(0xFF7D8692),
    divider = Color(0xFF313844),
    border = Color(0xFF313844),
    isLight = false,
)

private val DawnPalette = ThemePalette(
    accent = Color(0xFFCE6D3C),
    accentDark = Color(0xFFB15528),
    accentLight = Color(0xFFE49264),
    bgPrimary = Color(0xFFF6EFE7),
    bgSecondary = Color(0xFFFFF8F2),
    bgCard = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF201915),
    textSecondary = Color(0xFF5B5148),
    textMuted = Color(0xFF8A7F75),
    divider = Color(0xFFE2D5C8),
    border = Color(0xFFDDCFBF),
    isLight = true,
)

private val PaperPalette = ThemePalette(
    accent = Color(0xFF355CFF),
    accentDark = Color(0xFF2748D4),
    accentLight = Color(0xFF6B86FF),
    bgPrimary = Color(0xFFF8F9FB),
    bgSecondary = Color(0xFFF1F4F8),
    bgCard = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF151A23),
    textSecondary = Color(0xFF4F596B),
    textMuted = Color(0xFF7A8597),
    divider = Color(0xFFD9E0EA),
    border = Color(0xFFD0D9E4),
    isLight = true,
)

private fun paletteFor(name: String?): ThemePalette = when (name?.lowercase()) {
    "graphite" -> GraphitePalette
    "dawn" -> DawnPalette
    "paper" -> PaperPalette
    else -> OceanPalette
}

fun themePalette(name: String?): ThemePalette = paletteFor(name)

object ThemeTokens {
    var palette by mutableStateOf(OceanPalette)
        private set

    fun apply(name: String?) {
        palette = paletteFor(name)
    }
}

fun setThemePalette(name: String?) {
    ThemeTokens.apply(name)
}

val Accent: Color get() = ThemeTokens.palette.accent
val AccentDark: Color get() = ThemeTokens.palette.accentDark
val AccentLight: Color get() = ThemeTokens.palette.accentLight
val BgPrimary: Color get() = ThemeTokens.palette.bgPrimary
val BgSecondary: Color get() = ThemeTokens.palette.bgSecondary
val BgCard: Color get() = ThemeTokens.palette.bgCard
val TextPrimary: Color get() = ThemeTokens.palette.textPrimary
val TextSecondary: Color get() = ThemeTokens.palette.textSecondary
val TextMuted: Color get() = ThemeTokens.palette.textMuted
val Divider: Color get() = ThemeTokens.palette.divider
val Border: Color get() = ThemeTokens.palette.border

// Event type colors stay constant across themes.
val EventTypeConvoy = Color(0xFF00AAFF)
val EventTypeTruckShow = Color(0xFFFF9800)
val EventTypeExploration = Color(0xFF4CAF50)
val EventTypeCompetition = Color(0xFFEF5350)
val EventTypeOther = Color(0xFF78909C)

// Game colors stay constant across themes.
val GameATS = Color(0xFF2196F3)
val GameETS2 = Color(0xFFFF9800)

// Connection colors stay constant across themes.
val StatusOnline = Color(0xFF4ADE80)
val StatusSearching = Color(0xFFFACC15)
val StatusOffline = Color(0xFF666666)

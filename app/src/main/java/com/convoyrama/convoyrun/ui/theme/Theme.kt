package com.convoyrama.convoyrun.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color

private fun ThemePalette.toColorScheme() = if (isLight) {
    lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = accentLight,
        onPrimaryContainer = bgPrimary,
        secondary = bgSecondary,
        onSecondary = textPrimary,
        secondaryContainer = bgCard,
        onSecondaryContainer = textPrimary,
        tertiary = accentDark,
        onTertiary = Color.White,
        background = bgPrimary,
        onBackground = textPrimary,
        surface = bgSecondary,
        onSurface = textPrimary,
        surfaceVariant = bgCard,
        onSurfaceVariant = textSecondary,
        outline = divider,
        outlineVariant = border,
    )
} else {
    darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = accentDark,
        onPrimaryContainer = Color.White,
        secondary = bgSecondary,
        onSecondary = textPrimary,
        secondaryContainer = bgCard,
        onSecondaryContainer = textPrimary,
        tertiary = accentLight,
        onTertiary = Color.White,
        background = bgPrimary,
        onBackground = textPrimary,
        surface = bgSecondary,
        onSurface = textPrimary,
        surfaceVariant = bgCard,
        onSurfaceVariant = textSecondary,
        outline = divider,
        outlineVariant = border,
    )
}

@Composable
fun ConvoyRunTheme(
    themeName: String = DEFAULT_THEME_NAME,
    content: @Composable () -> Unit
) {
    val palette = themePalette(themeName)
    SideEffect { setThemePalette(themeName) }

    MaterialTheme(
        colorScheme = palette.toColorScheme(),
        typography = ConvoyRunTypography,
        content = content
    )
}

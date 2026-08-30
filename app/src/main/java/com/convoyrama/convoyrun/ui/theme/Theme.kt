package com.convoyrama.convoyrun.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentDark,
    onPrimaryContainer = Color.White,
    secondary = BgSecondary,
    onSecondary = TextPrimary,
    secondaryContainer = BgCard,
    onSecondaryContainer = TextPrimary,
    tertiary = AccentLight,
    onTertiary = Color.White,
    background = BgPrimary,
    onBackground = TextPrimary,
    surface = BgSecondary,
    onSurface = TextPrimary,
    surfaceVariant = BgCard,
    onSurfaceVariant = TextSecondary,
    outline = Divider,
    outlineVariant = Border
)

private val LightColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentLight,
    onPrimaryContainer = BgPrimary,
    secondary = BgSecondary,
    onSecondary = TextPrimary,
    secondaryContainer = BgCard,
    onSecondaryContainer = TextPrimary,
    tertiary = AccentDark,
    onTertiary = Color.White,
    background = BgPrimary,
    onBackground = TextPrimary,
    surface = BgSecondary,
    onSurface = TextPrimary,
    surfaceVariant = BgCard,
    onSurfaceVariant = TextSecondary,
    outline = Divider,
    outlineVariant = Border
)

/**
 * ConvoyRun theme
 *
 * Uses the same color palette as the desktop app.
 * Currently only supports dark theme.
 */
@Composable
fun ConvoyRunTheme(
    darkTheme: Boolean = true, // Always dark for ConvoyRun
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        DarkColorScheme
    } else {
        LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ConvoyRunTypography,
        content = content
    )
}

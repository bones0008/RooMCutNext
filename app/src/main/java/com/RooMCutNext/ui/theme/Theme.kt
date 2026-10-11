package com.RooMCutNext.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// RooMCutNext dark professional palette (CapCut-inspired).
val BgColor = Color(0xFF0B0D11)
val SurfaceColor = Color(0xFF14181F)
val SurfaceVariantColor = Color(0xFF1C222B)
val OutlineColor = Color(0xFF2A323E)
val AccentColor = Color(0xFF37E6C5)
val AccentDimColor = Color(0xFF1E5E54)
val OnAccentColor = Color(0xFF06231E)
val TextPrimaryColor = Color(0xFFEAF0F4)
val TextSecondaryColor = Color(0xFF97A3B0)
val DangerColor = Color(0xFFFF5C5C)
val WarnColor = Color(0xFFFFC24B)
val ClipBgColor = Color(0xFF1A2028)
val ClipBorderColor = Color(0xFF2A323E)
val RulerTextColor = Color(0xFF77828E)
val PlayheadColor = Color(0xFFFFF3F3)

private val DarkScheme = darkColorScheme(
    primary = AccentColor,
    onPrimary = OnAccentColor,
    primaryContainer = AccentDimColor,
    onPrimaryContainer = AccentColor,
    secondary = WarnColor,
    onSecondary = Color(0xFF332600),
    background = BgColor,
    onBackground = TextPrimaryColor,
    surface = SurfaceColor,
    onSurface = TextPrimaryColor,
    surfaceVariant = SurfaceVariantColor,
    onSurfaceVariant = TextSecondaryColor,
    outline = OutlineColor,
    outlineVariant = OutlineColor,
    error = DangerColor,
    onError = Color(0xFF3B0000),
)

@Composable
fun RooMCutNextTheme(content: @Composable () -> Unit) {
    // The editor is intentionally always-dark for comfortable long sessions.
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = RooMCutNextTypography,
        content = content,
    )
}

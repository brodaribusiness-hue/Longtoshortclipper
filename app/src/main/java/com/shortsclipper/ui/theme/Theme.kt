package com.shortsclipper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Professional dark video-editor palette.
val BgPrimary = Color(0xFF0F0F10)
val BgSecondary = Color(0xFF18181B)
val BgControl = Color(0xFF222225)
val Accent = Color(0xFFFF6B2C)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFA1A1AA)
val Success = Color(0xFF22C55E)
val Warning = Color(0xFFF59E0B)
val Error = Color(0xFFEF4444)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = BgControl,
    onSecondary = TextPrimary,
    background = BgPrimary,
    onBackground = TextPrimary,
    surface = BgSecondary,
    onSurface = TextPrimary,
    surfaceVariant = BgControl,
    onSurfaceVariant = TextSecondary,
    error = Error,
    outline = BgControl,
)

@Composable
fun ShortsClipperTheme(content: @Composable () -> Unit) {
    // The editor is intentionally dark-only for a professional video tool.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}

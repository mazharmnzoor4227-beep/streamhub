package com.streamhub.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val StreamHubColors = darkColorScheme(
    primary = Red,
    onPrimary = TextPrimary,
    secondary = TextMuted,
    onSecondary = Night,
    background = Night,
    onBackground = TextPrimary,
    surface = SurfaceNight,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextMuted,
    outline = DividerColor
)

@Composable
fun StreamHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StreamHubColors,
        content = content
    )
}

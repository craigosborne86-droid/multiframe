package dev.multiframe.camera.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MultiframeColors = darkColorScheme(
    primary = Color(0xFF4A9EFF),
    onPrimary = Color(0xFF06121F),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE8F1FA),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8F1FA),
)

@Composable
fun MultiframeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MultiframeColors, content = content)
}

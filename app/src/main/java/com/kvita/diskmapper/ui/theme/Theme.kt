package com.kvita.diskmapper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Always-dark compact theme: this is a disk tree tool, not a Material showcase.
private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Ink,
    secondary = AccentAmber,
    tertiary = AccentAmber,
    background = Ink,
    onBackground = TextMain,
    surface = Ink,
    onSurface = TextMain,
    surfaceVariant = InkVariant,
    onSurfaceVariant = TextDim,
    surfaceContainer = InkSurface,
    surfaceContainerHigh = InkSurface,
    error = DangerRed
)

@Composable
fun DiskMapperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography,
        content = content
    )
}

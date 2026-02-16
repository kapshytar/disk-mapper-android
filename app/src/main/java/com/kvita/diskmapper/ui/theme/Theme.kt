package com.kvita.diskmapper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = DeepBlue,
    secondary = Steel,
    tertiary = Mint,
    background = Sand,
    error = Orange
)

private val DarkColors = darkColorScheme(
    primary = Sand,
    secondary = Mint,
    tertiary = Orange
)

@Composable
fun DiskMapperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography,
        content = content
    )
}


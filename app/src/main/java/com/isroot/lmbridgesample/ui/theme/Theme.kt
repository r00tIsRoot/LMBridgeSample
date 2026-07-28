package com.isroot.lmbridgesample.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Brand = Color(0xFF4C6FFF)

private val LightColors = lightColorScheme(
    primary = Brand,
    secondary = Color(0xFF5A6472),
)

private val DarkColors = darkColorScheme(
    primary = Brand,
    secondary = Color(0xFFAAB4C2),
)

@Composable
fun LMBridgeSampleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

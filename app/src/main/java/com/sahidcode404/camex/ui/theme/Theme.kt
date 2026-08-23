package com.sahidcode404.camex.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ECAFF),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF004B70),
    background = Color(0xFF101114),
    surface = Color(0xFF191C20),
    surfaceVariant = Color(0xFF3F484F),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006493),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCBE6FF),
    background = Color(0xFFF8F9FD),
    surface = Color(0xFFF8F9FD),
)

@Composable
fun CameraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

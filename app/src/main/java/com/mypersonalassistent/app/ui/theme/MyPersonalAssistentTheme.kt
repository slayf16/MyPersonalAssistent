package com.mypersonalassistent.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF4355A6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE4FF),
    onPrimaryContainer = Color(0xFF0A1A62),
    secondary = Color(0xFF5A5E75),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E5F8),
    onSecondaryContainer = Color(0xFF171B2C),
    background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFAF8FF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF757780),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF10225C),
    primaryContainer = Color(0xFF293D7F),
    onPrimaryContainer = Color(0xFFDEE5FF),
    secondary = Color(0xFFC2C5DD),
    onSecondary = Color(0xFF2B2F42),
    secondaryContainer = Color(0xFF41455A),
    onSecondaryContainer = Color(0xFFE0E4FB),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C6D0),
    outline = Color(0xFF8F909A),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
)

/** Shared Material 3 visual language for the application, following the system theme. */
@Composable
fun MyPersonalAssistentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}

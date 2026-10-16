package com.lifevault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF006B62),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF2E3),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF5B5D8A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1DFFF),
    onSecondaryContainer = Color(0xFF171944),
    tertiary = Color(0xFF865400),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF291800),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FAF8),
    surface = Color(0xFFF6FAF8),
    surfaceVariant = Color(0xFFDAE5E1),
    surfaceContainer = Color(0xFFE7F0EC),
    surfaceContainerHigh = Color(0xFFE1EAE6),
    surfaceContainerLow = Color(0xFFF0F6F3),
    onSurface = Color(0xFF171D1B),
    onSurfaceVariant = Color(0xFF3F4A46),
    outline = Color(0xFF6F7A75),
    outlineVariant = Color(0xFFBECAC5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF80D5C6),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF9CF2E3),
    secondary = Color(0xFFC2C2FF),
    onSecondary = Color(0xFF2B2D5B),
    secondaryContainer = Color(0xFF414374),
    onSecondaryContainer = Color(0xFFE1DFFF),
    tertiary = Color(0xFFFFBA51),
    tertiaryContainer = Color(0xFF623F00),
    onTertiaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1513),
    surface = Color(0xFF0F1513),
    surfaceVariant = Color(0xFF3F4946),
    surfaceContainer = Color(0xFF19211F),
    surfaceContainerHigh = Color(0xFF232C29),
    surfaceContainerLow = Color(0xFF151C1A),
    onSurface = Color(0xFFDDE5E1),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF89948F),
    outlineVariant = Color(0xFF3F4946),
)

/** Status colours used for attendance and warnings, tuned per theme for contrast. */
@Immutable
data class StatusColors(
    val present: Color,
    val absent: Color,
    val pending: Color,
    val cancelled: Color,
    val holiday: Color,
    val warning: Color,
    val good: Color,
)

private val LightStatus = StatusColors(
    present = Color(0xFF2E7D32), absent = Color(0xFFC62828), pending = Color(0xFF8D6E00),
    cancelled = Color(0xFF6D6D6D), holiday = Color(0xFF6A1B9A), warning = Color(0xFFB3261E), good = Color(0xFF2E7D32),
)
private val DarkStatus = StatusColors(
    present = Color(0xFF81C784), absent = Color(0xFFEF9A9A), pending = Color(0xFFFFD54F),
    cancelled = Color(0xFFBDBDBD), holiday = Color(0xFFCE93D8), warning = Color(0xFFFFB4AB), good = Color(0xFF81C784),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun LifeVaultTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dark) Dark else Light
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

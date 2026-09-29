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
    primary = Color(0xFF1F4E79),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF3D6A5A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC0ECDB),
    onSecondaryContainer = Color(0xFF002118),
    tertiary = Color(0xFF7A5A1F),
    tertiaryContainer = Color(0xFFFFDEA8),
    onTertiaryContainer = Color(0xFF271900),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFF8F9FC),
    surfaceVariant = Color(0xFFDFE2EB),
    surfaceContainer = Color(0xFFECEEF3),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerLow = Color(0xFFF2F3F8),
    onSurface = Color(0xFF191C20),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA2C9FE),
    onPrimary = Color(0xFF00315B),
    primaryContainer = Color(0xFF0E4A7F),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFA4D0BF),
    onSecondary = Color(0xFF0A372B),
    secondaryContainer = Color(0xFF244E41),
    onSecondaryContainer = Color(0xFFC0ECDB),
    tertiary = Color(0xFFEBC17C),
    tertiaryContainer = Color(0xFF5E420A),
    onTertiaryContainer = Color(0xFFFFDEA8),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF43474E),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerLow = Color(0xFF191C20),
    onSurface = Color(0xFFE1E2E8),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
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

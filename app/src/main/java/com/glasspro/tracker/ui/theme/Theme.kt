package com.glasspro.tracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = SlateDark,
    primaryContainer = ElectricCyanBg,
    onPrimaryContainer = ElectricCyan,
    secondary = NeonGreen,
    onSecondary = SlateDark,
    secondaryContainer = NeonGreenBg,
    onSecondaryContainer = NeonGreen,
    tertiary = NeonAmber,
    onTertiary = SlateDark,
    background = SlateDark,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateCard,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    error = NeonRed,
    onError = TextPrimary
)

@Composable
fun GlassProTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}

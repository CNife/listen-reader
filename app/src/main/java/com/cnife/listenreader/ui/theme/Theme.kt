package com.cnife.listenreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = LightLrColors.primary,
    onPrimary = LightLrColors.onPrimary,
    primaryContainer = LightLrColors.currentBg,
    onPrimaryContainer = LightLrColors.ink,
    secondary = LightLrColors.night,
    onSecondary = LightLrColors.onNight,
    secondaryContainer = LightLrColors.sunken,
    onSecondaryContainer = LightLrColors.ink,
    tertiary = LightLrColors.link,
    onTertiary = LightLrColors.surface,
    tertiaryContainer = LightLrColors.sunken,
    onTertiaryContainer = LightLrColors.ink,
    error = LightLrColors.ink,
    onError = LightLrColors.surface,
    errorContainer = LightLrColors.sunken,
    onErrorContainer = LightLrColors.ink,
    background = LightLrColors.canvas,
    onBackground = LightLrColors.ink,
    surface = LightLrColors.surface,
    onSurface = LightLrColors.ink,
    surfaceVariant = LightLrColors.sunken,
    onSurfaceVariant = LightLrColors.muted,
    outline = LightLrColors.hairline,
    outlineVariant = LightLrColors.hairline,
    inverseSurface = LightLrColors.night,
    inverseOnSurface = LightLrColors.onNight,
    inversePrimary = LightLrColors.link,
    surfaceTint = Color.Transparent,
    scrim = LightLrColors.scrim,
)

private val DarkColors = darkColorScheme(
    primary = DarkLrColors.primary,
    onPrimary = DarkLrColors.onPrimary,
    primaryContainer = DarkLrColors.currentBg,
    onPrimaryContainer = DarkLrColors.ink,
    secondary = DarkLrColors.night,
    onSecondary = DarkLrColors.onNight,
    secondaryContainer = DarkLrColors.sunken,
    onSecondaryContainer = DarkLrColors.ink,
    tertiary = DarkLrColors.link,
    onTertiary = DarkLrColors.surface,
    tertiaryContainer = DarkLrColors.sunken,
    onTertiaryContainer = DarkLrColors.ink,
    error = DarkLrColors.ink,
    onError = DarkLrColors.surface,
    errorContainer = DarkLrColors.sunken,
    onErrorContainer = DarkLrColors.ink,
    background = DarkLrColors.canvas,
    onBackground = DarkLrColors.ink,
    surface = DarkLrColors.surface,
    onSurface = DarkLrColors.ink,
    surfaceVariant = DarkLrColors.sunken,
    onSurfaceVariant = DarkLrColors.muted,
    outline = DarkLrColors.hairline,
    outlineVariant = DarkLrColors.hairline,
    inverseSurface = DarkLrColors.night,
    inverseOnSurface = DarkLrColors.onNight,
    inversePrimary = DarkLrColors.link,
    surfaceTint = Color.Transparent,
    scrim = DarkLrColors.scrim,
)

@Composable
fun ListenReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalLrColors provides if (darkTheme) DarkLrColors else LightLrColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = ListenReaderTypography,
            shapes = ListenReaderShapes,
            content = content,
        )
    }
}

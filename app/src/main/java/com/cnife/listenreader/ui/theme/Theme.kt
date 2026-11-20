package com.cnife.listenreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightOnAccent,
    primaryContainer = LightCardSurface,
    onPrimaryContainer = LightInk,
    secondary = LightInk2,
    onSecondary = LightPaper,
    secondaryContainer = LightSunken,
    onSecondaryContainer = LightInk,
    tertiary = LightInk3,
    onTertiary = LightPaper,
    tertiaryContainer = LightSunken,
    onTertiaryContainer = LightInk,
    // 错误不另立色板：状态词与正文同级，靠文案说明。
    error = LightInk,
    onError = LightCardSurface,
    errorContainer = LightSunken,
    onErrorContainer = LightInk,
    background = LightPaper,
    onBackground = LightInk,
    surface = LightPaper,
    onSurface = LightInk,
    surfaceVariant = LightSunken,
    onSurfaceVariant = LightInk2,
    outline = LightRule,
    outlineVariant = LightRule,
    inverseSurface = LightInk,
    inverseOnSurface = LightPaper,
    inversePrimary = DarkAccent,
    surfaceTint = Color.Transparent,
    scrim = LightScrim,
)

private val DarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkCardSurface,
    onPrimaryContainer = DarkInk,
    secondary = DarkInk2,
    onSecondary = DarkPaper,
    secondaryContainer = DarkSunken,
    onSecondaryContainer = DarkInk,
    tertiary = DarkInk3,
    onTertiary = DarkPaper,
    tertiaryContainer = DarkSunken,
    onTertiaryContainer = DarkInk,
    error = DarkInk,
    onError = DarkCardSurface,
    errorContainer = DarkSunken,
    onErrorContainer = DarkInk,
    background = DarkPaper,
    onBackground = DarkInk,
    surface = DarkPaper,
    onSurface = DarkInk,
    surfaceVariant = DarkSunken,
    onSurfaceVariant = DarkInk2,
    outline = DarkRule,
    outlineVariant = DarkRule,
    inverseSurface = DarkInk,
    inverseOnSurface = DarkPaper,
    inversePrimary = LightAccent,
    surfaceTint = Color.Transparent,
    scrim = DarkScrim,
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

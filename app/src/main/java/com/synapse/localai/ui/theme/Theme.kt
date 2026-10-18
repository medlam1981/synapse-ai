package com.synapse.localai.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val ColorScheme.surfaceContainerLowest: Color
    @Composable get() = if (background.luminance() < 0.5f) Color(0xFF141418) else Color(0xFFF8F8F9)

val ColorScheme.surfaceContainerLow: Color
    @Composable get() = if (background.luminance() < 0.5f) NightSurface else DaySurface

val ColorScheme.surfaceContainer: Color
    @Composable get() = if (background.luminance() < 0.5f) NightSurfaceContainer else DaySurfaceContainer

val ColorScheme.surfaceContainerHigh: Color
    @Composable get() = if (background.luminance() < 0.5f) NightSurfaceVariant else DaySurfaceVariant

val ColorScheme.surfaceContainerHighest: Color
    @Composable get() = if (background.luminance() < 0.5f) Color(0xFF303037) else Color(0xFFD4D4D8)

private fun colorScheme(darkTheme: Boolean): ColorScheme {
    return if (darkTheme) {
        darkColorScheme(
            primary = NightPrimary,
            onPrimary = NightOnPrimary,
            primaryContainer = NightPrimaryContainer,
            onPrimaryContainer = NightOnPrimaryContainer,
            secondary = NightSecondary,
            onSecondary = NightOnSecondary,
            tertiary = NightTertiary,
            onTertiary = NightOnTertiary,
            background = NightBackground,
            onBackground = NightOnBackground,
            surface = NightSurface,
            onSurface = NightOnSurface,
            surfaceVariant = NightSurfaceVariant,
            onSurfaceVariant = NightOnSurfaceVariant,
            outline = NightOutline,
            outlineVariant = NightOutlineVariant,
            error = NightError,
            onError = NightOnError,
            inverseSurface = DayBackground,
            inverseOnSurface = DayOnBackground,
            inversePrimary = DayPrimary
        )
    } else {
        lightColorScheme(
            primary = DayPrimary,
            onPrimary = DayOnPrimary,
            primaryContainer = DayPrimaryContainer,
            onPrimaryContainer = DayOnPrimaryContainer,
            secondary = DaySecondary,
            onSecondary = DayOnSecondary,
            tertiary = DayTertiary,
            onTertiary = DayOnTertiary,
            background = DayBackground,
            onBackground = DayOnBackground,
            surface = DaySurface,
            onSurface = DayOnSurface,
            surfaceVariant = DaySurfaceVariant,
            onSurfaceVariant = DayOnSurfaceVariant,
            outline = DayOutline,
            outlineVariant = DayOutlineVariant,
            error = DayError,
            onError = DayOnError,
            inverseSurface = NightSurface,
            inverseOnSurface = NightOnSurface,
            inversePrimary = NightPrimary
        )
    }
}

@Composable
fun AtlasAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = remember(darkTheme) { colorScheme(darkTheme) }
    val bgArgb = scheme.background.toArgb()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                @Suppress("DEPRECATION")
                window.statusBarColor = bgArgb
                @Suppress("DEPRECATION")
                window.navigationBarColor = bgArgb
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
}

@Composable
fun SynapseAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) = AtlasAiTheme(darkTheme = darkTheme, content = content)

private fun Color.luminance(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue

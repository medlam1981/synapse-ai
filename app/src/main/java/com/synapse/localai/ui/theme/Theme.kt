package com.synapse.localai.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// =========================================================================
// Material 3 ColorScheme Extension Properties
// Provides surfaceContainer tiers for both themes cleanly
// =========================================================================
val ColorScheme.surfaceContainerLowest: Color
    @Composable get() = if (background.luminance() < 0.5f)
        Color(0xFF080810) else Color(0xFFF0F4FF)

val ColorScheme.surfaceContainerLow: Color
    @Composable get() = if (background.luminance() < 0.5f)
        NightSurface else DaySurface

val ColorScheme.surfaceContainer: Color
    @Composable get() = if (background.luminance() < 0.5f)
        NightSurfaceContainer else DaySurfaceContainer

val ColorScheme.surfaceContainerHigh: Color
    @Composable get() = if (background.luminance() < 0.5f)
        NightSurfaceVariant else DaySurfaceVariant

val ColorScheme.surfaceContainerHighest: Color
    @Composable get() = if (background.luminance() < 0.5f)
        Color(0xFF26263E) else Color(0xFFDDE3F4)

// =========================================================================
// NIGHT THEME — "Atlas Deep Space"
// Background: #0A0A12 (Near-black cosmic)  |  Primary: #00E5FF (Electric Cyan)
// =========================================================================
private val AtlasNightColorScheme = darkColorScheme(
    primary               = NightPrimary,
    onPrimary             = NightOnPrimary,
    primaryContainer      = NightPrimaryContainer,
    onPrimaryContainer    = NightOnPrimaryContainer,
    secondary             = NightSecondary,
    onSecondary           = NightOnSecondary,
    secondaryContainer    = Color(0xFF2A1A4E),
    onSecondaryContainer  = Color(0xFFD8B4FE),
    tertiary              = NightTertiary,
    onTertiary            = Color(0xFFFFFFFF),
    tertiaryContainer     = Color(0xFF4A0018),
    onTertiaryContainer   = Color(0xFFFFB3C0),
    background            = NightBackground,
    onBackground          = NightOnBackground,
    surface               = NightSurface,
    onSurface             = NightOnSurface,
    surfaceVariant        = NightSurfaceVariant,
    onSurfaceVariant      = NightOnSurfaceVariant,
    outline               = NightOutline,
    outlineVariant        = NightOutlineVariant,
    error                 = NightError,
    onError               = Color(0xFFFFFFFF),
    errorContainer        = Color(0xFF4A0000),
    onErrorContainer      = Color(0xFFFFB3B3),
    inverseSurface        = Color(0xFFE8EAF6),
    inverseOnSurface      = Color(0xFF0A0A12),
    inversePrimary        = Color(0xFF006B80),
    scrim                 = Color(0xCC000000),
)

// =========================================================================
// DAY THEME — "Atlas Crystal White"
// Background: #FFFFFF (Pure White)  |  Primary: #2563EB (Modern Indigo Blue)
// =========================================================================
private val AtlasDayColorScheme = lightColorScheme(
    primary               = DayPrimary,
    onPrimary             = DayOnPrimary,
    primaryContainer      = DayPrimaryContainer,
    onPrimaryContainer    = DayOnPrimaryContainer,
    secondary             = DaySecondary,
    onSecondary           = DayOnSecondary,
    secondaryContainer    = Color(0xFFEDE9FE),
    onSecondaryContainer  = Color(0xFF4C1D95),
    tertiary              = DayTertiary,
    onTertiary            = Color(0xFFFFFFFF),
    tertiaryContainer     = Color(0xFFFEE2E2),
    onTertiaryContainer   = Color(0xFF7F1D1D),
    background            = DayBackground,
    onBackground          = DayOnBackground,
    surface               = DaySurface,
    onSurface             = DayOnSurface,
    surfaceVariant        = DaySurfaceVariant,
    onSurfaceVariant      = DayOnSurfaceVariant,
    outline               = DayOutline,
    outlineVariant        = DayOutlineVariant,
    error                 = DayError,
    onError               = Color(0xFFFFFFFF),
    errorContainer        = Color(0xFFFEE2E2),
    onErrorContainer      = Color(0xFF7F1D1D),
    inverseSurface        = Color(0xFF1C1C2E),
    inverseOnSurface      = Color(0xFFE8EAF6),
    inversePrimary        = Color(0xFF93C5FD),
    scrim                 = Color(0x80000000),
)

// =========================================================================
// AtlasAiTheme — Primary Dynamic Theme
// Automatically switches between Night and Day based on system setting
// =========================================================================
@Composable
fun AtlasAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) AtlasNightColorScheme else AtlasDayColorScheme
    val bgArgb = colorScheme.background.toArgb()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// =========================================================================
// SynapseAITheme — Alias for backward compatibility → delegates to AtlasAiTheme
// =========================================================================
@Composable
fun SynapseAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) = AtlasAiTheme(darkTheme = darkTheme, content = content)

// Helper: calculate perceived luminance of a Color
private fun Color.luminance(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue

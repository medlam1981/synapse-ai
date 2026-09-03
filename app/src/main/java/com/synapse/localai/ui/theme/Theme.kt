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
// Universal Semantic Material 3 Container Extensions for Fixed Dual-Theme
// =========================================================================
// =========================================================================
// Universal Semantic Material 3 Container Extensions for Fixed Dual-Theme
// =========================================================================
val ColorScheme.surfaceContainerLowest: Color
    @Composable
    get() = if (isSystemInDarkTheme()) Color(0xFF0B0F17) else Color(0xFFF4F6F9)

val ColorScheme.surfaceContainerLow: Color
    @Composable
    get() = if (isSystemInDarkTheme()) Color(0xFF111827) else Color(0xFFFFFFFF)

val ColorScheme.surfaceContainer: Color
    @Composable
    get() = if (isSystemInDarkTheme()) Color(0xFF162032) else Color(0xFFFFFFFF)

val ColorScheme.surfaceContainerHigh: Color
    @Composable
    get() = if (isSystemInDarkTheme()) Color(0xFF1F2B3E) else Color(0xFFEDF2F7)

val ColorScheme.surfaceContainerHighest: Color
    @Composable
    get() = if (isSystemInDarkTheme()) Color(0xFF29384E) else Color(0xFFE2E8F0)

// =========================================================================
// 1. Dark Theme Palette (Modern Cyber Obsidian)
// =========================================================================
private val DarkColorScheme = darkColorScheme(
    primary = ObsidianPrimary,
    secondary = ObsidianSecondary,
    tertiary = ObsidianTertiary,
    background = ObsidianBackground,
    surface = ObsidianBackground,
    surfaceVariant = ObsidianSurface,
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondaryContainer = Color(0xFF312E81),
    onSecondaryContainer = Color(0xFFEDE9FE),
    tertiaryContainer = Color(0xFF1E1B4B),
    onTertiaryContainer = Color(0xFFE0E7FF),
    onPrimary = Color(0xFF0B0F17),
    onSecondary = Color(0xFF0B0F17),
    onTertiary = Color(0xFF0B0F17),
    onBackground = ObsidianOnSurface,
    onSurface = ObsidianOnSurface,
    onSurfaceVariant = ObsidianOnSurfaceVariant,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineVariant,
    error = ObsidianError
)

// =========================================================================
// 2. Light Theme Palette (Modern Porcelain Minimalist)
// =========================================================================
private val LightColorScheme = lightColorScheme(
    primary = PorcelainPrimary,
    secondary = PorcelainSecondary,
    tertiary = PorcelainTertiary,
    background = PorcelainBackground,
    surface = PorcelainBackground,
    surfaceVariant = Color(0xFFEDF2F7),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E40AF),
    secondaryContainer = Color(0xFFEEF2FF),
    onSecondaryContainer = Color(0xFF3730A3),
    tertiaryContainer = Color(0xFFF3E8FF),
    onTertiaryContainer = Color(0xFF581C87),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = PorcelainOnSurface,
    onSurface = PorcelainOnSurface,
    onSurfaceVariant = PorcelainOnSurfaceVariant,
    outline = PorcelainOutline,
    outlineVariant = PorcelainOutlineVariant,
    error = PorcelainError
)

/**
 * Deterministic Dual-Theme Architecture:
 * Completely decoupled from Android OS wallpaper extraction APIs.
 * Operates strictly with handcrafted Dark and Light palettes.
 */
@Composable
fun SynapseAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Deprecated: dynamic extraction completely decoupled
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

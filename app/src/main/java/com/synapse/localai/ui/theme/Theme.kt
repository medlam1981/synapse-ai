package com.synapse.localai.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// =========================================================================
// Universal Semantic Material 3 Container Extensions for Dynamic Color
// =========================================================================
val ColorScheme.surfaceContainerLowest: Color
    @Composable
    get() = if (isSystemInDarkTheme()) surface.copy(alpha = 0.85f) else Color(0xFFFFFFFF)

val ColorScheme.surfaceContainerLow: Color
    @Composable
    get() = if (isSystemInDarkTheme()) surfaceVariant.copy(alpha = 0.40f) else Color(0xFFF8FAFC)

val ColorScheme.surfaceContainer: Color
    @Composable
    get() = if (isSystemInDarkTheme()) surfaceVariant.copy(alpha = 0.60f) else Color(0xFFF1F5F9)

val ColorScheme.surfaceContainerHigh: Color
    @Composable
    get() = if (isSystemInDarkTheme()) surfaceVariant.copy(alpha = 0.80f) else Color(0xFFE2E8F0)

val ColorScheme.surfaceContainerHighest: Color
    @Composable
    get() = if (isSystemInDarkTheme()) surfaceVariant else Color(0xFFCBD5E1)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    secondary = DarkSecondary,
    tertiary = DarkAccent,
    background = Color(0xFF0A0E14),
    surface = Color(0xFF0F141C),
    surfaceVariant = Color(0xFF1B222D),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondaryContainer = Color(0xFF4C1D95),
    onSecondaryContainer = Color(0xFFEDE9FE),
    tertiaryContainer = Color(0xFF1E1B4B),
    onTertiaryContainer = Color(0xFFE0E7FF),
    onPrimary = Color(0xFF0A0E14),
    onSecondary = Color(0xFF0A0E14),
    onTertiary = Color(0xFF0A0E14),
    onBackground = DarkOnSurface,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    secondary = LightSecondary,
    tertiary = LightAccent,
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFFE2E8F0),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF312E81),
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF4C1D95),
    tertiaryContainer = Color(0xFFE0E7FF),
    onTertiaryContainer = Color(0xFF1E1B4B),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError
)

@Composable
fun SynapseAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
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

package com.synapse.localai.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val ColorScheme.surfaceContainerLowest: Color
    @Composable get() = tonalSurface(NightSurfaceContainerLowest, DaySurfaceContainerLowest)

val ColorScheme.surfaceContainerLow: Color
    @Composable get() = tonalSurface(NightSurfaceContainerLow, DaySurfaceContainerLow)

val ColorScheme.surfaceContainer: Color
    @Composable get() = tonalSurface(NightSurfaceContainer, DaySurfaceContainer)

val ColorScheme.surfaceContainerHigh: Color
    @Composable get() = tonalSurface(NightSurfaceContainerHigh, DaySurfaceContainerHigh)

val ColorScheme.surfaceContainerHighest: Color
    @Composable get() = tonalSurface(NightSurfaceContainerHighest, DaySurfaceContainerHighest)

val ColorScheme.codeSurface: Color
    @Composable get() = tonalSurface(CodeBackgroundDark, CodeBackgroundLight)

val ColorScheme.headerBackground: Color
    @Composable get() = tonalSurface(NightHeaderBackground, DayHeaderBackground)

val ColorScheme.headerTitle: Color
    @Composable get() = tonalSurface(NightHeaderTitle, DayHeaderTitle)

val ColorScheme.headerModel: Color
    @Composable get() = tonalSurface(NightHeaderModel, DayHeaderModel)

val ColorScheme.headerChipBackground: Color
    @Composable get() = tonalSurface(NightHeaderChipBackground, DayHeaderChipBackground)

val ColorScheme.headerChipBorder: Color
    @Composable get() = tonalSurface(NightHeaderChipBorder, DayHeaderChipBorder)

val ColorScheme.headerActive: Color
    @Composable get() = tonalSurface(NightPrimary, DayHeaderActive)

val ColorScheme.headerSecondary: Color
    @Composable get() = tonalSurface(NightSecondary, DayHeaderSecondary)

val ColorScheme.headerBottomBorder: Color
    @Composable get() = tonalSurface(NightHeaderChipBorder, DayHeaderBottomBorder)

val ColorScheme.statusSuccess: Color
    @Composable get() = tonalSurface(SuccessGreen, DaySuccess)

val ColorScheme.statusWarning: Color
    @Composable get() = tonalSurface(WarningAmber, DayTertiary)

val ColorScheme.statusIdle: Color
    @Composable get() = tonalSurface(IdleSlate, DayOnSurfaceVariant)

private val LocalAtlasDarkTheme = compositionLocalOf { true }

@Composable
private fun ColorScheme.tonalSurface(dark: Color, light: Color): Color =
    animateColorAsState(
        targetValue = if (LocalAtlasDarkTheme.current) dark else light,
        animationSpec = tween(durationMillis = 260),
        label = "themeTonalColor"
    ).value

private fun colorScheme(darkTheme: Boolean): ColorScheme {
    return if (darkTheme) {
        darkColorScheme(
            primary = NightPrimary,
            onPrimary = NightOnPrimary,
            primaryContainer = NightPrimaryContainer,
            onPrimaryContainer = NightOnPrimaryContainer,
            secondary = NightSecondary,
            onSecondary = NightOnSecondary,
            secondaryContainer = NightSecondaryContainer,
            onSecondaryContainer = NightOnSecondaryContainer,
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
            inversePrimary = DayPrimary,
            surfaceTint = NightPrimary
        )
    } else {
        lightColorScheme(
            primary = DayPrimary,
            onPrimary = DayOnPrimary,
            primaryContainer = DayPrimaryContainer,
            onPrimaryContainer = DayOnPrimaryContainer,
            secondary = DaySecondary,
            onSecondary = DayOnSecondary,
            secondaryContainer = DaySecondaryContainer,
            onSecondaryContainer = DayOnSecondaryContainer,
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
            inversePrimary = NightPrimary,
            surfaceTint = DayPrimary
        )
    }
}

@Composable
fun AtlasAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // This is the single app-level theme boundary; semantic colors animate before
    // reaching screens, while tonal surface extensions follow the same mode state.
    val targetScheme = remember(darkTheme) { colorScheme(darkTheme) }
    val scheme = if (LocalView.current.isInEditMode) targetScheme else targetScheme.copy(
        primary = animateColorAsState(targetScheme.primary, tween(260), label = "themePrimary").value,
        onPrimary = animateColorAsState(targetScheme.onPrimary, tween(260), label = "themeOnPrimary").value,
        primaryContainer = animateColorAsState(targetScheme.primaryContainer, tween(260), label = "themePrimaryContainer").value,
        onPrimaryContainer = animateColorAsState(targetScheme.onPrimaryContainer, tween(260), label = "themeOnPrimaryContainer").value,
        secondary = animateColorAsState(targetScheme.secondary, tween(260), label = "themeSecondary").value,
        onSecondary = animateColorAsState(targetScheme.onSecondary, tween(260), label = "themeOnSecondary").value,
        secondaryContainer = animateColorAsState(targetScheme.secondaryContainer, tween(260), label = "themeSecondaryContainer").value,
        onSecondaryContainer = animateColorAsState(targetScheme.onSecondaryContainer, tween(260), label = "themeOnSecondaryContainer").value,
        tertiary = animateColorAsState(targetScheme.tertiary, tween(260), label = "themeTertiary").value,
        onTertiary = animateColorAsState(targetScheme.onTertiary, tween(260), label = "themeOnTertiary").value,
        background = animateColorAsState(targetScheme.background, tween(260), label = "themeBackground").value,
        onBackground = animateColorAsState(targetScheme.onBackground, tween(260), label = "themeOnBackground").value,
        surface = animateColorAsState(targetScheme.surface, tween(260), label = "themeSurface").value,
        onSurface = animateColorAsState(targetScheme.onSurface, tween(260), label = "themeOnSurface").value,
        surfaceVariant = animateColorAsState(targetScheme.surfaceVariant, tween(260), label = "themeSurfaceVariant").value,
        onSurfaceVariant = animateColorAsState(targetScheme.onSurfaceVariant, tween(260), label = "themeOnSurfaceVariant").value,
        outline = animateColorAsState(targetScheme.outline, tween(260), label = "themeOutline").value,
        outlineVariant = animateColorAsState(targetScheme.outlineVariant, tween(260), label = "themeOutlineVariant").value,
        error = animateColorAsState(targetScheme.error, tween(260), label = "themeError").value,
        onError = animateColorAsState(targetScheme.onError, tween(260), label = "themeOnError").value,
        errorContainer = animateColorAsState(targetScheme.errorContainer, tween(260), label = "themeErrorContainer").value,
        onErrorContainer = animateColorAsState(targetScheme.onErrorContainer, tween(260), label = "themeOnErrorContainer").value,
        inverseSurface = animateColorAsState(targetScheme.inverseSurface, tween(260), label = "themeInverseSurface").value,
        inverseOnSurface = animateColorAsState(targetScheme.inverseOnSurface, tween(260), label = "themeInverseOnSurface").value,
        inversePrimary = animateColorAsState(targetScheme.inversePrimary, tween(260), label = "themeInversePrimary").value,
        surfaceTint = animateColorAsState(targetScheme.surfaceTint, tween(260), label = "themeSurfaceTint").value,
        scrim = animateColorAsState(targetScheme.scrim, tween(260), label = "themeScrim").value
    )
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
    CompositionLocalProvider(LocalAtlasDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}

@Composable
fun SynapseAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) = AtlasAiTheme(darkTheme = darkTheme, content = content)

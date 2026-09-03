package com.synapse.localai.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * UI/UX Architectural Specification: Universal Stripping of Ambient Glows, Halos, and Drop Shadows.
 *
 * Zero Perimeter Lighting:
 * Completely stripped of all blue, violet, and neon outer glows, ambient drop shadows,
 * and halo diffusions from every element in the application.
 *
 * Strict Borderless & Flat Separation:
 * Maintains a flat, clean interface. Component separation relies entirely on subtle,
 * solid tonal contrast between the surface and background layers — zero glowing strokes,
 * zero colored borders, and zero luminous drop shadows.
 */
@Composable
fun Modifier.tactileGlassGlow(
    cornerRadius: Dp = 10.dp,
    elevation: Dp = 0.dp,
    isDark: Boolean = false,
    shadowColor: Color? = null,
    borderWidth: Dp = 0.dp,
    glowAlpha: Float = 0f,
    coreColor: Color? = null,
    hazeColor: Color? = null,
    ambientColor: Color? = null
): Modifier = this

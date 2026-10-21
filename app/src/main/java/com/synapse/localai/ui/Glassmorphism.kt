package com.synapse.localai.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dual-Theme Tactile Card Elevation (Zero Neon Glows):
 *
 * Light Theme (Crisp Pure-White Elevation):
 * - Ambient drop shadow: Diffuse, natural elevation shadow (rgba(15, 23, 42, 0.08), Y = 2dp, blur 12-16dp)
 *   giving cards clean floating depth without visible harsh borders or gray muddy tones.
 *
 * Dark Theme (Tactile Obsidian Depth):
 * - Ambient drop shadow: Deep, realistic ambient shadow (rgba(0, 0, 0, 0.55), Y = 3dp, blur 14-18dp),
 *   providing structural physical elevation against the deep obsidian canvas.
 *
 * Zero perimeter neon halos, electric cyan bleed, or colored glow outlines.
 */
@Composable
fun Modifier.tactileCardElevation(
    isDark: Boolean,
    cornerRadius: Dp = 16.dp,
    elevation: Dp = if (isDark) 3.dp else 2.dp
): Modifier {
    val shadowColor = MaterialTheme.colorScheme.scrim.copy(alpha = if (isDark) 0.42f else 0.08f)
    return this.shadow(
        elevation = elevation,
        shape = RoundedCornerShape(cornerRadius),
        clip = false,
        ambientColor = shadowColor,
        spotColor = shadowColor
    )
}

@Composable
fun Modifier.tactileGlassGlow(
    cornerRadius: Dp = 16.dp,
    elevation: Dp = 2.dp,
    isDark: Boolean = false,
    shadowColor: Color? = null,
    borderWidth: Dp = 0.dp,
    glowAlpha: Float = 0f,
    coreColor: Color? = null,
    hazeColor: Color? = null,
    ambientColor: Color? = null
): Modifier = this.tactileCardElevation(isDark = isDark, cornerRadius = cornerRadius, elevation = elevation)

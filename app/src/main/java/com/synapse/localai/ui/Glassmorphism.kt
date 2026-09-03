package com.synapse.localai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.synapse.localai.ui.theme.DarkGlowAzure
import com.synapse.localai.ui.theme.DarkGlowIndigo
import com.synapse.localai.ui.theme.LightAmbientShadow

/**
 * UI/UX Architectural Specification: Outward-Only Ambient Glow & Elevation
 *
 * 1. Strictly Outward Feathering:
 *    The ambient light radiates exclusively outward from container perimeters into the canvas,
 *    decaying exponentially without sharp cutoffs or internal shadow bleed.
 * 2. Dark Theme (Deep Twilight Obsidian):
 *    Outward-feathering electric indigo-azure glow:
 *    rgba(56, 130, 246, 0.18) transitioning to rgba(99, 102, 241, 0.08)
 *    with a 24-30dp blur radius, 0dp spread, and zero hard boundaries.
 * 3. Light Theme (Pure Floating Porcelain):
 *    Soft, diffuse ambient shadow:
 *    rgba(15, 23, 42, 0.07) with wide-radius dispersion (20-26dp blur)
 *    giving cards a floating, tactile elevation.
 * 4. Zero Stroke Outlines:
 *    Maintains the borderless aesthetic (borderWidth = 0dp).
 */
@Composable
fun Modifier.tactileGlassGlow(
    cornerRadius: Dp = 10.dp,
    elevation: Dp = 6.dp,
    isDark: Boolean = isSystemInDarkTheme(),
    shadowColor: Color? = null,
    borderWidth: Dp = 0.dp,
    glowAlpha: Float = 1.0f,
    coreColor: Color? = null,
    hazeColor: Color? = null,
    ambientColor: Color? = null
): Modifier {
    val scale = glowAlpha.coerceIn(0.5f, 1.5f)

    return this
        // 1. Android RenderNode hardware-accelerated outward ambient elevation (0 edge clipping)
        .shadow(
            elevation = elevation,
            shape = RoundedCornerShape(cornerRadius),
            clip = false,
            ambientColor = if (isDark) DarkGlowAzure.copy(alpha = 0.18f * scale) else LightAmbientShadow.copy(alpha = 0.05f * scale),
            spotColor = if (isDark) DarkGlowIndigo.copy(alpha = 0.12f * scale) else LightAmbientShadow.copy(alpha = 0.07f * scale)
        )
        // 2. Outward-only multi-layered exponential diffuse falloff behind the surface
        .drawBehind {
            val cr = cornerRadius.toPx()
            val w = size.width
            val h = size.height

            if (isDark) {
                // DARK THEME: Outward electric indigo-azure glow (24-30dp blur, 0dp spread)
                // Pass 6: Outermost atmospheric dispersion (spread: 28dp)
                drawRoundRect(
                    color = Color(99, 102, 241, (20 * scale).toInt().coerceIn(0, 255)), // rgba(99, 102, 241, 0.08) tail
                    topLeft = Offset(-28f, -28f),
                    size = Size(w + 56f, h + 56f),
                    cornerRadius = CornerRadius(cr + 28f, cr + 28f)
                )
                // Pass 5: Ethereal indigo haze (spread: 22dp)
                drawRoundRect(
                    color = Color(99, 102, 241, (28 * scale).toInt().coerceIn(0, 255)),
                    topLeft = Offset(-22f, -22f),
                    size = Size(w + 44f, h + 44f),
                    cornerRadius = CornerRadius(cr + 22f, cr + 22f)
                )
                // Pass 4: Mid-field transition bloom (spread: 16dp)
                drawRoundRect(
                    color = Color(75, 115, 248, (34 * scale).toInt().coerceIn(0, 255)),
                    topLeft = Offset(-16f, -16f),
                    size = Size(w + 32f, h + 32f),
                    cornerRadius = CornerRadius(cr + 16f, cr + 16f)
                )
                // Pass 3: Radiant azure body (spread: 10dp)
                drawRoundRect(
                    color = Color(56, 130, 246, (40 * scale).toInt().coerceIn(0, 255)),
                    topLeft = Offset(-10f, -10f),
                    size = Size(w + 20f, h + 20f),
                    cornerRadius = CornerRadius(cr + 10f, cr + 10f)
                )
                // Pass 2: Concentrated rim accent (spread: 5dp)
                drawRoundRect(
                    color = Color(56, 130, 246, (46 * scale).toInt().coerceIn(0, 255)), // rgba(56, 130, 246, 0.18) core
                    topLeft = Offset(-5f, -5f),
                    size = Size(w + 10f, h + 10f),
                    cornerRadius = CornerRadius(cr + 5f, cr + 5f)
                )
                // Pass 1: Immediate outward perimeter contour (spread: 1.5dp)
                drawRoundRect(
                    color = Color(56, 130, 246, (46 * scale).toInt().coerceIn(0, 255)),
                    topLeft = Offset(-1.5f, -1.5f),
                    size = Size(w + 3f, h + 3f),
                    cornerRadius = CornerRadius(cr + 1.5f, cr + 1.5f)
                )
            } else {
                // LIGHT THEME: Soft diffuse ambient elevation shadow (rgba(15, 23, 42, 0.07), 20-26dp blur)
                // Pass 5: Outermost ambient dissipation (spread: 24dp, Y-offset: +2.5dp)
                drawRoundRect(
                    color = Color(15, 23, 42, (10 * scale).toInt().coerceIn(0, 255)),
                    topLeft = Offset(-24f, -21.5f),
                    size = Size(w + 48f, h + 48f),
                    cornerRadius = CornerRadius(cr + 24f, cr + 24f)
                )
                // Pass 4: Wide diffuse tonal shadow (spread: 16dp, Y-offset: +2.0dp)
                drawRoundRect(
                    color = Color(15, 23, 42, (14 * scale).toInt().coerceIn(0, 255)),
                    topLeft = Offset(-16f, -14f),
                    size = Size(w + 32f, h + 32f),
                    cornerRadius = CornerRadius(cr + 16f, cr + 16f)
                )
                // Pass 3: Mid-elevation soft body (spread: 10dp, Y-offset: +1.5dp)
                drawRoundRect(
                    color = Color(15, 23, 42, (16 * scale).toInt().coerceIn(0, 255)),
                    topLeft = Offset(-10f, -8.5f),
                    size = Size(w + 20f, h + 20f),
                    cornerRadius = CornerRadius(cr + 10f, cr + 10f)
                )
                // Pass 2: Tactile grounding contact (spread: 5dp, Y-offset: +1.0dp)
                drawRoundRect(
                    color = Color(15, 23, 42, (18 * scale).toInt().coerceIn(0, 255)), // rgba(15, 23, 42, 0.07)
                    topLeft = Offset(-5f, -4f),
                    size = Size(w + 10f, h + 10f),
                    cornerRadius = CornerRadius(cr + 5f, cr + 5f)
                )
                // Pass 1: Direct grounding anchor (spread: 1.5dp, Y-offset: +0.5dp)
                drawRoundRect(
                    color = Color(15, 23, 42, (18 * scale).toInt().coerceIn(0, 255)),
                    topLeft = Offset(-1.5f, -1f),
                    size = Size(w + 3f, h + 3f),
                    cornerRadius = CornerRadius(cr + 1.5f, cr + 1.5f)
                )
            }
        }
}

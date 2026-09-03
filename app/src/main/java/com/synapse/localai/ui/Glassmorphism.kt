package com.synapse.localai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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

/**
 * UI/UX Specification: Adaptive Ambient Glow & Elevation (Canva-Style Falloff)
 *
 * 1. Borderless Floating Geometry:
 *    Retains the zero-borderline rule across all modes (borderWidth = 0dp).
 * 2. Theme-Aware Ambient Falloff:
 *    - In Dark Mode: The ambient drop shadow samples the dynamic palette's primary / secondary hue
 *      with a soft, wide-feathered radial dispersion (24-32dp blur radius, zero spread, low opacity).
 *      Produces a rich, luminous ambient halo that dissipates naturally into the dark canvas.
 *    - In Light Mode: Transitions into a clean, diffuse ambient elevation shadow (soft tonal drop shadow
 *      using deep dynamic slate tones at 6-10% opacity) that lifts cards off the screen like physical,
 *      clean glass cards without creating dark rings or dirty contours.
 */
@Composable
fun Modifier.tactileGlassGlow(
    cornerRadius: Dp = 10.dp,
    elevation: Dp = 6.dp,
    isDark: Boolean = isSystemInDarkTheme(),
    shadowColor: Color? = null,
    borderWidth: Dp = 0.dp,   // Strictly ZERO visible strokes!
    glowAlpha: Float = 1.0f,
    coreColor: Color? = null,
    hazeColor: Color? = null,
    ambientColor: Color? = null
): Modifier {
    val dynamicPrimary = MaterialTheme.colorScheme.primary
    val dynamicSecondary = MaterialTheme.colorScheme.secondary

    // Theme-Aware Dynamic Ambient Sampling:
    // Dark mode: dynamic primary / secondary hue
    // Light mode: clean deep slate shadow tone
    val ambientDropColor = shadowColor ?: if (isDark) dynamicPrimary else Color(0xFF0F172A)
    val contactDepthColor = if (isDark) dynamicSecondary else Color(0xFF1E293B)
    val scale = glowAlpha.coerceIn(0.5f, 1.5f)

    return this
        // 1. Android RenderNode hardware-accelerated ambient shadow elevation (0 edge clipping)
        .shadow(
            elevation = elevation,
            shape = RoundedCornerShape(cornerRadius),
            clip = false,
            ambientColor = if (isDark) ambientDropColor.copy(alpha = 0.20f) else Color(0xFF0F172A).copy(alpha = 0.06f),
            spotColor = if (isDark) ambientDropColor.copy(alpha = 0.32f) else Color(0xFF0F172A).copy(alpha = 0.10f)
        )
        // 2. Canva-style high-blur multi-layered diffuse falloff behind the surface
        .drawBehind {
            val cr = cornerRadius.toPx()
            val w = size.width
            val h = size.height

            if (isDark) {
                // DARK MODE: Rich luminous ambient halo (28-32dp blur radius, zero spread, low opacity)
                // Pass 6: Deep field atmospheric dissipation (spread: 28dp)
                drawRoundRect(
                    color = ambientDropColor.copy(alpha = 0.008f * scale),
                    topLeft = Offset(-28f, -28f),
                    size = Size(w + 56f, h + 56f),
                    cornerRadius = CornerRadius(cr + 28f, cr + 28f)
                )
                // Pass 5: Far diffuse ambient halo (spread: 20dp)
                drawRoundRect(
                    color = ambientDropColor.copy(alpha = 0.015f * scale),
                    topLeft = Offset(-20f, -20f),
                    size = Size(w + 40f, h + 40f),
                    cornerRadius = CornerRadius(cr + 20f, cr + 20f)
                )
                // Pass 4: Mid ambient bloom (spread: 14dp)
                drawRoundRect(
                    color = ambientDropColor.copy(alpha = 0.025f * scale),
                    topLeft = Offset(-14f, -14f),
                    size = Size(w + 28f, h + 28f),
                    cornerRadius = CornerRadius(cr + 14f, cr + 14f)
                )
                // Pass 3: Soft ambient elevation body (spread: 8dp)
                drawRoundRect(
                    color = ambientDropColor.copy(alpha = 0.040f * scale),
                    topLeft = Offset(-8f, -8f),
                    size = Size(w + 16f, h + 16f),
                    cornerRadius = CornerRadius(cr + 8f, cr + 8f)
                )
                // Pass 2: Near contact elevation (spread: 4dp)
                drawRoundRect(
                    color = contactDepthColor.copy(alpha = 0.060f * scale),
                    topLeft = Offset(-4f, -4f),
                    size = Size(w + 8f, h + 8f),
                    cornerRadius = CornerRadius(cr + 4f, cr + 4f)
                )
                // Pass 1: Direct grounding contour (spread: 1.5dp)
                drawRoundRect(
                    color = contactDepthColor.copy(alpha = 0.080f * scale),
                    topLeft = Offset(-1.5f, -1.5f),
                    size = Size(w + 3f, h + 3f),
                    cornerRadius = CornerRadius(cr + 1.5f, cr + 1.5f)
                )
            } else {
                // LIGHT MODE: Clean, diffuse ambient elevation shadow (soft tonal drop shadow at 6-10% opacity)
                // Pass 6: Outermost atmospheric dissipation (spread: 24dp, Y-offset: +2.5dp)
                drawRoundRect(
                    color = ambientDropColor.copy(alpha = 0.008f * scale),
                    topLeft = Offset(-24f, -21.5f),
                    size = Size(w + 48f, h + 48f),
                    cornerRadius = CornerRadius(cr + 24f, cr + 24f)
                )
                // Pass 5: Far diffuse tonal shadow (spread: 16dp, Y-offset: +2.0dp)
                drawRoundRect(
                    color = ambientDropColor.copy(alpha = 0.015f * scale),
                    topLeft = Offset(-16f, -14f),
                    size = Size(w + 32f, h + 32f),
                    cornerRadius = CornerRadius(cr + 16f, cr + 16f)
                )
                // Pass 4: Mid ambient elevation (spread: 10dp, Y-offset: +1.5dp)
                drawRoundRect(
                    color = ambientDropColor.copy(alpha = 0.025f * scale),
                    topLeft = Offset(-10f, -8.5f),
                    size = Size(w + 20f, h + 20f),
                    cornerRadius = CornerRadius(cr + 10f, cr + 10f)
                )
                // Pass 3: Soft contact shadow (spread: 6dp, Y-offset: +1.5dp)
                drawRoundRect(
                    color = ambientDropColor.copy(alpha = 0.035f * scale),
                    topLeft = Offset(-6f, -4.5f),
                    size = Size(w + 12f, h + 12f),
                    cornerRadius = CornerRadius(cr + 6f, cr + 6f)
                )
                // Pass 2: Near grounding shadow (spread: 3dp, Y-offset: +1.0dp)
                drawRoundRect(
                    color = ambientDropColor.copy(alpha = 0.050f * scale),
                    topLeft = Offset(-3f, -2.0f),
                    size = Size(w + 6f, h + 6f),
                    cornerRadius = CornerRadius(cr + 3f, cr + 3f)
                )
                // Pass 1: Direct contact drop (spread: 1dp, Y-offset: +0.5dp)
                drawRoundRect(
                    color = ambientDropColor.copy(alpha = 0.075f * scale),
                    topLeft = Offset(-1f, -0.5f),
                    size = Size(w + 2f, h + 2f),
                    cornerRadius = CornerRadius(cr + 1f, cr + 1f)
                )
            }
        }
}

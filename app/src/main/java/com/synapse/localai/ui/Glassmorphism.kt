package com.synapse.localai.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// =========================================================================
// Canva-Style Ambient Shadow Spectrum (Subtle Slate-Indigo & Twilight Violet)
// =========================================================================
val AmbientShadowDark = Color(0xFF6366F1)       // Subtle electric slate-indigo ambient tint (dark mode)
val AmbientShadowLight = Color(0xFF312E81)      // Subtle twilight slate drop shadow tint (light mode)
val ContactShadowDark = Color(0xFF1E1B4B)       // Contact depth shadow (dark mode)
val ContactShadowLight = Color(0xFF0F172A)      // Contact depth shadow (light mode)

/**
 * UI Specification: Canva-Style Ambient Shadow Elevation (Zero Border Outlines)
 *
 * 1. Total Removal of Harsh Border Outlines:
 *    Zero visible strokes, zero linear edges, zero borderWidth.
 * 2. Canva-Style Soft Ambient Drop Shadow:
 *    Clean floating pill surface with multi-layered diffuse falloff (20-28dp blur radius,
 *    0-2dp Y-offset, 0dp spread).
 * 3. Atmospheric Dissipation:
 *    Fades seamlessly into the background canvas with zero visible boundaries.
 * 4. Soft Chromatic Tint:
 *    Subtle, low-opacity violet/slate-indigo shade preserving depth without creating a line.
 */
fun Modifier.tactileGlassGlow(
    cornerRadius: Dp = 10.dp,
    isDark: Boolean = true,
    shadowTint: Color = if (isDark) AmbientShadowDark else AmbientShadowLight,
    elevation: Dp = 6.dp,
    borderWidth: Dp = 0.dp,   // Deprecated / ignored: strictly ZERO visible strokes!
    glowAlpha: Float = 1.0f,
    coreColor: Color = AmbientShadowDark,       // Backwards-compatibility
    hazeColor: Color = AmbientShadowLight,      // Backwards-compatibility
    ambientColor: Color = ContactShadowDark     // Backwards-compatibility
): Modifier = this
    // 1. Android RenderNode hardware-accelerated ambient shadow elevation (0 edge clipping)
    .shadow(
        elevation = elevation,
        shape = RoundedCornerShape(cornerRadius),
        clip = false,
        ambientColor = shadowTint.copy(alpha = if (isDark) 0.18f else 0.12f),
        spotColor = if (isDark) ContactShadowDark.copy(alpha = 0.35f) else ContactShadowLight.copy(alpha = 0.20f)
    )
    // 2. Canva-style high-blur multi-layered diffuse falloff behind the surface
    .drawBehind {
        val cr = cornerRadius.toPx()
        val w = size.width
        val h = size.height
        val scale = glowAlpha.coerceIn(0.5f, 1.5f)

        // Pass 6: Outermost atmospheric dissipation (spread: 24dp, Y-offset: +2dp)
        drawRoundRect(
            color = shadowTint.copy(alpha = (if (isDark) 0.010f else 0.015f) * scale),
            topLeft = Offset(-24f, -22f),
            size = Size(w + 48f, h + 48f),
            cornerRadius = CornerRadius(cr + 24f, cr + 24f)
        )

        // Pass 5: Far diffuse ambient falloff (spread: 18dp, Y-offset: +2dp)
        drawRoundRect(
            color = shadowTint.copy(alpha = (if (isDark) 0.018f else 0.025f) * scale),
            topLeft = Offset(-18f, -16f),
            size = Size(w + 36f, h + 36f),
            cornerRadius = CornerRadius(cr + 18f, cr + 18f)
        )

        // Pass 4: Mid ambient bloom (spread: 12dp, Y-offset: +1.5dp)
        drawRoundRect(
            color = shadowTint.copy(alpha = (if (isDark) 0.030f else 0.040f) * scale),
            topLeft = Offset(-12f, -10.5f),
            size = Size(w + 24f, h + 24f),
            cornerRadius = CornerRadius(cr + 12f, cr + 12f)
        )

        // Pass 3: Soft ambient elevation body (spread: 7dp, Y-offset: +1.5dp)
        drawRoundRect(
            color = shadowTint.copy(alpha = (if (isDark) 0.045f else 0.060f) * scale),
            topLeft = Offset(-7f, -5.5f),
            size = Size(w + 14f, h + 14f),
            cornerRadius = CornerRadius(cr + 7f, cr + 7f)
        )

        // Pass 2: Near contact shadow (spread: 3.5dp, Y-offset: +1dp)
        drawRoundRect(
            color = if (isDark) ContactShadowDark.copy(alpha = 0.065f * scale) else ContactShadowLight.copy(alpha = 0.080f * scale),
            topLeft = Offset(-3.5f, -2.5f),
            size = Size(w + 7f, h + 7f),
            cornerRadius = CornerRadius(cr + 3.5f, cr + 3.5f)
        )

        // Pass 1: Direct grounding shadow beneath the pill (spread: 1dp, Y-offset: +1dp)
        drawRoundRect(
            color = if (isDark) ContactShadowDark.copy(alpha = 0.090f * scale) else ContactShadowLight.copy(alpha = 0.110f * scale),
            topLeft = Offset(-1f, 0f),
            size = Size(w + 2f, h + 2f),
            cornerRadius = CornerRadius(cr + 1f, cr + 1f)
        )
    }

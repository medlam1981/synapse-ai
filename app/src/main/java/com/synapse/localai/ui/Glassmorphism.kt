package com.synapse.localai.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// =========================================================================
// Deep Indigo-Violet Spectrum (Twilight Indigo & Luminous Violet-Blue)
// =========================================================================
val ElectricIndigoCore = Color(0xFF818CF8)     // Luminous Indigo Core (rim accent)
val ElectricVioletAccent = Color(0xFF7C3AED)   // Radiant Electric Violet
val DeepIndigoHaze = Color(0xFF4F46E5)         // Ethereal Twilight Indigo (mid-decay)
val AtmosphericVioletBlue = Color(0xFF3730A3)  // Outer atmospheric dispersion (far-decay)
val TwilightVioletShadow = Color(0xFF1E1B4B)   // Deep 2.5D optical glass elevation

/**
 * UI/UX Specification: Diffused Indigo-Violet Ambient Rim Lighting (Feathered Radial Decay)
 *
 * 1. Deep Indigo-Violet Spectrum:
 *    Rich, luminous electric indigo-blue leaning into violet, tuned for dark backgrounds.
 * 2. Photometric Feathering:
 *    Zero hard outer boundaries or box cutoffs. Decays smoothly outwards via concentric,
 *    exponentially-feathered anti-aliased perimeter strokes.
 * 3. No Solid Silhouette / Backing Plate:
 *    Light strictly radiates outward from the contour (Stroke-only). Interior glass remains
 *    pristine and translucent.
 * 4. Optical 2.5D Glass Elevation:
 *    High-translucency refractive rim gradient catching ambient light.
 */
fun Modifier.tactileGlassGlow(
    cornerRadius: Dp = 10.dp,
    isDark: Boolean = true,
    coreColor: Color = ElectricIndigoCore,
    hazeColor: Color = DeepIndigoHaze,
    ambientColor: Color = AtmosphericVioletBlue,
    borderWidth: Dp = 0.8.dp,
    glowAlpha: Float = 1.0f
): Modifier = this
    .drawBehind {
        val cr = cornerRadius.toPx()
        val w = size.width
        val h = size.height
        val scale = if (glowAlpha <= 0.3f) (glowAlpha / 0.16f).coerceIn(0.5f, 1.2f) else glowAlpha.coerceIn(0.5f, 2.0f)

        // Photometric exponential decay rings radiating strictly outward from the perimeter.
        // Every pass uses DrawStyle = Stroke so NO solid backing plate is rendered inside.

        // Pass 7: Deep field dispersion - seamlessly vanishes into darkness (spread: ~14dp)
        drawRoundRect(
            color = ambientColor.copy(alpha = (if (isDark) 0.015f else 0.008f) * scale),
            topLeft = Offset(-14f, -14f),
            size = Size(w + 28f, h + 28f),
            cornerRadius = CornerRadius(cr + 14f, cr + 14f),
            style = Stroke(width = 12f)
        )

        // Pass 6: Far atmospheric haze (spread: ~10dp)
        drawRoundRect(
            color = ambientColor.copy(alpha = (if (isDark) 0.035f else 0.015f) * scale),
            topLeft = Offset(-10f, -10f),
            size = Size(w + 20f, h + 20f),
            cornerRadius = CornerRadius(cr + 10f, cr + 10f),
            style = Stroke(width = 8f)
        )

        // Pass 5: Outer violet-blue dispersion (spread: ~7dp)
        drawRoundRect(
            color = hazeColor.copy(alpha = (if (isDark) 0.065f else 0.025f) * scale),
            topLeft = Offset(-7f, -7f),
            size = Size(w + 14f, h + 14f),
            cornerRadius = CornerRadius(cr + 7f, cr + 7f),
            style = Stroke(width = 6f)
        )

        // Pass 4: Mid twilight indigo haze (spread: ~4.5dp)
        drawRoundRect(
            color = hazeColor.copy(alpha = (if (isDark) 0.11f else 0.045f) * scale),
            topLeft = Offset(-4.5f, -4.5f),
            size = Size(w + 9f, h + 9f),
            cornerRadius = CornerRadius(cr + 4.5f, cr + 4.5f),
            style = Stroke(width = 4.5f)
        )

        // Pass 3: Inner violet glow (spread: ~2.5dp)
        drawRoundRect(
            color = coreColor.copy(alpha = (if (isDark) 0.18f else 0.08f) * scale),
            topLeft = Offset(-2.5f, -2.5f),
            size = Size(w + 5f, h + 5f),
            cornerRadius = CornerRadius(cr + 2.5f, cr + 2.5f),
            style = Stroke(width = 3.2f)
        )

        // Pass 2: Near-rim concentrated accent (spread: ~1.2dp)
        drawRoundRect(
            color = coreColor.copy(alpha = (if (isDark) 0.32f else 0.14f) * scale),
            topLeft = Offset(-1.2f, -1.2f),
            size = Size(w + 2.4f, h + 2.4f),
            cornerRadius = CornerRadius(cr + 1.2f, cr + 1.2f),
            style = Stroke(width = 2.0f)
        )

        // Pass 1: Luminous core rim contour (centered directly on the glass edge)
        drawRoundRect(
            color = coreColor.copy(alpha = (if (isDark) 0.50f else 0.22f) * scale),
            topLeft = Offset(-0.4f, -0.4f),
            size = Size(w + 0.8f, h + 0.8f),
            cornerRadius = CornerRadius(cr + 0.4f, cr + 0.4f),
            style = Stroke(width = 1.0f)
        )

        // Optical 2.5D depth: soft downward ambient elevation shadow beneath the glass slab
        drawRoundRect(
            color = TwilightVioletShadow.copy(alpha = (if (isDark) 0.25f else 0.10f) * scale),
            topLeft = Offset(0f, 3f),
            size = Size(w, h + 2f),
            cornerRadius = CornerRadius(cr + 2f, cr + 2f),
            style = Stroke(width = 4f)
        )
    }
    .border(
        width = borderWidth,
        brush = Brush.linearGradient(
            colors = listOf(
                coreColor.copy(alpha = if (isDark) 0.60f else 0.35f),
                hazeColor.copy(alpha = if (isDark) 0.25f else 0.15f),
                ElectricVioletAccent.copy(alpha = if (isDark) 0.50f else 0.28f),
                ambientColor.copy(alpha = if (isDark) 0.20f else 0.10f)
            ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        ),
        shape = RoundedCornerShape(cornerRadius)
    )

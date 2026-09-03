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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tactile Floating Glassmorphism:
 * Refined, low-intensity diffused blue glow (soft cyan/azure ambient rim shadow)
 * and organic floating glass depth for containers, capsules, and status pills.
 */
fun Modifier.tactileGlassGlow(
    cornerRadius: Dp = 10.dp,
    isDark: Boolean = true,
    glowColor: Color = Color(0xFF38BDF8), // Soft cyan / azure ambient glow
    glowAlpha: Float = if (isDark) 0.16f else 0.10f,
    borderAlpha: Float = if (isDark) 0.35f else 0.25f,
    borderWidth: Dp = 0.8.dp
): Modifier = this
    .drawBehind {
        // 1. Subtle ambient diffused rim halo
        drawRoundRect(
            color = glowColor.copy(alpha = glowAlpha),
            cornerRadius = CornerRadius(cornerRadius.toPx() + 3f, cornerRadius.toPx() + 3f),
            topLeft = Offset(-1.5f, -1.5f),
            size = Size(size.width + 3f, size.height + 3f)
        )
        // 2. Soft lower depth shadow for organic floating glass elevation
        drawRoundRect(
            color = Color(0xFF0284C7).copy(alpha = glowAlpha * 0.65f),
            cornerRadius = CornerRadius(cornerRadius.toPx() + 4f, cornerRadius.toPx() + 4f),
            topLeft = Offset(-1f, 2f),
            size = Size(size.width + 2f, size.height + 3.5f)
        )
    }
    .border(
        width = borderWidth,
        brush = Brush.linearGradient(
            colors = listOf(
                glowColor.copy(alpha = borderAlpha),
                glowColor.copy(alpha = borderAlpha * 0.25f),
                glowColor.copy(alpha = borderAlpha * 0.75f)
            )
        ),
        shape = RoundedCornerShape(cornerRadius)
    )

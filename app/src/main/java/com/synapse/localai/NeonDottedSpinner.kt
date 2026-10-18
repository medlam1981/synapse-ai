package com.synapse.localai

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Neon Cyan Dotted Orbit Spinner:
 * Discrete circular dots arranged along a circular path that illuminate sequentially
 * with a smooth trailing fade, matching the muted mauve accent palette.
 */
@Composable
fun NeonDottedSpinner(
    modifier: Modifier = Modifier.size(16.dp),
    dotCount: Int = 8,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveAlpha: Float = 0.15f,
    cycleDurationMs: Int = 1000
) {
    val infiniteTransition = rememberInfiniteTransition(label = "NeonDottedRingOrbit")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = cycleDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "OrbitRotation"
    )

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val dotRadius = radius * 0.18f
        val orbitRadius = radius - dotRadius

        for (i in 0 until dotCount) {
            val angleDeg = (i * (360f / dotCount) + rotationAngle) % 360f
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val x = center.x + (orbitRadius * cos(angleRad)).toFloat()
            val y = center.y + (orbitRadius * sin(angleRad)).toFloat()

            // Calculate trailing decay based on angle position
            val normalizedPhase = (angleDeg / 360f)
            val alpha = inactiveAlpha + (1f - inactiveAlpha) * normalizedPhase.pow(2.2f)

            drawCircle(
                color = activeColor.copy(alpha = alpha.coerceIn(0.1f, 1f)),
                radius = dotRadius * (0.8f + 0.3f * normalizedPhase),
                center = Offset(x, y)
            )
        }
    }
}

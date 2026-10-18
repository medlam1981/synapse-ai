package com.synapse.localai.tools

import android.content.Context
import com.synapse.localai.AtlasLog as Log
import com.synapse.localai.MainActivity
import com.synapse.localai.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UiAutomationTool — Root Shell and Vision-Based Direct UI Input Engine.
 *
 * Implements:
 * Layer 1: Density Normalization (Clamps coordinates against physical display metrics).
 * Layer 2: Visual OCR Coordinate Mapping (ML Kit on-device text coordinate recognition).
 * Layer 3: Root Shell Input Injection (input tap, input swipe, input text, input keyevent).
 *
 * Fully decoupled from Android Accessibility Service.
 */
object UiAutomationTool {

    private const val TAG = "UiAutomationTool"

    /**
     * Normalizes and clamps coordinates against physical display metrics to ensure precision across screen densities.
     */
    fun normalizeCoordinates(rawX: Float, rawY: Float, context: Context? = null): Pair<Float, Float> {
        val ctx = context ?: MainActivity.instance
        val metrics = ctx?.resources?.displayMetrics
        if (metrics == null) return Pair(rawX, rawY)

        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        // Normalize if coordinate was specified in [0..1] fractional space
        val scaledX = if (rawX in 0f..1f && width > 0f) rawX * width else rawX
        val scaledY = if (rawY in 0f..1f && height > 0f) rawY * height else rawY

        // Clamp to physical screen bounds
        val clampedX = scaledX.coerceIn(0f, width - 1f)
        val clampedY = scaledY.coerceIn(0f, height - 1f)

        return Pair(clampedX, clampedY)
    }

    /**
     * Clicks an element by direct screen coordinates "X,Y" or visual text label via OCR.
     */
    suspend fun click(target: String, context: Context? = null): String = withContext(Dispatchers.IO) {
        val clean = target.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (clean.isBlank()) return@withContext "[Error: Click target is blank]"

        // Case 1: Coordinate click "X,Y" with density/display bounds validation
        if (clean.matches(Regex("""^\d+\s*[, ]\s*\d+$"""))) {
            val parts = clean.split(Regex("""[, ]+"""))
            val rawX = parts[0].toFloatOrNull() ?: 0f
            val rawY = parts[1].toFloatOrNull() ?: 0f
            val (x, y) = normalizeCoordinates(rawX, rawY, context)
            RootShellManager.exec("input tap ${x.toInt()} ${y.toInt()}")
            return@withContext "✔ Tapped coordinates ($x, $y) via Root Shell."
        }

        // Case 2: Visual Coordinate Mapping (OCR-based)
        Log.i(TAG, "Locating visual coordinates for '$clean' via on-device OCR...")
        val visualMatch = ScreenVisionTool.findVisualCoordinates(clean)
        if (visualMatch != null) {
            val (cx, cy) = normalizeCoordinates(visualMatch.centerX, visualMatch.centerY, context)
            RootShellManager.exec("input tap ${cx.toInt()} ${cy.toInt()}")
            return@withContext "✔ [Visual OCR]: Located '${visualMatch.text}' at (${cx.toInt()}, ${cy.toInt()}) and tapped via Root Shell."
        }

        "[Failed to find or click element matching '$clean'. Ensure the element is visible on screen or use coordinates 'x,y'.]"
    }

    /**
     * Dispatches directional swipes or explicit coordinate trajectories via Root Shell.
     */
    suspend fun swipe(directionOrCoords: String, context: Context? = null): String = withContext(Dispatchers.IO) {
        val raw = directionOrCoords.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        val clean = raw.lowercase()

        // Explicit coordinate swipe "startX,startY,endX,endY"
        if (clean.matches(Regex("""^\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*\d+(?:\s*,\s*\d+)?$"""))) {
            val parts = clean.split(',').map { it.trim().toFloatOrNull() ?: 0f }
            val (x1, y1) = normalizeCoordinates(parts[0], parts[1], context)
            val (x2, y2) = normalizeCoordinates(parts[2], parts[3], context)
            val dur = if (parts.size > 4) parts[4].toLong() else 350L

            RootShellManager.exec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $dur")
            return@withContext "✔ Swiped ($x1, $y1) → ($x2, $y2) via Root Shell."
        }

        // Density-aware directional swipe presets
        val ctx = context ?: MainActivity.instance
        val metrics = ctx?.resources?.displayMetrics
        val width = metrics?.widthPixels?.toFloat() ?: 1080f
        val height = metrics?.heightPixels?.toFloat() ?: 2400f

        val isUp = clean in setOf("up", "scroll_up", "swipe_up", "top", "اعلى", "أعلى", "فوق", "haut", "arriba")
        val isDown = clean in setOf("down", "scroll_down", "swipe_down", "bottom", "اسفل", "أسفل", "تحت", "bas", "abajo")
        val isLeft = clean in setOf("left", "scroll_left", "swipe_left", "يسار", "شمال", "gauche", "izquierda")
        val isRight = clean in setOf("right", "scroll_right", "swipe_right", "يمين", "droite", "derecha")

        if (!isUp && !isDown && !isLeft && !isRight) {
            return@withContext "[Error: Invalid swipe direction '$directionOrCoords'. Use 'up' (أعلى), 'down' (أسفل), 'left' (يسار), 'right' (يمين) or 'x1,y1,x2,y2']"
        }

        val (x1, y1, x2, y2) = when {
            isUp -> listOf(width * 0.5f, height * 0.75f, width * 0.5f, height * 0.25f)
            isDown -> listOf(width * 0.5f, height * 0.25f, width * 0.5f, height * 0.75f)
            isLeft -> listOf(width * 0.85f, height * 0.5f, width * 0.15f, height * 0.5f)
            isRight -> listOf(width * 0.15f, height * 0.5f, width * 0.85f, height * 0.5f)
            else -> listOf(width * 0.5f, height * 0.75f, width * 0.5f, height * 0.25f)
        }

        RootShellManager.exec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} 350")
        return@withContext "✔ Performed swipe $raw via Root Shell."
    }

    /**
     * Inputs text into an editable input element or currently focused field via Root Shell.
     */
    suspend fun inputText(text: String, target: String? = null, context: Context? = null): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext "[Error: Input text is blank]"

        // Visual OCR focus if target specified
        if (!target.isNullOrBlank()) {
            val visualMatch = ScreenVisionTool.findVisualCoordinates(target)
            if (visualMatch != null) {
                val (cx, cy) = normalizeCoordinates(visualMatch.centerX, visualMatch.centerY, context)
                RootShellManager.exec("input tap ${cx.toInt()} ${cy.toInt()}")
                kotlinx.coroutines.delay(200)
            }
        }

        val escaped = text.replace(" ", "%s").replace("\"", "\\\"").replace("'", "\\'")
        RootShellManager.exec("input text \"$escaped\"")
        return@withContext "✔ Injected text via Root Shell input."
    }

    /**
     * Executes standard system navigation keys (Back, Home, Recents) via Root Shell.
     */
    suspend fun pressNavigationKey(keyName: String): String = withContext(Dispatchers.IO) {
        val clean = keyName.trim().lowercase()
        val keycode = when (clean) {
            "back" -> 4
            "home" -> 3
            "recents", "app_switch" -> 187
            "enter" -> 66
            else -> return@withContext "[Error: Unknown navigation key '$keyName']"
        }
        RootShellManager.exec("input keyevent $keycode")
        return@withContext "✔ Triggered KEYCODE_$keycode via Root Shell."
    }
}

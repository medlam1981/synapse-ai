package com.synapse.localai.tools

import android.content.Context
import android.util.Log
import com.synapse.localai.MainActivity
import com.synapse.localai.RootShellManager
import com.synapse.localai.automation.SynapseAccessibilityService
import com.synapse.localai.automation.TaskCompletionProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * UiAutomationTool — Unified Multi-Layer Android UI Automation Engine.
 *
 * Implements:
 * Layer 1: Native AccessibilityService (Fast, Non-Root, Zero-Lag, Real-Time node tracking).
 * Layer 2: Visual Coordinate Mapping (On-device ML Kit OCR fallback for Flutter, React Native, Canvas, & custom views).
 * Layer 3: Root Shell / uiautomator / input fallback (Autonomous root-driven execution).
 * Density Normalization: Screen-density aware coordinate mapping across all form factors.
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
     * Dumps screen node hierarchies and visible actionable elements.
     */
    suspend fun readScreenHierarchy(context: Context?): String = withContext(Dispatchers.IO) {
        // Layer 1: Native AccessibilityService
        val service = SynapseAccessibilityService.instance
        if (service != null) {
            val hierarchy = service.dumpNodeHierarchy()
            if (!hierarchy.startsWith("[Error")) {
                return@withContext hierarchy
            }
        }

        // Layer 2: Root / ADB Shell uiautomator fallback
        try {
            val dumpPath = "/data/local/tmp/window_dump.xml"
            RootShellManager.exec("uiautomator dump $dumpPath")
            val xmlLines = RootShellManager.exec("cat $dumpPath")
            val xml = xmlLines.joinToString("\n").trim()
            if (xml.isNotBlank() && xml.contains("<node")) {
                return@withContext parseUiAutomatorXml(xml)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Root uiautomator fallback error: ${e.message}")
        }

        return@withContext "[Notice: Accessibility Service is currently disabled and root uiautomator returned no nodes. Enable ATLAS-AI in Android Settings > Accessibility for direct UI interaction.]"
    }

    /**
     * Clicks an element by text, ID, or direct screen coordinates "X,Y".
     * Automatically employs Visual Coordinate Mapping (OCR-based) for non-standard frameworks (Flutter, React Native).
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

            val service = SynapseAccessibilityService.instance
            if (service != null && service.clickCoordinates(x, y)) {
                return@withContext "✔ Clicked coordinates ($x, $y) via Accessibility Service."
            }
            RootShellManager.exec("input tap ${x.toInt()} ${y.toInt()}")
            return@withContext "✔ Tapped coordinates ($x, $y) via Root Shell."
        }

        // Case 2: Named node or resource ID via standard Accessibility Service
        val service = SynapseAccessibilityService.instance
        if (service != null && service.clickNode(clean)) {
            return@withContext "✔ Successfully clicked element matching '$clean' via Accessibility Service."
        }

        // Case 3: Visual Coordinate Mapping (OCR-based) for Flutter, React Native, Canvas, & custom components
        Log.i(TAG, "Standard accessibility node not found for '$clean'. Engaging Visual OCR mapping fallback...")
        val visualMatch = ScreenVisionTool.findVisualCoordinates(clean)
        if (visualMatch != null) {
            val (cx, cy) = normalizeCoordinates(visualMatch.centerX, visualMatch.centerY, context)
            if (service != null && service.clickCoordinates(cx, cy)) {
                return@withContext "✔ [Visual Coordinate Mapping (OCR)]: Found element '${visualMatch.text}' at visual coordinates (${cx.toInt()}, ${cy.toInt()}) [${visualMatch.bounds.left},${visualMatch.bounds.top}][${visualMatch.bounds.right},${visualMatch.bounds.bottom}] and clicked via Accessibility Service."
            }
            RootShellManager.exec("input tap ${cx.toInt()} ${cy.toInt()}")
            return@withContext "✔ [Visual Coordinate Mapping (OCR)]: Found element '${visualMatch.text}' at visual coordinates (${cx.toInt()}, ${cy.toInt()}) and tapped via Root Shell."
        }

        // Case 4: Shell UIAutomator node bounds fallback
        val center = findNodeCenterViaShell(clean)
        if (center != null) {
            val (rawCx, rawCy) = center
            val (cx, cy) = normalizeCoordinates(rawCx.toFloat(), rawCy.toFloat(), context)
            RootShellManager.exec("input tap ${cx.toInt()} ${cy.toInt()}")
            return@withContext "✔ Located '$clean' at ($cx, $cy) via UIAutomator dump and tapped via Root Shell."
        }

        "[Failed to find or click element matching '$clean' via Accessibility, Visual OCR, or UIAutomator. Use read_screen_nodes() or ocr_screen() to inspect current layout.]"
    }

    /**
     * Dispatches directional swipes or explicit coordinate trajectories.
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

            val service = SynapseAccessibilityService.instance
            if (service != null && service.swipe(x1, y1, x2, y2, dur)) {
                return@withContext "✔ Swiped ($x1, $y1) → ($x2, $y2) via Accessibility Service."
            }
            RootShellManager.exec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $dur")
            return@withContext "✔ Swiped ($x1, $y1) → ($x2, $y2) via Root Shell."
        }

        // Density-aware directional swipe presets
        val ctx = context ?: MainActivity.instance
        val metrics = ctx?.resources?.displayMetrics
        val width = metrics?.widthPixels?.toFloat() ?: 1080f
        val height = metrics?.heightPixels?.toFloat() ?: 2400f

        val service = SynapseAccessibilityService.instance

        val isUp = clean in setOf("up", "scroll_up", "swipe_up", "top", "اعلى", "أعلى", "فوق", "haut", "arriba")
        val isDown = clean in setOf("down", "scroll_down", "swipe_down", "bottom", "اسفل", "أسفل", "تحت", "bas", "abajo")
        val isLeft = clean in setOf("left", "scroll_left", "swipe_left", "يسار", "شمال", "gauche", "izquierda")
        val isRight = clean in setOf("right", "scroll_right", "swipe_right", "يمين", "droite", "derecha")

        if (!isUp && !isDown && !isLeft && !isRight) {
            return@withContext "[Error: Invalid swipe direction '$directionOrCoords'. Use 'up' (أعلى), 'down' (أسفل), 'left' (يسار), 'right' (يمين) or 'x1,y1,x2,y2']"
        }

        // Tier 1: If Up/Down, try native Accessibility Node scroll first (fast & reliable)
        if (service != null) {
            if (isUp && service.scroll(forward = true)) {
                return@withContext "✔ Scrolled up (forward) via Accessibility Service."
            }
            if (isDown && service.scroll(forward = false)) {
                return@withContext "✔ Scrolled down (backward) via Accessibility Service."
            }
        }

        // Tier 2: Density-aware human gesture swipe (350ms duration)
        val (x1, y1, x2, y2) = when {
            isUp -> listOf(width * 0.5f, height * 0.75f, width * 0.5f, height * 0.25f)
            isDown -> listOf(width * 0.5f, height * 0.25f, width * 0.5f, height * 0.75f)
            isLeft -> listOf(width * 0.85f, height * 0.5f, width * 0.15f, height * 0.5f)
            isRight -> listOf(width * 0.15f, height * 0.5f, width * 0.85f, height * 0.5f)
            else -> listOf(width * 0.5f, height * 0.75f, width * 0.5f, height * 0.25f)
        }

        if (service != null && service.swipe(x1, y1, x2, y2, 350L)) {
            return@withContext "✔ Performed swipe $raw via Accessibility Service."
        }

        // Tier 3: Root Shell input swipe
        RootShellManager.exec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} 350")
        return@withContext "✔ Performed swipe $raw via Root Shell."
    }

    /**
     * Inputs text into an editable input element or currently focused field.
     */
    suspend fun inputText(text: String, target: String? = null, context: Context? = null): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext "[Error: Input text is blank]"

        val service = SynapseAccessibilityService.instance
        if (service != null && service.inputText(text, target)) {
            return@withContext "✔ Injected text into ${target ?: "focused element"} via Accessibility Service."
        }

        // Visual OCR focus fallback if target specified
        if (!target.isNullOrBlank()) {
            val visualMatch = ScreenVisionTool.findVisualCoordinates(target)
            if (visualMatch != null) {
                val (cx, cy) = normalizeCoordinates(visualMatch.centerX, visualMatch.centerY, context)
                RootShellManager.exec("input tap ${cx.toInt()} ${cy.toInt()}")
                kotlinx.coroutines.delay(200)
            } else {
                val center = findNodeCenterViaShell(target)
                if (center != null) {
                    val (cx, cy) = normalizeCoordinates(center.first.toFloat(), center.second.toFloat(), context)
                    RootShellManager.exec("input tap ${cx.toInt()} ${cy.toInt()}")
                    kotlinx.coroutines.delay(200)
                }
            }
        }

        val escaped = text.replace(" ", "%s").replace("\"", "\\\"").replace("'", "\\'")
        RootShellManager.exec("input text \"$escaped\"")
        return@withContext "✔ Sent text via Root Shell input."
    }

    /**
     * Executes standard system navigation keys (Back, Home, Recents).
     */
    suspend fun pressNavigationKey(keyName: String): String = withContext(Dispatchers.IO) {
        val clean = keyName.trim().lowercase()
        val service = SynapseAccessibilityService.instance
        if (service != null && service.performNavigation(clean)) {
            return@withContext "✔ Performed $clean navigation via Accessibility Service."
        }

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

    /**
     * Return-to-Home workflow execution helper.
     */
    suspend fun returnToHome(context: Context?): String {
        val ok = TaskCompletionProtocol.executeReturnToHome(context)
        return if (ok) "✔ Executed Return-to-Home and refocused ATLAS-AI interface."
        else "✖ Return-to-Home workflow completed with fallback."
    }

    private fun findNodeCenterViaShell(query: String): Pair<Int, Int>? {
        try {
            val dumpPath = "/data/local/tmp/window_dump.xml"
            RootShellManager.exec("uiautomator dump $dumpPath")
            val xmlLines = RootShellManager.exec("cat $dumpPath")
            val xml = xmlLines.joinToString("\n")
            val q = Pattern.quote(query.lowercase())
            val pattern = Pattern.compile("""<node[^>]*?(?:text="[^"]*$q[^"]*"|resource-id="[^"]*$q[^"]*"|content-desc="[^"]*$q[^"]*")[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""", Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(xml)
            if (matcher.find()) {
                val left = matcher.group(1)?.toIntOrNull() ?: 0
                val top = matcher.group(2)?.toIntOrNull() ?: 0
                val right = matcher.group(3)?.toIntOrNull() ?: 0
                val bottom = matcher.group(4)?.toIntOrNull() ?: 0
                return Pair((left + right) / 2, (top + bottom) / 2)
            }
        } catch (_: Exception) {}
        return null
    }

    private fun parseUiAutomatorXml(xml: String): String {
        val sb = StringBuilder()
        sb.append("=== DETECTED UI ELEMENTS (via Root UIAutomator) ===\n")
        val pattern = Pattern.compile("""<node[^>]*?class="([^"]*)"[^>]*?(?:resource-id="([^"]*)")?[^>]*?(?:text="([^"]*)")?[^>]*?(?:content-desc="([^"]*)")?[^>]*?(?:clickable="([^"]*)")?[^>]*?(?:bounds="(\[\d+,\d+\]\[\d+,\d+\])")?""")
        val matcher = pattern.matcher(xml)
        var count = 0
        while (matcher.find() && count < 60) {
            val className = matcher.group(1)?.substringAfterLast('.') ?: "View"
            val resId = matcher.group(2)?.substringAfterLast(":id/") ?: ""
            val text = matcher.group(3) ?: ""
            val desc = matcher.group(4) ?: ""
            val clickable = matcher.group(5) == "true"
            val bounds = matcher.group(6) ?: ""

            if (text.isNotBlank() || desc.isNotBlank() || resId.isNotBlank() || clickable) {
                sb.append("• <$className")
                if (resId.isNotBlank()) sb.append(" id=\"$resId\"")
                if (text.isNotBlank()) sb.append(" text=\"${text.take(60)}\"")
                if (desc.isNotBlank()) sb.append(" desc=\"${desc.take(60)}\"")
                if (bounds.isNotBlank()) sb.append(" bounds=\"$bounds\"")
                if (clickable) sb.append(" [clickable]")
                sb.append(">\n")
                count++
            }
        }
        return if (count > 0) sb.toString().trim() else "[No visible interactive nodes parsed from UIAutomator]"
    }
}

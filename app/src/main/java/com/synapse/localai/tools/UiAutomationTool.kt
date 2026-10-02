package com.synapse.localai.tools

import android.content.Context
import android.util.Log
import com.synapse.localai.RootShellManager
import com.synapse.localai.automation.SynapseAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.regex.Pattern

/**
 * UiAutomationTool — Unified Multi-Layer Android UI Automation Engine.
 *
 * Implements:
 * Layer 1: Native AccessibilityService (Fast, Non-Root, Zero-Lag, Real-Time node tracking).
 * Layer 2: Root Shell / uiautomator / input fallback (Autonomous root-driven execution).
 */
object UiAutomationTool {

    private const val TAG = "UiAutomationTool"

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
     */
    suspend fun click(target: String): String = withContext(Dispatchers.IO) {
        val clean = target.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (clean.isBlank()) return@withContext "[Error: Click target is blank]"

        // Case 1: Coordinate click "X,Y"
        if (clean.matches(Regex("""^\d+\s*[, ]\s*\d+$"""))) {
            val parts = clean.split(Regex("""[, ]+"""))
            val x = parts[0].toFloatOrNull() ?: 0f
            val y = parts[1].toFloatOrNull() ?: 0f
            
            val service = SynapseAccessibilityService.instance
            if (service != null && service.clickCoordinates(x, y)) {
                return@withContext "✔ Clicked coordinates ($x, $y) via Accessibility Service."
            }
            // Root fallback
            RootShellManager.exec("input tap ${x.toInt()} ${y.toInt()}")
            return@withContext "✔ Tapped coordinates ($x, $y) via Root Shell."
        }

        // Case 2: Named node or resource ID
        val service = SynapseAccessibilityService.instance
        if (service != null && service.clickNode(clean)) {
            return@withContext "✔ Successfully clicked element matching '$clean' via Accessibility Service."
        }

        // Fallback: search node bounds via uiautomator dump and tap center
        val center = findNodeCenterViaShell(clean)
        if (center != null) {
            val (cx, cy) = center
            RootShellManager.exec("input tap $cx $cy")
            return@withContext "✔ Located '$clean' at ($cx, $cy) and tapped via Root Shell."
        }

        "[Failed to find or click element matching '$clean'. Use read_screen_nodes() to inspect available on-screen elements.]"
    }

    /**
     * Dispatches directional swipes or explicit coordinate trajectories.
     */
    suspend fun swipe(directionOrCoords: String): String = withContext(Dispatchers.IO) {
        val clean = directionOrCoords.trim().lowercase()

        // Explicit coordinate swipe "startX,startY,endX,endY"
        if (clean.matches(Regex("""^\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*\d+(?:\s*,\s*\d+)?$"""))) {
            val parts = clean.split(',').map { it.trim().toFloatOrNull() ?: 0f }
            val x1 = parts[0]
            val y1 = parts[1]
            val x2 = parts[2]
            val y2 = parts[3]
            val dur = if (parts.size > 4) parts[4].toLong() else 300L

            val service = SynapseAccessibilityService.instance
            if (service != null && service.swipe(x1, y1, x2, y2, dur)) {
                return@withContext "✔ Swiped ($x1, $y1) → ($x2, $y2) via Accessibility Service."
            }
            RootShellManager.exec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $dur")
            return@withContext "✔ Swiped ($x1, $y1) → ($x2, $y2) via Root Shell."
        }

        // Directional swipe presets (based on standard 1080x2400 FHD resolution)
        val (x1, y1, x2, y2) = when (clean) {
            "up", "scroll_up" -> listOf(540f, 1600f, 540f, 400f)
            "down", "scroll_down" -> listOf(540f, 600f, 540f, 1800f)
            "left" -> listOf(900f, 1200f, 180f, 1200f)
            "right" -> listOf(180f, 1200f, 900f, 1200f)
            else -> return@withContext "[Error: Invalid swipe direction '$directionOrCoords'. Use 'up', 'down', 'left', 'right' or 'x1,y1,x2,y2']"
        }

        val service = SynapseAccessibilityService.instance
        if (service != null && service.swipe(x1, y1, x2, y2, 350L)) {
            return@withContext "✔ Performed swipe $clean via Accessibility Service."
        }
        RootShellManager.exec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} 350")
        return@withContext "✔ Performed swipe $clean via Root Shell."
    }

    /**
     * Inputs text into an editable input element or currently focused field.
     */
    suspend fun inputText(text: String, target: String? = null): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext "[Error: Input text is blank]"

        val service = SynapseAccessibilityService.instance
        if (service != null && service.inputText(text, target)) {
            return@withContext "✔ Injected text into ${target ?: "focused element"} via Accessibility Service."
        }

        // Root fallback
        if (!target.isNullOrBlank()) {
            val center = findNodeCenterViaShell(target)
            if (center != null) {
                RootShellManager.exec("input tap ${center.first} ${center.second}")
                kotlinx.coroutines.delay(200)
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

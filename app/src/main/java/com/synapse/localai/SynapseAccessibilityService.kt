package com.synapse.localai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SynapseAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SynapseA11y"

        @Volatile
        var instance: SynapseAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Accessibility service destroyed")
    }

    // ===== SCREEN READING =====

    fun getScreenTree(): String {
        val root = rootInActiveWindow ?: return "[No active window]"
        val sb = StringBuilder()
        traverseTree(root, sb, 0)
        root.recycle()
        return sb.toString()
    }

    private fun traverseTree(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""

        val isInteractive = node.isClickable || node.isEditable || node.isCheckable
        val hasContent = text.isNotEmpty() || desc.isNotEmpty()

        // Only include nodes with content or that are interactive
        if (hasContent || isInteractive) {
            val indent = "  ".repeat(depth.coerceAtMost(6))
            val flags = buildList {
                if (node.isClickable) add("clickable")
                if (node.isEditable) add("editable")
                if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                if (node.isScrollable) add("scrollable")
                if (node.isFocused) add("focused")
            }
            val label = text.ifEmpty { desc }
            val flagStr = if (flags.isNotEmpty()) " [${flags.joinToString(",")}]" else ""
            val idStr = if (id.isNotEmpty()) " #$id" else ""
            sb.appendLine("$indent$cls$idStr ($rect)$flagStr ${label.take(80)}")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseTree(child, sb, depth + 1)
            child.recycle()
        }
    }

    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val texts = mutableListOf<String>()
        extractText(root, texts)
        root.recycle()
        return texts.joinToString("\n")
    }

    private fun extractText(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty()) texts.add(text)
        else if (!desc.isNullOrEmpty()) texts.add(desc)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractText(child, texts)
            child.recycle()
        }
    }

    // ===== GESTURE ACTIONS =====

    fun performClick(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Click at ($x, $y) completed")
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Click at ($x, $y) cancelled")
                callback?.invoke(false)
            }
        }, null)
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 400, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Swipe ($x1,$y1)->($x2,$y2) completed")
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    fun performLongPress(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1200))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { callback?.invoke(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { callback?.invoke(false) }
        }, null)
    }

    // ===== SCROLL =====

    fun scrollScreen(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollable(root)
        root.recycle()
        if (scrollable != null) {
            val action = if (direction.uppercase() == "UP")
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val result = scrollable.performAction(action)
            scrollable.recycle()
            return result
        }
        // Fallback: gesture swipe to scroll
        val midX = 540f
        val path = Path()
        if (direction.uppercase() == "UP") {
            path.moveTo(midX, 600f); path.lineTo(midX, 1400f)
        } else {
            path.moveTo(midX, 1400f); path.lineTo(midX, 600f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollable(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    // ===== NAVIGATION =====

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    // ===== TEXT INPUT =====

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditable(root)
        root.recycle()
        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focused.recycle()
            return result
        }
        return false
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && (node.isFocused || node.isAccessibilityFocused))
            return AccessibilityNodeInfo.obtain(node)
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditable(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    // ===== APP LAUNCH =====

    fun launchApp(appName: String): Boolean {
        try {
            val pm = packageManager
            val intent = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
            val match = intent.find {
                it.loadLabel(pm).toString().equals(appName, ignoreCase = true)
            } ?: intent.find {
                it.loadLabel(pm).toString().contains(appName, ignoreCase = true)
            }
            if (match != null) {
                val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    Log.d(TAG, "Launched: ${match.loadLabel(pm)} (${match.activityInfo.packageName})")
                    return true
                }
            }
            Log.w(TAG, "App not found: $appName")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "launchApp failed", e)
            return false
        }
    }

    // ===== FIND & CLICK BY TEXT =====

    fun clickByText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, target)
        root.recycle()
        if (node != null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            node.recycle()
            val cx = rect.centerX().toFloat()
            val cy = rect.centerY().toFloat()
            performClick(cx, cy)
            return true
        }
        return false
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.contains(target, ignoreCase = true) || desc.contains(target, ignoreCase = true)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByText(child, target)
            child.recycle()
            if (result != null) return result
        }
        return null
    }
}

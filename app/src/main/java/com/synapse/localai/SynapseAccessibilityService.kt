package com.synapse.localai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class SynapseAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SynapseA11y"
        private const val MIN_ELEMENT_SIZE = 5
        private const val MAX_COMPACT_NODES = 300
        private const val MAX_DEPTH = 12
        private const val VISIBILITY_THRESHOLD = 0.01f
        private const val REFRESH_INTERVAL_MS = 500L
        private const val MIN_FRAME_TIME_MS = 200L
        private const val POST_CHANGE_DELAY_MS = 300L
        private const val NOTIFICATION_CHANNEL_ID = "synapse_agent"
        private const val NOTIFICATION_ID = 999

        @Volatile
        var instance: SynapseAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }

    var overlayManager: OverlayManager? = null
        private set
    private var overlayVisible = false // Controls visual display only (user toggle)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastUpdateTime = 0L
    private var currentPackageName = ""
    private val isProcessing = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    // Periodic refresh — ALWAYS runs to keep element data fresh for the agent
    private val refreshRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now - lastUpdateTime >= MIN_FRAME_TIME_MS) {
                refreshOverlayInternal()
                lastUpdateTime = now
            }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlayManager = OverlayManager(this)
        // Always show overlay (agent always needs it), but drawing controlled by overlayVisible
        overlayManager?.showOverlay()
        startForegroundNotification()
        acquireWakeLock()
        startPeriodicUpdates()
        Log.i(TAG, "Accessibility service connected, overlay active, periodic updates ON")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPkg = event.packageName?.toString() ?: return

        // Skip our own package events
        if (eventPkg == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (eventPkg != currentPackageName) {
                    currentPackageName = eventPkg
                    overlayManager?.clearElements()
                    overlayManager?.refreshOverlay()
                    Log.d(TAG, "Window changed to: $eventPkg")
                }
                mainHandler.removeCallbacks(refreshRunnable)
                mainHandler.postDelayed(refreshRunnable, POST_CHANGE_DELAY_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastUpdateTime >= MIN_FRAME_TIME_MS) {
                    refreshOverlayInternal()
                    lastUpdateTime = now
                }
            }
        }
    }

    override fun onInterrupt() {
        stopPeriodicUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicUpdates()
        overlayManager?.hideOverlay()
        overlayManager = null
        releaseWakeLock()
        instance = null
        Log.i(TAG, "Accessibility service destroyed")
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "synapse:agent_wake_lock"
            ).apply {
                acquire(24 * 60 * 60 * 1000L) // 24 hours max
            }
            Log.i(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {}
    }

    private fun startPeriodicUpdates() {
        lastUpdateTime = System.currentTimeMillis()
        mainHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    private fun stopPeriodicUpdates() {
        mainHandler.removeCallbacks(refreshRunnable)
    }

    // ===== FOREGROUND NOTIFICATION =====

    private fun startForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Synapse AI Agent",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps Synapse AI agent running in background"
                    setShowBadge(false)
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }

            val openIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
                .setContentTitle("Synapse AI Active")
                .setContentText("Agent running • Tap to open")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            Log.i(TAG, "Foreground notification started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground notification: ${e.message}", e)
        }
    }

    // ===== HUD OVERLAY CONTROL =====

    /** Controls visual display of colored boxes. Data collection always runs. */
    fun setOverlayVisible(visible: Boolean) {
        overlayVisible = visible
        Log.i(TAG, "Overlay display visible: $visible")
        overlayManager?.setDrawingEnabled(visible)
        overlayManager?.refreshOverlay()
    }

    fun setOverlayScale(scale: Float) {
        overlayManager?.setHudScaleFactor(scale)
    }

    fun isOverlayVisible(): Boolean = overlayVisible

    /** Public refresh — called by agent commands */
    fun refreshOverlay() {
        refreshOverlayInternal()
    }

    private val OVERLAY_COLORS = arrayOf(
        android.graphics.Color.rgb(0, 122, 255),
        android.graphics.Color.rgb(255, 45, 85),
        android.graphics.Color.rgb(52, 199, 89),
        android.graphics.Color.rgb(255, 149, 0),
        android.graphics.Color.rgb(175, 82, 222),
        android.graphics.Color.rgb(255, 204, 0),
        android.graphics.Color.rgb(90, 200, 250),
        android.graphics.Color.rgb(88, 86, 214)
    )

    private fun getScreenBounds(): Rect {
        val dm = resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun getVisiblePercentage(rect: Rect, screenBounds: Rect): Float {
        val area = rect.width() * rect.height()
        if (area <= 0) return 0f
        if (rect.left <= 0 && rect.top <= 0 && rect.right >= screenBounds.right && rect.bottom >= screenBounds.bottom) return 1f
        val vl = maxOf(rect.left, screenBounds.left)
        val vt = maxOf(rect.top, screenBounds.top)
        val vr = minOf(rect.right, screenBounds.right)
        val vb = minOf(rect.bottom, screenBounds.bottom)
        val va = maxOf(0, vr - vl) * maxOf(0, vb - vt)
        return va.toFloat() / area.toFloat()
    }

    // ===== SCREEN READING =====

    private var lastIndexedElements = mutableListOf<IndexedElement>()

    data class IndexedElement(val index: Int, val rect: Rect, val text: String, val node: AccessibilityNodeInfo? = null)

    fun getScreenTree(): String {
        val root = rootInActiveWindow ?: return "[No active window]"
        val sb = StringBuilder()
        lastIndexedElements.clear()
        var idx = 0
        idx = traverseTree(root, sb, 0, idx)
        root.recycle()

        // Auto-refresh HUD overlay
        refreshOverlay()

        return sb.toString()
    }

    /** Build compact JSON tree from ALL windows (like droidrun portal) */
    fun getCompactScreenTreeJson(): String {
        val screenBounds = getScreenBounds()
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        val indexRef = intArrayOf(0)
        val tempElements = mutableListOf<IndexedElement>()
        
        val rootArray = JSONArray()
        
        try {
            val windowList = windows
            if (windowList.isNotEmpty()) {
                // Sort windows by layer/type if needed, but usually iterating is fine
                for (window in windowList) {
                    val root = try { window.root } catch (_: Exception) { null } ?: continue
                    val windowTree = buildCompactTreeJson(root, screenBounds, 0, counter, indexRef, tempElements)
                    root.recycle()
                    if (windowTree != null) {
                        rootArray.put(windowTree)
                    }
                }
            } else {
                // Fallback to active window
                val root = try { rootInActiveWindow } catch (_: Exception) { null }
                if (root != null) {
                    val windowTree = buildCompactTreeJson(root, screenBounds, 0, counter, indexRef, tempElements)
                    root.recycle()
                    if (windowTree != null) rootArray.put(windowTree)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error building multi-window tree: ${e.message}")
        }

        // Safely update the master list
        synchronized(lastIndexedElements) {
            lastIndexedElements.clear()
            lastIndexedElements.addAll(tempElements)
        }

        // Force a HUD redraw with the exact new elements
        if (overlayVisible) {
            overlayManager?.let { om ->
                om.clearElements()
                synchronized(lastIndexedElements) {
                    for (el in lastIndexedElements) {
                        om.addElement(el.rect, "", el.text, 0, el.index)
                    }
                }
                om.refreshOverlay()
            }
        }

        return if (rootArray.length() == 1) {
            rootArray.getJSONObject(0).toString(2)
        } else {
            JSONObject().put("windows", rootArray).toString(2)
        }
    }

    /** Internal refresh with processing guard — ALWAYS collects element data using the full tree logic */
    private fun refreshOverlayInternal() {
        if (!isProcessing.compareAndSet(false, true)) return
        try {
            getCompactScreenTreeJson() // This already handles building elements and refreshing the HUD
        } catch (e: Exception) {
            Log.e(TAG, "refreshOverlay error: ${e.message}")
        } finally {
            isProcessing.set(false)
        }
    }

    private fun buildCompactTreeJson(
        node: AccessibilityNodeInfo, screenBounds: Rect,
        depth: Int, nodeCount: java.util.concurrent.atomic.AtomicInteger,
        indexRef: IntArray,
        outElements: MutableList<IndexedElement>
    ): JSONObject? {
        if (depth > MAX_DEPTH || nodeCount.get() > MAX_COMPACT_NODES) return null

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val className = node.className?.toString() ?: ""
        if (screenBounds.width() > 0) {
            val vp = getVisiblePercentage(rect, screenBounds)
            if (vp < VISIBILITY_THRESHOLD) return null
        }

        val childrenArray = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childJson = buildCompactTreeJson(child, screenBounds, depth + 1, nodeCount, indexRef, outElements)
            child.recycle()
            if (childJson != null) childrenArray.put(childJson)
        }

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val resId = node.viewIdResourceName ?: ""
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isFocusable = node.isFocusable

        val isMeaningful = text.isNotEmpty() || desc.isNotEmpty() || resId.isNotEmpty() ||
                isClickable || isEditable || isFocusable

        if (!isMeaningful && childrenArray.length() == 0) return null
        nodeCount.incrementAndGet()

        val isInteractive = isClickable || isEditable || node.isCheckable || isFocusable
        val elementIndex = if ((isInteractive || text.isNotEmpty() || desc.isNotEmpty()) &&
            rect.width() >= MIN_ELEMENT_SIZE && rect.height() >= MIN_ELEMENT_SIZE) {
            val idx = indexRef[0]
            indexRef[0]++
            val label = if (text.isNotEmpty()) text.take(80) else desc.take(80)
            outElements.add(IndexedElement(idx, Rect(rect), label))
            idx
        } else -1

        return JSONObject().apply {
            if (resId.isNotEmpty()) put("id", resId.substringAfterLast('/'))
            if (text.isNotEmpty()) put("text", text.take(120))
            if (desc.isNotEmpty()) put("desc", desc.take(120))
            put("class", className.substringAfterLast('.'))
            put("bounds", "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]")
            if (elementIndex >= 0) put("idx", elementIndex)
            if (isClickable) put("clickable", true)
            if (isEditable) put("editable", true)
            if (isFocusable) put("focusable", true)
            if (node.isFocused) put("focused", true)
            if (node.isScrollable) put("scrollable", true)
            if (childrenArray.length() > 0) put("children", childrenArray)
        }
    }

    private fun traverseTree(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int, startIndex: Int): Int {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        var idx = startIndex

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""

        val isInteractive = node.isClickable || node.isEditable || node.isCheckable
        val hasContent = text.isNotEmpty() || desc.isNotEmpty()

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

            if (isInteractive && rect.width() > 2 && rect.height() > 2) {
                sb.appendLine("$indent[$idx] $cls$idStr ($rect)$flagStr ${label.take(80)}")
                idx++
            } else {
                sb.appendLine("$indent$cls$idStr ($rect)$flagStr ${label.take(80)}")
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            idx = traverseTree(child, sb, depth + 1, idx)
            child.recycle()
        }
        return idx
    }

    fun clickByIndex(index: Int): Boolean {
        var element: IndexedElement? = null
        synchronized(lastIndexedElements) {
            element = lastIndexedElements.find { it.index == index }
        }
        
        if (element != null) {
            val cx = element!!.rect.centerX().toFloat()
            val cy = element!!.rect.centerY().toFloat()
            performClick(cx, cy)
            return true
        }
        
        getCompactScreenTreeJson() // Force a refresh using the compact JSON logic
        
        synchronized(lastIndexedElements) {
            element = lastIndexedElements.find { it.index == index }
        }
        
        if (element != null) {
            val cx = element!!.rect.centerX().toFloat()
            val cy = element!!.rect.centerY().toFloat()
            performClick(cx, cy)
            return true
        }
        return false
    }

    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val texts = mutableListOf<String>()
        extractText(root, texts)
        root.recycle()
        return texts.joinToString("\n")
    }

    /** Get a summary of the screen state (like neural-agent's getPhoneState) */
    fun getScreenSummary(): String {
        val root = rootInActiveWindow ?: return "No active window"
        val pkg = root.packageName?.toString() ?: "unknown"
        val sb = StringBuilder()
        sb.appendLine("App: $pkg")

        // Find focused element
        val focused = findFocusedEditable(root)
        if (focused != null) {
            val focusText = focused.text?.toString() ?: ""
            val focusHint = focused.hintText?.toString() ?: ""
            sb.appendLine("Focused: editable field" + if (focusText.isNotEmpty()) " text=\"$focusText\"" else "" + if (focusHint.isNotEmpty()) " hint=\"$focusHint\"" else "")
            focused.recycle()
        }

        // Count interactive elements
        var clickableCount = 0
        var textCount = 0
        var scrollableCount = 0
        countElements(root, object : ElementCounter {
            override fun onClickable() { clickableCount++ }
            override fun onText() { textCount++ }
            override fun onScrollable() { scrollableCount++ }
        })

        sb.appendLine("Elements: $clickableCount clickable, $textCount text, $scrollableCount scrollable")
        root.recycle()
        return sb.toString()
    }

    private interface ElementCounter {
        fun onClickable()
        fun onText()
        fun onScrollable()
    }

    private fun countElements(node: AccessibilityNodeInfo, counter: ElementCounter) {
        if (node.isClickable) counter.onClickable()
        if ((node.text?.toString() ?: "").isNotEmpty()) counter.onText()
        if (node.isScrollable) counter.onScrollable()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            countElements(child, counter)
            child.recycle()
        }
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

    // ===== SCROLL & DIRECTIONAL SWIPE =====

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
        val dm = resources.displayMetrics
        val midX = dm.widthPixels / 2f
        val midY = dm.heightPixels / 2f
        val path = Path()
        when (direction.uppercase()) {
            "UP" -> { path.moveTo(midX, midY + 300); path.lineTo(midX, midY - 300) }
            "DOWN" -> { path.moveTo(midX, midY - 300); path.lineTo(midX, midY + 300) }
            "LEFT" -> { path.moveTo(midX + 300, midY); path.lineTo(midX - 300, midY) }
            "RIGHT" -> { path.moveTo(midX - 300, midY); path.lineTo(midX + 300, midY) }
            else -> { path.moveTo(midX, midY + 300); path.lineTo(midX, midY - 300) }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Directional swipe: swipe up/down/left/right from screen center */
    fun swipeDirection(direction: String, distance: Int = 600, durationMs: Long = 500): Boolean {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val half = distance / 2f
        return when (direction.uppercase()) {
            "UP" -> { performSwipe(cx, cy + half, cx, cy - half, durationMs); true }
            "DOWN" -> { performSwipe(cx, cy - half, cx, cy + half, durationMs); true }
            "LEFT" -> { performSwipe(cx + half, cy, cx - half, cy, durationMs); true }
            "RIGHT" -> { performSwipe(cx - half, cy, cx + half, cy, durationMs); true }
            else -> false
        }
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

    // ===== TEXT INPUT & KEYBOARD MANAGEMENT =====

    private var savedImeId: String? = null

    /** Called when agent starts thinking — switch to Synapse keyboard */
    fun switchToSynapseKeyboard() {
        if (savedImeId != null) return // Already switched
        val currentIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val myImeId = SynapseKeyboardIME.getImeId(this)
        if (currentIme == myImeId) return // Already our keyboard
        savedImeId = currentIme
        Log.i(TAG, "Agent starting → switching IME to Synapse: $currentIme → $myImeId")
        switchIme(myImeId)
    }

    /** Called when agent finishes — restore original keyboard */
    fun restoreKeyboard() {
        val oldIme = savedImeId ?: return
        savedImeId = null
        Log.i(TAG, "Agent done → restoring IME: $oldIme")
        switchIme(oldIme)
    }

    fun typeText(text: String): Boolean {
        // Try IME first if available and selected
        val ime = SynapseKeyboardIME.instance
        if (ime != null && SynapseKeyboardIME.isSelected(this) && ime.hasInputConnection()) {
            return ime.inputText(text, clear = true)
        }
        // Fallback to accessibility
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

    /** Switch IME using su command */
    private fun switchIme(imeId: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "settings put secure default_input_method $imeId"))
            Thread { proc.waitFor() }.apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "switchIme failed: ${e.message}")
            false
        }
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

    private val APP_NAME_MAP = mapOf(
        "chrome" to "com.android.chrome",
        "settings" to "com.android.settings",
        "camera" to "com.android.camera",
        "camera2" to "com.android.camera2",
        "phone" to "com.android.dialer",
        "dialer" to "com.android.dialer",
        "contacts" to "com.android.contacts",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "gmail" to "com.google.android.gm",
        "youtube" to "com.google.android.youtube",
        "maps" to "com.google.android.apps.maps",
        "photos" to "com.google.android.apps.photos",
        "play store" to "com.android.vending",
        "calendar" to "com.google.android.calendar",
        "clock" to "com.google.android.deskclock",
        "calculator" to "com.google.android.calculator",
        "files" to "com.google.android.documentsui",
        "file manager" to "com.google.android.documentsui",
        "whatsapp" to "com.whatsapp",
        "واتساب" to "com.whatsapp",
        "telegram" to "org.telegram.messenger",
        "تلغرام" to "org.telegram.messenger",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "tiktok" to "com.zhiliaoapp.musically"
    )

    fun launchApp(appName: String): Boolean {
        try {
            val pm = packageManager
            val normalizedName = appName.trim().lowercase()

            // Try direct package name mapping first
            val mappedPackage = APP_NAME_MAP[normalizedName]
            if (mappedPackage != null) {
                val launchIntent = pm.getLaunchIntentForPackage(mappedPackage)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    Log.d(TAG, "Launched via map: $mappedPackage")
                    return true
                }
            }

            // Fallback: search installed apps by label
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

    // ===== STOP APP (ported from neural-agent) =====

    fun stopApp(packageName: String): Boolean {
        try {
            val resolvedPkg = resolvePackageName(packageName) ?: return false
            if (resolvedPkg == "com.synapse.localai") return false

            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(resolvedPkg)
            Log.d(TAG, "Killed background processes: $resolvedPkg")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "stopApp failed", e)
            return false
        }
    }

    // ===== OPEN APP SETTINGS (ported from neural-agent) =====

    fun openAppSettings(packageName: String): Boolean {
        try {
            val resolvedPkg = resolvePackageName(packageName) ?: return false
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$resolvedPkg")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "openAppSettings failed", e)
            return false
        }
    }

    // ===== RESOLVE PACKAGE NAME (label-to-package, from neural-agent) =====

    private fun resolvePackageName(nameOrPackage: String): String? {
        val pm = packageManager
        // Check if it's already a valid package
        try {
            pm.getPackageInfo(nameOrPackage, 0)
            return nameOrPackage
        } catch (_: Exception) {}

        // Try APP_NAME_MAP
        val mapped = APP_NAME_MAP[nameOrPackage.trim().lowercase()]
        if (mapped != null) {
            try { pm.getPackageInfo(mapped, 0); return mapped } catch (_: Exception) {}
        }

        // Try label search
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val exact = apps.find { it.loadLabel(pm).toString().equals(nameOrPackage, ignoreCase = true) }
        if (exact != null) return exact.activityInfo.packageName
        val fuzzy = apps.find {
            val label = it.loadLabel(pm).toString()
            label.contains(nameOrPackage, ignoreCase = true) || nameOrPackage.contains(label, ignoreCase = true)
        }
        return fuzzy?.activityInfo?.packageName
    }

    // ===== INSTALLED PACKAGES (ported from neural-agent) =====

    fun getInstalledPackages(): String {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val arr = JSONArray()
        for (info in apps) {
            try {
                val pkgInfo = pm.getPackageInfo(info.activityInfo.packageName, 0)
                arr.put(JSONObject().apply {
                    put("package", info.activityInfo.packageName)
                    put("label", info.loadLabel(pm).toString())
                    put("version", pkgInfo.versionName ?: "")
                })
            } catch (_: Exception) {}
        }
        return arr.toString(2)
    }

    // ===== VOLUME CONTROL (ported from neural-agent) =====

    fun setVolume(percent: Int): Boolean {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val vol = (percent.coerceIn(0, 100) * max) / 100
            am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, AudioManager.FLAG_SHOW_UI)
            Log.d(TAG, "Volume set to $percent%")
            true
        } catch (e: Exception) {
            Log.e(TAG, "setVolume failed", e)
            false
        }
    }

    fun getVolume(): Int {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) (current * 100) / max else 0
        } catch (_: Exception) { 0 }
    }

    // ===== BRIGHTNESS CONTROL =====

    fun setBrightness(percent: Int): Boolean {
        return try {
            val value = (percent.coerceIn(0, 100) * 255) / 100
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            Log.d(TAG, "Brightness set to $percent%")
            true
        } catch (e: Exception) {
            Log.e(TAG, "setBrightness failed", e)
            false
        }
    }

    // ===== APPEND TEXT (instead of replace, from neural-agent) =====

    fun appendText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditable(root)
        root.recycle()
        if (focused != null) {
            val current = focused.text?.toString() ?: ""
            val hintText = focused.hintText?.toString()
            val base = if (current == hintText) "" else current
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base + text)
            }
            val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focused.recycle()
            return result
        }
        return false
    }

    // ===== DELETE TEXT (backspace, from neural-agent) =====

    fun deleteText(count: Int = 1): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditable(root)
        root.recycle()
        if (focused != null) {
            val current = focused.text?.toString() ?: ""
            val hintText = focused.hintText?.toString()
            val base = if (current == hintText) "" else current
            if (base.isEmpty()) { focused.recycle(); return false }
            val newText = base.dropLast(count.coerceAtLeast(1).coerceAtMost(base.length))
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
            val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focused.recycle()
            return result
        }
        return false
    }

    // ===== GLOBAL ACTION BY CODE (from neural-agent) =====

    fun performGlobalActionById(actionId: Int): Boolean {
        return performGlobalAction(actionId)
    }

    // ===== TAKE SCREENSHOT (from neural-agent, Android R+) =====

    fun takeScreenshotBase64(callback: (String?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            try {
                                val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                    result.hardwareBuffer, result.colorSpace
                                )
                                if (bitmap != null) {
                                    val stream = java.io.ByteArrayOutputStream()
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
                                    val base64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                                    callback(base64)
                                    bitmap.recycle()
                                } else {
                                    callback(null)
                                }
                                result.hardwareBuffer.close()
                            } catch (e: Exception) {
                                Log.e(TAG, "Screenshot encode failed", e)
                                callback(null)
                            }
                        }
                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot failed: error=$errorCode")
                            callback(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot exception", e)
                callback(null)
            }
        } else {
            callback(null)
        }
    }

    // ===== GET CURRENT APP INFO =====

    fun getCurrentPackage(): String {
        val root = rootInActiveWindow ?: return ""
        val pkg = root.packageName?.toString() ?: ""
        root.recycle()
        return pkg
    }

    // ===== OPEN URL =====

    fun openUrl(url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "openUrl failed", e)
            false
        }
    }

    // ===== OPEN SYSTEM SETTINGS =====

    fun openSystemSettings(settingsAction: String = Settings.ACTION_SETTINGS): Boolean {
        return try {
            val intent = Intent(settingsAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "openSystemSettings failed", e)
            false
        }
    }

    // ===== WIFI TOGGLE =====

    fun openWifiSettings(): Boolean = openSystemSettings(Settings.ACTION_WIFI_SETTINGS)
    fun openBluetoothSettings(): Boolean = openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
    fun openDisplaySettings(): Boolean = openSystemSettings(Settings.ACTION_DISPLAY_SETTINGS)
    fun openSoundSettings(): Boolean = openSystemSettings(Settings.ACTION_SOUND_SETTINGS)
    fun openBatterySettings(): Boolean = openSystemSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
    fun openLocationSettings(): Boolean = openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
}

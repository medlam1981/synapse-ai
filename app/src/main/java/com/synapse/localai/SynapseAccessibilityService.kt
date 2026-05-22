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
    private var overlayVisible = false 
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastUpdateTime = 0L
    private var currentPackageName = ""
    private val isProcessing = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var isScreenOn = true

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!isScreenOn) {
                mainHandler.postDelayed(this, 5000) // Sleepy mode when screen is off
                return
            }
            
            val now = System.currentTimeMillis()
            // Dynamic interval: 1s if processing, 3s if idle
            val interval = if (AgentViewModel.instance?.uiState?.value?.isProcessing == true) 1000L else 3000L
            
            if (now - lastUpdateTime >= interval) {
                refreshOverlayInternal()
                lastUpdateTime = now
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlayManager = OverlayManager(this)
        overlayManager?.showOverlay()
        startForegroundNotification()
        
        // Respect the bubble visibility setting immediately on startup
        val prefs = getSharedPreferences("synapse_config", Context.MODE_PRIVATE)
        val showBubble = prefs.getBoolean("show_floating_bubble", true)
        setBubbleVisible(showBubble)

        startPeriodicUpdates()

        
        // Register screen state receiver
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOn = true
                        Log.d(TAG, "Screen ON: Resuming updates")
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn = false
                        Log.d(TAG, "Screen OFF: Entering power-save mode")
                    }
                }
            }
        }, filter)
        
        Log.i(TAG, "Accessibility service connected, energy optimization active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isScreenOn) return
        val eventPkg = event.packageName?.toString() ?: return


        // Magic: If we see any window or content change, try to auto-select our keyboard if the picker is open.
        // The picker is usually in 'android' or 'com.android.settings' package.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            val source = event.source
            if (source != null) {
                handleImePickerDialog(source)
                source.recycle()
            }
            
            val root = try { rootInActiveWindow } catch (_: Exception) { null }
            if (root != null) {
                handleImePickerDialog(root)
                root.recycle()
            }
        }

        // Skip our own app's UI events for general tracking
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

    fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "synapse:agent_wake_lock"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max per acquisition
                Log.i(TAG, "Wake lock acquired for task processing")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "Wake lock released")
            }
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
                    "Medlam-AI Agent",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps Medlam-AI agent running in background"
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
                .setContentTitle("Medlam-AI Active")
                .setContentText("Agent running • Tap to open")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
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

    fun setBubbleVisible(visible: Boolean) {
        overlayManager?.setBubbleVisible(visible)
    }

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

    /** Build flat indexed text tree (Droidrun Format) */
    fun getDroidrunScreenTree(): String {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return "[No active window]"
        val sb = StringBuilder()
        val dm = resources.displayMetrics
        val screenBounds = Rect(0, 0, dm.widthPixels, dm.heightPixels)
        
        val tempElements = mutableListOf<IndexedElement>()
        val indexCounter = intArrayOf(1)
        
        sb.append("Current Clickable UI elements:\n")
        sb.append("'index. className: resourceId; checkedState, text - bounds(x1,y1,x2,y2)'\n")
        
        traverseAndFormatDroidrun(root, sb, screenBounds, indexCounter, tempElements)
        root.recycle()

        synchronized(lastIndexedElements) {
            lastIndexedElements.clear()
            lastIndexedElements.addAll(tempElements)
        }

        // Refresh HUD
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

        return sb.toString()
    }

    /** Internal refresh with processing guard — ALWAYS collects element data using the Droidrun logic */
    private fun refreshOverlayInternal() {
        if (!isProcessing.compareAndSet(false, true)) return
        try {
            getDroidrunScreenTree() // This already handles building elements and refreshing the HUD
        } catch (e: Exception) {
            Log.e(TAG, "refreshOverlay error: ${e.message}")
        } finally {
            isProcessing.set(false)
        }
    }

    private fun traverseAndFormatDroidrun(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        screenBounds: Rect,
        indexRef: IntArray,
        outElements: MutableList<IndexedElement>
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        if (screenBounds.width() > 0) {
            val vp = getVisiblePercentage(rect, screenBounds)
            if (vp < VISIBILITY_THRESHOLD) return
        }

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val resId = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
        val isClickable = node.isClickable || node.isCheckable
        val isEditable = node.isEditable
        
        val isMeaningful = text.isNotEmpty() || desc.isNotEmpty() || resId.isNotEmpty() || isEditable

        if (isMeaningful || isClickable) {
            val idx = indexRef[0]
            indexRef[0]++
            
            val label = text.ifEmpty { desc }.replace("\n", " ").take(60)
            outElements.add(IndexedElement(idx, Rect(rect), label))
            
            val checkedState = if (node.isCheckable) {
                if (node.isChecked) "; isChecked=True" else "; isChecked=False"
            } else ""
            
            val details = mutableListOf<String>()
            if (resId.isNotEmpty()) details.add("\"$resId\"")
            if (label.isNotEmpty()) details.add("\"$label\"")
            
            val detailsStr = if (details.isNotEmpty()) details.joinToString(", ") else ""
            
            sb.append("${idx}. ${className}: ${detailsStr}${checkedState} - (${rect.left},${rect.top},${rect.right},${rect.bottom})\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseAndFormatDroidrun(child, sb, screenBounds, indexRef, outElements)
            child.recycle()
        }
    }

    /** Helper for Droidrun parity: Get phone state summary */
    fun getDroidrunPhoneState(): String {
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        val pkg = root?.packageName?.toString() ?: "Unknown"
        val focused = root?.let { findFocusedNode(it) }
        val focusedText = focused?.text?.toString() ?: focused?.contentDescription?.toString() ?: ""
        val isKeyboardVisible = focused?.isEditable == true
        
        root?.recycle()
        focused?.recycle()

        return """**Current Phone State:**
• **App:** $pkg
• **Keyboard:** ${if (isKeyboardVisible) "Visible" else "Hidden"}
• **Focused Element:** '$focusedText'"""
    }

    private fun findFocusedNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findFocusedNode(child)
            if (res != null) {
                child.recycle()
                return res
            }
            child.recycle()
        }
        return null
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
        
        getDroidrunScreenTree() // Force a refresh using the Droidrun logic
        
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
        val rootBefore = try { rootInActiveWindow } catch (_: Exception) { null }
        val beforeHash = rootBefore?.let { getNodeHash(it) } ?: ""
        rootBefore?.recycle()

        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return false
        val scrollable = findScrollable(root)
        
        if (scrollable != null) {
            val action = if (direction.uppercase() == "UP")
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            
            scrollable.performAction(action)
            scrollable.recycle()
        }
        root.recycle()

        // Fallback/Reinforce with gesture swipe
        val dm = resources.displayMetrics
        val midX = dm.widthPixels / 2f
        val midY = dm.heightPixels / 2f
        val path = Path()
        when (direction.uppercase()) {
            "UP" -> { path.moveTo(midX, midY - 400); path.lineTo(midX, midY + 400) }
            "DOWN" -> { path.moveTo(midX, midY + 400); path.lineTo(midX, midY - 400) }
            "LEFT" -> { path.moveTo(midX + 400, midY); path.lineTo(midX - 400, midY) }
            "RIGHT" -> { path.moveTo(midX - 400, midY); path.lineTo(midX + 400, midY) }
            else -> { path.moveTo(midX, midY + 400); path.lineTo(midX, midY - 400) }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        
        // Blocking wait for gesture to finish
        val gestureDone = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { gestureDone.countDown() }
            override fun onCancelled(g: GestureDescription?) { gestureDone.countDown() }
        }, null)
        try { gestureDone.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}

        // Verification: Did it actually move?
        val rootAfter = try { rootInActiveWindow } catch (_: Exception) { null }
        val afterHash = rootAfter?.let { getNodeHash(it) } ?: ""
        rootAfter?.recycle()

        val moved = beforeHash != afterHash
        Log.d(TAG, "Scroll $direction move detection: $moved (Before: $beforeHash, After: $afterHash)")
        return moved
    }

    private fun getNodeHash(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val rect = Rect()
        node.getBoundsInScreen(rect)
        sb.append("${node.className}:${rect.top}:${rect.left};")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(getNodeHash(child))
            child.recycle()
        }
        return sb.toString().hashCode().toString()
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
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
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

    private var originalImeLabel: String? = null
    private var isRestoringKeyboard = false

    private fun getCurrentImeLabel(): String? {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val currentId = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (e: Exception) {
            null
        }
        return imm.enabledInputMethodList.find { it.id == currentId }?.loadLabel(packageManager)?.toString()
    }

    private var lastClickedIme = ""
    private var lastClickTime = 0L

    /** Automatically select the target keyboard in the picker dialog */
    private fun handleImePickerDialog(root: AccessibilityNodeInfo) {
        val targetLabel = if (isRestoringKeyboard) originalImeLabel else "Medlam-AI Keyboard"
        if (targetLabel == null) return

        val now = System.currentTimeMillis()
        if (targetLabel == lastClickedIme && now - lastClickTime < 3000) return

        Log.d(TAG, "IME Picker Detected. Searching for: $targetLabel")
        
        // Recursive search for the text since findAccessibilityNodeInfosByText can be flaky on some OEMs
        val targetNode = findNodeByTextRecursive(root, targetLabel)
        
        if (targetNode != null) {
            var clickable = targetNode
            while (clickable != null && !clickable.isClickable) {
                val p = clickable.parent
                if (p == null) break
                clickable = p
            }
            
            if (clickable != null && clickable.isClickable) {
                Log.i(TAG, "Success! Clicking $targetLabel in IME picker...")
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastClickedIme = targetLabel
                lastClickTime = now
                isRestoringKeyboard = false 
            }
            targetNode.recycle()
        }
    }

    private fun findNodeByTextRecursive(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        
        if (nodeText.contains(text, ignoreCase = true) || contentDesc.contains(text, ignoreCase = true)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByTextRecursive(child, text)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    fun switchToSynapseKeyboard() {
        if (SynapseKeyboardIME.isSelected(this)) return
        
        // Save current IME to restore later
        originalImeLabel = getCurrentImeLabel()
        isRestoringKeyboard = false
        
        Log.i(TAG, "Switching to Synapse Keyboard (Original: $originalImeLabel)")
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showInputMethodPicker()
    }

    fun restoreOriginalKeyboard() {
        if (!SynapseKeyboardIME.isSelected(this)) return
        if (originalImeLabel == null) {
            // If we don't know, just show picker for manual selection
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showInputMethodPicker()
            return
        }

        isRestoringKeyboard = true
        Log.i(TAG, "Restoring original keyboard: $originalImeLabel")
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showInputMethodPicker()
    }

    fun typeText(text: String): Boolean {
        // Try IME first
        val ime = SynapseKeyboardIME.instance
        if (ime != null && SynapseKeyboardIME.isSelected(this) && ime.hasInputConnection()) {
            return ime.inputText(text, clear = true)
        }
        // Fallback to accessibility
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return false
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
            Log.d(TAG, "launchApp requested for: '$appName'")
            val pm = packageManager
            val resolvedPkg = resolvePackageName(appName)
            
            if (resolvedPkg != null) {
                Log.d(TAG, "Resolved '$appName' to package: '$resolvedPkg'")
                val launchIntent = pm.getLaunchIntentForPackage(resolvedPkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(launchIntent)
                    Log.i(TAG, "Successfully started intent for: $resolvedPkg")
                    return true
                } else {
                    Log.w(TAG, "No launch intent found for package: $resolvedPkg")
                }
            } else {
                Log.w(TAG, "Could not resolve app name/package: '$appName'")
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "launchApp exception: ${e.message}", e)
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
        val mapped = APP_NAME_MAP[nameOrPackage.lowercase().trim()]
        if (mapped != null) return mapped

        // Search by label
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
        return list.find { it.loadLabel(pm).toString().equals(nameOrPackage, ignoreCase = true) }?.activityInfo?.packageName
            ?: list.find { it.loadLabel(pm).toString().contains(nameOrPackage, ignoreCase = true) }?.activityInfo?.packageName
    }

    fun getInstalledApps(): String {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
        
        val apps = list.map { 
            val label = it.loadLabel(pm).toString()
            val pkg = it.activityInfo.packageName
            "$label ($pkg)"
        }.sorted().joinToString(", ")
        
        return "Available Apps on Device: $apps"
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

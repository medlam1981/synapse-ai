package com.synapse.localai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

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

    private var lastIndexedElements = mutableListOf<IndexedElement>()

    data class IndexedElement(val index: Int, val rect: Rect, val text: String)

    fun getScreenTree(): String {
        val root = rootInActiveWindow ?: return "[No active window]"
        val sb = StringBuilder()
        lastIndexedElements.clear()
        var idx = 0
        idx = traverseTree(root, sb, 0, idx)
        root.recycle()
        return sb.toString()
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

            // Add index prefix for interactive elements
            if (isInteractive && rect.width() > 2 && rect.height() > 2) {
                sb.appendLine("$indent[$idx] $cls$idStr ($rect)$flagStr ${label.take(80)}")
                lastIndexedElements.add(IndexedElement(idx, rect, label.take(80)))
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
        // If index matches a cached element, click its center
        val element = lastIndexedElements.find { it.index == index }
        if (element != null) {
            val cx = element.rect.centerX().toFloat()
            val cy = element.rect.centerY().toFloat()
            performClick(cx, cy)
            return true
        }
        // Fallback: rebuild tree and try again
        getScreenTree()
        val retryElement = lastIndexedElements.find { it.index == index }
        if (retryElement != null) {
            val cx = retryElement.rect.centerX().toFloat()
            val cy = retryElement.rect.centerY().toFloat()
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

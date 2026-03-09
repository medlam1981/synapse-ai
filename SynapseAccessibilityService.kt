
package com.synapse.localai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import java.util.*

class SynapseAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var hudOverlay: View? = null
    private var statusText: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        setupHUD()
    }

    private fun setupHUD() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 50
        params.y = 100

        hudOverlay = FrameLayout(this).apply {
            setBackgroundResource(android.R.drawable.toast_frame)
            alpha = 0.8f
            setPadding(20, 10, 20, 10)
        }
        
        statusText = TextView(this).apply {
            text = "SYNAPSE HUD: ACTIVE"
            setTextColor(0xFF00E5FF.toInt())
            textSize = 10f
        }
        
        (hudOverlay as FrameLayout).addView(statusText)
        windowManager?.addView(hudOverlay, params)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // This is where we would parse the screen content
        // For now, we just update the HUD to show activity
        statusText?.text = "SYNAPSE HUD: SCANNING..."
    }

    override fun onInterrupt() {
        statusText?.text = "SYNAPSE HUD: INTERRUPTED"
    }

    // API methods to be called from the bridge
    fun performClick(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
        updateHUD("ACTION: CLICK at ($x, $y)")
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        dispatchGesture(gesture, null, null)
        updateHUD("ACTION: SWIPE")
    }

    private fun updateHUD(message: String) {
        statusText?.text = "SYNAPSE HUD: $message"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (hudOverlay != null) {
            windowManager?.removeView(hudOverlay)
        }
    }
}

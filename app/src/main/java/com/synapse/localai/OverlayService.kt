package com.synapse.localai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "synapse_overlay"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var instance: OverlayService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var overlayScale: Float = 1.0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "OverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        overlayScale = intent?.getFloatExtra("overlay_size", 1.0f) ?: 1.0f
        when (intent?.getStringExtra("action")) {
            "show" -> showBubble()
            "hide" -> hideBubble()
            "update_size" -> updateBubbleSize()
            else -> showBubble()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        hideBubble()
        instance = null
        Log.i(TAG, "OverlayService destroyed")
    }

    private fun showBubble() {
        if (bubbleView != null) return

        val size = (56 * overlayScale).toInt()

        val bubble = FrameLayout(this).apply {
            val icon = TextView(this@OverlayService).apply {
                text = "🧠"
                textSize = 20f * overlayScale
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(0xDD0A0E1A.toInt())
                setPadding(8, 8, 8, 8)
            }
            addView(icon, FrameLayout.LayoutParams(size, size))
        }

        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        // Make draggable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx < 10 && dy < 10) {
                        // Tap — open the app
                        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
                        launchIntent?.let { startActivity(it) }
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(bubble, params)
            bubbleView = bubble
            Log.d(TAG, "Bubble shown (scale=$overlayScale)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show bubble", e)
        }
    }

    private fun hideBubble() {
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        bubbleView = null
    }

    private fun updateBubbleSize() {
        hideBubble()
        showBubble()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Synapse AI Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating overlay for Synapse AI"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Synapse AI")
                .setContentText("Overlay active")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Synapse AI")
                .setContentText("Overlay active")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build()
        }
    }
}

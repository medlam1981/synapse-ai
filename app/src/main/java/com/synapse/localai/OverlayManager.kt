package com.synapse.localai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CopyOnWriteArrayList

class OverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: OverlayView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val elementRects = CopyOnWriteArrayList<ElementInfo>()
    private var isOverlayVisible = false
    private var isDrawingEnabled = false
    private var elementIndexCounter = 0

    private var hudScaleFactor: Float = 1.0f

    companion object {
        private const val TAG = "SynapseHUD"
        private const val INITIAL_STROKE_WIDTH = 3f
        private const val INITIAL_TEXT_SIZE = 24f

        private val COLOR_SCHEME = arrayOf(
            Color.rgb(0, 122, 255),    // Blue
            Color.rgb(255, 45, 85),    // Red
            Color.rgb(52, 199, 89),    // Green
            Color.rgb(255, 149, 0),    // Orange
            Color.rgb(175, 82, 222),   // Purple
            Color.rgb(255, 204, 0),    // Yellow
            Color.rgb(90, 200, 250),   // Light Blue
            Color.rgb(88, 86, 214)     // Indigo
        )
    }

    data class ElementInfo(
        val rect: Rect,
        val type: String,
        val text: String,
        val depth: Int = 0,
        val color: Int = Color.GREEN,
        val index: Int = 0
    )

    fun setHudScaleFactor(scale: Float) {
        hudScaleFactor = scale.coerceIn(0.5f, 2.0f)
        overlayView?.updateScale(hudScaleFactor)
        if (isOverlayVisible && isDrawingEnabled) refreshOverlay()
    }

    /** Controls whether colored boxes are drawn. Data collection is unaffected. */
    fun setDrawingEnabled(enabled: Boolean) {
        isDrawingEnabled = enabled
        Log.i(TAG, "Overlay drawing enabled: $enabled")
        refreshOverlay()
    }

    fun showOverlay() {
        handler.post {
            if (overlayView != null && overlayView?.parent != null) {
                isOverlayVisible = true
                Log.d(TAG, "showOverlay: already visible")
                return@post
            }
            // Remove stale view if any
            overlayView?.let {
                try { if (it.parent != null) windowManager.removeView(it) } catch (_: Exception) {}
                overlayView = null
            }
            createAndAddOverlay()
        }
    }

    private fun createAndAddOverlay() {
        try {
            val view = OverlayView(context)
            overlayView = view

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            windowManager.addView(view, params)
            isOverlayVisible = true
            Log.i(TAG, "Overlay created and added to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating overlay: ${e.message}", e)
            overlayView = null
            isOverlayVisible = false
        }
    }

    fun hideOverlay() {
        handler.post {
            try {
                overlayView?.let {
                    if (it.parent != null) windowManager.removeView(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay: ${e.message}")
            }
            overlayView = null
            isOverlayVisible = false
            elementRects.clear()
            Log.d(TAG, "Overlay hidden")
        }
    }

    fun clearElements() {
        elementRects.clear()
        elementIndexCounter = 0
    }

    fun addElement(rect: Rect, type: String, text: String, depth: Int = 0, index: Int? = null) {
        val elementIndex = index ?: elementIndexCounter++
        val colorFromScheme = COLOR_SCHEME[elementIndex % COLOR_SCHEME.size]
        elementRects.add(ElementInfo(Rect(rect), type, text, depth, colorFromScheme, elementIndex))
    }

    fun refreshOverlay() {
        handler.post {
            overlayView?.invalidate()
        }
    }

    fun getElementCount(): Int = elementRects.size
    fun isVisible(): Boolean = isOverlayVisible

    inner class OverlayView(context: Context) : View(context) {
        private var boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = INITIAL_STROKE_WIDTH * hudScaleFactor
            isAntiAlias = true
        }

        private var textPaint = Paint().apply {
            color = Color.WHITE
            textSize = INITIAL_TEXT_SIZE * hudScaleFactor
            isAntiAlias = true
            isFakeBoldText = true
        }

        private val textBackgroundPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        init {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun updateScale(scale: Float) {
            boxPaint.strokeWidth = INITIAL_STROKE_WIDTH * scale
            textPaint.textSize = INITIAL_TEXT_SIZE * scale
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!isOverlayVisible || !isDrawingEnabled) return

            val elements = elementRects.toList()
            if (elements.isEmpty()) return

            for (info in elements.sortedBy { it.depth }) {
                drawElement(canvas, info)
            }
        }

        private fun drawElement(canvas: Canvas, info: ElementInfo) {
            if (info.rect.width() <= 0 || info.rect.height() <= 0) return
            try {
                boxPaint.color = info.color
                textBackgroundPaint.color = Color.argb(200,
                    Color.red(info.color), Color.green(info.color), Color.blue(info.color))

                // Draw element border
                canvas.drawRect(info.rect, boxPaint)

                // Draw index label in top-right corner
                val label = "${info.index}"
                val tw = textPaint.measureText(label)
                val th = INITIAL_TEXT_SIZE * hudScaleFactor
                val pad = 3f
                val lx = info.rect.right - tw - pad * 2
                val ly = info.rect.top.toFloat()

                // Background for label
                canvas.drawRect(lx - pad, ly, lx + tw + pad, ly + th + pad * 2, textBackgroundPaint)
                // Label text
                canvas.drawText(label, lx, ly + th, textPaint)
            } catch (e: Exception) {
                Log.e(TAG, "Draw error #${info.index}: ${e.message}")
            }
        }
    }
}

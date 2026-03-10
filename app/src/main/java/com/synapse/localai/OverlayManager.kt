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
import android.widget.FrameLayout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class OverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: OverlayView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val elementRects = CopyOnWriteArrayList<ElementInfo>()
    private var isOverlayVisible = false
    private var isDrawingEnabled = true
    private var elementIndexCounter = 0
    private val isOverlayReady = AtomicBoolean(false)

    private var positionOffsetY = 0
    private var hudScaleFactor: Float = 1.0f

    companion object {
        private const val TAG = "SynapseHUD"
        private const val INITIAL_STROKE_WIDTH = 2f
        private const val INITIAL_TEXT_SIZE = 28f

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
        overlayView?.apply {
            boxPaint.strokeWidth = INITIAL_STROKE_WIDTH * hudScaleFactor
            textPaint.textSize = INITIAL_TEXT_SIZE * hudScaleFactor
        }
        refreshOverlay()
    }

    fun showOverlay() {
        if (overlayView != null) {
            try {
                overlayView?.parent ?: run {
                    overlayView = null
                    createAndAddOverlay()
                    return
                }
            } catch (e: Exception) {
                overlayView = null
                createAndAddOverlay()
                return
            }
            isOverlayReady.set(true)
            return
        }
        createAndAddOverlay()
    }

    private fun createAndAddOverlay() {
        try {
            overlayView = OverlayView(context).apply {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            handler.post {
                try {
                    windowManager.addView(overlayView, params)
                    isOverlayVisible = true
                    handler.postDelayed({
                        if (overlayView?.parent != null) {
                            isOverlayReady.set(true)
                        }
                    }, 100)
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding overlay: ${e.message}", e)
                    overlayView = null
                    isOverlayVisible = false
                    isOverlayReady.set(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating overlay: ${e.message}", e)
            overlayView = null
            isOverlayVisible = false
            isOverlayReady.set(false)
        }
    }

    fun hideOverlay() {
        handler.post {
            try {
                overlayView?.let {
                    if (it.parent != null) {
                        windowManager.removeView(it)
                    }
                    overlayView = null
                }
                isOverlayVisible = false
                isOverlayReady.set(false)
            } catch (e: Exception) {
                if (e is IllegalArgumentException && e.message?.contains("not attached") == true) {
                    overlayView = null
                    isOverlayVisible = false
                    isOverlayReady.set(false)
                } else {
                    Log.e(TAG, "Error removing overlay: ${e.message}", e)
                }
            }
        }
    }

    fun clearElements() {
        elementRects.clear()
        elementIndexCounter = 0
        refreshOverlay()
    }

    fun addElement(rect: Rect, type: String, text: String, depth: Int = 0, index: Int? = null) {
        val correctedRect = Rect(rect).apply { offset(0, positionOffsetY) }
        val elementIndex = index ?: elementIndexCounter++
        val colorFromScheme = COLOR_SCHEME[elementIndex % COLOR_SCHEME.size]
        elementRects.add(ElementInfo(correctedRect, type, text, depth, colorFromScheme, elementIndex))
    }

    fun refreshOverlay() {
        handler.post { overlayView?.invalidate() }
    }

    fun getElementCount(): Int = elementRects.size

    fun setDrawingEnabled(enabled: Boolean) {
        isDrawingEnabled = enabled
        refreshOverlay()
    }

    fun isVisible(): Boolean = isOverlayVisible

    inner class OverlayView(context: Context) : FrameLayout(context) {
        var boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = INITIAL_STROKE_WIDTH * hudScaleFactor
            isAntiAlias = true
        }

        var textPaint = Paint().apply {
            color = Color.WHITE
            textSize = INITIAL_TEXT_SIZE * hudScaleFactor
            isAntiAlias = true
        }

        private val textBackgroundPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        init {
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            if (!isOverlayVisible || !isDrawingEnabled) return
            super.onDraw(canvas)
            if (elementRects.isEmpty()) return

            val sortedElements = elementRects.sortedBy { it.depth }
            for (elementInfo in sortedElements) {
                drawElement(canvas, elementInfo)
            }
        }

        private fun drawElement(canvas: Canvas, info: ElementInfo) {
            try {
                if (info.rect.width() <= 0 || info.rect.height() <= 0) return

                val c = Color.argb(255, Color.red(info.color), Color.green(info.color), Color.blue(info.color))
                boxPaint.color = c
                textBackgroundPaint.color = Color.argb(200, Color.red(info.color), Color.green(info.color), Color.blue(info.color))

                canvas.drawRect(info.rect, boxPaint)

                val displayText = "${info.index}"
                val textWidth = textPaint.measureText(displayText)
                val textHeight = INITIAL_TEXT_SIZE * hudScaleFactor + 4f

                val textX = info.rect.right - textWidth - 4f
                val textY = info.rect.top + textHeight

                val bgPad = 4f
                val bgRect = Rect(
                    (textX - bgPad).toInt(),
                    (textY - textHeight).toInt(),
                    (textX + textWidth + bgPad).toInt(),
                    (textY + bgPad).toInt()
                )
                canvas.drawRect(bgRect, textBackgroundPaint)
                canvas.drawText(displayText, textX, textY - bgPad, textPaint)
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing element ${info.index}: ${e.message}")
            }
        }
    }
}

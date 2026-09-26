package com.synapse.localai.latex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * Native Canvas/Span-based LaTeX Math Renderer with in-memory LRU caching.
 *
 * Avoids WebViews to ensure zero jank and 60fps scrolling on 4GB RAM devices.
 * Uses JLatexMath-Android to pre-render formulas to cached Bitmaps.
 */
object MathRenderer {

    private const val TAG = "MathRenderer"

    // LRU Cache for rendered equation bitmaps.
    // Each bitmap is typically ~5-30KB; 150 entries take < 3MB in memory.
    private const val MAX_CACHE_ENTRIES = 150

    data class RenderedMath(
        val bitmap: ImageBitmap,
        val widthPx: Int,
        val heightPx: Int,
        val intrinsicWidthDp: Float,
        val intrinsicHeightDp: Float
    )

    private val cache = object : LruCache<String, RenderedMath>(MAX_CACHE_ENTRIES) {}
    private var initialized = false

    fun init(context: Context) {
        if (!initialized) {
            try {
                JLatexMathAndroid.init(context.applicationContext)
                initialized = true
            } catch (t: Throwable) {
                Log.w(TAG, "JLatexMathAndroid.init notice: ${t.message}")
            }
        }
    }

    /**
     * Renders LaTeX formula to an ImageBitmap with the given text size and color.
     * Caches results in the in-memory LRU cache.
     * Returns null if formula is empty or has syntax errors.
     */
    fun renderFormula(
        formula: String,
        textSizePx: Float,
        colorArgb: Int,
        density: Float = 2.5f,
        isBlock: Boolean = false
    ): RenderedMath? {
        val clean = formula.trim()
        if (clean.isEmpty()) return null

        val cacheKey = "$clean|$textSizePx|$colorArgb|$isBlock"
        synchronized(cache) {
            val existing = cache.get(cacheKey)
            if (existing != null) return existing
        }

        return try {
            val padding = if (isBlock) 8 else 2
            val align = if (isBlock) JLatexMathDrawable.ALIGN_CENTER else JLatexMathDrawable.ALIGN_LEFT

            val drawable = JLatexMathDrawable.builder(clean)
                .textSize(textSizePx)
                .color(colorArgb)
                .padding(padding)
                .background(AndroidColor.TRANSPARENT)
                .align(align)
                .build()

            val w = drawable.intrinsicWidth
            val h = drawable.intrinsicHeight

            if (w <= 0 || h <= 0) return null

            // Maximum bounds protection
            val safeW = w.coerceIn(1, 2048)
            val safeH = h.coerceIn(1, 1024)

            val bitmap = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, safeW, safeH)
            drawable.draw(canvas)

            val imageBitmap = bitmap.asImageBitmap()
            val densitySafe = if (density > 0f) density else 2.5f
            val rendered = RenderedMath(
                bitmap = imageBitmap,
                widthPx = safeW,
                heightPx = safeH,
                intrinsicWidthDp = safeW / densitySafe,
                intrinsicHeightDp = safeH / densitySafe
            )

            synchronized(cache) {
                cache.put(cacheKey, rendered)
            }
            rendered
        } catch (t: Throwable) {
            Log.w(TAG, "LaTeX render failed for: \"$clean\" - ${t.message}")
            null
        }
    }

    fun clearCache() {
        synchronized(cache) {
            cache.evictAll()
        }
    }
}

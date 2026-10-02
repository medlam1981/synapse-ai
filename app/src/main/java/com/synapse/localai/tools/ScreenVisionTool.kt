package com.synapse.localai.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.synapse.localai.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ScreenVisionTool — Autonomous Screen Buffer Capture & Vision/OCR Processing Engine.
 *
 * Implements:
 * 1. `capture_screen(save_path)`: Captures screen framebuffer directly to PNG format.
 * 2. `ocr_screen(image_path)`: Visual perception & text recognition engine extracting layout,
 *    text lines, and actionable UI bounding boxes.
 * 3. `findVisualCoordinates(query)`: Visual coordinate mapping fallback using on-device ML Kit OCR
 *    to locate non-standard UI elements (Flutter, React Native, Canvas) lacking Accessibility IDs.
 */
object ScreenVisionTool {

    private const val TAG = "ScreenVisionTool"
    const val DEFAULT_SCREENSHOT_PATH = "/sdcard/Medlam-Ai/scratch/screenshot.png"

    data class VisualElement(
        val text: String,
        val bounds: Rect,
        val centerX: Float,
        val centerY: Float
    )

    /**
     * Captures the active Android screen buffer and persists it to disk.
     */
    suspend fun captureScreen(savePath: String? = null): String = withContext(Dispatchers.IO) {
        val targetPath = savePath?.trim()?.ifBlank { null } ?: DEFAULT_SCREENSHOT_PATH
        val file = File(targetPath)
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }

        try {
            if (file.exists()) file.delete()

            // Primary: Root/Shell screencap
            RootShellManager.exec("screencap -p \"${file.absolutePath}\"")
            if (file.exists() && file.length() > 1024) {
                val sizeKb = file.length() / 1024
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                val dimensions = if (options.outWidth > 0 && options.outHeight > 0) "${options.outWidth}x${options.outHeight}" else "FHD"
                return@withContext "✔ Screen Buffer Captured Successfully:\n• Path: ${file.absolutePath}\n• Dimensions: $dimensions\n• Size: $sizeKb KB"
            }

            // Fallback: standard shell execution
            val p = ProcessBuilder("sh", "-c", "screencap -p \"${file.absolutePath}\"").start()
            p.waitFor(3, TimeUnit.SECONDS)

            if (file.exists() && file.length() > 1024) {
                val sizeKb = file.length() / 1024
                return@withContext "✔ Screen Buffer Captured Successfully:\n• Path: ${file.absolutePath}\n• Size: $sizeKb KB"
            }

            "[Error: Failed to capture screen buffer. Ensure storage write permissions and root/ADB privileges are active.]"
        } catch (e: Exception) {
            Log.e(TAG, "Screen capture failed: ${e.message}")
            "[Screen Capture Error: ${e.message}]"
        }
    }

    /**
     * Captures and decodes the active screen buffer into an in-memory Bitmap.
     */
    suspend fun captureScreenBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        val path = DEFAULT_SCREENSHOT_PATH
        val result = captureScreen(path)
        if (result.startsWith("[Error")) return@withContext null
        val f = File(path)
        if (f.exists() && f.length() > 0) {
            try {
                BitmapFactory.decodeFile(f.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode screenshot bitmap: ${e.message}")
                null
            }
        } else null
    }

    /**
     * Extracts visual text elements and bounding boxes using on-device ML Kit OCR.
     */
    suspend fun extractVisualElementsWithMlKit(bitmap: Bitmap): List<VisualElement> = withContext(Dispatchers.Default) {
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = Tasks.await(recognizer.process(image), 4000, TimeUnit.MILLISECONDS)
            val elements = mutableListOf<VisualElement>()

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val lineBox = line.boundingBox
                    if (lineBox != null && line.text.isNotBlank()) {
                        elements.add(
                            VisualElement(
                                text = line.text.trim(),
                                bounds = lineBox,
                                centerX = lineBox.exactCenterX(),
                                centerY = lineBox.exactCenterY()
                            )
                        )
                    }
                }
            }
            elements
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit OCR processing error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Finds screen coordinates of an element by visual OCR text mapping.
     * Essential for Flutter, React Native, Canvas, and custom UI components without accessibility IDs.
     */
    suspend fun findVisualCoordinates(query: String, existingBitmap: Bitmap? = null): VisualElement? = withContext(Dispatchers.IO) {
        val bitmap = existingBitmap ?: captureScreenBitmap() ?: return@withContext null
        val cleanQuery = query.trim().lowercase()
        val elements = extractVisualElementsWithMlKit(bitmap)

        // 1. Exact match
        elements.firstOrNull { it.text.equals(cleanQuery, ignoreCase = true) }?.let { return@withContext it }

        // 2. Contains match
        elements.firstOrNull { it.text.lowercase().contains(cleanQuery) }?.let { return@withContext it }

        // 3. Substring / multi-word match
        elements.firstOrNull { elem ->
            val words = cleanQuery.split(Regex("\\s+"))
            words.isNotEmpty() && words.all { w -> elem.text.lowercase().contains(w) }
        }?.let { return@withContext it }

        null
    }

    /**
     * Analyzes visual elements, extracts text labels, and detects interactive bounding boxes.
     */
    suspend fun ocrScreen(imagePath: String? = null, context: Context? = null): String = withContext(Dispatchers.IO) {
        val path = if (imagePath.isNullOrBlank()) {
            val captureMsg = captureScreen(DEFAULT_SCREENSHOT_PATH)
            if (captureMsg.startsWith("[Error")) return@withContext captureMsg
            DEFAULT_SCREENSHOT_PATH
        } else {
            imagePath.trim()
        }

        val file = File(path)
        if (!file.exists()) {
            return@withContext "[Error: Image file does not exist at '$path']"
        }

        val sb = StringBuilder()
        sb.append("=== SCREEN VISION & OCR REPORT ===\n")
        sb.append("• Target File: ${file.absolutePath} (${file.length() / 1024} KB)\n")

        val bitmap = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) { null }

        if (bitmap != null) {
            sb.append("• Resolution: ${bitmap.width} x ${bitmap.height} px\n")
            val visualElements = extractVisualElementsWithMlKit(bitmap)
            if (visualElements.isNotEmpty()) {
                sb.append("\n[Visual OCR Elements (ML Kit)]: Detected ${visualElements.size} text blocks\n")
                visualElements.take(40).forEach { elem ->
                    sb.append("• \"${elem.text}\" at [${elem.bounds.left},${elem.bounds.top}][${elem.bounds.right},${elem.bounds.bottom}] Center: (${elem.centerX.toInt()}, ${elem.centerY.toInt()})\n")
                }
                sb.append("\n• Interaction: You can click any visual element using click(\"${visualElements.first().text}\") or click(\"${visualElements.first().centerX.toInt()},${visualElements.first().centerY.toInt()}\").")
                return@withContext sb.toString().trim()
            }
        }

        // Secondary: Correlate with active UI layout hierarchy
        try {
            val hierarchy = UiAutomationTool.readScreenHierarchy(context)
            if (!hierarchy.startsWith("[Notice") && !hierarchy.startsWith("[Error")) {
                sb.append("\n[Detected On-Screen Text & Interactive Elements]:\n")
                sb.append(hierarchy)
                return@withContext sb.toString().trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hierarchy extraction in OCR error: ${e.message}")
        }

        // Python OCR fallback if Tesseract/OCR is installed in Termux
        try {
            val pyOcrScript = """
import sys, os
try:
    from PIL import Image
    import pytesseract
    text = pytesseract.image_to_string(Image.open('${file.absolutePath}'))
    print(text.strip())
except Exception as e:
    print(f'[No Python OCR library available: {e}]')
""".trimIndent()
            val pyResult = PythonTool.execute(pyOcrScript)
            if (pyResult.isNotBlank() && !pyResult.contains("No Python OCR library")) {
                sb.append("\n[Optical Character Recognition (OCR) Output]:\n")
                sb.append(pyResult)
                return@withContext sb.toString().trim()
            }
        } catch (_: Exception) {}

        sb.append("\n• Status: Screen buffer verified. To interact with elements on screen, use click(\"label\") or click(\"x,y\").")
        return@withContext sb.toString().trim()
    }
}

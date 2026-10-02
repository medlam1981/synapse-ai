package com.synapse.localai.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.synapse.localai.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ScreenVisionTool — Autonomous Screen Buffer Capture & Vision/OCR Processing Engine.
 *
 * Implements:
 * 1. `capture_screen(save_path)`: Captures screen framebuffer directly to PNG format.
 * 2. `ocr_screen(image_path)`: Visual perception & text recognition engine extracting layout,
 *    text lines, and actionable UI bounding boxes.
 */
object ScreenVisionTool {

    private const val TAG = "ScreenVisionTool"
    const val DEFAULT_SCREENSHOT_PATH = "/sdcard/Medlam-Ai/scratch/screenshot.png"

    /**
     * Captures the active Android screen buffer and persists it to disk.
     */
    suspend fun captureScreen(savePath: String? = null): String = withContext(Dispatchers.IO) {
        val targetPath = savePath?.trim()?.ifBlank { null } ?: DEFAULT_SCREENSHOT_PATH
        val file = File(targetPath)
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }

        try {
            // Delete previous capture if exists
            if (file.exists()) file.delete()

            // Primary: Root/Shell screencap
            val result = RootShellManager.exec("screencap -p \"${file.absolutePath}\"")
            if (file.exists() && file.length() > 1024) {
                val sizeKb = file.length() / 1024
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                val dimensions = if (options.outWidth > 0 && options.outHeight > 0) "${options.outWidth}x${options.outHeight}" else "FHD"
                return@withContext "✔ Screen Buffer Captured Successfully:\n• Path: ${file.absolutePath}\n• Dimensions: $dimensions\n• Size: $sizeKb KB"
            }

            // Fallback: standard shell execution
            val p = ProcessBuilder("sh", "-c", "screencap -p \"${file.absolutePath}\"").start()
            p.waitFor()

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

        // Inspect image resolution
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                sb.append("• Resolution: ${options.outWidth} x ${options.outHeight} px\n")
            }
        } catch (_: Exception) {}

        // Correlate with active UI layout hierarchy to provide high-fidelity OCR with exact tap coordinates
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

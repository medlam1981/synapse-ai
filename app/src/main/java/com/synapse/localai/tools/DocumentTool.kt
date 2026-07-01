package com.synapse.localai.tools

import android.content.Context
import android.net.Uri
import android.util.Log
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

object DocumentTool {
    private const val TAG = "DocumentTool"

    suspend fun extractTextFromUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val type = contentResolver.getType(uri) ?: ""
        
        try {
            if (type == "application/pdf" || uri.path?.endsWith(".pdf", true) == true) {
                return@withContext extractPdf(context, uri)
            } else {
                // Try reading as plain text
                val inputStream = contentResolver.openInputStream(uri)
                val text = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                
                // Truncate to avoid extreme token usage if it's a massive raw text file
                return@withContext if (text.length > 30000) {
                    text.substring(0, 30000) + "\n...[TRUNCATED TO SAVE TOKENS]..."
                } else text
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract text: ${e.message}")
            return@withContext "Error reading file: ${e.message}"
        }
    }

    private fun extractPdf(context: Context, uri: Uri): String {
        var inputStream: InputStream? = null
        var reader: PdfReader? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            reader = PdfReader(inputStream)
            val pages = reader.numberOfPages
            val extractedText = java.lang.StringBuilder()
            
            // Extract text from the first few pages and the last page to save tokens
            // This is a "NotebookLM-lite" approach for mobile: compress context smartly.
            val maxPagesToRead = 10
            val pagesToRead = mutableListOf<Int>()
            
            if (pages <= maxPagesToRead) {
                for (i in 1..pages) pagesToRead.add(i)
            } else {
                // Read start, middle, and end for long PDFs
                for (i in 1..4) pagesToRead.add(i)
                val mid = pages / 2
                pagesToRead.add(mid)
                pagesToRead.add(mid + 1)
                for (i in pages - 3..pages) pagesToRead.add(i)
            }

            for (i in pagesToRead.distinct().sorted()) {
                val pageText = PdfTextExtractor.getTextFromPage(reader, i)
                extractedText.append(pageText).append("\n\n")
            }
            
            val finalString = extractedText.toString().trim()
            if (finalString.isEmpty()) return "PDF contains no extractable text (might be scanned images)."
            
            return if (pages > maxPagesToRead) {
                "$finalString\n\n[NOTE: This is a large PDF ($pages pages). Only the intro, middle, and conclusion were extracted to conserve processing power.]"
            } else finalString
            
        } catch (e: Exception) {
            Log.e(TAG, "PDF extraction error", e)
            throw e
        } finally {
            try { reader?.close() } catch (e: Exception) {}
            try { inputStream?.close() } catch (e: Exception) {}
        }
    }
}

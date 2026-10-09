package com.synapse.localai

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Xml
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class UploadedAttachment(
    val displayName: String,
    val text: String = "",
    val imageBase64: String? = null
)

object UploadedFileReader {
    private const val MAX_FILE_BYTES = 15 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 8_000

    suspend fun read(context: Context, uri: Uri, isGemini: Boolean): UploadedAttachment =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                ?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "attachment"
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_FILE_BYTES) { "The attached file is larger than 15 MB." }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: error("Could not read the attached file.")
            require(bytes.isNotEmpty()) { "The attached file is empty." }

            val extension = displayName.substringAfterLast('.', "").lowercase()
            val mimeType = resolver.getType(uri).orEmpty()
            if (mimeType.startsWith("image/") || extension in setOf("png", "jpg", "jpeg", "webp", "bmp")) {
                val imageBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, imageBounds)
                require(imageBounds.outWidth > 0 && imageBounds.outHeight > 0) {
                    "The attached image could not be decoded."
                }
                if (isGemini) {
                    return@withContext UploadedAttachment(
                        displayName = displayName,
                        imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    )
                }
                val ocrText = recognizeImageText(bytes)
                require(ocrText.isNotBlank()) {
                    "The local model cannot inspect this image because no readable text was found. Use Gemini for image understanding."
                }
                return@withContext UploadedAttachment(displayName, ocrText.take(MAX_TEXT_CHARS))
            }

            val text = when (extension) {
                "pdf" -> extractPdfText(bytes)
                "docx" -> extractDocxText(bytes)
                "xlsx" -> extractXlsxText(bytes)
                "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "kt", "java", "py", "js", "ts",
                "jsx", "tsx", "css", "yaml", "yml", "log", "sh", "sql" -> decodeText(bytes)
                else -> if (mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("xml")) {
                    decodeText(bytes)
                } else {
                    error("Unsupported attachment type: $displayName. Attach a text file, PDF, DOCX, XLSX, or image.")
                }
            }.trim()
            require(text.isNotBlank()) { "No readable text was found in $displayName." }
            UploadedAttachment(displayName, text.take(MAX_TEXT_CHARS))
        }

    private fun decodeText(bytes: ByteArray): String {
        require(bytes.none { it == 0.toByte() }) { "The attached file is binary, not readable text." }
        return bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun extractPdfText(bytes: ByteArray): String {
        val reader = PdfReader(bytes)
        return try {
            val builder = StringBuilder()
            val pageLimit = minOf(reader.numberOfPages, 30)
            for (page in 1..pageLimit) {
                builder.append(PdfTextExtractor.getTextFromPage(reader, page)).append('\n')
                if (builder.length >= MAX_TEXT_CHARS) break
            }
            if (reader.numberOfPages > pageLimit) builder.append("\n[Only the first $pageLimit pages were extracted.]")
            builder.toString()
        } finally {
            reader.close()
        }
    }

    private fun extractDocxText(bytes: ByteArray): String {
        val documentXml = unzipEntries(bytes)["word/document.xml"]
            ?: error("The DOCX file does not contain a Word document body.")
        return xmlText(documentXml)
    }

    private fun extractXlsxText(bytes: ByteArray): String {
        val entries = unzipEntries(bytes)
        val sheetXml = entries["xl/worksheets/sheet1.xml"]
            ?: error("The XLSX file does not contain a worksheet.")
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val rows = Regex("""<row\b[^>]*>([\s\S]*?)</row>""").findAll(sheetXml).map { rowMatch ->
            Regex("""<c\b([^>]*)>([\s\S]*?)</c>""").findAll(rowMatch.groupValues[1]).map { cellMatch ->
                val attrs = cellMatch.groupValues[1]
                val body = cellMatch.groupValues[2]
                val value = Regex("""<v>([\s\S]*?)</v>""").find(body)?.groupValues?.get(1)
                when {
                    Regex("""\bt="s"""").containsMatchIn(attrs) ->
                        value?.toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty()
                    Regex("""\bt="inlineStr"""").containsMatchIn(attrs) -> xmlText(body)
                    else -> value.orEmpty()
                }
            }.joinToString("\t")
        }
        return rows.joinToString("\n")
    }

    private fun parseSharedStrings(xml: String): List<String> =
        Regex("""<si\b[^>]*>([\s\S]*?)</si>""").findAll(xml).map { xmlText(it.groupValues[1]) }.toList()

    private fun xmlText(xml: String): String {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), "UTF-8")
        val output = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.TEXT) output.append(parser.text)
            if (event == XmlPullParser.END_TAG && parser.name in setOf("p", "row", "si")) output.append('\n')
            event = parser.next()
        }
        return output.toString()
    }

    private fun unzipEntries(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml" ||
                    entry.name == "xl/worksheets/sheet1.xml" ||
                    entry.name == "xl/sharedStrings.xml"
                ) {
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        return entries
    }

    private suspend fun recognizeImageText(bytes: ByteArray): String {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("The attached image could not be decoded.")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) continuation.resume(result.text)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }
}

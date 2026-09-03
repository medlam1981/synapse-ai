package com.synapse.localai

import android.util.Log
import java.io.File

/**
 * Data structure representing a parsed code block along with its latched target file path.
 */
data class ExtractedCodeBlock(
    val language: String,
    val code: String,
    val targetPath: String,
    val rawMatch: String
)

/**
 * ToolExecutor — File Extraction & Autonomous Writing Engine for MEDLAM AI.
 *
 * Responsibilities:
 * 1. Parse AI responses for file path markers immediately preceding code blocks.
 * 2. Latch detected file paths strictly to the immediately subsequent code block.
 *    Clear path buffer on block close to prevent directory-inheritance bugs.
 * 3. Autonomous fallback: If no explicit path is given, auto-assign valid path in /sdcard/Medlam-Ai/.
 * 4. Enforce valid executable code (HTML, CSS, JS, Kotlin, Python, etc.).
 * 5. Automatically create missing parent directories (mkdirs()) before writing.
 * 6. Write extracted files directly to /sdcard/Medlam-Ai/ project directory.
 */
object ToolExecutor {

    private const val TAG = "ToolExecutor"
    const val BASE_DIR = "/sdcard/Medlam-Ai"

    // Patterns that suggest a file path label before a code block
    private val FILE_PATH_PATTERNS = listOf(
        Regex("""(?:file|path|save to|write to|create file|filename)[:\s]+[`"']?([^\s`"'\n]+\.[a-zA-Z0-9]{1,10})[`"']?""", RegexOption.IGNORE_CASE),
        Regex("""→\s*([^\s\n]+\.[a-zA-Z0-9]{1,10})"""),
        Regex("""#\s*([\w/.-]+\.[a-zA-Z0-9]{1,10})\s*$""", RegexOption.MULTILINE),
        Regex("""`([\w/.-]+\.[a-zA-Z0-9]{1,10})`"""),
        Regex("""(/sdcard/[^\s\n"'`]+\.[a-zA-Z0-9]{1,10})"""),
        Regex("""([\w/.-]+/[\w.-]+\.[a-zA-Z0-9]{1,10})""")
    )

    // Extensions that represent executable web/UI content
    private val WEB_EXTENSIONS = setOf("html", "htm", "css", "js", "ts", "jsx", "tsx", "vue", "svelte")

    // Extensions that MUST contain real code (not conversational text)
    private val CODE_EXTENSIONS = WEB_EXTENSIONS + setOf(
        "kt", "java", "py", "cpp", "c", "h", "rs", "go", "rb", "php",
        "swift", "sh", "bash", "sql", "json", "xml", "yaml", "yml"
    )

    /**
     * Parse all code blocks from text, resolving target file path for each block.
     */
    fun parseCodeBlocksWithPaths(text: String): List<ExtractedCodeBlock> {
        val blocks = mutableListOf<ExtractedCodeBlock>()
        val lines = text.lines()
        var pendingFilePath: String? = null
        var inCodeBlock = false
        var codeLanguage = ""
        val codeSb = StringBuilder()
        val rawSb = StringBuilder()

        for (line in lines) {
            if (!inCodeBlock) {
                val detectedPath = detectFilePath(line)
                if (detectedPath != null) {
                    pendingFilePath = detectedPath
                }

                if (line.trimStart().startsWith("```")) {
                    inCodeBlock = true
                    codeLanguage = line.trim().removePrefix("```").lowercase().trim()
                    codeSb.clear()
                    rawSb.clear()
                    rawSb.appendLine(line)
                }
            } else {
                rawSb.appendLine(line)
                if (line.trimStart().startsWith("```")) {
                    inCodeBlock = false
                    val code = codeSb.toString().trim()
                    val lang = codeLanguage.ifBlank { "text" }
                    val targetPath = pendingFilePath ?: defaultPathForLanguage(lang, code)

                    if (code.isNotBlank()) {
                        blocks.add(
                            ExtractedCodeBlock(
                                language = lang,
                                code = code,
                                targetPath = targetPath,
                                rawMatch = rawSb.toString()
                            )
                        )
                    }

                    // CLEAR latched path buffer on block close
                    pendingFilePath = null
                    codeLanguage = ""
                    codeSb.clear()
                    rawSb.clear()
                } else {
                    codeSb.appendLine(line)
                }
            }
        }

        return blocks
    }

    /**
     * Extract and write all detected file-code pairs from an AI response.
     * Writes autonomously to /sdcard/Medlam-Ai/.
     * @return List of written file paths, or empty if nothing was extracted.
     */
    fun extractAndWriteFiles(response: String): List<String> {
        val writtenFiles = mutableListOf<String>()
        val blocks = parseCodeBlocksWithPaths(response)

        for (block in blocks) {
            val ext = block.targetPath.substringAfterLast('.').lowercase()
            if (ext in CODE_EXTENSIONS && isLikelyCode(block.code, ext)) {
                val written = writeFile(block.targetPath, block.code)
                if (written != null) {
                    writtenFiles.add(written)
                }
            }
        }

        return writtenFiles
    }

    /**
     * Assigns a smart default filename when no explicit path was provided.
     */
    fun defaultPathForLanguage(language: String, code: String): String {
        val lower = language.lowercase().trim()
        val filename = when {
            lower == "html" || lower == "htm" || code.contains("<!DOCTYPE") || code.contains("<html") -> "index.html"
            lower == "css" -> "styles.css"
            lower == "javascript" || lower == "js" -> "app.js"
            lower == "typescript" || lower == "ts" -> "app.ts"
            lower == "python" || lower == "py" -> "main.py"
            lower == "kotlin" || lower == "kt" -> "App.kt"
            lower == "java" -> "Main.java"
            lower == "bash" || lower == "sh" -> "script.sh"
            lower == "json" -> "data.json"
            lower == "xml" -> "layout.xml"
            else -> "output.txt"
        }
        return "$BASE_DIR/$filename"
    }

    fun detectFilePath(line: String): String? {
        if (isLikelyConversation(line)) return null

        for (pattern in FILE_PATH_PATTERNS) {
            val match = pattern.find(line) ?: continue
            val raw = match.groupValues.getOrNull(1)?.trim() ?: continue
            if (raw.isBlank() || raw.length < 4) continue

            val normalized = when {
                raw.startsWith("/sdcard/") -> raw
                raw.startsWith("~/") -> "/sdcard/${raw.removePrefix("~/")}"
                raw.contains("/") -> "$BASE_DIR/$raw"
                else -> "$BASE_DIR/$raw"
            }

            val ext = normalized.substringAfterLast('.')
            if (ext.length in 1..10 && !normalized.contains(" ")) {
                return normalized
            }
        }
        return null
    }

    /**
     * Write content to the resolved file path, auto-creating parent dirs.
     * @return The absolute path that was written, or null on failure.
     */
    fun writeFile(rawPath: String, content: String): String? {
        return try {
            val resolvedPath = when {
                rawPath.startsWith("/sdcard/") -> rawPath
                rawPath.startsWith("~/") -> "/sdcard/${rawPath.removePrefix("~/")}"
                rawPath.startsWith("/") -> rawPath
                else -> "$BASE_DIR/$rawPath"
            }

            val file = File(resolvedPath)
            // Auto-create missing parent directories
            file.parentFile?.let { parent ->
                if (!parent.exists()) {
                    val created = parent.mkdirs()
                    Log.d(TAG, "Created directories: ${parent.absolutePath} (success=$created)")
                }
            }

            file.writeText(content)
            Log.i(TAG, "✅ File written: ${file.absolutePath} (${content.length} chars)")
            fileLog("Written: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write file '$rawPath': ${e.message}")
            null
        }
    }

    private fun isLikelyConversation(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return true
        val arabicCount = trimmed.count { it in '\u0600'..'\u06FF' }
        if (arabicCount > 3) return true
        if (trimmed.length > 120 && !trimmed.contains('/') && !trimmed.contains('.')) return true
        return false
    }

    private fun isLikelyCode(content: String, extension: String): Boolean {
        val lower = content.lowercase()
        return when (extension) {
            "html", "htm" -> lower.contains("<html") || lower.contains("<!doctype") ||
                             lower.contains("<div") || lower.contains("<body") ||
                             lower.contains("<head") || lower.contains("<script")
            "css" -> lower.contains("{") && lower.contains(":") && lower.contains(";")
            "js", "ts", "jsx", "tsx" -> lower.contains("function") || lower.contains("const ") ||
                                         lower.contains("var ") || lower.contains("let ") ||
                                         lower.contains("=>") || lower.contains("import ")
            "kt" -> lower.contains("fun ") || lower.contains("class ") || lower.contains("val ") || lower.contains("var ")
            "java" -> lower.contains("public ") || lower.contains("class ") || lower.contains("import ")
            "py" -> lower.contains("def ") || lower.contains("import ") || lower.contains("class ") || lower.contains("print(")
            "json" -> content.trim().startsWith("{") || content.trim().startsWith("[")
            "xml" -> content.trim().startsWith("<") && content.contains(">")
            else -> {
                val arabicCount = content.count { it in '\u0600'..'\u06FF' }
                arabicCount.toDouble() / content.length.coerceAtLeast(1).toDouble() < 0.3
            }
        }
    }

    private fun fileLog(msg: String) {
        try {
            val dir = File(BASE_DIR)
            if (!dir.exists()) dir.mkdirs()
            File(dir, "tool_executor.log").appendText("[${java.util.Date()}] $msg\n")
        } catch (_: Exception) {}
    }
}


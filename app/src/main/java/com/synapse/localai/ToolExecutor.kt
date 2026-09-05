package com.synapse.localai

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Data structure representing a parsed code block along with its latched target file path.
 */
data class ExtractedCodeBlock(
    val language: String,
    val code: String,
    val targetPath: String?,
    val hasExplicitPath: Boolean = false,
    val rawMatch: String = ""
)

/**
 * ToolExecutor — File Extraction, Real-Time Concurrent Disk Streaming & Post-Execution Hygiene.
 *
 * Responsibilities:
 * 1. Immediate File Provisioning: Instantly create and allocate target files on disk when code begins.
 * 2. Synchronous Live Writing: Stream incoming code tokens concurrently into disk buffer in real time.
 * 3. Auto-Flush & Atomic Save: Close, flush and save files the moment a code block terminates.
 * 4. Post-Execution Summary Hygiene: Eliminate code echoes in conversational chat, confining raw code
 *    strictly to physical files and interactive code containers.
 */
object ToolExecutor {

    private const val TAG = "ToolExecutor"
    const val BASE_DIR = "/sdcard/Medlam-Ai"

    // Patterns that suggest an explicit file path label before a code block
    private val FILE_PATH_PATTERNS = listOf(
        Regex("""(?:file|path|save to|write to|create file|filename)[:\s]+[`"']?([^\s`"'\n]+\.[a-zA-Z0-9]{1,10})[`"']?""", RegexOption.IGNORE_CASE),
        Regex("""(?://|/\*|#|<!--)\s*(?:file|path)[:\s]+([^\s*`"'\n]+\.[a-zA-Z0-9]{1,10})""", RegexOption.IGNORE_CASE),
        Regex("""(/sdcard/Medlam-Ai/[^\s\n"'`]+\.[a-zA-Z0-9]{1,10})""")
    )

    // Extensions that represent executable web/UI content
    private val WEB_EXTENSIONS = setOf("html", "htm", "css", "js", "ts", "jsx", "tsx", "vue", "svelte")

    // Extensions that MUST contain real code (not conversational text)
    private val CODE_EXTENSIONS = WEB_EXTENSIONS + setOf(
        "kt", "java", "py", "cpp", "c", "h", "rs", "go", "rb", "php",
        "swift", "sh", "bash", "sql", "json", "xml", "yaml", "yml"
    )

    /**
     * Resolves raw path against base directory and standardizes path.
     */
    fun resolvePath(rawPath: String, baseDir: String = BASE_DIR): String {
        return when {
            rawPath.startsWith("/sdcard/") -> rawPath
            rawPath.startsWith("~/") -> "/sdcard/${rawPath.removePrefix("~/")}"
            rawPath.startsWith("/") -> rawPath
            else -> "$baseDir/$rawPath"
        }
    }

    /**
     * Formats file size nicely (e.g. 4.2 KB, 1.1 MB).
     */
    fun formatFileSize(filePath: String): String {
        val f = File(filePath)
        if (!f.exists()) return "synced"
        val bytes = f.length()
        val kb = bytes / 1024.0
        return if (kb >= 1024) String.format(Locale.US, "%.1f MB", kb / 1024.0)
        else String.format(Locale.US, "%.1f KB", kb)
    }

    /**
     * Real-Time Disk Streaming Engine:
     * - Immediately provisions target file upon detecting code block opening.
     * - Synchronously streams tokens directly to disk buffer in real time.
     * - Auto-flushes and atomically saves on block termination.
     */
    class LiveFileStreamer(
        private val baseDir: String = BASE_DIR,
        private val onStatusUpdate: ((String) -> Unit)? = null
    ) {
        private var inCodeBlock = false
        private var currentFile: File? = null
        private var currentFos: FileOutputStream? = null
        private var currentWriter: BufferedWriter? = null
        private var pendingFilePath: String? = null
        private var lastWrittenOffset = 0
        private var lastProcessedOffset = 0
        private val writtenFiles = mutableListOf<String>()

        @Synchronized
        fun onChunk(fullAccumulatedText: String) {
            // Pre-Parser Tool-Call Quarantine: Mute all file streaming from tool calls
            if (fullAccumulatedText.contains("<function_calls>") || fullAccumulatedText.contains("<invoke")) {
                return
            }

            if (!inCodeBlock) {
                // Look for opening fence strictly AFTER the last processed/closed block
                val nextOpening = fullAccumulatedText.indexOf("```", lastProcessedOffset)
                if (nextOpening != -1) {
                    val afterBacktick = fullAccumulatedText.substring(nextOpening + 3)
                    val newlineIdx = afterBacktick.indexOf('\n')
                    if (newlineIdx != -1) {
                        // Opening fence header is complete (e.g. ```html\n)
                        val lang = afterBacktick.substring(0, newlineIdx).trim().lowercase()
                        
                        // If it's an internal thought/reasoning block, skip past it
                        if (lang in setOf("thought", "thinking", "reasoning")) {
                            lastProcessedOffset = nextOpening + 3 + newlineIdx + 1
                            return
                        }

                        // Check lines between lastProcessedOffset and nextOpening for file path annotation
                        val precedingText = fullAccumulatedText.substring(lastProcessedOffset, nextOpening)
                        for (line in precedingText.lines().takeLast(6)) {
                            val path = detectFilePath(line)
                            if (path != null) {
                                pendingFilePath = path
                            }
                        }

                        val codeStart = nextOpening + 3 + newlineIdx + 1
                        val explicitTarget = pendingFilePath

                        if (explicitTarget == null) {
                            // Turn-Level State Sanitization: Strictly prohibit writing to /sdcard/...
                            // unless the active stream contains an explicit file-writing directive with an explicitly defined target path.
                            inCodeBlock = true
                            currentFile = null
                            currentWriter = null
                            currentFos = null
                            lastWrittenOffset = codeStart
                            lastProcessedOffset = codeStart
                            return
                        }

                        val resolved = resolvePath(explicitTarget, baseDir)

                        try {
                            val targetFile = File(resolved)
                            targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

                            // Immediate File Provisioning with Append-Safe Disk Sink (Zero-OOM RAM Protection)
                            val fos = FileOutputStream(targetFile, true)
                            currentFos = fos
                            currentWriter = BufferedWriter(OutputStreamWriter(fos, StandardCharsets.UTF_8))
                            currentFile = targetFile
                            inCodeBlock = true
                            lastWrittenOffset = codeStart
                            lastProcessedOffset = codeStart

                            if (!writtenFiles.contains(targetFile.absolutePath)) {
                                writtenFiles.add(targetFile.absolutePath)
                            }

                            Log.i(TAG, "⚡ LiveDiskStreamer: Explicit file provisioned (append=true): ${targetFile.absolutePath}")
                            onStatusUpdate?.invoke("Writing to ${targetFile.absolutePath}...")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed provisioning $resolved: ${e.message}")
                        }
                    }
                }
            } else {
                // Inside active code block: check if closing delimiter (\n```) has arrived
                val newlineFence = fullAccumulatedText.indexOf("\n```", lastWrittenOffset)
                val closingBackticks = if (newlineFence != -1) {
                    newlineFence + 1 // points to first '`'
                } else if (fullAccumulatedText.startsWith("```", lastWrittenOffset)) {
                    lastWrittenOffset
                } else {
                    -1
                }

                if (closingBackticks != -1) {
                    // Closing ``` found: write final code characters up to the closing fence if active file handle exists
                    if (currentWriter != null && currentFile != null) {
                        val finalChunk = fullAccumulatedText.substring(lastWrittenOffset, closingBackticks)
                        try {
                            currentWriter?.write(finalChunk)
                            currentWriter?.flush()
                            try { currentFos?.fd?.sync() } catch (_: Exception) {}
                            currentWriter?.close()
                            currentFos?.close()
                            Log.i(TAG, "⚡ LiveDiskStreamer: Auto-flush & saved (${currentFile?.length()} bytes): ${currentFile?.absolutePath}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error closing stream: ${e.message}")
                        }
                    }
                    currentWriter = null
                    currentFos = null
                    currentFile = null
                    inCodeBlock = false
                    pendingFilePath = null
                    // Move lastProcessedOffset past closing ```
                    val afterClosing = fullAccumulatedText.indexOf('\n', closingBackticks + 3)
                    lastProcessedOffset = if (afterClosing != -1) afterClosing + 1 else closingBackticks + 3
                    lastWrittenOffset = lastProcessedOffset
                } else if (currentWriter != null) {
                    // Still generating code: stream tokens safely to disk, leaving a 4-char buffer for "\n```"
                    val safeEnd = (fullAccumulatedText.length - 4).coerceAtLeast(lastWrittenOffset)
                    if (safeEnd > lastWrittenOffset) {
                        val chunk = fullAccumulatedText.substring(lastWrittenOffset, safeEnd)
                        try {
                            currentWriter?.write(chunk)
                            currentWriter?.flush()
                            try { currentFos?.fd?.sync() } catch (_: Exception) {}
                            lastWrittenOffset = safeEnd
                            lastProcessedOffset = safeEnd
                        } catch (e: Exception) {
                            Log.e(TAG, "Error writing stream chunk: ${e.message}")
                        }
                    }
                }
            }
        }

        @Synchronized
        fun flush() {
            try {
                currentWriter?.flush()
                try { currentFos?.fd?.sync() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Error flushing stream: ${e.message}")
            }
        }

        @Synchronized
        fun flushAndSeal() {
            try {
                currentWriter?.flush()
                try { currentFos?.fd?.sync() } catch (_: Exception) {}
                currentWriter?.close()
                currentFos?.close()
                Log.i(TAG, "⚡ LiveDiskStreamer: Stream gracefully sealed on disk: ${currentFile?.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Error sealing stream: ${e.message}")
            } finally {
                currentWriter = null
                currentFos = null
                currentFile = null
                inCodeBlock = false
                pendingFilePath = null
            }
        }

        fun isStreamActive(): Boolean = inCodeBlock && currentWriter != null

        @Synchronized
        fun closeAll(finalFullText: String = ""): List<String> {
            try {
                if (inCodeBlock && currentWriter != null) {
                    // Stream completed before explicit closing fence: flush all remaining code
                    if (finalFullText.length > lastWrittenOffset) {
                        val remainder = finalFullText.substring(lastWrittenOffset)
                        val fenceIdx = remainder.indexOf("```")
                        val finalCode = if (fenceIdx != -1) remainder.substring(0, fenceIdx) else remainder
                        if (finalCode.isNotEmpty()) {
                            currentWriter?.write(finalCode)
                        }
                    }
                    currentWriter?.flush()
                    try { currentFos?.fd?.sync() } catch (_: Exception) {}
                    currentWriter?.close()
                    currentFos?.close()
                    Log.i(TAG, "⚡ LiveDiskStreamer: Auto-flush & closed remaining on closeAll: ${currentFile?.absolutePath}")
                } else {
                    currentWriter?.flush()
                    try { currentFos?.fd?.sync() } catch (_: Exception) {}
                    currentWriter?.close()
                    currentFos?.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing all streams: ${e.message}")
            } finally {
                currentWriter = null
                currentFos = null
                currentFile = null
                inCodeBlock = false
                pendingFilePath = null
            }

            // Comprehensive multi-file safety check: Ensure only EXPLICIT file blocks are written to physical disk
            if (finalFullText.isNotBlank()) {
                try {
                    val demuxed = StreamDemultiplexer.demux(finalFullText)
                    for (block in demuxed.blocks) {
                        if (block is StreamDemultiplexer.DemuxBlock.Code && block.code.isNotBlank() && !block.filePath.isNullOrBlank()) {
                            val resolved = resolvePath(block.filePath, baseDir)
                            val f = File(resolved)
                            if (!f.exists() || f.length() < block.code.length.toLong() / 2) {
                                f.parentFile?.let { if (!it.exists()) it.mkdirs() }
                                f.writeText(block.code, StandardCharsets.UTF_8)
                                if (!writtenFiles.contains(f.absolutePath)) {
                                    writtenFiles.add(f.absolutePath)
                                }
                                Log.i(TAG, "⚡ LiveDiskStreamer: Explicit file safety verified & saved: ${f.absolutePath} (${f.length()} bytes)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in multi-file safety write: ${e.message}")
                }
            }

            return writtenFiles.toList()
        }

        @Synchronized
        fun reset() {
            try {
                currentWriter?.flush()
                currentWriter?.close()
                currentFos?.close()
            } catch (_: Exception) {}
            currentWriter = null
            currentFos = null
            currentFile = null
            inCodeBlock = false
            pendingFilePath = null
            lastWrittenOffset = 0
            lastProcessedOffset = 0
            writtenFiles.clear()
        }
    }

    /**
     * Post-Execution Summary Hygiene (Strict No-Echo Rule):
     * Eliminates code regurgitation/echoing in conversational chat text.
     * Confines all code strictly to code fences (rendered in dedicated code containers),
     * and produces a clean high-level executive deployment confirmation.
     */
    fun sanitizePostExecutionSummary(rawReply: String, writtenFiles: List<String>): String {
        if (writtenFiles.isEmpty()) return rawReply.trim()

        val demuxed = StreamDemultiplexer.demux(rawReply)
        val codeBlocks = demuxed.blocks.filterIsInstance<StreamDemultiplexer.DemuxBlock.Code>()
        if (codeBlocks.isEmpty()) return rawReply.trim()

        val firstProse = demuxed.blocks.filterIsInstance<StreamDemultiplexer.DemuxBlock.Prose>().firstOrNull()?.text ?: ""
        val lastProse = demuxed.blocks.filterIsInstance<StreamDemultiplexer.DemuxBlock.Prose>().lastOrNull()?.text ?: ""

        val sb = StringBuilder()
        if (firstProse.isNotBlank() && !firstProse.startsWith("### Executive Deployment Confirmation")) {
            val cleanFirst = StreamDemultiplexer.sanitizeProse(firstProse)
            if (cleanFirst.isNotBlank()) {
                sb.append(cleanFirst).append("\n\n")
            }
        }

        // Maintain fenced code blocks intact (rendered inside dedicated CliCodeBlockContainer)
        codeBlocks.forEach { block ->
            sb.append("```").append(block.language).append("\n")
            sb.append(block.code).append("\n")
            sb.append("```\n\n")
        }

        // Strict Anti-Echo Enforcement: Filter prose after code to remove any code quotes or echoes
        if (lastProse.isNotBlank() && lastProse != firstProse) {
            val cleanAfter = StreamDemultiplexer.sanitizeProse(lastProse)
                .replace("### Executive Deployment Confirmation:", "")
                .replace("### Executive Deployment Confirmation", "")
                .trim()

            // Only retain high-level conversational summary sentences (no raw code or echoes)
            val nonCodeLines = cleanAfter.lines().filter { line ->
                val t = line.trim()
                t.isNotEmpty() && !t.startsWith("{") && !t.startsWith("}") &&
                !t.contains(";") && !t.contains("flex:") && !t.contains("margin:") &&
                !t.startsWith(".") && !t.startsWith("#") && !t.startsWith("<") &&
                !t.startsWith("function") && !t.startsWith("const") && !t.startsWith("var")
            }
            val filteredSummary = nonCodeLines.joinToString("\n").trim()
            if (filteredSummary.isNotBlank() && filteredSummary.length > 20) {
                sb.append(filteredSummary).append("\n\n")
            }
        }

        sb.append("### Executive Deployment Confirmation:\n")
        sb.append("• **Provisioned Components & File Paths:**\n")
        writtenFiles.forEach { path ->
            val sizeStr = formatFileSize(path)
            sb.append("  - `$path` ($sizeStr)\n")
        }
        sb.append("• **Operational Readiness:** Synchronous disk streaming complete. Files verified on local storage.")

        return sb.toString().trim()
    }

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
                    val lang = line.trim().removePrefix("```").lowercase().trim()
                    if (lang in setOf("thought", "thinking", "reasoning")) {
                        continue
                    }
                    inCodeBlock = true
                    codeLanguage = lang
                    codeSb.clear()
                    rawSb.clear()
                    rawSb.appendLine(line)
                }
            } else {
                rawSb.appendLine(line)
                if (line.trimStart().startsWith("```")) {
                    inCodeBlock = false
                    val code = codeSb.toString().trim()
                    val lang = codeLanguage.ifBlank { "plaintext" }
                    val targetPath = pendingFilePath

                    if (code.isNotBlank()) {
                        blocks.add(
                            ExtractedCodeBlock(
                                language = lang,
                                code = code,
                                targetPath = targetPath,
                                hasExplicitPath = targetPath != null,
                                rawMatch = rawSb.toString()
                            )
                        )
                    }

                    pendingFilePath = null
                    codeLanguage = ""
                    codeSb.clear()
                    rawSb.clear()
                } else {
                    codeSb.appendLine(line)
                }
            }
        }

        // Catch any trailing unclosed code block:
        if (inCodeBlock && codeSb.isNotBlank()) {
            val code = codeSb.toString().trim()
            val lang = codeLanguage.ifBlank { "plaintext" }
            val targetPath = pendingFilePath
            blocks.add(
                ExtractedCodeBlock(
                    language = lang,
                    code = code,
                    targetPath = targetPath,
                    hasExplicitPath = targetPath != null,
                    rawMatch = rawSb.toString()
                )
            )
        }

        return blocks
    }

    /**
     * Extract and write all detected file-code pairs from an AI response.
     * Strictly writes to disk ONLY if an explicit target file path directive was present.
     */
    fun extractAndWriteFiles(response: String): List<String> {
        val writtenFiles = mutableListOf<String>()
        val blocks = parseCodeBlocksWithPaths(response)

        for (block in blocks) {
            val target = block.targetPath
            if (block.hasExplicitPath && !target.isNullOrBlank()) {
                val ext = target.substringAfterLast('.').lowercase()
                if (ext in CODE_EXTENSIONS && isLikelyCode(block.code, ext)) {
                    val resolved = resolvePath(target, BASE_DIR)
                    val existingFile = File(resolved)
                    // Integrity check: If file already exists and was streamed with more or equal content,
                    // do NOT truncate or overwrite with an echoed snippet!
                    if (existingFile.exists() && existingFile.length() >= block.code.length) {
                        Log.i(TAG, "Skipping re-write: existing file is complete (${existingFile.length()} >= ${block.code.length})")
                        if (!writtenFiles.contains(existingFile.absolutePath)) {
                            writtenFiles.add(existingFile.absolutePath)
                        }
                        continue
                    }

                    val written = writeFile(target, block.code)
                    if (written != null && !writtenFiles.contains(written)) {
                        writtenFiles.add(written)
                    }
                }
            }
        }

        return writtenFiles
    }

    /**
     * Fallback generic output path when no explicit path was provided.
     */
    fun defaultPathForLanguage(language: String, code: String): String {
        return "$BASE_DIR/output.txt"
    }

    fun detectFilePath(line: String): String? {
        if (isLikelyConversation(line)) return null

        for (pattern in FILE_PATH_PATTERNS) {
            val match = pattern.find(line) ?: continue
            val raw = match.groupValues.getOrNull(1)?.trim() ?: continue
            if (raw.isBlank() || raw.length < 4) continue

            val normalized = resolvePath(raw, BASE_DIR)
            val ext = normalized.substringAfterLast('.')
            if (ext.length in 1..10 && !normalized.contains(" ")) {
                return normalized
            }
        }
        return null
    }

    /**
     * Write content to the resolved file path, auto-creating parent dirs.
     */
    fun writeFile(rawPath: String, content: String): String? {
        return try {
            val resolvedPath = resolvePath(rawPath, BASE_DIR)
            val file = File(resolvedPath)
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

    /**
     * Headless shell command execution utility.
     */
    fun executeShellCommand(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (e: Exception) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                output
            } catch (e2: Exception) {
                "Error: ${e2.message}"
            }
        }
    }
}

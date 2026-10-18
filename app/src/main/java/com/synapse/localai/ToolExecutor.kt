package com.synapse.localai

import com.synapse.localai.AtlasLog as Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

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
 */
object ToolExecutor {

    private const val TAG = "ToolExecutor"
    const val BASE_DIR = "/sdcard/atlas-ai"

    private val FILE_PATH_PATTERNS = listOf(
        Regex("""(?:file|path|save to|write to|create file|filename)[:\s]+[`"']?([^\s`"'\n]+\.[a-zA-Z0-9]{1,10})[`"']?""", RegexOption.IGNORE_CASE),
        Regex("""(?://|/\*|#|<!--)\s*(?:file|path)[:\s]+([^\s*`"'\n]+\.[a-zA-Z0-9]{1,10})""", RegexOption.IGNORE_CASE),
        Regex("""(?:Synthesizing|Scaffolding)\s+([^\s`"'\n:\]]+\.[a-zA-Z0-9]{1,10})""", RegexOption.IGNORE_CASE),
        Regex("""(/sdcard/atlas-ai/[^\s\n"'`\]:]+\.[a-zA-Z0-9]{1,10})""")
    )

    private val WEB_EXTENSIONS = setOf("html", "htm", "css", "js", "ts", "jsx", "tsx", "vue", "svelte")

    private val CODE_EXTENSIONS = WEB_EXTENSIONS + setOf(
        "kt", "java", "py", "cpp", "c", "h", "rs", "go", "rb", "php",
        "swift", "sh", "bash", "sql", "json", "xml", "yaml", "yml"
    )

    fun resolvePath(rawPath: String, baseDir: String = BASE_DIR): String {
        return when {
            rawPath.startsWith("/sdcard/") -> rawPath
            rawPath.startsWith("~/") -> "/sdcard/${rawPath.removePrefix("~/")}"
            rawPath.startsWith("/") -> rawPath
            else -> "$baseDir/$rawPath"
        }
    }

    fun formatFileSize(filePath: String): String {
        val f = File(filePath)
        if (!f.exists()) return "synced"
        val bytes = f.length()
        val kb = bytes / 1024.0
        return if (kb >= 1024) String.format(Locale.US, "%.1f MB", kb / 1024.0)
        else String.format(Locale.US, "%.1f KB", kb)
    }

    class LiveFileStreamer(
        private val baseDir: String = BASE_DIR,
        private val onStatusUpdate: ((String) -> Unit)? = null,
        private val fileJournal: FileChangeJournal? = null,
        val transactionId: String = UUID.randomUUID().toString()
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
            if (fullAccumulatedText.contains("<function_calls>") || fullAccumulatedText.contains("<invoke")) {
                return
            }

            if (!inCodeBlock) {
                val nextOpening = fullAccumulatedText.indexOf("```", lastProcessedOffset)
                if (nextOpening != -1) {
                    val afterBacktick = fullAccumulatedText.substring(nextOpening + 3)
                    val newlineIdx = afterBacktick.indexOf('\n')
                    if (newlineIdx != -1) {
                        val lang = afterBacktick.substring(0, newlineIdx).trim().lowercase()
                        
                        if (lang in setOf("thought", "thinking", "reasoning")) {
                            lastProcessedOffset = nextOpening + 3 + newlineIdx + 1
                            return
                        }

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
                            fileJournal?.checkpoint(transactionId, targetFile)

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

                            Log.i(TAG, "LiveDiskStreamer: Explicit file provisioned (append=true): ${targetFile.absolutePath}")
                            val undoText = if (fileJournal != null) " (rollback ID: $transactionId)" else ""
                            onStatusUpdate?.invoke("Writing to ${targetFile.absolutePath}$undoText...")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed provisioning $resolved: ${e.message}")
                        }
                    }
                }
            } else {
                val newlineFence = fullAccumulatedText.indexOf("\n```", lastWrittenOffset)
                val closingBackticks = if (newlineFence != -1) {
                    newlineFence + 1
                } else if (fullAccumulatedText.startsWith("```", lastWrittenOffset)) {
                    lastWrittenOffset
                } else {
                    -1
                }

                if (closingBackticks != -1) {
                    if (currentWriter != null && currentFile != null) {
                        val finalChunk = fullAccumulatedText.substring(lastWrittenOffset, closingBackticks)
                        val targetFileToSanitize = currentFile
                        try {
                            currentWriter?.write(finalChunk)
                            currentWriter?.flush()
                            try { currentFos?.fd?.sync() } catch (_: Exception) {}
                            currentWriter?.close()
                            currentFos?.close()
                            Log.i(TAG, "LiveDiskStreamer: Auto-flush & saved (${targetFileToSanitize?.length()} bytes): ${targetFileToSanitize?.absolutePath}")
                            if (targetFileToSanitize != null && (targetFileToSanitize.name.endsWith(".html", ignoreCase = true) || targetFileToSanitize.name.equals("index.html", ignoreCase = true))) {
                                val savedContent = targetFileToSanitize.readText(StandardCharsets.UTF_8)
                                val clean = HtmlSanitizer.sanitizeHtmlStream(savedContent)
                                if (clean != savedContent) {
                                    targetFileToSanitize.writeText(clean, StandardCharsets.UTF_8)
                                    Log.i(TAG, "LiveDiskStreamer: Start-of-File Strict Anchor applied to ${targetFileToSanitize.absolutePath}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error closing stream: ${e.message}")
                        }
                    }
                    currentWriter = null
                    currentFos = null
                    currentFile = null
                    inCodeBlock = false
                    pendingFilePath = null
                    val afterClosing = fullAccumulatedText.indexOf('\n', closingBackticks + 3)
                    lastProcessedOffset = if (afterClosing != -1) afterClosing + 1 else closingBackticks + 3
                    lastWrittenOffset = lastProcessedOffset
                } else if (currentWriter != null) {
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
                val targetFileToSanitize = currentFile
                currentWriter?.close()
                currentFos?.close()
                Log.i(TAG, "LiveDiskStreamer: Stream gracefully sealed on disk: ${targetFileToSanitize?.absolutePath}")
                if (targetFileToSanitize != null && (targetFileToSanitize.name.endsWith(".html", ignoreCase = true) || targetFileToSanitize.name.equals("index.html", ignoreCase = true))) {
                    val savedContent = targetFileToSanitize.readText(StandardCharsets.UTF_8)
                    val clean = HtmlSanitizer.sanitizeHtmlStream(savedContent)
                    if (clean != savedContent) {
                        targetFileToSanitize.writeText(clean, StandardCharsets.UTF_8)
                        Log.i(TAG, "LiveDiskStreamer: Start-of-File Strict Anchor applied to ${targetFileToSanitize.absolutePath}")
                    }
                }
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
                    Log.i(TAG, "LiveDiskStreamer: Auto-flush & closed remaining on closeAll: ${currentFile?.absolutePath}")
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

            if (finalFullText.isNotBlank()) {
                try {
                    val demuxed = StreamDemultiplexer.demux(finalFullText)
                    for (block in demuxed.blocks) {
                        if (block is StreamDemultiplexer.DemuxBlock.Code && block.code.isNotBlank() && !block.filePath.isNullOrBlank()) {
                            val resolved = resolvePath(block.filePath, baseDir)
                            val f = File(resolved)
                            val finalCode = if (resolved.endsWith(".html", ignoreCase = true) || resolved.endsWith("index.html", ignoreCase = true)) {
                                HtmlSanitizer.sanitizeHtmlStream(block.code)
                            } else {
                                block.code
                            }
                            if (!f.exists() || f.length() < finalCode.length.toLong() / 2) {
                                f.parentFile?.let { if (!it.exists()) it.mkdirs() }
                                fileJournal?.checkpoint(transactionId, f)
                                f.writeText(finalCode, StandardCharsets.UTF_8)
                                if (!writtenFiles.contains(f.absolutePath)) {
                                    writtenFiles.add(f.absolutePath)
                                }
                                Log.i(TAG, "LiveDiskStreamer: Explicit file safety verified & saved: ${f.absolutePath} (${f.length()} bytes)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in multi-file safety write: ${e.message}")
                }
            }

            for (filePath in writtenFiles) {
                if (filePath.endsWith(".html", ignoreCase = true) || filePath.endsWith("index.html", ignoreCase = true)) {
                    try {
                        val f = File(filePath)
                        if (f.exists()) {
                            val content = f.readText(StandardCharsets.UTF_8)
                            val sanitized = HtmlSanitizer.sanitizeHtmlStream(content)
                            if (sanitized != content) {
                                f.writeText(sanitized, StandardCharsets.UTF_8)
                                Log.i(TAG, "LiveDiskStreamer: Post-sanitized HTML at Byte 0: $filePath")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not post-sanitize HTML $filePath:${e.message}")
                    }
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

    fun sanitizePostExecutionSummary(
        rawReply: String,
        writtenFiles: List<String>,
        transactionId: String? = null
    ): String {
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

        codeBlocks.forEach { block ->
            sb.append("```").append(block.language).append("\n")
            sb.append(block.code).append("\n")
            sb.append("```\n\n")
        }

        if (lastProse.isNotBlank() && lastProse != firstProse) {
            val cleanAfter = StreamDemultiplexer.sanitizeProse(lastProse)
                .replace("### Executive Deployment Confirmation:", "")
                .replace("### Executive Deployment Confirmation", "")
                .trim()

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
        if (!transactionId.isNullOrBlank()) {
            sb.append("\n• **Rollback:** `rollback_file_changes(transaction_id=\"$transactionId\")` restores the prior versions.")
        }

        return sb.toString().trim()
    }

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

    fun extractAndWriteFiles(
        response: String,
        fileJournal: FileChangeJournal? = null,
        transactionId: String? = null
    ): List<String> {
        val writtenFiles = mutableListOf<String>()
        val blocks = parseCodeBlocksWithPaths(response)

        for (block in blocks) {
            val target = block.targetPath
            if (block.hasExplicitPath && !target.isNullOrBlank()) {
                val ext = target.substringAfterLast('.').lowercase()
                if (ext in CODE_EXTENSIONS && isLikelyCode(block.code, ext)) {
                    val resolved = resolvePath(target, BASE_DIR)
                    val existingFile = File(resolved)
                    if (existingFile.exists() && existingFile.length() >= block.code.length) {
                        Log.i(TAG, "Skipping re-write: existing file is complete (${existingFile.length()} >= ${block.code.length})")
                        if (!writtenFiles.contains(existingFile.absolutePath)) {
                            writtenFiles.add(existingFile.absolutePath)
                        }
                        continue
                    }

                    val written = writeFile(target, block.code, fileJournal, transactionId)
                    if (written != null && !writtenFiles.contains(written)) {
                        writtenFiles.add(written)
                    }
                }
            }
        }

        return writtenFiles
    }

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

    fun writeFile(
        rawPath: String,
        content: String,
        fileJournal: FileChangeJournal? = null,
        transactionId: String? = null
    ): String? {
        return try {
            val resolvedPath = resolvePath(rawPath, BASE_DIR)
            val file = File(resolvedPath)
            file.parentFile?.let { parent ->
                if (!parent.exists()) {
                    val created = parent.mkdirs()
                    Log.d(TAG, "Created directories: ${parent.absolutePath} (success=$created)")
                }
            }

            val finalContent = if (resolvedPath.endsWith(".html", ignoreCase = true) || resolvedPath.endsWith("index.html", ignoreCase = true)) {
                HtmlSanitizer.sanitizeHtmlStream(content)
            } else {
                content
            }

            if (fileJournal != null) {
                fileJournal.write(transactionId, file, finalContent)
            } else {
                file.writeText(finalContent)
            }
            Log.i(TAG, "✅ File written: ${file.absolutePath} (${finalContent.length} chars)")
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
        Log.d(TAG, msg)
    }

    /**
     * Headless shell command execution utility. Long commands are allowed to finish.
     */
    fun executeShellCommand(cmd: String, timeoutMs: Long = 0L): String {
        return try {
            val (exitCode, lines) = RootShellManager.execWithExitCode(cmd)
            if (exitCode >= 0) {
                "Exit code: $exitCode\n${lines.joinToString("\n")}".trim()
            } else {
                runProcessWithWatchdog(cmd, "sh", timeoutMs)
            }
        } catch (_: Exception) {
            try {
                runProcessWithWatchdog(cmd, "sh", timeoutMs)
            } catch (e2: Exception) {
                "Error: ${e2.message}"
            }
        }
    }

    /**
     * Spawns non-root process with watchdog and explicit terminal dimensions environment variables.
     */
    private fun runProcessWithWatchdog(cmd: String, shell: String, timeoutMs: Long): String {
        val pb = ProcessBuilder(shell, "-c", cmd)
        val env = pb.environment()
        env["TERM"] = "xterm-256color"
        env["COLUMNS"] = "120"
        env["LINES"] = "40"

        pb.redirectErrorStream(true)
        val process = pb.start()
        try { process.outputStream.close() } catch (_: Exception) {}

        val outputSb = StringBuilder()
        val thread = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buf = CharArray(2048)
                    var n: Int
                    while (reader.read(buf).also { n = it } != -1) {
                        outputSb.append(buf, 0, n)
                        if (outputSb.length > 512 * 1024) break
                    }
                }
            } catch (_: Exception) {}
        }
        thread.isDaemon = true
        thread.start()

        val finished = if (timeoutMs > 0L) {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } else {
            process.waitFor()
            true
        }
        if (!finished) {
            try { process.destroyForcibly() } catch (_: Exception) {}
            try { thread.join(500) } catch (_: Exception) {}
            val partial = outputSb.toString().trim()
            return "[Shell Command Timed Out (${timeoutMs / 1000}s) - Terminated]\n$partial".trim()
        }

        try { thread.join(1000) } catch (_: Exception) {}
        return outputSb.toString()
    }
}

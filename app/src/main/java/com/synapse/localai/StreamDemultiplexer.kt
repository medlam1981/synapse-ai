package com.synapse.localai

import android.util.Log

/**
 * State Machine Token Parser & Stream Demultiplexer:
 * Situated between the network/engine stream callback and the UI view adapter.
 *
 * Implements an active look-ahead buffer that detects token boundaries in real time:
 * - Thinking Phase: Captures everything between <think> and </think> (or equivalent tokens),
 *   routing incoming text exclusively to the internal buffer of the Collapsible Thought Container,
 *   suppressing it completely from the chat bubble.
 * - Code Phase: The instant opening triple backticks (```) are detected, transitions state to lock
 *   all subsequent syntax tokens into an active Code Container instance.
 * - Prose/Chat Phase: Any text outside thinking or code blocks is treated as standard assistant prose
 *   and rendered inside the MEDLAM >> message body.
 */
object StreamDemultiplexer {

    private const val TAG = "StreamDemultiplexer"

    data class DemuxResult(
        val thinkingText: String,
        val isThinking: Boolean,
        val blocks: List<DemuxBlock>
    )

    sealed class DemuxBlock {
        data class Prose(val text: String) : DemuxBlock()
        data class Code(
            val language: String,
            val code: String,
            val filePath: String?,
            val isClosed: Boolean
        ) : DemuxBlock()
    }

    private data class ThinkTagDef(val open: String, val close: String)

    private val THINK_TAGS = listOf(
        ThinkTagDef("<think>", "</think>"),
        ThinkTagDef("<|think|>", "<|/think|>"),
        ThinkTagDef("<|thought|>", "<|/thought|>"),
        ThinkTagDef("<thought>", "</thought>"),
        ThinkTagDef("<reasoning>", "</reasoning>"),
        ThinkTagDef("<|reasoning|>", "<|/reasoning|>")
    )

    /**
     * Parse raw incoming stream text or completed response turn through
     * the real-time look-ahead state machine.
     */
    fun demux(
        rawText: String,
        externalThinkingText: String = "",
        externalIsThinking: Boolean = false
    ): DemuxResult {
        if (rawText.isBlank() && externalThinkingText.isBlank()) {
            return DemuxResult(
                thinkingText = "",
                isThinking = externalIsThinking,
                blocks = emptyList()
            )
        }

        // 1. Thinking Phase Extraction
        var workingText = rawText
        var extractedThought = ""
        var currentlyThinking = false

        // Check for fenced thought blocks: ```thought\n...```
        val fencedThoughtMatch = Regex("""```(?:thought|thinking|reasoning)\n([\s\S]*?)(?:```|$)""", RegexOption.IGNORE_CASE)
            .find(workingText)
        if (fencedThoughtMatch != null) {
            extractedThought = fencedThoughtMatch.groupValues[1].trimStart()
            currentlyThinking = !fencedThoughtMatch.value.endsWith("```")
            workingText = workingText.removeRange(fencedThoughtMatch.range).trim()
        }

        if (extractedThought.isEmpty()) {
            // Check for XML-style think tags
            for (tagDef in THINK_TAGS) {
                val openIdx = workingText.indexOf(tagDef.open)
                if (openIdx != -1) {
                    val afterOpen = workingText.substring(openIdx + tagDef.open.length)
                    val closeIdx = afterOpen.indexOf(tagDef.close)
                    if (closeIdx == -1) {
                        // Unclosed thinking tag: currently in active thinking phase!
                        // Preserve trailing whitespace for real-time letter-by-letter typing and strip partial closing tag
                        val rawThought = afterOpen.trimStart()
                        extractedThought = rawThought.replace(Regex("""</?[a-zA-Z0-9_|]*$"""), "")
                        currentlyThinking = true
                        workingText = workingText.substring(0, openIdx).trim()
                    } else {
                        // Completed thinking block
                        extractedThought = afterOpen.substring(0, closeIdx).trim()
                        currentlyThinking = false
                        val before = workingText.substring(0, openIdx).trim()
                        val after = afterOpen.substring(closeIdx + tagDef.close.length).trim()
                        workingText = if (before.isNotEmpty() && after.isNotEmpty()) "$before\n\n$after" else before + after
                    }
                    break
                }
            }
        }

        val finalThinkingText = if (extractedThought.isNotEmpty()) {
            extractedThought
        } else {
            externalThinkingText
        }

        val finalIsThinking = currentlyThinking || (externalIsThinking && finalThinkingText.isNotBlank())

        // 2. Multi-Fence State Machine with Heuristic Code Quarantine
        val blocks = mutableListOf<DemuxBlock>()
        var pos = 0
        val textLen = workingText.length

        var pendingFilePathFromProse: String? = null

        while (pos < textLen) {
            val fenceIdx = workingText.indexOf("```", pos)
            if (fenceIdx == -1) {
                // No more explicit code fences: process remainder through Heuristic Quarantine
                val remainder = workingText.substring(pos).trim()
                if (remainder.isNotBlank()) {
                    blocks.addAll(processProseWithQuarantine(remainder, pendingFilePathFromProse))
                }
                break
            }

            // Text prior to the code fence: candidate prose, process through quarantine
            if (fenceIdx > pos) {
                val proseChunk = workingText.substring(pos, fenceIdx).trim()
                if (proseChunk.isNotBlank()) {
                    val processed = processProseWithQuarantine(proseChunk, pendingFilePathFromProse)
                    blocks.addAll(processed)
                    // Track any detected file paths for the upcoming code block
                    pendingFilePathFromProse = ToolExecutor.detectFilePath(proseChunk)
                }
            }

            // Transition to Code Phase
            val afterOpening = workingText.substring(fenceIdx + 3)
            val newlineIdx = afterOpening.indexOf('\n')

            if (newlineIdx == -1) {
                // Code block header actively in progress (e.g. ```html without newline yet)
                val lang = afterOpening.trim().lowercase()
                val targetPath = pendingFilePathFromProse ?: ToolExecutor.defaultPathForLanguage(lang, "")
                blocks.add(
                    DemuxBlock.Code(
                        language = lang.ifBlank { "code" },
                        code = "",
                        filePath = targetPath,
                        isClosed = false
                    )
                )
                break
            }

            val lang = afterOpening.substring(0, newlineIdx).trim().lowercase()
            val codeStartIdx = fenceIdx + 3 + newlineIdx + 1

            // Target file path resolution
            var targetPath = pendingFilePathFromProse

            // Search for closing delimiter: match ``` preceded by \n or whitespace, or another fence
            val closingIdx = findClosingFence(workingText, codeStartIdx)
            if (closingIdx == -1) {
                // Code block is actively streaming: lock tokens into this code block container
                val activeCode = workingText.substring(codeStartIdx)
                if (targetPath == null) {
                    targetPath = detectPathFromCodeComments(activeCode) ?: ToolExecutor.defaultPathForLanguage(lang, activeCode)
                }
                blocks.add(
                    DemuxBlock.Code(
                        language = lang.ifBlank { "code" },
                        code = activeCode,
                        filePath = targetPath,
                        isClosed = false
                    )
                )
                break
            } else {
                // Code block closed cleanly
                val closedCode = workingText.substring(codeStartIdx, closingIdx)
                if (targetPath == null) {
                    targetPath = detectPathFromCodeComments(closedCode) ?: ToolExecutor.defaultPathForLanguage(lang, closedCode)
                }
                blocks.add(
                    DemuxBlock.Code(
                        language = lang.ifBlank { "code" },
                        code = closedCode,
                        filePath = targetPath,
                        isClosed = true
                    )
                )

                // Advance pos past closing ``` and optional following newline
                val pastClosing = closingIdx + 3
                val nextNewline = workingText.indexOf('\n', pastClosing)
                pos = if (nextNewline != -1 && nextNewline <= pastClosing + 2) nextNewline + 1 else pastClosing
                pendingFilePathFromProse = null
            }
        }

        return DemuxResult(
            thinkingText = finalThinkingText,
            isThinking = finalIsThinking,
            blocks = blocks
        )
    }

    /**
     * Finds the closing fence ``` starting from fromIndex.
     * Supports newline-preceded ```, start-of-line ```, or immediately following ```.
     * Also detects if an unclosed block hits an Executive Deployment Confirmation.
     */
    private fun findClosingFence(text: String, fromIndex: Int): Int {
        val execIdx1 = text.indexOf("### Executive Deployment Confirmation", fromIndex)
        val execIdx2 = text.indexOf("Executive Deployment Confirmation:", fromIndex)
        val execBoundary = when {
            execIdx1 != -1 && execIdx2 != -1 -> minOf(execIdx1, execIdx2)
            execIdx1 != -1 -> execIdx1
            execIdx2 != -1 -> execIdx2
            else -> -1
        }

        val fence = text.indexOf("```", fromIndex)
        if (fence != -1 && (execBoundary == -1 || fence < execBoundary)) {
            return fence
        }
        if (execBoundary != -1) {
            return execBoundary
        }
        return -1
    }

    /**
     * Heuristic Quarantine for Untagged Code:
     * If candidate prose contains consecutive code patterns (CSS selectors/properties, JS functions,
     * HTML elements, brackets) outside an active code fence, auto-routes them into an isolated Code container
     * rather than rendering them as unstyled raw text in the chat bubble.
     *
     * Features:
     * - Brace balance tracking: keeps blocks open until braces close.
     * - Multi-file boundary recognition: detects transitions like styles.css or script.js.
     * - Clean structural demarcation for Executive Deployment Confirmation.
     */
    fun processProseWithQuarantine(rawChunk: String, hintFilePath: String? = null): List<DemuxBlock> {
        if (rawChunk.isBlank()) return emptyList()

        val result = mutableListOf<DemuxBlock>()

        // 1. Check for Executive Deployment Confirmation demarcation
        val execMarker = if (rawChunk.contains("### Executive Deployment Confirmation")) {
            "### Executive Deployment Confirmation"
        } else if (rawChunk.contains("Executive Deployment Confirmation:")) {
            "Executive Deployment Confirmation:"
        } else null

        val textToProcess: String
        val execSection: String?

        if (execMarker != null) {
            val markerIdx = rawChunk.indexOf(execMarker)
            textToProcess = rawChunk.substring(0, markerIdx).trim()
            execSection = rawChunk.substring(markerIdx).trim()
        } else {
            textToProcess = rawChunk
            execSection = null
        }

        // 2. Scan textToProcess for code clusters vs natural conversational text
        if (textToProcess.isNotBlank()) {
            val lines = textToProcess.lines()
            val proseBuffer = StringBuilder()
            val codeBuffer = StringBuilder()
            var inQuarantinedCode = false
            var openBraces = 0
            var detectedLang = "code"
            var quarantinedPath: String? = hintFilePath

            fun flushProse() {
                val p = sanitizeProse(proseBuffer.toString()).trim()
                if (p.isNotBlank()) {
                    result.add(DemuxBlock.Prose(p))
                }
                proseBuffer.clear()
            }

            fun flushCode() {
                val c = codeBuffer.toString().trim()
                if (c.isNotBlank()) {
                    val resolvedPath = quarantinedPath ?: detectPathFromCodeComments(c) ?: ToolExecutor.defaultPathForLanguage(detectedLang, c)
                    result.add(
                        DemuxBlock.Code(
                            language = detectedLang,
                            code = c,
                            filePath = resolvedPath,
                            isClosed = true
                        )
                    )
                }
                codeBuffer.clear()
                inQuarantinedCode = false
                openBraces = 0
                detectedLang = "code"
                quarantinedPath = null
            }

            for (i in lines.indices) {
                val line = lines[i]
                val trimmed = line.trim()
                val isCode = isLikelyCodeLine(trimmed)

                // Detect file transition hints in comments (e.g. /* styles.css */ or // script.js)
                val pathHint = detectPathFromCodeComments(line) ?: ToolExecutor.detectFilePath(line)
                if (pathHint != null) {
                    if (inQuarantinedCode && quarantinedPath != null && quarantinedPath != pathHint) {
                        // Multi-file transition inside prose: close current file and start new file container
                        flushCode()
                    }
                    quarantinedPath = pathHint
                }

                if (isCode) {
                    if (!inQuarantinedCode) {
                        // Check look-ahead: is this part of an upcoming code block?
                        val nextLineIsCode = (i + 1 < lines.size) && isLikelyCodeLine(lines[i + 1].trim())
                        val isMultiProperty = trimmed.contains("{") || (trimmed.contains(":") && trimmed.endsWith(";")) || trimmed.startsWith("<")
                        if (nextLineIsCode || isMultiProperty || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
                            flushProse()
                            inQuarantinedCode = true
                            detectedLang = inferLanguageFromLine(trimmed, detectedLang)
                        }
                    }

                    if (inQuarantinedCode) {
                        // Update brace balance
                        openBraces += trimmed.count { it == '{' }
                        openBraces = (openBraces - trimmed.count { it == '}' }).coerceAtLeast(0)

                        detectedLang = inferLanguageFromLine(trimmed, detectedLang)
                        codeBuffer.append(line).append("\n")
                    } else {
                        proseBuffer.append(line).append("\n")
                    }
                } else {
                    if (inQuarantinedCode) {
                        // If we are inside unbalanced braces (CSS selector, JS block, HTML style), stay in code!
                        val isInsideBraces = openBraces > 0
                        val isInsideHtml = detectedLang == "html" && !trimmed.startsWith("#") && !trimmed.startsWith("-") && !trimmed.startsWith("•")
                        val isCommentOrIndented = trimmed.isEmpty() || line.startsWith("  ") || line.startsWith("\t") || trimmed.startsWith("*")

                        if (isInsideBraces || isInsideHtml || isCommentOrIndented) {
                            openBraces += trimmed.count { it == '{' }
                            openBraces = (openBraces - trimmed.count { it == '}' }).coerceAtLeast(0)
                            codeBuffer.append(line).append("\n")
                        } else {
                            // Genuine non-code prose reached: close quarantined code container
                            flushCode()
                            proseBuffer.append(line).append("\n")
                        }
                    } else {
                        proseBuffer.append(line).append("\n")
                    }
                }
            }

            if (inQuarantinedCode) {
                flushCode()
            } else {
                flushProse()
            }
        }

        // 3. Demarcate Executive Deployment Confirmation as a clean, isolated block
        if (!execSection.isNullOrBlank()) {
            val cleanExec = sanitizeProse(execSection).trim()
            if (cleanExec.isNotBlank()) {
                result.add(DemuxBlock.Prose(cleanExec))
            }
        }

        return result
    }

    private fun isLikelyCodeLine(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()

        // CSS patterns
        if (trimmed.startsWith(".") && trimmed.contains("{")) return true
        if (trimmed.startsWith("#") && !trimmed.startsWith("# ") && trimmed.contains("{")) return true
        if (trimmed.startsWith("@media") || trimmed.startsWith("@keyframes") || trimmed.startsWith("@import") || trimmed.startsWith("@font-face")) return true
        if (lower.startsWith("body ") && trimmed.contains("{")) return true
        if (lower.startsWith("html ") && trimmed.contains("{")) return true
        if (lower.startsWith("nav ") && trimmed.contains("{")) return true
        if (lower.startsWith("header ") && trimmed.contains("{")) return true
        if (lower.startsWith("main ") && trimmed.contains("{")) return true
        if (lower.startsWith("footer ") && trimmed.contains("{")) return true
        if (lower.startsWith("section ") && trimmed.contains("{")) return true
        if (lower.startsWith("div ") && trimmed.contains("{")) return true
        if (lower.startsWith("button ") && trimmed.contains("{")) return true
        if (lower.startsWith("input ") && trimmed.contains("{")) return true
        if (lower.startsWith("form ") && trimmed.contains("{")) return true
        if (lower.startsWith("table ") && trimmed.contains("{")) return true
        if (lower.startsWith("span ") && trimmed.contains("{")) return true
        if (lower.startsWith("p ") && trimmed.contains("{")) return true
        if (lower.startsWith("a ") && trimmed.contains("{")) return true
        if (trimmed.startsWith("*") && trimmed.contains("{")) return true

        // CSS declarations: property: value;
        if (trimmed.contains(":") && trimmed.endsWith(";") && !trimmed.startsWith("http") && !trimmed.startsWith("Note:") && !trimmed.startsWith("Step:")) {
            return true
        }

        if (lower.startsWith("display:") || lower.startsWith("flex:") || lower.startsWith("margin:") ||
            lower.startsWith("padding:") || lower.startsWith("background:") || lower.startsWith("background-color:") ||
            lower.startsWith("color:") || lower.startsWith("font-") || lower.startsWith("border:") ||
            lower.startsWith("border-radius:") || lower.startsWith("width:") || lower.startsWith("height:") ||
            lower.startsWith("box-sizing:") || lower.startsWith("align-items:") || lower.startsWith("justify-content:") ||
            lower.startsWith("position:") || lower.startsWith("top:") || lower.startsWith("left:") ||
            lower.startsWith("z-index:") || lower.startsWith("overflow:") || lower.startsWith("cursor:") ||
            lower.startsWith("box-shadow:") || lower.startsWith("transition:") || lower.startsWith("gap:")) return true
        if (trimmed == "}" || trimmed == "};" || trimmed == "});" || trimmed == "{") return true

        // HTML / XML patterns
        if (trimmed.startsWith("<") && (trimmed.endsWith(">") || trimmed.contains("</") || trimmed.contains(">") || lower.startsWith("<!doctype"))) return true

        // JavaScript / TypeScript patterns
        if (lower.startsWith("function ") || lower.startsWith("function(") || lower.startsWith("const ") ||
            lower.startsWith("let ") || lower.startsWith("var ") || lower.startsWith("import ") ||
            lower.startsWith("export ") || lower.startsWith("class ") || lower.startsWith("return ") ||
            lower.startsWith("document.") || lower.startsWith("window.") || lower.startsWith("console.") ||
            lower.startsWith("addeventlistener")) return true

        // Kotlin / Python / Shell patterns
        if (lower.startsWith("def ") || lower.startsWith("fun ") || lower.startsWith("package ") ||
            lower.startsWith("val ") || lower.startsWith("var ") || lower.startsWith("echo ") ||
            lower.startsWith("curl ") || lower.startsWith("npm ") || lower.startsWith("git ")) return true

        // Code comment banners
        if (trimmed.startsWith("/*") || trimmed.startsWith("//") || trimmed.startsWith("#!/") || trimmed.startsWith("<!--")) return true

        return false
    }

    private fun inferLanguageFromLine(trimmed: String, current: String): String {
        val lower = trimmed.lowercase()
        // If already recognized as HTML, don't drop to CSS just because of an inline style
        if (current == "html" && !trimmed.startsWith("/*") && !trimmed.startsWith("// styles.css")) {
            return "html"
        }
        return when {
            lower.startsWith("<") -> "html"
            trimmed.startsWith(".") && trimmed.contains("{") -> "css"
            lower.contains("margin:") || lower.contains("padding:") || lower.contains("display:") ||
            lower.contains("background:") || lower.contains("border:") || lower.contains("flex:") ||
            (trimmed.contains(":") && trimmed.endsWith(";")) -> "css"
            lower.startsWith("function") || lower.startsWith("const ") || lower.startsWith("let ") ||
            lower.startsWith("document.") || lower.startsWith("console.") -> "javascript"
            lower.startsWith("fun ") || lower.startsWith("val ") || lower.startsWith("package ") -> "kotlin"
            lower.startsWith("def ") || (lower.startsWith("import ") && !lower.contains("from")) -> "python"
            else -> current
        }
    }

    private fun detectPathFromCodeComments(code: String): String? {
        val lines = code.lines().take(4)
        for (line in lines) {
            val p = ToolExecutor.detectFilePath(line)
            if (p != null) return p
        }
        return null
    }

    /**
     * Strips leaked raw code syntax, function declarations, HTML tags, and repeated script logic
     * from natural prose text outside code containers.
     */
    fun sanitizeProse(raw: String): String {
        if (raw.isBlank()) return ""

        val lines = raw.lines()
        val sanitizedLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                sanitizedLines.add(line)
                continue
            }

            val lower = trimmed.lowercase()

            // Filter out invoke XML tags
            if (trimmed.startsWith("<invoke") || trimmed.startsWith("</invoke>") ||
                trimmed.startsWith("<function_calls>") || trimmed.startsWith("</function_calls>") ||
                trimmed.startsWith("<parameter") || trimmed.startsWith("</parameter>")) {
                continue
            }

            // Filter raw backtick artifacts
            if (trimmed.startsWith("```")) continue

            // Detect raw code leak lines
            val isCodeLeak = (trimmed.startsWith("<") && (trimmed.endsWith(">") || trimmed.contains("</") || lower.startsWith("<!doctype"))) ||
                             lower.startsWith("function ") || lower.startsWith("function(") ||
                             lower.startsWith("const ") || lower.startsWith("var ") || lower.startsWith("let ") ||
                             lower.startsWith("import ") || lower.startsWith("export ") ||
                             lower.startsWith("class ") || lower.startsWith("def ") ||
                             lower.startsWith("return ") ||
                             (trimmed.contains("{") && trimmed.contains("}") && !trimmed.contains(" ")) ||
                             lower.contains("margin:") || lower.contains("padding:") ||
                             lower.contains("color:") || lower.contains("display:") ||
                             lower.contains("background:") || lower.contains("font-size:")

            if (!isCodeLeak) {
                sanitizedLines.add(line)
            }
        }

        return sanitizedLines.joinToString("\n").trim()
    }
}

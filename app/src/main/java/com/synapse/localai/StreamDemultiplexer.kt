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
        ThinkTagDef("<|think|>", "<|thought|>"),
        ThinkTagDef("<thought>", "</thought>"),
        ThinkTagDef("<reasoning>", "</reasoning>")
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
            extractedThought = fencedThoughtMatch.groupValues[1].trim()
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
                        extractedThought = afterOpen.trim()
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

        val finalThinkingText = if (extractedThought.isNotBlank()) {
            extractedThought
        } else {
            externalThinkingText.trim()
        }

        val finalIsThinking = currentlyThinking || (externalIsThinking && finalThinkingText.isNotBlank())

        // 2. Code vs Prose State Machine
        val blocks = mutableListOf<DemuxBlock>()
        var pos = 0
        val textLen = workingText.length

        var precedingProseForPath: String? = null

        while (pos < textLen) {
            val fenceIdx = workingText.indexOf("```", pos)
            if (fenceIdx == -1) {
                // No more code fences: remainder of text is pure prose
                val remainder = workingText.substring(pos).trim()
                val cleanProse = sanitizeProse(remainder)
                if (cleanProse.isNotBlank()) {
                    blocks.add(DemuxBlock.Prose(cleanProse))
                }
                break
            }

            // Text prior to the code fence belongs to Prose Phase
            if (fenceIdx > pos) {
                val proseChunk = workingText.substring(pos, fenceIdx).trim()
                val cleanProse = sanitizeProse(proseChunk)
                if (cleanProse.isNotBlank()) {
                    blocks.add(DemuxBlock.Prose(cleanProse))
                    precedingProseForPath = cleanProse
                }
            }

            // Transition to Code Phase
            val afterBackticks = workingText.substring(fenceIdx + 3)
            val newlineIdx = afterBackticks.indexOf('\n')

            if (newlineIdx == -1) {
                // Code block header in progress (e.g. ```html without newline yet)
                val lang = afterBackticks.trim().lowercase()
                val defaultPath = ToolExecutor.defaultPathForLanguage(lang, "")
                blocks.add(
                    DemuxBlock.Code(
                        language = lang.ifBlank { "code" },
                        code = "",
                        filePath = defaultPath,
                        isClosed = false
                    )
                )
                break
            }

            val lang = afterBackticks.substring(0, newlineIdx).trim().lowercase()
            val codeStartIdx = fenceIdx + 3 + newlineIdx + 1

            // Resolve target file path from preceding prose or default
            var targetPath: String? = null
            if (precedingProseForPath != null) {
                targetPath = ToolExecutor.detectFilePath(precedingProseForPath)
            }

            // Look for closing delimiter \n```
            val closingIdx = workingText.indexOf("\n```", codeStartIdx)
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

                // Advance pos past closing \n``` and optional newline
                val afterClosing = workingText.indexOf('\n', closingIdx + 4)
                pos = if (afterClosing != -1) afterClosing + 1 else closingIdx + 4
                precedingProseForPath = null
            }
        }

        return DemuxResult(
            thinkingText = finalThinkingText,
            isThinking = finalIsThinking,
            blocks = blocks
        )
    }

    private fun detectPathFromCodeComments(code: String): String? {
        val lines = code.lines().take(3)
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
                             lower.contains("background:") || lower.contains("font-size:") ||
                             trimmed.startsWith("```")

            if (!isCodeLeak) {
                sanitizedLines.add(line)
            }
        }

        return sanitizedLines.joinToString("\n").trim()
    }
}

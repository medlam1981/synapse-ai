package com.synapse.localai

import android.util.Log
import org.json.JSONObject

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
        data class ToolCall(
            val toolName: String,
            val params: Map<String, String>,
            val isClosed: Boolean = true
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
        val thoughts = mutableListOf<String>()
        var currentlyThinking = false

        // Continuously extract all thinking blocks (fenced or XML tags)
        var continueScanning = true
        while (continueScanning && workingText.isNotEmpty()) {
            val fencedRegex = Regex("""```(?:thought|thinking|reasoning)\r?\n([\s\S]*?)(?:```|$)""", RegexOption.IGNORE_CASE)
            val fencedMatch = fencedRegex.find(workingText)

            var earliestTagDef: ThinkTagDef? = null
            var earliestTagIdx = -1
            for (tagDef in THINK_TAGS) {
                val idx = workingText.indexOf(tagDef.open, ignoreCase = true)
                if (idx != -1 && (earliestTagIdx == -1 || idx < earliestTagIdx)) {
                    earliestTagIdx = idx
                    earliestTagDef = tagDef
                }
            }

            val fencedIdx = fencedMatch?.range?.first ?: -1

            if (fencedIdx == -1 && earliestTagIdx == -1) {
                continueScanning = false
                break
            }

            if (fencedIdx != -1 && (earliestTagIdx == -1 || fencedIdx < earliestTagIdx)) {
                val match = fencedMatch!!
                val content = match.groupValues[1].trim()
                if (content.isNotEmpty()) {
                    thoughts.add(content)
                }
                val isClosed = match.value.endsWith("```")
                currentlyThinking = !isClosed
                workingText = workingText.removeRange(match.range).trim()
                if (!isClosed) {
                    continueScanning = false
                }
            } else if (earliestTagDef != null && earliestTagIdx != -1) {
                val tagDef = earliestTagDef
                val afterOpen = workingText.substring(earliestTagIdx + tagDef.open.length)
                val closeIdx = afterOpen.indexOf(tagDef.close, ignoreCase = true)
                if (closeIdx == -1) {
                    val rawThought = afterOpen.trimStart()
                    val cleanThought = rawThought.replace(Regex("""</?[a-zA-Z0-9_|]*$"""), "").trim()
                    if (cleanThought.isNotEmpty()) {
                        thoughts.add(cleanThought)
                    }
                    currentlyThinking = true
                    workingText = workingText.substring(0, earliestTagIdx).trim()
                    continueScanning = false
                } else {
                    val thought = afterOpen.substring(0, closeIdx).trim()
                    if (thought.isNotEmpty()) {
                        thoughts.add(thought)
                    }
                    currentlyThinking = false
                    val before = workingText.substring(0, earliestTagIdx).trim()
                    val after = afterOpen.substring(closeIdx + tagDef.close.length).trim()
                    workingText = if (before.isNotEmpty() && after.isNotEmpty()) "$before\n\n$after" else before + after
                }
            }
        }

        // Post-extraction sanitation: strip any stray or orphaned thinking tags from workingText
        for (tagDef in THINK_TAGS) {
            workingText = workingText.replace(tagDef.open, "", ignoreCase = true).replace(tagDef.close, "", ignoreCase = true)
        }
        workingText = workingText
            .replace(Regex("""</?\|?(?:think|thought|reasoning)\|?>""", RegexOption.IGNORE_CASE), "")
            .trim()

        // Strip incomplete trailing opening tags in active stream
        val trailingOpenTagRegex = Regex("""<\|?(?:th(?:i(?:n(?:k\|?)?)?)?|th(?:o(?:u(?:g(?:h(?:t\|?)?)?)?)?)?|re(?:a(?:s(?:o(?:n(?:i(?:n(?:g\|?)?)?)?)?)?)?)?)$""", RegexOption.IGNORE_CASE)
        if (trailingOpenTagRegex.containsMatchIn(workingText)) {
            workingText = workingText.replace(trailingOpenTagRegex, "").trimEnd()
            currentlyThinking = true
        }

        val extractedThought = thoughts.filter { it.isNotBlank() }.joinToString("\n\n").trim()
        val finalThinkingText = when {
            extractedThought.isNotEmpty() && externalThinkingText.isNotEmpty() &&
                    !externalThinkingText.contains(extractedThought) && !extractedThought.contains(externalThinkingText) -> {
                "$externalThinkingText\n\n$extractedThought"
            }
            extractedThought.isNotEmpty() -> extractedThought
            else -> externalThinkingText
        }

        val finalIsThinking = currentlyThinking || (externalIsThinking && finalThinkingText.isNotBlank())

        // 2. Pre-Parser Tool-Call Quarantine:
        // Intercept any <function_calls> or <invoke> structures before token dispatch to markdown or code parsers.
        val (textWithoutTools, toolCalls) = quarantineToolCalls(workingText)

        // 3. Strict Explicit Fence Parser (Anti-Leakage & Zero Auto-HTML Guessing)
        val blocks = mutableListOf<DemuxBlock>()
        var pos = 0
        val textLen = textWithoutTools.length
        var pendingFilePathFromProse: String? = null

        while (pos < textLen) {
            val fenceIdx = textWithoutTools.indexOf("```", pos)
            if (fenceIdx == -1) {
                // Remainder is strictly Prose. Zero heuristic code guessing!
                val remainder = textWithoutTools.substring(pos).trim()
                if (remainder.isNotBlank()) {
                    val cleanProse = sanitizeProse(remainder)
                    if (cleanProse.isNotBlank()) {
                        blocks.add(DemuxBlock.Prose(cleanProse))
                    }
                }
                break
            }

            // Text prior to the code fence is pure Prose
            if (fenceIdx > pos) {
                val proseChunk = textWithoutTools.substring(pos, fenceIdx).trim()
                if (proseChunk.isNotBlank()) {
                    val cleanProse = sanitizeProse(proseChunk)
                    if (cleanProse.isNotBlank()) {
                        blocks.add(DemuxBlock.Prose(cleanProse))
                    }
                    // Track explicit file path directives for the immediately upcoming code block
                    pendingFilePathFromProse = ToolExecutor.detectFilePath(proseChunk)
                }
            }

            // Transition to Code Phase
            val afterOpening = textWithoutTools.substring(fenceIdx + 3)
            val newlineIdx = afterOpening.indexOf('\n')

            if (newlineIdx == -1) {
                // Code block header actively in progress (e.g. ``` or ```python)
                val rawLang = afterOpening.trim().lowercase()
                val lang = when {
                    rawLang.isBlank() || rawLang == "code" -> "plaintext"
                    else -> rawLang
                }
                blocks.add(
                    DemuxBlock.Code(
                        language = lang,
                        code = "",
                        filePath = pendingFilePathFromProse,
                        isClosed = false
                    )
                )
                break
            }

            val rawLang = afterOpening.substring(0, newlineIdx).trim().lowercase()
            val codeStartIdx = fenceIdx + 3 + newlineIdx + 1

            // Strict Anti-HTML Guessing:
            // Do NOT assign HTML unless explicitly declared as "html" or "htm"
            val lang = when {
                rawLang == "html" || rawLang == "htm" -> "html"
                rawLang.isBlank() || rawLang == "code" -> {
                    val preview = textWithoutTools.substring(codeStartIdx).take(60).trim()
                    if (preview.startsWith("$") || preview.startsWith("#!/") ||
                        preview.startsWith("pm ") || preview.startsWith("am ") ||
                        preview.startsWith("echo ") || preview.startsWith("ls ")) "bash" else "plaintext"
                }
                else -> rawLang
            }

            val targetPath = pendingFilePathFromProse

            // Search for closing delimiter: match ``` preceded by \n or whitespace, or start of line
            val closingIdx = findClosingFence(textWithoutTools, codeStartIdx)
            if (closingIdx == -1) {
                // Code block is actively streaming: lock tokens inside this code container
                val activeCode = textWithoutTools.substring(codeStartIdx)
                blocks.add(
                    DemuxBlock.Code(
                        language = lang,
                        code = activeCode,
                        filePath = targetPath,
                        isClosed = false
                    )
                )
                break
            } else {
                // Code block closed cleanly: seal the active container and lock the internal code buffer
                val closedCode = textWithoutTools.substring(codeStartIdx, closingIdx)
                blocks.add(
                    DemuxBlock.Code(
                        language = lang,
                        code = closedCode,
                        filePath = targetPath,
                        isClosed = true
                    )
                )

                // Advance pos past closing ``` and optional following newline
                val pastClosing = closingIdx + 3
                val nextNewline = textWithoutTools.indexOf('\n', pastClosing)
                pos = if (nextNewline != -1 && nextNewline <= pastClosing + 2) nextNewline + 1 else pastClosing
                pendingFilePathFromProse = null
            }
        }

        // Append quarantined tool calls as clean single-line events
        if (toolCalls.isNotEmpty()) {
            blocks.addAll(toolCalls)
        }

        return DemuxResult(
            thinkingText = finalThinkingText,
            isThinking = finalIsThinking,
            blocks = blocks
        )
    }

    /**
     * Resilient parameter parser that handles:
     * 1. XML parameter tags: <parameter name="param">value</parameter>
     * 2. Direct named tags: <command>...</command>, <query>...</query>
     * 3. Key-Value lines: command: "..." or query: "..."
     * 4. JSON objects: {"command": "..."}
     * 5. Raw text body inside <invoke> or <parameters>
     */
    fun extractToolParams(rawBody: String): Map<String, String> {
        val params = mutableMapOf<String, String>()

        // 1. Standard XML: <parameter name="param_name">value</parameter>
        val pRegex = Regex("<parameter name=[\"'](.*?)[\"']>(.*?)(?:</parameter>|$)", RegexOption.DOT_MATCHES_ALL)
        pRegex.findAll(rawBody).forEach { p ->
            val k = p.groupValues[1].trim()
            val v = p.groupValues[2].trim()
            if (v.isNotBlank()) params[k] = v
        }
        if (params.isNotEmpty()) return params

        // 2. Direct tag names: <command>...</command>, <query>...</query>, etc.
        val tagRegex = Regex("<([a-zA-Z0-9_-]+)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
        tagRegex.findAll(rawBody).forEach { t ->
            val k = t.groupValues[1].trim().lowercase()
            val v = t.groupValues[2].trim()
            if (k != "parameters" && k != "parameter" && v.isNotBlank()) {
                params[k] = v
            }
        }
        if (params.isNotEmpty()) return params

        // 3. Key-Value pairs: command: "..." or query: "..."
        val kvRegex = Regex("""(?:^|\n)\s*([a-zA-Z0-9_-]+)\s*[:=]\s*["']?([\s\S]*?)["']?\s*(?:$|\n)""")
        kvRegex.findAll(rawBody).forEach { kv ->
            val k = kv.groupValues[1].trim().lowercase()
            val v = kv.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
            if (k in listOf("command", "cmd", "query", "q", "description", "desc", "delay_minutes", "summary") && v.isNotBlank()) {
                params[k] = v
            }
        }
        if (params.isNotEmpty()) return params

        // 4. JSON structure: {"command": "..."}
        val trimmed = rawBody.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    params[k] = json.optString(k, "")
                }
                if (params.isNotEmpty()) return params
            } catch (_: Exception) {}
        }

        // 5. Raw text fallback inside <invoke> or <parameters>
        val clean = rawBody
            .replace("<parameters>", "")
            .replace("</parameters>", "")
            .replace("command:", "")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
        if (clean.isNotBlank()) {
            params["command"] = clean
            params["cmd"] = clean
            params["query"] = clean
            params["q"] = clean
            params["summary"] = clean
        }

        return params
    }

    /**
     * Intercepts and quarantines tool calls from the raw stream text.
     * Removes all tool call XML/fences from the returned prose text so that code containers
     * and file I/O operations are completely muted.
     */
    private fun quarantineToolCalls(text: String): Pair<String, List<DemuxBlock.ToolCall>> {
        if (!text.contains("<function_calls") && !text.contains("<tool_calls") && !text.contains("<invoke") && !text.contains("</invoke>")) {
            return text to emptyList()
        }

        val toolCalls = mutableListOf<DemuxBlock.ToolCall>()
        val iRegex = Regex("<invoke name=[\"'](.*?)[\"']>(.*?)(?:</invoke>|$)", RegexOption.DOT_MATCHES_ALL)

        var cleanText = text

        val parseInvokes = { source: String ->
            iRegex.findAll(source).forEach { inv ->
                val toolName = inv.groupValues[1].trim()
                val inner = inv.groupValues[2].trim()
                val params = extractToolParams(inner)
                val isClosed = inv.value.contains("</invoke>")
                toolCalls.add(DemuxBlock.ToolCall(toolName, params, isClosed))
            }
        }

        // 1. Strip fenced tool calls: ```xml\n<function_calls>...``` or ```\n<function_calls>...```
        val fencedXmlRegex = Regex("""```(?:xml)?\s*(<(?:tool_calls|function_calls)>[\s\S]*?(?:</(?:tool_calls|function_calls)>|```|$))""", RegexOption.IGNORE_CASE)
        fencedXmlRegex.findAll(cleanText).forEach { m ->
            parseInvokes(m.groupValues[1])
        }
        cleanText = fencedXmlRegex.replace(cleanText, "")

        // 2. Strip unfenced <function_calls> or <tool_calls> blocks
        val fnRegex = Regex("""<(?:tool_calls|function_calls)>[\s\S]*?(?:</(?:tool_calls|function_calls)>|$)""", RegexOption.IGNORE_CASE)
        fnRegex.findAll(cleanText).forEach { m ->
            parseInvokes(m.value)
        }
        cleanText = fnRegex.replace(cleanText, "")

        // 3. Strip any standalone <invoke> tags
        parseInvokes(cleanText)
        cleanText = iRegex.replace(cleanText, "")

        // 4. Strip any residual XML tags / artifacts
        cleanText = cleanText
            .replace("<tool_calls>", "")
            .replace("</tool_calls>", "")
            .replace("<function_calls>", "")
            .replace("</function_calls>", "")
            .replace(Regex("""<(?:tool_calls|function_calls)\b.*"""), "")
            .trim()

        return cleanText to toolCalls
    }

    /**
     * Finds the closing fence ``` starting from fromIndex.
     * Supports newline-preceded ```, start-of-line ```, or immediately following ```.
     * Also detects if an unclosed block hits an Executive Deployment Confirmation or Tool tag.
     */
    private fun findClosingFence(text: String, fromIndex: Int): Int {
        val execIdx1 = text.indexOf("### Executive Deployment Confirmation", fromIndex)
        val execIdx2 = text.indexOf("Executive Deployment Confirmation:", fromIndex)
        val toolIdx1 = text.indexOf("<function_calls", fromIndex)
        val toolIdx2 = text.indexOf("<tool_calls", fromIndex)
        val toolIdx3 = text.indexOf("<invoke", fromIndex)

        val boundaries = listOf(execIdx1, execIdx2, toolIdx1, toolIdx2, toolIdx3).filter { it != -1 }
        val earliestBoundary = if (boundaries.isNotEmpty()) boundaries.minOrNull() ?: -1 else -1

        val fence = text.indexOf("```", fromIndex)
        if (fence != -1 && (earliestBoundary == -1 || fence < earliestBoundary)) {
            return fence
        }
        if (earliestBoundary != -1) {
            return earliestBoundary
        }
        return -1
    }

    /**
     * Strips leaked tool XML tags, function declarations, and raw backtick artifacts
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

            // Filter out any XML invoke / function_calls / tool_calls tags
            if (trimmed.startsWith("<invoke", ignoreCase = true) || trimmed.startsWith("</invoke>", ignoreCase = true) ||
                trimmed.startsWith("<function_calls>", ignoreCase = true) || trimmed.startsWith("</function_calls>", ignoreCase = true) ||
                trimmed.startsWith("<tool_calls>", ignoreCase = true) || trimmed.startsWith("</tool_calls>", ignoreCase = true) ||
                trimmed.startsWith("<parameters>", ignoreCase = true) || trimmed.startsWith("</parameters>", ignoreCase = true) ||
                trimmed.startsWith("<parameter", ignoreCase = true) || trimmed.startsWith("</parameter>", ignoreCase = true)) {
                continue
            }

            // Filter out thinking tags
            if (trimmed.startsWith("<think", ignoreCase = true) || trimmed.startsWith("</think", ignoreCase = true) ||
                trimmed.startsWith("<thought", ignoreCase = true) || trimmed.startsWith("</thought", ignoreCase = true) ||
                trimmed.startsWith("<reasoning", ignoreCase = true) || trimmed.startsWith("</reasoning", ignoreCase = true) ||
                trimmed.startsWith("<|think", ignoreCase = true) || trimmed.startsWith("<|/think", ignoreCase = true) ||
                trimmed.startsWith("<|thought", ignoreCase = true) || trimmed.startsWith("<|/thought", ignoreCase = true) ||
                trimmed.startsWith("<|reasoning", ignoreCase = true) || trimmed.startsWith("<|/reasoning", ignoreCase = true)) {
                continue
            }

            // Filter raw backtick artifacts
            if (trimmed.startsWith("```")) continue

            val cleanLine = line.replace(Regex("""</?\|?(?:think|thought|reasoning)\|?>""", RegexOption.IGNORE_CASE), "")
            if (cleanLine.isNotBlank()) {
                sanitizedLines.add(cleanLine)
            }
        }

        return sanitizedLines.joinToString("\n").trim()
    }
}

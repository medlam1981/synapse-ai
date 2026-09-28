package com.synapse.localai

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.nio.charset.StandardCharsets

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
            val executionId: String = UUID.randomUUID().toString(),
            val toolName: String,
            val params: Map<String, String>,
            val isClosed: Boolean = true
        ) : DemuxBlock()
        data class TerminalFrame(
            val executionId: String = UUID.randomUUID().toString(),
            val command: String,
            val output: String,
            val exitCode: Int = 0,
            val isRunning: Boolean = false
        ) : DemuxBlock()
        data class Image(
            val executionId: String = UUID.randomUUID().toString(),
            val imageBytes: ByteArray,
            val prompt: String = ""
        ) : DemuxBlock() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Image
                return executionId == other.executionId && imageBytes.contentEquals(other.imageBytes) && prompt == other.prompt
            }
            override fun hashCode(): Int {
                var result = executionId.hashCode()
                result = 31 * result + imageBytes.contentHashCode()
                result = 31 * result + prompt.hashCode()
                return result
            }
        }
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
        externalIsThinking: Boolean = false,
        externalImageBytes: ByteArray? = null,
        imagePrompt: String = ""
    ): DemuxResult {
        if (rawText.isBlank() && externalThinkingText.isBlank() && (externalImageBytes == null || externalImageBytes.isEmpty())) {
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

        // 2. Pre-Parser Tool-Call Quarantine (Wipes JSON/XML tool calls from conversation prose)
        val (textWithoutTools, toolBlocksMap) = quarantineToolCalls(workingText)
        val placedBlockIds = mutableSetOf<String>()

        // 3. Strict Explicit Fence Parser (Anti-Leakage & Zero Auto-HTML Guessing)
        val blocks = mutableListOf<DemuxBlock>()
        var pos = 0
        val textLen = textWithoutTools.length
        var pendingFilePathFromProse: String? = null

        while (pos < textLen) {
            val fenceIdx = textWithoutTools.indexOf("```", pos)
            if (fenceIdx == -1) {
                val remainder = textWithoutTools.substring(pos).trim()
                if (remainder.isNotBlank()) {
                    emitProseWithBlocks(remainder, toolBlocksMap, placedBlockIds, blocks)
                }
                break
            }

            if (fenceIdx > pos) {
                val proseChunk = textWithoutTools.substring(pos, fenceIdx).trim()
                if (proseChunk.isNotBlank()) {
                    emitProseWithBlocks(proseChunk, toolBlocksMap, placedBlockIds, blocks)
                    pendingFilePathFromProse = ToolExecutor.detectFilePath(proseChunk)
                }
            }

            val afterOpening = textWithoutTools.substring(fenceIdx + 3)
            val newlineIdx = afterOpening.indexOf('\n')

            if (newlineIdx == -1) {
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

            val headerLine = afterOpening.substring(0, newlineIdx).trim()
            val pathFromHeader = Regex("""(?:filepath|path)=["']?([^"'\s]+)["']?""", RegexOption.IGNORE_CASE)
                .find(headerLine)?.groupValues?.get(1)
            val rawLang = headerLine.substringBefore(" ").trim().lowercase()
            val codeStartIdx = fenceIdx + 3 + newlineIdx + 1

            val lang = when {
                rawLang == "html" || rawLang == "htm" -> "html"
                rawLang == "js" || rawLang == "javascript" -> "javascript"
                rawLang == "css" -> "css"
                rawLang == "py" || rawLang == "python" -> "python"
                rawLang.isBlank() || rawLang == "code" -> {
                    val preview = textWithoutTools.substring(codeStartIdx).take(60).trim()
                    if (preview.startsWith("$") || preview.startsWith("#!/") ||
                        preview.startsWith("pm ") || preview.startsWith("am ") ||
                        preview.startsWith("echo ") || preview.startsWith("ls ")) "bash" else "plaintext"
                }
                else -> rawLang
            }

            val targetPath = pathFromHeader ?: pendingFilePathFromProse
            val isHtml = lang == "html" || targetPath?.endsWith(".html", ignoreCase = true) == true

            val closingIdx = findClosingFence(textWithoutTools, codeStartIdx)
            if (closingIdx == -1) {
                val activeCode = textWithoutTools.substring(codeStartIdx)
                val finalActiveCode = if (isHtml) HtmlSanitizer.sanitizeHtmlStream(activeCode) else activeCode
                blocks.add(
                    DemuxBlock.Code(
                        language = lang,
                        code = finalActiveCode,
                        filePath = targetPath,
                        isClosed = false
                    )
                )
                break
            } else {
                val closedCode = textWithoutTools.substring(codeStartIdx, closingIdx)
                val finalClosedCode = if (isHtml) HtmlSanitizer.sanitizeHtmlStream(closedCode) else closedCode
                blocks.add(
                    DemuxBlock.Code(
                        language = lang,
                        code = finalClosedCode,
                        filePath = targetPath,
                        isClosed = true
                    )
                )

                val pastClosing = closingIdx + 3
                val nextNewline = textWithoutTools.indexOf('\n', pastClosing)
                pos = if (nextNewline != -1 && nextNewline <= pastClosing + 2) nextNewline + 1 else pastClosing
                pendingFilePathFromProse = null
            }
        }

        // Append any orphaned tool blocks that were not placed inline
        for ((id, block) in toolBlocksMap) {
            if (!placedBlockIds.contains(id)) {
                blocks.add(block)
            }
        }

        if (externalImageBytes != null && externalImageBytes.isNotEmpty()) {
            blocks.add(DemuxBlock.Image(imageBytes = externalImageBytes, prompt = imagePrompt))
        }

        return DemuxResult(
            thinkingText = finalThinkingText,
            isThinking = finalIsThinking,
            blocks = blocks
        )
    }

    /**
     * Resilient parameter parser for XML, JSON, and flat key-value pairs.
     */
    fun extractToolParams(rawBody: String): Map<String, String> {
        val params = mutableMapOf<String, String>()

        val paramTagRegex = Regex("""<parameter\s+name=["']?([^"'>\s]+)["']?[^>]*>([\s\S]*?)</parameter>""", RegexOption.IGNORE_CASE)
        for (m in paramTagRegex.findAll(rawBody)) {
            val k = m.groupValues[1].trim().lowercase()
            val v = unescapeXml(m.groupValues[2].trim())
            if (k.isNotBlank() && v.isNotBlank()) {
                params[k] = v
            }
        }
        if (params.isNotEmpty()) return params

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

        val kvRegex = Regex("""(?:^|\n)\s*([a-zA-Z0-9_-]+)\s*[:=]\s*["']?([\s\S]*?)["']?\s*(?:$|\n)""")
        kvRegex.findAll(rawBody).forEach { kv ->
            val k = kv.groupValues[1].trim().lowercase()
            val v = kv.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
            if (k in listOf("command", "cmd", "query", "q", "description", "desc", "delay_minutes", "summary", "package_name", "path", "content") && v.isNotBlank()) {
                params[k] = v
            }
        }

        if (params.isEmpty() && trimmed.isNotBlank() && !trimmed.startsWith("<")) {
            params["command"] = trimmed
        }

        return params
    }

    data class ParsedToolCall(
        val name: String,
        val params: Map<String, String>,
        val isClosed: Boolean = true,
        val rawMatch: String = ""
    )

    /**
     * Robust Tool-Call JSON Parser: Supports standard org.json and resilient regex fallback
     * to handle multiline content, unescaped quotes, and in-progress streaming tokens.
     */
    fun parseJsonToolCall(jsonStr: String): ParsedToolCall? {
        val trimmed = jsonStr.trim()
        if (!trimmed.startsWith("{")) return null
        val isClosed = trimmed.endsWith("}")

        try {
            val json = JSONObject(if (isClosed) trimmed else "$trimmed}")
            var toolName = json.optString("name", "").ifBlank {
                json.optString("tool", "").ifBlank {
                    json.optString("action", "")
                }
            }

            if (toolName.isBlank() && json.has("function")) {
                val fnObj = json.optJSONObject("function")
                if (fnObj != null) {
                    toolName = fnObj.optString("name", "")
                }
            }

            if (toolName.isNotBlank()) {
                val params = mutableMapOf<String, String>()
                val argsObj = json.optJSONObject("parameters")
                    ?: json.optJSONObject("arguments")
                    ?: json.optJSONObject("action_input")
                    ?: json.optJSONObject("function")?.optJSONObject("arguments")

                if (argsObj != null) {
                    val keys = argsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        params[k] = argsObj.optString(k, "")
                    }
                } else {
                    val argsStr = json.optString("arguments", "").ifBlank {
                        json.optString("parameters", "").ifBlank {
                            json.optString("action_input", "")
                        }
                    }
                    if (argsStr.startsWith("{") && argsStr.endsWith("}")) {
                        try {
                            val innerJson = JSONObject(argsStr)
                            val keys = innerJson.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                params[k] = innerJson.optString(k, "")
                            }
                        } catch (_: Exception) {
                            params["command"] = argsStr
                        }
                    } else if (argsStr.isNotBlank()) {
                        params["command"] = argsStr
                    }
                }

                val topKeys = json.keys()
                while (topKeys.hasNext()) {
                    val k = topKeys.next()
                    if (k !in listOf("name", "tool", "action", "function", "parameters", "arguments", "action_input")) {
                        if (!params.containsKey(k)) {
                            params[k] = json.optString(k, "")
                        }
                    }
                }

                return ParsedToolCall(toolName.lowercase(), params, isClosed, jsonStr)
            }
        } catch (_: Exception) {}

        try {
            val nameMatch = Regex("""\"(?:name|tool|action)\"\s*:\s*\"([^\"]+)\"""", RegexOption.IGNORE_CASE).find(trimmed)
                ?: Regex("""\"function\"\s*:\s*\{\s*\"name\"\s*:\s*\"([^\"]+)\"""", RegexOption.IGNORE_CASE).find(trimmed)
            if (nameMatch == null) return null
            val toolName = nameMatch.groupValues[1].trim().lowercase()

            val params = mutableMapOf<String, String>()
            val paramPairs = Regex("""\"([a-zA-Z0-9_-]+)\"\s*:\s*(?:\"((?:\\\"|[^\"])*)\"|(\d+|true|false))""").findAll(trimmed)
            for (p in paramPairs) {
                val key = p.groupValues[1].lowercase()
                if (key !in listOf("name", "tool", "action", "function", "type")) {
                    val value = if (p.groupValues[2].isNotEmpty()) {
                        p.groupValues[2].replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t")
                    } else {
                        p.groupValues[3]
                    }
                    params[key] = value
                }
            }

            if (!params.containsKey("content")) {
                val contentMatch = Regex("""\"content\"\s*:\s*\"([\s\S]*?)(?:\"\s*,\s*\"[a-zA-Z0-9_-]+\"|\"\s*\}|$)""").find(trimmed)
                if (contentMatch != null) {
                    params["content"] = contentMatch.groupValues[1].replace("\\\"", "\"")
                }
            }

            return ParsedToolCall(toolName, params, isClosed, jsonStr)
        } catch (_: Exception) {}

        return null
    }

    fun parseJsonOrArrayToolCalls(raw: String): List<ParsedToolCall> {
        val trimmed = raw.trim()
        val list = mutableListOf<ParsedToolCall>()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            try {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    if (item != null) {
                        parseJsonToolCall(item.toString())?.let { list.add(it) }
                    }
                }
            } catch (_: Exception) {}
        } else {
            parseJsonToolCall(trimmed)?.let { list.add(it) }
        }
        return list
    }

    fun parsePythonArgs(argsStr: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val argRegex = Regex("""([a-zA-Z0-9_-]+)\s*=\s*(["'])(.*?)\2""")
        for (m in argRegex.findAll(argsStr)) {
            params[m.groupValues[1].lowercase()] = m.groupValues[3]
        }
        if (params.isEmpty() && argsStr.isNotBlank()) {
            val clean = argsStr.removeSurrounding("\"").removeSurrounding("'").trim()
            params["command"] = clean
            params["package_name"] = clean
        }
        return params
    }

    fun parseAllToolCalls(text: String): List<ParsedToolCall> {
        val results = mutableListOf<ParsedToolCall>()
        val seen = mutableSetOf<String>()

        // 0. Explicit <tool_call> JSON tags (ATLAS / Gemma standard)
        val toolCallRegex = Regex("""<tool_call>([\s\S]*?)(?:</tool_call>|$)""", RegexOption.IGNORE_CASE)
        for (m in toolCallRegex.findAll(text)) {
            val inner = m.groupValues[1].trim()
            val isClosed = m.value.contains("</tool_call>")
            parseJsonOrArrayToolCalls(inner).forEach { call ->
                val key = "${call.name}:${call.params.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}"
                if (!seen.contains(key)) {
                    seen.add(key)
                    results.add(call.copy(isClosed = isClosed, rawMatch = m.value))
                }
            }
        }

        // 0.1 Explicit <function_call> tags
        val functionCallRegex = Regex("""<function_call\b[^>]*>([\s\S]*?)(?:</function_call>|$)""", RegexOption.IGNORE_CASE)
        for (m in functionCallRegex.findAll(text)) {
            val inner = m.groupValues[1].trim()
            val isClosed = m.value.contains("</function_call>")
            parseJsonOrArrayToolCalls(inner).forEach { call ->
                val key = "${call.name}:${call.params.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}"
                if (!seen.contains(key)) {
                    seen.add(key)
                    results.add(call.copy(isClosed = isClosed, rawMatch = m.value))
                }
            }
        }

        // 1. XML <invoke name="..."> or <function_calls> parsing
        val invokeRegex = Regex("""<invoke\s+name=["']?([^"'>\s]+)["']?[^>]*>([\s\S]*?)(?:</invoke>|$)""", RegexOption.IGNORE_CASE)
        for (m in invokeRegex.findAll(text)) {
            val toolName = m.groupValues[1].trim()
            val innerBody = m.groupValues[2]
            val params = extractToolParams(innerBody)
            val isClosed = m.value.contains("</invoke>")
            val key = "$toolName:${params.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}"
            if (!seen.contains(key)) {
                seen.add(key)
                results.add(ParsedToolCall(name = toolName.lowercase(), params = params, isClosed = isClosed, rawMatch = m.value))
            }
        }

        // 2. Fenced ```json ... ``` tool calls (FIXED: Using raw multiline string pattern)
        val fencedJsonRegex = Regex("""```(?:json)?\s*\n?(\{[\s\S]*?\}|\[[\s\S]*?\])\s*\n?```""", RegexOption.IGNORE_CASE)
        for (m in fencedJsonRegex.findAll(text)) {
            val jsonStr = m.groupValues[1].trim()
            parseJsonOrArrayToolCalls(jsonStr).forEach { call ->
                val key = "${call.name}:${call.params.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}"
                if (!seen.contains(key)) {
                    seen.add(key)
                    results.add(call.copy(rawMatch = m.value))
                }
            }
        }

        // 3. Raw unfenced JSON tool calls (Intercepts write_file, path, content, command even when multiline)
        val rawJsonRegex = Regex("""(?:\A|\n)\s*(\{\s*\"(?:name|tool|action|type)\"\s*:[\s\S]*?(?:\}\s*(?:\n|\Z)|$))""")
        for (m in rawJsonRegex.findAll(text)) {
            val jsonStr = m.groupValues[1].trim()
            parseJsonToolCall(jsonStr)?.let { call ->
                val key = "${call.name}:${call.params.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}"
                if (!seen.contains(key)) {
                    seen.add(key)
                    results.add(call.copy(rawMatch = m.value))
                }
            }
        }

        return results
    }

    private fun emitProseWithBlocks(
        rawText: String,
        toolBlocksMap: Map<String, DemuxBlock>,
        placedBlockIds: MutableSet<String>,
        targetBlocks: MutableList<DemuxBlock>
    ) {
        val placeholderRegex = Regex("""___DEMUX_BLOCK_([a-zA-Z0-9_\-]+)___""")
        var currentIdx = 0
        val matches = placeholderRegex.findAll(rawText).toList()
        if (matches.isEmpty()) {
            val clean = sanitizeProse(rawText)
            if (clean.isNotBlank()) {
                targetBlocks.add(DemuxBlock.Prose(clean))
            }
            return
        }

        for (m in matches) {
            if (m.range.first > currentIdx) {
                val textBefore = rawText.substring(currentIdx, m.range.first)
                val clean = sanitizeProse(textBefore)
                if (clean.isNotBlank()) {
                    targetBlocks.add(DemuxBlock.Prose(clean))
                }
            }
            val blockId = m.groupValues[1]
            val block = toolBlocksMap[blockId]
            if (block != null && !placedBlockIds.contains(blockId)) {
                placedBlockIds.add(blockId)
                targetBlocks.add(block)
            }
            currentIdx = m.range.last + 1
        }

        if (currentIdx < rawText.length) {
            val textAfter = rawText.substring(currentIdx)
            val clean = sanitizeProse(textAfter)
            if (clean.isNotBlank()) {
                targetBlocks.add(DemuxBlock.Prose(clean))
            }
        }
    }

    /**
     * Synchronous ReAct Barrier: Detects if incoming token text has reached a completed tool call.
     */
    fun hasCompletedToolCall(text: String): Boolean {
        if (text.contains("</tool_call>", ignoreCase = true) && text.contains("<tool_call", ignoreCase = true)) return true
        if (text.contains("</invoke>", ignoreCase = true) && text.contains("<invoke", ignoreCase = true)) return true
        if (text.contains("</function_call>", ignoreCase = true) && text.contains("<function_call", ignoreCase = true)) return true
        if (text.contains("</function_calls>", ignoreCase = true) && text.contains("<function_calls", ignoreCase = true)) return true
        if (text.contains("</terminal_frame>", ignoreCase = true) && text.contains("<terminal_frame", ignoreCase = true)) return true
        if (Regex("""```(?:json)?\s*\{[\s\S]*?\"(?:name|tool|action)\"\s*:[\s\S]*?\}\s*```""").containsMatchIn(text)) return true
        if (Regex("""(?:\A|\n)\s*\{\s*\"(?:name|tool|action)\"\s*:\s*\"[^\"]+\"[\s\S]*?\}\s*(?:\n|\Z)""").containsMatchIn(text)) return true
        return false
    }

    /**
     * Truncates token stream cleanly at the end of the tool invocation delimiter.
     */
    fun truncateAfterToolCall(text: String): String {
        val endTags = listOf("</tool_call>", "</invoke>", "</function_call>", "</function_calls>", "</terminal_frame>")
        var earliestEnd = -1
        for (tag in endTags) {
            val idx = text.indexOf(tag, ignoreCase = true)
            if (idx != -1) {
                val endPos = idx + tag.length
                if (earliestEnd == -1 || endPos < earliestEnd) {
                    earliestEnd = endPos
                }
            }
        }
        if (earliestEnd != -1) {
            return text.substring(0, earliestEnd).trimEnd()
        }
        val fencedMatch = Regex("""```(?:json)?\s*\{[\s\S]*?\"(?:name|tool|action)\"\s*:[\s\S]*?\}\s*```""").find(text)
        if (fencedMatch != null) {
            return text.substring(0, fencedMatch.range.last + 1).trimEnd()
        }
        val rawJsonMatch = Regex("""(?:\A|\n)\s*(\{\s*\"(?:name|tool|action)\"\s*:\s*\"[^\"]+\"[\s\S]*?\})\s*(?:\n|\Z)""").find(text)
        if (rawJsonMatch != null) {
            return text.substring(0, rawJsonMatch.range.last + 1).trimEnd()
        }
        return text
    }

    /**
     * Extracts explanatory text (preamble) that the agent emitted before triggering a tool call.
     */
    fun extractPreambleProse(text: String, toolMatch: String = ""): String {
        val toolIdx = if (toolMatch.isNotBlank()) text.indexOf(toolMatch) else -1
        val beforeTool = if (toolIdx != -1) {
            text.substring(0, toolIdx)
        } else {
            val candidates = listOf("<tool_call", "<invoke", "<function_call", "<function_calls", "<terminal_frame", "{\"name\":", "{\"tool\":", "{\"action\":")
            val minIdx = candidates.map { text.indexOf(it, ignoreCase = true) }.filter { it != -1 }.minOrNull() ?: -1
            if (minIdx != -1) text.substring(0, minIdx) else text
        }
        return sanitizeProse(beforeTool).trim()
    }

    fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun unescapeXml(text: String): String {
        return text.replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
            .replace("&amp;", "&")
    }

    /**
     * Intercepts and quarantines tool calls, terminal frames, and raw command traces
     * from the raw stream text, replacing them with positional placeholders.
     */
    private fun quarantineToolCalls(text: String): Pair<String, Map<String, DemuxBlock>> {
        val blockMap = mutableMapOf<String, DemuxBlock>()
        var cleanText = text
        val seenCommands = mutableSetOf<String>()
        val seenToolCalls = mutableSetOf<String>()

        // 1. Intercept explicit <terminal_frame ...>output</terminal_frame>
        val tfRegex = Regex("""<terminal_frame([^>]*)>([\s\S]*?)(?:</terminal_frame>|$)""", RegexOption.IGNORE_CASE)
        val attrRegex = Regex("""([a-zA-Z0-9_-]+)=(["'])(.*?)\2""")

        cleanText = tfRegex.replace(cleanText) { m ->
            val attrs = m.groupValues[1]
            val innerContent = m.groupValues[2]
            val attrMap = attrRegex.findAll(attrs).associate { it.groupValues[1].lowercase() to it.groupValues[3] }

            val cmd = unescapeXml(attrMap["command"] ?: "")
            val exitCode = attrMap["exit_code"]?.toIntOrNull() ?: 0
            val explicitId = attrMap["id"] ?: attrMap["execution_id"]
            val output = unescapeXml(innerContent.trim())
            val isClosed = m.value.contains("</terminal_frame>")

            val normalizedCmd = cmd.trim()
            val execId = explicitId?.takeIf { it.isNotBlank() }
                ?: UUID.nameUUIDFromBytes("tf:$normalizedCmd".toByteArray(StandardCharsets.UTF_8)).toString()
            if (!seenCommands.contains(normalizedCmd)) {
                seenCommands.add(normalizedCmd)
                blockMap[execId] = DemuxBlock.TerminalFrame(
                    executionId = execId,
                    command = cmd,
                    output = output,
                    exitCode = exitCode,
                    isRunning = !isClosed
                )
            }
            "\n___DEMUX_BLOCK_${execId}___\n"
        }

        // 2. Intercept legacy shell output
        val legacyShellRegex = Regex("""Shell output \(`([^`]+)`\):\s*```(?:bash|sh)?\r?\n([\s\S]*?)(?:```|$)""", RegexOption.IGNORE_CASE)
        cleanText = legacyShellRegex.replace(cleanText) { m ->
            val cmd = m.groupValues[1].trim()
            val output = m.groupValues[2].trim()
            val isClosed = m.value.endsWith("```")
            val normalizedCmd = cmd.trim()
            val execId = UUID.nameUUIDFromBytes("tf:$normalizedCmd".toByteArray(StandardCharsets.UTF_8)).toString()
            if (!seenCommands.contains(normalizedCmd)) {
                seenCommands.add(normalizedCmd)
                blockMap[execId] = DemuxBlock.TerminalFrame(
                    executionId = execId,
                    command = cmd,
                    output = output,
                    exitCode = 0,
                    isRunning = !isClosed
                )
            }
            "\n___DEMUX_BLOCK_${execId}___\n"
        }

        cleanText = cleanText.replace(Regex("""\[System Tool Execution Output\]:?\r?\n?"""), "")

        // 4. Universal Tool Call Parser: Parse XML, JSON, and Python tag invocations
        val parsedCalls = parseAllToolCalls(cleanText)
        for (call in parsedCalls) {
            val toolName = call.name
            val params = call.params
            val isClosed = call.isClosed

            val execId = if (toolName in listOf("run_shell", "execute_shell", "terminal", "bash", "sh")) {
                val cmd = params["command"] ?: params["cmd"] ?: params.values.firstOrNull() ?: ""
                val normalizedCmd = cmd.trim()
                val id = UUID.nameUUIDFromBytes("tf:$normalizedCmd".toByteArray(StandardCharsets.UTF_8)).toString()
                if (!seenCommands.contains(normalizedCmd)) {
                    seenCommands.add(normalizedCmd)
                    blockMap[id] = DemuxBlock.TerminalFrame(
                        executionId = id,
                        command = cmd,
                        output = if (isClosed) "" else "[Executing command...]",
                        exitCode = 0,
                        isRunning = !isClosed
                    )
                }
                id
            } else {
                val toolKey = "$toolName:${params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }}"
                val id = UUID.nameUUIDFromBytes("tc:$toolKey".toByteArray(StandardCharsets.UTF_8)).toString()
                if (!seenToolCalls.contains(toolKey)) {
                    seenToolCalls.add(toolKey)
                    blockMap[id] = DemuxBlock.ToolCall(
                        executionId = id,
                        toolName = toolName,
                        params = params,
                        isClosed = isClosed
                    )
                }
                id
            }

            if (call.rawMatch.isNotBlank()) {
                cleanText = cleanText.replace(call.rawMatch, "\n___DEMUX_BLOCK_${execId}___\n")
            }
        }

        // Clean up any remaining container artifacts or raw JSON tool invocations from prose canvas
        cleanText = cleanText
            .replace("<tool_calls>", "")
            .replace("</tool_calls>", "")
            .replace("<function_calls>", "")
            .replace("</function_calls>", "")
            .replace("<tool_call>", "")
            .replace("</tool_call>", "")
            .replace("<function_call>", "")
            .replace("</function_call>", "")
            .replace(Regex("""<(?:tool_calls|function_calls)\b.*"""), "")
            .replace("<terminal_frame>", "")
            .replace("</terminal_frame>", "")
            .replace(Regex("""(?:\A|\n)\s*\{\s*\"(?:name|tool|action)\"\s*:[\s\S]*?(?:\}\s*(?:\n|\Z)|$)"""), "")
            .trim()

        return cleanText to blockMap
    }

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
     * Strips leaked tool XML tags, JSON tool declarations, and raw backtick artifacts
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

            // Filter XML invoke / tool_calls tags
            if (trimmed.startsWith("<invoke", ignoreCase = true) || trimmed.startsWith("</invoke>", ignoreCase = true) ||
                trimmed.startsWith("<function_calls>", ignoreCase = true) || trimmed.startsWith("</function_calls>", ignoreCase = true) ||
                trimmed.startsWith("<tool_calls>", ignoreCase = true) || trimmed.startsWith("</tool_calls>", ignoreCase = true) ||
                trimmed.startsWith("<parameters>", ignoreCase = true) || trimmed.startsWith("</parameters>", ignoreCase = true) ||
                trimmed.startsWith("<parameter", ignoreCase = true) || trimmed.startsWith("</parameter>", ignoreCase = true)) {
                continue
            }

            // Filter JSON tool invocation leaks from prose canvas
            if (trimmed.startsWith("{\"name\":", ignoreCase = true) ||
                trimmed.startsWith("{\"tool\":", ignoreCase = true) ||
                trimmed.startsWith("{\"action\":", ignoreCase = true) ||
                trimmed.startsWith("\"name\": \"", ignoreCase = true) ||
                trimmed.startsWith("\"tool\": \"", ignoreCase = true) ||
                trimmed.startsWith("\"action\": \"", ignoreCase = true) ||
                trimmed.startsWith("\"path\": \"", ignoreCase = true) ||
                trimmed.startsWith("\"content\": \"", ignoreCase = true) ||
                trimmed.startsWith("\"command\": \"", ignoreCase = true) ||
                trimmed.startsWith("\"parameters\":", ignoreCase = true) ||
                trimmed.startsWith("\"arguments\":", ignoreCase = true)) {
                continue
            }

            // Filter thinking tags
            if (trimmed.startsWith("<think", ignoreCase = true) || trimmed.startsWith("</think", ignoreCase = true) ||
                trimmed.startsWith("<thought", ignoreCase = true) || trimmed.startsWith("</thought", ignoreCase = true) ||
                trimmed.startsWith("<reasoning", ignoreCase = true) || trimmed.startsWith("</reasoning", ignoreCase = true) ||
                trimmed.startsWith("<|think", ignoreCase = true) || trimmed.startsWith("<|/think", ignoreCase = true) ||
                trimmed.startsWith("<|thought", ignoreCase = true) || trimmed.startsWith("<|/thought", ignoreCase = true) ||
                trimmed.startsWith("<|reasoning", ignoreCase = true) || trimmed.startsWith("<|/reasoning", ignoreCase = true)) {
                continue
            }

            // Filter terminal frames and shell traces
            if (trimmed.startsWith("<terminal_frame", ignoreCase = true) || trimmed.startsWith("</terminal_frame>", ignoreCase = true) ||
                trimmed.startsWith("Shell output (", ignoreCase = true) || trimmed.startsWith("[Process completed with exit code", ignoreCase = true) ||
                trimmed.startsWith("[Command completed with exit code", ignoreCase = true) ||
                trimmed.startsWith("[System Tool Execution Output]", ignoreCase = true) || trimmed.startsWith("cat <<", ignoreCase = true) ||
                trimmed.startsWith("cat <<-", ignoreCase = true)) {
                continue
            }

            if (trimmed.startsWith("```")) continue

            val cleanLine = line.replace(Regex("""</?\|?(?:think|thought|reasoning)\|?>""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<terminal_frame[^>]*>""", RegexOption.IGNORE_CASE), "")
                .replace("</terminal_frame>", "", ignoreCase = true)
            if (cleanLine.isNotBlank()) {
                sanitizedLines.add(cleanLine)
            }
        }

        return sanitizedLines.joinToString("\n").trim()
    }
}

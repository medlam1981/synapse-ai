package com.synapse.localai

import com.synapse.localai.AtlasLog as Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Dynamic Model-Family Adapter & Chat Template Engine
 *
 * Dynamically binds model architectures (Gemma, Qwen ChatML, Llama-3) to:
 * - Native turn delimiters and prompt constructors
 * - Family-specific tool calling protocols and structured schemas
 * - Strict stop sequences for prompt cache stability and leak prevention
 * - Observation and continuation turn formatting
 */
object ChatTemplateEngine {

    private const val TAG = "ChatTemplateEngine"

    enum class Architecture(val tag: String, val templateName: String) {
        GEMMA("gemma", "gemma"),
        QWEN("qwen", "chatml"),
        LLAMA_3("llama3", "llama3")
    }

    data class ModelMetadata(
        val architecture: Architecture = Architecture.GEMMA,
        val archName: String = "gemma",
        val modelName: String = "Gemma",
        val chatTemplate: String? = "gemma",
        val contextLength: Int = 4096,
        val blockCount: Int? = null
    )

    const val ATLAS_SYSTEM_DIRECTIVE = """[SYSTEM DIRECTIVE]: You are ATLAS-AI, a state-of-the-art edge AI assistant engineered for high-performance, offline execution. You are highly intelligent, analytical, and concise. You provide professional, elite-level technical and general assistance.
CRITICAL MANDATE: You MUST always reply in the exact same language as the user's prompt. If the user writes in Arabic, you MUST respond in fluent, natural Arabic. Do not break character.
When asked about real-time events, facts, or unknown entities, you MUST use the web search tool. Stop generating standard text and output exactly this format:
<tool_call>{"name": "web_search", "query": "your search keywords"}</tool_call>
When asked to run terminal/shell/bash commands or inspect system state, output exactly:
<tool_call>{"name": "bash", "command": "your command"}</tool_call>
When asked to generate, create, or draw an image, output:
<tool_call>{"name": "generate_image", "prompt": "detailed visual description"}</tool_call>
Stop generating text immediately after tool call.
SYSTEM STATE GUARDRAIL: When analyzing command observations in <observation>...</observation>, parse and state the EXACT numbers and metrics from stdout (RAM Total, Used, Free, Swap, CPU %, Temperature). Never invent or guess metrics."""

    const val GEMMA_SYSTEM_DIRECTIVE = ATLAS_SYSTEM_DIRECTIVE

    const val QWEN_SYSTEM_DIRECTIVE = """You are ATLAS-AI, a state-of-the-art edge AI assistant engineered for high-performance, offline execution. You are highly intelligent, analytical, and concise. You provide professional, elite-level technical and general assistance.
CRITICAL MANDATE: You MUST always reply in the exact same language as the user's prompt. If the user writes in Arabic, you MUST respond in fluent, natural Arabic. Do not break character.

# Tools

You may call one or more functions to assist with the user query.
You are provided with function signatures within <tools></tools> XML tags:
<tools>
{"type": "function", "function": {"name": "bash", "description": "Execute shell or terminal command to inspect system state (RAM, CPU, storage, files)", "parameters": {"type": "object", "properties": {"command": {"type": "string", "description": "The exact shell command line"}}, "required": ["command"]}}}
{"type": "function", "function": {"name": "web_search", "description": "Search the web for real-time information", "parameters": {"type": "object", "properties": {"query": {"type": "string", "description": "The search keywords"}}, "required": ["query"]}}}
{"type": "function", "function": {"name": "generate_image", "description": "Synthesize or generate an authentic image from a text description", "parameters": {"type": "object", "properties": {"prompt": {"type": "string", "description": "Detailed visual description of the image to generate"}}, "required": ["prompt"]}}}
</tools>

For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:
<tool_call>
{"name": "<function-name>", "arguments": <args-json-object>}
</tool_call>

SYSTEM STATE GUARDRAIL: When analyzing command observations in <observation>...</observation>, parse and state the EXACT numbers and metrics from stdout (RAM Total, Used, Free, Swap, CPU %, Temperature). Never invent or guess metrics."""

    const val LLAMA3_SYSTEM_DIRECTIVE = """You are ATLAS-AI, a state-of-the-art edge AI assistant engineered for high-performance, offline execution. You are highly intelligent, analytical, and concise.
CRITICAL MANDATE: You MUST always reply in the exact same language as the user's prompt. If the user writes in Arabic, you MUST respond in fluent, natural Arabic. Do not break character.
When asked about real-time events, facts, or unknown entities, you MUST use the web search tool:
<tool_call>{"name": "web_search", "query": "your search keywords"}</tool_call>
When asked to run terminal/shell/bash commands or inspect system state, output exactly:
<tool_call>{"name": "bash", "command": "your command"}</tool_call>
When asked to generate an image, output:
<tool_call>{"name": "generate_image", "prompt": "visual description"}</tool_call>
Stop generating text immediately after tool call.
SYSTEM STATE GUARDRAIL: When analyzing command observations in <observation>...</observation>, parse and state the EXACT numbers and metrics from stdout (RAM Total, Used, Free, Swap, CPU %, Temperature). Never invent or guess metrics."""

    fun getSystemDirective(arch: Architecture, customDirective: String = ""): String {
        if (customDirective.isNotBlank() && customDirective != ATLAS_SYSTEM_DIRECTIVE) {
            return customDirective.trim()
        }
        return when (arch) {
            Architecture.GEMMA -> GEMMA_SYSTEM_DIRECTIVE
            Architecture.QWEN -> QWEN_SYSTEM_DIRECTIVE
            Architecture.LLAMA_3 -> LLAMA3_SYSTEM_DIRECTIVE
        }
    }

    val GEMMA_STOP_TOKENS = listOf(
        "<end_of_turn>",
        "<start_of_turn>",
        "<eos>",
        "<|end_of_turn|>"
    )

    val QWEN_STOP_TOKENS = listOf(
        "<|im_end|>",
        "<|endoftext|>",
        "<|im_start|>"
    )

    val LLAMA_3_STOP_TOKENS = listOf(
        "<|eot_id|>",
        "<|end_of_text|>",
        "<|start_header_id|>"
    )

    fun getStopTokens(arch: Architecture = Architecture.GEMMA): List<String> = when (arch) {
        Architecture.GEMMA -> GEMMA_STOP_TOKENS
        Architecture.QWEN -> QWEN_STOP_TOKENS
        Architecture.LLAMA_3 -> LLAMA_3_STOP_TOKENS
    }

    fun getServerTemplateArg(arch: Architecture = Architecture.GEMMA): String = arch.templateName

    /**
     * Extracts architecture, context length, and metadata directly from the GGUF binary header.
     * Falls back seamlessly to filename heuristic if binary parsing fails or is non-standard.
     */
    fun extractMetadata(file: File): ModelMetadata {
        val fileName = file.nameWithoutExtension
        var detectedArch = detectArchitecture(fileName = file.name)
        var archName = detectedArch.tag
        var chatTemplate: String? = detectedArch.templateName
        var ctxLength = 4096
        var blockCount: Int? = null

        try {
            if (file.exists() && file.length() > 24) {
                RandomAccessFile(file, "r").use { raf ->
                    val magic = ByteArray(4)
                    raf.readFully(magic)
                    // Check 'GGUF' magic: 0x47, 0x47, 0x55, 0x46
                    if (magic[0] == 0x47.toByte() && magic[1] == 0x47.toByte() &&
                        magic[2] == 0x55.toByte() && magic[3] == 0x46.toByte()
                    ) {
                        val version = readLittleEndianInt(raf)
                        val tensorCount = readLittleEndianLong(raf)
                        val kvCount = readLittleEndianLong(raf)

                        val maxScan = minOf(kvCount, 150L)
                        var readEntries = 0L

                        while (readEntries < maxScan && raf.filePointer < raf.length() - 8) {
                            readEntries++
                            val keyLen = readLittleEndianLong(raf)
                            if (keyLen <= 0 || keyLen > 2048) break
                            val keyBytes = ByteArray(keyLen.toInt())
                            raf.readFully(keyBytes)
                            val key = String(keyBytes, Charsets.UTF_8)

                            val valType = readLittleEndianInt(raf)
                            when (valType) {
                                8 -> { // STRING
                                    val strLen = readLittleEndianLong(raf)
                                    if (strLen in 0..65536) {
                                        val strBytes = ByteArray(strLen.toInt())
                                        raf.readFully(strBytes)
                                        val strVal = String(strBytes, Charsets.UTF_8)
                                        if (key == "general.architecture") {
                                            archName = strVal.lowercase()
                                            detectedArch = detectArchitecture(rawArch = archName, fileName = file.name)
                                            chatTemplate = detectedArch.templateName
                                        } else if (key == "tokenizer.chat_template") {
                                            chatTemplate = strVal
                                        }
                                    } else {
                                        break
                                    }
                                }
                                4 -> { // UINT32
                                    val u32Val = readLittleEndianInt(raf)
                                    if (key.endsWith(".context_length")) {
                                        ctxLength = u32Val
                                    } else if (key.endsWith(".block_count") && u32Val in 1..512) {
                                        blockCount = u32Val
                                    }
                                }
                                10 -> { // UINT64
                                    val u64Val = readLittleEndianLong(raf)
                                    if (key.endsWith(".context_length")) {
                                        ctxLength = u64Val.toInt()
                                    }
                                }
                                0, 1, 7 -> raf.skipBytes(1)
                                2, 3 -> raf.skipBytes(2)
                                5, 6 -> raf.skipBytes(4)
                                11, 12 -> raf.skipBytes(8)
                                9 -> { // ARRAY
                                    val itemType = readLittleEndianInt(raf)
                                    val arrayLen = readLittleEndianLong(raf)
                                    if (!skipGgufArray(raf, itemType, arrayLen)) break
                                }
                                else -> break
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GGUF binary header parse notice (${e.message}), utilizing filename heuristics.")
        }

        Log.i(TAG, "⚡ Dynamic Model Adapter: Detected ${detectedArch.name} ($archName) for ${file.name}")
        return ModelMetadata(
            architecture = detectedArch,
            archName = archName,
            modelName = fileName,
            chatTemplate = chatTemplate ?: detectedArch.templateName,
            contextLength = ctxLength.coerceIn(2048, 8192),
            blockCount = blockCount
        )
    }

    private fun readLittleEndianInt(raf: RandomAccessFile): Int {
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        val b4 = raf.read()
        if ((b1 or b2 or b3 or b4) < 0) throw java.io.EOFException()
        return (b1 and 0xFF) or ((b2 and 0xFF) shl 8) or ((b3 and 0xFF) shl 16) or ((b4 and 0xFF) shl 24)
    }

    private fun readLittleEndianLong(raf: RandomAccessFile): Long {
        val low = readLittleEndianInt(raf).toLong() and 0xFFFFFFFFL
        val high = readLittleEndianInt(raf).toLong() and 0xFFFFFFFFL
        return low or (high shl 32)
    }

    private fun skipGgufArray(raf: RandomAccessFile, itemType: Int, count: Long): Boolean {
        if (count < 0 || count > 50000) return false
        val itemSize = when (itemType) {
            0, 1, 7 -> 1
            2, 3 -> 2
            4, 5, 6 -> 4
            10, 11, 12 -> 8
            8 -> -1
            else -> return false
        }
        return if (itemSize > 0) {
            raf.skipBytes((itemSize * count).toInt())
            true
        } else if (itemType == 8) {
            for (i in 0 until count) {
                val len = readLittleEndianLong(raf)
                if (len < 0 || len > 65536) return false
                raf.skipBytes(len.toInt())
            }
            true
        } else {
            false
        }
    }

    fun detectArchitecture(
        rawArch: String = "",
        modelName: String = "",
        template: String? = null,
        fileName: String = ""
    ): Architecture {
        val s = (rawArch + " " + modelName + " " + fileName + " " + (template ?: "")).lowercase()
        return when {
            s.contains("qwen") -> Architecture.QWEN
            s.contains("llama") || s.contains("alpaca") || s.contains("vicuna") -> Architecture.LLAMA_3
            s.contains("gemma") -> Architecture.GEMMA
            else -> Architecture.GEMMA
        }
    }

    /**
     * Dynamic Prompt Constructor:
     * - Gemma: Folds directive into turn 1's user block (<start_of_turn>user\n[SYSTEM DIRECTIVE]...\n[USER]: ...<end_of_turn>)
     * - Qwen: Native ChatML (<|im_start|>system\n...<|im_end|>\n<|im_start|>user\n...<|im_end|>\n<|im_start|>assistant\n)
     * - Llama-3: Native Llama-3 header format (<|start_header_id|>system<|end_header_id|>\n\n...<|eot_id|>)
     */
    fun formatPrompt(
        history: List<Pair<String, String>>,
        systemDirective: String = "",
        arch: Architecture = Architecture.GEMMA,
        addAssistantPrompt: Boolean = true
    ): String {
        val directive = getSystemDirective(arch, systemDirective)
        return when (arch) {
            Architecture.GEMMA -> formatGemmaPrompt(history, directive, addAssistantPrompt)
            Architecture.QWEN -> formatQwenPrompt(history, directive, addAssistantPrompt)
            Architecture.LLAMA_3 -> formatLlama3Prompt(history, directive, addAssistantPrompt)
        }
    }

    private fun formatGemmaPrompt(
        history: List<Pair<String, String>>,
        directive: String,
        addAssistantPrompt: Boolean
    ): String {
        val sb = StringBuilder()
        var firstUserProcessed = false

        for ((role, content) in history) {
            val isUser = role.equals("user", ignoreCase = true)
            if (isUser) {
                sb.append("<start_of_turn>user\n")
                if (!firstUserProcessed) {
                    sb.append(directive.trim()).append("\n")
                    sb.append("[USER]: ").append(content.trim()).append("<end_of_turn>\n")
                    firstUserProcessed = true
                } else {
                    sb.append(content.trim()).append("<end_of_turn>\n")
                }
            } else {
                sb.append("<start_of_turn>model\n")
                    .append(content.trim())
                    .append("<end_of_turn>\n")
            }
        }

        if (!firstUserProcessed) {
            sb.append("<start_of_turn>user\n")
                .append(directive.trim()).append("\n")
                .append("[USER]: Hello<end_of_turn>\n")
        }

        if (addAssistantPrompt) {
            sb.append("<start_of_turn>model\n")
        }

        return sb.toString()
    }

    private fun formatQwenPrompt(
        history: List<Pair<String, String>>,
        directive: String,
        addAssistantPrompt: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
            .append(directive.trim())
            .append("<|im_end|>\n")

        for ((role, content) in history) {
            val isUser = role.equals("user", ignoreCase = true)
            val tag = if (isUser) "user" else "assistant"
            sb.append("<|im_start|>").append(tag).append("\n")
                .append(content.trim())
                .append("<|im_end|>\n")
        }

        if (addAssistantPrompt) {
            sb.append("<|im_start|>assistant\n")
        }

        return sb.toString()
    }

    private fun formatLlama3Prompt(
        history: List<Pair<String, String>>,
        directive: String,
        addAssistantPrompt: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("<|begin_of_text|>")
        sb.append("<|start_header_id|>system<|end_header_id|>\n\n")
            .append(directive.trim())
            .append("<|eot_id|>")

        for ((role, content) in history) {
            val isUser = role.equals("user", ignoreCase = true)
            val tag = if (isUser) "user" else "assistant"
            sb.append("<|start_header_id|>").append(tag).append("<|end_header_id|>\n\n")
                .append(content.trim())
                .append("<|eot_id|>")
        }

        if (addAssistantPrompt) {
            sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }

        return sb.toString()
    }

    /**
     * Formats multi-turn ReAct observation turn to resume generation after tool execution.
     */
    fun formatObservationTurn(arch: Architecture, observationMsg: String): String = when (arch) {
        Architecture.GEMMA -> "<end_of_turn>\n<start_of_turn>user\n$observationMsg<end_of_turn>\n<start_of_turn>model\n"
        Architecture.QWEN -> "<|im_end|>\n<|im_start|>user\n$observationMsg<|im_end|>\n<|im_start|>assistant\n"
        Architecture.LLAMA_3 -> "<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n$observationMsg<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
    }

    /**
     * Formats stream extension continuation turn when token budget extends.
     */
    fun formatContinuationTurn(arch: Architecture, continuationPayload: String): String = when (arch) {
        Architecture.GEMMA -> "<end_of_turn>\n<start_of_turn>user\n$continuationPayload<end_of_turn>\n<start_of_turn>model\n"
        Architecture.QWEN -> "<|im_end|>\n<|im_start|>user\n$continuationPayload<|im_end|>\n<|im_start|>assistant\n"
        Architecture.LLAMA_3 -> "<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n$continuationPayload<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
    }

    /**
     * Formats direct observation delta for inline KV-cache continuation.
     */
    fun formatObservationDelta(arch: Architecture, output: String): String = when (arch) {
        Architecture.GEMMA -> "\n<observation>\n$output\n</observation>\nAssistant: "
        Architecture.QWEN -> "\n<observation>\n$output\n</observation>\n<|im_start|>assistant\n"
        Architecture.LLAMA_3 -> "\n<observation>\n$output\n</observation>\n<|start_header_id|>assistant<|end_header_id|>\n\n"
    }

    // Compatibility overload for callers expecting (arch, systemPrompt, history, ...)
    fun formatPrompt(
        arch: Architecture,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        addAssistantPrompt: Boolean = true,
        nativeTemplate: String? = null
    ): String {
        return formatPrompt(
            history = history,
            systemDirective = systemPrompt,
            arch = arch,
            addAssistantPrompt = addAssistantPrompt
        )
    }
}

package com.synapse.localai

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.FunctionCallPart
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.defineFunction
import com.google.ai.client.generativeai.type.generationConfig
import com.google.ai.client.generativeai.type.Schema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlin.random.Random

class GeminiModelClient(
    private val apiKeys: List<String>,
    initialKeyIndex: Int = 0,
    private val onKeyRotated: ((Int) -> Unit)? = null,
    private val onKeyUsed: ((String, Long) -> Unit)? = null,
    private val getKeyUsage: ((String) -> Long)? = null,
    private val onKeyValidated: ((Int) -> Unit)? = null,
    private val onModelAutoSwitched: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "GeminiClient"
    }

    private var currentKeyIndex = initialKeyIndex.coerceIn(0, (apiKeys.size - 1).coerceAtLeast(0))
    private var nextKeyIndex = if (apiKeys.isEmpty()) 0 else (currentKeyIndex + 1) % apiKeys.size
    private val keyCooldowns = ConcurrentHashMap<Int, Long>()
    private val keyFailureCounts = ConcurrentHashMap<Int, Int>()
    private val inFlightByKey = ConcurrentHashMap<Int, Int>()

    var modelName: String = AgentViewModel.DEFAULT_GEMINI_MODEL
        set(value) {
            val sanitized = AgentViewModel.sanitizeGeminiModelName(value)
            if (field != sanitized) {
                field = sanitized
                keyCooldowns.clear()
                keyFailureCounts.clear()
                activeChat = null
                lastFunctionCall = null
                fileLog("Model changed to '$sanitized'. Cleared key cooldowns and chat state.")
            }
        }
    var maxTokens: Int = 8192
    var temperature: Float = 0.30f
    var isLoaded: Boolean = apiKeys.isNotEmpty()

    @Volatile var lastFinishReason: String? = null
    @Volatile var lastTokenCount: Int = 0
    @Volatile var activeChat: com.google.ai.client.generativeai.Chat? = null
    @Volatile var lastFunctionCall: FunctionCallPart? = null

    private fun fileLog(msg: String) {
        Log.d(TAG, msg)
        try {
            val dir = File("/sdcard/atlas-ai")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "gguf_log.txt").appendText("[${Date()}] [GEMINI] $msg\n")
        } catch (_: Exception) {}
    }

    private fun markKeyDegraded(keyIdx: Int, isQuota: Boolean, errorMsg: String) {
        val failures = (keyFailureCounts[keyIdx] ?: 0) + 1
        keyFailureCounts[keyIdx] = failures

        // زيادة مدة الـ Cooldown تصاعدياً حسب عدد الفشلات المتكررة
        val baseCooldown = if (isQuota) 60_000L else 20_000L
        val multiplier = 1.5.pow(failures.coerceAtMost(4).toDouble())
        val cooldownDuration = (baseCooldown * multiplier).toLong()
        val cooldownUntil = System.currentTimeMillis() + cooldownDuration
        keyCooldowns[keyIdx] = cooldownUntil

        val apiKey = apiKeys.getOrNull(keyIdx) ?: ""
        KeyUsageManager.markCooldown(apiKey, cooldownDuration)
        fileLog("Key #${keyIdx + 1}/${apiKeys.size} marked ${if (isQuota) "RATE-LIMITED (429)" else "DEGRADED/COOLDOWN"}. Cooldown ${cooldownDuration / 1000}s (Failures: $failures). Error: $errorMsg")
    }

    private fun markKeySuccess(keyIdx: Int, tokensUsed: Long) {
        keyFailureCounts[keyIdx] = 0
        onKeyValidated?.invoke(keyIdx)
        apiKeys.getOrNull(keyIdx)?.let { onKeyUsed?.invoke(it, tokensUsed.coerceAtLeast(1L)) }
    }

    @Synchronized
    private fun getNextValidKey(): Pair<String, Int>? {
        if (apiKeys.isEmpty()) return null
        val totalKeys = apiKeys.size
        val now = System.currentTimeMillis()

        keyCooldowns.entries.removeIf { it.value <= now }

        val availableKeys = (0 until totalKeys)
            .map { offset -> (nextKeyIndex + offset) % totalKeys }
            .filter { (keyCooldowns[it] ?: 0L) <= now }
        val idleKeys = availableKeys.filter { (inFlightByKey[it] ?: 0) == 0 }
        val candidates = idleKeys.ifEmpty { availableKeys }
        val selectedKey = candidates.minByOrNull { idx ->
            getKeyUsage?.invoke(apiKeys[idx]) ?: 0L
        } ?: return null

        currentKeyIndex = selectedKey
        nextKeyIndex = (selectedKey + 1) % totalKeys
        inFlightByKey[selectedKey] = (inFlightByKey[selectedKey] ?: 0) + 1
        onKeyRotated?.invoke(currentKeyIndex)
        fileLog("Rotated to key #${currentKeyIndex + 1}/$totalKeys")
        return apiKeys[currentKeyIndex] to currentKeyIndex
    }

    @Synchronized
    private fun releaseKey(keyIdx: Int) {
        val remaining = (inFlightByKey[keyIdx] ?: 1) - 1
        if (remaining > 0) inFlightByKey[keyIdx] = remaining else inFlightByKey.remove(keyIdx)
    }

    private fun getCooldownWaitMs(): Long {
        val earliestCooldown = keyCooldowns.values.minOrNull() ?: return 1_000L
        return (earliestCooldown - System.currentTimeMillis()).coerceAtLeast(250L)
    }

    init {
        if (apiKeys.isNotEmpty()) {
            onKeyRotated?.invoke(currentKeyIndex)
        }
    }

    suspend fun generateStream(
        prompt: String,
        systemPrompt: String = ""
    ): Flow<String> = flow {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        generateStreamWithOllamaMessages(messages, systemPrompt).collect { emit(it) }
    }

    suspend fun generateStreamWithOllamaMessages(
        messages: JSONArray,
        systemPrompt: String = "",
        onRetry: (() -> Unit)? = null
    ): Flow<String> = flow {
        val chatHistory = mutableListOf<Content>()
        var promptText = ""
        val promptImages = mutableListOf<android.graphics.Bitmap>()

        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val role = msg.optString("role", "user")
            val contentStr = msg.optString("content", "")
            val imagesArray = msg.optJSONArray("images")
            val geminiRole = if (role == "user") "user" else "model"

            if (i == messages.length() - 1 && role == "user") {
                promptText = contentStr
                if (imagesArray != null) {
                    for (j in 0 until imagesArray.length()) {
                        try {
                            val base64Str = imagesArray.getString(j)
                            val decodedString = Base64.decode(base64Str, Base64.DEFAULT)
                            val decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                            promptImages.add(decodedByte)
                        } catch (e: Exception) {
                            fileLog("Failed to decode image: ${e.message}")
                        }
                    }
                }
            } else {
                chatHistory.add(content(role = geminiRole) {
                    text(contentStr)
                    if (imagesArray != null) {
                        for (j in 0 until imagesArray.length()) {
                            try {
                                val base64Str = imagesArray.getString(j)
                                val decodedString = Base64.decode(base64Str, Base64.DEFAULT)
                                val decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                                image(decodedByte)
                            } catch (e: Exception) {
                                fileLog("Failed to decode history image: ${e.message}")
                            }
                        }
                    }
                })
            }
        }

        executeResilientInference(
            chatHistory = chatHistory,
            promptText = promptText,
            promptImages = promptImages,
            systemPrompt = systemPrompt,
            onRetry = onRetry
        ).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    suspend fun generateStreamWithHistory(
        history: List<Pair<String, String>>,
        systemPrompt: String = "",
        onRetry: (() -> Unit)? = null
    ): Flow<String> = flow {
        val chatHistory = mutableListOf<Content>()
        for (msg in history) {
            val contentStr = msg.second.trim()
            if (contentStr.isBlank()) continue
            val role = if (msg.first == "user") "user" else "model"
            chatHistory.add(content(role = role) { text(contentStr) })
        }

        val lastUserMsg = if (chatHistory.isNotEmpty()) chatHistory.removeAt(chatHistory.size - 1) else null
        val promptText = lastUserMsg?.parts?.filterIsInstance<com.google.ai.client.generativeai.type.TextPart>()
            ?.joinToString(" ") { it.text } ?: ""

        executeResilientInference(
            chatHistory = chatHistory,
            promptText = promptText,
            promptImages = emptyList(),
            systemPrompt = systemPrompt,
            onRetry = onRetry
        ).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    /**
     * Unified Core Inference Engine مع معالجة حماية الضغط الذكية وحلقة المحاولات مع Exponential Backoff.
     */
    private fun executeResilientInference(
        chatHistory: List<Content>,
        promptText: String,
        promptImages: List<android.graphics.Bitmap>,
        systemPrompt: String,
        onRetry: (() -> Unit)?
    ): Flow<String> = flow {
        val totalKeys = apiKeys.size
        if (totalKeys == 0) throw IllegalStateException("No Gemini API keys are configured.")
        var attempt = 0
        var success = false
        var lastErrorDetail = ""
        var retryHistory = chatHistory.toMutableList()
        var retryPrompt = promptText
        var retryImages = promptImages
        var hasEmittedAnyOutput = false
        var currentPair = getNextValidKey()

        while (!success) {
            if (currentPair == null) {
                delay(getCooldownWaitMs())
                currentPair = getNextValidKey()
                continue
            }
            val (apiKey, keyIdx) = currentPair
            val attemptOutput = StringBuilder()
            val attemptTokenUsage = AtomicLong(0L)

            val channel = Channel<String>(Channel.BUFFERED)
            val streamScope = CoroutineScope(coroutineContext + Job())
            val streamJob = streamScope.launch {
                try {
                    val effectiveModelName = AgentViewModel.sanitizeGeminiModelName(modelName)

                    val generativeModel = GenerativeModel(
                        modelName = effectiveModelName,
                        apiKey = apiKey,
                        generationConfig = generationConfig {
                            this.temperature = this@GeminiModelClient.temperature
                            this.maxOutputTokens = this@GeminiModelClient.maxTokens
                        },
                        safetySettings = listOf(
                            SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                            SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                            SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                            SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE)
                        ),
                        tools = getAgentTools(),
                        systemInstruction = content { text(systemPrompt) }
                    )

                    val chat = generativeModel.startChat(retryHistory)
                    activeChat = chat
                    val responseFlow = chat.sendMessageStream(content(role = "user") {
                        text(retryPrompt)
                        for (img in retryImages) {
                            image(img)
                        }
                    })

                    lastFinishReason = null
                    lastTokenCount = 0

                    var functionCallDetected = false
                    responseFlow.collect { chunk ->
                        if (functionCallDetected) return@collect
                        val candidate = chunk.candidates.firstOrNull()
                        val reason = candidate?.finishReason?.name
                        if (!reason.isNullOrBlank()) lastFinishReason = reason

                        val candTokens = chunk.usageMetadata?.candidatesTokenCount ?: 0
                        if (candTokens > 0) lastTokenCount = candTokens
                        val totalTokens = chunk.usageMetadata?.totalTokenCount ?: 0
                        if (totalTokens > 0) {
                            attemptTokenUsage.updateAndGet { current -> maxOf(current, totalTokens.toLong()) }
                        }

                        val fns = chunk.functionCalls
                        if (fns.isNotEmpty()) {
                            val call = fns.first()
                            lastFunctionCall = call
                            val paramsXml = if (call.args.isNotEmpty()) {
                                call.args.entries.joinToString("\n") { (k, v) ->
                                    "<parameter name=\"$k\">$v</parameter>"
                                }
                            } else ""
                            fileLog("Gemini native FunctionCall intercepted: ${call.name}")
                            val toolXml = "<invoke name=\"${call.name}\">\n$paramsXml\n</invoke>"
                            channel.send(toolXml)
                            channel.close()
                            functionCallDetected = true
                            return@collect
                        }
                        if (isActive && chunk.text != null) {
                            channel.send(chunk.text!!)
                        }
                    }
                    if (lastFinishReason == "MAX_TOKENS" && lastTokenCount == 0) {
                        lastTokenCount = maxTokens
                    }
                    channel.close()
                } catch (t: Throwable) {
                    val errorStr = t.message ?: t.toString()
                    val isMaxTokens = t is com.google.ai.client.generativeai.type.ResponseStoppedException ||
                            errorStr.contains("MAX_TOKENS", ignoreCase = true)
                    if (isMaxTokens) {
                        lastFinishReason = "MAX_TOKENS"
                        if (lastTokenCount == 0) lastTokenCount = maxTokens
                        try {
                            if (t is com.google.ai.client.generativeai.type.ResponseStoppedException) {
                                val remainingText = t.response?.text
                                if (!remainingText.isNullOrEmpty()) {
                                    channel.send(remainingText)
                                }
                            }
                        } catch (_: Exception) {}
                        channel.close()
                    } else {
                        channel.close(t)
                    }
                }
            }

            try {
                // مهلة 25 ثانية لاستقبال الحزمة الأولى لمنع التجمد
                val firstChunk = withTimeoutOrNull(25_000L) {
                    val res = channel.receiveCatching()
                    if (res.isClosed) {
                        val cause = res.exceptionOrNull()
                        if (cause != null) throw cause
                        null
                    } else {
                        res.getOrNull()
                    }
                }

                if (firstChunk == null) {
                    if (!channel.isClosedForReceive) {
                        streamJob.cancel()
                        throw java.net.SocketTimeoutException("Key #${keyIdx + 1}/$totalKeys timeout: zero response chunks received")
                    } else {
                        throw IllegalStateException("Key #${keyIdx + 1}/$totalKeys returned empty stream")
                    }
                }

                attemptOutput.append(firstChunk)
                emit(firstChunk)
                hasEmittedAnyOutput = true

                for (chunk in channel) {
                    if (coroutineContext.isActive) {
                        attemptOutput.append(chunk)
                        emit(chunk)
                        hasEmittedAnyOutput = true
                    }
                }

                markKeySuccess(keyIdx, attemptTokenUsage.get())
                releaseKey(keyIdx)
                success = true
                fileLog("Inference successful with Key #${keyIdx + 1}/$totalKeys")

            } catch (t: Throwable) {
                streamJob.cancelAndJoin()
                releaseKey(keyIdx)
                val consumedTokens = attemptTokenUsage.get()
                if (consumedTokens > 0L) onKeyUsed?.invoke(apiKey, consumedTokens)
                if (t is kotlinx.coroutines.CancellationException ||
                    t.javaClass.simpleName == "SynchronousToolBarrierException" ||
                    t.message?.contains("Synchronous ReAct Barrier") == true
                ) {
                    throw t
                }

                val errorMsg = t.message ?: t.toString()
                val lowerError = errorMsg.lowercase()
                val targetModel = AgentViewModel.sanitizeGeminiModelName(modelName)

                fileLog("Key #${keyIdx + 1}/$totalKeys Error: $errorMsg")

                // الأخطاء الدائمة: 404
                val isNotFound = lowerError.contains("404") || lowerError.contains("not found")
                if (isNotFound) {
                    throw RuntimeException("Gemini Model Error 404: Model '$targetModel' not found.")
                }

                val isBadRequest = lowerError.contains("400") || lowerError.contains("bad request")
                if (isBadRequest) {
                    throw RuntimeException("Gemini API Error 400: $errorMsg")
                }

                // تحديد أخطاء الضغط والكوتا
                val isHighDemand503 = lowerError.contains("503") || lowerError.contains("high demand") ||
                        lowerError.contains("overloaded") || lowerError.contains("unavailable")
                val isQuotaExhausted = lowerError.contains("429") || lowerError.contains("quota") || lowerError.contains("resource_exhausted")
                val isTimeoutOrServer = t is java.net.SocketTimeoutException || lowerError.contains("timeout") ||
                        lowerError.contains("500") || lowerError.contains("502") || lowerError.contains("504")

                lastErrorDetail = errorMsg
                markKeyDegraded(keyIdx, isQuota = isQuotaExhausted, errorMsg = errorMsg)

                val shouldRetry = isQuotaExhausted || isHighDemand503 || isTimeoutOrServer ||
                        lowerError.contains("limit") || lowerError.contains("empty stream") ||
                        t is java.io.IOException

                if (shouldRetry) {
                    if (attempt < Int.MAX_VALUE) attempt++
                    // حساب مدة الانتظار التصاعدية (Exponential Backoff + Jitter) عند الضغط
                    val backoffDelay = (250L * 2.0.pow(attempt.coerceAtMost(8).toDouble())).toLong()
                        .coerceAtMost(60_000L) + Random.nextLong(100L, 300L)
                    fileLog("Key #${keyIdx + 1} failed (retry $attempt). Rotating in ${backoffDelay}ms...")
                    if (!hasEmittedAnyOutput) {
                        onRetry?.invoke()
                    } else {
                        retryHistory.add(content(role = "user") {
                            text(retryPrompt)
                            for (img in retryImages) image(img)
                        })
                        retryHistory.add(content(role = "model") { text(attemptOutput.toString()) })
                        retryPrompt = "Continue your previous response from exactly where it stopped. Do not repeat any text already written. Preserve the conversation, persona, requested format, and intent, and complete the response."
                        retryImages = emptyList()
                    }
                    delay(backoffDelay)
                    currentPair = getNextValidKey()
                } else {
                    throw RuntimeException("Gemini Error: $errorMsg")
                }
            }
        }

        if (!success) {
            throw RuntimeException("All API keys in pool failed or are in cooldown. Last error: $lastErrorDetail")
        }
    }

    suspend fun sendFunctionResponseStream(
        name: String,
        sanitizedOutput: String,
        exitCode: Int,
        fallbackMessages: JSONArray? = null,
        systemPrompt: String = ""
    ): Flow<String> = flow {
        var collectedAnyText = false
        val chat = activeChat

        if (chat != null) {
            try {
                val historyList = chat.history as? MutableList<Content>
                if (historyList != null) {
                    val lastItem = historyList.lastOrNull()
                    val hasFc = lastItem?.parts?.any { it is FunctionCallPart } == true
                    if (!hasFc) {
                        val argsMap = lastFunctionCall?.args ?: mapOf("command" to "")
                        val fnCallPart = FunctionCallPart(name, argsMap)
                        if (lastItem != null && lastItem.role == "model") {
                            val existingParts = lastItem.parts.filter { (it as? com.google.ai.client.generativeai.type.TextPart)?.text?.isNotBlank() ?: true }
                            val newParts = if (existingParts.isEmpty()) listOf(fnCallPart) else existingParts + fnCallPart
                            historyList[historyList.size - 1] = Content(role = "model", parts = newParts)
                        } else {
                            historyList.add(Content(role = "model", parts = listOf(fnCallPart)))
                        }
                    }
                }

                val toolResponsePart = Part.fromFunctionResponse(name, mapOf("output" to sanitizedOutput, "exit_code" to exitCode))
                val toolResponseContent = Content(role = "function", parts = listOf(toolResponsePart))
                val responseFlow = chat.sendMessageStream(toolResponseContent)

                lastFinishReason = null
                lastTokenCount = 0
                responseFlow.collect { chunk ->
                    val candidate = chunk.candidates.firstOrNull()
                    val reason = candidate?.finishReason?.name
                    if (!reason.isNullOrBlank()) lastFinishReason = reason

                    val candTokens = chunk.usageMetadata?.candidatesTokenCount ?: 0
                    if (candTokens > 0) lastTokenCount = candTokens

                    val text = chunk.text
                    if (!text.isNullOrEmpty()) {
                        collectedAnyText = true
                        emit(text)
                    }
                }
            } catch (t: Throwable) {
                fileLog("Native sendFunctionResponseStream failed: ${t.message}. Initiating multi-turn fallback.")
            }
        }

        if (!collectedAnyText && fallbackMessages != null) {
            generateStreamWithOllamaMessages(fallbackMessages, systemPrompt).collect { chunk ->
                emit(chunk)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun getAgentTools(): List<Tool> {
        return listOf(
            Tool(
                listOf(
                    defineFunction(
                        name = "run_shell",
                        description = "Execute a shell command with root or adb permissions",
                        parameters = listOf(Schema.str("command", "The bash shell command to execute")),
                        requiredParameters = listOf("command")
                    ),
                    defineFunction(
                        name = "open_app",
                        description = "Launch an Android application dynamically",
                        parameters = listOf(Schema.str("package_name", "Android package name or application name/alias")),
                        requiredParameters = listOf("package_name")
                    ),
                    defineFunction(
                        name = "click",
                        description = "Click an element by text, ID, or direct screen coordinates 'x,y'",
                        parameters = listOf(Schema.str("target", "Text, resource ID, or 'x,y' coordinates")),
                        requiredParameters = listOf("target")
                    ),
                    defineFunction(
                        name = "swipe",
                        description = "Perform a swipe or scroll gesture",
                        parameters = listOf(Schema.str("direction", "Direction ('up', 'down', 'left', 'right') or coordinates")),
                        requiredParameters = listOf("direction")
                    ),
                    defineFunction(
                        name = "input_text",
                        description = "Inject text into an editable element",
                        parameters = listOf(
                            Schema.str("text", "The text to type"),
                            Schema.str("target", "Optional target element text or ID")
                        ),
                        requiredParameters = listOf("text")
                    ),
                    defineFunction(
                        name = "capture_screen",
                        description = "Capture screen framebuffer buffer",
                        parameters = listOf(Schema.str("save_path", "Optional file path")),
                        requiredParameters = emptyList()
                    ),
                    defineFunction(
                        name = "ocr_screen",
                        description = "Inspect visual elements and text labels via ML Kit OCR",
                        parameters = listOf(Schema.str("image_path", "Optional image path")),
                        requiredParameters = emptyList()
                    ),
                    defineFunction(
                        name = "write_file",
                        description = "Create or overwrite a local file while saving a reversible before-image",
                        parameters = listOf(
                            Schema.str("path", "File path on disk"),
                            Schema.str("content", "Text content to write")
                        ),
                        requiredParameters = listOf("path", "content")
                    ),
                    defineFunction(
                        name = "rollback_file_changes",
                        description = "Restore files changed by a file-write transaction or task ID",
                        parameters = listOf(Schema.str("transaction_id", "Transaction ID returned by write_file, or background task ID")),
                        requiredParameters = listOf("transaction_id")
                    ),
                    defineFunction(
                        name = "read_file_range",
                        description = "Read chunk slices from large files safely",
                        parameters = listOf(
                            Schema.str("path", "Path of file to read"),
                            Schema.str("start_line", "Start line"),
                            Schema.str("line_count", "Number of lines to read")
                        ),
                        requiredParameters = listOf("path")
                    ),
                    defineFunction(
                        name = "web_search",
                        description = "Query live search engines for real-time facts",
                        parameters = listOf(Schema.str("query", "Search terms")),
                        requiredParameters = listOf("query")
                    ),
                    defineFunction(
                        name = "fetch_web_content",
                        description = "Extract clean text content from web URLs",
                        parameters = listOf(Schema.str("url", "Web page URL")),
                        requiredParameters = listOf("url")
                    ),
                    defineFunction(
                        name = "run_python",
                        description = "Execute local Python code for calculations",
                        parameters = listOf(Schema.str("script", "Python source code")),
                        requiredParameters = listOf("script")
                    ),
                    defineFunction(
                        name = "schedule_task",
                        description = "Register delayed execution with hardware alarm triggers",
                        parameters = listOf(
                            Schema.str("description", "Description of scheduled task"),
                            Schema.str("command", "Command to run"),
                            Schema.str("delay_minutes", "Delay in minutes")
                        ),
                        requiredParameters = listOf("description", "command", "delay_minutes")
                    ),
                    defineFunction(
                        name = "queue_background_task",
                        description = "Defer heavy workloads to asynchronous background threads",
                        parameters = listOf(Schema.str("command", "Workload command")),
                        requiredParameters = listOf("command")
                    ),
                    defineFunction(
                        name = "list_tasks",
                        description = "List active background tasks, including tasks waiting for user input",
                        parameters = emptyList(),
                        requiredParameters = emptyList()
                    ),
                    defineFunction(
                        name = "resume_task",
                        description = "Resume a background task waiting for user input, using the user's clarification",
                        parameters = listOf(
                            Schema.str("task_id", "Full ID of the task waiting for user input"),
                            Schema.str("user_input", "The user's answer or clarification")
                        ),
                        requiredParameters = listOf("task_id", "user_input")
                    ),
                    defineFunction(
                        name = "complete",
                        description = "Finalize task execution cycles",
                        parameters = listOf(
                            Schema.str("summary", "Structured summary of completed actions"),
                            Schema.str("task", "Name or description of task")
                        ),
                        requiredParameters = listOf("summary")
                    )
                )
            )
        )
    }

    fun stop() {}
    fun release() { isLoaded = false }
    fun close() { release() }
}

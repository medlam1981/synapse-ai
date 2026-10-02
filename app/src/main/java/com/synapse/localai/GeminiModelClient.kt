package com.synapse.localai

import android.util.Log
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.defineFunction
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.FunctionCallPart
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext

class GeminiModelClient(
    private val apiKeys: List<String>,
    initialKeyIndex: Int = 0,
    private val onKeyRotated: ((Int) -> Unit)? = null,
    private val onKeyUsed: ((String) -> Unit)? = null,
    private val onKeyValidated: ((Int) -> Unit)? = null,
    private val onModelAutoSwitched: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "GeminiClient"
    }

    private var currentKeyIndex = initialKeyIndex.coerceIn(0, (apiKeys.size - 1).coerceAtLeast(0))
    // Track cooldown timestamps for degraded or rate-limited keys
    private val keyCooldowns = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val keyFailureCounts = java.util.concurrent.ConcurrentHashMap<Int, Int>()

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
    var maxTokens: Int = 8192 // Maximized API ceiling to 8192 for unbounded output streaming
    var temperature: Float = 0.30f
    var isLoaded: Boolean = apiKeys.isNotEmpty()

    @Volatile var lastFinishReason: String? = null
    @Volatile var lastTokenCount: Int = 0
    @Volatile var activeChat: com.google.ai.client.generativeai.Chat? = null
    @Volatile var lastFunctionCall: FunctionCallPart? = null

    private fun fileLog(msg: String) {
        Log.d(TAG, msg)
        try {
            val dir = File("/sdcard/Medlam-Ai")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "gguf_log.txt").appendText("[${java.util.Date()}] [GEMINI] $msg\n")
        } catch (_: Exception) {}
    }

    private fun markKeyDegraded(keyIdx: Int, isQuota: Boolean, errorMsg: String) {
        val failures = (keyFailureCounts[keyIdx] ?: 0) + 1
        keyFailureCounts[keyIdx] = failures

        // Cooldown SLA: Quota (429) -> 60s cooldown; Timeout / Server 5xx -> 30s back-off
        val cooldownDuration = if (isQuota) 60_000L else 30_000L
        val cooldownUntil = System.currentTimeMillis() + cooldownDuration
        keyCooldowns[keyIdx] = cooldownUntil

        val apiKey = apiKeys.getOrNull(keyIdx) ?: ""
        if (isQuota) {
            onKeyUsed?.invoke(apiKey + "_EXHAUSTED")
        }
        fileLog("Key #${keyIdx + 1}/${apiKeys.size} marked ${if (isQuota) "EXHAUSTED (429)" else "DEGRADED"}. Cooldown ${cooldownDuration / 1000}s. Error: $errorMsg")
    }

    private fun markKeySuccess(keyIdx: Int) {
        keyFailureCounts[keyIdx] = 0
        keyCooldowns.remove(keyIdx)
        onKeyValidated?.invoke(keyIdx)
        apiKeys.getOrNull(keyIdx)?.let { onKeyUsed?.invoke(it) }
    }

    private fun getNextValidKey(forceRotate: Boolean): Pair<String, Int>? {
        if (apiKeys.isEmpty()) return null
        val totalKeys = apiKeys.size
        val now = System.currentTimeMillis()

        // Clean up expired cooldowns
        keyCooldowns.entries.removeIf { it.value <= now }

        // If ALL keys are cooling down, un-cooldown only the earliest degraded key for retry
        if (keyCooldowns.size >= totalKeys) {
            val oldestCooldownKey = keyCooldowns.minByOrNull { it.value }?.key
            if (oldestCooldownKey != null) {
                keyCooldowns.remove(oldestCooldownKey)
                fileLog("All keys in cooldown pool. Released earliest key #${oldestCooldownKey + 1}/$totalKeys for retry.")
            }
        }

        if (forceRotate) {
            // Strict circular round-robin sequence: N+1, N+2, ...
            for (i in 1..totalKeys) {
                val nextIdx = (currentKeyIndex + i) % totalKeys
                val cooldownUntil = keyCooldowns[nextIdx] ?: 0L
                if (now >= cooldownUntil) {
                    currentKeyIndex = nextIdx
                    onKeyRotated?.invoke(currentKeyIndex)
                    fileLog("Rotated to key #${currentKeyIndex + 1}/$totalKeys")
                    break
                }
            }
        } else {
            // Check if current key is valid; if cooling down, find next available
            val currentCooldown = keyCooldowns[currentKeyIndex] ?: 0L
            if (now < currentCooldown) {
                for (i in 1 until totalKeys) {
                    val nextIdx = (currentKeyIndex + i) % totalKeys
                    val cooldownUntil = keyCooldowns[nextIdx] ?: 0L
                    if (now >= cooldownUntil) {
                        currentKeyIndex = nextIdx
                        onKeyRotated?.invoke(currentKeyIndex)
                        fileLog("Current key in cooldown. Advanced to key #${currentKeyIndex + 1}/$totalKeys")
                        break
                    }
                }
            }
        }

        return apiKeys[currentKeyIndex] to currentKeyIndex
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
        Log.d(TAG, "Generating with Gemini (Ollama Vision Engine)...")

        val chatHistory = mutableListOf<com.google.ai.client.generativeai.type.Content>()
        var promptText = ""
        var promptImages = mutableListOf<android.graphics.Bitmap>()

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

        var attempt = 0
        var demandRetryCount = 0
        val totalKeys = apiKeys.size
        val maxAttempts = minOf(totalKeys, 6)
        var success = false
        var lastErrorDetail = ""
        var currentPair = getNextValidKey(forceRotate = false)

        while (attempt < maxAttempts && !success) {
            if (currentPair == null) {
                delay(5000L)
                currentPair = getNextValidKey(forceRotate = false)
                if (currentPair == null) {
                    attempt++
                    continue
                }
            }
            val (apiKey, keyIdx) = currentPair

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

                    val chat = generativeModel.startChat(chatHistory)
                    activeChat = chat
                    val responseFlow = chat.sendMessageStream(content(role = "user") {
                        text(promptText)
                        for (img in promptImages) {
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
                        if (!reason.isNullOrBlank()) {
                            lastFinishReason = reason
                        }
                        val candTokens = chunk.usageMetadata?.candidatesTokenCount ?: 0
                        if (candTokens > 0) {
                            lastTokenCount = candTokens
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
                            fileLog("Gemini native FunctionCall intercepted: ${call.name} (args: ${call.args})")
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
                        fileLog("Response stopped cleanly with MAX_TOKENS at stream level")
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
                // 30-Second timeout for initial connection & response chunk (Gemini thinking mode may take up to 15s to emit first token)
                val firstChunk = withTimeoutOrNull(30_000L) {
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
                        throw java.net.SocketTimeoutException("Key #${keyIdx + 1}/$totalKeys 30-second timeout: zero response chunks received within 30000ms")
                    } else {
                        throw IllegalStateException("Key #${keyIdx + 1}/$totalKeys returned empty response stream")
                    }
                }

                // First byte received: Validate key & persist immediately
                markKeySuccess(keyIdx)
                emit(firstChunk)

                // Stream the remainder of the response
                for (chunk in channel) {
                    if (coroutineContext.isActive) {
                        emit(chunk)
                    }
                }

                success = true
                fileLog("Inference successful with Key #${keyIdx + 1}/$totalKeys (FinishReason: $lastFinishReason)")

            } catch (t: Throwable) {
                streamJob.cancel()
                if (t is kotlinx.coroutines.CancellationException || t.javaClass.simpleName == "SynchronousToolBarrierException" || t.message?.contains("Synchronous ReAct Barrier") == true) {
                    throw t
                }

                val errorMsg = t.message ?: t.toString()
                val isMaxTokens = t is com.google.ai.client.generativeai.type.ResponseStoppedException ||
                                  errorMsg.contains("MAX_TOKENS", ignoreCase = true)
                if (isMaxTokens) {
                    lastFinishReason = "MAX_TOKENS"
                    if (lastTokenCount == 0) lastTokenCount = maxTokens
                    markKeySuccess(keyIdx)
                    success = true
                    fileLog("Inference reached MAX_TOKENS ceiling with Key #${keyIdx + 1}/$totalKeys (Graceful continuation trigger)")
                    break
                }

                lastErrorDetail = errorMsg
                val lowerError = errorMsg.lowercase()

                fileLog("Key #${keyIdx + 1}/$totalKeys [${t.javaClass.simpleName}] Error: $errorMsg")

                val targetModel = AgentViewModel.sanitizeGeminiModelName(modelName)
                val isNotFound = lowerError.contains("404") ||
                    lowerError.contains("not found") ||
                    lowerError.contains("not_found") ||
                    lowerError.contains("is not supported for generatecontent")

                if (isNotFound) {
                    fileLog("Gemini model '$targetModel' NOT FOUND (404) on Key #${keyIdx + 1}/$totalKeys — halting rotation immediately. Error: $errorMsg")
                    throw RuntimeException("Gemini Model Error 404: The model '$targetModel' was not found on Google Cloud. Please check the model name in Settings.")
                }

                val isBadRequest = lowerError.contains("400") ||
                    lowerError.contains("bad request") ||
                    lowerError.contains("invalid_argument") ||
                    lowerError.contains("invalid argument")

                // HTTP 400 Bad Request = protocol/payload error, NOT a key issue.
                // Rotating keys would cause an infinite loop. Throw immediately.
                if (isBadRequest) {
                    fileLog("HTTP 400 Bad Request on Key #${keyIdx + 1}/$totalKeys — halting rotation immediately. Error: $errorMsg")
                    throw RuntimeException("Gemini API Error 400 (Bad Request): $errorMsg")
                }

                val isHighDemand503 = lowerError.contains("503") ||
                    lowerError.contains("high demand") ||
                    lowerError.contains("spikes in demand") ||
                    lowerError.contains("overloaded") ||
                    (lowerError.contains("unavailable") && !lowerError.contains("not found")) ||
                    (lowerError.contains("missingfieldexception") && lowerError.contains("details"))

                if (isHighDemand503) {
                    if (demandRetryCount == 0) {
                        demandRetryCount++
                        fileLog("Model '$targetModel' experiencing high demand (503). Retrying once in 2000ms on Key #${keyIdx + 1}/$totalKeys...")
                        delay(2000L)
                        continue
                    } else {
                        fileLog("Model '$targetModel' 503 high demand persisted after retry. Halting key rotation to avoid futile loops.")
                        throw RuntimeException("Google Cloud 503: Model '$targetModel' is currently experiencing high demand on Google servers. Please retry in a few moments, or enter another model in Settings.")
                    }
                }

                val isQuotaExhausted = lowerError.contains("429") || lowerError.contains("quota") ||
                    lowerError.contains("exhausted") || lowerError.contains("resource_exhausted")

                val isTimeoutOrServer = t is java.net.SocketTimeoutException ||
                    lowerError.contains("timeout") ||
                    lowerError.contains("500") || lowerError.contains("502") || lowerError.contains("504") ||
                    (t.javaClass.simpleName.contains("ServerException") && !isNotFound && !isBadRequest)

                markKeyDegraded(keyIdx, isQuota = isQuotaExhausted, errorMsg = errorMsg)

                val shouldRetry = isQuotaExhausted || isTimeoutOrServer ||
                    lowerError.contains("limit") ||
                    lowerError.contains("deadline") ||
                    lowerError.contains("safety") ||
                    lowerError.contains("recitation") ||
                    lowerError.contains("serialization") ||
                    t is java.io.IOException ||
                    t.javaClass.simpleName.contains("SerializationException")

                if (shouldRetry && attempt + 1 < maxAttempts) {
                    attempt++
                    fileLog("Key #${keyIdx + 1}/$totalKeys failed ($attempt/$maxAttempts). Fast failover to next key in 250ms...")
                    Log.w(TAG, "Key #${keyIdx + 1}/$totalKeys failed ($attempt/$maxAttempts). Fast failover to next key in 250ms...")
                    onRetry?.invoke()

                    // Fast 250ms failover between keys:
                    delay(250L)

                    // Advance key and update UI after short failover delay
                    currentPair = getNextValidKey(forceRotate = true)
                } else if (lowerError.contains("location") || lowerError.contains("unsupported")) {
                    throw RuntimeException("Region Blocked: Gemini is not available in your region. Use a VPN.")
                } else {
                    throw RuntimeException("Gemini Error: $errorMsg")
                }
            }
        }

        if (!success) {
            throw RuntimeException("All tried API keys in pool failed or timed out. Last error: $lastErrorDetail")
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateStreamWithHistory(
        history: List<Pair<String, String>>,
        systemPrompt: String = "",
        onRetry: (() -> Unit)? = null
    ): Flow<String> = flow {
        Log.d(TAG, "Generating with Gemini (Resilient Engine)...")

        val chatHistory = mutableListOf<com.google.ai.client.generativeai.type.Content>()
        for (msg in history) {
            val content = msg.second.trim()
            if (content.isBlank()) continue
            val role = if (msg.first == "user") "user" else "model"
            chatHistory.add(content(role = role) { text(content) })
        }

        val lastUserMsg = chatHistory.removeAt(chatHistory.size - 1)
        val promptText = lastUserMsg.parts.filterIsInstance<com.google.ai.client.generativeai.type.TextPart>().joinToString(" ") { it.text }

        var attempt = 0
        var demandRetryCount = 0
        val totalKeys = apiKeys.size
        val maxAttempts = minOf(totalKeys, 6)
        var success = false
        var lastErrorDetail = ""
        var currentPair = getNextValidKey(forceRotate = false)

        while (attempt < maxAttempts && !success) {
            if (currentPair == null) {
                delay(5000L)
                currentPair = getNextValidKey(forceRotate = false)
                if (currentPair == null) {
                    attempt++
                    continue
                }
            }
            val (apiKey, keyIdx) = currentPair

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

                    val chat = generativeModel.startChat(chatHistory)
                    activeChat = chat
                    val responseFlow = chat.sendMessageStream(promptText)

                    lastFinishReason = null
                    lastTokenCount = 0

                    var functionCallDetected = false
                    responseFlow.collect { chunk ->
                        if (functionCallDetected) return@collect
                        val candidate = chunk.candidates.firstOrNull()
                        val reason = candidate?.finishReason?.name
                        if (!reason.isNullOrBlank()) {
                            lastFinishReason = reason
                        }
                        val candTokens = chunk.usageMetadata?.candidatesTokenCount ?: 0
                        if (candTokens > 0) {
                            lastTokenCount = candTokens
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
                            fileLog("Gemini native FunctionCall intercepted: ${call.name} (args: ${call.args})")
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
                        fileLog("Response stopped cleanly with MAX_TOKENS at stream level")
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
                // 30-Second timeout for initial connection & response chunk (Gemini thinking mode may take up to 15s to emit first token)
                val firstChunk = withTimeoutOrNull(30_000L) {
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
                        throw java.net.SocketTimeoutException("Key #${keyIdx + 1}/$totalKeys 30-second timeout: zero response chunks received within 30000ms")
                    } else {
                        throw IllegalStateException("Key #${keyIdx + 1}/$totalKeys returned empty response stream")
                    }
                }

                // First byte received: Validate key & persist immediately
                markKeySuccess(keyIdx)
                emit(firstChunk)

                // Stream the remainder of the response
                for (chunk in channel) {
                    if (coroutineContext.isActive) {
                        emit(chunk)
                    }
                }

                success = true
                fileLog("Inference successful with Key #${keyIdx + 1}/$totalKeys (FinishReason: $lastFinishReason)")

            } catch (t: Throwable) {
                streamJob.cancel()
                if (t is kotlinx.coroutines.CancellationException || t.javaClass.simpleName == "SynchronousToolBarrierException" || t.message?.contains("Synchronous ReAct Barrier") == true) {
                    throw t
                }

                val errorMsg = t.message ?: t.toString()
                val isMaxTokens = t is com.google.ai.client.generativeai.type.ResponseStoppedException ||
                                  errorMsg.contains("MAX_TOKENS", ignoreCase = true)
                if (isMaxTokens) {
                    lastFinishReason = "MAX_TOKENS"
                    if (lastTokenCount == 0) lastTokenCount = maxTokens
                    markKeySuccess(keyIdx)
                    success = true
                    fileLog("Inference reached MAX_TOKENS ceiling with Key #${keyIdx + 1}/$totalKeys (Graceful continuation trigger)")
                    break
                }

                lastErrorDetail = errorMsg
                val lowerError = errorMsg.lowercase()

                fileLog("Key #${keyIdx + 1}/$totalKeys [${t.javaClass.simpleName}] Error: $errorMsg")

                val targetModel = AgentViewModel.sanitizeGeminiModelName(modelName)
                val isNotFound = lowerError.contains("404") ||
                    lowerError.contains("not found") ||
                    lowerError.contains("not_found") ||
                    lowerError.contains("is not supported for generatecontent")

                if (isNotFound) {
                    fileLog("Gemini model '$targetModel' NOT FOUND (404) on Key #${keyIdx + 1}/$totalKeys — halting rotation immediately. Error: $errorMsg")
                    throw RuntimeException("Gemini Model Error 404: The model '$targetModel' was not found on Google Cloud. Please check the model name in Settings.")
                }

                val isBadRequest = lowerError.contains("400") ||
                    lowerError.contains("bad request") ||
                    lowerError.contains("invalid_argument") ||
                    lowerError.contains("invalid argument")

                // HTTP 400 Bad Request = protocol/payload error, NOT a key issue.
                // Rotating keys would cause an infinite loop. Throw immediately.
                if (isBadRequest) {
                    fileLog("HTTP 400 Bad Request on Key #${keyIdx + 1}/$totalKeys — halting rotation immediately. Error: $errorMsg")
                    throw RuntimeException("Gemini API Error 400 (Bad Request): $errorMsg")
                }

                val isHighDemand503 = lowerError.contains("503") ||
                    lowerError.contains("high demand") ||
                    lowerError.contains("spikes in demand") ||
                    lowerError.contains("overloaded") ||
                    (lowerError.contains("unavailable") && !lowerError.contains("not found")) ||
                    (lowerError.contains("missingfieldexception") && lowerError.contains("details"))

                if (isHighDemand503) {
                    if (demandRetryCount == 0) {
                        demandRetryCount++
                        fileLog("Model '$targetModel' experiencing high demand (503). Retrying once in 2000ms on Key #${keyIdx + 1}/$totalKeys...")
                        delay(2000L)
                        continue
                    } else {
                        fileLog("Model '$targetModel' 503 high demand persisted after retry. Halting key rotation to avoid futile loops.")
                        throw RuntimeException("Google Cloud 503: Model '$targetModel' is currently experiencing high demand on Google servers. Please retry in a few moments, or enter another model in Settings.")
                    }
                }

                val isQuotaExhausted = lowerError.contains("429") || lowerError.contains("quota") ||
                    lowerError.contains("exhausted") || lowerError.contains("resource_exhausted")

                val isTimeoutOrServer = t is java.net.SocketTimeoutException ||
                    lowerError.contains("timeout") ||
                    lowerError.contains("500") || lowerError.contains("502") || lowerError.contains("504") ||
                    (t.javaClass.simpleName.contains("ServerException") && !isNotFound && !isBadRequest)

                markKeyDegraded(keyIdx, isQuota = isQuotaExhausted, errorMsg = errorMsg)

                val shouldRetry = isQuotaExhausted || isTimeoutOrServer ||
                    lowerError.contains("limit") ||
                    lowerError.contains("deadline") ||
                    lowerError.contains("safety") ||
                    lowerError.contains("recitation") ||
                    lowerError.contains("serialization") ||
                    t is java.io.IOException ||
                    t.javaClass.simpleName.contains("SerializationException")

                if (shouldRetry && attempt + 1 < maxAttempts) {
                    attempt++
                    fileLog("Key #${keyIdx + 1}/$totalKeys failed ($attempt/$maxAttempts). Fast failover to next key in 250ms...")
                    Log.w(TAG, "Key #${keyIdx + 1}/$totalKeys failed ($attempt/$maxAttempts). Fast failover to next key in 250ms...")
                    onRetry?.invoke()

                    // Fast 250ms failover between keys:
                    delay(250L)

                    // Advance key and update UI after short failover delay
                    currentPair = getNextValidKey(forceRotate = true)
                } else if (lowerError.contains("location") || lowerError.contains("unsupported")) {
                    throw RuntimeException("Region Blocked: Gemini is not available in your region. Use a VPN.")
                } else {
                    throw RuntimeException("Gemini Error: $errorMsg")
                }
            }
        }

        if (!success) {
            throw RuntimeException("All tried API keys in pool failed or timed out. Last error: $lastErrorDetail")
        }
    }.flowOn(Dispatchers.IO)

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
                    if (!reason.isNullOrBlank()) {
                        lastFinishReason = reason
                    }
                    val candTokens = chunk.usageMetadata?.candidatesTokenCount ?: 0
                    if (candTokens > 0) {
                        lastTokenCount = candTokens
                    }
                    val text = chunk.text
                    if (!text.isNullOrEmpty()) {
                        collectedAnyText = true
                        emit(text)
                    }
                }
            } catch (t: Throwable) {
                fileLog("Native sendFunctionResponseStream failed: ${t.message}. Initiating multi-turn fallback.")
                Log.w(TAG, "Native sendFunctionResponseStream failed: ${t.message}. Initiating multi-turn fallback.", t)
            }
        }

        // If native SDK response failed or produced no output, stream via resilient multi-turn Ollama engine
        if (!collectedAnyText && fallbackMessages != null) {
            fileLog("Executing resilient second-turn synthesis via generateStreamWithOllamaMessages")
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
                        description = "Execute a shell command with root or adb permissions (e.g. to inspect system resources, or 'input keyevent 3' to return to the Home Screen after finishing app tasks)",
                        parameters = listOf(Schema.str("command", "The bash shell command to execute")),
                        requiredParameters = listOf("command")
                    ),
                    defineFunction(
                        name = "open_app",
                        description = "Launch an Android application dynamically using package name or app alias (e.g. 'youtube', 'whatsapp', 'settings', 'chrome')",
                        parameters = listOf(Schema.str("package_name", "Android package name or application name/alias")),
                        requiredParameters = listOf("package_name")
                    ),
                    defineFunction(
                        name = "read_screen_nodes",
                        description = "Read active screen UI node hierarchy (texts, IDs, bounds, clickable buttons). Must be parsed silently in your thought process without leaking raw XML or node dumps to the user.",
                        parameters = emptyList(),
                        requiredParameters = emptyList()
                    ),
                    defineFunction(
                        name = "click",
                        description = "Click an element by text, ID, or direct screen coordinates 'x,y'",
                        parameters = listOf(Schema.str("target", "Text, resource ID, or 'x,y' coordinates of the element")),
                        requiredParameters = listOf("target")
                    ),
                    defineFunction(
                        name = "swipe",
                        description = "Perform a swipe or scroll gesture ('up'/'اعلى', 'down'/'اسفل', 'left'/'يسار', 'right'/'يمين' or 'startX,startY,endX,endY')",
                        parameters = listOf(Schema.str("direction", "Direction ('up', 'down', 'left', 'right') or coordinate trajectory")),
                        requiredParameters = listOf("direction")
                    ),
                    defineFunction(
                        name = "input_text",
                        description = "Inject text into an editable element or currently active input field",
                        parameters = listOf(
                            Schema.str("text", "The text to type"),
                            Schema.str("target", "Optional target element text or ID")
                        ),
                        requiredParameters = listOf("text")
                    ),
                    defineFunction(
                        name = "capture_screen",
                        description = "Capture the active screen framebuffer buffer to a PNG image file",
                        parameters = listOf(Schema.str("save_path", "Optional file path to save screenshot")),
                        requiredParameters = emptyList()
                    ),
                    defineFunction(
                        name = "ocr_screen",
                        description = "Inspect visual elements, extract text labels, and map button coordinates via ML Kit OCR",
                        parameters = listOf(Schema.str("image_path", "Optional path to image file, or active screen if empty")),
                        requiredParameters = emptyList()
                    ),
                    defineFunction(
                        name = "write_file",
                        description = "Create or overwrite a local file atomically",
                        parameters = listOf(
                            Schema.str("path", "File path on disk"),
                            Schema.str("content", "Text content to write into file")
                        ),
                        requiredParameters = listOf("path", "content")
                    ),
                    defineFunction(
                        name = "read_file_range",
                        description = "Read chunk slices from large files safely without OOM",
                        parameters = listOf(
                            Schema.str("path", "Path of file to read"),
                            Schema.str("start_line", "Start line (1-indexed)"),
                            Schema.str("line_count", "Number of lines to read")
                        ),
                        requiredParameters = listOf("path")
                    ),
                    defineFunction(
                        name = "web_search",
                        description = "Query live search engines for real-time facts and documentation",
                        parameters = listOf(Schema.str("query", "Search terms or question")),
                        requiredParameters = listOf("query")
                    ),
                    defineFunction(
                        name = "fetch_web_content",
                        description = "Extract clean text content from web URLs",
                        parameters = listOf(Schema.str("url", "Web page URL to fetch")),
                        requiredParameters = listOf("url")
                    ),
                    defineFunction(
                        name = "run_python",
                        description = "Execute local Python code for advanced mathematics, algorithms, and computation",
                        parameters = listOf(Schema.str("script", "Python source code to execute")),
                        requiredParameters = listOf("script")
                    ),
                    defineFunction(
                        name = "schedule_task",
                        description = "Register delayed execution with hardware alarm triggers",
                        parameters = listOf(
                            Schema.str("description", "Description of scheduled task"),
                            Schema.str("command", "Command or action to run"),
                            Schema.str("delay_minutes", "Delay in minutes before triggering")
                        ),
                        requiredParameters = listOf("description", "command", "delay_minutes")
                    ),
                    defineFunction(
                        name = "queue_background_task",
                        description = "Defer heavy compute workloads to asynchronous background threads",
                        parameters = listOf(Schema.str("command", "Command or workload to run in background")),
                        requiredParameters = listOf("command")
                    ),
                    defineFunction(
                        name = "complete",
                        description = "Finalize task execution cycles, trigger Return-to-Home, and return direct output summary",
                        parameters = listOf(
                            Schema.str("summary", "Structured summary of completed actions"),
                            Schema.str("task", "Name or description of completed task")
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

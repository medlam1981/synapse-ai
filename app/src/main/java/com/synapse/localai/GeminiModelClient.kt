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
    private val onKeyValidated: ((Int) -> Unit)? = null
) {

    companion object {
        private const val TAG = "GeminiClient"
    }

    private var currentKeyIndex = initialKeyIndex.coerceIn(0, (apiKeys.size - 1).coerceAtLeast(0))
    // Track cooldown timestamps for degraded or rate-limited keys
    private val keyCooldowns = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val keyFailureCounts = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    var modelName: String = "gemini-1.5-flash"
    var maxTokens: Int = 2048 // Reduced from 8192 to 2048 to drastically cut TPM rate-limiter reservation and save quota
    var temperature: Float = 0.7f
    var isLoaded: Boolean = apiKeys.isNotEmpty()

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
                    val effectiveModelName = modelName.trim().removePrefix("models/").ifBlank { "gemini-2.0-flash" }

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
                        systemInstruction = content { text(systemPrompt) }
                    )

                    val chat = generativeModel.startChat(chatHistory)
                    val responseFlow = chat.sendMessageStream(content(role = "user") {
                        text(promptText)
                        for (img in promptImages) {
                            image(img)
                        }
                    })

                    responseFlow.collect { chunk ->
                        if (isActive && chunk.text != null) {
                            channel.send(chunk.text!!)
                        }
                    }
                    channel.close()
                } catch (t: Throwable) {
                    channel.close(t)
                }
            }

            try {
                // Strict 5-Second SLA Timeout Window for initial connection & response chunk
                val firstChunk = withTimeoutOrNull(5000L) {
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
                        throw java.net.SocketTimeoutException("Key #${keyIdx + 1}/$totalKeys 5-second SLA timeout: zero response chunks received within 5000ms")
                    } else {
                        throw IllegalStateException("Key #${keyIdx + 1}/$totalKeys returned empty response stream")
                    }
                }

                // First byte received within 5-second SLA: Validate key & persist immediately
                markKeySuccess(keyIdx)
                emit(firstChunk)

                // Stream the remainder of the response
                for (chunk in channel) {
                    if (coroutineContext.isActive) {
                        emit(chunk)
                    }
                }

                success = true
                fileLog("Inference successful with Key #${keyIdx + 1}/$totalKeys")

            } catch (t: Throwable) {
                streamJob.cancel()
                if (t is kotlinx.coroutines.CancellationException) {
                    throw t
                }

                val errorMsg = t.message ?: t.toString()
                lastErrorDetail = errorMsg
                val lowerError = errorMsg.lowercase()

                fileLog("Key #${keyIdx + 1}/$totalKeys [${t.javaClass.simpleName}] Error: $errorMsg")

                val isQuotaExhausted = lowerError.contains("429") || lowerError.contains("quota") ||
                    lowerError.contains("exhausted") || lowerError.contains("resource_exhausted")

                val isTimeoutOrServer = t is java.net.SocketTimeoutException ||
                    lowerError.contains("timeout") ||
                    lowerError.contains("500") || lowerError.contains("502") || lowerError.contains("503") || lowerError.contains("504") ||
                    t.javaClass.simpleName.contains("ServerException") ||
                    lowerError.contains("overloaded") || lowerError.contains("unavailable")

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
                    fileLog("Key #${keyIdx + 1}/$totalKeys failed ($attempt/$maxAttempts). Enforcing 5000ms stable grace period before failover...")
                    Log.w(TAG, "Key #${keyIdx + 1}/$totalKeys failed ($attempt/$maxAttempts). Enforcing 5000ms stable grace period before failover...")
                    onRetry?.invoke()

                    // Strict 5-Second Stable Grace Period:
                    // Never rotate or hammer keys in milliseconds! Wait a full 5000ms before rotating to next key.
                    delay(5000L)

                    // Advance key and update UI only after the 5-second grace window expires
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
                    val effectiveModelName = modelName.trim().removePrefix("models/").ifBlank { "gemini-2.0-flash" }

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
                        systemInstruction = content { text(systemPrompt) }
                    )

                    val chat = generativeModel.startChat(chatHistory)
                    val responseFlow = chat.sendMessageStream(promptText)

                    responseFlow.collect { chunk ->
                        if (isActive && chunk.text != null) {
                            channel.send(chunk.text!!)
                        }
                    }
                    channel.close()
                } catch (t: Throwable) {
                    channel.close(t)
                }
            }

            try {
                // Strict 5-Second SLA Timeout Window for initial connection & response chunk
                val firstChunk = withTimeoutOrNull(5000L) {
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
                        throw java.net.SocketTimeoutException("Key #${keyIdx + 1}/$totalKeys 5-second SLA timeout: zero response chunks received within 5000ms")
                    } else {
                        throw IllegalStateException("Key #${keyIdx + 1}/$totalKeys returned empty response stream")
                    }
                }

                // First byte received within 5-second SLA: Validate key & persist immediately
                markKeySuccess(keyIdx)
                emit(firstChunk)

                // Stream the remainder of the response
                for (chunk in channel) {
                    if (coroutineContext.isActive) {
                        emit(chunk)
                    }
                }

                success = true
                fileLog("Inference successful with Key #${keyIdx + 1}/$totalKeys")

            } catch (t: Throwable) {
                streamJob.cancel()
                if (t is kotlinx.coroutines.CancellationException) {
                    throw t
                }

                val errorMsg = t.message ?: t.toString()
                lastErrorDetail = errorMsg
                val lowerError = errorMsg.lowercase()

                fileLog("Key #${keyIdx + 1}/$totalKeys [${t.javaClass.simpleName}] Error: $errorMsg")

                val isQuotaExhausted = lowerError.contains("429") || lowerError.contains("quota") ||
                    lowerError.contains("exhausted") || lowerError.contains("resource_exhausted")

                val isTimeoutOrServer = t is java.net.SocketTimeoutException ||
                    lowerError.contains("timeout") ||
                    lowerError.contains("500") || lowerError.contains("502") || lowerError.contains("503") || lowerError.contains("504") ||
                    t.javaClass.simpleName.contains("ServerException") ||
                    lowerError.contains("overloaded") || lowerError.contains("unavailable")

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
                    fileLog("Key #${keyIdx + 1}/$totalKeys failed ($attempt/$maxAttempts). Enforcing 5000ms stable grace period before failover...")
                    Log.w(TAG, "Key #${keyIdx + 1}/$totalKeys failed ($attempt/$maxAttempts). Enforcing 5000ms stable grace period before failover...")
                    onRetry?.invoke()

                    // Strict 5-Second Stable Grace Period:
                    // Never rotate or hammer keys in milliseconds! Wait a full 5000ms before rotating to next key.
                    delay(5000L)

                    // Advance key and update UI only after the 5-second grace window expires
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

    fun stop() {}
    fun release() { isLoaded = false }
    fun close() { release() }
}

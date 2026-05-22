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
import kotlin.coroutines.coroutineContext

class GeminiModelClient(
    private val apiKeys: List<String>,
    initialKeyIndex: Int = 0,
    private val onKeyRotated: ((Int) -> Unit)? = null
) {

    companion object {
        private const val TAG = "GeminiClient"
    }

    private var currentKeyIndex = initialKeyIndex.coerceIn(0, (apiKeys.size - 1).coerceAtLeast(0))

    var modelName: String = "models/gemini-1.5-flash"
    var maxTokens: Int = 4096
    var temperature: Float = 0.1f
    var isLoaded: Boolean = apiKeys.isNotEmpty()

    private fun fileLog(msg: String) {
        Log.d(TAG, msg)
        try {
            val dir = File("/sdcard/MedlamAI")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "gguf_log.txt").appendText("[${java.util.Date()}] [GEMINI] $msg\n")
        } catch (_: Exception) {}
    }

    private fun getNextKey(forceRotate: Boolean): String {
        if (apiKeys.isEmpty()) throw RuntimeException("No API keys provided")
        if (forceRotate || currentKeyIndex == -1) {
            currentKeyIndex = (currentKeyIndex + 1) % apiKeys.size
            fileLog( "Rotating to API key at index $currentKeyIndex")
            onKeyRotated?.invoke(currentKeyIndex)
        }
        return apiKeys[currentKeyIndex]
    }

    init {
        if (apiKeys.isNotEmpty()) onKeyRotated?.invoke(currentKeyIndex)
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
                // Last user message, keep as prompt
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
                // History message
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
        var success = false
        var lastErrorDetail = ""

        while (attempt < totalKeys && !success) {
            val apiKey = getNextKey(forceRotate = attempt > 0)
            try {
                if (attempt > 0) {
                    onRetry?.invoke()
                    val waitTime = (attempt * 1500L).coerceAtMost(10000L)
                    kotlinx.coroutines.delay(waitTime)
                }

                val effectiveModelName = if (modelName.contains("/")) modelName else "models/$modelName"
                
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
                val responseFlow = chat.sendMessageStream(content(role="user"){
                    text(promptText)
                    for (img in promptImages) {
                        image(img)
                    }
                })
                
                responseFlow.collect { chunk ->
                    if (coroutineContext.isActive && chunk.text != null) {
                        emit(chunk.text!!)
                    }
                }
                
                success = true 
                fileLog( "Inference successful with Key #$currentKeyIndex")

            } catch (t: Throwable) {
                val errorMsg = t.message ?: t.toString()
                lastErrorDetail = errorMsg
                val lowerError = errorMsg.lowercase()
                
                fileLog("Key #$currentKeyIndex [${t.javaClass.simpleName}] Error: $errorMsg")

                if (lowerError.contains("429") || 
                    lowerError.contains("quota") || 
                    lowerError.contains("limit") || 
                    lowerError.contains("exhausted") ||
                    lowerError.contains("resource") ||
                    lowerError.contains("overloaded") ||
                    lowerError.contains("unavailable") ||
                    lowerError.contains("deadline") ||
                    lowerError.contains("safety") ||
                    lowerError.contains("recitation") ||
                    t is java.net.SocketTimeoutException) {
                    
                    attempt++
                    Log.w(TAG, "Key #$currentKeyIndex failed ($attempt/$totalKeys). Attempting rotation...")
                } else if (lowerError.contains("location") || lowerError.contains("unsupported")) {
                    throw RuntimeException("Region Blocked: Gemini is not available in your region. Use a VPN.")
                } else {
                    // Fatal configuration error
                    throw RuntimeException("Gemini Error: $errorMsg")
                }
            }
        }
        
        if (!success) {
            throw RuntimeException("All $totalKeys API keys are currently blocked or failed. Last error: $lastErrorDetail")
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateStreamWithHistory(
        history: List<Pair<String, String>>,
        systemPrompt: String = "",
        onRetry: (() -> Unit)? = null
    ): Flow<String> = flow {
        Log.d(TAG, "Generating with Gemini (Resilient Engine)...")

        // 1. Sanitize and Collapse History
        val collapsed = mutableListOf<Pair<String, String>>()
        for (msg in history) {
            val content = msg.second.trim()
            if (content.isBlank()) continue 
            
            if (collapsed.isNotEmpty() && collapsed.last().first == msg.first) {
                val last = collapsed.removeAt(collapsed.size - 1)
                collapsed.add(last.first to last.second + "\n\n" + content)
            } else {
                collapsed.add(msg.first to content)
            }
        }

        while (collapsed.isNotEmpty() && collapsed.first().first != "user") {
            collapsed.removeAt(0)
        }

        if (collapsed.isEmpty()) throw RuntimeException("No valid conversation history.")

        val promptText = collapsed.removeAt(collapsed.size - 1).second
        val chatHistory = collapsed.map { (role, text) ->
            val geminiRole = if (role == "user") "user" else "model"
            content(role = geminiRole) { text(text) }
        }

        var attempt = 0
        val totalKeys = apiKeys.size
        var success = false
        var lastErrorDetail = ""

        while (attempt < totalKeys && !success) {
            val apiKey = getNextKey(forceRotate = attempt > 0)
            try {
                // Incremental delay to respect RPM (Requests Per Minute)
                if (attempt > 0) {
                    onRetry?.invoke()
                    val waitTime = (attempt * 1500L).coerceAtMost(10000L)
                    Log.d(TAG, "Waiting ${waitTime}ms before retry with next key...")
                    kotlinx.coroutines.delay(waitTime)
                }

                val effectiveModelName = if (modelName.contains("/")) modelName else "models/$modelName"
                
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
                    if (coroutineContext.isActive && chunk.text != null) {
                        emit(chunk.text!!)
                    }
                }
                
                success = true 

            } catch (t: Throwable) {
                val errorMsg = t.message ?: t.toString()
                lastErrorDetail = errorMsg
                val lowerError = errorMsg.lowercase()
                
                // Rotate for rate limits, quota, safety blocks, or temporary server issues
                if (lowerError.contains("429") || 
                    lowerError.contains("quota") || 
                    lowerError.contains("limit") || 
                    lowerError.contains("exhausted") ||
                    lowerError.contains("resource") ||
                    lowerError.contains("overloaded") ||
                    lowerError.contains("unavailable") ||
                    lowerError.contains("safety") ||
                    lowerError.contains("recitation") ||
                    lowerError.contains("deadline") ||
                    t is java.net.SocketTimeoutException) {
                    
                    attempt++
                    Log.w(TAG, "Key #$currentKeyIndex failed ($attempt/$totalKeys). Error: $errorMsg")
                } else {
                    // Fatal configuration error
                    throw RuntimeException("Gemini Fatal: $errorMsg")
                }
            }
        }
        
        if (!success) {
            throw RuntimeException("All $totalKeys API keys are currently blocked or failed. Last error: $lastErrorDetail")
        }
    }.flowOn(Dispatchers.IO)

    fun stop() {
        // No explicit stop needed for Gemini API, the coroutine cancellation handles it
    }

    fun release() {
        isLoaded = false
    }

    fun close() {
        release()
    }
}

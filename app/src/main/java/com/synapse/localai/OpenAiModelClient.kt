package com.synapse.localai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class OpenAiModelClient(
    private val apiKeys: List<String>,
    initialKeyIndex: Int = 0,
    private val onKeyRotated: ((Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "OpenAiClient"
    }

    private var currentKeyIndex = initialKeyIndex.coerceIn(0, (apiKeys.size - 1).coerceAtLeast(0))
    var isLoaded: Boolean = apiKeys.isNotEmpty()
    var modelName: String = "gpt-4o"
    var maxTokens: Int = 4096
    var temperature: Float = 0.1f

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    init {
        if (apiKeys.isNotEmpty()) onKeyRotated?.invoke(currentKeyIndex)
    }

    private fun getNextKey(forceRotate: Boolean): String {
        if (apiKeys.isEmpty()) throw RuntimeException("No API keys provided")
        if (forceRotate || currentKeyIndex == -1) {
            currentKeyIndex = (currentKeyIndex + 1) % apiKeys.size
            Log.i(TAG, "Rotating to API key at index $currentKeyIndex")
            onKeyRotated?.invoke(currentKeyIndex)
        }
        return apiKeys[currentKeyIndex]
    }

    suspend fun generateStreamWithOllamaMessages(
        messages: JSONArray,
        systemPrompt: String = "",
        onRetry: (() -> Unit)? = null
    ): Flow<String> = flow {
        Log.d(TAG, "Generating with OpenAI ($modelName)...")

        val openAiMessages = JSONArray()
        if (systemPrompt.isNotBlank()) {
            openAiMessages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }

        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val role = msg.optString("role", "user")
            val contentStr = msg.optString("content", "")
            val imagesArray = msg.optJSONArray("images")

            val openAiRole = if (role == "user") "user" else "assistant"
            
            if (imagesArray != null && imagesArray.length() > 0) {
                val contentArray = JSONArray()
                contentArray.put(JSONObject().put("type", "text").put("text", contentStr))
                for (j in 0 until imagesArray.length()) {
                    val b64 = imagesArray.getString(j)
                    val imgObj = JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
                    contentArray.put(imgObj)
                }
                openAiMessages.put(JSONObject().put("role", openAiRole).put("content", contentArray))
            } else {
                openAiMessages.put(JSONObject().put("role", openAiRole).put("content", contentStr))
            }
        }

        val requestBody = JSONObject()
            .put("model", modelName)
            .put("messages", openAiMessages)
            .put("max_tokens", maxTokens)
            .put("temperature", temperature.toDouble())
            .put("stream", true)

        var key = getNextKey(false)
        var retryCount = 0
        var success = false

        while (!success && retryCount < apiKeys.size) {
            try {
                if (retryCount > 0) {
                    onRetry?.invoke()
                    kotlinx.coroutines.delay(1500L)
                }

                val req = Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $key")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(req).execute()
                
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "OpenAI Error: ${response.code} - $errorBody")
                    if (response.code == 429 || response.code == 401) {
                        key = getNextKey(true)
                        retryCount++
                        continue
                    } else {
                        throw RuntimeException("OpenAI API Error: ${response.code}")
                    }
                }

                success = true
                val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.startsWith("data: ")) {
                        val data = line!!.substring(6)
                        if (data == "[DONE]") break
                        try {
                            val chunkObj = JSONObject(data)
                            val choices = chunkObj.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                if (delta != null && delta.has("content")) {
                                    emit(delta.getString("content"))
                                }
                            }
                        } catch (e: Exception) {
                            // ignore parse error for incomplete chunks
                        }
                    }
                }
            } catch (e: Exception) {
                if (!success) {
                    key = getNextKey(true)
                    retryCount++
                }
                if (retryCount >= apiKeys.size) throw e
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateStream(
        prompt: String,
        systemPrompt: String = "",
        onRetry: (() -> Unit)? = null
    ): Flow<String> = flow {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        generateStreamWithOllamaMessages(messages, systemPrompt, onRetry).collect { emit(it) }
    }
}

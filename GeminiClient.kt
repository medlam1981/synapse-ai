
package com.synapse.localai

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json

class GeminiClient(private val apiKey: String) {
    private val client = OkHttpClient()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun generateResponse(prompt: String, model: String = "gemini-3-flash-preview"): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        
        val jsonRequest = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", prompt) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.7)
            }
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonRequest.toString().toRequestBody(mediaType))
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return "Error: ${response.code}"
            val body = response.body?.string() ?: return "Error: empty response"
            try {
                val json = Json.parseToJsonElement(body).jsonObject
                json["candidates"]
                    ?.jsonArray?.get(0)?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray?.get(0)?.jsonObject
                    ?.get("text")?.jsonPrimitive?.content
                    ?: "Error: unexpected response format"
            } catch (e: Exception) {
                "Error: failed to parse response - ${e.message}"
            }
        }
    }
}

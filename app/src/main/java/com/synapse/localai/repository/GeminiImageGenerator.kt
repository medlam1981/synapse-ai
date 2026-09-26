package com.synapse.localai.repository

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GeminiImageGenerator: Native Cloud Image Generator supporting Google's Imagen & Gemini Image models.
 * 
 * Intercepts visual synthesis requests in Cloud mode, delegates directly to Google's
 * generativelanguage endpoints (both Imagen :predict and Gemini :generateContent),
 * decodes Base64 data into raw in-memory ByteArray, and maintains zero reliance on
 * external URL hosting or third-party web services.
 */
class GeminiImageGenerator(
    private val apiKeysProvider: () -> List<String>,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : ImageGenerator {

    private data class CandidateModel(
        val name: String,
        val isPredictMethod: Boolean
    )

    companion object {
        private const val TAG = "GeminiImageGen"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        // Priority list of Google Image models (Imagen 3 + Gemini Image Preview)
        private val MODEL_CANDIDATES = listOf(
            CandidateModel("imagen-3.0-generate-002", isPredictMethod = true),
            CandidateModel("imagen-3.0-generate-001", isPredictMethod = true),
            CandidateModel("imagen-3.0-fast-generate-001", isPredictMethod = true),
            CandidateModel("gemini-2.5-flash-image", isPredictMethod = false),
            CandidateModel("gemini-3.1-flash-image-preview", isPredictMethod = false),
            CandidateModel("gemini-3-pro-image-preview", isPredictMethod = false)
        )
    }

    override suspend fun generateImage(prompt: String): ImageResult = withContext(Dispatchers.IO) {
        val keys = apiKeysProvider()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.startsWith("AIza") } // Only valid Google API keys
        if (keys.isEmpty()) {
            return@withContext ImageResult.Error("No valid Gemini API keys configured. Please add an API key starting with AIza in Settings.")
        }

        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) {
            return@withContext ImageResult.Error("Image prompt cannot be empty.")
        }

        var lastError = "Image generation failed"

        // Iterate through valid keys and candidate models with resilient failover
        for (key in keys) {
            for (candidate in MODEL_CANDIDATES) {
                try {
                    val endpoint = if (candidate.isPredictMethod) ":predict" else ":generateContent"
                    val url = "$BASE_URL/${candidate.name}$endpoint?key=$key"

                    val requestPayload = if (candidate.isPredictMethod) {
                        JSONObject().apply {
                            put("instances", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("prompt", cleanPrompt)
                                })
                            })
                            put("parameters", JSONObject().apply {
                                put("sampleCount", 1)
                                put("aspectRatio", "1:1")
                                put("outputMimeType", "image/jpeg")
                            })
                        }
                    } else {
                        JSONObject().apply {
                            put("contents", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("parts", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("text", cleanPrompt)
                                        })
                                    })
                                })
                            })
                        }
                    }

                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val requestBody = requestPayload.toString().toRequestBody(mediaType)
                    val request = Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .addHeader("Content-Type", "application/json")
                        .build()

                    val response = client.newCall(request).execute()
                    val code = response.code
                    val responseText = response.body?.string() ?: ""

                    if (response.isSuccessful && responseText.isNotBlank()) {
                        val json = JSONObject(responseText)

                        // 1. Imagen format: predictions[0].bytesBase64Encoded
                        val predictions = json.optJSONArray("predictions")
                        if (predictions != null && predictions.length() > 0) {
                            val first = predictions.get(0)
                            val base64Data = when (first) {
                                is JSONObject -> {
                                    first.optString("bytesBase64Encoded").ifBlank {
                                        first.optString("image").ifBlank {
                                            first.optString("bytes")
                                        }
                                    }
                                }
                                is String -> first
                                else -> ""
                            }

                            if (base64Data.isNotBlank()) {
                                val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                if (imageBytes != null && imageBytes.isNotEmpty()) {
                                    Log.i(TAG, "Synthesized image (${imageBytes.size} bytes) via Imagen ${candidate.name}")
                                    return@withContext ImageResult.Success(
                                        imageBytes = imageBytes,
                                        mimeType = "image/jpeg",
                                        prompt = cleanPrompt
                                    )
                                }
                            }
                        }

                        // 2. Gemini Multimodal format: candidates[0].content.parts[...].inlineData.data
                        val candidates = json.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val content = candidates.optJSONObject(0)?.optJSONObject("content")
                            val parts = content?.optJSONArray("parts")
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val part = parts.optJSONObject(i)
                                    val inlineData = part?.optJSONObject("inlineData")
                                    val b64 = inlineData?.optString("data")
                                    val mime = inlineData?.optString("mimeType", "image/jpeg") ?: "image/jpeg"
                                    if (!b64.isNullOrBlank()) {
                                        val imageBytes = Base64.decode(b64, Base64.DEFAULT)
                                        if (imageBytes != null && imageBytes.isNotEmpty()) {
                                            Log.i(TAG, "Synthesized image (${imageBytes.size} bytes) via Gemini ${candidate.name}")
                                            return@withContext ImageResult.Success(
                                                imageBytes = imageBytes,
                                                mimeType = mime,
                                                prompt = cleanPrompt
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Handle known API response codes
                    when (code) {
                        404 -> {
                            Log.w(TAG, "Model ${candidate.name} returned 404, trying next candidate...")
                            lastError = "Model ${candidate.name} not available on this tier"
                            continue // Try next model candidate
                        }
                        429 -> {
                            Log.w(TAG, "Quota limit (429) for key ${key.take(6)}..., rotating key...")
                            if (responseText.contains("limit: 0", ignoreCase = true)) {
                                lastError = "Image generation requires a Google Cloud project with billing enabled (Free tier quota is 0 for image synthesis)."
                            } else {
                                lastError = "API rate limit reached (429). Google Cloud quota exhausted."
                            }
                            break // Break to next API key
                        }
                        400, 403 -> {
                            val errMessage = try {
                                JSONObject(responseText).optJSONObject("error")?.optString("message") ?: "HTTP $code"
                            } catch (_: Exception) { "HTTP $code" }
                            Log.w(TAG, "API error $code on key ${key.take(6)}...: $errMessage")
                            lastError = errMessage
                            break // Break to next API key
                        }
                        else -> {
                            val errMessage = try {
                                JSONObject(responseText).optJSONObject("error")?.optString("message") ?: "HTTP $code"
                            } catch (_: Exception) { "HTTP $code: $responseText" }
                            lastError = errMessage
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during visual synthesis (${candidate.name}): ${e.message}", e)
                    lastError = e.message ?: "Network error during visual generation"
                }
            }
        }

        ImageResult.Error(lastError)
    }
}

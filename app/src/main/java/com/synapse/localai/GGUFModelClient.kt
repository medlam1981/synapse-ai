package com.synapse.localai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class GGUFModelClient {

    companion object {
        private const val TAG = "GGUFModel"
        private const val SERVER_URL = "http://127.0.0.1:8080"
    }

    private var modelPath: String? = null

    var isLoaded: Boolean = false
        private set

    fun loadModel(path: String, contextLength: Int = 512, onLoaded: () -> Unit = {}) {
        Log.d(TAG, "Checking GGUF model: $path")
        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException("Model not found: $path")

        // Check if server is running
        try {
            val url = URL("$SERVER_URL/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            if (response.contains("ok")) {
                modelPath = path
                isLoaded = true
                Log.d(TAG, "GGUF server is running, model ready")
                onLoaded()
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Server not running: ${e.message}")
        }

        // Server not running - mark as loaded anyway (user must start server manually via adb)
        modelPath = path
        isLoaded = true
        Log.d(TAG, "GGUF model path set (server may need to be started via adb)")
        onLoaded()
    }

    fun generateStream(prompt: String, systemPrompt: String = ""): Flow<String> = flow {
        Log.d(TAG, "Generating: ${prompt.take(50)}...")

        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))

        val body = JSONObject()
            .put("model", "local")
            .put("messages", messages)
            .put("max_tokens", 512)
            .put("temperature", 0.7)
            .put("stream", true)

        val url = URL("$SERVER_URL/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 30000
        conn.readTimeout = 120000

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var line: String? = null
            var thinking = false

            while (coroutineContext.isActive && reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val json = JSONObject(data)
                    val delta = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .optJSONObject("delta") ?: continue

                    // Handle thinking/reasoning content
                    // Note: optString returns "null" for JSON null, so check with isNull first
                    val reasoningContent = if (delta.isNull("reasoning_content")) "" else delta.optString("reasoning_content", "")
                    if (reasoningContent.isNotEmpty()) {
                        if (!thinking) {
                            thinking = true
                            emit("💭 ")
                        }
                        emit(reasoningContent)
                        continue
                    }

                    // Handle regular content
                    val content = if (delta.isNull("content")) "" else delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        if (thinking) {
                            thinking = false
                            emit("\n\n")
                        }
                        emit(content)
                    }
                } catch (_: Exception) {}
            }

            reader.close()
        } catch (e: Exception) {
            Log.e(TAG, "Stream error: ${e.message}", e)
            throw RuntimeException("GGUF inference failed: ${e.message}. Make sure llama-server is running on the device.", e)
        } finally {
            conn.disconnect()
        }

        Log.d(TAG, "Generation complete")
    }.flowOn(Dispatchers.IO)

    fun stop() {
        // Could POST to /slots endpoint to cancel, but simple disconnect suffices
    }

    fun release() {
        modelPath = null
        isLoaded = false
    }

    fun close() {
        release()
        Log.d(TAG, "GGUF client closed")
    }
}

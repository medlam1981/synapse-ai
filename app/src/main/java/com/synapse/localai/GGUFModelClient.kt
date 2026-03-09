package com.synapse.localai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
        private const val SERVER_BINARY = "/data/local/tmp/llama-server"
        private const val SERVER_START_TIMEOUT_MS = 60_000L
        private const val SERVER_POLL_INTERVAL_MS = 1_000L
    }

    private var modelPath: String? = null
    private var serverProcess: Process? = null

    var isLoaded: Boolean = false
        private set

    private fun isServerRunning(): Boolean {
        return try {
            val url = URL("$SERVER_URL/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            response.contains("ok")
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun startServer(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Attempting to start llama-server...")

        val serverFile = File(SERVER_BINARY)
        if (!serverFile.exists()) {
            Log.e(TAG, "llama-server binary not found at $SERVER_BINARY")
            return@withContext false
        }

        try {
            // Use su to start the server as root (bypasses app seccomp restrictions)
            val cmd = arrayOf(
                "su", "-c",
                "$SERVER_BINARY -m $modelPath -c 2048 -t 2 --host 127.0.0.1 --port 8080 &"
            )
            serverProcess = Runtime.getRuntime().exec(cmd)
            Log.d(TAG, "Server process started via su")

            // Wait for server to become ready
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < SERVER_START_TIMEOUT_MS) {
                delay(SERVER_POLL_INTERVAL_MS)
                if (isServerRunning()) {
                    Log.d(TAG, "Server is ready! (took ${System.currentTimeMillis() - startTime}ms)")
                    return@withContext true
                }
            }

            Log.e(TAG, "Server failed to start within timeout")
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}", e)
            return@withContext false
        }
    }

    suspend fun loadModel(path: String, contextLength: Int = 512, onStatus: (String) -> Unit = {}, onLoaded: () -> Unit = {}) {
        Log.d(TAG, "Checking GGUF model: $path")
        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException("Model not found: $path")

        modelPath = path

        // Check if server is already running
        if (isServerRunning()) {
            isLoaded = true
            Log.d(TAG, "GGUF server already running, model ready")
            onLoaded()
            return
        }

        // Try to auto-start the server
        onStatus("Starting llama-server...")
        val started = startServer(path)
        if (started) {
            isLoaded = true
            Log.d(TAG, "GGUF server auto-started successfully")
            onLoaded()
        } else {
            // Server couldn't start - still mark as loaded, will fail on inference
            isLoaded = true
            Log.w(TAG, "Could not auto-start server. Model set but server not running.")
            onStatus("⚠️ Server not started. Start manually via adb or Termux.")
            onLoaded()
        }
    }

    fun generateStream(prompt: String, systemPrompt: String = ""): Flow<String> = flow {
        val history = listOf("user" to prompt)
        val innerFlow = generateStreamWithHistory(history, systemPrompt)
        innerFlow.collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    fun generateStreamWithHistory(
        history: List<Pair<String, String>>,
        systemPrompt: String = ""
    ): Flow<String> = flow {
        Log.d(TAG, "Generating with ${history.size} messages...")

        // Auto-restart server if it died
        if (!isServerRunning() && modelPath != null) {
            Log.w(TAG, "Server not responding, attempting restart...")
            val restarted = startServer(modelPath!!)
            if (!restarted) {
                throw RuntimeException("llama-server is not running. Please restart the app or start the server manually.")
            }
        }

        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        for ((role, content) in history) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }

        val body = JSONObject()
            .put("model", "local")
            .put("messages", messages)
            .put("max_tokens", 2048)
            .put("temperature", 0.7)
            .put("stream", true)

        val url = URL("$SERVER_URL/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 30000
        conn.readTimeout = 300000

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
            throw RuntimeException("GGUF inference failed: ${e.message}", e)
        } finally {
            conn.disconnect()
        }

        Log.d(TAG, "Generation complete")
    }.flowOn(Dispatchers.IO)

    fun stop() {}

    fun release() {
        modelPath = null
        isLoaded = false
    }

    fun close() {
        release()
        Log.d(TAG, "GGUF client closed")
    }
}

package com.synapse.localai

import android.content.Context
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
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 8080
        private const val SERVER_LIB_NAME = "libllama_server.so"
        private const val SERVER_START_TIMEOUT_MS = 90_000L
        private const val SERVER_POLL_INTERVAL_MS = 1_000L
    }

    private var modelPath: String? = null
    private var serverProcess: Process? = null
    private var nativeLibDir: String? = null

    var serverHost: String = DEFAULT_HOST
    var serverPort: Int = DEFAULT_PORT
    var contextLength: Int = 2048
    var threadCount: Int = 2

    private val serverUrl: String get() = "http://$serverHost:$serverPort"

    var isLoaded: Boolean = false
        private set

    private fun isServerRunning(): Boolean {
        return try {
            val url = URL("$serverUrl/health")
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

    private fun findServerBinary(): String? {
        // Primary: bundled native lib
        val nativeLib = nativeLibDir?.let { File(it, SERVER_LIB_NAME) }
        if (nativeLib?.exists() == true) {
            Log.d(TAG, "Found bundled server at: ${nativeLib.absolutePath}")
            return nativeLib.absolutePath
        }
        // Fallback: legacy location
        val legacy = File("/data/local/tmp/llama-server")
        if (legacy.exists()) {
            Log.d(TAG, "Found legacy server at: ${legacy.absolutePath}")
            return legacy.absolutePath
        }
        return null
    }

    private suspend fun startServer(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Attempting to start llama-server...")

        val serverBinary = findServerBinary()
        if (serverBinary == null) {
            Log.e(TAG, "llama-server binary not found")
            return@withContext false
        }

        // Disable phantom process killer (resets on reboot)
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c",
                "device_config put activity_manager max_phantom_processes 2147483647"))
                .waitFor()
            Log.d(TAG, "Phantom process killer disabled")
        } catch (e: Exception) {
            Log.w(TAG, "Could not disable phantom process killer: ${e.message}")
        }

        try {
            // Kill any existing server on our port
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c",
                    "pkill -f libllama_server")).waitFor()
                delay(500)
            } catch (_: Exception) {}

            // Launch via su + setsid + nohup to detach from app cgroup (survives phantom process killer)
            val cmd = "setsid nohup $serverBinary" +
                " -m $modelPath" +
                " -c $contextLength" +
                " -t $threadCount" +
                " --host $serverHost" +
                " --port $serverPort" +
                " --reasoning-budget 0" +
                " > /dev/null 2>&1 &"
            val pb = ProcessBuilder("su", "-c", cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.waitFor()
            Log.d(TAG, "Server launched via su+setsid (detached from app cgroup)")

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

    suspend fun loadModel(context: Context, path: String, contextLength: Int = 512, onStatus: (String) -> Unit = {}, onLoaded: () -> Unit = {}) {
        Log.d(TAG, "Checking GGUF model: $path")
        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException("Model not found: $path")

        modelPath = path
        nativeLibDir = context.applicationInfo.nativeLibraryDir

        // Check if server is already running with correct model
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
            isLoaded = true
            Log.w(TAG, "Could not auto-start server.")
            onStatus("⚠️ Server failed to start. Check model file permissions.")
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
            .put("max_tokens", 1024)
            .put("temperature", 0.7)
            .put("stream", true)

        val url = URL("$serverUrl/v1/chat/completions")
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

                    val content = if (delta.isNull("content")) "" else delta.optString("content", "")
                    if (content.isNotEmpty()) {
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

    fun stop() {
        // Kill the detached server process
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f libllama_server"))
            Log.d(TAG, "Stopped llama-server")
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop server: ${e.message}")
        }
    }

    fun release() {
        modelPath = null
        isLoaded = false
    }

    fun close() {
        stop()
        release()
        Log.d(TAG, "GGUF client closed")
    }
}

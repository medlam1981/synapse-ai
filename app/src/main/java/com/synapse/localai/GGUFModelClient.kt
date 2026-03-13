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
        private const val DEFAULT_HOST = "0.0.0.0"
        private const val DEFAULT_PORT = 11434
        private const val SERVER_LIB_NAME = "libllama_server.so"
        private const val SERVER_START_TIMEOUT_MS = 180_000L
        private const val SERVER_POLL_INTERVAL_MS = 1_000L
    }

    private var modelPath: String? = null
    private var serverProcess: Process? = null
    private var nativeLibDir: String? = null

    var serverHost: String = DEFAULT_HOST
    var serverPort: Int = DEFAULT_PORT
    var contextLength: Int = 1024
    var threadCount: Int = 4

    private val serverUrl: String get() {
        val host = if (serverHost == "0.0.0.0") "127.0.0.1" else serverHost
        return "http://$host:$serverPort"
    }

    var isLoaded: Boolean = false
        private set

    private suspend fun isServerRunning(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = URL("$serverUrl/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            response.contains("ok")
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun isModelReady(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = URL("$serverUrl/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
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
            System.gc()
            // Magic Touch: Kill any existing rogue servers first
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f libllama_server")).waitFor()
                delay(300)
            } catch (_: Exception) {}

            // Launch via su + setsid + nohup to evade PhantomProcess restrictions
            val cmd = "setsid nohup $serverBinary" +
                " -m $modelPath" +
                " -c 512" +
                " -b 128" +
                " -ub 128" +
                " -t $threadCount" +
                " -tb 4" +
                " --prio 1" +
                " -fa auto" +
                " --no-warmup" +
                " --mmap" +
                " -fit off" +
                " --host $serverHost" +
                " --port $serverPort" +
                " > /dev/null 2>&1 &"
            val pb = ProcessBuilder("su", "-c", cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.waitFor()
            Log.d(TAG, "Server launched via su+setsid (detached from app cgroup, added 256MB RAM space)")

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
        this.contextLength = contextLength
        nativeLibDir = context.applicationInfo.nativeLibraryDir

        // Check if server is already running with correct model
        if (isServerRunning()) {
            isLoaded = true
            Log.d(TAG, "GGUF server already running, model ready")
            onLoaded()
            return
        }

        // Try to auto-start the server
        onStatus("Loading Model... ⏳")
        val started = startServer(path)
        if (started) {
            isLoaded = true
            Log.d(TAG, "GGUF model loaded and server ready")
            onLoaded()
        } else {
            isLoaded = false
            Log.w(TAG, "Could not auto-start server.")
            onStatus("⚠️ Model failed to load. Check storage.")
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
            .put("max_tokens", 512)
            .put("temperature", 0.7)
            .put("stream", true)
            .put("cache_prompt", true)

        val url = URL("$serverUrl/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 30000
        conn.readTimeout = 600000 // 10 minutes for slow inference

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
                    val reasoning = if (delta.isNull("reasoning_content")) "" else delta.optString("reasoning_content", "")
                    
                    if (reasoning.isNotEmpty()) {
                        emit(reasoning)
                    } else if (content.isNotEmpty()) {
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
        // Kill the detached server process (launched via su+setsid, so serverProcess won't work)
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

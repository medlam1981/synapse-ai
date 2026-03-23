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
    private var proxy: OllamaProxy? = null

    var serverHost: String = DEFAULT_HOST
    var serverPort: Int = DEFAULT_PORT
    var contextLength: Int = 4096
    var threadCount: Int = 4
    var maxTokens: Int = 4096
    var temperature: Float = 0.1f

    private val internalPort: Int get() = serverPort + 1

    private val serverUrl: String get() {
        val host = if (serverHost == "0.0.0.0") "127.0.0.1" else serverHost
        return "http://$host:$internalPort"
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

        try {
            System.gc()
            
            // Clean up any old process
            serverProcess?.destroy()
            serverProcess = null

            // Launch directly without su/setsid for non-root support
            val cmd = listOf(
                serverBinary,
                "-m", modelPath,
                "-c", contextLength.toString(),
                "-b", "512",
                "-ub", "512",
                "-t", threadCount.toString(),
                "-tb", threadCount.toString(),
                "-fa", "auto",
                "--no-warmup",
                "--mmap",
                "-fit", "off",
                "--alias", "local",
                "--host", serverHost,
                "--port", internalPort.toString()
            )
            
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            serverProcess = proc
            Log.d(TAG, "Server launched as standard app subprocess (Rootless Mode)")

            // Crucial: Consume output to prevent buffer blocking
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        // Log.v("LlamaServer", line ?: "") // Optional: log server output
                    }
                } catch (_: Exception) {}
            }.start()

            // Start the Ollama proxy to fix the CLI parsing bug
            proxy?.stop()
            proxy = OllamaProxy(serverPort, internalPort, serverHost)
            proxy?.start()

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
            // Ensure proxy is running
            proxy?.stop()
            proxy = OllamaProxy(serverPort, internalPort, serverHost)
            proxy?.start()
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

        val promptBuilder = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            promptBuilder.append("<|im_start|>system\n$systemPrompt<|im_end|>\n")
        }
        for ((role, content) in history) {
            val roleName = if (role == "user") "user" else "assistant"
            promptBuilder.append("<|im_start|>$roleName\n$content<|im_end|>\n")
        }
        promptBuilder.append("<|im_start|>assistant\n")

        val body = JSONObject()
            .put("prompt", promptBuilder.toString())
            .put("n_predict", maxTokens)
            .put("temperature", temperature.toDouble())
            .put("stream", true)
            .put("cache_prompt", true)
            .put("stop", JSONArray().put("<|im_end|>").put("<|endoftext|>"))

        val url = URL("$serverUrl/completion")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 30000
        conn.readTimeout = 600000 

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorStream = conn.errorStream ?: conn.inputStream
                val errorBody = errorStream.bufferedReader().use { it.readText() }
                throw RuntimeException("HTTP $responseCode: $errorBody")
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var line: String? = null

            while (coroutineContext.isActive && reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val json = JSONObject(data)
                    val content = json.optString("content", "")
                    if (content.isNotEmpty()) {
                        emit(content)
                    }
                } catch (_: Exception) {}
            }

            reader.close()
        } catch (e: Exception) {
            Log.e(TAG, "Stream error: ${e.message}", e)
            throw RuntimeException("Inference failed: ${e.message}", e)
        } finally {
            conn.disconnect()
        }

        Log.d(TAG, "Generation complete")
    }.flowOn(Dispatchers.IO)

    fun stop() {
        try {
            serverProcess?.destroy()
            serverProcess = null
            Log.d(TAG, "Stopped llama-server")
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop server: ${e.message}")
        }
        proxy?.stop()
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

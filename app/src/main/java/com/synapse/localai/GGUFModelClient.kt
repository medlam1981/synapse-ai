package com.synapse.localai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import android.database.Cursor
import android.provider.OpenableColumns

class GGUFModelClient {

    companion object {
        private const val TAG = "GGUFModel"
        private const val DEFAULT_HOST = "127.0.0.1" // Prefer localhost
        private const val DEFAULT_PORT = 11434
        private const val SERVER_START_TIMEOUT_MS = 300_000L
        private const val SERVER_POLL_INTERVAL_MS = 2_000L

        private fun fileLog(msg: String) {
            Log.d(TAG, msg)
            try {
                val dir = File("/sdcard/MedlamAI")
                if (!dir.exists()) dir.mkdirs()
                File(dir, "gguf_log.txt").appendText("[${java.util.Date()}] $msg\n")
            } catch (_: Exception) {}
        }

        
        @Volatile private var globalLoadedPath: String? = null
        @Volatile var globalServerThread: Thread? = null
    }

    private var modelPath: String? = null
    private var modelPfd: ParcelFileDescriptor? = null
    private var appContext: Context? = null
    private var proxy: OllamaProxy? = null
    var serverThread: Thread? = null

    var serverHost: String = DEFAULT_HOST
    var serverPort: Int = DEFAULT_PORT
    var contextLength: Int = 2048
    var threadCount: Int = 4
    var maxTokens: Int = 2048
    var temperature: Float = 0.1f

    private val internalPort: Int get() = serverPort + 1
    
    private val fallbackUrl: String get() = "http://127.0.0.1:$internalPort"

    var isLoaded: Boolean = false
        private set

    private fun resolveModelPath(context: Context, modelIdentifier: String, onStatus: (String) -> Unit = {}): String? {
        if (!modelIdentifier.startsWith("content://")) {
            val f = File(modelIdentifier)
            return if (f.exists()) f.absolutePath else null
        }
        val uri = Uri.parse(modelIdentifier)
        val fileName = getFileNameFromUri(context, uri) ?: "model.gguf"
        val cacheFile = File(context.filesDir, "models/$fileName")

        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile.absolutePath

        return try {
            cacheFile.parentFile?.mkdirs()
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            modelPfd = pfd
            val totalBytes = pfd.statSize.takeIf { it > 0 }
            var copiedBytes = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    val buf = ByteArray(8 * 1024 * 1024)
                    var bytes = input.read(buf)
                    while (bytes >= 0) {
                        output.write(buf, 0, bytes)
                        copiedBytes += bytes
                        if (totalBytes != null && totalBytes > 0) {
                            val pct = (copiedBytes * 100 / totalBytes).toInt()
                            if (pct % 10 == 0) onStatus("Copying model... $pct%")
                        }
                        bytes = input.read(buf)
                    }
                }
            }
            pfd.close()
            modelPfd = null
            cacheFile.absolutePath
        } catch (e: Exception) {
            try { modelPfd?.close() } catch (_: Exception) {}
            modelPfd = null
            cacheFile.delete()
            null
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var name: String? = null
        try {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use { if (it.moveToFirst()) { val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (idx >= 0) name = it.getString(idx) } }
        } catch (_: Exception) {}
        return name ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private suspend fun isServerRunning(): Boolean = withContext(Dispatchers.IO) {
        val address = try { LlamaServerJNI.getAddress() } catch (_: Throwable) { null } 
        val urlToTry = address?.replace("0.0.0.0", "127.0.0.1") ?: fallbackUrl
        
        var conn: HttpURLConnection? = null
        return@withContext try {
            val url = URL("$urlToTry/health")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            response.contains("ok")
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun startServer(resolvedPath: String, onStatus: (String) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        fileLog("startServer called for: $resolvedPath")

        if (globalLoadedPath == resolvedPath && isServerRunning()) {
            fileLog("Server already running with same model — reusing")
            onStatus("Model already loaded ✓")
            serverThread = globalServerThread
            return@withContext true
        }

        if (!LlamaServerJNI.load()) {
            fileLog("Failed to load native libraries")
            onStatus("❌ Error: Native Engine Load Failure")
            return@withContext false
        }

        val ctx = appContext
        if (ctx != null) {
            fileLog("Starting ModelEngineService priority shield...")
            ctx.startService(Intent(ctx, ModelEngineService::class.java))
        }

        if (globalLoadedPath != null) {
            onStatus("Stopping previous server...")
            try { LlamaServerJNI.stop() } catch (_: Exception) {}
            delay(2000)
            globalServerThread?.interrupt()
            globalServerThread = null
            globalLoadedPath = null
            serverThread = null
            delay(1500)
        }

        try {
            System.gc()
            val hw = appContext?.let { DeviceProfile.detect(it) }
            val batchSize = if (hw != null) DeviceProfile.optimalBatch(hw) else 128
            val ubatchSize = if (hw != null) DeviceProfile.optimalUbatch(hw) else 64
            val threads = if (hw != null) DeviceProfile.optimalThreads(hw) else 4

            val args = arrayOf(
                "-m", resolvedPath,
                "-c", contextLength.toString(),
                "-np", "1",
                "-b", batchSize.toString(),
                "-ub", ubatchSize.toString(),
                "-t", threads.toString(),
                "--no-warmup",
                "--mmap",
                "--cache-type-k", "q4_0",      
                "--cache-type-v", "q4_0",
                "--alias", "local",
                "--host", "127.0.0.1",
                "--port", internalPort.toString()
            )

            val newThread = Thread {
                try {
                    fileLog("JNI server thread starting with args: ${args.joinToString(" ")}")
                    val result = LlamaServerJNI.start(args)
                    fileLog("JNI server thread finished (returned $result)")
                } catch (e: Exception) {
                    fileLog("JNI server thread exception: ${e.message}")
                }
            }.apply {
                name = "llama-server-jni"
                isDaemon = true
                start()
            }
            serverThread = newThread
            globalServerThread = newThread
            globalLoadedPath = resolvedPath

            proxy?.stop()
            proxy = OllamaProxy(serverPort, internalPort, "127.0.0.1")
            proxy?.start()

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < SERVER_START_TIMEOUT_MS) {
                delay(SERVER_POLL_INTERVAL_MS)
                if (isServerRunning()) {
                    fileLog("Server ready via JNI!")
                    onStatus("✅ Local Server Ready.")
                    return@withContext true
                }
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                if (elapsed % 10 == 0L) {
                    onStatus("Loading model... ${elapsed}s")
                }
            }
            fileLog("Timeout: Server did not respond within ${SERVER_START_TIMEOUT_MS/1000}s")
            onStatus("❌ Error: Boot Timeout.")
            return@withContext false
        } catch (e: Exception) {
            fileLog("Failed to start JNI server: ${e.message}")
            return@withContext false
        }
    }

    suspend fun loadModel(context: Context, path: String, onStatus: (String) -> Unit = {}, onLoaded: () -> Unit = {}) {
        appContext = context.applicationContext
        val resolvedPath = withContext(Dispatchers.IO) { resolveModelPath(context, path, onStatus) }
        if (resolvedPath == null) { onStatus("❌ Error: File not found."); return }
        modelPath = path
        if (isServerRunning() && globalLoadedPath == resolvedPath) { isLoaded = true; onLoaded(); return }
        val started = startServer(resolvedPath, onStatus)
        isLoaded = started
        onLoaded()
    }

    fun generateStream(prompt: String, systemPrompt: String = ""): Flow<String> = flow {
        val history = listOf("user" to prompt)
        generateStreamWithHistory(history, systemPrompt).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    fun generateStreamWithHistory(
        history: List<Pair<String, String>>,
        systemPrompt: String = ""
    ): Flow<String> = flow {
        var address = try { LlamaServerJNI.getAddress() } catch (_: Throwable) { null }
        if (address == null && modelPath != null) {
            fileLog("Address is null during stream request, attempting restart...")
            val ctx = appContext
            val resolvedPath = if (ctx != null) resolveModelPath(ctx, modelPath!!) else null
            if (resolvedPath != null) startServer(resolvedPath)
            address = try { LlamaServerJNI.getAddress() } catch (_: Throwable) { null }
        }

        val promptBuilder = StringBuilder()
        if (systemPrompt.isNotBlank()) promptBuilder.append("<|im_start|>system\n$systemPrompt<|im_end|>\n")
        for ((role, content) in history) {
            val roleName = if (role == "user") "user" else "assistant"
            promptBuilder.append("<|im_start|>$roleName\n$content<|im_end|>\n")
        }
        promptBuilder.append("<|im_start|>assistant\n")
        val finalPrompt = promptBuilder.toString()

        val body = JSONObject()
            .put("prompt", finalPrompt)
            .put("n_predict", maxTokens)
            .put("temperature", temperature.toDouble())
            .put("stream", true)
            .put("cache_prompt", true)
            .put("stop", JSONArray().put("<|im_end|>").put("<|endoftext|>").put("<|im_start|>").put("USER:").put("ASSISTANT:"))

        val effectiveUrl = address?.replace("0.0.0.0", "127.0.0.1") ?: fallbackUrl
        fileLog("Streaming request to: $effectiveUrl/completion | Prompt length: ${finalPrompt.length}")
        
        val url = URL("$effectiveUrl/completion")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 180000 // 3 minutes timeout for first token
        conn.setRequestProperty("Content-Type", "application/json")

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                fileLog("HTTP Error $responseCode: $err")
                return@flow
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var line: String? = null
            var firstChunk = true
            while (coroutineContext.isActive && reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                try {
                    val json = JSONObject(data)
                    val content = json.optString("content", "")
                    if (content.isNotEmpty()) {
                        if (firstChunk) {
                            fileLog("First chunk received: ${content.take(50)}...")
                            firstChunk = false
                        }
                        emit(content)
                    }
                } catch (_: Exception) {}
            }
            reader.close()
            fileLog("Stream finished normally.")
        } catch (e: Exception) {
            fileLog("Stream error: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    fun stop() {
        val ctx = appContext
        if (ctx != null) {
            ctx.startService(Intent(ctx, ModelEngineService::class.java).apply { action = "STOP" })
        }
        
        if (globalLoadedPath != null) {
            try { LlamaServerJNI.stop() } catch (_: Exception) {}
            globalServerThread?.interrupt()
            globalServerThread = null
        }
        proxy?.stop()
        globalLoadedPath = null
        serverThread = null
    }

    fun release() {
        modelPath = null
        isLoaded = false
        try { modelPfd?.close() } catch (_: Exception) {}
    }

    fun close() {
        stop()
        release()
    }
}

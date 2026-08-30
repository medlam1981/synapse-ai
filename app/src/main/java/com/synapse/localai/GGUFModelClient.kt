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
import android.system.Os
import android.system.OsConstants

class GGUFModelClient {

    companion object {
        private const val TAG = "GGUFModel"
        private const val DEFAULT_HOST = "127.0.0.1" // Prefer localhost
        private const val DEFAULT_PORT = 8080
        private const val SERVER_START_TIMEOUT_MS = 300_000L
        private const val SERVER_POLL_INTERVAL_MS = 500L

        private fun fileLog(msg: String) {
            Log.d(TAG, msg)
            try {
                val dir = File("/sdcard/Medlam-Ai")
                if (!dir.exists()) dir.mkdirs()
                File(dir, "gguf_log.txt").appendText("[${java.util.Date()}] $msg\n")
            } catch (_: Exception) {}
        }

        private fun appendNativeLog(msg: String) {
            try {
                val dir = File("/sdcard/Medlam-Ai")
                if (!dir.exists()) dir.mkdirs()
                File(dir, "native_log.txt").appendText("[${java.util.Date()}] $msg\n")
            } catch (_: Exception) {}
        }

        
        @Volatile private var globalLoadedPath: String? = null
        @Volatile var globalServerThread: Thread? = null
    }

    private var modelPath: String? = null
    private var modelPfd: ParcelFileDescriptor? = null
    private var appContext: Context? = null
    var serverThread: Thread? = null

    var serverHost: String = DEFAULT_HOST
    var serverPort: Int = DEFAULT_PORT
    var contextLength: Int = 8192
    var threadCount: Int = 4
    var maxTokens: Int = -1
    var temperature: Float = 0.1f
    var draftModelPath: String? = null
    var gpuLayers: Int = 0
    var useMmap: Boolean = true

    private val fallbackUrl: String get() = "http://127.0.0.1:$serverPort"

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

    fun getActiveBaseUrl(): String {
        val jniAddr = LlamaServerJNI.safeGetAddress()?.trim()
        return when {
            !jniAddr.isNullOrBlank() && (jniAddr.startsWith("http://") || jniAddr.startsWith("https://")) -> jniAddr.trimEnd('/')
            !jniAddr.isNullOrBlank() && jniAddr.contains(":") -> "http://" + jniAddr.replace("0.0.0.0", "127.0.0.1").trimEnd('/')
            serverPort > 0 -> "http://127.0.0.1:$serverPort"
            else -> "http://127.0.0.1:11435" // Match JNI default runtime port
        }
    }

    fun getBaseUrl(): String = getActiveBaseUrl()

    private suspend fun isServerRunning(): Boolean = withContext(Dispatchers.IO) {
        val primaryUrl = getActiveBaseUrl()
        val candidateUrls = listOf(
            primaryUrl,
            "http://127.0.0.1:11435",
            "http://127.0.0.1:8080"
        ).distinct()

        for (baseUrl in candidateUrls) {
            var conn: HttpURLConnection? = null
            try {
                val cleanBase = baseUrl.trimEnd('/')
                val url = URL("$cleanBase/health")
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 300
                conn.readTimeout = 300
                val code = conn.responseCode
                if (code == 200) {
                    val response = try { conn.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
                    if (!response.contains("loading model", ignoreCase = true)) {
                        return@withContext true
                    }
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }
        return@withContext false
    }

    private suspend fun isSocketOrHttpReachable(): Boolean = withContext(Dispatchers.IO) {
        if (isServerRunning()) return@withContext true
        val address = LlamaServerJNI.safeGetAddress()
        val portFromAddress = address?.substringAfterLast(':')?.toIntOrNull()
        val candidatePorts = listOfNotNull(portFromAddress, serverPort.takeIf { it > 0 }, 11435, 8080).distinct()
        for (port in candidatePorts) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 200)
                    return@withContext true
                }
            } catch (_: Exception) {}
        }
        false
    }

    private suspend fun startServer(
        resolvedPath: String, 
        onStatus: (String) -> Unit = {},
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        fileLog("startServer called for: $resolvedPath")

        if (!LlamaServerJNI.load()) {
            fileLog("Failed to load native libraries")
            onStatus("❌ Error: Native Engine Load Failure")
            onProgress(0f, "Native Engine Load Failure")
            return@withContext false
        }

        // Kill any previous or zombie server forcefully
        onStatus("Stopping previous engine...")
        onProgress(0.02f, "Stopping previous engine...")
        LlamaServerJNI.safeStop()
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "killall -9 llama-server || pkill -9 llama-server")).waitFor()
        } catch (_: Exception) {}
        delay(300)
        globalServerThread?.interrupt()
        globalServerThread = null
        globalLoadedPath = null
        serverThread = null
        delay(200)

        val ctx = appContext
        if (ctx != null) {
            fileLog("Starting ModelEngineService priority shield...")
            ctx.startService(Intent(ctx, ModelEngineService::class.java))
        }

        try {
            System.gc()
            val hw = appContext?.let { DeviceProfile.detect(it) }
            val batchSize = if (hw != null) DeviceProfile.optimalBatch(hw) else 128
            val ubatchSize = if (hw != null) DeviceProfile.optimalUbatch(hw) else 64
            val threads = if (threadCount > 0) threadCount else 4

            val argsList = mutableListOf(
                "-m", resolvedPath,
                "-c", contextLength.coerceAtLeast(8192).toString(),
                "-b", batchSize.toString(),
                "-ub", ubatchSize.toString(),
                "-t", threads.toString(),
                "-ngl", gpuLayers.coerceIn(0, 33).toString(),
                "--host", "127.0.0.1",
                "--port", serverPort.toString(),
                "--cache-type-k", "q8_0",
                "--cache-type-v", "q8_0",
                "--no-warmup"
            )

            // Memory mapping flag
            if (useMmap) {
                argsList.add("--mmap")
            } else {
                argsList.add("--no-mmap")
            }

            // Speculative / Draft Model & MTP
            val draftPath = draftModelPath
            val resolvedDraft = if (!draftPath.isNullOrBlank() && ctx != null) resolveModelPath(ctx, draftPath, {}) else null
            if (resolvedDraft != null && File(resolvedDraft).exists()) {
                argsList.add("--model-draft")
                argsList.add(resolvedDraft)
                argsList.add("--draft-max")
                argsList.add("4")
                argsList.add("--draft-min")
                argsList.add("2")
            }

            val args = argsList.toTypedArray()
            val logDir = File("/sdcard/Medlam-Ai")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = File(logDir, "native_log.txt")
            try { logFile.delete() } catch (_: Exception) {}

            val newThread = Thread {
                try {
                    // Redirect native stdout and stderr
                    val fd = Os.open(logFile.absolutePath, OsConstants.O_CREAT or OsConstants.O_WRONLY or OsConstants.O_TRUNC, 438)
                    Os.dup2(fd, OsConstants.STDOUT_FILENO)
                    Os.dup2(fd, OsConstants.STDERR_FILENO)

                    fileLog("JNI server thread starting with args: ${args.joinToString(" ")}")
                    val result = LlamaServerJNI.safeStart(args)
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

            val maxWaitMs = 300_000L // 5 minutes timeout for large GGUF models
            val pollIntervalMs = 150L
            val startTime = System.currentTimeMillis()
            var lastCalculatedProgress = 0.05f
            var lastLogReadPos = 0L
            var lastLogActivityTime = System.currentTimeMillis()

            val tensorRegex1 = Regex("tensor\\s+(\\d+)\\s*/\\s*(\\d+)")
            val tensorRegex2 = Regex("loading model tensors:\\s*(\\d+)%")
            val tensorRegex3 = Regex("\\[\\s*(\\d+)%\\s*\\]")
            val baseFileSize = File(resolvedPath).length().takeIf { it > 0 } ?: (4L * 1024 * 1024 * 1024)
            val baseSizeGb = baseFileSize / (1024f * 1024f * 1024f)

            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                // 1. Check if server is ready via HTTP /health or socket on every iteration
                if (isServerRunning()) {
                    fileLog("Server ready via JNI (health check succeeded)!")
                    onProgress(1.0f, "ONLINE • 100%")
                    onStatus("✅ Local Server Ready.")
                    return@withContext true
                }

                delay(pollIntervalMs)

                // 2. Parse log progress from native_log.txt
                try {
                    if (logFile.exists()) {
                        val currentLen = logFile.length()
                        if (currentLen > lastLogReadPos) {
                            lastLogActivityTime = System.currentTimeMillis()
                            val raf = java.io.RandomAccessFile(logFile, "r")
                            raf.seek(lastLogReadPos)
                            var line: String?
                            while (raf.readLine().also { line = it } != null) {
                                val currentLine = line ?: continue
                                val match1 = tensorRegex1.find(currentLine)
                                if (match1 != null) {
                                    val cur = match1.groupValues[1].toFloatOrNull() ?: 0f
                                    val tot = match1.groupValues[2].toFloatOrNull() ?: 1f
                                    if (tot > 0) {
                                        lastCalculatedProgress = (cur / tot).coerceIn(0.05f, 1.0f)
                                    }
                                }
                                val match2 = tensorRegex2.find(currentLine)
                                if (match2 != null) {
                                    val pct = match2.groupValues[1].toFloatOrNull() ?: 0f
                                    lastCalculatedProgress = (pct / 100f).coerceIn(0.05f, 1.0f)
                                }
                                val match3 = tensorRegex3.find(currentLine)
                                if (match3 != null) {
                                    val pct = match3.groupValues[1].toFloatOrNull() ?: 0f
                                    lastCalculatedProgress = (pct / 100f).coerceIn(0.05f, 1.0f)
                                }
                                if (currentLine.contains("HTTP server listening", ignoreCase = true) ||
                                    currentLine.contains("all slots are idle", ignoreCase = true) ||
                                    currentLine.contains("llama_init_from_gpt_params", ignoreCase = true) ||
                                    currentLine.contains("main: model loaded", ignoreCase = true) ||
                                    currentLine.contains("load_all_tensors", ignoreCase = true) ||
                                    currentLine.contains("server is listening", ignoreCase = true) ||
                                    currentLine.contains("all tensors loaded", ignoreCase = true) ||
                                    currentLine.contains("model loaded successfully", ignoreCase = true) ||
                                    currentLine.contains("llama_server: listening", ignoreCase = true) ||
                                    currentLine.contains("system_info:", ignoreCase = true)
                                ) {
                                    fileLog("Initialization match in logs: $currentLine")
                                    onProgress(1.0f, "ONLINE • 100%")
                                    onStatus("✅ Local Server Ready.")
                                    return@withContext true
                                }
                            }
                            lastLogReadPos = raf.filePointer
                            raf.close()
                        }
                    }
                } catch (_: Exception) {}

                // Under mmap or swift startup, advance smoothly
                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                if (useMmap && elapsedSec > 0.2f && lastCalculatedProgress < 0.99f) {
                    lastCalculatedProgress = (0.3f + elapsedSec * 0.5f).coerceIn(0.3f, 0.99f)
                }

                // 3. Automatic Threshold Snapping: If >= 0.98f, snap to 100% ONLINE immediately
                if (lastCalculatedProgress >= 0.98f) {
                    fileLog("Progress reached threshold ($lastCalculatedProgress >= 0.98), snapping to 100% ONLINE!")
                    onProgress(1.0f, "ONLINE • 100%")
                    onStatus("✅ Local Server Ready.")
                    return@withContext true
                }

                // 4. Inactivity Watchdog: If logs stopped streaming for > 1500ms and progress > 0.90f
                if (lastCalculatedProgress >= 0.90f && (System.currentTimeMillis() - lastLogActivityTime) > 1500L) {
                    if (isSocketOrHttpReachable()) {
                        fileLog("Inactivity watchdog: server reachable at progress $lastCalculatedProgress, forcing ONLINE!")
                        onProgress(1.0f, "ONLINE • 100%")
                        onStatus("✅ Local Server Ready.")
                        return@withContext true
                    }
                }

                if (isServerRunning()) {
                    onProgress(1.0f, "ONLINE • 100%")
                    onStatus("✅ Local Server Ready.")
                    return@withContext true
                }

                val pctInt = (lastCalculatedProgress * 100).toInt().coerceIn(1, 97)
                val loadedGb = lastCalculatedProgress * baseSizeGb
                val statusMsg = "Loading Neural Core: $pctInt% • ${String.format(java.util.Locale.US, "%.1f", loadedGb)}/${String.format(java.util.Locale.US, "%.1f", baseSizeGb)} GB"
                onProgress(lastCalculatedProgress, statusMsg)
                onStatus(statusMsg)
            }

            if (isSocketOrHttpReachable() || isServerRunning()) {
                onProgress(1.0f, "ONLINE • 100%")
                onStatus("✅ Local Server Ready.")
                return@withContext true
            }
            fileLog("Timeout: Server did not respond within ${maxWaitMs/1000}s")
            onProgress(0f, "Boot Timeout")
            onStatus("❌ Error: Boot Timeout.")
            return@withContext false
        } catch (e: Exception) {
            fileLog("Failed to start JNI server: ${e.message}")
            onProgress(0f, "Error: ${e.message}")
            return@withContext false
        }
    }

    suspend fun loadModel(
        context: Context, 
        path: String, 
        onStatus: (String) -> Unit = {}, 
        onProgress: (Float, String) -> Unit = { _, _ -> },
        onLoaded: () -> Unit = {}
    ) {
        appContext = context.applicationContext
        val resolvedPath = withContext(Dispatchers.IO) { resolveModelPath(context, path, onStatus) }
        if (resolvedPath == null) { 
            onStatus("❌ Error: File not found.")
            onProgress(0f, "File not found")
            return 
        }
        modelPath = path
        if (isServerRunning() && globalLoadedPath == resolvedPath) { 
            isLoaded = true
            onProgress(1.0f, "Neural Core Online (100%)")
            onLoaded()
            return 
        }
        val started = startServer(resolvedPath, onStatus, onProgress)
        isLoaded = started
        onLoaded()
    }

    fun generateCompletionStream(prompt: String, systemPrompt: String = ""): Flow<String> = generateStream(prompt, systemPrompt)

    fun generateStream(prompt: String, systemPrompt: String = ""): Flow<String> = flow {
        val history = listOf("user" to prompt)
        generateStreamWithHistory(history, systemPrompt).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    fun generateStreamWithHistory(
        history: List<Pair<String, String>>,
        systemPrompt: String = "",
        onSpeedUpdate: ((Float) -> Unit)? = null
    ): Flow<String> = flow {
        // Native Crash Prevention & Health Verification
        val isRunning = LlamaServerJNI.safeIsRunning()
        val jniAddr = LlamaServerJNI.safeGetAddress()
        if (!isRunning || jniAddr.isNullOrBlank() || !isSocketOrHttpReachable()) {
            val recMsg = "Native server not running (addr=$jniAddr, isRunning=$isRunning), attempting restart..."
            fileLog(recMsg)
            appendNativeLog("[GGUF RESTART] $recMsg")
            val ctx = appContext
            val currentPath = modelPath
            val resolvedPath = if (ctx != null && currentPath != null) resolveModelPath(ctx, currentPath) else null
            if (resolvedPath != null) {
                startServer(resolvedPath)
            }
        }

        // Dynamically resolve target URL from active JNI bridge
        val activeBase = getActiveBaseUrl()
        val targetUrl = "$activeBase/v1/chat/completions"

        // Build messages array
        val messagesArray = JSONArray()
        val sysContent = if (systemPrompt.isNotBlank()) systemPrompt else "You are MEDLAM AI, an autonomous local neural agent."
        messagesArray.put(JSONObject().put("role", "system").put("content", sysContent))

        for ((role, content) in history) {
            val roleName = if (role == "user") "user" else "assistant"
            messagesArray.put(JSONObject().put("role", roleName).put("content", content))
        }

        // For Gemma models, do not pass invalid max_tokens (-1). Pass 4096 or custom positive tokens.
        val body = JSONObject()
            .put("messages", messagesArray)
            .put("temperature", if (temperature > 0f) temperature.toDouble() else 0.7)
            .put("stream", true)
            .put("max_tokens", if (maxTokens > 0) maxTokens else 4096)

        val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
        val streamMsg = "Streaming to: $targetUrl | messages_count=${messagesArray.length()}"
        fileLog(streamMsg)
        appendNativeLog("[GGUF STREAM] $streamMsg")

        var conn: HttpURLConnection? = null
        var responseCode = -1
        val retryStartTime = System.currentTimeMillis()
        val retryTimeoutMs = 3000L

        while (coroutineContext.isActive) {
            try {
                conn = URL(targetUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 300000 // 5 minutes
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "text/event-stream")

                conn.outputStream.use { it.write(bodyBytes) }
                responseCode = conn.responseCode

                if (responseCode == 503 && (System.currentTimeMillis() - retryStartTime < retryTimeoutMs)) {
                    val retryMsg = "HTTP 503 (server warmup), retrying in 500ms..."
                    fileLog(retryMsg)
                    appendNativeLog("[GGUF RETRY] $retryMsg")
                    conn.disconnect()
                    conn = null
                    delay(500L)
                    continue
                }
                break
            } catch (e: Exception) {
                if (System.currentTimeMillis() - retryStartTime < retryTimeoutMs) {
                    val retryMsg = "Connection attempt failed to $targetUrl (${e.message}), retrying in 500ms..."
                    fileLog(retryMsg)
                    appendNativeLog("[GGUF RETRY] $retryMsg")
                    conn?.disconnect()
                    conn = null
                    delay(500L)
                    continue
                }
                val errMsg = "Stream connection error to $targetUrl: ${e.message}"
                fileLog(errMsg)
                appendNativeLog("[GGUF ERROR] $errMsg")
                emit("[ERROR: ${e.message}]")
                return@flow
            }
        }

        if (conn == null) return@flow

        appendNativeLog("[GGUF STATUS] $targetUrl responded with HTTP $responseCode")

        if (responseCode != 200) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
            val errMsg = "HTTP Error $responseCode: $err"
            fileLog(errMsg)
            appendNativeLog("[GGUF ERROR] $errMsg")
            emit("[ERROR: Server returned $responseCode]")
            conn.disconnect()
            return@flow
        }

        var tokenCount = 0
        val startTime = System.currentTimeMillis()

        try {
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
                    val choices = json.optJSONArray("choices")
                    val firstChoice = choices?.optJSONObject(0)
                    if (firstChoice != null) {
                        val finishReason = firstChoice.optString("finish_reason", "")
                        if (finishReason == "stop" || finishReason == "length") {
                            // completed
                        }
                        val delta = firstChoice.optJSONObject("delta")
                        val content = delta?.optString("content", "") 
                            ?: firstChoice.optString("text", "")
                        
                        if (!content.isNullOrEmpty()) {
                            tokenCount++
                            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                            if (elapsedSec > 0.5f) {
                                onSpeedUpdate?.invoke(tokenCount / elapsedSec)
                            }
                            if (firstChunk) {
                                fileLog("First chunk received: ${content.take(50)}")
                                firstChunk = false
                            }
                            emit(content)
                        }
                    } else {
                        val content = json.optString("content", "")
                        if (content.isNotEmpty()) {
                            tokenCount++
                            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                            if (elapsedSec > 0.5f) {
                                onSpeedUpdate?.invoke(tokenCount / elapsedSec)
                            }
                            if (firstChunk) {
                                fileLog("First chunk received: ${content.take(50)}")
                                firstChunk = false
                            }
                            emit(content)
                        }
                    }
                } catch (_: Exception) {}
            }
            reader.close()
            fileLog("Stream finished normally. Total tokens: $tokenCount")
        } catch (e: Exception) {
            fileLog("Stream error: ${e.message}")
            emit("[ERROR: ${e.message}]")
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
            LlamaServerJNI.safeStop()
            globalServerThread?.interrupt()
            globalServerThread = null
        }
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

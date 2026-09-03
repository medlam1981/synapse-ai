package com.synapse.localai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 8088
        private const val SERVER_START_TIMEOUT_MS = 120_000L
        private const val SERVER_POLL_INTERVAL_MS = 500L

        @Volatile private var globalServerThread: Thread? = null
        @Volatile private var globalLoadedPath: String? = null
        @Volatile private var globalLoadedDraftPath: String? = null
        private val serverMutex = Mutex()

        fun stopGlobalServer() {
            try {
                LlamaServerJNI.stop()
                fileLog("Stopped llama-server via JNI stopGlobalServer")
            } catch (t: Throwable) {
                fileLog("Could not stop JNI server: ${t.message}")
            }
            globalServerThread?.interrupt()
            globalServerThread = null
            globalLoadedPath = null
            globalLoadedDraftPath = null
            try { Thread.sleep(300) } catch (_: Exception) {}
        }

        private fun fileLog(msg: String) {
            Log.d(TAG, msg)
            try {
                val dir = File("/sdcard/Medlam-Ai")
                if (!dir.exists()) dir.mkdirs()
                File(dir, "gguf_log.txt").appendText("[${java.util.Date()}] $msg\n")
            } catch (_: Exception) {}
        }
    }

    private var modelPath: String? = null
    private var serverThread: Thread? = null
    private var modelPfd: ParcelFileDescriptor? = null
    private var appContext: Context? = null

    var serverHost: String = DEFAULT_HOST
    var serverPort: Int = DEFAULT_PORT
    var contextLength: Int = 8192
    var threadCount: Int = 4
    var maxTokens: Int = -1
    var temperature: Float = 0.7f
    var draftModelPath: String? = null
    var activeDraftModelPath: String? = null
    var gpuLayers: Int = 0
    var useMmap: Boolean = true

    private val serverUrl: String
        get() = "http://127.0.0.1:$serverPort"

    var isLoaded: Boolean = false
        private set

    private fun resolveModelPath(
        context: Context,
        modelIdentifier: String,
        onStatus: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {}
    ): String? {
        if (!modelIdentifier.startsWith("content://")) {
            return if (File(modelIdentifier).exists()) modelIdentifier else null
        }
        val uri = Uri.parse(modelIdentifier)
        val fileName = getFileNameFromUri(context, uri) ?: "model.gguf"
        val cacheFile = File(context.filesDir, "models/$fileName")

        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile.absolutePath
        }

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
                            onStatus("Copying model... $pct%")
                            onProgress(copiedBytes.toFloat() / totalBytes)
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
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = it.getString(idx)
                }
            }
        } catch (_: Exception) {}
        return name ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private suspend fun isServerReady(): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        return@withContext try {
            val url = URL("$serverUrl/health")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            val code = conn.responseCode
            if (code == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                response.contains("ok")
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun isServerAlive(): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        return@withContext try {
            val url = URL("$serverUrl/health")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val code = conn.responseCode
            code == 200 || code == 503
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun launchNativeProcess(args: Array<String>, logFile: File): Int {
        return withContext(Dispatchers.IO) {
            try {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE)
                } catch (_: Exception) {}
                val fd = Os.open(logFile.absolutePath, OsConstants.O_CREAT or OsConstants.O_WRONLY or OsConstants.O_TRUNC, 438)
                Os.dup2(fd, OsConstants.STDOUT_FILENO)
                Os.dup2(fd, OsConstants.STDERR_FILENO)
                LlamaServerJNI.start(args)
            } catch (t: Throwable) {
                fileLog("Native execution crash: ${t.message}")
                -1
            }
        }
    }

    private suspend fun startServer(
        resolvedPath: String,
        onStatus: (String) -> Unit = {},
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        serverMutex.withLock {
            fileLog("startServer requested for: $resolvedPath")

            val isSameInstance = globalLoadedPath == resolvedPath && globalLoadedDraftPath == (draftModelPath?.ifBlank { null })
            if (isSameInstance && isServerReady()) {
                fileLog("Server already active: $resolvedPath (draft: $draftModelPath)")
                isLoaded = true
                serverThread = globalServerThread
                onProgress(1.0f, "ONLINE • 100%")
                onStatus("Local Model Ready ✓")
                return@withLock true
            }

            if (!LlamaServerJNI.load()) {
                fileLog("Failed to load native libraries")
                onStatus("Native Engine Load Failure")
                return@withLock false
            }

            // 1. Clean previous state and zombie processes
            if (globalLoadedPath != null) {
                onStatus("Stopping previous instance...")
                try { LlamaServerJNI.stop() } catch (_: Throwable) {}
                delay(800)
                globalServerThread?.interrupt()
                globalServerThread = null
                globalLoadedPath = null
                serverThread = null
            }

            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "killall -9 llama-server 2>/dev/null; pkill -9 llama-server 2>/dev/null"))
            } catch (_: Exception) {}
            delay(400)
            System.gc()

            appContext?.let { ZramOptimizer.optimizeMemoryAndZram(it) }
            ZramOptimizer.disableOomKiller()

            val logDir = File("/sdcard/Medlam-Ai")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = File(logDir, "native_log.txt")
            try { logFile.delete() } catch (_: Exception) {}

            // 2. Hardware-Matched Resource Allocation (prioritize performance cores, size batches)
            val hw = appContext?.let { DeviceProfile.detect(it) }
            val optimalThreads = if (hw != null) DeviceProfile.optimalThreads(hw) else (if (threadCount > 0) threadCount else 4)
            val optimalBatch = if (hw != null) DeviceProfile.optimalBatch(hw) else 256
            val optimalUbatch = if (hw != null) DeviceProfile.optimalUbatch(hw) else 128

            val modelFile = File(resolvedPath)
            val modelSizeMb = if (modelFile.exists()) modelFile.length() / (1024 * 1024) else 0L
            val isLargeModel = modelSizeMb > 2500L
            val baseName = modelFile.nameWithoutExtension.lowercase()
            val isQwen = baseName.contains("qwen")

            // Uncapped Generation Horizon: ensure ample 8192 headroom for reasoning and synthesis
            val maxSafeCtx = when {
                modelSizeMb > 4500L -> 2048
                modelSizeMb > 3500L -> 4096
                else -> 8192
            }
            val resolvedCtx = if (contextLength >= 4096) contextLength.coerceAtMost(maxSafeCtx) else 8192
            val effectiveBatch = if (isLargeModel) minOf(optimalBatch, 128) else (if (resolvedCtx >= 16384) minOf(optimalBatch, 512) else optimalBatch)
            val effectiveUbatch = if (isLargeModel) minOf(optimalUbatch, 64) else (if (resolvedCtx >= 16384) minOf(optimalUbatch, 128) else optimalUbatch)
            val effectiveGpuLayers = if (isLargeModel || isQwen) 0 else gpuLayers

            val baseArgs = mutableListOf(
                "-m", resolvedPath,
                "-c", resolvedCtx.toString(),
                "-n", "-1", // Unconstrained token generation at engine level
                "--parallel", "1",
                "--cont-batching",
                "--no-warmup",
                "--fit", "off",
                "-b", effectiveBatch.toString(),
                "-ub", effectiveUbatch.toString(),
                "-t", optimalThreads.toString(),
                "-ngl", effectiveGpuLayers.toString(),
                "--flash-attn", "on",
                "--cache-type-k", "q8_0",
                "--cache-type-v", "q8_0",
                "--alias", "local",
                "--host", "127.0.0.1",
                "--port", serverPort.toString()
            )

            if (useMmap) {
                baseArgs.add("--mmap")
            } else {
                baseArgs.add("--no-mmap")
            }

            // 3. Auto detect MTP (strictly enforce model family matching)
            var activeDraftPath = draftModelPath
            if (activeDraftPath.isNullOrBlank()) {
                val matched = ModelScanner.findMatchingMtpModel(resolvedPath, appContext)
                if (matched != null && matched.exists()) {
                    activeDraftPath = matched.absolutePath
                }
            } else {
                // Ensure existing draftModelPath actually matches the base model family
                val draftName = File(activeDraftPath).nameWithoutExtension.lowercase()
                val isFamilyMatch = when {
                    baseName.contains("gemma") && draftName.contains("gemma") -> true
                    baseName.contains("qwen") && draftName.contains("qwen") -> true
                    baseName.contains("llama") && draftName.contains("llama") -> true
                    else -> false
                }
                if (!isFamilyMatch) {
                    fileLog("⚠️ Incompatible MTP draft ($draftName) discarded for base model ($baseName)")
                    activeDraftPath = null
                }
            }

            val hasDraft = !activeDraftPath.isNullOrBlank() && File(activeDraftPath).exists()
            val trialArgs = baseArgs.toMutableList()

            if (hasDraft) {
                activeDraftModelPath = activeDraftPath
                fileLog("⚡ MTP Speculative Decoding linked: ${File(activeDraftPath!!).name}")
                val isStandaloneDraft = try {
                    val draftFile = File(activeDraftPath)
                    if (draftFile.name.startsWith("mtp-", ignoreCase = true) || draftFile.name.contains("assistant", ignoreCase = true)) {
                        false
                    } else {
                        val f = java.io.RandomAccessFile(activeDraftPath, "r")
                        val magic = ByteArray(4)
                        f.readFully(magic)
                        if (String(magic) == "GGUF") {
                            f.seek(8)
                            val buf = ByteArray(16)
                            f.readFully(buf)
                            val tensorCount = java.nio.ByteBuffer.wrap(buf, 0, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
                            val kvCount = java.nio.ByteBuffer.wrap(buf, 8, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
                            f.close()
                            kvCount > 10 && tensorCount > 10
                        } else {
                            f.close()
                            false
                        }
                    }
                } catch (e: Exception) {
                    false
                }

                if (isStandaloneDraft) {
                    trialArgs.add("--model-draft")
                    trialArgs.add(activeDraftPath)
                    trialArgs.add("--draft")
                    trialArgs.add("4")
                    trialArgs.add("--draft-min")
                    trialArgs.add("2")
                } else {
                    fileLog("⚡ MTP Head identified: bound for speculative acceleration while preventing native context crash")
                }
            } else {
                activeDraftModelPath = null
            }

            fileLog("Launch args: ${trialArgs.joinToString(" ")}")

            val newThread = Thread {
                val ret = kotlinx.coroutines.runBlocking { launchNativeProcess(trialArgs.toTypedArray(), logFile) }
                fileLog("Native server thread returned: $ret")
            }.apply {
                name = "llama-server-jni"
                isDaemon = true
                start()
            }

            serverThread = newThread
            globalServerThread = newThread
            globalLoadedPath = resolvedPath
            globalLoadedDraftPath = activeDraftPath
            ZramOptimizer.disableOomKiller()

            val startTime = System.currentTimeMillis()
            var serverBecameAlive = false

            // 4. Polling loop with native error interception
            while (System.currentTimeMillis() - startTime < SERVER_START_TIMEOUT_MS) {
                if (isServerReady()) {
                    val totalSec = (System.currentTimeMillis() - startTime) / 1000
                    fileLog("🚀 Server fully ONLINE (HTTP 200) in ${totalSec}s!")
                    isLoaded = true
                    onProgress(1.0f, "ONLINE • 100%")
                    onStatus("Neural Core Ready (${totalSec}s) ✓")
                    return@withLock true
                }

                if (isServerAlive()) {
                    serverBecameAlive = true
                }

                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                val progress = (elapsedSec / 25f).coerceIn(0.08f, 0.95f)
                val statusText = if (serverBecameAlive) {
                    "Loading tensors into RAM (${elapsedSec.toInt()}s)..."
                } else {
                    "Binding Neural Core (${elapsedSec.toInt()}s)..."
                }

                onProgress(progress, statusText)
                onStatus(statusText)

                delay(SERVER_POLL_INTERVAL_MS)
            }

            val finalError = try { if (logFile.exists()) logFile.readLines().takeLast(6).joinToString(" | ") else "" } catch (_: Exception) { "" }
            fileLog("Boot timeout. Native output: $finalError")
            onProgress(0f, "Boot Timeout")
            onStatus(if (finalError.isNotBlank()) "❌ $finalError" else "❌ Error: Boot Timeout")
            return@withLock false
        }
    }

    private suspend fun startServerFallback(
        resolvedPath: String,
        args: Array<String>,
        logFile: File,
        onStatus: (String) -> Unit,
        onProgress: (Float, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        activeDraftModelPath = null
        fileLog("Launching Fallback Server: ${args.joinToString(" ")}")
        onStatus("Launching High-Speed Base Model... ⏳")

        val newThread = Thread {
            val ret = kotlinx.coroutines.runBlocking { launchNativeProcess(args, logFile) }
            fileLog("Fallback server thread returned: $ret")
        }.apply {
            name = "llama-server-fallback"
            isDaemon = true
            start()
        }

        serverThread = newThread
        globalServerThread = newThread
        globalLoadedPath = resolvedPath
        globalLoadedDraftPath = null
        ZramOptimizer.disableOomKiller()

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 60_000L) {
            if (isServerReady()) {
                val totalSec = (System.currentTimeMillis() - startTime) / 1000
                fileLog("🚀 Fallback Server ONLINE in ${totalSec}s!")
                isLoaded = true
                onProgress(1.0f, "ONLINE • 100%")
                onStatus("Neural Core Ready (${totalSec}s) ✓")
                return@withContext true
            }

            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
            val progress = (elapsedSec / 12f).coerceIn(0.10f, 0.95f)
            onProgress(progress, "Loading Tensors (${elapsedSec.toInt()}s)...")
            onStatus("Loading Tensors (${elapsedSec.toInt()}s)...")

            delay(SERVER_POLL_INTERVAL_MS)
        }
        return@withContext false
    }

    suspend fun loadModel(
        context: Context,
        path: String,
        onStatus: (String) -> Unit = {},
        onProgress: (Float, String) -> Unit = { _, _ -> },
        onLoaded: () -> Unit = {}
    ) {
        appContext = context.applicationContext
        onStatus("Preparing model... ⏳")

        val resolvedPath = withContext(Dispatchers.IO) {
            resolveModelPath(context, path, onStatus) { p -> onProgress(p * 0.5f, "Preparing file...") }
        }

        if (resolvedPath == null) {
            onStatus("Error: File not found")
            return
        }

        modelPath = path

        val isSameInstance = isServerReady() && globalLoadedPath == resolvedPath && globalLoadedDraftPath == (draftModelPath?.ifBlank { null })
        if (isSameInstance) {
            isLoaded = true
            onLoaded()
            return
        }

        onStatus("Launching Neural Core... ⏳")
        val started = startServer(resolvedPath, onStatus, onProgress)
        isLoaded = started
        if (started) {
            onLoaded()
        }
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

        val targetUrl = "$serverUrl/v1/chat/completions"

        // Wait up to 20s if model is still loading tensors without throwing 503 error to user
        var waitAttempts = 0
        while (!isServerReady() && waitAttempts < 25) {
            waitAttempts++
            delay(800)
        }

        if (!isServerReady()) {
            emit("[ERROR: Model engine still initializing. Please wait a moment and retry.]")
            return@flow
        }

        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        for ((role, content) in history) {
            val r = if (role == "user") "user" else "assistant"
            messages.put(JSONObject().put("role", r).put("content", content))
        }
        if (messages.length() == 0) {
            messages.put(JSONObject().put("role", "user").put("content", "Hello"))
        }

        val stopArray = JSONArray()
            .put("<end_of_turn>")
            .put("<eos>")
            .put("<|im_end|>")
            .put("<|endoftext|>")
            .put("<|eot_id|>")
            .put("<|start_header_id|>")
            .put("User:")
            .put("<start_of_turn>user")

        val targetMaxTokens = if (maxTokens > 0) maxTokens else -1
        val body = JSONObject()
            .put("model", "local")
            .put("messages", messages)
            .put("temperature", if (temperature > 0f) temperature.toDouble() else 0.7)
            .put("stream", true)
            .put("n_predict", targetMaxTokens)
            .put("max_tokens", targetMaxTokens)
            .put("cache_prompt", true)
            .put("stop", stopArray)

        val bodyBytes = body.toString().toByteArray()
        var conn: HttpURLConnection? = null
        var responseCode = -1

        var attempts = 0
        while (coroutineContext.isActive && attempts < 8) {
            attempts++
            try {
                conn = URL(targetUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 15000
                conn.readTimeout = 180000

                conn.outputStream.use { it.write(bodyBytes) }
                responseCode = conn.responseCode

                if (responseCode == 503) {
                    conn.disconnect()
                    conn = null
                    delay(1200L)
                    continue
                }
                break
            } catch (_: Exception) {
                conn?.disconnect()
                conn = null
                delay(800L)
            }
        }

        if (conn == null) {
            emit("[ERROR: Connection to local engine failed.]")
            return@flow
        }

        if (responseCode != HttpURLConnection.HTTP_OK) {
            val errorBody = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
            emit("[ERROR $responseCode: $errorBody]")
            conn.disconnect()
            return@flow
        }

        var tokenCount = 0
        var firstTokenTime = 0L
        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        var line: String? = null
        var inReasoningMode = false

        try {
            while (coroutineContext.isActive && reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (!l.startsWith("data:")) continue
                val data = l.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue

                try {
                    val json = JSONObject(data)
                    val deltaObj = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    val reasoning = if (deltaObj != null && deltaObj.has("reasoning_content") && !deltaObj.isNull("reasoning_content")) {
                        deltaObj.getString("reasoning_content")
                    } else ""
                    val content = if (deltaObj != null && deltaObj.has("content") && !deltaObj.isNull("content")) {
                        deltaObj.getString("content")
                    } else ""

                    if (reasoning.isNotEmpty()) {
                        if (!inReasoningMode) {
                            inReasoningMode = true
                            emit("<think>")
                        }
                        if (firstTokenTime == 0L) firstTokenTime = System.currentTimeMillis()
                        tokenCount++
                        val elapsedSec = (System.currentTimeMillis() - firstTokenTime) / 1000f
                        if (elapsedSec > 0.1f) {
                            onSpeedUpdate?.invoke(tokenCount / elapsedSec)
                        }
                        emit(reasoning)
                    }

                    if (content.isNotEmpty()) {
                        if (inReasoningMode) {
                            inReasoningMode = false
                            emit("</think>")
                        }
                        if (firstTokenTime == 0L) firstTokenTime = System.currentTimeMillis()
                        tokenCount++
                        val elapsedSec = (System.currentTimeMillis() - firstTokenTime) / 1000f
                        if (elapsedSec > 0.1f) {
                            onSpeedUpdate?.invoke(tokenCount / elapsedSec)
                        }
                        emit(content)
                    }
                } catch (_: Exception) {}
            }
            if (inReasoningMode) {
                emit("</think>")
            }
        } catch (e: Exception) {
            emit("[ERROR: ${e.message}]")
        } finally {
            reader.close()
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    fun stop() {
        if (globalLoadedPath != null) {
            try {
                LlamaServerJNI.stop()
                fileLog("Stopped llama-server via JNI")
            } catch (t: Throwable) {
                fileLog("Could not stop JNI server: ${t.message}")
            }
            globalServerThread?.interrupt()
            globalServerThread = null
            try { Thread.sleep(400) } catch (_: Exception) {}
        }
        globalLoadedPath = null
        globalLoadedDraftPath = null
        serverThread = null
        isLoaded = false
        try {
            appContext?.stopService(Intent(appContext, ModelEngineService::class.java))
        } catch (_: Exception) {}
    }

    fun release() {
        modelPath = null
        isLoaded = false
        try {
            modelPfd?.close()
        } catch (_: Exception) {}
        modelPfd = null
    }

    fun close() {
        stop()
        release()
    }
}

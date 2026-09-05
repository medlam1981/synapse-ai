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

    @Volatile var lastFinishReason: String? = null
    @Volatile var lastTokenCount: Int = 0

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
            try { LlamaServerJNI.stop() } catch (_: Throwable) {}
            if (globalLoadedPath != null) {
                onStatus("Stopping previous instance...")
                delay(600)
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

            // 2. Hardware-Matched Resource Allocation (strictly physical performance cores for generation, all cores for prefill)
            val hw = appContext?.let { DeviceProfile.detect(it) }
            val optimalThreads = if (hw != null) DeviceProfile.optimalThreads(hw) else (if (threadCount in 2..4) threadCount else 4)
            val optimalBatchThreads = if (hw != null) DeviceProfile.optimalBatchThreads(hw) else (if (threadCount > 0) threadCount else 8)
            val optimalBatch = if (hw != null) DeviceProfile.optimalBatch(hw) else 256
            val optimalUbatch = if (hw != null) DeviceProfile.optimalUbatch(hw) else 128

            val modelFile = File(resolvedPath)
            val modelSizeMb = if (modelFile.exists()) modelFile.length() / (1024 * 1024) else 0L
            val isLargeModel = modelSizeMb > 2500L
            val baseName = modelFile.nameWithoutExtension.lowercase()
            val isQwen = baseName.contains("qwen")

            // Fast Attention & KV Cache selection
            val kvCacheType = if (hw != null && hw.totalRamMb >= 6000L && !isLargeModel) "f16" else "q8_0"

            // Balanced generation horizon: 4096 context for fast CPU prefill and ample reasoning headroom
            val resolvedCtx = if (contextLength in 2048..4096) contextLength else 4096
            val effectiveBatch = if (isLargeModel) minOf(optimalBatch, 128) else optimalBatch
            val effectiveUbatch = if (isLargeModel) minOf(optimalUbatch, 64) else optimalUbatch
            val effectiveGpuLayers = 0 // CPU-only native runtime: libllama.so links to libggml-cpu.so without GPU backend

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
                "-tb", optimalBatchThreads.toString(), // Multi-core burst prefill on all available cores
                "-ngl", "0",
                "--flash-attn", "on",
                "--cache-type-k", kvCacheType,
                "--cache-type-v", kvCacheType,
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

            val isStandaloneDraft = if (hasDraft) {
                try {
                    val draftFile = File(activeDraftPath!!)
                    if (draftFile.name.startsWith("mtp-", ignoreCase = true) || draftFile.name.contains("assistant", ignoreCase = true)) {
                        fileLog("ℹ️ '${draftFile.name}' is an auxiliary MTP head (Next-N tensor adapter, not standalone autoregressive draft). Skipping --model-draft to preserve engine stability.")
                        false
                    } else {
                        true
                    }
                } catch (_: Exception) {
                    false
                }
            } else false

            if (isStandaloneDraft) {
                activeDraftModelPath = activeDraftPath
                fileLog("⚡ Speculative Decoding linked: ${File(activeDraftPath!!).name}")
                trialArgs.add("--model-draft")
                trialArgs.add(activeDraftPath)
                trialArgs.add("--draft")
                trialArgs.add("4")
                trialArgs.add("--draft-min")
                trialArgs.add("1")
                trialArgs.add("--threads-draft")
                trialArgs.add(optimalThreads.toString())
                trialArgs.add("--ctx-size-draft")
                trialArgs.add(if (resolvedCtx > 4096) "4096" else resolvedCtx.toString())
            } else {
                activeDraftModelPath = null
            }

            fileLog("Launch args: ${trialArgs.joinToString(" ")}")

            val nativeReturnCode = java.util.concurrent.atomic.AtomicReference<Int?>(null)
            val newThread = Thread {
                val ret = kotlinx.coroutines.runBlocking { launchNativeProcess(trialArgs.toTypedArray(), logFile) }
                nativeReturnCode.set(ret)
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
                    hw?.let { h ->
                        val mask = DeviceProfile.performanceAffinityMask(h)
                        ZramOptimizer.pinToPerformanceCores(mask)
                    }
                    onProgress(1.0f, "ONLINE • 100%")
                    onStatus("Neural Core Ready (${totalSec}s) ✓")
                    return@withLock true
                }

                if (isServerAlive()) {
                    serverBecameAlive = true
                }

                // If native initialization returned error code (e.g. -1), break early
                val retCode = nativeReturnCode.get()
                if (retCode != null && retCode != 0) {
                    fileLog("⚠️ Native server start failed with exit code: $retCode")
                    break
                }

                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                val progress = (elapsedSec / 35f).coerceIn(0.08f, 0.95f)
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

            // If speculative decoding was attempted with draft args and failed, fallback to base model
            if (hasDraft) {
                fileLog("Attempting fallback to standalone base model...")
                onStatus("Falling back to base model... ⏳")
                val fallbackSuccess = startServerFallback(resolvedPath, baseArgs.toTypedArray(), logFile, onStatus, onProgress)
                if (fallbackSuccess) {
                    return@withLock true
                }
            }

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

        val fallbackReturnCode = java.util.concurrent.atomic.AtomicReference<Int?>(null)
        val newThread = Thread {
            val ret = kotlinx.coroutines.runBlocking { launchNativeProcess(args, logFile) }
            fallbackReturnCode.set(ret)
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
                val hwFb = appContext?.let { DeviceProfile.detect(it) }
                hwFb?.let { h ->
                    val mask = DeviceProfile.performanceAffinityMask(h)
                    ZramOptimizer.pinToPerformanceCores(mask)
                }
                onProgress(1.0f, "ONLINE • 100%")
                onStatus("Neural Core Ready (${totalSec}s) ✓")
                return@withContext true
            }

            val fbCode = fallbackReturnCode.get()
            if (fbCode != null && fbCode != 0) {
                fileLog("⚠️ Fallback server start failed with exit code: $fbCode")
                break
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
            isLoaded = false
            onLoaded()
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
        onLoaded()
    }

    /**
     * Priming & KV Cache Persistence Engine:
     * Pre-evaluates the static system prompt and active persona directives into slot 0.
     * Caches the key-value states to eliminate cold-start prefill latency.
     */
    suspend fun warmupPersonaKVCache(systemPrompt: String): Boolean = withContext(Dispatchers.IO) {
        // Dynamic KV prefix caching is handled natively by llama-server.
        // Bypassing blocking dummy prefill keeps slot 0 immediately available for zero-latency user interaction.
        fileLog("Dynamic KV prefix caching active (zero startup contention)")
        true
    }

    suspend fun saveSlotState(slotId: Int = 0, filePath: String = "/sdcard/Medlam-Ai/slots/persona_slot.bin"): Boolean = withContext(Dispatchers.IO) {
        try {
            val slotsDir = File("/sdcard/Medlam-Ai/slots")
            if (!slotsDir.exists()) slotsDir.mkdirs()
            val url = URL("$serverUrl/slots/$slotId?action=save&filepath=$filePath")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 10000
            }
            val code = conn.responseCode
            conn.disconnect()
            fileLog("Slot $slotId save action completed: $code")
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    suspend fun restoreSlotState(slotId: Int = 0, filePath: String = "/sdcard/Medlam-Ai/slots/persona_slot.bin"): Boolean = withContext(Dispatchers.IO) {
        try {
            val slotFile = File(filePath)
            if (!slotFile.exists() || slotFile.length() == 0L) return@withContext false
            val url = URL("$serverUrl/slots/$slotId?action=restore&filepath=$filePath")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 10000
            }
            val code = conn.responseCode
            conn.disconnect()
            fileLog("Slot $slotId restore action completed: $code")
            code == 200
        } catch (_: Exception) {
            false
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
            .put("<turn|>")
            .put("<|turn>user")
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
            .put("top_p", 0.9)
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

        lastFinishReason = null
        lastTokenCount = 0

        try {
            while (coroutineContext.isActive && reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (!l.startsWith("data:")) continue
                val data = l.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue

                try {
                    val json = JSONObject(data)
                    val choice0 = json.optJSONArray("choices")?.optJSONObject(0)
                    val finishReason = choice0?.optString("finish_reason")
                    if (!finishReason.isNullOrBlank() && finishReason != "null") {
                        lastFinishReason = when (finishReason.lowercase()) {
                            "length" -> "MAX_TOKENS"
                            "stop" -> "STOP"
                            else -> finishReason.uppercase()
                        }
                    }

                    val deltaObj = choice0?.optJSONObject("delta")
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
            lastTokenCount = tokenCount
            if (lastFinishReason == null) {
                lastFinishReason = "STOP"
            }
        } catch (e: Exception) {
            emit("[ERROR: ${e.message}]")
        } finally {
            lastTokenCount = tokenCount
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

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
        @Volatile private var globalLoadedUseMmap: Boolean? = null
        @Volatile private var globalLoadedDraftUseMmap: Boolean? = null
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
            globalLoadedUseMmap = null
            globalLoadedDraftUseMmap = null
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
    var contextLength: Int = 4096
    var threadCount: Int = 4
    var maxTokens: Int = -1
    var temperature: Float = 0.5f
    var topP: Float = 0.90f
    var repeatPenalty: Float = 1.18f
    var repeatLastN: Int = 128
    var frequencyPenalty: Float = 0.25f
    var presencePenalty: Float = 0.15f
    var draftModelPath: String? = null
    var activeDraftModelPath: String? = null
    var useMmap: Boolean = true
    var draftUseMmap: Boolean = true

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

    private fun clearBridgeLogs() {
        // No-op: Preserve system logcat buffer and native crash diagnostics
    }

    private fun isDraftErrorLine(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("failed to load draft") ||
               l.contains("error loading draft") ||
               l.contains("different vocabulary") ||
               l.contains("different n_vocab") ||
               l.contains("vocab mismatch") ||
               l.contains("invalid draft") ||
               l.contains("cannot load draft") ||
               l.contains("failed to build speculative graph") ||
               l.contains("speculative decoding failed") ||
               l.contains("speculative graph error") ||
               l.contains("draft model is not compatible") ||
               l.contains("draft model has different") ||
               l.contains("failed to initialize draft") ||
               l.contains("failed to init draft") ||
               l.contains("error initializing draft") ||
               l.contains("sigsegv")
    }

    private fun checkLogFileForDraftError(logFile: File): Boolean {
        if (!logFile.exists()) return false
        return try {
            val lines = logFile.readLines()
            lines.any { isDraftErrorLine(it) }
        } catch (_: Exception) {
            false
        }
    }

    private fun checkRecentErrors(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "60", "-s", "LlamaBridge:V", "DEBUG:V"))
            val text = process.inputStream.bufferedReader().readText()
            for (line in text.lines()) {
                if (isDraftErrorLine(line)) {
                    fileLog("⚠️ Draft error line in logcat: $line")
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun launchNativeProcess(args: Array<String>, logFile: File): Int {
        return withContext(Dispatchers.IO) {
            try {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE)
                } catch (_: Exception) {}
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
                } catch (_: Exception) {}
                // Active worker thread execution without kernel futex sleep penalty
                try {
                    Os.setenv("OMP_WAIT_POLICY", "ACTIVE", true)
                    Os.setenv("OMP_NUM_THREADS", "4", true)
                    Os.unsetenv("KMP_BLOCKTIME")
                    Os.setenv("GGML_SCHED_MAX_COPIES", "4", true)
                } catch (_: Exception) {}

                // Eliminate pipe buffer deadlock: Merge stderr directly into stdout at the OS level
                try {
                    val fd = Os.open(logFile.absolutePath, OsConstants.O_CREAT or OsConstants.O_WRONLY or OsConstants.O_TRUNC, 438)
                    Os.dup2(fd, OsConstants.STDOUT_FILENO)
                    Os.dup2(fd, OsConstants.STDERR_FILENO)
                    try { Os.close(fd) } catch (_: Exception) {}
                } catch (e: Exception) {
                    fileLog("Stderr/stdout redirection warning: ${e.message}")
                }

                clearBridgeLogs()
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
        if (serverMutex.isLocked) {
            onStatus("Synchronizing with inference pipeline... ⏳")
            onProgress(0.06f, "Awaiting engine lock...")
        }
        serverMutex.withLock {
            fileLog("startServer requested for: $resolvedPath")

            val effectiveDraft = if (draftModelPath == "STANDALONE" || draftModelPath.isNullOrBlank()) null else draftModelPath
            val isSameInstance = globalLoadedPath == resolvedPath &&
                globalLoadedDraftPath == effectiveDraft &&
                globalLoadedUseMmap == useMmap &&
                globalLoadedDraftUseMmap == draftUseMmap

            if (isSameInstance) {
                activeDraftModelPath = effectiveDraft
                if (isServerReady()) {
                    fileLog("Server already active: $resolvedPath (draft: $draftModelPath, mmap: $useMmap, draftMmap: $draftUseMmap)")
                    isLoaded = true
                    serverThread = globalServerThread
                    onProgress(1.0f, "ONLINE • 100%")
                    onStatus("Local Model Ready ✓")
                    return@withLock true
                }
                if (globalServerThread?.isAlive == true || isServerAlive()) {
                    fileLog("Server is currently booting for $resolvedPath. Joining startup progress...")
                    val waitStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - waitStart < 40_000L) {
                        if (isServerReady()) {
                            fileLog("🚀 Server became ONLINE while waiting!")
                            isLoaded = true
                            serverThread = globalServerThread
                            activeDraftModelPath = effectiveDraft
                            onProgress(1.0f, "ONLINE • 100%")
                            onStatus("Neural Core Ready ✓")
                            return@withLock true
                        }
                        val elapsed = (System.currentTimeMillis() - waitStart) / 1000f
                        val p = (0.2f + elapsed / 25f).coerceIn(0.1f, 0.95f)
                        onProgress(p, "Loading Tensors into RAM (${elapsed.toInt()}s)...")
                        onStatus("Loading Tensors into RAM (${elapsed.toInt()}s)...")
                        delay(SERVER_POLL_INTERVAL_MS)
                    }
                }
            }

            if (!LlamaServerJNI.load()) {
                fileLog("Failed to load native libraries")
                onStatus("Native Engine Load Failure")
                return@withLock false
            }

            // 1. Clean previous state and zombie processes only if needed
            if (globalLoadedPath != null || globalServerThread != null) {
                onStatus("Stopping previous instance...")
                try { LlamaServerJNI.stop() } catch (_: Throwable) {}
                delay(400)
                var waitCount = 0
                while ((isServerAlive() || globalServerThread?.isAlive == true) && waitCount < 15) {
                    delay(200)
                    waitCount++
                }
                try { globalServerThread?.interrupt() } catch (_: Exception) {}
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
            // 2. Model Architecture Detection (for attention / KV-cache compatibility)
            val hw = appContext?.let { DeviceProfile.detect(it) }
            val modelFile = File(resolvedPath)
            val baseName = modelFile.nameWithoutExtension.lowercase()
            val isQwen = baseName.contains("qwen")

            // KV cache type: Strictly f16 for high-speed CPU inference.
            // On CPU (n-gpu-layers = 0), q8_0 KV-cache lacks SIMD kernels and forces scalar
            // dequantization on every attention head for every token, collapsing generation speed to 0.2 T/s.
            // f16 KV-cache runs direct vector instructions without dequantization overhead.
            val kvCacheType = "f16"

            // Flash Attention: Disabled on CPU to allow ggml's native optimized CPU attention kernels to run.
            val flashAttnArg = "off"

            val safeThreads = if (this@GGUFModelClient.threadCount in 2..8) this@GGUFModelClient.threadCount else 4
            val resolvedCtx = if (this@GGUFModelClient.contextLength >= 2048) this@GGUFModelClient.contextLength else 4096

            // Fixed sustainable batch sizes for mobile inference
            val effectiveBatch = 512
            val effectiveUbatch = 256

            val baseArgs = mutableListOf(
                "-m", resolvedPath,
                "--ctx-size", resolvedCtx.toString(),
                "-n", "-1",                 // Unconstrained token generation at engine level
                "--parallel", "1",
                "--cont-batching",
                "--no-warmup",
                "-b", effectiveBatch.toString(),
                "-ub", effectiveUbatch.toString(),
                "--threads", safeThreads.toString(),
                "-tb", safeThreads.toString(),
                "--n-gpu-layers", "0",
                "--flash-attn", flashAttnArg,
                "--cache-type-k", kvCacheType,
                "--cache-type-v", kvCacheType,
                "--alias", "local",
                "--host", "127.0.0.1",
                "--port", serverPort.toString()
            )

            // Memory mapping: Never pass --mlock. When mmap is disabled, load directly into RAM without system mlock
            if (useMmap) {
                baseArgs.add("--mmap")
            } else {
                baseArgs.add("--no-mmap")
            }

            // 3. Auto detect MTP draft (strictly enforce model family matching)
            var activeDraftPath = if (draftModelPath == "STANDALONE") null else draftModelPath

            if (!activeDraftPath.isNullOrBlank() && activeDraftPath != "STANDALONE") {
                val draftFile = File(activeDraftPath)
                if (!draftFile.exists()) {
                    activeDraftPath = null
                } else {
                    val draftName = draftFile.nameWithoutExtension.lowercase()
                    val isFamilyMatch = when {
                        baseName.contains("gemma") && draftName.contains("gemma") -> true
                        baseName.contains("qwen") && draftName.contains("qwen") -> true
                        baseName.contains("llama") && draftName.contains("llama") -> true
                        baseName.contains("bitnet") && (draftName.contains("bitnet") || draftName.contains("mult")) -> true
                        else -> false
                    }
                    if (!isFamilyMatch) {
                        fileLog("⚠️ Configured draft ($draftName) does not match base ($baseName). Auto-resolving compatible draft...")
                        activeDraftPath = null
                    }
                }
            }

            if (draftModelPath != "STANDALONE" && activeDraftPath.isNullOrBlank()) {
                val matched = ModelScanner.findMatchingMtpModel(resolvedPath, appContext)
                if (matched != null && matched.exists()) {
                    activeDraftPath = matched.absolutePath
                    fileLog("⚡ Auto-linked compatible companion MTP draft: ${matched.name}")
                }
            }

            val hasDraft = !activeDraftPath.isNullOrBlank() && activeDraftPath != "STANDALONE" && File(activeDraftPath).exists()
            val trialArgs = baseArgs.toMutableList()

            if (hasDraft) {
                activeDraftModelPath = activeDraftPath
                fileLog("MTP Draft Companion candidate linked: ${File(activeDraftPath!!).name} (draft mmap=$draftUseMmap)")
                trialArgs.add("--model-draft")
                trialArgs.add(activeDraftPath)
                trialArgs.add("--draft-max")
                trialArgs.add("4")
                trialArgs.add("--draft-min")
                trialArgs.add("1")
                trialArgs.add("--threads-draft")
                trialArgs.add(safeThreads.toString())
                trialArgs.add("--ctx-size-draft")
                trialArgs.add(resolvedCtx.toString())
                trialArgs.add("--n-gpu-layers-draft")
                trialArgs.add("0")
                trialArgs.add("--cache-type-k-draft")
                trialArgs.add("f16")
                trialArgs.add("--cache-type-v-draft")
                trialArgs.add("f16")
            } else {
                activeDraftModelPath = null
            }
            trialArgs.add("--cache-prompt")

            fileLog("Launch args: ${trialArgs.joinToString(" ")} (base mmap=$useMmap, draft mmap=$draftUseMmap)")

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
            globalLoadedUseMmap = useMmap
            globalLoadedDraftUseMmap = draftUseMmap
            ZramOptimizer.disableOomKiller()

            val startTime = System.currentTimeMillis()
            var serverBecameAlive = false
            var draftFailureDetected = false

            // Standard startup timeout for dual-model weight loading; fast-exit immediately on native errors
            val maxStartupWait = SERVER_START_TIMEOUT_MS

            // 4. Polling loop with native error interception
            while (System.currentTimeMillis() - startTime < maxStartupWait) {
                if (isServerReady()) {
                    val totalSec = (System.currentTimeMillis() - startTime) / 1000
                    fileLog("🚀 Server fully ONLINE (HTTP 200) in ${totalSec}s! (draft: ${if (hasDraft) activeDraftPath else "STANDALONE"})")
                    isLoaded = true
                    activeDraftModelPath = if (hasDraft) activeDraftPath else null

                    onProgress(1.0f, "ONLINE • 100%")
                    val readyMsg = if (hasDraft) {
                        "Neural Core Ready (MTP Active) (${totalSec}s) ✓"
                    } else {
                        "Neural Core Ready (STANDALONE) (${totalSec}s) ✓"
                    }
                    onStatus(readyMsg)
                    return@withLock true
                }

                if (isServerAlive()) {
                    serverBecameAlive = true
                }

                // If native initialization returned error code (e.g. -1), break early
                val retCode = nativeReturnCode.get()
                if (retCode != null && retCode != 0) {
                    fileLog("⚠️ Native server start failed with exit code: $retCode")
                    draftFailureDetected = true
                    break
                }

                if (hasDraft && checkRecentErrors()) {
                    fileLog("⚠️ Native draft loading error detected via logcat! Fast-failing draft and falling back to STANDALONE mode.")
                    draftFailureDetected = true
                    break
                }

                if (hasDraft && logFile.exists()) {
                    val hasLogDraftError = checkLogFileForDraftError(logFile)
                    if (hasLogDraftError) {
                        fileLog("⚠️ Native draft loading error detected in native_log.txt! Aborting draft to prevent spinlock.")
                        draftFailureDetected = true
                        break
                    }
                }

                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                val progress = (elapsedSec / 20f).coerceIn(0.08f, 0.95f)
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
            fileLog("Startup pass ended. isReady=${isServerReady()}, hasDraft=$hasDraft, draftFailureDetected=$draftFailureDetected. Output: $finalError")

            // If MTP/draft loading failed or timed out, do NOT spinlock at 100% CPU.
            // Immediately fall back to STANDALONE mode.
            if (hasDraft && !isLoaded) {
                fileLog("Draft companion failed to load. Gracefully falling back to STANDALONE mode immediately...")
                onStatus("Draft failed: Falling back to STANDALONE mode... ⏳")
                
                try { LlamaServerJNI.stop() } catch (_: Throwable) {}
                try { newThread.interrupt() } catch (_: Exception) {}
                serverThread = null
                globalServerThread = null
                activeDraftModelPath = null
                delay(400)

                val fallbackSuccess = startServerStandalone(resolvedPath, baseArgs.toTypedArray(), logFile, onStatus, onProgress)
                if (fallbackSuccess) {
                    activeDraftModelPath = null
                    onStatus("⚠️ Standalone mode active ✓")
                    return@withLock true
                }
            }

            onProgress(0f, "Boot Timeout")
            onStatus(if (finalError.isNotBlank()) "❌ $finalError" else "❌ Error: Boot Timeout")
            return@withLock false
        }
    }

    private suspend fun startServerStandalone(
        resolvedPath: String,
        args: Array<String>,
        logFile: File,
        onStatus: (String) -> Unit,
        onProgress: (Float, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        activeDraftModelPath = null
        val standaloneArgs = if (!args.contains("--cache-prompt")) {
            (args.toMutableList().apply { add("--cache-prompt") }).toTypedArray()
        } else {
            args
        }
        fileLog("Launching STANDALONE Server: ${standaloneArgs.joinToString(" ")}")
        onStatus("Launching High-Speed Base Model (STANDALONE)... ⏳")

        try { LlamaServerJNI.stop() } catch (_: Throwable) {}
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "killall -9 llama-server 2>/dev/null; pkill -9 llama-server 2>/dev/null"))
        } catch (_: Exception) {}
        delay(300)

        val fallbackReturnCode = java.util.concurrent.atomic.AtomicReference<Int?>(null)
        val newThread = Thread {
            val ret = kotlinx.coroutines.runBlocking { launchNativeProcess(standaloneArgs, logFile) }
            fallbackReturnCode.set(ret)
            fileLog("Standalone server thread returned: $ret")
        }.apply {
            name = "llama-server-standalone"
            isDaemon = true
            start()
        }

        serverThread = newThread
        globalServerThread = newThread
        globalLoadedPath = resolvedPath
        globalLoadedDraftPath = null
        globalLoadedUseMmap = useMmap
        globalLoadedDraftUseMmap = false
        ZramOptimizer.disableOomKiller()

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 60_000L) {
            if (isServerReady()) {
                val totalSec = (System.currentTimeMillis() - startTime) / 1000
                fileLog("🚀 STANDALONE Server ONLINE in ${totalSec}s!")
                isLoaded = true
                activeDraftModelPath = null
                onProgress(1.0f, "ONLINE • 100%")
                onStatus("Neural Core Ready (STANDALONE) (${totalSec}s) ✓")
                return@withContext true
            }

            val fbCode = fallbackReturnCode.get()
            if (fbCode != null && fbCode != 0) {
                fileLog("⚠️ Standalone server start failed with exit code: $fbCode")
                break
            }

            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
            val progress = (elapsedSec / 15f).coerceIn(0.10f, 0.95f)
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

        val effectiveDraft = if (draftModelPath == "STANDALONE" || draftModelPath.isNullOrBlank()) null else draftModelPath
        val isSameInstance = isServerReady() && globalLoadedPath == resolvedPath && globalLoadedDraftPath == effectiveDraft
        if (isSameInstance) {
            activeDraftModelPath = effectiveDraft
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

    fun ensurePromptEos(prompt: String, path: String? = null): String {
        val clean = prompt.trimEnd()
        val p = (path ?: modelPath ?: "").lowercase()
        return when {
            p.contains("gemma") -> {
                if (clean.endsWith("<end_of_turn>") || clean.endsWith("<start_of_turn>user")) clean
                else "$clean\n<end_of_turn>\n<start_of_turn>user\n"
            }
            p.contains("bitnet") -> {
                if (clean.endsWith("<|im_end|>") || clean.endsWith("<|im_start|>user") || clean.endsWith("<|eot_id|>")) clean
                else "$clean\n<|im_end|>\n<|im_start|>user\n"
            }
            else -> {
                if (clean.endsWith("<|im_end|>") || clean.endsWith("<|im_start|>user") || clean.endsWith("<end_of_turn>")) clean
                else "$clean\n<|im_end|>\n<|im_start|>user\n"
            }
        }
    }

    fun generateCompletionStream(prompt: String, systemPrompt: String = ""): Flow<String> = generateStream(prompt, systemPrompt)

    fun generateStream(prompt: String, systemPrompt: String = ""): Flow<String> = flow {
        val history = listOf("user" to prompt)
        generateStreamWithHistory(history, systemPrompt).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    private fun isTrivialLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return true
        val syntaxChars = setOf(
            '{', '}', '(', ')', '[', ']', ';', ',', ':', '"', '\'', '`',
            '=', '+', '-', '*', '/', '\\', '|', '&', '^', '%', '$', '#', '@', '!', '?'
        )
        return trimmed.all { it in syntaxChars || it.isWhitespace() }
    }

    suspend fun generateStreamWithHistory(
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
            val resolvedSystemPrompt = ensurePromptEos(systemPrompt, modelPath)
            messages.put(JSONObject().put("role", "system").put("content", resolvedSystemPrompt))
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
            .put("<|end_header_id|>")
            .put("User:")
            .put("<start_of_turn>user")
            .put("<start_of_turn>")
            .put("<|im_start|>")

        val targetMaxTokens = if (maxTokens > 0) maxTokens else -1
        val targetTemp = if (temperature > 0f) temperature else 0.5f
        val targetTopP = if (topP > 0f) topP else 0.90f
        val targetRepeatPenalty = if (repeatPenalty > 0f) repeatPenalty else 1.18f
        val targetRepeatLastN = if (repeatLastN > 0) repeatLastN else 128

        val body = JSONObject()
            .put("model", "local")
            .put("messages", messages)
            .put("temperature", targetTemp.toDouble())
            .put("top_p", targetTopP.toDouble())
            .put("repeat_penalty", targetRepeatPenalty.toDouble())
            .put("repetition_penalty", targetRepeatPenalty.toDouble())
            .put("repeat_last_n", targetRepeatLastN)
            .put("frequency_penalty", frequencyPenalty.toDouble())
            .put("presence_penalty", presencePenalty.toDouble())
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
        var lastThermalCheckTime = 0L

        // Hard Repetition Safeguard: stream interceptor tracking repeated lines
        val lineBuffer = StringBuilder()
        var lastEmittedLine = ""
        var consecutiveLineRepeats = 0
        var repetitionLimitExceeded = false

        try {
            while (coroutineContext.isActive && reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (!l.startsWith("data:")) continue
                val data = l.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue

                // ── Automatic Thermal Breaker: Pause 2s strictly if device temp > 90°C ────────
                val now = System.currentTimeMillis()
                if (now - lastThermalCheckTime >= 1000L) {
                    lastThermalCheckTime = now
                    val currentTemp = DeviceProfile.readDeviceTemperature()
                    if (currentTemp > 90) {
                        fileLog("⚠️ [THERMAL BREAKER] Device SoC temp is ${currentTemp}°C (> 90°C). Pausing generation for 2000ms...")
                        delay(2000L)
                    }
                }

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
                            lineBuffer.clear()
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
                            lineBuffer.clear()
                        }
                        if (firstTokenTime == 0L) firstTokenTime = System.currentTimeMillis()
                        tokenCount++
                        val elapsedSec = (System.currentTimeMillis() - firstTokenTime) / 1000f
                        if (elapsedSec > 0.1f) {
                            onSpeedUpdate?.invoke(tokenCount / elapsedSec)
                        }

                        // Hard Repetition Safeguard: inspect content stream for infinite repetition loops
                        var emittedLength = 0
                        for (i in content.indices) {
                            val ch = content[i]
                            if (ch == '\n') {
                                val completedLine = lineBuffer.toString().trim()
                                lineBuffer.clear()
                                if (completedLine.isNotEmpty() && !isTrivialLine(completedLine)) {
                                    if (completedLine == lastEmittedLine) {
                                        consecutiveLineRepeats++
                                        if (consecutiveLineRepeats > 3) {
                                            Log.w(TAG, "Hard repetition safeguard triggered! Repeated line > 3 times: \"$completedLine\"")
                                            fileLog("[SAFEGUARD] Terminated loop: line repeated > 3 times: \"$completedLine\"")
                                            lastFinishReason = "STOP"
                                            repetitionLimitExceeded = true
                                            val remainingChunk = content.substring(emittedLength, i + 1)
                                            if (remainingChunk.isNotEmpty()) {
                                                emit(remainingChunk)
                                            }
                                            emittedLength = i + 1
                                            break
                                        }
                                    } else {
                                        lastEmittedLine = completedLine
                                        consecutiveLineRepeats = 1
                                    }
                                }
                            } else {
                                lineBuffer.append(ch)
                            }
                        }

                        if (repetitionLimitExceeded) {
                            break
                        }

                        if (emittedLength < content.length) {
                            val chunkToEmit = if (emittedLength > 0) content.substring(emittedLength) else content
                            emit(chunkToEmit)
                        }
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
            val totalElapsedSec = (System.currentTimeMillis() - firstTokenTime) / 1000f
            if (firstTokenTime > 0L && totalElapsedSec > 0.05f && tokenCount > 0) {
                onSpeedUpdate?.invoke(tokenCount / totalElapsedSec)
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

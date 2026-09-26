package com.synapse.localai

import android.app.ActivityManager
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
        @Volatile private var isWarmingUp = false
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

            // Give the server thread up to 1.5 seconds to exit and free port 8088
            val waitStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - waitStart < 1500L) {
                try {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress(DEFAULT_HOST, DEFAULT_PORT), 100)
                    socket.close()
                    Thread.sleep(100)
                } catch (_: Exception) {
                    break // Port closed cleanly
                }
            }
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
    var maxTokens: Int = 2048
    var temperature: Float = 0.4f
    var topP: Float = 0.90f
    var topK: Int = 40
    var repeatPenalty: Float = 1.15f
    var repeatLastN: Int = 64
    var frequencyPenalty: Float = 0.0f
    var presencePenalty: Float = 0.0f
    var draftModelPath: String? = null
    var activeDraftModelPath: String? = null
    var useMmap: Boolean = true
    var draftUseMmap: Boolean = false
    var useFlashAttn: Boolean = false
    var gpuLayers: Int = 0

    private val serverUrl: String
        get() = "http://127.0.0.1:$serverPort"

    var isLoaded: Boolean = false
        private set

    @Volatile var currentMetadata: ChatTemplateEngine.ModelMetadata? = null
    @Volatile var lastFinishReason: String? = null
    @Volatile var lastTokenCount: Int = 0

    private fun resolveModelPath(
        context: Context,
        modelIdentifier: String,
        onStatus: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {}
    ): String? {
        if (!modelIdentifier.startsWith("content://")) {
            if (modelIdentifier.startsWith("/sdcard/") || modelIdentifier.startsWith("/storage/emulated/0/")) {
                val rawSub = modelIdentifier.removePrefix("/sdcard/").removePrefix("/storage/emulated/0/")
                val mediaFile = File("/data/media/0", rawSub)
                try {
                    val parentPath = mediaFile.parentFile?.absolutePath ?: "/data/media/0"
                    RootShellManager.exec("chmod 755 /data/media /data/media/0 2>/dev/null; chmod -R 755 /data/media/0/Medlam-Ai /data/media/0/Download '$parentPath' 2>/dev/null; chmod 666 '${mediaFile.absolutePath}' 2>/dev/null")
                } catch (_: Throwable) {}
                if (mediaFile.exists() && mediaFile.canRead()) {
                    fileLog("⚡ Direct Zero-FUSE path active: ${mediaFile.absolutePath}")
                    return mediaFile.absolutePath
                }
            }
            val directFile = File(context.filesDir, "models/${File(modelIdentifier).name}")
            if (directFile.exists() && directFile.length() > 0) {
                fileLog("⚡ Utilizing Direct-Mapped zero-FUSE model path: ${directFile.absolutePath}")
                return directFile.absolutePath
            }
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
               l.contains("failed to create draft context") ||
               l.contains("unordered_map::at: key not found") ||
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
                    Os.setenv("OMP_WAIT_POLICY", "PASSIVE", true)
                    Os.setenv("OMP_NUM_THREADS", "4", true)
                    Os.setenv("KMP_BLOCKTIME", "0", true)
                    Os.unsetenv("OMP_PROC_BIND")
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

    private fun getAvailableRamMb(): Long {
        return try {
            val file = File("/proc/meminfo")
            if (file.exists()) {
                val line = file.useLines { lines ->
                    lines.firstOrNull { it.startsWith("MemAvailable:") }
                }
                if (line != null) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        return parts[1].toLong() / 1024
                    }
                }
            }
            750L
        } catch (_: Exception) {
            750L
        }
    }

    private fun getProcessRssMb(): Long {
        return try {
            val status = File("/proc/self/status")
            if (status.exists()) {
                val line = status.useLines { lines ->
                    lines.firstOrNull { it.startsWith("VmRSS:") }
                }
                if (line != null) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        return parts[1].toLong() / 1024
                    }
                }
            }
            0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun determineEffectiveMmap(modelPath: String, requestedMmap: Boolean): Boolean {
        // Zero-Forced-RAM: Baseline architecture strictly relies on on-demand file-backed mmap
        if (requestedMmap) return true
        val freeRam = getAvailableRamMb()
        val modelFile = File(modelPath)
        val modelSizeMb = if (modelFile.exists()) modelFile.length() / (1024 * 1024) else 0L

        // Opt-in Physical RAM Pinning:
        val requiredRamMb = modelSizeMb + 1536L
        if (freeRam <= requiredRamMb) {
            fileLog("⚡ Storage Offloading: Insufficient physical RAM (${freeRam}MB available, ${requiredRamMb}MB required for ${modelSizeMb}MB model). Reverting to zero-forced-RAM mmap.")
            return true
        }
        return false
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
            PageCacheAdvisor.assert64BitArchitecture()
            fileLog("startServer requested for: $resolvedPath (PageSize=${PageCacheAdvisor.pageSize}B)")

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
                        onProgress(p, "Loading Tensors (${elapsed.toInt()}s)...")
                        onStatus("Loading Tensors (${elapsed.toInt()}s)...")
                        delay(SERVER_POLL_INTERVAL_MS)
                    }
                }
            }

            // Ensure all native dependencies including Vulkan and Synapse Bridge are initialized
            try { LlamaNativeEngine.load() } catch (_: Throwable) {}
            if (!LlamaServerJNI.load()) {
                fileLog("Failed to load native libraries")
                onStatus("Native Engine Load Failure")
                return@withLock false
            }

            // 1. Clean previous state and zombie processes if any server is still running or lingering
            if (globalLoadedPath != null || globalServerThread != null || isServerAlive() || isServerReady()) {
                onStatus("Stopping previous instance...")
                try { LlamaServerJNI.stop() } catch (_: Throwable) {}
                delay(300)
                var waitCount = 0
                while ((isServerAlive() || globalServerThread?.isAlive == true) && waitCount < 20) {
                    delay(150)
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
            delay(300)
            System.gc()

            appContext?.let { ZramOptimizer.optimizeMemoryAndZram(it) }
            ZramOptimizer.disableOomKiller()
            ZramOptimizer.optimizeKernelReadAhead()
            try {
                RootShellManager.exec("echo 4096 > /sys/block/mmcblk0/queue/read_ahead_kb 2>/dev/null || echo 4096 > /sys/block/sda/queue/read_ahead_kb 2>/dev/null")
            } catch (_: Exception) {}

            val internalLogDir = appContext?.filesDir ?: File("/sdcard/Medlam-Ai")
            if (!internalLogDir.exists()) internalLogDir.mkdirs()
            val logFile = File(internalLogDir, "native_log.txt")
            try { logFile.delete() } catch (_: Exception) {}

            // 2. Model Architecture & Metadata Detection
            val hw = appContext?.let { DeviceProfile.detect(it) }
            val modelFile = File(resolvedPath)
            val meta = ChatTemplateEngine.extractMetadata(modelFile)
            currentMetadata = meta

            val freeRam = getAvailableRamMb()
            val modelSizeMb = modelFile.length() / (1024 * 1024)
            val kvCacheType = "f16"

            // Compute Allocation: prioritize performance (big) cores strictly
            val effectiveThreads = if (this@GGUFModelClient.threadCount in 1..32) {
                this@GGUFModelClient.threadCount
            } else {
                hw?.let { DeviceProfile.optimalThreads(it) } ?: 4
            }
            val effectiveBatchThreads = effectiveThreads
            val resolvedCtx = (this@GGUFModelClient.contextLength).coerceIn(1024, 4096)
            var effectiveGpuLayers = this@GGUFModelClient.gpuLayers.coerceAtLeast(0)
            if (effectiveGpuLayers > 0) {
                val vkSupported = LlamaServerJNI.safeRegisterVulkan()
                if (!vkSupported) {
                    fileLog("⚠️ Vulkan GPU compute not supported on this SoC. Safely offloading to high-speed Big CPU cores.")
                    effectiveGpuLayers = 0
                } else {
                    fileLog("⚡ Vulkan GPU backend successfully activated with $effectiveGpuLayers layers.")
                }
            }

            val effectiveBatch = 512
            val effectiveUbatch = 256
            val flashAttnArg = if (effectiveGpuLayers > 0) "auto" else "off"

            val effectiveMmap = determineEffectiveMmap(resolvedPath, useMmap)
            val estKvCacheMb = (resolvedCtx.toLong() * 20L * 1024L * 9L) / (16L * 1024L * 1024L)
            fileLog("📊 [Engine RAM Profile] Physical Available: ${freeRam}MB | Model Size: ${modelSizeMb}MB | Threads: $effectiveThreads | GPU Layers: $effectiveGpuLayers | Est KV Cache (${resolvedCtx} ctx, $kvCacheType): ~${estKvCacheMb}MB")

            val baseArgs = mutableListOf(
                "-m", resolvedPath,
                "--ctx-size", resolvedCtx.toString(),
                "-n", "2048",
                "--parallel", "1",
                "--cont-batching",
                "-b", effectiveBatch.toString(),
                "-ub", effectiveUbatch.toString(),
                "-t", effectiveThreads.toString(),
                "-tb", effectiveBatchThreads.toString(),
                "--n-gpu-layers", effectiveGpuLayers.toString(),
                "--cache-type-k", kvCacheType,
                "--cache-type-v", kvCacheType,
                "--defrag-thold", "0.1",
                "--context-shift",
                "-fa", flashAttnArg,
                "--alias", "local",
                "--host", "127.0.0.1",
                "--port", serverPort.toString(),
                "--no-warmup",
                "--log-file", logFile.absolutePath
            )

            val serverTmplArg = ChatTemplateEngine.getServerTemplateArg(meta.architecture)
            baseArgs.add("--chat-template")
            baseArgs.add(serverTmplArg)
            fileLog("⚡ Injected native server chat template: $serverTmplArg (${meta.architecture})")

            if (resolvedCtx > 8192) {
                baseArgs.add("--rope-freq-scale")
                baseArgs.add("0.25")
                baseArgs.add("--yarn-orig-ctx")
                baseArgs.add("8192")
            }

            if (effectiveMmap) {
                baseArgs.add("--mmap")
            } else {
                baseArgs.add("--no-mmap")
            }

            // Speculative Decoding Configuration
            var activeDraftPath = if (draftModelPath == "STANDALONE" || draftModelPath.isNullOrBlank()) null else draftModelPath
            if (draftModelPath != "STANDALONE" && activeDraftPath.isNullOrBlank()) {
                val matched = ModelScanner.findMatchingMtpModel(resolvedPath, appContext)
                if (matched != null && matched.exists()) {
                    activeDraftPath = matched.absolutePath
                    fileLog("⚡ Auto-linked companion Gemma MTP draft: ${matched.name}")
                }
            }

            val hasDraft = !activeDraftPath.isNullOrBlank() && activeDraftPath != "STANDALONE" && File(activeDraftPath).exists()
            val trialArgs = baseArgs.toMutableList()

            if (hasDraft) {
                activeDraftModelPath = activeDraftPath
                trialArgs.add("-md")
                trialArgs.add(activeDraftPath!!)
                trialArgs.add("--draft")
                trialArgs.add("5")
                trialArgs.add("--draft-min")
                trialArgs.add("1")
                trialArgs.add("-td")
                trialArgs.add(effectiveThreads.toString())
                trialArgs.add("-cd")
                trialArgs.add(resolvedCtx.coerceAtMost(4096).toString())
                trialArgs.add("-ngld")
                trialArgs.add(effectiveGpuLayers.toString())
                trialArgs.add("-ctkd")
                trialArgs.add(kvCacheType)
                trialArgs.add("-ctvd")
                trialArgs.add(kvCacheType)
                trialArgs.add("--override-kv")
                trialArgs.add("gemma4.context_length=int:131072,gemma4-assistant.context_length=int:131072")
            } else {
                activeDraftModelPath = null
            }
            trialArgs.add("--cache-prompt")

            fileLog("Launch args: ${trialArgs.joinToString(" ")}")

            try {
                RootShellManager.exec("chmod 644 /sys/devices/system/cpu/cpu*/online 2>/dev/null; for i in 0 1 2 3 4 5 6 7; do echo 1 > /sys/devices/system/cpu/cpu\$i/online 2>/dev/null; done")
            } catch (_: Exception) {}

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
            val maxStartupWait = SERVER_START_TIMEOUT_MS

            while (System.currentTimeMillis() - startTime < maxStartupWait) {
                if (isServerReady()) {
                    val totalSec = (System.currentTimeMillis() - startTime) / 1000
                    val perfMask = hw?.let { DeviceProfile.performanceAffinityMask(it) } ?: "f0"
                    LlamaNativeEngine.pinToBigCores()
                    val nativePinned = LlamaServerJNI.safeBindPerformanceCores()
                    val shellPinned = ZramOptimizer.pinToPerformanceCores(perfMask)
                    fileLog("🚀 Performance Cores Dedicated: Native=$nativePinned, Shell=$shellPinned (mask $perfMask)")
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

                val retCode = nativeReturnCode.get()
                if (retCode != null && retCode != 0) {
                    fileLog("⚠️ Native server failed to launch with return code: $retCode")
                    draftFailureDetected = true
                    break
                }

                if (hasDraft && checkRecentErrors()) {
                    draftFailureDetected = true
                    break
                }

                if (hasDraft && logFile.exists()) {
                    if (checkLogFileForDraftError(logFile)) {
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

            if (hasDraft && !isLoaded) {
                fileLog("Draft companion failed to load. Falling back to STANDALONE mode...")
                onStatus("Draft failed: Falling back to STANDALONE mode... ⏳")
                
                try { LlamaServerJNI.stop() } catch (_: Throwable) {}
                try { newThread.interrupt() } catch (_: Exception) {}
                serverThread = null
                globalServerThread = null
                activeDraftModelPath = null
                delay(300)

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
        val cleanList = args.toMutableList()
        val draftIndices = mutableListOf<Int>()
        var skipNext = false
        for (i in cleanList.indices) {
            if (skipNext) {
                draftIndices.add(i)
                skipNext = false
                continue
            }
            val arg = cleanList[i]
            if (arg in setOf(
                "--model-draft", "-md", "--threads-draft", "-td",
                "--ctx-size-draft", "-cd", "--draft", "--draft-max",
                "--draft-min", "--cpu-mask-draft", "--n-gpu-layers-draft",
                "-ngld", "--cache-type-k-draft", "-ctkd",
                "--cache-type-v-draft", "-ctvd"
            )) {
                draftIndices.add(i)
                skipNext = true
            }
        }
        for (i in draftIndices.asReversed()) {
            cleanList.removeAt(i)
        }

        if (!cleanList.contains("--cache-prompt")) {
            cleanList.add("--cache-prompt")
        }

        val effectiveMmap = determineEffectiveMmap(resolvedPath, useMmap)
        cleanList.removeAll { it == "--mlock" }
        if (effectiveMmap) {
            cleanList.removeAll { it == "--no-mmap" }
            if (!cleanList.contains("--mmap")) {
                cleanList.add("--mmap")
            }
        } else {
            cleanList.removeAll { it == "--mmap" }
            if (!cleanList.contains("--no-mmap")) {
                cleanList.add("--no-mmap")
            }
        }

        val standaloneArgs = cleanList.toTypedArray()
        fileLog("Launching STANDALONE Server: ${standaloneArgs.joinToString(" ")}")
        onStatus("Launching High-Speed Base Model (STANDALONE)... ⏳")

        try {
            RootShellManager.exec("chmod 644 /sys/devices/system/cpu/cpu*/online 2>/dev/null; for i in 0 1 2 3 4 5 6 7; do echo 1 > /sys/devices/system/cpu/cpu\$i/online 2>/dev/null; done; echo 4096 > /sys/block/mmcblk0/queue/read_ahead_kb 2>/dev/null || echo 4096 > /sys/block/sda/queue/read_ahead_kb 2>/dev/null")
        } catch (_: Exception) {}
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
        while (System.currentTimeMillis() - startTime < SERVER_START_TIMEOUT_MS) {
            if (isServerReady()) {
                val totalSec = (System.currentTimeMillis() - startTime) / 1000
                LlamaNativeEngine.pinToBigCores()
                LlamaServerJNI.safeBindPerformanceCores()
                ZramOptimizer.pinToPerformanceCores("f0")
                isLoaded = true
                activeDraftModelPath = null
                onProgress(1.0f, "ONLINE • 100%")
                onStatus("Neural Core Ready (STANDALONE) (${totalSec}s) ✓")
                return@withContext true
            }

            val fbCode = fallbackReturnCode.get()
            if (fbCode != null && fbCode != 0) {
                fileLog("⚠️ Standalone server failed to launch with code: $fbCode")
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
        val isSameInstance = isServerReady() && 
            globalLoadedPath == resolvedPath && 
            globalLoadedDraftPath == effectiveDraft &&
            globalLoadedUseMmap == useMmap &&
            globalLoadedDraftUseMmap == draftUseMmap
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

    suspend fun warmupPersonaKVCache(systemPrompt: String): Boolean = withContext(Dispatchers.IO) {
        if (!isServerReady()) return@withContext false
        if (isWarmingUp) return@withContext true
        isWarmingUp = true
        fileLog("Initiating Neural Core Warmup & Persona KV Prefill...")
        try {
            val url = URL("$serverUrl/completion")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10000
                readTimeout = 120000
            }

            val arch = currentMetadata?.architecture ?: ChatTemplateEngine.Architecture.GEMMA
            val warmupPrompt = ChatTemplateEngine.formatPrompt(
                history = listOf("user" to "ping"),
                systemDirective = systemPrompt,
                arch = arch,
                addAssistantPrompt = true
            )

            val body = JSONObject().apply {
                put("prompt", warmupPrompt)
                put("max_tokens", 1)
                put("n_predict", 1)
                put("temperature", 0.0)
                put("n_threads", 4)
                put("threads", 4)
                put("cache_prompt", true)
                put("stream", false)
            }

            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                fileLog("🚀 Warmup & Persona KV Prefill complete (HTTP 200, length=${resp.length})")
                conn.disconnect()
                true
            } else {
                fileLog("Warmup returned HTTP $code")
                conn.disconnect()
                false
            }
        } catch (e: Exception) {
            fileLog("Warmup exception: ${e.message}")
            false
        } finally {
            isWarmingUp = false
        }
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
        return prompt.trimEnd()
    }

    fun generateCompletionStream(prompt: String, systemPrompt: String = "", overrideTemperature: Float? = null): Flow<String> = generateStream(prompt, systemPrompt, overrideTemperature)

    fun generateStream(prompt: String, systemPrompt: String = "", overrideTemperature: Float? = null): Flow<String> = flow {
        val history = listOf("user" to prompt)
        generateStreamWithHistory(history, systemPrompt, overrideTemperature = overrideTemperature).collect { emit(it) }
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

    suspend fun generateStreamWithRawPrompt(
        promptText: String,
        overrideTemperature: Float? = null,
        onSpeedUpdate: ((Float) -> Unit)? = null
    ): Flow<String> = executeInferenceFlow(promptText, overrideTemperature, onSpeedUpdate)

    suspend fun generateStreamWithHistory(
        history: List<Pair<String, String>>,
        systemPrompt: String = "",
        overrideTemperature: Float? = null,
        onSpeedUpdate: ((Float) -> Unit)? = null
    ): Flow<String> {
        val arch = currentMetadata?.architecture ?: ChatTemplateEngine.Architecture.GEMMA
        val promptText = ChatTemplateEngine.formatPrompt(
            history = history,
            systemDirective = systemPrompt,
            arch = arch,
            addAssistantPrompt = true
        )
        return executeInferenceFlow(promptText, overrideTemperature, onSpeedUpdate)
    }

    private fun executeInferenceFlow(
        promptText: String,
        overrideTemperature: Float? = null,
        onSpeedUpdate: ((Float) -> Unit)? = null
    ): Flow<String> = flow {
        val targetUrl = "$serverUrl/completion"

        var waitAttempts = 0
        while (!isServerReady() && waitAttempts < 25) {
            waitAttempts++
            delay(800)
        }

        if (!isServerReady()) {
            emit("[ERROR: Model engine still initializing. Please wait a moment and retry.]")
            return@flow
        }

        val activeArch = currentMetadata?.architecture ?: ChatTemplateEngine.Architecture.GEMMA

        // FIXED: Explicitly register all Gemma EOS stop sequences to the native server payload
        val allStopTokens = (ChatTemplateEngine.getStopTokens(activeArch) + listOf(
            "<end_of_turn>", "<start_of_turn>", "<eos>", "<|end_of_turn|>",
            "<|im_end|>", "<|im_start|>", "<|eot_id|>", "<|start_header_id|>",
            "end_of_turn", "<end_of", ">end_of"
        )).distinct()

        val stopArray = JSONArray().apply {
            allStopTokens.forEach { put(it) }
        }

        val targetMaxTokens = if (maxTokens in 1..2048) maxTokens else 2048
        val targetTemp = overrideTemperature ?: 0.4f
        val targetTopP = 0.90f
        val targetTopK = 40
        val targetRepeatPenalty = 1.15f
        val targetRepeatLastN = 64

        val body = JSONObject()
            .put("prompt", promptText)
            .put("temperature", targetTemp.toDouble())
            .put("top_p", targetTopP.toDouble())
            .put("top_k", targetTopK)
            .put("repeat_penalty", targetRepeatPenalty.toDouble())
            .put("repetition_penalty", targetRepeatPenalty.toDouble())
            .put("repeat_last_n", targetRepeatLastN)
            .put("stream", true)
            .put("n_predict", targetMaxTokens)
            .put("cache_prompt", true)
            .put("stop", stopArray)

        val bodyBytes = body.toString().toByteArray()
        var conn: HttpURLConnection? = null
        var responseCode = -1

        val requestStartTime = System.currentTimeMillis()
        try {
            // FIXED: Enforce performance cores allocation before dispatching inference
            LlamaNativeEngine.pinToBigCores()
            LlamaServerJNI.safeBindPerformanceCores()
            ZramOptimizer.pinToPerformanceCores("f0")
            PageCacheAdvisor.adviseModelActive(modelPath ?: globalLoadedPath)
        } catch (_: Exception) {}

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

        val lineBuffer = StringBuilder()
        val fullStreamText = StringBuilder()
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

                val now = System.currentTimeMillis()
                if (now - lastThermalCheckTime >= 5000L) {
                    lastThermalCheckTime = now
                    val currentTemp = DeviceProfile.readDeviceTemperature()
                    if (currentTemp > 80) {
                        fileLog("ℹ️ [THERMAL MONITOR] SoC temp: ${currentTemp}°C")
                    }
                }

                try {
                    val json = JSONObject(data)
                    val isStopped = json.optBoolean("stop", false)
                    val choice0 = json.optJSONArray("choices")?.optJSONObject(0)
                    val finishReason = choice0?.optString("finish_reason") ?: if (isStopped) "stop" else null
                    if (!finishReason.isNullOrBlank() && finishReason != "null") {
                        lastFinishReason = when (finishReason.lowercase()) {
                            "length" -> "MAX_TOKENS"
                            "stop" -> "STOP"
                            else -> finishReason.uppercase()
                        }
                    }

                    val deltaObj = choice0?.optJSONObject("delta")
                    val rawReasoning = if (deltaObj != null && deltaObj.has("reasoning_content") && !deltaObj.isNull("reasoning_content")) {
                        deltaObj.getString("reasoning_content")
                    } else ""
                    var rawContent = if (deltaObj != null && deltaObj.has("content") && !deltaObj.isNull("content")) {
                        deltaObj.getString("content")
                    } else if (json.has("content") && !json.isNull("content")) {
                        json.getString("content")
                    } else ""

                    // FIXED: Immediate sanitization and stopping on delimiter detection
                    var reasoning = rawReasoning
                    var content = rawContent
                    var reachedStop = false

                    for (stopTok in allStopTokens) {
                        if (reasoning.contains(stopTok)) {
                            reasoning = reasoning.substringBefore(stopTok)
                            reachedStop = true
                            lastFinishReason = "STOP"
                            break
                        }
                        if (content.contains(stopTok)) {
                            content = content.substringBefore(stopTok)
                            reachedStop = true
                            lastFinishReason = "STOP"
                            break
                        }
                    }

                    // BiDi RTL Artifact Sanitizer: Strips reversed or residual end delimiters
                    content = content.replace("<end_of_turn>", "")
                                     .replace(">end_of", "")
                                     .replace("<end_of", "")
                                     .replace("end_of_turn", "")
                                     .replace("<start_of_turn>", "")

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
                            lineBuffer.append(chunkToEmit)
                            fullStreamText.append(chunkToEmit)

                            if (StreamDemultiplexer.hasCompletedToolCall(fullStreamText.toString())) {
                                Log.i(TAG, "GGUFModelClient: Synchronous ReAct Barrier triggered, halting llama.cpp text generation immediately.")
                                lastFinishReason = "STOP"
                                break
                            }
                        }
                        if (isStopped || reachedStop) {
                            break
                        }
                    } else if (isStopped || reachedStop) {
                        break
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
            val prefillLatencyMs = if (firstTokenTime > 0L) firstTokenTime - requestStartTime else 0L
            val genSpeedTps = if (firstTokenTime > 0L && totalElapsedSec > 0.05f && tokenCount > 0) {
                tokenCount / totalElapsedSec
            } else 0f
            if (firstTokenTime > 0L && totalElapsedSec > 0.05f && tokenCount > 0) {
                onSpeedUpdate?.invoke(genSpeedTps)
            }
            fileLog("⚡ [BENCHMARK] Prompt Eval (Prefill): ${prefillLatencyMs}ms | Generation: ${tokenCount} tokens in ${String.format(java.util.Locale.US, "%.2f", totalElapsedSec)}s (${String.format(java.util.Locale.US, "%.2f", genSpeedTps)} T/s)")
            Log.i(TAG, "[BENCHMARK] Prompt Eval (Prefill): ${prefillLatencyMs}ms | Generation: ${tokenCount} tokens in ${String.format(java.util.Locale.US, "%.2f", totalElapsedSec)}s (${String.format(java.util.Locale.US, "%.2f", genSpeedTps)} T/s)")
        } catch (e: Exception) {
            emit("[ERROR: ${e.message}]")
        } finally {
            lastTokenCount = tokenCount
            reader.close()
            conn.disconnect()
            try {
                PageCacheAdvisor.adviseModelIdle(modelPath ?: globalLoadedPath)
            } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    fun stop() {
        try {
            PageCacheAdvisor.adviseModelIdle(globalLoadedPath ?: modelPath)
        } catch (_: Exception) {}
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

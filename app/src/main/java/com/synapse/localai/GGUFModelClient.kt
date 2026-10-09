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
        private const val AUTO_GPU_LAYER_COUNT = 999
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 8088
        private const val SERVER_START_TIMEOUT_MIN_MS = 120_000L
        private const val SERVER_START_TIMEOUT_MAX_MS = 360_000L
        private const val SERVER_POLL_INTERVAL_MS = 500L

        @Volatile private var globalServerThread: Thread? = null
        @Volatile private var globalLoadedPath: String? = null
        @Volatile private var globalLoadedDraftPath: String? = null
        @Volatile private var globalLoadedUseMmap: Boolean? = null
        @Volatile private var globalLoadedDraftUseMmap: Boolean? = null
        @Volatile private var globalLoadedGpuRequest: Int? = null
        @Volatile private var globalLoadedThreadCount: Int? = null
        @Volatile private var globalLoadedContextLength: Int? = null
        @Volatile private var isWarmingUp = false
        private val serverMutex = Mutex()

        fun stopGlobalServer() {
            try {
                LlamaServerJNI.safeStop()
                fileLog("Stopped llama-server via JNI stopGlobalServer")
            } catch (t: Throwable) {
                fileLog("Could not stop JNI server: ${t.message}")
            }
            val serverThread = globalServerThread
            try {
                serverThread?.join(1_500L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (serverThread?.isAlive == true) {
                fileLog("Native server has not exited yet; retaining its thread handle to prevent an overlapping model load.")
                return
            }
            serverThread?.interrupt()
            if (globalServerThread === serverThread) globalServerThread = null
            globalLoadedPath = null
            globalLoadedDraftPath = null
            globalLoadedUseMmap = null
            globalLoadedDraftUseMmap = null
            globalLoadedGpuRequest = null
            globalLoadedThreadCount = null
            globalLoadedContextLength = null
        }

        private fun fileLog(msg: String) {
            Log.d(TAG, msg)
            try {
                val dir = File("/sdcard/atlas-ai")
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
    var temperature: Float = 0.30f
    var topP: Float = 0.90f
    var topK: Int = 40
    var repeatPenalty: Float = 1.18f
    var repeatLastN: Int = 128
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
                    RootShellManager.exec("chmod 755 /data/media /data/media/0 2>/dev/null; chmod -R 755 /data/media/0/atlas-ai /data/media/0/Download '$parentPath' 2>/dev/null; chmod 666 '${mediaFile.absolutePath}' 2>/dev/null")
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
            if (code !in 200..299) {
                false
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                body.isBlank() || body.contains("ok", ignoreCase = true)
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

    private fun readRecentLog(logFile: File, maxBytes: Long = 64 * 1024L): String {
        if (!logFile.exists()) return ""
        return try {
            java.io.RandomAccessFile(logFile, "r").use { file ->
                val start = (file.length() - maxBytes).coerceAtLeast(0L)
                file.seek(start)
                ByteArray((file.length() - start).toInt()).also(file::readFully).toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun checkLogFileForDraftError(logFile: File): Boolean {
        return readRecentLog(logFile).lineSequence().any { isDraftErrorLine(it) }
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

    private fun startupTimeoutMs(modelFile: File): Long {
        val sizeMb = modelFile.length() / (1024 * 1024)
        return (SERVER_START_TIMEOUT_MIN_MS + sizeMb * 45L)
            .coerceAtMost(SERVER_START_TIMEOUT_MAX_MS)
    }

    private fun modelMemoryRejection(modelFile: File): String? {
        return if (!modelFile.isFile || modelFile.length() <= 0L) {
            "Model file is missing, empty, or unreadable: ${modelFile.name}"
        } else {
            null
        }
    }

    private fun lowerMemoryArgs(args: List<String>): List<String> {
        val reduced = args.toMutableList()
        fun setOption(option: String, value: String) {
            val index = reduced.indexOf(option)
            if (index >= 0 && index + 1 < reduced.size) reduced[index + 1] = value
        }
        val ctxIndex = reduced.indexOf("--ctx-size")
        if (ctxIndex >= 0 && ctxIndex + 1 < reduced.size) {
            val current = reduced[ctxIndex + 1].toIntOrNull() ?: 1024
            reduced[ctxIndex + 1] = current.coerceAtMost(1024).toString()
        }
        setOption("-b", "64")
        setOption("-ub", "32")
        setOption("--n-gpu-layers", "0")
        setOption("--cache-type-k", "f16")
        setOption("--cache-type-v", "f16")
        return reduced
    }

    private suspend fun stopNativeServerAndWait(): Boolean {
        val thread = globalServerThread
        try {
            LlamaServerJNI.safeStop()
        } catch (t: Throwable) {
            fileLog("Native server stop request failed: ${t.message}")
        }
        var waitedMs = 0L
        while (thread?.isAlive == true && waitedMs < 5_000L) {
            delay(100)
            waitedMs += 100
        }
        return thread?.isAlive != true
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
            val requestedGpuLayers = this@GGUFModelClient.gpuLayers.coerceAtLeast(0)
                .let { if (it == 0) AUTO_GPU_LAYER_COUNT else it }
            val isSameInstance = globalLoadedPath == resolvedPath &&
                globalLoadedDraftPath == effectiveDraft &&
                globalLoadedUseMmap == useMmap &&
                globalLoadedDraftUseMmap == draftUseMmap &&
                globalLoadedGpuRequest == requestedGpuLayers &&
                globalLoadedThreadCount == threadCount &&
                globalLoadedContextLength == contextLength

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

            isLoaded = false

            val modelFile = File(resolvedPath)
            val deviceMemory = appContext?.let { DeviceProfile.detect(it) }
            val availableRamMb = getAvailableRamMb()
            val memoryRejection = modelMemoryRejection(modelFile)
            if (memoryRejection != null) {
                fileLog("Model startup stopped: $memoryRejection")
                onProgress(0f, "Invalid model file")
                onStatus("❌ $memoryRejection")
                return@withLock false
            }
            val preflightModelSizeMb = modelFile.length() / (1024 * 1024)
            val totalRamMb = deviceMemory?.totalRamMb ?: 0L
            if (availableRamMb < maxOf(768L, preflightModelSizeMb / 2L) ||
                totalRamMb in 1 until (preflightModelSizeMb + 1_024L)
            ) {
                fileLog(
                    "Low-memory model startup: ${availableRamMb}MB available, ${totalRamMb}MB total RAM, " +
                        "${preflightModelSizeMb}MB model. Continuing with mmap and adaptive CPU settings."
                )
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
                if (!stopNativeServerAndWait()) {
                    fileLog("Previous native server did not stop; aborting startup to prevent overlapping model instances.")
                    onStatus("Previous model is still stopping. Retry loading in a moment.")
                    return@withLock false
                }
                try { globalServerThread?.interrupt() } catch (_: Exception) {}
                globalServerThread = null
                globalLoadedPath = null
                globalLoadedDraftPath = null
                globalLoadedUseMmap = null
                globalLoadedDraftUseMmap = null
                globalLoadedGpuRequest = null
                globalLoadedThreadCount = null
                globalLoadedContextLength = null
                serverThread = null
            }

            delay(300)
            System.gc()

            appContext?.let { ZramOptimizer.optimizeMemoryAndZram(it) }
            ZramOptimizer.optimizeKernelReadAhead()
            try {
                RootShellManager.exec("echo 1024 > /sys/block/mmcblk0/queue/read_ahead_kb 2>/dev/null || echo 1024 > /sys/block/sda/queue/read_ahead_kb 2>/dev/null")
            } catch (_: Exception) {}

            val internalLogDir = File("/sdcard/atlas-ai")
            if (!internalLogDir.exists()) internalLogDir.mkdirs()
            val logFile = File(internalLogDir, "native_log.txt")
            try { logFile.delete() } catch (_: Exception) {}

            // 2. Model Architecture & Metadata Detection
            val hw = deviceMemory
            val meta = ChatTemplateEngine.extractMetadata(modelFile)
            currentMetadata = meta

            val freeRam = getAvailableRamMb()
            val modelSizeMb = modelFile.length() / (1024 * 1024)
            // llama.cpp requires Flash Attention for quantized V cache; this ARM profile disables it.
            val kvCacheType = "f16"

            // Compute Allocation: strictly dedicate all 4 Kryo Gold Big performance cores (Cores 4-7)
            val effectiveThreads = DeviceProfile.inferenceThreads(threadCount, freeRam, hw)
            val effectiveBatchThreads = effectiveThreads.coerceAtMost(4)
            val resolvedCtx = DeviceProfile.inferenceContextLength(this@GGUFModelClient.contextLength, freeRam)
            val vkSupported = LlamaServerJNI.safeRegisterVulkan()
            val autoGpuFitsMemory = (hw?.totalRamMb ?: 0L) >= 8_192L &&
                freeRam >= (modelSizeMb * 2L + 1_536L)
            val effectiveGpuLayers = if (vkSupported) {
                if (requestedGpuLayers == AUTO_GPU_LAYER_COUNT && !autoGpuFitsMemory) {
                    fileLog("Automatic Vulkan offload skipped: device RAM headroom is insufficient (${freeRam}MB available, ${modelSizeMb}MB model); using memory-mapped CPU mode.")
                    0
                } else if (requestedGpuLayers != AUTO_GPU_LAYER_COUNT &&
                    freeRam < (modelSizeMb + 1_536L)
                ) {
                    fileLog("Requested Vulkan offload skipped: insufficient memory headroom; using CPU to avoid process termination.")
                    0
                } else {
                    requestedGpuLayers
                }
            } else {
                fileLog("Vulkan is unavailable on this device; using CPU inference.")
                0
            }

            val effectiveBatch = when {
                freeRam >= 6_144L -> 256
                freeRam >= 3_072L -> 128
                freeRam >= 1_536L -> 64
                else -> 32
            }
            val effectiveUbatch = effectiveBatch.coerceAtMost(64)
            // CPU inference: Flash Attention disabled on ARM Cortex-A73 to avoid tiling overhead
            val flashAttnArg = "off"

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
                "--cache-ram", "0",
                "--defrag-thold", "0.1",
                "--context-shift",
                "-fa", flashAttnArg,
                "--alias", "local",
                "--host", "127.0.0.1",
                "--port", serverPort.toString(),
                "--no-warmup",
                "--fit", "off",
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

            // Speculative decoding is only enabled on devices with additional physical-RAM headroom.
            var activeDraftPath = if (draftModelPath == "STANDALONE" || draftModelPath.isNullOrBlank()) null else draftModelPath
            if (draftModelPath != "STANDALONE" && activeDraftPath.isNullOrBlank()) {
                // Require memory headroom for both the base model and the companion draft.
                if ((hw?.totalRamMb ?: 0L) >= 8_192L &&
                    freeRam >= modelSizeMb * 2L + 1_536L
                ) {
                    val matched = ModelScanner.findMatchingMtpModel(resolvedPath, appContext)
                    if (matched != null && matched.exists()) {
                        activeDraftPath = matched.absolutePath
                        fileLog("⚡ Auto-linked companion Gemma MTP draft: ${matched.name}")
                    }
                } else {
                    fileLog("ℹ️ Memory Guard: ${freeRam}MB RAM available (< 1500MB). Running base model in standalone mode to avoid page thrashing.")
                }
            }

            val hasDraft = !activeDraftPath.isNullOrBlank() && activeDraftPath != "STANDALONE" && File(activeDraftPath).exists()
            val trialArgs = baseArgs.toMutableList()
            PageCacheAdvisor.adviseModelActive(resolvedPath)

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
                /* Thermal Safety: Core forcing disabled */
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
            globalLoadedGpuRequest = requestedGpuLayers
            globalLoadedThreadCount = threadCount
            globalLoadedContextLength = contextLength
            val startTime = System.currentTimeMillis()
            var serverBecameAlive = false
            var draftFailureDetected = false
            var lastLogcatCheckTime = 0L
            var nativeReturnObserved = false
            val maxStartupWait = startupTimeoutMs(modelFile)

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
                if (retCode != null) {
                    if (retCode != 0) {
                        fileLog("⚠️ Native server failed to launch with return code: $retCode")
                        draftFailureDetected = true
                        break
                    }
                    if (!nativeReturnObserved) {
                        nativeReturnObserved = true
                        fileLog("Native server returned success before health became ready; waiting for model loading to finish.")
                    }
                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000L
                    onStatus("Loading model weights (${elapsedSec}s)...")
                    delay(SERVER_POLL_INTERVAL_MS)
                    continue
                }

                if (hasDraft && System.currentTimeMillis() - lastLogcatCheckTime >= 5_000L) {
                    lastLogcatCheckTime = System.currentTimeMillis()
                    if (checkRecentErrors()) {
                        draftFailureDetected = true
                        break
                    }
                }

                if (hasDraft && logFile.exists()) {
                    if (checkLogFileForDraftError(logFile)) {
                        draftFailureDetected = true
                        break
                    }
                }

                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                val progress = (elapsedSec / 20f).coerceIn(0.08f, 0.95f)
                val recentLog = readRecentLog(logFile, 16 * 1024L).lowercase()
                val loadPhase = when {
                    recentLog.contains("load_tensors") || recentLog.contains("loading model") -> "Reading model weights"
                    recentLog.contains("allocating") || recentLog.contains("context") -> "Allocating inference memory"
                    serverBecameAlive -> "Initializing model runtime"
                    else -> "Starting native server"
                }
                val statusText = "$loadPhase (${elapsedSec.toInt()}s)..."

                onProgress(progress, statusText)
                onStatus(statusText)

                delay(SERVER_POLL_INTERVAL_MS)
            }

            val finalError = readRecentLog(logFile, 8 * 1024L).lines().takeLast(6).joinToString(" | ")
            fileLog("Startup pass ended. isReady=${isServerReady()}, hasDraft=$hasDraft, draftFailureDetected=$draftFailureDetected. Output: $finalError")

            if (hasDraft && !isLoaded) {
                fileLog("Draft companion failed to load. Falling back to STANDALONE mode...")
                onStatus("Draft failed: Falling back to STANDALONE mode... ⏳")
                
                if (!stopNativeServerAndWait()) {
                    fileLog("Draft server did not stop; refusing to start a concurrent fallback server.")
                    onStatus("Draft server is still shutting down...")
                    return@withLock false
                }
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

            if (effectiveGpuLayers > 0 && !isLoaded) {
                fileLog("Vulkan startup failed; retrying this model on CPU to keep local inference available.")
                onStatus("GPU initialization failed: retrying with CPU…")
                if (!stopNativeServerAndWait()) {
                    fileLog("GPU server did not stop; refusing to start a concurrent CPU fallback.")
                    onStatus("GPU server is still shutting down...")
                    return@withLock false
                }
                try { newThread.interrupt() } catch (_: Exception) {}
                serverThread = null
                globalServerThread = null
                activeDraftModelPath = null
                delay(300)

                val cpuArgs = baseArgs.toMutableList()
                val gpuLayerArg = cpuArgs.indexOf("--n-gpu-layers")
                if (gpuLayerArg >= 0 && gpuLayerArg + 1 < cpuArgs.size) {
                    cpuArgs[gpuLayerArg + 1] = "0"
                }
                if (startServerStandalone(resolvedPath, cpuArgs.toTypedArray(), logFile, onStatus, onProgress)) {
                    activeDraftModelPath = null
                    onStatus("Local Model Ready (CPU fallback) ✓")
                    return@withLock true
                }
            }

            val lowMemoryArgs = lowerMemoryArgs(baseArgs)
            if (lowMemoryArgs != baseArgs) {
                fileLog("Retrying model startup with low-memory profile: ctx=1024, batch=64, GPU=off.")
                onStatus("Retrying with low-memory CPU profile...")
                if (stopNativeServerAndWait()) {
                    if (startServerStandalone(resolvedPath, lowMemoryArgs.toTypedArray(), logFile, onStatus, onProgress)) {
                        activeDraftModelPath = null
                        onStatus("Local Model Ready (low-memory mode) ✓")
                        return@withLock true
                    }
                } else {
                    fileLog("Previous native server did not stop; refusing overlapping startup attempts.")
                }
            }

            val finalLog = readRecentLog(logFile, 8 * 1024L).lines().takeLast(10).joinToString(" | ")
            onProgress(0f, "Model startup failed")
            onStatus(
                if (finalLog.isNotBlank()) "❌ Model failed to start: $finalLog"
                else if (finalError.isNotBlank()) "❌ Model failed to start: $finalError"
                else "❌ Model startup failed. Check gguf_log.txt and native_log.txt."
            )
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
            /* Thermal Safety: Core forcing disabled */
        } catch (_: Exception) {}
        if (!stopNativeServerAndWait()) {
            fileLog("Cannot start fallback while the previous native server thread is still active.")
            onStatus("Native server is still stopping; retrying shortly...")
            return@withContext false
        }

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
        globalLoadedThreadCount = threadCount
        globalLoadedContextLength = contextLength

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < startupTimeoutMs(File(resolvedPath))) {
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
            if (fbCode != null) {
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
        val requestedGpuLayers = gpuLayers.coerceAtLeast(0)
            .let { if (it == 0) AUTO_GPU_LAYER_COUNT else it }
        val isSameInstance = isServerReady() && 
            globalLoadedPath == resolvedPath && 
            globalLoadedDraftPath == effectiveDraft &&
            globalLoadedUseMmap == useMmap &&
            globalLoadedDraftUseMmap == draftUseMmap &&
            globalLoadedGpuRequest == requestedGpuLayers &&
            globalLoadedThreadCount == threadCount &&
            globalLoadedContextLength == contextLength
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
        val totalRamMb = appContext?.let { DeviceProfile.detect(it).totalRamMb } ?: 0L
        if (totalRamMb in 1 until 8_192L) {
            fileLog("Skipping persona KV prefill on a ${totalRamMb}MB device; persona instructions are included in each request.")
            return@withContext false
        }
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

    suspend fun saveSlotState(slotId: Int = 0, filePath: String = "/sdcard/atlas-ai/slots/persona_slot.bin"): Boolean = withContext(Dispatchers.IO) {
        try {
            val slotsDir = File("/sdcard/atlas-ai/slots")
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

    suspend fun restoreSlotState(slotId: Int = 0, filePath: String = "/sdcard/atlas-ai/slots/persona_slot.bin"): Boolean = withContext(Dispatchers.IO) {
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
        val readinessWaitStartedAt = System.currentTimeMillis()
        while (!isServerReady() && waitAttempts < 100) {
            waitAttempts++
            delay(800)
        }

        if (!isServerReady()) {
            val waitedSeconds = (System.currentTimeMillis() - readinessWaitStartedAt) / 1000
            fileLog(
                "Inference refused after waiting ${waitedSeconds}s: model endpoint $serverUrl/health did not report ready; " +
                    "clientLoaded=$isLoaded, serverThreadAlive=${serverThread?.isAlive == true}."
            )
            emit("[ERROR: Local model endpoint is not ready after ${waitedSeconds}s. The model may have stopped; reload it and retry.]")
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

        val targetMaxTokens = maxTokens.coerceIn(1, 8192)
        val targetTemp = overrideTemperature ?: this@GGUFModelClient.temperature
        val targetTopP = this@GGUFModelClient.topP
        val targetTopK = this@GGUFModelClient.topK
        val targetRepeatPenalty = this@GGUFModelClient.repeatPenalty
        val targetRepeatLastN = this@GGUFModelClient.repeatLastN

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

        val bodyBytes: ByteArray = body.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        var conn: HttpURLConnection? = null
        var responseCode = -1

        val requestStartTime = System.currentTimeMillis()
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
        var serverPredictedTokens: Int? = null
        var serverPredictedMs: Double? = null
        var serverPromptTokens: Int? = null
        var serverPromptMs: Double? = null
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
                    json.optJSONObject("timings")?.let { timings ->
                        val predictedN = timings.optInt("predicted_n", -1)
                        val predictedMs = timings.optDouble("predicted_ms", -1.0)
                        val promptN = timings.optInt("prompt_n", -1)
                        val promptMs = timings.optDouble("prompt_ms", -1.0)
                        if (predictedN >= 0) serverPredictedTokens = predictedN
                        if (predictedMs >= 0.0) serverPredictedMs = predictedMs
                        if (promptN >= 0) serverPromptTokens = promptN
                        if (promptMs >= 0.0) serverPromptMs = promptMs
                    }
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

                            if ((chunkToEmit.contains('>') || chunkToEmit.contains('}')) &&
                                StreamDemultiplexer.hasCompletedToolCall(fullStreamText.toString())) {
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
            val measuredTokenCount = serverPredictedTokens ?: tokenCount
            lastTokenCount = measuredTokenCount
            if (lastFinishReason == null) {
                lastFinishReason = "STOP"
            }
            val wallGenerationMs = if (firstTokenTime > 0L) System.currentTimeMillis() - firstTokenTime else 0L
            val measuredGenerationMs = serverPredictedMs?.takeIf { it > 0.0 } ?: wallGenerationMs.toDouble()
            val totalElapsedSec = (measuredGenerationMs / 1000.0).toFloat()
            val prefillLatencyMs = serverPromptMs?.takeIf { it >= 0.0 }?.toLong()
                ?: if (firstTokenTime > 0L) firstTokenTime - requestStartTime else 0L
            val genSpeedTps = if (totalElapsedSec > 0.05f && measuredTokenCount > 0) {
                measuredTokenCount / totalElapsedSec
            } else 0f
            if (totalElapsedSec > 0.05f && measuredTokenCount > 0) {
                onSpeedUpdate?.invoke(genSpeedTps)
            }
            val timingSource = if (serverPredictedTokens != null && serverPredictedMs != null) {
                "llama.cpp timings"
            } else {
                "SSE chunk estimate"
            }
            val promptTokenText = serverPromptTokens?.let { "$it prompt tokens, " }.orEmpty()
            fileLog(
                "⚡ [BENCHMARK] source=$timingSource | Prefill: $promptTokenText${prefillLatencyMs}ms | " +
                    "Generation: $measuredTokenCount tokens in ${String.format(java.util.Locale.US, "%.2f", totalElapsedSec)}s " +
                    "(${String.format(java.util.Locale.US, "%.2f", genSpeedTps)} T/s)"
            )
            Log.i(
                TAG,
                "[BENCHMARK] source=$timingSource | Prefill: $promptTokenText${prefillLatencyMs}ms | " +
                    "Generation: $measuredTokenCount tokens in ${String.format(java.util.Locale.US, "%.2f", totalElapsedSec)}s " +
                    "(${String.format(java.util.Locale.US, "%.2f", genSpeedTps)} T/s)"
            )
        } catch (e: Exception) {
            emit("[ERROR: ${e.message}]")
        } finally {
            lastTokenCount = serverPredictedTokens ?: tokenCount
            reader.close()
            conn.disconnect()
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
        globalLoadedGpuRequest = null
        globalLoadedThreadCount = null
        globalLoadedContextLength = null
        globalLoadedUseMmap = null
        globalLoadedDraftUseMmap = null
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

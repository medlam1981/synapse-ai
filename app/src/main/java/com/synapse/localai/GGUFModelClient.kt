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
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 8080
        private const val SERVER_START_TIMEOUT_MS = 180_000L
        private const val SERVER_POLL_INTERVAL_MS = 300L

        private fun fileLog(msg: String) {
            Log.d(TAG, msg)
            try {
                val dir = File("/sdcard/Medlam-Ai")
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
    var serverThread: Thread? = null

    var serverHost: String = DEFAULT_HOST
    var serverPort: Int = DEFAULT_PORT
    var contextLength: Int = 2048 // CRITICAL for BF16 models to avoid Android OOM killer
    var threadCount: Int = 0 
    var maxTokens: Int = 2048
    var temperature: Float = 0.7f
    var draftModelPath: String? = null
    var gpuLayers: Int = 0
    var useMmap: Boolean = true

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

    fun getActiveBaseUrl(): String = "http://127.0.0.1:$serverPort"
    fun getBaseUrl(): String = getActiveBaseUrl()

    private suspend fun isServerRunning(): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("http://127.0.0.1:$serverPort/health")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1000 // Lenient timeout
            conn.readTimeout = 1000
            val code = conn.responseCode
            return@withContext (code == 200 || code == 503)
        } catch (_: Exception) {
            return@withContext false
        } finally {
            conn?.disconnect()
        }
    }

    private fun findMatchingMtpFile(baseModelPath: String): String? {
        val searchDirs = listOf(
            File("/sdcard/Medlam-Ai/mtp"),
            File("/sdcard/Medlam-Ai/Mtp"),
            File("/sdcard/Medlam-Ai/models"),
            File("/sdcard/Medlam-Ai/Models")
        )
        for (dir in searchDirs) {
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles() ?: continue
                for (f in files) {
                    if (f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && f.name.contains("mtp", ignoreCase = true)) {
                        return f.absolutePath
                    }
                }
            }
        }
        return null
    }

    private suspend fun startServer(
        resolvedPath: String, 
        onStatus: (String) -> Unit = {},
        onProgress: (Float, String) -> Unit = { _, _ -> },
        enableDraft: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        fileLog("startServer called for: $resolvedPath (enableDraft=$enableDraft)")

        if (!LlamaServerJNI.load()) {
            fileLog("Failed to load native libraries")
            onStatus("❌ Error: Native Engine Load Failure")
            onProgress(0f, "Native Engine Load Failure")
            return@withContext false
        }

        if (globalLoadedPath == resolvedPath && isServerRunning()) {
            fileLog("Server already active: $resolvedPath")
            isLoaded = true
            onProgress(1.0f, "ONLINE • 100%")
            onStatus("✅ Local Server Ready.")
            return@withContext true
        }

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

        val ctx = appContext
        if (ctx != null) {
            ctx.startService(Intent(ctx, ModelEngineService::class.java))
        }

        try {
            System.gc()
            // Dynamic thread count: use all available cores, capped at 8 for stability
            val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

            // NOTE: JNI start(args) excludes argv[0] — do NOT include "llama-server" here.
            val argsList = mutableListOf(
                "-m", resolvedPath,
                "-c", contextLength.coerceIn(2048, 4096).toString(),
                "-b", "256",
                "-ub", "256",
                "-t", cores.toString(),
                "--host", "127.0.0.1",
                "--port", serverPort.toString(),
                "-ngl", gpuLayers.toString(),
                "--cache-type-k", "q8_0",
                "--cache-type-v", "q8_0",
                "--parallel", "1",
                "--cont-batching",
                "--no-warmup"
            )

            if (useMmap) {
                argsList.add("--mmap")
            } else {
                argsList.add("--no-mmap")
            }

            // Smart MTP draft auto-pairing:
            // Allow E2B draft models. Only exclude genuine auxiliary-only blocks:
            //   - files with "assistant" in name (no standalone context length)
            //   - BF16 mtp-gemma-4 WITHOUT "e2b" (12B auxiliary, not a full draft)
            val filteredDraft = draftModelPath?.takeIf { path ->
                val lower = path.lowercase()
                !lower.contains("assistant") &&
                !(lower.contains("bf16") && lower.contains("mtp-gemma-4") && !lower.contains("e2b"))
            }
            val matchedDraft = if (enableDraft) {
                filteredDraft?.takeIf { File(it).exists() }
                    ?: ModelScanner.findMatchingMtpModel(resolvedPath)?.absolutePath
            } else null
            if (matchedDraft != null && File(matchedDraft).exists()) {
                argsList.add("--model-draft")
                argsList.add(matchedDraft)
                argsList.add("--draft")
                argsList.add("4")
                argsList.add("--draft-min")
                argsList.add("2")
                fileLog("⚡ MTP LINKED: $matchedDraft")
            } else {
                fileLog("Standalone mode — no valid draft model found")
            }

            val args = argsList.toTypedArray()
            val logDir = File("/sdcard/Medlam-Ai")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = File(logDir, "native_log.txt")
            try { logFile.delete() } catch (_: Exception) {}

            var nativeCrashCode: Int? = null

            val newThread = Thread {
                try {
                    val fd = Os.open(logFile.absolutePath, OsConstants.O_CREAT or OsConstants.O_WRONLY or OsConstants.O_TRUNC, 438)
                    Os.dup2(fd, OsConstants.STDOUT_FILENO)
                    Os.dup2(fd, OsConstants.STDERR_FILENO)

                    fileLog("JNI starting: ${args.joinToString(" ")}")
                    val result = LlamaServerJNI.safeStart(args)
                    // Any exit is unexpected during model load (0 = model incompatible, non-0 = crash)
                    nativeCrashCode = result
                    fileLog("JNI process exited with return code: $result")
                } catch (e: Exception) {
                    nativeCrashCode = -1
                    fileLog("JNI process error: ${e.message}")
                }
            }.apply {
                name = "llama-server-jni"
                isDaemon = true
                start()
            }
            serverThread = newThread
            globalServerThread = newThread
            globalLoadedPath = resolvedPath

            val startTime = System.currentTimeMillis()
            val baseFileSize = File(resolvedPath).length().takeIf { it > 0 } ?: (2L * 1024 * 1024 * 1024)
            val baseSizeGb = baseFileSize / (1024f * 1024f * 1024f)

            var lastLogReadPos = 0L
            val tensorRegex1 = Regex("tensor\\s+(\\d+)\\s*/\\s*(\\d+)")
            val tensorRegex2 = Regex("loading model tensors:\\s*(\\d+)%")

            while (System.currentTimeMillis() - startTime < SERVER_START_TIMEOUT_MS) {
                if (isServerRunning()) {
                    fileLog("Server verified ONLINE on port $serverPort!")
                    isLoaded = true
                    onProgress(1.0f, "ONLINE • 100%")
                    onStatus("✅ Local Server Ready.")
                    return@withContext true
                }

                // Detect any early server exit (including clean exit code 0 = model load failure)
                val threadDied = !newThread.isAlive
                val earlyExit = threadDied && (System.currentTimeMillis() - startTime < 30_000)
                if ((nativeCrashCode != null || earlyExit) && (System.currentTimeMillis() - startTime > 1000)) {
                    // Scan native log for specific model load error
                    val failReason = try {
                        if (logFile.exists()) {
                            logFile.readLines().lastOrNull { line ->
                                line.contains("failed to load model", ignoreCase = true) ||
                                line.contains("missing tensor", ignoreCase = true) ||
                                line.contains("unknown model architecture", ignoreCase = true) ||
                                line.contains("error loading model", ignoreCase = true)
                            }?.trim()
                        } else null
                    } catch (_: Exception) { null }

                    if (enableDraft && matchedDraft != null) {
                        fileLog("MTP Draft failed. Falling back to Standalone...")
                        draftModelPath = null
                        LlamaServerJNI.safeStop()
                        delay(600)
                        return@withContext startServer(resolvedPath, onStatus, onProgress, enableDraft = false)
                    } else {
                        val msg = if (failReason != null)
                            "❌ Model incompatible: ${failReason.take(80)}"
                        else
                            "❌ Engine failed to start (exit ${nativeCrashCode ?: 0})"
                        fileLog(msg)
                        onProgress(0f, "Model Load Failed")
                        onStatus(msg)
                        globalLoadedPath = null
                        return@withContext false
                    }
                }

                delay(SERVER_POLL_INTERVAL_MS)

                var actualProgress = 0.05f
                try {
                    if (logFile.exists()) {
                        val currentLen = logFile.length()
                        if (currentLen > lastLogReadPos) {
                            val raf = java.io.RandomAccessFile(logFile, "r")
                            raf.seek(lastLogReadPos)
                            var line: String?
                            while (raf.readLine().also { line = it } != null) {
                                val currentLine = line ?: continue
                                val match1 = tensorRegex1.find(currentLine)
                                if (match1 != null) {
                                    val cur = match1.groupValues[1].toFloatOrNull() ?: 0f
                                    val tot = match1.groupValues[2].toFloatOrNull() ?: 1f
                                    if (tot > 0) actualProgress = (cur / tot).coerceIn(0.05f, 0.99f)
                                }
                                val match2 = tensorRegex2.find(currentLine)
                                if (match2 != null) {
                                    val pct = match2.groupValues[1].toFloatOrNull() ?: 0f
                                    actualProgress = (pct / 100f).coerceIn(0.05f, 0.99f)
                                }
                            }
                            lastLogReadPos = raf.filePointer
                            raf.close()
                        }
                    }
                } catch (_: Exception) {}

                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                val smoothProgress = if (actualProgress > 0.1f) actualProgress else (0.05f + (elapsedSec / 15f) * 0.90f).coerceIn(0.05f, 0.95f)

                val pctInt = (smoothProgress * 100).toInt()
                val loadedGb = smoothProgress * baseSizeGb
                val statusMsg = "Loading Neural Core: $pctInt% • ${String.format(java.util.Locale.US, "%.1f", loadedGb)}/${String.format(java.util.Locale.US, "%.1f", baseSizeGb)} GB"
                onProgress(smoothProgress, statusMsg)
                onStatus(statusMsg)
            }

            fileLog("Server boot timeout.")
            onProgress(0f, "Boot Timeout")
            onStatus("❌ Error: Boot Timeout.")
            return@withContext false
        } catch (e: Exception) {
            fileLog("Server startup exception: ${e.message}")
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
        val started = startServer(resolvedPath, onStatus, onProgress, enableDraft = true)
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
        
        // CRITICAL FIX: Do NOT auto-restart server if globalLoadedPath is present. 
        // Let the HTTP request handle timeouts gracefully without assassination.
        if (globalLoadedPath == null && modelPath != null) {
            val ctx = appContext
            val resolvedPath = if (ctx != null) resolveModelPath(ctx, modelPath!!) else null
            if (resolvedPath != null) {
                startServer(resolvedPath)
            }
        }

        val targetUrl = "http://127.0.0.1:$serverPort/v1/chat/completions"

        val persona = if (systemPrompt.isNotBlank()) {
            systemPrompt.trim()
        } else {
            try {
                val f = File("/sdcard/Medlam-Ai/persona/system_persona.md")
                if (f.exists()) f.readText().trim() else ""
            } catch (_: Exception) { "" }
        }

        val messagesArray = JSONArray()
        if (persona.isNotBlank()) {
            messagesArray.put(JSONObject().put("role", "system").put("content", persona))
        }
        for ((role, content) in history) {
            val roleName = if (role == "user") "user" else "assistant"
            messagesArray.put(JSONObject().put("role", roleName).put("content", content.trim()))
        }
        if (messagesArray.length() == 0) {
            messagesArray.put(JSONObject().put("role", "user").put("content", "Hello"))
        }

        val stopArray = JSONArray()
            .put("<end_of_turn>")
            .put("<eos>")
            .put("<|im_end|>")
            .put("User:")
            .put("<start_of_turn>user")

        val body = JSONObject()
            .put("model", "default")
            .put("messages", messagesArray)
            .put("temperature", if (temperature > 0f) temperature.toDouble() else 0.7)
            .put("stream", true)
            .put("n_predict", -1)        // Unlimited tokens
            .put("cache_prompt", true)   // KV cache reuse for speed
            .put("stop", stopArray)

        val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        var responseCode = -1

        var attempts = 0
        val maxAttempts = 15

        while (coroutineContext.isActive && attempts < maxAttempts) {
            attempts++
            try {
                conn = URL(targetUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 180000 // Long timeout for heavy BF16 prompt processing
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.setRequestProperty("Connection", "keep-alive")

                conn.outputStream.use { it.write(bodyBytes) }
                responseCode = conn.responseCode

                if (responseCode == 503) {
                    conn.disconnect()
                    conn = null
                    delay(1000L)
                    continue
                }
                break
            } catch (e: Exception) {
                conn?.disconnect()
                conn = null
                if (attempts < maxAttempts) {
                    delay(1000L)
                    continue
                }
                emit("[ERROR: ${e.message}]")
                return@flow
            }
        }

        if (conn == null) return@flow

        if (responseCode != 200) {
            emit("[ERROR: Server returned $responseCode]")
            conn.disconnect()
            return@flow
        }

        var tokenCount = 0
        val startTime = System.currentTimeMillis()
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        var line: String? = null

        while (coroutineContext.isActive && reader.readLine().also { line = it } != null) {
            val l = line?.trim() ?: continue
            if (!l.startsWith("data:")) continue
            val data = l.removePrefix("data:").trim()
            if (data == "[DONE]") break
            if (data.isEmpty()) continue

            try {
                val json = JSONObject(data)
                val choices = json.optJSONArray("choices")
                val firstChoice = choices?.optJSONObject(0)
                if (firstChoice != null) {
                    val delta = firstChoice.optJSONObject("delta")
                    val deltaContent = delta?.let { if (!it.isNull("content")) it.opt("content")?.toString() else null }
                    val deltaReasoning = delta?.let { if (!it.isNull("reasoning_content")) it.opt("reasoning_content")?.toString() else null }
                    val directText = if (!firstChoice.isNull("text")) firstChoice.opt("text")?.toString() else null

                    val chunkText = (deltaContent ?: deltaReasoning ?: directText)?.takeIf {
                        it.isNotEmpty() && it != "null"
                    }

                    if (chunkText != null) {
                        tokenCount++
                        val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                        if (elapsedSec > 0.2f) {
                            onSpeedUpdate?.invoke(tokenCount / elapsedSec)
                        }
                        emit(chunkText)
                    }
                }
            } catch (_: Exception) {}
        }
        reader.close()
        conn.disconnect()
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

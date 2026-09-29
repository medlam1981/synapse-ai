package com.synapse.localai

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.synapse.localai.audio.CascadeStreamInterceptor
import android.content.pm.PackageManager
import android.content.SharedPreferences
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import android.util.Log
import android.net.Uri
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.text.NumberFormat
import java.io.File
import java.util.*
import com.synapse.localai.tools.YouTubeTool
import com.synapse.localai.tools.DocumentTool
import com.synapse.localai.tools.WebSearchTool
import com.synapse.localai.repository.ImageGenerator
import com.synapse.localai.repository.ImageRepository
import com.synapse.localai.repository.ImageResult
import com.synapse.localai.repository.ImageState
import com.synapse.localai.repository.GeminiImageGenerator
import com.synapse.localai.repository.LocalDiffusionGenerator

enum class ModelType { GGUF, GEMINI }

enum class AgentActionPhase {
    IDLE,
    THINKING,
    PLANNING,
    GENERATING_CODE,
    WRITING_FILE,
    EXECUTING_TOOL,
    ANALYZING_OUTPUT,
    COMPLETING
}

typealias AgentStatus = AgentActionPhase

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val thinkingText: String = "",
    val isThinking: Boolean = false,
    val imageBytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LogEntry
        if (id != other.id) return false
        if (sender != other.sender) return false
        if (text != other.text) return false
        if (isError != other.isError) return false
        if (timestamp != other.timestamp) return false
        if (thinkingText != other.thinkingText) return false
        if (isThinking != other.isThinking) return false
        if (imageBytes != null) {
            if (other.imageBytes == null) return false
            if (!imageBytes.contentEquals(other.imageBytes)) return false
        } else if (other.imageBytes != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sender.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + isError.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + thinkingText.hashCode()
        result = 31 * result + isThinking.hashCode()
        result = 31 * result + (imageBytes?.contentHashCode() ?: 0)
        return result
    }
}

data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val command: String,
    val executeAt: Long,
    val status: String = "pending"
)

data class AgentConfig(
    val preferredModelType: ModelType = ModelType.GEMINI,
    val localModelPath: String = "",
    val draftModelPath: String = "",
    val gpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val draftUseMmap: Boolean = true,
    val pinModelInRam: Boolean = false,
    val geminiApiKeys: String = "",
    val geminiModelName: String = "gemini-2.0-flash",
    val serverHost: String = "0.0.0.0",
    val serverPort: Int = 8088,
    val contextLength: Int = 4096,
    val threadCount: Int = 4,
    val maxTokens: Int = 2048,
    val temperature: Float = 0.4f,
    val enableVisualHistory: Boolean = false,
    val enableNeuralReflexes: Boolean = false,
    val enabledSpecializedAgents: Set<String> = emptySet(),
    val systemPrompt: String = "",
    val customToolsPrompt: String = "",
    val showApiKeyTracker: Boolean = true,
    val activePersona: String = PersonaManager.DEFAULT_PERSONA,
    val activePersonas: Set<String> = PersonaManager.DEFAULT_PERSONAS_SET,
    val ttsEnabled: Boolean = true,
    val ttsEngineType: String = "HUMAN_NEURAL",
    val ttsVoiceLanguage: String = "AUTO",
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 0.88f,
    val customAcousticModelPath: String = "/sdcard/Medlam-Ai/tts/qwen3_tts_acoustic_int8.onnx",
    val customVocoderModelPath: String = "/sdcard/Medlam-Ai/tts/qwen3_tts_vocoder_fp16.onnx",
    val customSpeakerEmbeddingPath: String = "",
    val sdSteps: Int = 0,
    val sdCfgScale: Float = 0f,
    val sdWidth: Int = 256,
    val sdHeight: Int = 256,
    val sdLowMemoryMode: Boolean = true,
    val sdThreads: Int = 4,
    val sdEngineMode: String = "SD_TURBO",
    val sdComputeDevice: String = "CPU"
)

enum class EngineState {
    IDLE,
    RUNNING
}

enum class InitializationPhase {
    IDLE,
    ALLOCATING_MEMORY,
    PARSING_PERSONA,
    SYSTEM_READY
}

data class AgentUiState(
    val isProcessing: Boolean = false,
    val logs: List<LogEntry> = emptyList(),
    val config: AgentConfig = AgentConfig(),
    val serviceActive: Boolean = false,
    val overlayActive: Boolean = false,
    val keyboardActive: Boolean = false,
    val localModelLoading: Boolean = false,
    val localModelLoaded: Boolean = false,
    val modelLoadFailed: Boolean = false,
    val loadingProgress: Float? = null,
    val loadingStatusText: String = "",
    val initPhase: InitializationPhase = InitializationPhase.IDLE,
    val activeModelType: ModelType? = null,
    val activeModelName: String = "",
    val scheduledTasks: List<ScheduledTask> = emptyList(),
    val specializedAgents: List<File> = emptyList(),
    val activeTasks: List<AgentTask> = emptyList(),
    val taskEngineRunning: Boolean = false,
    val taskQueueSize: Int = 0,
    val taskSummary: String = "",
    val currentPhase: AgentActionPhase = AgentActionPhase.IDLE,
    val shouldMinimize: Boolean = false,
    val lastExecutionResult: String = "",
    val currentKeyIndex: Int = 0,
    val totalKeys: Int = 0,
    val isRootAvailable: Boolean = false,
    val streamInterruptMsg: String? = null,
    val tokensPerSecond: Float = 0f,
    val activeStatusDetail: String = "Idle / Ready",
    val resumptionNotice: String? = null,
    val activeWorkspacePath: String? = null,
    val activePersona: String = PersonaManager.DEFAULT_PERSONA,
    val activePersonas: Set<String> = PersonaManager.DEFAULT_PERSONAS_SET,
    val availablePersonas: List<String> = emptyList(),
    val isLiveCanvasOpen: Boolean = false,
    val activeDraftModelPath: String? = null,
    val hardwareWarning: String? = null,
    val isTtsEnabled: Boolean = true
) {
    val engineState: EngineState
        get() = if (isProcessing) EngineState.RUNNING else EngineState.IDLE
}

class SynchronousToolBarrierException : kotlinx.coroutines.CancellationException("Synchronous ReAct Barrier: Tool call detected")

class AgentViewModel : ViewModel() {

    companion object {
        private const val TAG = "SYNAPSE_CORE"
        private const val MAX_HISTORY_MESSAGES = 48
        private const val MAX_TASK_ITERATIONS = 15
        private const val AGENTS_DIR = "/sdcard/Medlam-Ai/Agents"
        
        var instance: AgentViewModel? = null
            private set

        fun detectModelType(path: String): ModelType {
            return ModelType.GGUF
        }

        val EXECUTABLE_LANGUAGES = setOf(
            "html", "htm", "css", "scss", "sass", "less",
            "js", "javascript", "jsx", "ts", "typescript", "tsx",
            "py", "python", "pyw",
            "sh", "bash", "zsh", "shell",
            "json", "yaml", "yml", "xml", "toml",
            "c", "cpp", "cxx", "cc", "h", "hpp",
            "java", "kotlin", "kt", "kts",
            "rust", "rs", "go", "golang",
            "sql", "php", "ruby", "rb", "swift", "dart", "lua", "r"
        )
    }

    init {
        instance = this
    }

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()
    val activePersonas: StateFlow<Set<String>> = _uiState
        .map { it.activePersonas }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.activePersonas)

    val systemStats: StateFlow<SystemStats> = flow {
        while (true) {
            val ctx = appContext ?: MainActivity.instance
            val stats = if (ctx != null) {
                getSystemStats(ctx)
            } else {
                SystemStats(0, false, "--", "--", "--", "--", 0, 0, 0)
            }
            emit(stats)
            delay(2000L)
        }
    }.flowOn(Dispatchers.IO)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SystemStats(0, false, "--", "--", "--", "--", 0, 0, 0)
    )

    val scrollSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    @Volatile private var lastTpsUpdateMs = 0L

    var taskMemory: TaskMemoryManager? = null
        private set
    var taskEngine: BackgroundTaskEngine? = null
        private set

    var chatDb: ChatDatabaseHelper? = null
        private set
    val currentSessionId = MutableStateFlow<String>(UUID.randomUUID().toString())
    val currentSessionTitle = MutableStateFlow<String>("New Session")
    val chatSessions = MutableStateFlow<List<ChatSessionEntity>>(emptyList())

    private var portalServer: PortalServer? = null
    private var ggufClient: GGUFModelClient? = null
    private var geminiClient: GeminiModelClient? = null
    private var cascadeInterceptor: CascadeStreamInterceptor? = null

    fun getOrCreateCascadeInterceptor(context: Context): CascadeStreamInterceptor {
        return cascadeInterceptor ?: synchronized(this) {
            cascadeInterceptor ?: CascadeStreamInterceptor(context.applicationContext).also {
                cascadeInterceptor = it
            }
        }
    }

    fun toggleTts() {
        _uiState.update { current ->
            val next = !current.isTtsEnabled
            cascadeInterceptor?.setEnabled(next)
            current.copy(isTtsEnabled = next)
        }
    }

    fun testVoice(context: Context, sampleText: String? = null) {
        getOrCreateCascadeInterceptor(context).testVoice(sampleText)
    }

    fun getAcousticModels(context: Context): List<File> = ModelScanner.getAcousticModels(context)
    fun getVocoderModels(context: Context): List<File> = ModelScanner.getVocoderModels(context)
    fun getSpeakerEmbeddings(context: Context): List<File> = ModelScanner.getSpeakerEmbeddings(context)

    private val _imageState = MutableStateFlow<ImageState>(ImageState.Idle)
    val imageState: StateFlow<ImageState> = _imageState.asStateFlow()

    val imageRepository: ImageRepository by lazy {
        val geminiGen = GeminiImageGenerator(
            apiKeysProvider = {
                _uiState.value.config.geminiApiKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
        )
        val localGen = LocalDiffusionGenerator(appContext ?: MainActivity.instance)
        ImageRepository(
            geminiGenerator = geminiGen,
            localGenerator = localGen,
            isCloudModeProvider = {
                _uiState.value.activeModelType == ModelType.GEMINI &&
                _uiState.value.config.geminiApiKeys.isNotBlank()
            }
        )
    }

    private fun saveImageToDisk(bytes: ByteArray, prompt: String): File? {
        return try {
            val baseDir = File("/sdcard/Pictures/SynapseAI").takeIf { it.exists() || it.mkdirs() }
                ?: File("/sdcard/Download").takeIf { it.exists() || it.mkdirs() }
                ?: File("/sdcard/Medlam-Ai").apply { if (!exists()) mkdirs() }
            val safePrompt = prompt.take(20).replace(Regex("[^a-zA-Z0-9_-]"), "_").trim('_')
            val fileName = "img_${System.currentTimeMillis()}${if (safePrompt.isNotBlank()) "_$safePrompt" else ""}.jpg"
            val file = File(baseDir, fileName)
            file.writeBytes(bytes)
            file
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save generated image to disk: ${e.message}")
            null
        }
    }

    private fun saveVideoToDisk(bytes: ByteArray, prompt: String): File? {
        return try {
            val baseDir = File("/sdcard/Movies/SynapseAI").takeIf { it.exists() || it.mkdirs() }
                ?: File("/sdcard/Download").takeIf { it.exists() || it.mkdirs() }
                ?: File("/sdcard/Medlam-Ai").apply { if (!exists()) mkdirs() }
            val safePrompt = prompt.take(20).replace(Regex("[^a-zA-Z0-9_-]"), "_").trim('_')
            val fileName = "vid_${System.currentTimeMillis()}${if (safePrompt.isNotBlank()) "_$safePrompt" else ""}.mp4"
            val file = File(baseDir, fileName)
            file.writeBytes(bytes)
            file
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save generated video to disk: ${e.message}")
            null
        }
    }

    suspend fun enrichVisualPromptWithLLM(shortPrompt: String): String = withContext(Dispatchers.Default) {
        val client = ggufClient
        val isLocalLoaded = (_uiState.value.activeModelType == ModelType.GGUF || _uiState.value.localModelLoaded || (client != null && client.isLoaded))
        val isGeminiLoaded = (_uiState.value.activeModelType == ModelType.GEMINI && geminiClient != null && geminiClient!!.isLoaded)

        if (!isLocalLoaded && !isGeminiLoaded) {
            Log.i(TAG, "⚡ [Enrichment Interceptor] No LLM currently loaded. Proceeding with raw prompt.")
            return@withContext shortPrompt
        }

        val enrichmentSystemPrompt = "You are an expert image prompt generator. Convert the user's short request into a highly detailed, comma-separated diffusion prompt. Do not add any conversational text."
        val userActualInput = if (shortPrompt.startsWith("/image", ignoreCase = true)) shortPrompt else "/image $shortPrompt"

        val fewShotHistory = listOf(
            "user" to "/image a cat",
            "assistant" to "A fluffy orange cat sitting on a wooden table, sunlight streaming through a window, highly detailed, 4k, cinematic lighting, sharp focus.",
            "user" to userActualInput
        )

        Log.i(TAG, "⚡ [Enrichment Interceptor] Few-Shot Prompting for: \"$userActualInput\" via active LLM...")
        _uiState.update { it.copy(
            activeStatusDetail = "Enriching visual prompt with LLM...",
            loadingStatusText = "Enriching visual prompt with LLM..."
        ) }
        addSystemLog("⚡ [Enrichment Interceptor] Generating enriched diffusion prompt via Few-Shot LLM...")

        val expandedSb = StringBuilder()
        try {
            withTimeoutOrNull(25_000L) {
                if (isLocalLoaded && client != null) {
                    client.generateStreamWithHistory(
                        history = fewShotHistory,
                        systemPrompt = enrichmentSystemPrompt,
                        overrideTemperature = 0.5f
                    ).collect { chunk ->
                        expandedSb.append(chunk)
                    }
                } else if (isGeminiLoaded && geminiClient != null) {
                    val messages = JSONArray().apply {
                        for ((role, content) in fewShotHistory) {
                            put(JSONObject().put("role", role).put("content", content))
                        }
                    }
                    geminiClient!!.generateStreamWithOllamaMessages(
                        messages = messages,
                        systemPrompt = enrichmentSystemPrompt
                    ).collect { chunk ->
                        expandedSb.append(chunk)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚡ [Enrichment Interceptor] Prompt enrichment exception: ${e.message}")
        }

        val rawExpanded = expandedSb.toString().trim()
        val cleaned = cleanEnrichedPrompt(rawExpanded, shortPrompt)
        Log.i(TAG, "⚡ [Enrichment Interceptor] Final enriched prompt: \"$cleaned\"")
        addSystemLog("⚡ [Enrichment Interceptor] Enriched Prompt: \"$cleaned\"")
        return@withContext cleaned
    }

    private fun cleanEnrichedPrompt(raw: String, fallback: String): String {
        if (raw.isBlank()) return fallback
        var cleaned = raw.replace(Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<think>[\s\S]*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^```(?:markdown|text|plaintext)?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*```$"""), "")
            .trim()

        cleaned = cleaned.replace(Regex("""^(?:Assistant|System|User)\s*:\s*""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""^(?:Expanded Prompt|Prompt|Output|Enriched Prompt)\s*:\s*""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.trim().removeSurrounding("\"").removeSurrounding("'").trim()

        if (cleaned.contains("[Your", ignoreCase = true) || cleaned.contains("[Insert", ignoreCase = true) || cleaned.contains("[complete", ignoreCase = true)) {
            Log.w(TAG, "⚡ [Enrichment Interceptor] Detected placeholder echo: \"$cleaned\". Falling back to raw prompt.")
            return fallback
        }

        if (cleaned.length < 5) return fallback
        return cleaned
    }

    suspend fun executeSequentialImageSynthesis(prompt: String): ImageResult = withContext(Dispatchers.Default) {
        val hadLocalModel = (_uiState.value.activeModelType == ModelType.GGUF ||
                             _uiState.value.localModelLoaded ||
                             (ggufClient != null && ggufClient!!.isLoaded))
        val modelPath = _uiState.value.config.localModelPath.ifBlank { currentLoadingPath ?: "" }
        val targetContext = appContext ?: MainActivity.instance

        if (!imageRepository.localDiffusionEngine.hasModelWeights()) {
            Log.i(TAG, "⚡ [Sequential Image Synthesis] No local diffusion weights detected on storage. Bypassing RAM swap and invoking authentic pipeline directly.")
            return@withContext imageRepository.generateImage(prompt)
        }

        Log.i(TAG, "⚡ [RAM Swap] Initiating Sequential Memory Swapping for prompt: \"$prompt\" (hadLocalModel=$hadLocalModel)")

        if (hadLocalModel) {
            Log.i(TAG, "⚡ [RAM Swap] Step 1/4: Suspending LLM to reclaim RAM...")
            _uiState.update { it.copy(
                activeStatusDetail = "Allocating Diffusion Engine...",
                loadingStatusText = "Allocating Diffusion Engine..."
            ) }
            addSystemLog("⚡ [RAM Swap] Step 1/4: Suspending LLM weights to reclaim RAM...")

            try {
                LlamaNativeEngine.unloadModel()
            } catch (t: Throwable) {
                Log.w(TAG, "LlamaNativeEngine.unloadModel error: ${t.message}")
            }
            try {
                ggufClient?.stop()
                ggufClient?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "ggufClient cleanup error: ${t.message}")
            }
            try {
                PageCacheAdvisor.adviseModelIdle(modelPath)
            } catch (_: Throwable) {}

            _uiState.update { it.copy(
                localModelLoaded = false,
                activeStatusDetail = "Allocating Diffusion Engine..."
            ) }
            System.gc()
            System.runFinalization()
        }

        Log.i(TAG, "⚡ [RAM Swap] Step 2/4: Synthesizing visual locally...")
        _uiState.update { it.copy(
            activeStatusDetail = "Synthesizing Image...",
            loadingStatusText = "Synthesizing Image..."
        ) }
        addSystemLog("⚡ [RAM Swap] Step 2/4: Synthesizing visual locally...")

        val diffusionEngine = imageRepository.localDiffusionEngine
        val result: ImageResult
        try {
            diffusionEngine.loadDiffusionModel()
            result = diffusionEngine.generateImage(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "Error during local diffusion generation: ${e.message}", e)
            return@withContext ImageResult.Error("Local diffusion failed: ${e.message}")
        } finally {
            Log.i(TAG, "⚡ [RAM Swap] Step 3/4: Flushing Diffusion Engine...")
            _uiState.update { it.copy(
                activeStatusDetail = "Flushing Diffusion Engine...",
                loadingStatusText = "Flushing Diffusion Engine..."
            ) }
            addSystemLog("⚡ [RAM Swap] Step 3/4: Flushing Diffusion Engine...")
            try {
                diffusionEngine.unloadDiffusionModel()
            } catch (t: Throwable) {
                Log.w(TAG, "unloadDiffusionModel error: ${t.message}")
            }
            System.gc()
            System.runFinalization()
        }

        if (hadLocalModel && modelPath.isNotBlank() && targetContext != null) {
            Log.i(TAG, "⚡ [RAM Swap] Step 4/4: Reloading LLM into RAM...")
            _uiState.update { it.copy(
                activeStatusDetail = "Reloading LLM into RAM...",
                loadingStatusText = "Reloading LLM into RAM..."
            ) }
            addSystemLog("⚡ [RAM Swap] Step 4/4: Reloading LLM into RAM...")
            try {
                PageCacheAdvisor.adviseModelActive(modelPath)
            } catch (_: Throwable) {}
            try {
                triggerModelLoad(targetContext, modelPath)
                withTimeoutOrNull(60_000L) {
                    _uiState.first { it.localModelLoaded || it.modelLoadFailed }
                }
                Log.i(TAG, "⚡ [RAM Swap] Step 4/4: LLM reloaded into RAM successfully.")
                addSystemLog("⚡ [RAM Swap] LLM Reloaded and Operational ✓")
            } catch (e: Exception) {
                Log.e(TAG, "Error reloading LLM: ${e.message}", e)
            }
        }

        _uiState.update { it.copy(
            activeStatusDetail = "Ready / Operational",
            loadingStatusText = ""
        ) }

        return@withContext result
    }

    private var currentJob: Job? = null
    private var schedulerJob: Job? = null
    private var visualMemoryJob: Job? = null
    private val scheduledTasks = Collections.synchronizedList(mutableListOf<ScheduledTask>())
    private val workflows = Collections.synchronizedMap(mutableMapOf<String, String>())
    
    private val pendingCommands = Collections.synchronizedList(mutableListOf<String>())
    
    private var coreMission: String = ""
    private val conversationHistory = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    private fun trimConversationHistoryToContextLimit(targetTokenCeiling: Int? = null) {
        val maxEstimatedTokens = targetTokenCeiling?.coerceAtLeast(1024) ?: run {
            val ctx = _uiState.value.config.contextLength.coerceIn(2048, 8192)
            (ctx - 1500).coerceAtLeast(1024)
        }
        synchronized(conversationHistory) {
            var estimatedTokens = conversationHistory.sumOf { (_, content) ->
                (content.length / 3.5).toInt() + 10
            }
            while (conversationHistory.size > 2 && (estimatedTokens > maxEstimatedTokens || conversationHistory.size > MAX_HISTORY_MESSAGES)) {
                val removed = conversationHistory.removeAt(0)
                estimatedTokens -= ((removed.second.length / 3.5).toInt() + 10)
            }
        }
    }

    private val executedCallsThisTurn = Collections.synchronizedSet(mutableSetOf<String>())
    private val inFlightCalls = Collections.synchronizedSet(mutableSetOf<String>())

    fun computeCallHash(toolName: String, params: Map<String, String>): String {
        val normalizedTool = toolName.trim().lowercase()
        val normalizedParams = params.toSortedMap().entries
            .joinToString("&") { "${it.key.trim().lowercase()}=${it.value.trim()}" }
        val raw = "$normalizedTool|$normalizedParams"
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(raw.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun resolvePackageName(target: String, pm: PackageManager?): String {
        return AppLauncherManager.resolvePackageName(target, pm)
    }

    fun launchApplication(target: String, context: Context? = null): Pair<Boolean, String> {
        val ctx = context ?: appContext ?: MainActivity.instance
        val res = AppLauncherManager.launch(target, ctx)
        return res.success to (if (res.success) res.appLabel else (res.errorMessage ?: "Failed to launch application"))
    }

    private fun extractTargetFromAmStart(cmd: String): String? {
        val nMatch = Regex("""-n\s+([a-zA-Z0-9_.]+)(?:/[^\s]+)?""").find(cmd)
        if (nMatch != null) return nMatch.groupValues[1].trim()

        val pMatch = Regex("""-p\s+([a-zA-Z0-9_.]+)""").find(cmd)
        if (pMatch != null) return pMatch.groupValues[1].trim()

        val words = cmd.split(Regex("""\s+"""))
        for (word in words) {
            val cleanWord = word.trim()
            if (cleanWord.contains(".") && !cleanWord.startsWith("-") && cleanWord != "am" && cleanWord != "start") {
                val pkg = if (cleanWord.contains("/")) cleanWord.substringBefore("/") else cleanWord
                if (pkg.matches(Regex("""^[a-zA-Z0-9_.]+$"""))) {
                    return pkg
                }
            }
        }
        return null
    }

    private fun isExecutableCodeStreaming(text: String): Boolean {
        val lastBackticks = text.lastIndexOf("```")
        if (lastBackticks == -1) return false
        val after = text.substring(lastBackticks + 3)
        if (after.contains("```")) return false
        val lang = after.substringBefore('\n').trim().lowercase()
        return lang in EXECUTABLE_LANGUAGES
    }

    private fun cleanVisibleText(raw: String): String {
        var text = raw
        text = text.replace(Regex("""```(?:thought|thinking|reasoning)\r?\n[\s\S]*?(?:```|$)""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<\|?(?:think|thought|reasoning)\|?>[\s\S]*?</\|?(?:think|thought|reasoning)\|?>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<\|?(?:think|thought|reasoning)\|?>[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<function_calls>[\s\S]*?</function_calls>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<invoke[\s\S]*?</invoke>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<function_calls>[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<invoke[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<terminal_frame[\s\S]*?</terminal_frame>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<terminal_frame[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""Shell output \(`[^`]+`\):\s*```(?:bash|sh)?\r?\n[\s\S]*?```""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""</?\|?(?:think|thought|reasoning)\|?>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""</?terminal_frame>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""^💭[\s\S]*?\n\n""", RegexOption.DOT_MATCHES_ALL), "")
        return text.trim()
    }

    private fun stripThinkingForHistory(raw: String): String {
        var text = raw
        text = text.replace(Regex("""```(?:thought|thinking|reasoning)\r?\n[\s\S]*?(?:```|$)""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<\|?(?:think|thought|reasoning)\|?>[\s\S]*?</\|?(?:think|thought|reasoning)\|?>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<\|?(?:think|thought|reasoning)\|?>[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""</?\|?(?:think|thought|reasoning)\|?>""", RegexOption.IGNORE_CASE), "")
        return text.trim()
    }

    private fun compactHistoryForQuota(content: String): String {
        val maxLen = if (_uiState.value.config.contextLength >= 16384) 16000 else 2400
        if (!content.contains("```")) return content.take(maxLen)
        return content.replace(Regex("""```([a-zA-Z0-9_\-]*)\n([\s\S]*?)```""")) { mr ->
            val lang = mr.groupValues[1]
            val code = mr.groupValues[2]
            if (code.length > 2000) {
                val preview = code.take(1000).trimEnd()
                val lineCount = code.lines().size
                "```$lang\n$preview\n// ... [$lineCount lines deliverable saved to disk] ...\n```"
            } else {
                mr.value
            }
        }.take(maxLen)
    }

    fun sanitizeProcessOutput(raw: String, maxChars: Int = 600, maxLines: Int = 15): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "[Process completed with no output]"

        val lines = trimmed.lines()
        val lineCapped = if (lines.size > maxLines) {
            lines.takeLast(maxLines).joinToString("\n")
        } else {
            trimmed
        }

        val charCapped = if (lineCapped.length > maxChars) {
            lineCapped.takeLast(maxChars)
        } else {
            lineCapped
        }

        return charCapped.trim().ifEmpty { "[Process completed with no output]" }
    }

    fun truncateObservation(raw: String, maxChars: Int = 600, maxLines: Int = 15): String {
        return sanitizeProcessOutput(raw, maxChars, maxLines)
    }

    suspend fun executeSafeProcess(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (command.isBlank()) return@withContext -1 to "[Process completed with no output]"

        try {
            val (exitCode, rawOutput) = runProcessWithWatchdog(command, "sh", 12_000L)
            val sanitizedOutput = sanitizeProcessOutput(rawOutput)
            exitCode to sanitizedOutput
        } catch (e: Exception) {
            -1 to sanitizeProcessOutput("Execution error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    data class ParsedThought(
        val thought: String,
        val isThinking: Boolean,
        val content: String
    )

    fun parseThoughtAndContent(raw: String): ParsedThought {
        val demuxed = StreamDemultiplexer.demux(raw)
        val contentParts = demuxed.blocks.mapNotNull { block ->
            when (block) {
                is StreamDemultiplexer.DemuxBlock.Prose -> block.text
                is StreamDemultiplexer.DemuxBlock.Code -> {
                    val lang = block.language
                    val pathHeader = if (!block.filePath.isNullOrBlank()) " filepath=\"${block.filePath}\"" else ""
                    "```$lang$pathHeader\n${block.code}\n```"
                }
                is StreamDemultiplexer.DemuxBlock.TerminalFrame -> {
                    val safeCmd = StreamDemultiplexer.escapeXml(block.command)
                    val safeOut = StreamDemultiplexer.escapeXml(block.output)
                    """<terminal_frame command="$safeCmd" exit_code="${block.exitCode}">$safeOut</terminal_frame>"""
                }
                is StreamDemultiplexer.DemuxBlock.ToolCall -> null
                is StreamDemultiplexer.DemuxBlock.Image -> "[Image: ${block.prompt.ifBlank { "Generated visual" }}]"
            }
        }
        val content = if (contentParts.isNotEmpty()) {
            contentParts.joinToString("\n\n").trim()
        } else {
            cleanVisibleText(raw)
        }

        return ParsedThought(
            thought = demuxed.thinkingText,
            isThinking = demuxed.isThinking,
            content = content
        )
    }

    fun scanSpecializedAgents() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = File(AGENTS_DIR)
                if (!dir.exists()) dir.mkdirs()
                val files = dir.listFiles { f: File -> f.extension.lowercase() == "md" }?.toList() ?: emptyList<File>()
                _uiState.update { it.copy(specializedAgents = files) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to scan agents: ${e.message}")
            }
        }
    }

    private var appContext: Context? = null

    val settingsViewModel: SettingsViewModel by lazy {
        val app = (appContext as? Application)
            ?: (MainActivity.instance?.application)
            ?: throw IllegalStateException("Application context unavailable")
        SettingsViewModel(app)
    }

    private val _customPersonaPrompt = MutableStateFlow(PersonaDataStore.DEFAULT_PROMPT)
    val customPersonaPrompt: StateFlow<String> = _customPersonaPrompt.asStateFlow()

    fun updateCustomPersonaPrompt(newPrompt: String, context: Context? = null) {
        _customPersonaPrompt.value = newPrompt
        cachedStaticPrefix = null
        cachedPrefixKey = ""
        val ctx = context ?: appContext ?: MainActivity.instance
        if (ctx != null) {
            viewModelScope.launch(Dispatchers.IO) {
                PersonaDataStore.saveCustomPersonaPrompt(ctx, newPrompt)
            }
        }
    }

    fun resetCustomPersonaPrompt(context: Context? = null) {
        val defaultVal = PersonaDataStore.DEFAULT_PROMPT
        _customPersonaPrompt.value = defaultVal
        cachedStaticPrefix = null
        cachedPrefixKey = ""
        val ctx = context ?: appContext ?: MainActivity.instance
        if (ctx != null) {
            viewModelScope.launch(Dispatchers.IO) {
                PersonaDataStore.saveCustomPersonaPrompt(ctx, defaultVal)
            }
        }
    }

    suspend fun getEffectivePersonaPrompt(context: Context? = null): String {
        val targetCtx = context ?: appContext ?: MainActivity.instance
        val prompt = if (targetCtx != null) {
            PersonaDataStore.getCustomPersonaPrompt(targetCtx)
        } else {
            _customPersonaPrompt.value.ifBlank { PersonaDataStore.DEFAULT_PROMPT }
        }
        _customPersonaPrompt.value = prompt
        return prompt
    }

    fun loadConfig(context: Context) {
        appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loaded = PersonaDataStore.getCustomPersonaPrompt(context)
                _customPersonaPrompt.value = loaded
                launch {
                    PersonaDataStore.getCustomPersonaPromptFlow(context).collectLatest { prompt ->
                        _customPersonaPrompt.value = prompt
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load customPersonaPrompt from DataStore: ${e.message}")
            }

            Log.i(TAG, "Starting background loadConfig on Dispatchers.IO...")
            try {
                if (portalServer == null) {
                    portalServer = PortalServer(this@AgentViewModel)
                    portalServer?.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "PortalServer init failed", e)
            }

            try {
                val prefs = prefs(context)
                val savedPath = prefs.getString("local_model_path", null)
                
                val hasMigrated4k = prefs.getBoolean("has_migrated_4k_baseline_context", false)
                var savedContext = prefs.getInt("context_length", 4096)
                if (!hasMigrated4k || savedContext < 4096) {
                    savedContext = 4096
                    prefs.edit()
                        .putBoolean("has_migrated_4k_baseline_context", true)
                        .putInt("context_length", 4096)
                        .putInt("thread_count", 4)
                        .putBoolean("use_mmap", true)
                        .apply()
                } else {
                    savedContext = savedContext.coerceIn(2048, 8192)
                }

                val modelTypeStr = prefs.getString("preferred_model_type", ModelType.GEMINI.name)
                val modelType = try { ModelType.valueOf(modelTypeStr!!) } catch (_: Exception) { ModelType.GEMINI }
                var geminiKeys = prefs.getString("gemini_api_keys", "") ?: ""
                var geminiModel = prefs.getString("gemini_model_name", "gemini-2.0-flash") ?: "gemini-2.0-flash"
                ModelScanner.ensureDirectories()
                val activePersonasSet = PersonaManager.getActivePersonas(context)
                val activePersonaName = activePersonasSet.firstOrNull() ?: PersonaManager.DEFAULT_PERSONA
                val cachedPersonasList = PersonaManager.getPersonas(context, forceRescan = false)
                
                var localPath = savedPath ?: ""
                val isCorruptLegacy = localPath.contains("qwen-to-mobilellm-r1-140m", ignoreCase = true)
                if (localPath.isBlank() || isCorruptLegacy || !File(localPath).exists() || !ModelScanner.isBaseModelFile(File(localPath))) {
                    val cachedModels = ModelScanner.getBaseModels(context, forceRescan = true)
                        .filter { ModelScanner.isBaseModelFile(it) }
                    if (cachedModels.isNotEmpty()) {
                        val preferred = cachedModels.firstOrNull { ModelScanner.isQuantizedModel(it) } ?: cachedModels.first()
                        localPath = preferred.absolutePath
                        prefs.edit().putString("local_model_path", localPath).apply()
                    }
                }
                val rawDraft = AgentSettingsRepository.getDraftModelPath(context).ifBlank {
                    prefs.getString("draft_model_path", "") ?: ""
                }
                val isExplicitStandalone = rawDraft == "STANDALONE"
                val draftFile = if (!isExplicitStandalone && rawDraft.isNotBlank()) File(rawDraft) else null
                val isDraftValid = draftFile != null && draftFile.exists() &&
                    (draftFile.name.contains("gemma", ignoreCase = true) || ModelScanner.isMtpModelFile(draftFile))

                val draftPath = when {
                    isExplicitStandalone -> "STANDALONE"
                    isDraftValid -> rawDraft
                    else -> {
                        if (localPath.isNotBlank()) {
                            val matched = ModelScanner.findMatchingMtpModel(localPath, context)
                            if (matched != null && matched.exists()) {
                                matched.absolutePath
                            } else {
                                "STANDALONE"
                            }
                        } else {
                            "STANDALONE"
                        }
                    }
                }
                val gpuLayers = prefs.getInt("gpu_layers", 0)
                val pinModelInRam = prefs.getBoolean("pin_model_in_ram", false)
                val useMmap = if (pinModelInRam) false else prefs.getBoolean("use_mmap", true)
                val draftUseMmap = if (pinModelInRam) false else prefs.getBoolean("draft_use_mmap", true)

                val activeKeysList = geminiKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val activeGeminiKeyCount = activeKeysList.size
                var effectiveModelType = modelType
                if (effectiveModelType == ModelType.GEMINI && activeGeminiKeyCount == 0 && localPath.isNotBlank()) {
                    effectiveModelType = ModelType.GGUF
                }
                
                val config = AgentConfig(
                    preferredModelType = effectiveModelType,
                    localModelPath = localPath,
                    draftModelPath = draftPath,
                    gpuLayers = gpuLayers,
                    useMmap = useMmap,
                    draftUseMmap = draftUseMmap,
                    pinModelInRam = pinModelInRam,
                    geminiApiKeys = geminiKeys,
                    geminiModelName = geminiModel,
                    serverHost = prefs.getString("server_host", "0.0.0.0") ?: "0.0.0.0",
                    serverPort = prefs.getInt("server_port", 8088),
                    contextLength = if (savedContext in 2048..8192) savedContext else 4096,
                    threadCount = prefs.getInt("thread_count", 4).let { if (it in 1..32) it else 4 },
                    maxTokens = prefs.getInt("max_tokens", 2048).let { if (it <= 0) 2048 else it },
                    temperature = prefs.getFloat("temperature", 0.4f).let { if (it == 0.7f || it == 0.5f) 0.4f else it },
                    enableVisualHistory = prefs.getBoolean("enable_visual_history", false),
                    enableNeuralReflexes = prefs.getBoolean("enable_neural_reflexes", false),
                    enabledSpecializedAgents = prefs.getStringSet("enabled_agents", emptySet()) ?: emptySet(),
                    systemPrompt = prefs.getString("system_prompt", "") ?: "",
                    customToolsPrompt = prefs.getString("custom_tools_prompt", "") ?: "",
                    showApiKeyTracker = prefs.getBoolean("show_api_key_tracker", true),
                    activePersona = activePersonaName,
                    activePersonas = activePersonasSet,
                    ttsEnabled = prefs.getBoolean("tts_enabled", true),
                    ttsEngineType = prefs.getString("tts_engine_type", "HUMAN_NEURAL") ?: "HUMAN_NEURAL",
                    ttsVoiceLanguage = prefs.getString("tts_voice_language", "AUTO") ?: "AUTO",
                    ttsSpeechRate = prefs.getFloat("tts_speech_rate", 1.0f),
                    ttsPitch = prefs.getFloat("tts_pitch", 0.88f),
                    customAcousticModelPath = prefs.getString("custom_acoustic_model_path", "/sdcard/Medlam-Ai/tts/qwen3_tts_acoustic_int8.onnx") ?: "/sdcard/Medlam-Ai/tts/qwen3_tts_acoustic_int8.onnx",
                    customVocoderModelPath = prefs.getString("custom_vocoder_model_path", "/sdcard/Medlam-Ai/tts/qwen3_tts_vocoder_fp16.onnx") ?: "/sdcard/Medlam-Ai/tts/qwen3_tts_vocoder_fp16.onnx",
                    customSpeakerEmbeddingPath = prefs.getString("custom_speaker_embedding_path", "") ?: "",
                    sdSteps = prefs.getInt("sd_steps", 0),
                    sdCfgScale = prefs.getFloat("sd_cfg_scale", 0f),
                    sdWidth = prefs.getInt("sd_width", 256).let { if (it <= 0) 256 else it },
                    sdHeight = prefs.getInt("sd_height", 256).let { if (it <= 0) 256 else it },
                    sdLowMemoryMode = prefs.getBoolean("sd_low_memory_mode", true),
                    sdThreads = prefs.getInt("sd_threads", 4).let { if (it <= 0) 4 else it },
                    sdEngineMode = prefs.getString("sd_engine_mode", "SD_TURBO") ?: "SD_TURBO",
                    sdComputeDevice = prefs.getString("sd_compute_device", "CPU") ?: "CPU"
                )

                getOrCreateCascadeInterceptor(context).applyConfig(config)

                val savedKeyIndex = prefs.getInt("current_key_index", 0)
                val safeKeyIndex = if (activeGeminiKeyCount > 0) savedKeyIndex.coerceIn(0, activeGeminiKeyCount - 1) else 0

                val newDraftPath = if (config.draftModelPath.isNotBlank() && config.draftModelPath != "STANDALONE") config.draftModelPath else null
                val baseModelName = if (config.localModelPath.isNotBlank()) {
                    File(config.localModelPath).nameWithoutExtension
                } else {
                    val baseModels = ModelScanner.getBaseModels(context)
                    baseModels.firstOrNull()?.nameWithoutExtension ?: "GEMMA-4-E2B Q4"
                }

                _uiState.update { 
                    it.copy(
                        config = config, 
                        totalKeys = activeGeminiKeyCount,
                        currentKeyIndex = safeKeyIndex,
                        activePersona = activePersonaName,
                        activePersonas = activePersonasSet,
                        availablePersonas = cachedPersonasList,
                        activeDraftModelPath = newDraftPath,
                        activeModelName = baseModelName,
                        isTtsEnabled = config.ttsEnabled
                    ) 
                }
                prefs.edit()
                    .putInt("context_length", config.contextLength)
                    .putInt("max_tokens", -1)
                    .apply()

                consolidateWorkspaceDirectories()

                if (chatDb == null) {
                    chatDb = ChatDatabaseHelper(context)
                }
                chatDb?.cleanupEmptySessions()
                refreshSessions()

                startNewSession()
                _uiState.update { it.copy(
                    logs = emptyList(),
                    resumptionNotice = null,
                    activeWorkspacePath = null
                ) }

                loadWorkflows(prefs)
                initTaskSystem(context)
                startScheduler()
                scanSpecializedAgents()

                if (config.preferredModelType == ModelType.GGUF && config.localModelPath.isNotBlank()) {
                    triggerModelLoad(context, config.localModelPath)
                } else if (config.preferredModelType == ModelType.GEMINI && activeGeminiKeyCount > 0) {
                    loadGeminiModel(config.geminiApiKeys, config.geminiModelName, context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "initialize failed", e)
                addLog(LogEntry(sender = "SYSTEM", text = "Boot Error: ${e.message}", isError = true))
            }
        }
    }

    fun setDraftModelPath(context: Context, path: String) {
        val trimmed = path.trim()
        val currentConfig = _uiState.value.config
        val updatedConfig = currentConfig.copy(draftModelPath = trimmed)
        val effectiveDraft = if (trimmed.isNotBlank() && trimmed != "STANDALONE") trimmed else null

        _uiState.update { 
            it.copy(
                config = updatedConfig,
                activeDraftModelPath = effectiveDraft
            ) 
        }

        AgentSettingsRepository.setDraftModelPath(context, trimmed)
        try {
            prefs(context).edit().putString("draft_model_path", trimmed).commit()
        } catch (_: Exception) {}

        if (updatedConfig.preferredModelType == ModelType.GGUF && updatedConfig.localModelPath.isNotBlank()) {
            closeAllModels()
            viewModelScope.launch(Dispatchers.IO) {
                delay(300)
                triggerModelLoad(context, updatedConfig.localModelPath)
            }
        }
    }

    fun saveConfig(context: Context, config: AgentConfig) {
        val baseNameLower = File(config.localModelPath).name.lowercase()
        val baseFamily = when {
            baseNameLower.contains("gemma") -> "gemma"
            baseNameLower.contains("qwen") -> "qwen"
            baseNameLower.contains("llama") -> "llama"
            baseNameLower.contains("minicpm") -> "minicpm"
            baseNameLower.contains("phi") -> "phi"
            baseNameLower.contains("deepseek") -> "deepseek"
            else -> ""
        }
        val draftNameLower = if (config.draftModelPath.isNotBlank() && config.draftModelPath != "STANDALONE") File(config.draftModelPath).name.lowercase() else ""
        val draftFamily = when {
            draftNameLower.contains("gemma") -> "gemma"
            draftNameLower.contains("qwen") -> "qwen"
            draftNameLower.contains("llama") -> "llama"
            draftNameLower.contains("minicpm") -> "minicpm"
            draftNameLower.contains("phi") -> "phi"
            draftNameLower.contains("deepseek") -> "deepseek"
            else -> ""
        }
        val safeDraftModelPath = if (baseFamily.isNotEmpty() && draftFamily.isNotEmpty() && baseFamily != draftFamily) {
            "STANDALONE"
        } else {
            config.draftModelPath
        }

        val finalConfig = config.copy(
            draftModelPath = safeDraftModelPath,
            contextLength = config.contextLength.coerceIn(2048, 8192),
            gpuLayers = config.gpuLayers.coerceAtLeast(0),
            threadCount = if (config.threadCount in 1..32) config.threadCount else 4
        )
        val oldConfig = _uiState.value.config
        val oldPath = oldConfig.localModelPath
        val oldGeminiKeys = oldConfig.geminiApiKeys
        val oldType = oldConfig.preferredModelType

        val activeKeys = finalConfig.geminiApiKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val newDraftPath = if (finalConfig.draftModelPath.isNotBlank() && finalConfig.draftModelPath != "STANDALONE") finalConfig.draftModelPath else null
        val baseModelName = if (finalConfig.localModelPath.isNotBlank()) {
            File(finalConfig.localModelPath).nameWithoutExtension
        } else {
            _uiState.value.activeModelName.ifBlank { "GEMMA-4-E2B Q4" }
        }
        _uiState.update { 
            it.copy(
                config = finalConfig, 
                totalKeys = activeKeys.size,
                activeDraftModelPath = newDraftPath,
                activeModelName = baseModelName,
                isTtsEnabled = finalConfig.ttsEnabled
            ) 
        }
        
        getOrCreateCascadeInterceptor(context).applyConfig(finalConfig)

        AgentSettingsRepository.saveConfig(context, finalConfig)
        val prefs = prefs(context)
        prefs.edit().apply {
            putString("local_model_path", finalConfig.localModelPath)
            putString("draft_model_path", finalConfig.draftModelPath)
            putInt("gpu_layers", finalConfig.gpuLayers)
            putBoolean("use_mmap", finalConfig.useMmap)
            putBoolean("draft_use_mmap", finalConfig.draftUseMmap)
            putBoolean("pin_model_in_ram", finalConfig.pinModelInRam)
            putString("preferred_model_type", finalConfig.preferredModelType.name)
            putString("gemini_api_keys", finalConfig.geminiApiKeys)
            putString("gemini_model_name", finalConfig.geminiModelName)
            putString("server_host", finalConfig.serverHost)
            putInt("server_port", finalConfig.serverPort)
            putInt("context_length", finalConfig.contextLength)
            putInt("thread_count", finalConfig.threadCount)
            putInt("max_tokens", finalConfig.maxTokens)
            putFloat("temperature", finalConfig.temperature)
            putBoolean("enable_visual_history", finalConfig.enableVisualHistory)
            putBoolean("enable_neural_reflexes", finalConfig.enableNeuralReflexes)
            putStringSet("enabled_agents", finalConfig.enabledSpecializedAgents)
            putString("system_prompt", finalConfig.systemPrompt)
            putString("custom_tools_prompt", finalConfig.customToolsPrompt)
            putBoolean("show_api_key_tracker", finalConfig.showApiKeyTracker)
            putBoolean("tts_enabled", finalConfig.ttsEnabled)
            putString("tts_engine_type", finalConfig.ttsEngineType)
            putString("tts_voice_language", finalConfig.ttsVoiceLanguage)
            putFloat("tts_speech_rate", finalConfig.ttsSpeechRate)
            putFloat("tts_pitch", finalConfig.ttsPitch)
            putString("custom_acoustic_model_path", finalConfig.customAcousticModelPath)
            putString("custom_vocoder_model_path", finalConfig.customVocoderModelPath)
            putString("custom_speaker_embedding_path", finalConfig.customSpeakerEmbeddingPath)
            putInt("sd_steps", finalConfig.sdSteps)
            putFloat("sd_cfg_scale", finalConfig.sdCfgScale)
            putInt("sd_width", finalConfig.sdWidth)
            putInt("sd_height", finalConfig.sdHeight)
            putBoolean("sd_low_memory_mode", finalConfig.sdLowMemoryMode)
            putInt("sd_threads", finalConfig.sdThreads)
            putString("sd_engine_mode", finalConfig.sdEngineMode)
            putString("sd_compute_device", finalConfig.sdComputeDevice)
            apply()
        }

        val oldDraftPath = oldConfig.draftModelPath
        val draftChanged = finalConfig.draftModelPath != oldDraftPath
        val modelChanged = finalConfig.localModelPath != oldPath
        val typeChanged = oldType != finalConfig.preferredModelType
        val mmapChanged = (finalConfig.useMmap != oldConfig.useMmap) ||
                          (finalConfig.draftUseMmap != oldConfig.draftUseMmap) ||
                          (finalConfig.pinModelInRam != oldConfig.pinModelInRam)
        val notLoaded = !isModelLoaded()

        if (finalConfig.preferredModelType == ModelType.GGUF) {
            if (modelChanged || draftChanged || typeChanged || mmapChanged || notLoaded) {
                closeAllModels()
                viewModelScope.launch(Dispatchers.IO) {
                    delay(300)
                    triggerModelLoad(context, finalConfig.localModelPath)
                }
            }
        } else if (finalConfig.preferredModelType == ModelType.GEMINI) {
            GGUFModelClient.stopGlobalServer()
            if (finalConfig.geminiApiKeys != oldGeminiKeys || 
                finalConfig.geminiModelName != oldConfig.geminiModelName ||
                !isModelLoaded() || oldType != ModelType.GEMINI) {
                closeAllModels()
                loadGeminiModel(finalConfig.geminiApiKeys, finalConfig.geminiModelName, context)
            }
        }
    }

    fun updateConfig(config: AgentConfig, context: Context) = saveConfig(context, config)

    fun toggleMmapState(enableMmap: Boolean, context: Context? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.localModelLoading || isModelLoadingInProgress) return@launch

            if (_uiState.value.localModelLoaded || isModelLoaded()) {
                closeAllModels()
            }

            val targetContext = context ?: appContext ?: MainActivity.instance
            val currentConfig = _uiState.value.config
            val newConfig = currentConfig.copy(useMmap = enableMmap)
            _uiState.update { it.copy(config = newConfig) }

            if (targetContext != null) {
                AgentSettingsRepository.saveConfig(targetContext, newConfig)
                try {
                    prefs(targetContext).edit().putBoolean("use_mmap", enableMmap).apply()
                } catch (_: Exception) {}
            }

            val selectedModelPath = newConfig.localModelPath
            if (newConfig.preferredModelType == ModelType.GGUF && selectedModelPath.isNotBlank() && targetContext != null) {
                delay(300)
                triggerModelLoad(targetContext, selectedModelPath)
            }
        }
    }

    fun toggleMmapState(enableMmap: Boolean) = toggleMmapState(enableMmap, null)

    fun toggleDraftMmapState(enableMmap: Boolean, context: Context? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.localModelLoading || isModelLoadingInProgress) return@launch

            if (_uiState.value.localModelLoaded || isModelLoaded()) {
                closeAllModels()
            }

            val targetContext = context ?: appContext ?: MainActivity.instance
            val currentConfig = _uiState.value.config
            val newConfig = currentConfig.copy(draftUseMmap = enableMmap)
            _uiState.update { it.copy(config = newConfig) }

            if (targetContext != null) {
                AgentSettingsRepository.saveConfig(targetContext, newConfig)
                try {
                    prefs(targetContext).edit().putBoolean("draft_use_mmap", enableMmap).apply()
                } catch (_: Exception) {}
            }

            val selectedModelPath = newConfig.localModelPath
            if (newConfig.preferredModelType == ModelType.GGUF && selectedModelPath.isNotBlank() && targetContext != null) {
                delay(300)
                triggerModelLoad(targetContext, selectedModelPath)
            }
        }
    }

    fun toggleDraftMmapState(enableMmap: Boolean) = toggleDraftMmapState(enableMmap, null)

    fun togglePinModelInRam(enable: Boolean, context: Context? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.localModelLoading || isModelLoadingInProgress) return@launch

            val targetContext = context ?: appContext ?: MainActivity.instance
            var finalEnable = enable

            if (enable && targetContext != null) {
                val actManager = targetContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                actManager?.getMemoryInfo(memInfo)

                val availRamMb = memInfo.availMem / (1024 * 1024)
                val modelFile = File(_uiState.value.config.localModelPath)
                val modelSizeMb = if (modelFile.exists()) modelFile.length() / (1024 * 1024) else 2000L
                val requiredRamMb = modelSizeMb + 1536L

                if (availRamMb <= requiredRamMb) {
                    finalEnable = false
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            targetContext,
                            "الذاكرة العشوائية غير كافية لتثبيت النموذج (${availRamMb}MB متاحة / ${requiredRamMb}MB مطلوبة). تم التراجع إلى نمط التخزين المتدفق (Zero-Forced-RAM mmap).",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            if (_uiState.value.localModelLoaded || isModelLoaded()) {
                closeAllModels()
            }

            val currentConfig = _uiState.value.config
            val newConfig = currentConfig.copy(
                pinModelInRam = finalEnable,
                useMmap = !finalEnable,
                draftUseMmap = !finalEnable
            )
            _uiState.update { it.copy(config = newConfig) }

            if (targetContext != null) {
                AgentSettingsRepository.saveConfig(targetContext, newConfig)
                try {
                    prefs(targetContext).edit()
                        .putBoolean("pin_model_in_ram", finalEnable)
                        .putBoolean("use_mmap", !finalEnable)
                        .putBoolean("draft_use_mmap", !finalEnable)
                        .apply()
                } catch (_: Exception) {}
            }

            val selectedModelPath = newConfig.localModelPath
            if (newConfig.preferredModelType == ModelType.GGUF && selectedModelPath.isNotBlank() && targetContext != null) {
                delay(300)
                triggerModelLoad(targetContext, selectedModelPath)
            }
        }
    }

    fun togglePinModelInRam(enable: Boolean) = togglePinModelInRam(enable, null)

    private var modelLoadJob: Job? = null
    @Volatile private var isModelLoadingInProgress = false
    @Volatile private var currentLoadingPath: String? = null

    suspend fun triggerModelLoad(context: Context, path: String) = withContext(Dispatchers.IO) {
        if (isModelLoadingInProgress && currentLoadingPath == path) {
            Log.i(TAG, "Model load already in progress for $path, ignoring duplicate trigger")
            return@withContext
        }
        if (ggufClient?.isLoaded == true && currentLoadingPath == path && _uiState.value.localModelLoaded) {
            Log.i(TAG, "Model already loaded and operational for $path")
            return@withContext
        }
        isModelLoadingInProgress = true
        currentLoadingPath = path
        modelLoadJob?.cancel()

        modelLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val type = detectModelType(path)
                val modelName = java.io.File(path).nameWithoutExtension
                val hwCheck = DeviceProfile.checkModelRequirements(context, path)
                if (hwCheck.warningMessage != null) {
                    addSystemLog("⚠️ ${hwCheck.warningMessage} (${String.format(java.util.Locale.US, "%.1fGB", hwCheck.modelSizeGb)} on ${String.format(java.util.Locale.US, "%.1fGB", hwCheck.totalRamGb)} RAM)")
                }
                
                _uiState.update { it.copy(
                    initPhase = InitializationPhase.ALLOCATING_MEMORY,
                    localModelLoading = true, 
                    localModelLoaded = false, 
                    modelLoadFailed = false,
                    activeModelType = null,
                    hardwareWarning = hwCheck.warningMessage,
                    loadingProgress = 0.05f,
                    loadingStatusText = "Allocating Memory..."
                ) }
                
                if (type == ModelType.GGUF) {
                    try {
                        val config = _uiState.value.config
                        val client = GGUFModelClient().apply {
                            serverHost = config.serverHost
                            serverPort = config.serverPort
                            contextLength = config.contextLength.coerceIn(2048, 8192)
                            threadCount = if (config.threadCount in 1..32) config.threadCount else 4
                            maxTokens = 2048
                            temperature = config.temperature
                            draftModelPath = config.draftModelPath.ifBlank { null }
                            useMmap = config.useMmap
                            draftUseMmap = config.draftUseMmap
                            gpuLayers = config.gpuLayers.coerceAtLeast(0)
                        }
                        ggufClient = client
                        client.loadModel(
                            context = context,
                            path = path,
                            onStatus = { status -> addSystemLog(status) },
                            onProgress = { progress, statusText ->
                                _uiState.update { it.copy(
                                    initPhase = InitializationPhase.ALLOCATING_MEMORY,
                                    loadingProgress = progress, 
                                    loadingStatusText = statusText
                                ) }
                            },
                            onLoaded = {
                                isModelLoadingInProgress = false
                                currentLoadingPath = null
                                if (client.isLoaded) {
                                    val effectiveDraft = client.activeDraftModelPath
                                    _uiState.update { it.copy(
                                        initPhase = InitializationPhase.PARSING_PERSONA,
                                        localModelLoaded = false,
                                        localModelLoading = true,
                                        modelLoadFailed = false,
                                        activeModelType = ModelType.GGUF,
                                        activeModelName = modelName,
                                        activeDraftModelPath = effectiveDraft,
                                        loadingProgress = 0.95f,
                                        loadingStatusText = "Parsing Persona..."
                                    ) }
                                    viewModelScope.launch(Dispatchers.IO) {
                                        try {
                                            val dynamicPersona = getEffectivePersonaPrompt(context)
                                            val systemPrompt = buildSystemPrompt(context, forLocalModel = true, dynamicPersonaPrompt = dynamicPersona)
                                            val primed = withTimeoutOrNull(12_000L) {
                                                client.warmupPersonaKVCache(systemPrompt)
                                            } ?: false
                                            if (primed) {
                                                addSystemLog("Persona KV Cache Primed & Pre-warmed ✓")
                                            } else {
                                                Log.i(TAG, "KV Cache pre-warm skipped or completed gracefully")
                                            }
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Background pre-warm notice: ${e.message}")
                                        } finally {
                                            _uiState.update { it.copy(
                                                initPhase = InitializationPhase.SYSTEM_READY,
                                                localModelLoaded = true, 
                                                localModelLoading = false, 
                                                modelLoadFailed = false,
                                                activeModelType = ModelType.GGUF, 
                                                activeModelName = modelName,
                                                activeDraftModelPath = effectiveDraft,
                                                loadingProgress = 1.0f,
                                                loadingStatusText = "System Ready."
                                            ) }
                                            addSystemLog("Local Model Active: $modelName" + if (effectiveDraft != null) " (MTP Active)" else " (STANDALONE)")
                                            val initialGreeting = "ATLAS-AI AGENT Core ready. Neural link established. How can I assist you today?"
                                            if (_uiState.value.logs.none { it.text.contains("ATLAS-AI AGENT Core ready") }) {
                                                addLog(LogEntry(
                                                    sender = "AGENT",
                                                    text = initialGreeting
                                                ))
                                            }
                                        }
                                    }
                                } else {
                                    _uiState.update { it.copy(
                                        initPhase = InitializationPhase.IDLE,
                                        localModelLoaded = false, 
                                        localModelLoading = false, 
                                        modelLoadFailed = true,
                                        activeDraftModelPath = null,
                                        loadingProgress = null,
                                        loadingStatusText = ""
                                    ) }
                                    addSystemLog("Model loading failed: $modelName")
                                }
                            }
                        )
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) {
                            Log.i(TAG, "Model load job cancelled for $path")
                            return@launch
                        }
                        isModelLoadingInProgress = false
                        currentLoadingPath = null
                        Log.e(TAG, "GGUF load failed", t)
                        addLog(LogEntry(sender = "SYSTEM", text = "Load Error: ${t.message}", isError = true))
                        _uiState.update { it.copy(
                            localModelLoaded = false, 
                            localModelLoading = false, 
                            modelLoadFailed = true,
                            loadingProgress = null,
                            loadingStatusText = ""
                        ) }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    return@launch
                }
                isModelLoadingInProgress = false
                currentLoadingPath = null
                Log.e(TAG, "Trigger model load error", t)
            } finally {
                if (coroutineContext[Job]?.isCancelled == true) {
                    isModelLoadingInProgress = false
                    currentLoadingPath = null
                }
            }
        }
    }

    private fun loadGeminiModel(apiKeysRaw: String, modelName: String = "gemini-2.0-flash", context: Context? = null) {
        val keys = apiKeysRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (keys.isEmpty()) return
        
        val targetCtx = context ?: appContext ?: MainActivity.instance
        val savedIdx = targetCtx?.let { prefs(it).getInt("current_key_index", 0) } ?: _uiState.value.currentKeyIndex
        val safeInitialIdx = if (keys.isNotEmpty()) savedIdx.coerceIn(0, keys.size - 1) else 0

        closeAllModels()
        geminiClient = GeminiModelClient(
            apiKeys = keys,
            initialKeyIndex = safeInitialIdx,
            onKeyRotated = { idx ->
                _uiState.update { it.copy(currentKeyIndex = idx, totalKeys = keys.size) }
                targetCtx?.let {
                    prefs(it).edit().putInt("current_key_index", idx).apply()
                }
            },
            onKeyValidated = { idx ->
                _uiState.update { it.copy(currentKeyIndex = idx, totalKeys = keys.size) }
                targetCtx?.let {
                    prefs(it).edit().putInt("current_key_index", idx).apply()
                }
            },
            onKeyUsed = { key ->
                targetCtx?.let {
                    KeyUsageManager.incrementUsage(it.applicationContext, key)
                }
            }
        )
        geminiClient?.modelName = modelName
        geminiClient?.isLoaded = true
        addSystemLog("AI Services Initialized (Key #${safeInitialIdx + 1}/${keys.size})")

        _uiState.update { it.copy(
            initPhase = InitializationPhase.SYSTEM_READY,
            activeModelType = ModelType.GEMINI,
            activeModelName = modelName,
            totalKeys = keys.size,
            currentKeyIndex = safeInitialIdx,
            modelLoadFailed = false
        ) }
        val initialGreeting = "ATLAS-AI AGENT Core ready. Neural link established. How can I assist you today?"
        if (_uiState.value.logs.none { it.text.contains("ATLAS-AI AGENT Core ready") }) {
            addLog(LogEntry(
                sender = "AGENT",
                text = initialGreeting
            ))
        }
    }

    private fun isModelLoaded(): Boolean {
        return (ggufClient != null && ggufClient!!.isLoaded) || 
               (geminiClient != null && geminiClient!!.isLoaded)
    }

    fun closeAllModels() {
        modelLoadJob?.cancel()
        modelLoadJob = null
        isModelLoadingInProgress = false
        GGUFModelClient.stopGlobalServer()
        ggufClient?.close()
        ggufClient = null
        geminiClient = null
        _uiState.update { it.copy(
            initPhase = InitializationPhase.IDLE,
            localModelLoaded = false, 
            localModelLoading = false, 
            loadingProgress = null,
            loadingStatusText = "",
            activeModelType = null, 
            activeModelName = "", 
            totalKeys = 0
        ) }
    }

    private fun getToolsDescription(): String {
        val bt = "\u0060\u0060\u0060"
        return """
## Host Action & System Toolchains:
You have direct programmatic access to the host Android system through these toolchains:

1. `run_shell(command: String)`: Execute terminal or shell commands directly on the host Android device with Superuser/Root permissions.
2. `web_search(query: String)`: Search the web silently for real-time facts, documentation, or live information.
3. `schedule_task(description: String, command: String, delay_minutes: Int)`: Schedule delayed headless background execution.
4. `queue_background_task(command: String)`: Queue a background automation script or command into the local Task Engine (tsk).
5. `complete(summary: String)`: Terminate and finalize an active multi-step automation mission.
6. `generate_image(prompt: String)`: Synthesize visual images, diagrams, or digital artwork completely offline on-device using the local diffusion engine.

Format for calling tools:
<function_calls>
<invoke name="tool_name">
<parameter name="param_name">value</parameter>
</invoke>
</function_calls>

## Autonomous Workspace File Engine:
To create or update files in the local workspace (`/sdcard/Medlam-Ai/...`), specify the file path immediately above a fenced code block:
file: /sdcard/Medlam-Ai/webapp/index.html
${bt}html
<!DOCTYPE html>
<html>...</html>
$bt
The file execution engine automatically parses this block and writes the file directly to device storage.
""".trimIndent()
    }

    @Volatile
    private var cachedStaticPrefix: String? = null
    @Volatile
    private var cachedPrefixKey: String = ""

    fun isChatPersonaActive(): Boolean = false

    private fun appendEosControlToken(prompt: String, modelPath: String): String {
        return prompt.trimEnd()
    }

    fun isSimpleGreeting(text: String): Boolean {
        val clean = text.trim().lowercase(Locale.ROOT)
            .replace("!", "")
            .replace(".", "")
            .replace("?", "")
            .trim()
        val greetings = setOf(
            "hello", "hi", "hey", "howdy", "greetings", "good morning", "good afternoon", "good evening",
            "مرحبا", "اهلا", "أهلا", "سلام", "السلام عليكم", "صباح الخير", "مساء الخير",
            "bonjour", "salut", "coucou", "bonsoir", "hola", "yo"
        )
        return clean in greetings || (clean.startsWith("hello") && clean.length < 10) || (clean.startsWith("hi") && clean.length < 5)
    }

    fun isGreeting(text: String): Boolean = isSimpleGreeting(text)

    private fun isCodeRequest(prompt: String): Boolean {
        if (isSimpleGreeting(prompt)) return false
        val lower = prompt.lowercase(Locale.ROOT)
        val arabicKeywords = listOf("كود", "برمج", "تطبيق", "صفحة", "موقع", "أنشئ", "اصنع", "اكتب", "برنامج", "حاسبة", "لعبة", "طور")
        if (arabicKeywords.any { lower.contains(it) }) return true

        val phrases = listOf("write code", "generate code", "web app", "web page", "landing page", "index.html", "source code", "create an app", "build an app")
        if (phrases.any { lower.contains(it) }) return true

        val words = lower.split(Regex("[^a-z0-9_#+-]+")).filter { it.isNotBlank() }.toSet()
        val codeWords = setOf(
            "code", "html", "css", "javascript", "js", "python", "script", "app",
            "build", "website", "webpage", "function", "class", "program", "develop",
            "calculator", "game", "frontend", "backend", "flutter", "kotlin", "react", "vue"
        )
        return words.any { it in codeWords }
    }

    private fun buildSystemPrompt(
        context: Context? = null,
        forLocalModel: Boolean = false,
        dynamicPersonaPrompt: String? = null,
        isCodeRequest: Boolean = false
    ): String {
        val config = _uiState.value.config
        val actualContext = context ?: appContext ?: MainActivity.instance
        val customPrompt = config.systemPrompt.trim()
        val effectivePersona = (dynamicPersonaPrompt ?: _customPersonaPrompt.value.ifBlank {
            actualContext?.let { PersonaDataStore.getCustomPersonaPromptSync(it) } ?: PersonaDataStore.DEFAULT_PROMPT
        }).trim()

        val antiMonologueDirective = if (isCodeRequest) {
            "\n\nCRITICAL: For code generation, bypass all logical reasoning and <think> steps. Begin your response IMMEDIATELY with the markdown code block. Do not output internal thoughts."
        } else ""

        if (forLocalModel) {
            val custom = if (customPrompt.isNotBlank()) "\n$customPrompt" else ""
            val prompt = """$effectivePersona
Tools: open_app(pkg), run_shell(cmd), write_file(path, content), web_search(query).
Format: <invoke name="tool_name"><parameter name="param">value</parameter></invoke>
Or: <tool_call>{"name": "bash", "command": "free -h"}</tool_call>
SYNCHRONOUS REACT RULE: Halt text generation immediately when calling tools. Never guess outputs.
SYSTEM STATE GUARDRAIL: When observations are provided in <observation>...</observation>, parse and state the EXACT numbers and metrics from stdout (RAM Total, Used, Free, Swap, CPU %, Temperature). Never invent or guess metrics.
Format code in ```lang fences.$custom$antiMonologueDirective""".trimIndent()
            return appendEosControlToken(prompt, config.localModelPath)
        }

        val currentKey = "PERSONA:${effectivePersona.hashCode()}|$customPrompt|$isCodeRequest"

        if (cachedPrefixKey == currentKey && cachedStaticPrefix != null) {
            return cachedStaticPrefix!!
        }

        val livingMind = actualContext?.let { EpistemicMemoryManager.getDistilledContextPrompt(it) } ?: ""

        val toolsAndGuidelines = """
## Host Toolchains & Execution Guidelines:
CRITICAL UI RULE: Never output raw shell commands, heredocs (cat << 'EOF'), or raw command logs directly into conversational dialogue. For creating or editing files, always call the dedicated write_file tool. To launch an Android app, always call open_app(package_name) natively; never pipe 'am start' through run_shell. When calling run_shell, supply clean, concise system commands only. Keep conversational responses focused on human-readable summaries, architectural analysis, and verified status reports.
ZERO-SURRENDER & AUTONOMOUS TOOL CHAINING RULE: You are strictly forbidden from telling the user to browse manually or answering 'I could not find results, please check manually in your browser'. You must autonomously chain tools (refining web_search with 2-4 distilled keywords, targeting site:domain queries, or running curl via run_shell) until live data is retrieved.
SYNCHRONOUS REACT RULE: When invoking tools, stop text generation immediately.
SYSTEM STATE GUARDRAIL: When tool observations are provided in <observation>...</observation>, parse and state the EXACT numbers and metrics from the tool output (RAM Total, Used, Free, Swap, CPU %, Temperature). Do not invent or estimate values.
You are an autonomous on-device CLI execution agent. You operate headlessly via shell commands and explicit file system writes. You have no accessibility service or screen-scraping capabilities. Tool calls must strictly follow schema definitions and must never be enclosed inside markdown code blocks or file streams. When performing an action, invoke the tool directly; do not generate HTML files to represent execution.
- App Launching: Use open_app(package_or_app_name) (e.g. youtube, whatsapp, com.android.chrome) to launch Android applications natively. Aliases, localized names, shell fallback, and web deep-links are resolved automatically. Do NOT pipe am start through run_shell.
- Deliver code strictly inside ```lang code fences only when programming or requested to write code.
- To write files to device storage (/sdcard/Medlam-Ai/...), use write_file(path, content) or place `file: <path>` immediately above the code fence.
- Conclude with a high-level summary confirmation without echoing raw code.
- Tool invocation format:
<function_calls>
<invoke name="tool_name"><parameter name="param_name">value</parameter></invoke>
</function_calls>
- Available tools: open_app(package_name), run_shell(command), write_file(path, content), web_search(query), schedule_task(description, command, delay_minutes), queue_background_task(command), complete(summary).
""".trimIndent()

        val customPromptSection = if (customPrompt.isNotBlank()) "\n\n$customPrompt" else ""

        val compiled = "$livingMind\n\n$effectivePersona\n\n$toolsAndGuidelines$customPromptSection$antiMonologueDirective"
        cachedPrefixKey = currentKey
        cachedStaticPrefix = compiled
        return compiled
    }

    fun sendCommand(command: String, fileUri: Uri? = null, context: Context? = null) {
        if (command.isBlank() && fileUri == null) return
        
        val actualContext = context ?: MainActivity.instance
        val trimmed = command.trim()
        cascadeInterceptor?.stopAndFlush()

        if (trimmed.startsWith("$")) {
            val shellCmd = trimmed.removePrefix("$").trim()
            autoTitleSessionIfNew(command)
            val userLog = LogEntry(sender = "USER", text = command)
            addLog(userLog)
            val sid = currentSessionId.value
            viewModelScope.launch(Dispatchers.IO) {
                chatDb?.insertMessage(
                    ChatMessageEntity(
                        id = userLog.id,
                        sessionId = sid,
                        sender = "USER",
                        text = userLog.text,
                        timestamp = userLog.timestamp
                    )
                )
                val (exitCode, output) = runShellWithExitCode(shellCmd)
                val safeCmd = StreamDemultiplexer.escapeXml(shellCmd)
                val safeOut = StreamDemultiplexer.escapeXml(output.trim())
                val terminalFrameText = """<terminal_frame command="$safeCmd" exit_code="$exitCode">$safeOut</terminal_frame>"""
                val agentLog = LogEntry(sender = "AGENT", text = terminalFrameText)
                addLog(agentLog)
                chatDb?.insertMessage(
                    ChatMessageEntity(
                        id = agentLog.id,
                        sessionId = sid,
                        sender = "AGENT",
                        text = terminalFrameText,
                        timestamp = agentLog.timestamp
                    )
                )
                refreshSessions()
            }
            return
        }

        // FIXED: Do NOT intercept /image as an unknown slash command
        if (trimmed.startsWith("/") && !trimmed.startsWith("/image", ignoreCase = true) && MediaIntentClassifier.classify(trimmed) is MediaIntent.TextDialogue) {
            val slashCmd = trimmed.substringBefore(" ").lowercase()
            autoTitleSessionIfNew(command)
            val userLog = LogEntry(sender = "USER", text = command)
            addLog(userLog)
            val sid = currentSessionId.value
            viewModelScope.launch(Dispatchers.IO) {
                chatDb?.insertMessage(
                    ChatMessageEntity(
                        id = userLog.id,
                        sessionId = sid,
                        sender = "USER",
                        text = userLog.text,
                        timestamp = userLog.timestamp
                    )
                )
                refreshSessions()
            }

            when (slashCmd) {
                "/code", "/sys", "/chat" -> {
                    addLog(LogEntry(sender = "SYSTEM", text = "Persona commands are retired. Medlam-AI operates with Unified Core Prompt."))
                }
                "/rollback" -> {
                    executeRollback(actualContext)
                }
                "/preview" -> {
                    openLiveCanvas()
                }
                "/clear" -> {
                    actualContext?.let { clearConversation(it) }
                }
                else -> {
                    addLog(LogEntry(sender = "SYSTEM", text = "Unknown command: $slashCmd. Available: /rollback, /preview, /clear"))
                }
            }
            return
        }

        if (_uiState.value.isProcessing) {
            synchronized(pendingCommands) {
                pendingCommands.add(command)
            }
            addLog(LogEntry(sender = "SYSTEM", text = "Command queued (${pendingCommands.size} in queue)"))
            return
        }
        autoTitleSessionIfNew(command)
        val userLog = LogEntry(sender = "USER", text = command.takeIf { it.isNotBlank() } ?: "[Attached File]")
        addLog(userLog)
        val sid = currentSessionId.value

        viewModelScope.launch(Dispatchers.IO) {
            chatDb?.insertMessage(
                ChatMessageEntity(
                    id = userLog.id,
                    sessionId = sid,
                    sender = "USER",
                    text = userLog.text,
                    timestamp = userLog.timestamp
                )
            )
            refreshSessions()
        }

        val lowerCmd = command.lowercase().trim()
        val isBackgroundRequest = lowerCmd.contains("in background") || lowerCmd.contains("فالخلفية") || 
            lowerCmd.startsWith("task:") || lowerCmd.startsWith("tsk:")
        if (isBackgroundRequest) {
            val cleanCmd = command
                .replace("in background", "", ignoreCase = true)
                .replace("فالخلفية", "")
                .replace("task:", "", ignoreCase = true)
                .replace("tsk:", "", ignoreCase = true)
                .trim()
            val taskId = taskEngine?.queueCommand(cleanCmd, source = "USER") ?: UUID.randomUUID().toString().take(8)
            updateTaskState()
            val confirmMsg = if (isArabic(command)) {
                "تمت جدولة المهمة في الخلفية [tsk #$taskId]: $cleanCmd"
            } else {
                "Queued background task [tsk #$taskId]: $cleanCmd"
            }
            addLog(userLog)
            addLog(LogEntry(sender = "AGENT", text = confirmMsg))
            viewModelScope.launch(Dispatchers.IO) {
                chatDb?.insertMessage(ChatMessageEntity(id = userLog.id, sessionId = sid, sender = "USER", text = userLog.text, timestamp = userLog.timestamp))
                chatDb?.insertMessage(ChatMessageEntity(id = UUID.randomUUID().toString(), sessionId = sid, sender = "AGENT", text = confirmMsg, timestamp = System.currentTimeMillis()))
                refreshSessions()
            }
            return
        }

        currentJob = viewModelScope.launch(Dispatchers.IO) {
            executedCallsThisTurn.clear()
            inFlightCalls.clear()
            _uiState.update { 
                it.copy(
                    isProcessing = true,
                    tokensPerSecond = 0f,
                    activeWorkspacePath = null,
                    lastExecutionResult = "",
                    streamInterruptMsg = null
                ) 
            }
            
            try {
                var finalCommand = command
                val imageBase64List = mutableListOf<String>()

                val isWeatherOrSearch = lowerCmd.contains("weather") || lowerCmd.contains("طقس") || 
                    lowerCmd.contains("météo") || lowerCmd.contains("meteo") || 
                    lowerCmd.contains("search for") || lowerCmd.contains("ابحث عن") ||
                    lowerCmd.contains("what is the weather") || lowerCmd.contains("حالة الطقس")
                if (isWeatherOrSearch) {
                    try {
                        val searchResult = WebSearchTool.search(command)
                        if (searchResult.isNotBlank() && !searchResult.startsWith("No direct") && !searchResult.startsWith("Internet retrieval failed")) {
                            finalCommand += "\n\n[Live Real-Time Observation]:\n$searchResult"
                        }
                    } catch (_: Exception) {}
                }

                if (fileUri != null && actualContext != null) {
                    val cr = actualContext.contentResolver
                    if ((cr.getType(fileUri) ?: "").startsWith("image/")) {
                        val bitmap = MediaStore.Images.Media.getBitmap(cr, fileUri)
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        imageBase64List.add(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                    } else {
                        val text = DocumentTool.extractTextFromUri(actualContext, fileUri)
                        finalCommand += "\n\n[FILE]:\n$text"
                    }
                }

                val modelType = _uiState.value.activeModelType
                if (modelType == null || !isModelLoaded()) {
                    val modelPath = _uiState.value.config.localModelPath
                    if (_uiState.value.config.preferredModelType == ModelType.GGUF && modelPath.isNotBlank() && File(modelPath).exists()) {
                        addLog(LogEntry(sender = "SYSTEM", text = "Starting local model: ${File(modelPath).name}...", isError = false))
                        actualContext?.let { ctx -> triggerModelLoad(ctx, modelPath) }
                        return@launch
                    }
                    val hint = if (modelPath.isNotBlank())
                        "Engine not ready. Check logs in /sdcard/Medlam-Ai/"
                    else
                        "No model active. Please add Gemini API keys or choose a GGUF model in Settings."
                    addLog(LogEntry(sender = "AGENT", text = hint, isError = false))
                    return@launch
                }
                
                synchronized(conversationHistory) {
                    conversationHistory.add("user" to finalCommand)
                }

                // FIXED: Direct Routing for /image commands
                val mediaIntent = if (finalCommand.trim().startsWith("/image", ignoreCase = true)) {
                    val cleanPrompt = finalCommand.trim().removePrefix("/image").trim()
                    MediaIntent.GenerateImage(prompt = cleanPrompt, rawInput = finalCommand, isArabic = isArabic(cleanPrompt))
                } else {
                    MediaIntentClassifier.classify(finalCommand)
                }

                if (mediaIntent !is MediaIntent.TextDialogue) {
                    val isCloud = modelType == ModelType.GEMINI &&
                                  _uiState.value.config.geminiApiKeys.isNotBlank()
                    val targetCtx = actualContext ?: appContext ?: MainActivity.instance

                    when (mediaIntent) {
                        is MediaIntent.GenerateImage -> {
                            val rawImagePrompt = mediaIntent.prompt
                            val engineDesc = if (isCloud) "Google Imagen 3 (Cloud)" else "Local Neural Diffusion (Storage-Streamed)"
                            val liveLogId = UUID.randomUUID().toString()
                            val initialLiveLog = LogEntry(
                                id = liveLogId,
                                sender = "AGENT",
                                text = "🎨 Synthesizing visual via $engineDesc...\nConcept: \"$rawImagePrompt\"",
                                isThinking = true
                            )
                            _uiState.update {
                                it.copy(
                                    logs = it.logs + initialLiveLog,
                                    currentPhase = AgentActionPhase.EXECUTING_TOOL,
                                    activeStatusDetail = "Synthesizing visual..."
                                )
                            }
                            _imageState.value = ImageState.Generating(rawImagePrompt)

                            if (isCloud) {
                                val enrichedPrompt = enrichVisualPromptWithLLM(rawImagePrompt)
                                val result = imageRepository.generateImage(enrichedPrompt)
                                when (result) {
                                    is ImageResult.Success -> {
                                        _imageState.value = ImageState.Success(result.imageBytes, enrichedPrompt)
                                        val savedFile = saveImageToDisk(result.imageBytes, enrichedPrompt)
                                        val pathNote = if (savedFile != null) "\n📁 Saved to: ${savedFile.absolutePath}" else ""
                                        val promptDisplay = if (enrichedPrompt != rawImagePrompt) "\nPrompt: \"$enrichedPrompt\"" else ""
                                        val successText = "✔ Visual Generated Successfully for: \"$rawImagePrompt\"$promptDisplay$pathNote"

                                        _uiState.update { state ->
                                            val updatedLogs = state.logs.toMutableList()
                                            val idx = updatedLogs.indexOfLast { it.id == liveLogId }
                                            val entry = LogEntry(
                                                id = liveLogId,
                                                sender = "AGENT",
                                                text = successText,
                                                isThinking = false,
                                                imageBytes = result.imageBytes
                                            )
                                            if (idx >= 0) updatedLogs[idx] = entry else updatedLogs.add(entry)
                                            state.copy(
                                                logs = updatedLogs,
                                                isProcessing = false,
                                                currentPhase = AgentActionPhase.COMPLETING,
                                                activeStatusDetail = "Ready / Operational"
                                            )
                                        }
                                        synchronized(conversationHistory) {
                                            conversationHistory.add("assistant" to successText)
                                            trimConversationHistoryToContextLimit()
                                        }
                                        chatDb?.insertMessage(
                                            ChatMessageEntity(
                                                id = liveLogId,
                                                sessionId = sid,
                                                sender = "AGENT",
                                                text = if (savedFile != null) "<!--IMAGE_PATH:${savedFile.absolutePath}-->\n$successText" else successText,
                                                timestamp = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                    is ImageResult.Error -> {
                                        _imageState.value = ImageState.Error(result.message)
                                        val errorText = "✖ Image Generation Failed: ${result.message}"
                                        _uiState.update { state ->
                                            val updatedLogs = state.logs.toMutableList()
                                            val idx = updatedLogs.indexOfLast { it.id == liveLogId }
                                            val entry = LogEntry(
                                                id = liveLogId,
                                                sender = "AGENT",
                                                text = errorText,
                                                isError = true,
                                                isThinking = false
                                            )
                                            if (idx >= 0) updatedLogs[idx] = entry else updatedLogs.add(entry)
                                            state.copy(
                                                logs = updatedLogs,
                                                isProcessing = false,
                                                currentPhase = AgentActionPhase.COMPLETING,
                                                activeStatusDetail = "Ready / Operational"
                                            )
                                        }
                                        chatDb?.insertMessage(
                                            ChatMessageEntity(
                                                id = liveLogId,
                                                sessionId = sid,
                                                sender = "AGENT",
                                                text = errorText,
                                                isError = true,
                                                timestamp = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                }
                            } else {
                                val brokerResult = DynamicMediaBroker.dispatchImageRequest(
                                    mediaIntent,
                                    targetCtx ?: appContext!!,
                                    onStatusUpdate = { statusMsg ->
                                        _uiState.update { state ->
                                            val updatedLogs = state.logs.toMutableList()
                                            val idx = updatedLogs.indexOfLast { it.id == liveLogId }
                                            if (idx >= 0) {
                                                updatedLogs[idx] = updatedLogs[idx].copy(text = statusMsg)
                                            }
                                            state.copy(
                                                logs = updatedLogs,
                                                activeStatusDetail = statusMsg
                                            )
                                        }
                                    }
                                )
                                when (brokerResult) {
                                    is MediaResult.ImageSuccess -> {
                                        _imageState.value = ImageState.Success(brokerResult.imageBytes, brokerResult.prompt)
                                        val savedFile = saveImageToDisk(brokerResult.imageBytes, brokerResult.prompt)
                                        val pathNote = if (savedFile != null) "\n📁 Saved to: ${savedFile.absolutePath}" else ""
                                        val modelNote = "\n⚙ Model: ${brokerResult.modelUsed}"
                                        val successText = "✔ Visual Generated Successfully for: \"$rawImagePrompt\"$modelNote$pathNote"

                                        _uiState.update { state ->
                                            val updatedLogs = state.logs.toMutableList()
                                            val idx = updatedLogs.indexOfLast { it.id == liveLogId }
                                            val entry = LogEntry(
                                                id = liveLogId,
                                                sender = "AGENT",
                                                text = successText,
                                                isThinking = false,
                                                imageBytes = brokerResult.imageBytes
                                            )
                                            if (idx >= 0) updatedLogs[idx] = entry else updatedLogs.add(entry)
                                            state.copy(
                                                logs = updatedLogs,
                                                isProcessing = false,
                                                currentPhase = AgentActionPhase.COMPLETING,
                                                activeStatusDetail = "Ready / Operational"
                                            )
                                        }
                                        synchronized(conversationHistory) {
                                            conversationHistory.add("assistant" to successText)
                                            trimConversationHistoryToContextLimit()
                                        }
                                        chatDb?.insertMessage(
                                            ChatMessageEntity(
                                                id = liveLogId,
                                                sessionId = sid,
                                                sender = "AGENT",
                                                text = if (savedFile != null) "<!--IMAGE_PATH:${savedFile.absolutePath}-->\n$successText" else successText,
                                                timestamp = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                    is MediaResult.FallbackMessage -> {
                                        val fallbackText = brokerResult.localizedMessage()
                                        _uiState.update { state ->
                                            val updatedLogs = state.logs.toMutableList()
                                            val idx = updatedLogs.indexOfLast { it.id == liveLogId }
                                            val entry = LogEntry(
                                                id = liveLogId,
                                                sender = "AGENT",
                                                text = fallbackText,
                                                isError = false,
                                                isThinking = false
                                            )
                                            if (idx >= 0) updatedLogs[idx] = entry else updatedLogs.add(entry)
                                            state.copy(
                                                logs = updatedLogs,
                                                isProcessing = false,
                                                currentPhase = AgentActionPhase.COMPLETING,
                                                activeStatusDetail = "Ready / Operational"
                                            )
                                        }
                                        synchronized(conversationHistory) {
                                            conversationHistory.add("assistant" to fallbackText)
                                            trimConversationHistoryToContextLimit()
                                        }
                                        chatDb?.insertMessage(
                                            ChatMessageEntity(
                                                id = liveLogId,
                                                sessionId = sid,
                                                sender = "AGENT",
                                                text = fallbackText,
                                                isError = false,
                                                timestamp = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                    is MediaResult.Error -> {
                                        _imageState.value = ImageState.Error(brokerResult.message)
                                        val errorText = "✖ Image Generation Failed: ${brokerResult.message}"
                                        _uiState.update { state ->
                                            val updatedLogs = state.logs.toMutableList()
                                            val idx = updatedLogs.indexOfLast { it.id == liveLogId }
                                            val entry = LogEntry(
                                                id = liveLogId,
                                                sender = "AGENT",
                                                text = errorText,
                                                isError = true,
                                                isThinking = false
                                            )
                                            if (idx >= 0) updatedLogs[idx] = entry else updatedLogs.add(entry)
                                            state.copy(
                                                logs = updatedLogs,
                                                isProcessing = false,
                                                currentPhase = AgentActionPhase.COMPLETING,
                                                activeStatusDetail = "Ready / Operational"
                                            )
                                        }
                                        chatDb?.insertMessage(
                                            ChatMessageEntity(
                                                id = liveLogId,
                                                sessionId = sid,
                                                sender = "AGENT",
                                                text = errorText,
                                                isError = true,
                                                timestamp = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                    else -> {}
                                }
                            }
                        }
                        is MediaIntent.GenerateVideo -> {
                            val rawVideoPrompt = mediaIntent.prompt
                            val liveLogId = UUID.randomUUID().toString()
                            val initialLiveLog = LogEntry(
                                id = liveLogId,
                                sender = "AGENT",
                                text = "🎬 Processing video animation request: \"$rawVideoPrompt\"...",
                                isThinking = true
                            )
                            _uiState.update {
                                it.copy(
                                    logs = it.logs + initialLiveLog,
                                    currentPhase = AgentActionPhase.EXECUTING_TOOL,
                                    activeStatusDetail = "Synthesizing animation..."
                                )
                            }

                            val brokerResult = DynamicMediaBroker.dispatchVideoRequest(mediaIntent, targetCtx ?: appContext!!)
                            when (brokerResult) {
                                is MediaResult.VideoSuccess -> {
                                    val videoFile = saveVideoToDisk(brokerResult.videoBytes, brokerResult.prompt)
                                    val pathNote = if (videoFile != null) "\n📁 Saved to: ${videoFile.absolutePath}" else ""
                                    val modelNote = "\n⚙ Model: ${brokerResult.modelUsed}"
                                    val successText = "✔ Video Animation Generated for: \"$rawVideoPrompt\"$modelNote$pathNote"

                                    _uiState.update { state ->
                                        val updatedLogs = state.logs.toMutableList()
                                        val idx = updatedLogs.indexOfLast { it.id == liveLogId }
                                        val entry = LogEntry(
                                            id = liveLogId,
                                            sender = "AGENT",
                                            text = successText,
                                            isThinking = false
                                        )
                                        if (idx >= 0) updatedLogs[idx] = entry else updatedLogs.add(entry)
                                        state.copy(
                                            logs = updatedLogs,
                                            isProcessing = false,
                                            currentPhase = AgentActionPhase.COMPLETING,
                                            activeStatusDetail = "Ready / Operational"
                                        )
                                    }
                                    synchronized(conversationHistory) {
                                        conversationHistory.add("assistant" to successText)
                                        trimConversationHistoryToContextLimit()
                                    }
                                    chatDb?.insertMessage(
                                        ChatMessageEntity(
                                            id = liveLogId,
                                            sessionId = sid,
                                            sender = "AGENT",
                                            text = successText,
                                            timestamp = System.currentTimeMillis()
                                        )
                                    )
                                }
                                is MediaResult.FallbackMessage -> {
                                    val fallbackText = brokerResult.localizedMessage()
                                    _uiState.update { state ->
                                        val updatedLogs = state.logs.toMutableList()
                                        val idx = updatedLogs.indexOfLast { it.id == liveLogId }
                                        val entry = LogEntry(
                                            id = liveLogId,
                                            sender = "AGENT",
                                            text = fallbackText,
                                            isError = false,
                                            isThinking = false
                                        )
                                        if (idx >= 0) updatedLogs[idx] = entry else updatedLogs.add(entry)
                                        state.copy(
                                            logs = updatedLogs,
                                            isProcessing = false,
                                            currentPhase = AgentActionPhase.COMPLETING,
                                            activeStatusDetail = "Ready / Operational"
                                        )
                                    }
                                    synchronized(conversationHistory) {
                                        conversationHistory.add("assistant" to fallbackText)
                                        trimConversationHistoryToContextLimit()
                                    }
                                    chatDb?.insertMessage(
                                        ChatMessageEntity(
                                            id = liveLogId,
                                            sessionId = sid,
                                            sender = "AGENT",
                                            text = fallbackText,
                                            isError = false,
                                            timestamp = System.currentTimeMillis()
                                        )
                                    )
                                }
                                is MediaResult.Error -> {
                                    val errorText = "✖ Video Generation Failed: ${brokerResult.message}"
                                    _uiState.update { state ->
                                        val updatedLogs = state.logs.toMutableList()
                                        val idx = updatedLogs.indexOfLast { it.id == liveLogId }
                                        val entry = LogEntry(
                                            id = liveLogId,
                                            sender = "AGENT",
                                            text = errorText,
                                            isError = true,
                                            isThinking = false
                                        )
                                        if (idx >= 0) updatedLogs[idx] = entry else updatedLogs.add(entry)
                                        state.copy(
                                            logs = updatedLogs,
                                            isProcessing = false,
                                            currentPhase = AgentActionPhase.COMPLETING,
                                            activeStatusDetail = "Ready / Operational"
                                        )
                                    }
                                    chatDb?.insertMessage(
                                        ChatMessageEntity(
                                            id = liveLogId,
                                            sessionId = sid,
                                            sender = "AGENT",
                                            text = errorText,
                                            isError = true,
                                            timestamp = System.currentTimeMillis()
                                        )
                                    )
                                }
                                else -> {}
                            }
                        }
                        else -> {}
                    }
                    refreshSessions()
                    return@launch
                }

                val isChatMode = isChatPersonaActive()
                val dynamicPersona = getEffectivePersonaPrompt(actualContext)
                val isCode = !isGreeting(finalCommand) && isCodeRequest(finalCommand)
                val systemPrompt = buildSystemPrompt(
                    context = actualContext,
                    forLocalModel = modelType == ModelType.GGUF,
                    dynamicPersonaPrompt = dynamicPersona,
                    isCodeRequest = isCode
                )
                var keepGoing = true
                var iterationCount = 0
                var toolsUsedThisTurn = false
                var toolCallCount = 0
                val MAX_TOOL_CALLS = 2
                var activeGgufPromptPrefix: String? = null
                var lastExecutedToolCall: Pair<String, Pair<Int, String>>? = null

                while (keepGoing && iterationCount < MAX_TASK_ITERATIONS) {
                    iterationCount++
                    val responseSb = StringBuilder()
                    val liveLogId = UUID.randomUUID().toString()
                    var streamInterrupted = false
                    var interruptMsg = ""

                    val liveDiskStreamer = ToolExecutor.LiveFileStreamer(
                        baseDir = ToolExecutor.BASE_DIR,
                        onStatusUpdate = { status ->
                            _uiState.update { it.copy(activeStatusDetail = status, currentPhase = AgentActionPhase.GENERATING_CODE) }
                        }
                    )

                    val isSecondTurnAnalysis = toolsUsedThisTurn && iterationCount > 1
                    val isCodeTurn = !isGreeting(finalCommand) && isCodeRequest(finalCommand)
                    val initialPhase = if (isSecondTurnAnalysis) AgentActionPhase.ANALYZING_OUTPUT else if (isCodeTurn) AgentActionPhase.GENERATING_CODE else AgentActionPhase.COMPLETING
                    val initialDetail = if (isSecondTurnAnalysis) "Analyzing tool output..." else if (isCodeTurn) "Drafting Code & Architecture..." else "Replying..."

                    val initialLiveLog = LogEntry(
                        id = liveLogId,
                        sender = "AGENT",
                        text = "",
                        thinkingText = "",
                        isThinking = false
                    )
                    _uiState.update { it.copy(
                        logs = it.logs + initialLiveLog,
                        currentPhase = initialPhase,
                        activeStatusDetail = initialDetail,
                        streamInterruptMsg = null
                    ) }

                    var continuationCycle = 1
                    var totalAccumulatedTokens = 0
                    var continueRecursion = true
                    var hitStorageLimit = false
                    val continuationPromptPayload = "Continue generation exactly from the last emitted token. Do not repeat prior lines. Continue directly with the remaining code/text."

                    val historyCopy = synchronized(conversationHistory) {
                        trimConversationHistoryToContextLimit()
                        val historyList = conversationHistory.takeLast(MAX_HISTORY_MESSAGES).toList()
                        historyList.mapIndexed { idx, (role, content) ->
                            val isOlder = idx < historyList.size - 2
                            val safeContent = if (isOlder && role == "assistant") {
                                compactHistoryForQuota(content)
                            } else {
                                content
                            }
                            role to safeContent
                        }
                    }

                    try {
                        var lastUiUpdateTime = 0L
                        var pendingUiUpdate = false

                        val pushUiUpdate = { rawText: String ->
                            val demuxed = StreamDemultiplexer.demux(rawText)
                            liveDiskStreamer.onChunk(rawText)

                            val hasCodeBlock = demuxed.blocks.any { it is StreamDemultiplexer.DemuxBlock.Code }
                            val isCodeStreaming = demuxed.blocks.any { it is StreamDemultiplexer.DemuxBlock.Code && !it.isClosed }
                            val detectedPath = demuxed.blocks.filterIsInstance<StreamDemultiplexer.DemuxBlock.Code>().lastOrNull()?.filePath ?: ToolExecutor.detectFilePath(rawText)
                            val isWritingFile = rawText.contains("<invoke name=\"write_to_file\"") || (detectedPath != null && isCodeStreaming)
                            val parsedTools = demuxed.blocks.filter { it is StreamDemultiplexer.DemuxBlock.ToolCall || it is StreamDemultiplexer.DemuxBlock.TerminalFrame }
                            val isToolInvoking = parsedTools.isNotEmpty() || rawText.contains("<invoke") || rawText.contains("<tool_call") || rawText.contains("<function_call")

                            val statusDetail = when {
                                isChatMode -> "Replying..."
                                demuxed.isThinking -> "Thinking & Planning..."
                                isWritingFile -> {
                                    val path = detectedPath ?: "/sdcard/Medlam-Ai/..."
                                    "Writing to $path..."
                                }
                                isToolInvoking -> {
                                    val toolBlock = demuxed.blocks.filterIsInstance<StreamDemultiplexer.DemuxBlock.ToolCall>().firstOrNull()
                                    val toolName = toolBlock?.toolName ?: Regex("<invoke name=\"([^\"]+)\"").find(rawText)?.groupValues?.get(1)
                                    if (toolName != null) "Executing Tool / Task ($toolName)..." else "Executing Tool / Task..."
                                }
                                isCodeTurn && (isCodeStreaming || hasCodeBlock) -> "Drafting Code & Architecture..."
                                demuxed.isThinking -> "Thinking & Planning..."
                                else -> "Replying..."
                            }

                            val currentPhase = when {
                                isChatMode -> AgentActionPhase.COMPLETING
                                toolsUsedThisTurn && iterationCount > 1 -> AgentActionPhase.ANALYZING_OUTPUT
                                demuxed.isThinking -> AgentActionPhase.THINKING
                                isToolInvoking -> AgentActionPhase.EXECUTING_TOOL
                                isCodeStreaming -> AgentActionPhase.GENERATING_CODE
                                demuxed.blocks.isNotEmpty() -> AgentActionPhase.COMPLETING
                                else -> AgentActionPhase.THINKING
                            }

                            _uiState.update { state ->
                                val updatedLogs = state.logs.toMutableList()
                                val lastIdx = updatedLogs.indexOfLast { it.id == liveLogId }
                                if (lastIdx >= 0) {
                                    updatedLogs[lastIdx] = updatedLogs[lastIdx].copy(
                                        text = rawText,
                                        thinkingText = demuxed.thinkingText,
                                        isThinking = demuxed.isThinking
                                    )
                                }
                                state.copy(
                                    currentPhase = currentPhase,
                                    activeStatusDetail = statusDetail,
                                    logs = updatedLogs
                                )
                            }
                            scrollSignal.tryEmit(Unit)
                        }

                        while (continueRecursion && !streamInterrupted) {
                            if (continuationCycle > 1) {
                                if (StorageSentinel.isStorageCritical()) {
                                    hitStorageLimit = true
                                    liveDiskStreamer.flushAndSeal()
                                    val sentinelMsg = "Storage Sentinel: Disk limit reached (< 500MB remaining). Stream gracefully sealed."
                                    Log.w(TAG, sentinelMsg)
                                    val sentinelLog = LogEntry(
                                        id = UUID.randomUUID().toString(),
                                        sender = "AGENT",
                                        text = sentinelMsg,
                                        isThinking = false
                                    )
                                    _uiState.update { state ->
                                        state.copy(
                                            logs = state.logs + sentinelLog,
                                            isProcessing = false,
                                            activeStatusDetail = "Idle / Ready",
                                            currentPhase = AgentActionPhase.IDLE
                                        )
                                    }
                                    break
                                }
                            }

                            val cycleStartOffset = responseSb.length

                            val stream = when (modelType) {
                                ModelType.GGUF -> {
                                    val activeArch = ggufClient?.currentMetadata?.architecture ?: ChatTemplateEngine.Architecture.GEMMA
                                    val ggufPrompt = if (continuationCycle == 1) {
                                        activeGgufPromptPrefix ?: run {
                                            val p = ChatTemplateEngine.formatPrompt(
                                                history = historyCopy,
                                                systemDirective = systemPrompt,
                                                arch = activeArch,
                                                addAssistantPrompt = true
                                            )
                                            activeGgufPromptPrefix = p
                                            p
                                        }
                                    } else {
                                        (activeGgufPromptPrefix ?: "") + responseSb.toString() + ChatTemplateEngine.formatContinuationTurn(activeArch, continuationPromptPayload)
                                    }
                                    ggufClient!!.generateStreamWithRawPrompt(
                                        promptText = ggufPrompt,
                                        overrideTemperature = if (isCodeTurn) 0.15f else null,
                                        onSpeedUpdate = { tps ->
                                            val now = System.currentTimeMillis()
                                            if (now - lastTpsUpdateMs >= 500L) {
                                                lastTpsUpdateMs = now
                                                _uiState.update { it.copy(tokensPerSecond = tps) }
                                            }
                                        }
                                    )
                                }
                                ModelType.GEMINI -> {
                                    val json = JSONArray()
                                    // Normalize roles: Gemini API only accepts "user" or "model"
                                    historyCopy.forEachIndexed { idx, (role, content) ->
                                        val geminiRole = when (role.lowercase()) {
                                            "assistant", "model" -> "model"
                                            else -> "user"
                                        }
                                        val m = JSONObject().put("role", geminiRole).put("content", content)
                                        if (idx == historyCopy.size - 1 && imageBase64List.isNotEmpty() && continuationCycle == 1) {
                                            m.put("images", JSONArray(imageBase64List))
                                        }
                                        json.put(m)
                                    }
                                    if (continuationCycle > 1) {
                                        json.put(JSONObject().put("role", "model").put("content", responseSb.toString()))
                                        json.put(JSONObject().put("role", "user").put("content", continuationPromptPayload))
                                    }

                                    // UNIFIED ReAct TEXT FLOW: never call sendFunctionResponseStream.
                                    // Tool observations are already injected as <observation> text in
                                    // conversationHistory (role="user") — just forward the full history.
                                    // This avoids HTTP 400 Bad Request caused by sending a native
                                    // functionResponse when no native functionCall was issued.
                                    lastExecutedToolCall = null
                                    geminiClient!!.generateStreamWithOllamaMessages(json, systemPrompt) {
                                        if (continuationCycle == 1) {
                                            responseSb.setLength(0)
                                            liveDiskStreamer.reset()
                                            pushUiUpdate("")
                                        }
                                    }
                                }
                            }

                            var bufferedTokensCount = 0
                            try {
                                stream.collect { chunk ->
                                    responseSb.append(chunk)
                                    bufferedTokensCount++
                                    if (_uiState.value.isTtsEnabled) {
                                        val ctx = actualContext ?: appContext ?: MainActivity.instance
                                        if (ctx != null) {
                                            getOrCreateCascadeInterceptor(ctx).onTokenChunk(chunk)
                                        }
                                    }
                                    val now = System.currentTimeMillis()
                                    val elapsedMs = now - lastUiUpdateTime
                                    if (elapsedMs >= 50L) {
                                        lastUiUpdateTime = now
                                        bufferedTokensCount = 0
                                        pushUiUpdate(responseSb.toString())
                                        pendingUiUpdate = false
                                    } else {
                                        pendingUiUpdate = true
                                    }

                                    if (StreamDemultiplexer.hasCompletedToolCall(responseSb.toString())) {
                                        Log.i(TAG, "ReAct Barrier: Tool call completed in stream. Halting text generation immediately.")
                                        throw SynchronousToolBarrierException()
                                    }
                                }
                            } catch (e: Throwable) {
                                if (e is SynchronousToolBarrierException || e.cause is SynchronousToolBarrierException || e.message?.contains("Synchronous ReAct Barrier") == true) {
                                    Log.i(TAG, "ReAct Barrier: Text generation paused cleanly for tool execution.")
                                    continueRecursion = false
                                } else {
                                    throw e
                                }
                            }

                            if (StreamDemultiplexer.hasCompletedToolCall(responseSb.toString())) {
                                val truncated = StreamDemultiplexer.truncateAfterToolCall(responseSb.toString())
                                responseSb.setLength(0)
                                responseSb.append(truncated)
                                continueRecursion = false
                            }

                            if (pendingUiUpdate || bufferedTokensCount > 0) {
                                bufferedTokensCount = 0
                                pendingUiUpdate = false
                                pushUiUpdate(responseSb.toString())
                            }

                            if (_uiState.value.isTtsEnabled) {
                                cascadeInterceptor?.onStreamComplete()
                            }

                            val finishReason = when (modelType) {
                                ModelType.GEMINI -> geminiClient?.lastFinishReason ?: "STOP"
                                ModelType.GGUF -> ggufClient?.lastFinishReason ?: "STOP"
                            }

                            val chunkTokensEmitted = when (modelType) {
                                ModelType.GEMINI -> {
                                    val reported = geminiClient?.lastTokenCount ?: 0
                                    if (reported > 0) reported else maxOf(1, (responseSb.length - cycleStartOffset + 3) / 4)
                                }
                                ModelType.GGUF -> {
                                    val reported = ggufClient?.lastTokenCount ?: 0
                                    if (reported > 0) reported else maxOf(1, (responseSb.length - cycleStartOffset + 3) / 4)
                                }
                            }

                            totalAccumulatedTokens += chunkTokensEmitted

                            if (finishReason == "MAX_TOKENS") {
                                liveDiskStreamer.flush()
                                val formattedChunkTokens = NumberFormat.getNumberInstance(Locale.US).format(chunkTokensEmitted)
                                val chunkFinalizedMsg = "Generation Chunk $continuationCycle finalized ($formattedChunkTokens tokens) -> Storage Synced"
                                val nextCycleMsg = "Auto-extending token stream [Cycle ${continuationCycle + 1}]..."
                                Log.i(TAG, "$chunkFinalizedMsg\n$nextCycleMsg")

                                _uiState.update { state ->
                                    state.copy(
                                        activeStatusDetail = "Auto-extending token stream [Cycle ${continuationCycle + 1}]...",
                                        currentPhase = AgentActionPhase.GENERATING_CODE,
                                        isProcessing = true
                                    )
                                }

                                continuationCycle++
                                continueRecursion = true
                            } else {
                                continueRecursion = false
                                val formattedTotalTokens = NumberFormat.getNumberInstance(Locale.US).format(totalAccumulatedTokens)
                                val completeMsg = "Execution Complete [Total Tokens: $formattedTotalTokens]"
                                Log.i(TAG, completeMsg)

                                _uiState.update { state ->
                                    state.copy(
                                        activeStatusDetail = "Ready [${formattedTotalTokens} tokens]"
                                    )
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        if (e is SynchronousToolBarrierException || e.message?.contains("Synchronous ReAct Barrier") == true) {
                            Log.i(TAG, "ReAct Barrier: Text generation paused cleanly for tool execution at cycle level.")
                            continueRecursion = false
                        } else {
                            Log.i(TAG, "Stream cancelled by user cleanly.")
                            streamInterrupted = true
                            interruptMsg = ""
                            throw e
                        }
                    } catch (e: Exception) {
                        val lowerErr = (e.message ?: "").lowercase()
                        val isTokenCeiling = lowerErr.contains("max_tokens") || lowerErr.contains("token limit")
                        if (isTokenCeiling) {
                            Log.i(TAG, "Suppressed single-turn token error banner: ${e.message}")
                        } else {
                            streamInterrupted = true
                            interruptMsg = e.message ?: e.javaClass.simpleName
                            Log.w(TAG, "Stream interrupted: $interruptMsg")
                            _uiState.update { it.copy(streamInterruptMsg = interruptMsg) }
                        }
                    }

                    val fullResponse = responseSb.toString()
                    val demuxedFull = StreamDemultiplexer.demux(fullResponse)
                    val writtenFilesFromStream = liveDiskStreamer.closeAll(fullResponse)

                    if (hitStorageLimit) {
                        keepGoing = false
                        _uiState.update { state ->
                            state.copy(
                                isProcessing = false,
                                activeStatusDetail = "Idle / Ready",
                                currentPhase = AgentActionPhase.IDLE
                            )
                        }
                    }

                    if (fullResponse.isNotBlank()) {
                        if (streamInterrupted) {
                            val assistantReply = if (writtenFilesFromStream.isNotEmpty()) {
                                ToolExecutor.sanitizePostExecutionSummary(fullResponse, writtenFilesFromStream)
                            } else {
                                fullResponse
                            }
                            val cleanHistoryReply = stripThinkingForHistory(assistantReply)
                            val displayText = if (cleanHistoryReply.isNotBlank()) cleanHistoryReply else assistantReply.ifBlank { "[Stream Interrupted: $interruptMsg]" }
                            _uiState.update { state ->
                                val updated = state.logs.map { log ->
                                    if (log.id == liveLogId) log.copy(text = displayText, thinkingText = demuxedFull.thinkingText, isThinking = false) else log
                                }
                                state.copy(logs = updated)
                            }
                            val sid = currentSessionId.value
                            val dbText = if (demuxedFull.thinkingText.isNotBlank()) "<think>${demuxedFull.thinkingText}</think>\n$displayText" else displayText
                            viewModelScope.launch(Dispatchers.IO) {
                                chatDb?.insertMessage(
                                    ChatMessageEntity(
                                        id = liveLogId,
                                        sessionId = sid,
                                        sender = "AGENT",
                                        text = dbText,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                                refreshSessions()
                            }
                            keepGoing = false
                        } else {
                            val parsedToolCalls = StreamDemultiplexer.parseAllToolCalls(fullResponse)
                            if (parsedToolCalls.isNotEmpty()) {
                                toolsUsedThisTurn = true
                                val primaryCall = parsedToolCalls.first()
                                val action = primaryCall.name.trim().lowercase()
                                val params = primaryCall.params
                                val explanatoryText = StreamDemultiplexer.extractPreambleProse(fullResponse, primaryCall.rawMatch)

                                if (action in listOf("run_shell", "execute_shell", "terminal", "bash", "sh")) {
                                    val rawCmd = params["command"] ?: params["cmd"] ?: params.values.firstOrNull() ?: ""
                                    _uiState.update { it.copy(currentPhase = AgentActionPhase.EXECUTING_TOOL, activeStatusDetail = "Executing: $rawCmd...") }
                                    val heredoc = interceptHeredocFileWrite(rawCmd)
                                    val (finalCmd, exitCode, output) = if (heredoc != null) {
                                        heredoc
                                    } else {
                                        val (code, out) = executeSafeProcess(rawCmd)
                                        Triple(rawCmd, code, out)
                                    }
                                    val safeCmd = StreamDemultiplexer.escapeXml(finalCmd)
                                    val safeOut = StreamDemultiplexer.escapeXml(output.trim())
                                    val terminalFrame = """<terminal_frame command="$safeCmd" exit_code="$exitCode">$safeOut</terminal_frame>"""

                                    val lockedSegmentText = if (explanatoryText.isNotBlank()) "$explanatoryText\n\n$terminalFrame" else terminalFrame
                                    _uiState.update { state ->
                                        val updated = state.logs.map { log ->
                                            if (log.id == liveLogId) log.copy(text = lockedSegmentText, thinkingText = demuxedFull.thinkingText, isThinking = false) else log
                                        }
                                        state.copy(logs = updated, activeStatusDetail = "Analyzing output metrics...", currentPhase = AgentActionPhase.ANALYZING_OUTPUT)
                                    }
                                    val sid = currentSessionId.value
                                    val dbText = if (demuxedFull.thinkingText.isNotBlank()) "<think>${demuxedFull.thinkingText}</think>\n$lockedSegmentText" else lockedSegmentText
                                    viewModelScope.launch(Dispatchers.IO) {
                                        chatDb?.insertMessage(ChatMessageEntity(id = liveLogId, sessionId = sid, sender = "AGENT", text = dbText, timestamp = System.currentTimeMillis()))
                                        refreshSessions()
                                    }

                                    lastExecutedToolCall = primaryCall.name to (exitCode to output)
                                    toolCallCount++

                                    val activeArch = ggufClient?.currentMetadata?.architecture ?: ChatTemplateEngine.Architecture.GEMMA
                                    val observationDelta = ChatTemplateEngine.formatObservationDelta(activeArch, output)
                                    val observationMsg = """<observation>
[Process completed with exit code $exitCode]
$output
</observation>

SYSTEM STATE GUARDRAIL: Analyze the command observation above. You must strictly parse and report the actual values from stdout (such as Total, Used, Free, Available memory, Swap, CPU %, Temperature). Do NOT produce placeholder boilerplate, estimates, or fabricated numbers. State the real, verified metrics directly from the command output."""

                                    synchronized(conversationHistory) {
                                        val assistantTurn = if (explanatoryText.isNotBlank()) "$explanatoryText\n${primaryCall.rawMatch}" else primaryCall.rawMatch
                                        // Use "model" role — Gemini API requires "user"/"model", not "assistant"
                                        conversationHistory.add("model" to assistantTurn)
                                        conversationHistory.add("user" to observationMsg)
                                        trimConversationHistoryToContextLimit()
                                    }

                                    val currentBase = activeGgufPromptPrefix ?: ChatTemplateEngine.formatPrompt(
                                        history = historyCopy,
                                        systemDirective = systemPrompt,
                                        arch = activeArch,
                                        addAssistantPrompt = true
                                    )
                                    activeGgufPromptPrefix = currentBase + fullResponse + observationDelta
                                    keepGoing = toolCallCount < MAX_TOOL_CALLS
                                } else if (action in listOf("web_search", "search", "google_search")) {
                                    val searchQuery = params["query"] ?: params["q"] ?: params.values.firstOrNull() ?: ""
                                    _uiState.update { it.copy(activeStatusDetail = "Searching: $searchQuery...", currentPhase = AgentActionPhase.EXECUTING_TOOL) }
                                    val searchData = try {
                                        WebSearchTool.search(searchQuery)
                                    } catch (e: Exception) {
                                        "Error executing web search: ${e.message}"
                                    }
                                    val searchSummary = "🔍 Web Search: $searchQuery"
                                    val lockedSegmentText = if (explanatoryText.isNotBlank()) "$explanatoryText\n\n$searchSummary" else searchSummary
                                    _uiState.update { state ->
                                        val updated = state.logs.map { log ->
                                            if (log.id == liveLogId) log.copy(text = lockedSegmentText, thinkingText = demuxedFull.thinkingText, isThinking = false) else log
                                        }
                                        state.copy(logs = updated, activeStatusDetail = "Synthesizing answer from search results...", currentPhase = AgentActionPhase.ANALYZING_OUTPUT)
                                    }
                                    toolCallCount++
                                    val sid = currentSessionId.value
                                    viewModelScope.launch(Dispatchers.IO) {
                                        chatDb?.insertMessage(ChatMessageEntity(id = liveLogId, sessionId = sid, sender = "AGENT", text = lockedSegmentText, timestamp = System.currentTimeMillis()))
                                        refreshSessions()
                                    }

                                    val truncatedSearch = truncateObservation(searchData, maxChars = 500, maxLines = 10)
                                    val observationMsg = """<observation>
[SEARCH RESULTS for "$searchQuery"]:
$truncatedSearch
</observation>

Please analyze the search results above and provide your complete response to the user."""

                                    synchronized(conversationHistory) {
                                        val assistantTurn = if (explanatoryText.isNotBlank()) "$explanatoryText\n${primaryCall.rawMatch}" else primaryCall.rawMatch
                                        // Use "model" role — Gemini API requires "user"/"model", not "assistant"
                                        conversationHistory.add("model" to assistantTurn)
                                        conversationHistory.add("user" to observationMsg)
                                        trimConversationHistoryToContextLimit()
                                    }

                                    val activeArch = ggufClient?.currentMetadata?.architecture ?: ChatTemplateEngine.Architecture.GEMMA
                                    val currentBase = activeGgufPromptPrefix ?: ChatTemplateEngine.formatPrompt(
                                        history = historyCopy,
                                        systemDirective = systemPrompt,
                                        arch = activeArch,
                                        addAssistantPrompt = true
                                    )
                                    activeGgufPromptPrefix = currentBase + fullResponse + ChatTemplateEngine.formatObservationTurn(activeArch, observationMsg)
                                    keepGoing = toolCallCount < MAX_TOOL_CALLS
                                } else {
                                    val (usedTools, summary) = executeToolLoop(fullResponse, actualContext)
                                    val cleanSummary = summary ?: ""
                                    val lockedSegmentText = when {
                                        explanatoryText.isNotBlank() && cleanSummary.isNotBlank() -> "$explanatoryText\n\n$cleanSummary"
                                        explanatoryText.isNotBlank() -> explanatoryText
                                        cleanSummary.isNotBlank() -> cleanSummary
                                        else -> ""
                                    }
                                    _uiState.update { state ->
                                        val updated = state.logs.map { log ->
                                            if (log.id == liveLogId) log.copy(text = lockedSegmentText, thinkingText = demuxedFull.thinkingText, isThinking = false) else log
                                        }
                                        state.copy(logs = updated, activeStatusDetail = "Analyzing tool observation...", currentPhase = AgentActionPhase.ANALYZING_OUTPUT)
                                    }
                                    toolCallCount++
                                    val sid = currentSessionId.value
                                    val dbText = if (demuxedFull.thinkingText.isNotBlank()) "<think>${demuxedFull.thinkingText}</think>\n$lockedSegmentText" else lockedSegmentText
                                    viewModelScope.launch(Dispatchers.IO) {
                                        chatDb?.insertMessage(ChatMessageEntity(id = liveLogId, sessionId = sid, sender = "AGENT", text = dbText, timestamp = System.currentTimeMillis()))
                                        refreshSessions()
                                    }

                                    val truncatedSummary = truncateObservation(cleanSummary, maxChars = 500, maxLines = 10)
                                    val observationMsg = """<observation>
$truncatedSummary
</observation>

Please analyze the tool observation above and provide your complete response to the user."""

                                    synchronized(conversationHistory) {
                                        val assistantTurn = if (explanatoryText.isNotBlank()) "$explanatoryText\n${primaryCall.rawMatch}" else primaryCall.rawMatch
                                        conversationHistory.add("assistant" to assistantTurn)
                                        conversationHistory.add("user" to observationMsg)
                                        trimConversationHistoryToContextLimit()
                                    }

                                    val activeArch = ggufClient?.currentMetadata?.architecture ?: ChatTemplateEngine.Architecture.GEMMA
                                    val currentBase = activeGgufPromptPrefix ?: ChatTemplateEngine.formatPrompt(
                                        history = historyCopy,
                                        systemDirective = systemPrompt,
                                        arch = activeArch,
                                        addAssistantPrompt = true
                                    )
                                    activeGgufPromptPrefix = currentBase + fullResponse + ChatTemplateEngine.formatObservationTurn(activeArch, observationMsg)
                                    keepGoing = toolCallCount < MAX_TOOL_CALLS
                                }
                            } else {
                                _uiState.update { it.copy(currentPhase = AgentActionPhase.COMPLETING) }
                                val assistantReply = if (writtenFilesFromStream.isNotEmpty()) {
                                    ToolExecutor.sanitizePostExecutionSummary(fullResponse, writtenFilesFromStream)
                                } else {
                                    fullResponse
                                }
                                val cleanHistoryReply = stripThinkingForHistory(assistantReply)
                                _uiState.update { state ->
                                    val updated = state.logs.map { log ->
                                        if (log.id == liveLogId) log.copy(text = cleanHistoryReply, thinkingText = demuxedFull.thinkingText, isThinking = false) else log
                                    }
                                    state.copy(logs = updated)
                                }
                                val sid = currentSessionId.value
                                val dbText = if (demuxedFull.thinkingText.isNotBlank()) "<think>${demuxedFull.thinkingText}</think>\n$cleanHistoryReply" else cleanHistoryReply
                                viewModelScope.launch(Dispatchers.IO) {
                                    chatDb?.insertMessage(
                                        ChatMessageEntity(
                                            id = liveLogId,
                                            sessionId = sid,
                                            sender = "AGENT",
                                            text = dbText,
                                            timestamp = System.currentTimeMillis()
                                        )
                                    )
                                    refreshSessions()

                                    val detectedPath = ToolExecutor.detectFilePath(fullResponse)
                                    EpistemicMemoryManager.recordInteraction(
                                        context = actualContext,
                                        userPrompt = command,
                                        agentResponse = fullResponse,
                                        touchedPath = detectedPath
                                    )
                                }
                                synchronized(conversationHistory) {
                                    conversationHistory.add("assistant" to cleanHistoryReply)
                                    trimConversationHistoryToContextLimit()
                                }
                                keepGoing = false
                                activeGgufPromptPrefix = null
                            }
                        }
                    } else {
                        keepGoing = false
                        activeGgufPromptPrefix = null
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Generation stopped by user (CancellationException caught cleanly).")
                addSystemLog("Generation stopped by user.")
                _uiState.update { state ->
                    state.copy(
                        isProcessing = false,
                        activeStatusDetail = "Generation stopped by user.",
                        currentPhase = AgentActionPhase.IDLE,
                        streamInterruptMsg = null
                    )
                }
            } catch (e: Exception) {
                addLog(LogEntry(sender = "SYSTEM", text = "Mission Error: ${e.message}", isError = true))
            } finally {
                _uiState.update { it.copy(isProcessing = false, currentPhase = AgentActionPhase.IDLE, activeStatusDetail = "Idle / Ready", streamInterruptMsg = null) }
                if (actualContext != null) saveConversation(actualContext)
                updateTaskState()
                processNextQueuedCommand(context)
            }
        }
    }
    
    private fun processNextQueuedCommand(context: Context?) {
        val nextCommand: String?
        synchronized(pendingCommands) {
            nextCommand = if (pendingCommands.isNotEmpty()) pendingCommands.removeAt(0) else null
        }
        if (nextCommand != null) {
            viewModelScope.launch(Dispatchers.IO) {
                delay(500)
                sendCommand(nextCommand, context = context)
            }
        }
    }

    suspend fun executeBackgroundTask(command: String, taskId: String? = null): String {
        val modelType = _uiState.value.activeModelType
            ?: throw IllegalStateException("No model loaded")
        if (!isModelLoaded()) throw IllegalStateException("Model not ready")
        
        val ctx = appContext ?: MainActivity.instance
        val pm = ctx?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "synapse:BackgroundTask")
        wakeLock?.acquire(10 * 60 * 1000L)
        
        try {
            val taskHistory = mutableListOf<Pair<String, String>>()
            taskHistory.add("user" to command)
            
            val dynamicPersona = getEffectivePersonaPrompt(ctx)
            val systemPrompt = buildSystemPrompt(ctx, forLocalModel = modelType == ModelType.GGUF, dynamicPersonaPrompt = dynamicPersona)
            val resultSb = StringBuilder()
            var iterations = 0
            var keepGoing = true
            
            while (keepGoing && iterations < 50) {
                if (taskId != null) {
                    val status = taskMemory?.getTask(taskId)?.status
                    if (status == TaskStatus.CANCELLED) {
                        throw kotlinx.coroutines.CancellationException("Task cancelled manually by user")
                    }
                }
                
                iterations++
                val responseSb = StringBuilder()
                
                val stream = when (modelType) {
                    ModelType.GGUF -> ggufClient!!.generateStreamWithHistory(taskHistory, systemPrompt)
                    ModelType.GEMINI -> {
                        val json = JSONArray()
                        taskHistory.forEach { (r, c) -> json.put(JSONObject().put("role", r).put("content", c)) }
                        geminiClient!!.generateStreamWithOllamaMessages(json, systemPrompt) { responseSb.setLength(0) }
                    }
                }
                
                stream.collect { responseSb.append(it) }
                val response = responseSb.toString()
                
                if (response.isBlank()) { keepGoing = false; continue }
                
                val reply = cleanVisibleText(response).trim()
                taskHistory.add("assistant" to stripThinkingForHistory(reply))
                while (taskHistory.size > 6) taskHistory.removeAt(0)
                
                val (usedTools, summary) = executeToolLoop(response, ctx)
                if (usedTools) {
                    // Headless tool execution completed, continue loop if needed
                } else {
                    resultSb.append(summary ?: reply)
                    keepGoing = false
                }
            }
            
            val result = resultSb.toString().ifBlank { "Task completed after $iterations iterations" }
            addLog(LogEntry(sender = "AGENT", text = "Background task done: ${command.take(50)}... -> $result"))
            return result
            
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
            updateTaskState()
        }
    }

    fun handleBackgroundEvent(eventText: String) {
        if (!_uiState.value.config.enableNeuralReflexes) return
        viewModelScope.launch { sendCommand("BACKGROUND_EVENT: $eventText") }
    }

    suspend fun executeSingleModelCall(
        modelType: ModelType,
        systemPrompt: String,
        userPrompt: String,
        onChunk: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val continuationPromptPayload = "Continue generation exactly from the last emitted token. Do not repeat prior lines. Continue directly with the remaining code/text."
        var continueRecursion = true
        var cycle = 1

        var firstTokenTime = 0L
        var tokenCount = 0

        while (continueRecursion && cycle <= 10) {
            if (cycle > 1 && StorageSentinel.isStorageCritical()) {
                Log.w(TAG, "Storage critical in executeSingleModelCall. Gracefully stopping continuation.")
                break
            }

            var encounteredMaxTokens = false
            try {
                when (modelType) {
                    ModelType.GEMINI -> {
                        val json = org.json.JSONArray().apply {
                            put(org.json.JSONObject().put("role", "user").put("content", userPrompt))
                            if (cycle > 1) {
                                put(org.json.JSONObject().put("role", "model").put("content", sb.toString()))
                                put(org.json.JSONObject().put("role", "user").put("content", continuationPromptPayload))
                            }
                        }
                        geminiClient?.generateStreamWithOllamaMessages(json, systemPrompt)?.collect { chunk ->
                            sb.append(chunk)
                            if (firstTokenTime == 0L) firstTokenTime = System.currentTimeMillis()
                            tokenCount++
                            val elapsed = (System.currentTimeMillis() - firstTokenTime) / 1000f
                            val now = System.currentTimeMillis()
                            if (elapsed > 0.1f && now - lastTpsUpdateMs >= 500L) {
                                lastTpsUpdateMs = now
                                _uiState.update { it.copy(tokensPerSecond = tokenCount / elapsed) }
                            }
                            onChunk?.invoke(chunk)
                        }
                    }
                    ModelType.GGUF -> {
                        val history = if (cycle == 1) {
                            listOf("user" to userPrompt)
                        } else {
                            listOf("user" to userPrompt, "assistant" to sb.toString(), "user" to continuationPromptPayload)
                        }
                        ggufClient?.generateStreamWithHistory(
                            history = history,
                            systemPrompt = systemPrompt,
                            onSpeedUpdate = { tps ->
                                val now = System.currentTimeMillis()
                                if (now - lastTpsUpdateMs >= 500L) {
                                    lastTpsUpdateMs = now
                                    _uiState.update { it.copy(tokensPerSecond = tps) }
                                }
                            }
                        )?.collect { chunk ->
                            sb.append(chunk)
                            if (firstTokenTime == 0L) firstTokenTime = System.currentTimeMillis()
                            tokenCount++
                            onChunk?.invoke(chunk)
                        }
                    }
                }
            } catch (e: Exception) {
                val errStr = (e.message ?: "").lowercase()
                if (errStr.contains("max_tokens") || errStr.contains("token limit")) {
                    encounteredMaxTokens = true
                    Log.i(TAG, "executeSingleModelCall caught MAX_TOKENS token ceiling: auto-continuing")
                } else {
                    throw e
                }
            }

            val finishReason = when (modelType) {
                ModelType.GEMINI -> geminiClient?.lastFinishReason ?: "STOP"
                ModelType.GGUF -> ggufClient?.lastFinishReason ?: "STOP"
            }

            if (finishReason == "MAX_TOKENS" || encounteredMaxTokens) {
                cycle++
                continueRecursion = true
            } else {
                continueRecursion = false
            }
        }

        val totalElapsed = (System.currentTimeMillis() - firstTokenTime) / 1000f
        if (firstTokenTime > 0L && totalElapsed > 0.05f && tokenCount > 0) {
            val finalTps = tokenCount / totalElapsed
            _uiState.update { it.copy(tokensPerSecond = finalTps) }
        }

        sb.toString()
    }

    private fun runProcessWithWatchdog(cmd: String, shell: String, timeoutMs: Long): Pair<Int, String> {
        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        val currentPath = env["PATH"] ?: "/system/bin:/system/xbin"
        env["PATH"] = "$currentPath:/system/bin:/system/xbin:/data/local/tmp"

        val process = pb.start()

        try {
            process.outputStream.close()
        } catch (_: Exception) {}

        val outputSb = StringBuilder()
        val readerThread = Thread({
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(2048)
                    var charsRead: Int
                    while (reader.read(buffer).also { charsRead = it } != -1) {
                        outputSb.append(buffer, 0, charsRead)
                        if (outputSb.length > 64 * 1024) {
                            outputSb.append("\n[Output truncated: exceeded 64KB]")
                            break
                        }
                    }
                }
            } catch (_: Exception) {}
        }, "shell-watchdog-reader")
        readerThread.isDaemon = true
        readerThread.start()

        try {
            val exitedInTime = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!exitedInTime) {
                Log.w(TAG, "Shell process timed out after ${timeoutMs}ms, destroying forcibly: $cmd")
                try {
                    process.destroyForcibly()
                } catch (_: Exception) {}
                try {
                    readerThread.join(300)
                } catch (_: Exception) {}
                val partial = outputSb.toString().trim()
                val timeoutMsg = "[Process Timed Out after ${timeoutMs / 1000}s - Process Terminated]"
                return -1 to (if (partial.isNotBlank()) "$timeoutMsg\nPartial output:\n$partial" else timeoutMsg)
            }

            try {
                readerThread.join(500)
            } catch (_: Exception) {}
            val exitCode = try { process.exitValue() } catch (_: Exception) { 0 }
            return exitCode to outputSb.toString()
        } finally {
            try { process.inputStream?.close() } catch (_: Exception) {}
            try { process.errorStream?.close() } catch (_: Exception) {}
            try { process.outputStream?.close() } catch (_: Exception) {}
            try { process.destroy() } catch (_: Exception) {}
        }
    }

    suspend fun runShellWithExitCode(cmd: String, timeoutMs: Long = 12_000L): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (cmd.isBlank()) return@withContext -1 to "[Process completed with no output]"

        val tryRoot = _uiState.value.isRootAvailable
        try {
            if (tryRoot) {
                try {
                    val lines = RootShellManager.execAsync(cmd)
                    if (lines.isNotEmpty()) {
                        val joined = lines.joinToString("\n")
                        return@withContext 0 to sanitizeProcessOutput(joined)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Persistent root shell execution failed ($e), falling back to safe sh runner")
                }
            }

            executeSafeProcess(cmd)
        } catch (e: Exception) {
            -1 to sanitizeProcessOutput("Error executing command: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun runShellInternal(cmd: String, timeoutMs: Long = 15_000L): String {
        return runShellWithExitCode(cmd, timeoutMs).second
    }

    private fun interceptHeredocFileWrite(cmd: String): Triple<String, Int, String>? {
        val trimmed = cmd.trim()
        val headerRegex = Regex("""^cat\s+<<-?\s*['"]?([a-zA-Z0-9_\-]+)['"]?\s*(>>|>)\s*([^\r\n]+)""", RegexOption.IGNORE_CASE)
        val headerMatch = headerRegex.find(trimmed) ?: return null

        val delimiter = headerMatch.groupValues[1]
        val isAppend = headerMatch.groupValues[2] == ">>"
        val rawPath = headerMatch.groupValues[3].trim().removeSurrounding("\"").removeSurrounding("'")

        val lines = trimmed.lines()
        if (lines.size < 2) return null

        val contentLines = mutableListOf<String>()

        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.trim() == delimiter) {
                break
            }
            contentLines.add(line)
        }

        val content = contentLines.joinToString("\n")
        return try {
            val resolved = ToolExecutor.resolvePath(rawPath, ToolExecutor.BASE_DIR)
            val file = File(resolved)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            if (isAppend) {
                file.appendText(content, java.nio.charset.StandardCharsets.UTF_8)
            } else {
                file.writeText(content, java.nio.charset.StandardCharsets.UTF_8)
            }
            val op = if (isAppend) "Appended to" else "Wrote"
            val displayCmd = if (isAppend) "cat >> $rawPath (${file.length()} bytes)" else "cat > $rawPath (${file.length()} bytes)"
            Triple(
                displayCmd,
                0,
                "[Native I/O Intercept]: $op ${content.length} chars directly to $resolved via Java NIO stream (bypassed shell heredoc)."
            )
        } catch (e: Exception) {
            Triple(
                "cat > $rawPath",
                1,
                "Error in native file write: ${e.message}"
            )
        }
    }

    private suspend fun executeToolLoop(response: String, context: Context? = null): Pair<Boolean, String?> = withTimeoutOrNull(25_000L) {
        val actualContext = context ?: appContext ?: MainActivity.instance
        try {
            val writtenFiles = withContext(Dispatchers.IO) {
                ToolExecutor.extractAndWriteFiles(response)
            }
            if (writtenFiles.isNotEmpty()) {
                val primaryPath = writtenFiles.first()
                _uiState.update { it.copy(activeStatusDetail = "Writing to $primaryPath...") }
                writtenFiles.forEach { path ->
                    addSystemLog("File written: $path")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ToolExecutor error: ${e.message}")
        }

        val toolCalls = StreamDemultiplexer.parseAllToolCalls(response)
        if (toolCalls.isEmpty()) return@withTimeoutOrNull false to null

        val summaries = mutableListOf<String>()
        var actionToolUsed = false
        try {
            for (call in toolCalls) {
                val action = call.name.trim().lowercase()
                val params = call.params
                _uiState.update { it.copy(activeStatusDetail = "Executing Tool / Task ($action)...") }

                val callHash = computeCallHash(action, params)
                if (executedCallsThisTurn.contains(callHash) || inFlightCalls.contains(callHash)) {
                    Log.d(TAG, "Idempotency Lock: Suppressed duplicate tool call [$action] with hash $callHash")
                    val preview = when (action) {
                        "open_app", "launch_app", "start_app" -> {
                            val target = params["package_name"] ?: params["package"] ?: params["app_name"] ?: params["app"] ?: params["name"] ?: ""
                            resolvePackageName(target, actualContext?.packageManager)
                        }
                        "run_shell", "execute_shell", "terminal", "bash", "sh" -> {
                            params["command"] ?: params["cmd"] ?: ""
                        }
                        else -> params.values.firstOrNull() ?: action
                    }
                    val statusMsg = when (action) {
                        "open_app", "launch_app", "start_app" -> "$preview brought to front."
                        else -> "Identical operation already executed in active turn."
                    }
                    summaries.add("✔ Tool($action: $preview) ─── [DUPLICATE SUPPRESSED]\n✔ Status: $statusMsg")
                    continue
                }

                inFlightCalls.add(callHash)
                try {
                    when (action) {
                        "open_app", "launch_app", "start_app" -> {
                            actionToolUsed = true
                            val target = params["package_name"] ?: params["package"] ?: params["app_name"] ?: params["app"] ?: params["name"] ?: ""
                            if (target.isNotBlank()) {
                                val toolRes = ToolHandler.handleOpenApp(target, actualContext)
                                summaries.add(toolRes.summary)
                                addSystemLog("App launch: $target -> ${toolRes.success}")
                            }
                        }
                        "run_shell", "execute_shell", "terminal", "bash", "sh" -> {
                            val rawCmd = params["command"] ?: params["cmd"] ?: params.values.firstOrNull() ?: ""
                            if (rawCmd.isNotBlank()) {
                                actionToolUsed = true
                                val trimmedCmd = rawCmd.trim()

                                if (trimmedCmd.startsWith("am start", ignoreCase = true)) {
                                    val extractedTarget = extractTargetFromAmStart(trimmedCmd)
                                    if (extractedTarget != null) {
                                        val openAppParams = mapOf("package_name" to extractedTarget)
                                        val openAppHash = computeCallHash("open_app", openAppParams)
                                        if (executedCallsThisTurn.contains(openAppHash) || inFlightCalls.contains(openAppHash)) {
                                            Log.d(TAG, "Idempotency Lock: Intercepted duplicate am start as open_app [$extractedTarget]")
                                            val resolvedPkg = resolvePackageName(extractedTarget, actualContext?.packageManager)
                                            summaries.add("✔ Tool(open_app: $resolvedPkg) ─── [DUPLICATE SUPPRESSED]\n✔ Status: $resolvedPkg already brought to front in active turn.")
                                        } else {
                                            inFlightCalls.add(openAppHash)
                                            try {
                                                val toolRes = ToolHandler.handleOpenApp(extractedTarget, actualContext)
                                                if (toolRes.success) {
                                                    val resolvedPkg = resolvePackageName(extractedTarget, actualContext?.packageManager)
                                                    executedCallsThisTurn.add(openAppHash)
                                                    summaries.add(toolRes.summary)
                                                    addSystemLog("Native fast-path launch (intercepted am start): $extractedTarget ($resolvedPkg)")
                                                } else {
                                                    val (code, out) = runShellWithExitCode(rawCmd)
                                                    val safeCmd = StreamDemultiplexer.escapeXml(rawCmd)
                                                    val safeOut = StreamDemultiplexer.escapeXml(truncateObservation(out.trim()))
                                                    summaries.add("""<terminal_frame command="$safeCmd" exit_code="$code">$safeOut</terminal_frame>""")
                                                }
                                            } finally {
                                                inFlightCalls.remove(openAppHash)
                                            }
                                        }
                                        continue
                                    }
                                }

                                val heredoc = interceptHeredocFileWrite(rawCmd)
                                val (finalCmd, exitCode, output) = if (heredoc != null) {
                                    heredoc
                                } else {
                                    val (code, out) = runShellWithExitCode(rawCmd)
                                    Triple(rawCmd, code, out)
                                }
                                val safeCmd = StreamDemultiplexer.escapeXml(finalCmd)
                                val safeOut = StreamDemultiplexer.escapeXml(truncateObservation(output.trim()))
                                summaries.add("""<terminal_frame command="$safeCmd" exit_code="$exitCode">$safeOut</terminal_frame>""")
                            }
                        }
                        "write_file", "write_to_file", "create_file", "edit_file" -> {
                            val path = params["path"] ?: params["file_path"] ?: params["target_file"] ?: params["filepath"] ?: ""
                            val content = params["content"] ?: params["code"] ?: params["text"] ?: ""
                            if (path.isNotBlank()) {
                                actionToolUsed = true
                                val toolRes = ToolHandler.handleWriteFile(path, content)
                                summaries.add(toolRes.summary)
                                addSystemLog("File write: $path -> ${toolRes.success}")
                            }
                        }
                        "web_search", "search", "google_search" -> {
                            val q = params["query"] ?: params["q"] ?: params.values.firstOrNull() ?: ""
                            if (q.isNotBlank()) {
                                actionToolUsed = true
                                val toolRes = ToolHandler.handleWebSearch(q)
                                summaries.add(toolRes.summary)
                            }
                        }
                        "generate_image", "image_generation", "create_image", "draw_image", "draw" -> {
                            val p = params["prompt"] ?: params["query"] ?: params["description"] ?: params.values.firstOrNull() ?: ""
                            if (p.isNotBlank()) {
                                actionToolUsed = true
                                _imageState.value = ImageState.Generating(p)
                                val isCloud = _uiState.value.activeModelType == ModelType.GEMINI && _uiState.value.config.geminiApiKeys.isNotBlank()
                                val imgRes = if (isCloud) {
                                    imageRepository.generateImage(p)
                                } else if (imageRepository.localDiffusionEngine.hasModelWeights()) {
                                    executeSequentialImageSynthesis(p)
                                } else {
                                    imageRepository.generateImage(p)
                                }
                                when (imgRes) {
                                    is ImageResult.Success -> {
                                        _imageState.value = ImageState.Success(imgRes.imageBytes, p)
                                        val saved = saveImageToDisk(imgRes.imageBytes, p)
                                        val diskNote = if (saved != null) " (Saved to ${saved.absolutePath})" else ""
                                        addLog(
                                            LogEntry(
                                                sender = "AGENT",
                                                text = "🎨 Synthesized visual for: \"$p\"$diskNote",
                                                imageBytes = imgRes.imageBytes
                                            )
                                        )
                                        summaries.add("✔ Tool(generate_image: \"$p\") ─── [GENERATED]\n✔ Status: Synthesized ${imgRes.imageBytes.size} bytes$diskNote")
                                    }
                                    is ImageResult.Error -> {
                                        _imageState.value = ImageState.Error(imgRes.message)
                                        summaries.add("✖ Tool(generate_image: \"$p\") ─── [FAILED]\n✖ Status: ${imgRes.message}")
                                    }
                                }
                            }
                        }
                        "schedule_task" -> {
                            val desc = params["description"] ?: "Scheduled Task"
                            val cmd = params["command"] ?: params["cmd"] ?: params.values.firstOrNull() ?: ""
                            val delay = params["delay_minutes"]?.toIntOrNull() ?: 5
                            val tid = taskEngine?.queueCommand(cmd, source = "AGENT") ?: UUID.randomUUID().toString().take(8)
                            updateTaskState()
                            summaries.add("Scheduled background task [tsk #$tid in $delay min]: $desc ($cmd)")
                        }
                        "queue_background_task", "queue_task" -> {
                            val cmd = params["command"] ?: params["cmd"] ?: params.values.firstOrNull() ?: ""
                            val tid = taskEngine?.queueCommand(cmd, source = "AGENT") ?: UUID.randomUUID().toString().take(8)
                            updateTaskState()
                            summaries.add("Queued background task [tsk #$tid]: $cmd")
                        }
                        "complete" -> {
                            val s = params["summary"] ?: params.values.firstOrNull() ?: ""
                            if (s.isNotBlank()) {
                                summaries.add(s)
                            }
                            return@withTimeoutOrNull false to summaries.joinToString("\n\n")
                        }
                    }
                } finally {
                    inFlightCalls.remove(callHash)
                    executedCallsThisTurn.add(callHash)
                }
                delay(100)
            }
            val finalSummary = if (summaries.isNotEmpty()) summaries.joinToString("\n\n") else null
            actionToolUsed to finalSummary
        } catch (e: Exception) {
            Log.e(TAG, "Tool loop error: ${e.message}")
            false to null
        }
    } ?: run {
        Log.w(TAG, "executeToolLoop timed out after 25s")
        false to "[Tool execution timed out after 25s]"
    }

    fun setRootAvailable(available: Boolean) {
        _uiState.update { it.copy(isRootAvailable = available) }
        Log.i(TAG, "Superuser status: ${if (available) "ROOT GRANTED" else "STANDARD USER"}")
    }

    fun togglePersona(personaName: String, context: Context) {
        val current = _uiState.value.activePersonas.toMutableSet()
        if (current.contains(personaName)) {
            if (current.size > 1) {
                current.remove(personaName)
            }
        } else {
            current.add(personaName)
        }
        PersonaManager.setActivePersonas(context, current)
        val primary = current.firstOrNull() ?: PersonaManager.DEFAULT_PERSONA
        _uiState.update { 
            it.copy(
                activePersonas = current,
                activePersona = primary,
                config = it.config.copy(activePersonas = current, activePersona = primary)
            ) 
        }
        addSystemLog("Operational Personas: ${current.joinToString(", ")}")
        viewModelScope.launch(Dispatchers.IO) {
            val dynamicPersona = getEffectivePersonaPrompt(context)
            val isLocal = _uiState.value.activeModelType == ModelType.GGUF || _uiState.value.config.preferredModelType == ModelType.GGUF
            val systemPrompt = buildSystemPrompt(context, forLocalModel = isLocal, dynamicPersonaPrompt = dynamicPersona)
            withTimeoutOrNull(10_000L) {
                ggufClient?.warmupPersonaKVCache(systemPrompt)
            }
        }
    }

    fun setActivePersonas(personas: Set<String>, context: Context) {
        val safeSet = if (personas.isEmpty()) PersonaManager.DEFAULT_PERSONAS_SET else personas
        PersonaManager.setActivePersonas(context, safeSet)
        val primary = safeSet.firstOrNull() ?: PersonaManager.DEFAULT_PERSONA
        _uiState.update { 
            it.copy(
                activePersonas = safeSet,
                activePersona = primary,
                config = it.config.copy(activePersonas = safeSet, activePersona = primary)
            ) 
        }
        addSystemLog("Operational Personas: ${safeSet.joinToString(", ")}")
        viewModelScope.launch(Dispatchers.IO) {
            val dynamicPersona = getEffectivePersonaPrompt(context)
            val isLocal = _uiState.value.activeModelType == ModelType.GGUF || _uiState.value.config.preferredModelType == ModelType.GGUF
            val systemPrompt = buildSystemPrompt(context, forLocalModel = isLocal, dynamicPersonaPrompt = dynamicPersona)
            withTimeoutOrNull(10_000L) {
                ggufClient?.warmupPersonaKVCache(systemPrompt)
            }
        }
    }

    fun setActivePersona(personaName: String, context: Context) {
        togglePersona(personaName, context)
    }

    fun openLiveCanvas() {
        _uiState.update { it.copy(isLiveCanvasOpen = true) }
    }

    fun closeLiveCanvas() {
        _uiState.update { it.copy(isLiveCanvasOpen = false) }
    }

    fun toggleLiveCanvas() {
        _uiState.update { it.copy(isLiveCanvasOpen = !it.isLiveCanvasOpen) }
    }

    fun togglePersonaQuick(personaName: String, context: Context) {
        togglePersona(personaName, context)
    }

    fun switchPersonaQuick(personaName: String, context: Context) {
        togglePersona(personaName, context)
    }

    fun executeRollback(context: Context? = null): String {
        val targetDir = File("/sdcard/Medlam-Ai/webapp")
        val (success, message) = SnapshotManager.rollbackLatest(targetDir)
        val exitCode = if (success) 0 else 1
        val safeCmd = "rollback"
        val safeOut = StreamDemultiplexer.escapeXml(message)
        val terminalFrameText = """<terminal_frame command="$safeCmd" exit_code="$exitCode">$safeOut</terminal_frame>"""
        val agentLog = LogEntry(sender = "AGENT", text = terminalFrameText)
        addLog(agentLog)
        val sid = currentSessionId.value
        viewModelScope.launch(Dispatchers.IO) {
            chatDb?.insertMessage(
                ChatMessageEntity(
                    id = agentLog.id,
                    sessionId = sid,
                    sender = "AGENT",
                    text = terminalFrameText,
                    timestamp = agentLog.timestamp
                )
            )
            refreshSessions()
        }
        return message
    }

    fun rescanPersonas(context: Context): List<String> {
        val list = PersonaManager.rescanPersonas(context)
        _uiState.update { it.copy(availablePersonas = list) }
        return list
    }

    fun rescanModels(context: Context): Pair<List<File>, List<File>> {
        val (base, mtp) = ModelScanner.rescanModels(context)
        return Pair(base, mtp)
    }

    private fun isCasualGreetingOrChat(text: String): Boolean {
        val lower = text.lowercase().trim()
        val casualTriggers = listOf(
            "hello", "hi", "hey", "salam", "salut", "bonjour", "holla",
            "صباح الخير", "مساء الخير", "السلام عليكم", "سلام", "أهلا", "اهلا",
            "labas", "kif dayr", "cv", "how are you", "ca va", "ça va",
            "شكون نتا", "من أنت", "who are you", "qui es-tu",
            "شكرا", "thanks", "merci", "thank you"
        )
        val actionTriggers = listOf(
            "open", "launch", "click", "type", "swipe", "scroll", "back", "home",
            "create", "write", "build", "run", "execute", "install", "download",
            "افتح", "اضغط", "اكتب", "امسح", "دير", "صاوب", "خدم", "انشئ"
        )
        val hasAction = actionTriggers.any { lower.contains(it) }
        return !hasAction && casualTriggers.any { lower.contains(it) }
    }

    fun initTaskSystem(context: Context) {
        if (taskMemory == null) {
            taskMemory = TaskMemoryManager(context)
            taskEngine = BackgroundTaskEngine(taskMemory!!)
            taskEngine?.start()
            addSystemLog("Task Engine initialized")
            updateTaskState()
        }
    }
    
    fun updateTaskState() {
        val tm = taskMemory ?: return
        _uiState.update { it.copy(
            activeTasks = tm.getAllActiveTasks(),
            taskQueueSize = tm.getTasksByStatus(TaskStatus.QUEUED).size,
            taskSummary = tm.getTaskSummary(),
            taskEngineRunning = taskEngine?.engineState?.value?.isRunning ?: false
        ) }
    }

    fun consolidateWorkspaceDirectories() {
        try {
            val rootDir = File("/sdcard/Medlam-Ai")
            if (!rootDir.exists()) rootDir.mkdirs()
            val oldCheckpoint = File("/sdcard/Medlam-Ai/memory/agent_checkpoint.json")
            if (oldCheckpoint.exists()) oldCheckpoint.delete()
            ZramOptimizer.ensureNoMedia()
        } catch (_: Exception) {}
    }

    fun refreshSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = chatDb?.getAllSessions() ?: emptyList()
            chatSessions.value = list
        }
    }

    fun startNewSession() {
        val newId = UUID.randomUUID().toString()
        currentSessionId.value = newId
        currentSessionTitle.value = "New Session"
        _uiState.update { it.copy(logs = emptyList(), resumptionNotice = null, activeWorkspacePath = null) }
        conversationHistory.clear()
        viewModelScope.launch(Dispatchers.IO) {
            chatDb?.cleanupEmptySessions(exceptSessionId = newId)
            chatDb?.insertSession(
                ChatSessionEntity(
                    id = newId,
                    title = "New Session",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            refreshSessions()
        }
    }

    fun switchSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = chatDb?.getSession(sessionId) ?: return@launch
            val messages = (chatDb?.getMessagesForSession(sessionId) ?: emptyList())
                .filter { !it.text.contains("Execution Complete") && !it.text.contains("Generation Chunk") }
            val restoredLogs = messages.map { msg ->
                val parsed = parseThoughtAndContent(msg.text)
                var logText = if (msg.sender == "AGENT") {
                    if (parsed.content.isNotBlank()) parsed.content else if (parsed.thought.isNotBlank()) "" else msg.text
                } else {
                    msg.text
                }
                var restoredImageBytes: ByteArray? = null
                if (logText.contains("<!--IMAGE_PATH:")) {
                    val path = logText.substringAfter("<!--IMAGE_PATH:").substringBefore("-->").trim()
                    logText = logText.substringAfter("-->\n").ifBlank { logText.substringAfter("-->") }
                    try {
                        val f = File(path)
                        if (f.exists()) restoredImageBytes = f.readBytes()
                    } catch (_: Exception) {}
                }
                LogEntry(
                    id = msg.id,
                    sender = msg.sender,
                    text = logText,
                    isError = msg.isError,
                    timestamp = msg.timestamp,
                    thinkingText = parsed.thought,
                    isThinking = false,
                    imageBytes = restoredImageBytes
                )
            }
            currentSessionId.value = session.id
            currentSessionTitle.value = session.title
            val sessionPath = messages.reversed().firstNotNullOfOrNull { ToolExecutor.detectFilePath(it.text) }
            _uiState.update { it.copy(logs = restoredLogs, resumptionNotice = null, activeWorkspacePath = sessionPath) }
            synchronized(conversationHistory) {
                conversationHistory.clear()
                restoredLogs.takeLast(MAX_HISTORY_MESSAGES).forEach { l ->
                    val role = if (l.sender == "USER") "user" else "assistant"
                    val cleanText = if (role == "assistant") {
                        stripThinkingForHistory(l.text).ifBlank { "[Acknowledged]" }
                    } else {
                        l.text
                    }
                    conversationHistory.add(role to cleanText)
                }
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            chatDb?.deleteSession(sessionId)
            refreshSessions()
            if (currentSessionId.value == sessionId) {
                val remaining = chatDb?.getAllSessions() ?: emptyList()
                if (remaining.isNotEmpty()) {
                    switchSession(remaining.first().id)
                } else {
                    startNewSession()
                }
            }
        }
    }

    fun autoTitleSessionIfNew(userMessage: String) {
        val currentTitle = currentSessionTitle.value
        if (currentTitle == "New Session" || currentTitle.isBlank()) {
            val clean = userMessage.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "Conversation"
            val title = if (clean.length > 32) clean.take(32) + "..." else clean
            currentSessionTitle.value = title
            val sid = currentSessionId.value
            viewModelScope.launch(Dispatchers.IO) {
                chatDb?.updateSessionTitle(sid, title)
                refreshSessions()
            }
        }
    }

    fun saveConversation(context: Context? = null) {
        val sid = currentSessionId.value
        val currentLogs = _uiState.value.logs
        viewModelScope.launch(Dispatchers.IO) {
            chatDb?.saveFullSessionMessages(sid, currentSessionTitle.value, currentLogs)
            refreshSessions()
        }
    }

    fun clearConversation(context: Context) {
        _uiState.update { it.copy(logs = emptyList()) }
        conversationHistory.clear()
        val sid = currentSessionId.value
        viewModelScope.launch(Dispatchers.IO) {
            chatDb?.saveFullSessionMessages(sid, currentSessionTitle.value, emptyList())
            refreshSessions()
        }
    }

    fun startScheduler() {
        if (schedulerJob != null) return
        schedulerJob = viewModelScope.launch { while (true) { delay(30000) } }
    }

    private fun saveWorkflows(context: Context) {
        val json = JSONObject()
        synchronized(workflows) { workflows.forEach { (k, v) -> json.put(k, v) } }
        prefs(context).edit().putString("saved_workflows", json.toString()).apply()
    }

    private fun loadWorkflows(prefs: SharedPreferences) {
        try {
            val json = JSONObject(prefs.getString("saved_workflows", "{}") ?: "{}")
            synchronized(workflows) { json.keys().forEach { k -> workflows[k] = json.getString(k) } }
        } catch (_: Exception) {}
    }

    fun stopAgent() { 
        currentJob?.cancel()
        currentJob = null
        try {
            LlamaNativeEngine.abort()
            LlamaNativeEngine.stop_generation()
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping native LLM: ${t.message}")
        }
        try {
            DynamicMediaBroker.abort()
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping DynamicMediaBroker: ${t.message}")
        }
        cascadeInterceptor?.stopAndFlush()
        _uiState.update { it.copy(isProcessing = false) } 
    }

    fun addSystemLog(text: String) { Log.i(TAG, "SystemLog: $text") }
    
    private fun prefs(context: Context) = context.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)
    
    private fun addLog(entry: LogEntry) { _uiState.update { it.copy(logs = it.logs + entry) } }

    fun dismissResumptionNotice() {
        _uiState.update { it.copy(resumptionNotice = null) }
    }

    override fun onCleared() { 
        super.onCleared()
        taskEngine?.stop()
        cascadeInterceptor?.release()
        cascadeInterceptor = null
        closeAllModels() 
    }
}

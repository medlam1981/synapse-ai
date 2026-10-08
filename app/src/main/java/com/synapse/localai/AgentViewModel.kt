package com.synapse.localai

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import com.synapse.localai.tools.PythonTool
import com.synapse.localai.tools.ContentReaderTool
import com.synapse.localai.tools.UiAutomationTool
import com.synapse.localai.tools.ScreenVisionTool
import com.synapse.localai.automation.TaskCompletionProtocol
import com.synapse.localai.ModelEngineService
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
    val geminiModelName: String = "gemini-3.8-flash",
    val serverHost: String = "0.0.0.0",
    val serverPort: Int = 8088,
    val contextLength: Int = 2048,
    val threadCount: Int = 4,
    val maxTokens: Int = 2048,
    val temperature: Float = 0.30f,
    val enableVisualHistory: Boolean = false,
    val enableNeuralReflexes: Boolean = false,
    val enabledSpecializedAgents: Set<String> = emptySet(),
    val systemPrompt: String = "",
    val customToolsPrompt: String = "",
    val showApiKeyTracker: Boolean = true,
    val activePersona: String = PersonaManager.DEFAULT_PERSONA,
    val activePersonas: Set<String> = PersonaManager.DEFAULT_PERSONAS_SET,
    val ttsEnabled: Boolean = false,
    val ttsEngineType: String = "HUMAN_NEURAL",
    val ttsVoiceLanguage: String = "AUTO",
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 0.88f,
    val customAcousticModelPath: String = "",
    val customVocoderModelPath: String = "",
    val customSpeakerEmbeddingPath: String = "",
    val sdSteps: Int = 0,
    val sdCfgScale: Float = 0f,
    val sdWidth: Int = 256,
    val sdHeight: Int = 256,
    val sdLowMemoryMode: Boolean = true,
    val sdThreads: Int = 4,
    val sdEngineMode: String = "SD_TURBO",
    val sdComputeDevice: String = "AUTO"
)

enum class EngineState { IDLE, RUNNING }

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
    val isTtsEnabled: Boolean = false
) {
    val engineState: EngineState
        get() = if (isProcessing) EngineState.RUNNING else EngineState.IDLE
}

class SynchronousToolBarrierException : kotlinx.coroutines.CancellationException("Synchronous ReAct Barrier: Tool call detected")

class AgentViewModel : ViewModel() {

    companion object {
        private const val TAG = "SYNAPSE_CORE"
        private const val MAX_HISTORY_MESSAGES = 48
        private const val MAX_TASK_ITERATIONS = 128
        private const val MAX_TOOL_CALLS_PER_TURN = 32
        private const val AGENTS_DIR = "/sdcard/Medlam-Ai/Agents"
        
        var instance: AgentViewModel? = null
            private set

        fun detectModelType(path: String): ModelType = ModelType.GGUF

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

        // Pre-compiled static regex patterns to eliminate allocation storms in text sanitization
        private val RE_THINK_FENCED = Regex("""```(?:thought|thinking|reasoning)\r?\n[\s\S]*?(?:```|$)""", RegexOption.IGNORE_CASE)
        private val RE_THINK_TAGS = Regex("""<\|?(?:think|thought|reasoning)\|?>[\s\S]*?</\|?(?:think|thought|reasoning)\|?>""", RegexOption.IGNORE_CASE)
        private val RE_THINK_OPEN = Regex("""<\|?(?:think|thought|reasoning)\|?>[\s\S]*$""", RegexOption.IGNORE_CASE)
        private val RE_FUNC_CALLS = Regex("""<function_calls>[\s\S]*?</function_calls>""", RegexOption.IGNORE_CASE)
        private val RE_INVOKE = Regex("""<invoke[\s\S]*?</invoke>""", RegexOption.IGNORE_CASE)
        private val RE_FUNC_CALLS_OPEN = Regex("""<function_calls>[\s\S]*$""", RegexOption.IGNORE_CASE)
        private val RE_INVOKE_OPEN = Regex("""<invoke[\s\S]*$""", RegexOption.IGNORE_CASE)
        private val RE_OBSERVATION = Regex("""<observation>[\s\S]*?</observation>""", RegexOption.IGNORE_CASE)
        private val RE_OBSERVATION_OPEN = Regex("""<observation>[\s\S]*$""", RegexOption.IGNORE_CASE)
        private val RE_SYS_INSTRUCTION = Regex("""SYSTEM MANDATORY INSTRUCTION:[\s\S]*$""", RegexOption.IGNORE_CASE)
        private val RE_TERMINAL = Regex("""<terminal_frame[\s\S]*?</terminal_frame>""", RegexOption.IGNORE_CASE)
        private val RE_TERMINAL_OPEN = Regex("""<terminal_frame[\s\S]*$""", RegexOption.IGNORE_CASE)
        private val RE_SHELL_OUT = Regex("""Shell output \(`[^`]+`\):\s*```(?:bash|sh)?\r?\n[\s\S]*?```""", RegexOption.IGNORE_CASE)
        private val RE_THINK_TAG_SOLO = Regex("""</?\|?(?:think|thought|reasoning)\|?>""", RegexOption.IGNORE_CASE)
        private val RE_TERMINAL_TAG_SOLO = Regex("""</?terminal_frame>""", RegexOption.IGNORE_CASE)
        private val RE_THOUGHT_EMOJI = Regex("""^💭[\s\S]*?\n\n""", RegexOption.DOT_MATCHES_ALL)
        private val RE_CODE_FENCE_COMPACT = Regex("""```([a-zA-Z0-9_\-]*)\n([\s\S]*?)```""")

        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"

        fun sanitizeGeminiModelName(raw: String?): String {
            val clean = (raw ?: "").trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .removeSurrounding("`")
                .removePrefix("models/")
                .trim()

            // Deduplicate only if user pasted an identical duplicated string back-to-back (e.g. "modelmodel")
            if (clean.length >= 8 && clean.length % 2 == 0) {
                val half = clean.length / 2
                val firstHalf = clean.substring(0, half)
                val secondHalf = clean.substring(half)
                if (firstHalf.equals(secondHalf, ignoreCase = true)) {
                    return firstHalf
                }
            }

            return clean.ifBlank { DEFAULT_GEMINI_MODEL }
        }
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
            val stats = if (ctx != null) getSystemStats(ctx) else SystemStats(0, false, "--", "--", "--", "--", 0, 0, 0)
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

    // دوال توافقية فارغة لضمان عدم حدوث أي خطأ في البناء
    fun toggleTts() {}
    fun testVoice(context: Context, sampleText: String? = null) {}
    fun getAcousticModels(context: Context): List<File> = emptyList()
    fun getVocoderModels(context: Context): List<File> = emptyList()
    fun getSpeakerEmbeddings(context: Context): List<File> = emptyList()

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

    suspend fun enrichVisualPromptWithLLM(shortPrompt: String): String = withContext(Dispatchers.Default) {
        val client = ggufClient
        val isLocalLoaded = (_uiState.value.activeModelType == ModelType.GGUF || _uiState.value.localModelLoaded || (client != null && client.isLoaded))
        val isGeminiLoaded = (_uiState.value.activeModelType == ModelType.GEMINI && geminiClient != null && geminiClient!!.isLoaded)

        if (!isLocalLoaded && !isGeminiLoaded) return@withContext shortPrompt

        val enrichmentSystemPrompt = "You are an expert image prompt generator. Convert the user's short request into a highly detailed, comma-separated diffusion prompt. Do not add any conversational text."
        val userActualInput = if (shortPrompt.startsWith("/image", ignoreCase = true)) shortPrompt else "/image $shortPrompt"

        val fewShotHistory = listOf(
            "user" to "/image a cat",
            "assistant" to "A fluffy orange cat sitting on a wooden table, sunlight streaming through a window, highly detailed, 4k, cinematic lighting, sharp focus.",
            "user" to userActualInput
        )

        _uiState.update { it.copy(activeStatusDetail = "Enriching visual prompt with LLM...", loadingStatusText = "Enriching visual prompt with LLM...") }

        val expandedSb = StringBuilder()
        try {
            withTimeoutOrNull(25_000L) {
                if (isLocalLoaded && client != null) {
                    client.generateStreamWithHistory(fewShotHistory, enrichmentSystemPrompt, overrideTemperature = 0.5f).collect { chunk -> expandedSb.append(chunk) }
                } else if (isGeminiLoaded && geminiClient != null) {
                    val messages = JSONArray().apply {
                        for ((role, content) in fewShotHistory) {
                            put(JSONObject().put("role", role).put("content", content))
                        }
                    }
                    geminiClient!!.generateStreamWithOllamaMessages(messages, enrichmentSystemPrompt).collect { chunk -> expandedSb.append(chunk) }
                }
            }
        } catch (_: Exception) {}

        cleanEnrichedPrompt(expandedSb.toString().trim(), shortPrompt)
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
            return fallback
        }
        if (cleaned.length < 5) return fallback
        return cleaned
    }

    suspend fun executeSequentialImageSynthesis(prompt: String): ImageResult = withContext(Dispatchers.Default) {
        val hadLocalModel = (_uiState.value.activeModelType == ModelType.GGUF || _uiState.value.localModelLoaded || (ggufClient != null && ggufClient!!.isLoaded))
        val modelPath = _uiState.value.config.localModelPath.ifBlank { currentLoadingPath ?: "" }
        val targetContext = appContext ?: MainActivity.instance

        if (!imageRepository.localDiffusionEngine.hasModelWeights()) {
            return@withContext imageRepository.generateImage(prompt)
        }

        if (hadLocalModel) {
            _uiState.update { it.copy(activeStatusDetail = "Allocating Diffusion Engine...", loadingStatusText = "Allocating Diffusion Engine...") }
            try { LlamaNativeEngine.unloadModel() } catch (_: Throwable) {}
            try { ggufClient?.stop(); ggufClient?.release() } catch (_: Throwable) {}
            try { PageCacheAdvisor.adviseModelIdle(modelPath) } catch (_: Throwable) {}

            _uiState.update { it.copy(localModelLoaded = false, activeStatusDetail = "Allocating Diffusion Engine...") }
            System.gc()
            System.runFinalization()
        }

        _uiState.update { it.copy(activeStatusDetail = "Synthesizing Image...", loadingStatusText = "Synthesizing Image...") }
        val diffusionEngine = imageRepository.localDiffusionEngine
        val result: ImageResult
        try {
            diffusionEngine.loadDiffusionModel()
            result = diffusionEngine.generateImage(prompt)
        } catch (e: Exception) {
            return@withContext ImageResult.Error("Local diffusion failed: ${e.message}")
        } finally {
            _uiState.update { it.copy(activeStatusDetail = "Flushing Diffusion Engine...", loadingStatusText = "Flushing Diffusion Engine...") }
            try { diffusionEngine.unloadDiffusionModel() } catch (_: Throwable) {}
            System.gc()
            System.runFinalization()
        }

        if (hadLocalModel && modelPath.isNotBlank() && targetContext != null) {
            _uiState.update { it.copy(activeStatusDetail = "Reloading LLM into RAM...", loadingStatusText = "Reloading LLM into RAM...") }
            try { PageCacheAdvisor.adviseModelActive(modelPath) } catch (_: Throwable) {}
            try {
                triggerModelLoad(targetContext, modelPath)
                withTimeoutOrNull(60_000L) { _uiState.first { it.localModelLoaded || it.modelLoadFailed } }
            } catch (_: Exception) {}
        }

        _uiState.update { it.copy(activeStatusDetail = "Ready / Operational", loadingStatusText = "") }
        return@withContext result
    }

    private var currentJob: Job? = null
    private var schedulerJob: Job? = null
    private val scheduledTasks = Collections.synchronizedList(mutableListOf<ScheduledTask>())
    private val workflows = Collections.synchronizedMap(mutableMapOf<String, String>())
    private val pendingCommands = Collections.synchronizedList(mutableListOf<String>())
    private val conversationHistory = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    private fun trimConversationHistoryToContextLimit(targetTokenCeiling: Int? = null) {
        val maxEstimatedTokens = targetTokenCeiling?.coerceAtLeast(1024) ?: run {
            val ctx = _uiState.value.config.contextLength.coerceIn(2048, 8192)
            (ctx - 1500).coerceAtLeast(1024)
        }
        synchronized(conversationHistory) {
            var estimatedTokens = conversationHistory.sumOf { (_, content) -> (content.length / 3.5).toInt() + 10 }
            while (conversationHistory.size > 2 && (estimatedTokens > maxEstimatedTokens || conversationHistory.size > MAX_HISTORY_MESSAGES)) {
                val removed = conversationHistory.removeAt(0)
                estimatedTokens -= ((removed.second.length / 3.5).toInt() + 10)
            }
        }
    }

    private fun compactContinuationContext(text: String): String =
        if (text.length <= 2800) text
        else "${text.take(700)}\n...[middle of previous output omitted]...\n${text.takeLast(1900)}"

    private fun encodeTaskCheckpoint(history: List<Pair<String, String>>): String =
        JSONArray().apply {
            history.takeLast(10).forEachIndexed { index, (role, content) ->
                val savedContent = if (index == 0 && role == "user") content.take(10_000) else content.takeLast(10_000)
                put(JSONObject().put("role", role).put("content", savedContent))
            }
        }.toString()

    private fun decodeTaskCheckpoint(checkpoint: String): MutableList<Pair<String, String>> {
        if (checkpoint.isBlank()) return mutableListOf()
        return try {
            val json = JSONArray(checkpoint)
            MutableList(json.length()) { index ->
                val message = json.getJSONObject(index)
                message.optString("role", "user") to message.optString("content", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to restore background task checkpoint", e)
            throw IllegalStateException("Background task checkpoint is corrupt; refusing to restart from the beginning.", e)
        }
    }

    private val executedCallsThisTurn = Collections.synchronizedSet(mutableSetOf<String>())
    private val inFlightCalls = Collections.synchronizedSet(mutableSetOf<String>())

    fun computeCallHash(toolName: String, params: Map<String, String>): String {
        val normalizedTool = toolName.trim().lowercase()
        val normalizedParams = params.toSortedMap().entries.joinToString("&") { "${it.key.trim().lowercase()}=${it.value.trim()}" }
        val raw = "$normalizedTool|$normalizedParams"
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(raw.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun cleanVisibleText(raw: String): String {
        // Fast-path: if text contains no special tags or backticks or screen dumps, return directly without running regexes
        if (!raw.contains('<') && !raw.contains('`') && !raw.contains("💭") && !raw.contains("SYSTEM MANDATORY") && !raw.contains("Shell output") && !raw.contains("ACTIVE SCREEN") && !raw.contains("SCREEN VISION") && !raw.contains("bounds=\"[")) {
            return raw.trim()
        }
        var text = raw
        if (text.contains("```")) text = text.replace(RE_THINK_FENCED, "")
        if (text.contains('<')) {
            text = text.replace(RE_THINK_TAGS, "")
                .replace(RE_THINK_OPEN, "")
                .replace(RE_FUNC_CALLS, "")
                .replace(RE_INVOKE, "")
                .replace(RE_FUNC_CALLS_OPEN, "")
                .replace(RE_INVOKE_OPEN, "")
                .replace(RE_OBSERVATION, "")
                .replace(RE_OBSERVATION_OPEN, "")
                .replace(RE_TERMINAL, "")
                .replace(RE_TERMINAL_OPEN, "")
                .replace(RE_THINK_TAG_SOLO, "")
                .replace(RE_TERMINAL_TAG_SOLO, "")
        }
        if (text.contains("=== ACTIVE SCREEN NODE HIERARCHY ===") || text.contains("=== SCREEN VISION & OCR REPORT ===")) {
            text = text.replace(Regex("""=== ACTIVE SCREEN NODE HIERARCHY ===[\s\S]*?(?=\n\n[^\s•<]|\z)"""), "")
                .replace(Regex("""=== SCREEN VISION & OCR REPORT ===[\s\S]*?(?=\n\n[^\s•<]|\z)"""), "")
                .trim()
        }
        if (text.contains("<node") || text.contains("</node>")) {
            text = text.replace(Regex("""<node\b[^>]*>(?:[\s\S]*?</node>)?"""), "")
                .replace(Regex("""<node\b[^>]*/>"""), "")
                .replace("</node>", "")
                .trim()
        }
        if (text.contains("<hierarchy") || text.contains("</hierarchy>")) {
            text = text.replace(Regex("""<hierarchy\b[^>]*>[\s\S]*?</hierarchy>"""), "")
                .replace("</hierarchy>", "")
                .trim()
        }
        if (text.contains("bounds=\"[")) {
            text = text.replace(Regex("""•\s*<[a-zA-Z0-9_.]+\b[^>]*bounds="\[\d+,\d+\]\[\d+,\d+\]"[^>]*>\s*"""), "")
                .trim()
        }
        if (text.contains("Shell output")) text = text.replace(RE_SHELL_OUT, "")
        if (text.contains("SYSTEM MANDATORY")) text = text.replace(RE_SYS_INSTRUCTION, "")
        if (text.startsWith("💭")) text = text.replace(RE_THOUGHT_EMOJI, "")
        return text.trim()
    }

    private fun stripThinkingForHistory(raw: String): String {
        if (!raw.contains('<') && !raw.contains('`') && !raw.contains("SYSTEM MANDATORY")) {
            return raw.trim()
        }
        var text = raw
        if (text.contains("```")) text = text.replace(RE_THINK_FENCED, "")
        if (text.contains('<')) {
            text = text.replace(RE_THINK_TAGS, "")
                .replace(RE_THINK_OPEN, "")
                .replace(RE_OBSERVATION, "")
                .replace(RE_OBSERVATION_OPEN, "")
                .replace(RE_THINK_TAG_SOLO, "")
        }
        if (text.contains("SYSTEM MANDATORY")) text = text.replace(RE_SYS_INSTRUCTION, "")
        return text.trim()
    }

    private fun compactHistoryForQuota(content: String): String {
        val maxLen = if (_uiState.value.config.contextLength >= 16384) 16000 else 2400
        if (!content.contains("```")) return content.take(maxLen)
        return content.replace(RE_CODE_FENCE_COMPACT) { mr ->
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

    fun sanitizeProcessOutput(raw: String, maxChars: Int = 800, maxLines: Int = 20): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "[Process completed with no output]"
        val lines = trimmed.lines()
        val lineCapped = if (lines.size > maxLines) lines.takeLast(maxLines).joinToString("\n") else trimmed
        val charCapped = if (lineCapped.length > maxChars) lineCapped.takeLast(maxChars) else lineCapped
        return charCapped.trim().ifEmpty { "[Process completed with no output]" }
    }

    fun truncateSearchObservation(raw: String, maxChars: Int = 1500, maxLines: Int = 25): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "[No search data found]"
        val lines = trimmed.lines()
        val lineCapped = if (lines.size > maxLines) lines.take(maxLines).joinToString("\n") else trimmed
        val charCapped = if (lineCapped.length > maxChars) lineCapped.takeLast(maxChars) else lineCapped
        return charCapped.trim().ifEmpty { "[No search data found]" }
    }

    suspend fun executeSafeProcess(command: String, timeoutMs: Long = 0L): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (command.isBlank()) return@withContext -1 to "[Process completed with no output]"
        try {
            val (exitCode, rawOutput) = runProcessWithWatchdog(command, "sh", timeoutMs)
            exitCode to sanitizeProcessOutput(rawOutput)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            -1 to sanitizeProcessOutput("Execution error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    data class ParsedThought(val thought: String, val isThinking: Boolean, val content: String)

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
        val content = if (contentParts.isNotEmpty()) contentParts.joinToString("\n\n").trim() else cleanVisibleText(raw)
        return ParsedThought(thought = demuxed.thinkingText, isThinking = demuxed.isThinking, content = content)
    }

    fun setRootAvailable(available: Boolean) {
        _uiState.update { it.copy(isRootAvailable = available) }
        Log.i(TAG, "Superuser status: ${if (available) "ROOT GRANTED" else "STANDARD USER"}")
    }

    fun togglePersona(personaName: String, context: Context) {
        val current = _uiState.value.activePersonas.toMutableSet()
        if (current.contains(personaName)) {
            if (current.size > 1) current.remove(personaName)
        } else {
            current.add(personaName)
        }
        PersonaManager.setActivePersonas(context, current)
        val primary = current.firstOrNull() ?: PersonaManager.DEFAULT_PERSONA
        _uiState.update { it.copy(activePersonas = current, activePersona = primary, config = it.config.copy(activePersonas = current, activePersona = primary)) }
        viewModelScope.launch(Dispatchers.IO) {
            val dynamicPersona = getEffectivePersonaPrompt(context)
            val isLocal = _uiState.value.activeModelType == ModelType.GGUF || _uiState.value.config.preferredModelType == ModelType.GGUF
            val systemPrompt = buildSystemPrompt(context, forLocalModel = isLocal, dynamicPersonaPrompt = dynamicPersona)
            withTimeoutOrNull(30_000L) { ggufClient?.warmupPersonaKVCache(systemPrompt) }
        }
    }

    fun setActivePersonas(personas: Set<String>, context: Context) {
        val safeSet = if (personas.isEmpty()) PersonaManager.DEFAULT_PERSONAS_SET else personas
        PersonaManager.setActivePersonas(context, safeSet)
        val primary = safeSet.firstOrNull() ?: PersonaManager.DEFAULT_PERSONA
        _uiState.update { it.copy(activePersonas = safeSet, activePersona = primary, config = it.config.copy(activePersonas = safeSet, activePersona = primary)) }
        viewModelScope.launch(Dispatchers.IO) {
            val dynamicPersona = getEffectivePersonaPrompt(context)
            val isLocal = _uiState.value.activeModelType == ModelType.GGUF || _uiState.value.config.preferredModelType == ModelType.GGUF
            val systemPrompt = buildSystemPrompt(context, forLocalModel = isLocal, dynamicPersonaPrompt = dynamicPersona)
            withTimeoutOrNull(30_000L) { ggufClient?.warmupPersonaKVCache(systemPrompt) }
        }
    }

    fun openLiveCanvas() { _uiState.update { it.copy(isLiveCanvasOpen = true) } }
    fun closeLiveCanvas() { _uiState.update { it.copy(isLiveCanvasOpen = false) } }
    fun toggleLiveCanvas() { _uiState.update { it.copy(isLiveCanvasOpen = !it.isLiveCanvasOpen) } }

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
            chatDb?.insertMessage(ChatMessageEntity(id = agentLog.id, sessionId = sid, sender = "AGENT", text = terminalFrameText, timestamp = agentLog.timestamp))
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

    private var appContext: Context? = null

    val settingsViewModel: SettingsViewModel by lazy {
        val app = (appContext as? Application) ?: (MainActivity.instance?.application)
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
            viewModelScope.launch(Dispatchers.IO) { PersonaDataStore.saveCustomPersonaPrompt(ctx, newPrompt) }
        }
    }

    fun resetCustomPersonaPrompt(context: Context? = null) {
        val defaultVal = PersonaDataStore.DEFAULT_PROMPT
        _customPersonaPrompt.value = defaultVal
        cachedStaticPrefix = null
        cachedPrefixKey = ""
        val ctx = context ?: appContext ?: MainActivity.instance
        if (ctx != null) {
            viewModelScope.launch(Dispatchers.IO) { PersonaDataStore.saveCustomPersonaPrompt(ctx, defaultVal) }
        }
    }

    suspend fun getEffectivePersonaPrompt(context: Context? = null): String {
        val targetCtx = context ?: appContext ?: MainActivity.instance
        val prompt = if (targetCtx != null) PersonaDataStore.getCustomPersonaPrompt(targetCtx) else _customPersonaPrompt.value.ifBlank { PersonaDataStore.DEFAULT_PROMPT }
        _customPersonaPrompt.value = prompt
        return prompt
    }

    fun setDraftModelPath(context: Context, path: String) {
        val trimmed = path.trim()
        val currentConfig = _uiState.value.config
        val updatedConfig = currentConfig.copy(draftModelPath = trimmed)
        val effectiveDraft = if (trimmed.isNotBlank() && trimmed != "STANDALONE") trimmed else null

        _uiState.update { it.copy(config = updatedConfig, activeDraftModelPath = effectiveDraft) }
        AgentSettingsRepository.setDraftModelPath(context, trimmed)
        try { prefs(context).edit().putString("draft_model_path", trimmed).commit() } catch (_: Exception) {}

        if (updatedConfig.preferredModelType == ModelType.GGUF && updatedConfig.localModelPath.isNotBlank()) {
            closeAllModels()
            viewModelScope.launch(Dispatchers.IO) {
                delay(300)
                triggerModelLoad(context, updatedConfig.localModelPath)
            }
        }
    }

    fun saveConfig(context: Context, config: AgentConfig) {
        val userModel = config.geminiModelName.trim().removeSurrounding("\"").removeSurrounding("'").removeSurrounding("`").removePrefix("models/")
        val finalConfig = config.copy(
            contextLength = config.contextLength.coerceIn(2048, 8192),
            gpuLayers = config.gpuLayers.coerceAtLeast(0),
            threadCount = if (config.threadCount in 1..32) config.threadCount else 4,
            ttsEnabled = false,
            geminiModelName = userModel
        )
        val oldConfig = _uiState.value.config
        val activeKeys = finalConfig.geminiApiKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val newDraftPath = if (finalConfig.draftModelPath.isNotBlank() && finalConfig.draftModelPath != "STANDALONE") finalConfig.draftModelPath else null
        val baseModelName = if (finalConfig.localModelPath.isNotBlank()) File(finalConfig.localModelPath).nameWithoutExtension else _uiState.value.activeModelName.ifBlank { "GEMMA-4-E2B Q4" }

        _uiState.update { 
            it.copy(
                config = finalConfig, 
                totalKeys = activeKeys.size,
                activeDraftModelPath = newDraftPath,
                activeModelName = baseModelName,
                isTtsEnabled = false
            ) 
        }
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
            putBoolean("tts_enabled", false)
            apply()
        }

        if (finalConfig.preferredModelType == ModelType.GGUF) {
            ggufClient?.temperature = finalConfig.temperature
            if (finalConfig.localModelPath != oldConfig.localModelPath ||
                finalConfig.draftModelPath != oldConfig.draftModelPath ||
                finalConfig.gpuLayers != oldConfig.gpuLayers ||
                !isModelLoaded()
            ) {
                closeAllModels()
                viewModelScope.launch(Dispatchers.IO) {
                    delay(300)
                    triggerModelLoad(context, finalConfig.localModelPath)
                }
            }
        } else if (finalConfig.preferredModelType == ModelType.GEMINI) {
            geminiClient?.temperature = finalConfig.temperature
            GGUFModelClient.stopGlobalServer()
            if (finalConfig.geminiApiKeys != oldConfig.geminiApiKeys || !isModelLoaded() || geminiClient == null) {
                closeAllModels()
                loadGeminiModel(finalConfig.geminiApiKeys, finalConfig.geminiModelName, context)
            } else {
                geminiClient?.modelName = finalConfig.geminiModelName
                _uiState.update { it.copy(activeModelName = finalConfig.geminiModelName) }
            }
        }
    }

    fun updateConfig(config: AgentConfig, context: Context) = saveConfig(context, config)

    fun toggleMmapState(enableMmap: Boolean, context: Context? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.localModelLoading || isModelLoadingInProgress) return@launch
            if (_uiState.value.localModelLoaded || isModelLoaded()) closeAllModels()

            val targetContext = context ?: appContext ?: MainActivity.instance
            val newConfig = _uiState.value.config.copy(useMmap = enableMmap)
            _uiState.update { it.copy(config = newConfig) }

            if (targetContext != null) {
                AgentSettingsRepository.saveConfig(targetContext, newConfig)
                try { prefs(targetContext).edit().putBoolean("use_mmap", enableMmap).apply() } catch (_: Exception) {}
                if (newConfig.preferredModelType == ModelType.GGUF && newConfig.localModelPath.isNotBlank()) {
                    delay(300)
                    triggerModelLoad(targetContext, newConfig.localModelPath)
                }
            }
        }
    }

    fun toggleDraftMmapState(enableMmap: Boolean, context: Context? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.localModelLoading || isModelLoadingInProgress) return@launch
            if (_uiState.value.localModelLoaded || isModelLoaded()) closeAllModels()

            val targetContext = context ?: appContext ?: MainActivity.instance
            val newConfig = _uiState.value.config.copy(draftUseMmap = enableMmap)
            _uiState.update { it.copy(config = newConfig) }

            if (targetContext != null) {
                AgentSettingsRepository.saveConfig(targetContext, newConfig)
                try { prefs(targetContext).edit().putBoolean("draft_use_mmap", enableMmap).apply() } catch (_: Exception) {}
                if (newConfig.preferredModelType == ModelType.GGUF && newConfig.localModelPath.isNotBlank()) {
                    delay(300)
                    triggerModelLoad(targetContext, newConfig.localModelPath)
                }
            }
        }
    }

    fun togglePinModelInRam(enable: Boolean, context: Context? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.localModelLoading || isModelLoadingInProgress) return@launch
            if (_uiState.value.localModelLoaded || isModelLoaded()) closeAllModels()

            val targetContext = context ?: appContext ?: MainActivity.instance
            val newConfig = _uiState.value.config.copy(pinModelInRam = enable, useMmap = !enable, draftUseMmap = !enable)
            _uiState.update { it.copy(config = newConfig) }

            if (targetContext != null) {
                AgentSettingsRepository.saveConfig(targetContext, newConfig)
                try {
                    prefs(targetContext).edit().putBoolean("pin_model_in_ram", enable).putBoolean("use_mmap", !enable).putBoolean("draft_use_mmap", !enable).apply()
                } catch (_: Exception) {}
                if (newConfig.preferredModelType == ModelType.GGUF && newConfig.localModelPath.isNotBlank()) {
                    delay(300)
                    triggerModelLoad(targetContext, newConfig.localModelPath)
                }
            }
        }
    }

    fun loadConfig(context: Context) {
        appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loaded = PersonaDataStore.getCustomPersonaPrompt(context)
                _customPersonaPrompt.value = loaded
                launch {
                    PersonaDataStore.getCustomPersonaPromptFlow(context).collectLatest { prompt -> _customPersonaPrompt.value = prompt }
                }
            } catch (_: Exception) {}

            try {
                if (portalServer == null) {
                    portalServer = PortalServer(this@AgentViewModel)
                    portalServer?.start()
                }
            } catch (_: Exception) {}

            try {
                val prefs = prefs(context)
                val savedPath = prefs.getString("local_model_path", null)
                var savedContext = prefs.getInt("context_length", 4096).coerceIn(2048, 8192)

                val modelTypeStr = prefs.getString("preferred_model_type", ModelType.GEMINI.name)
                val modelType = try { ModelType.valueOf(modelTypeStr!!) } catch (_: Exception) { ModelType.GEMINI }
                var geminiKeys = prefs.getString("gemini_api_keys", "") ?: ""
                var geminiModel = sanitizeGeminiModelName(prefs.getString("gemini_model_name", DEFAULT_GEMINI_MODEL))
                if (prefs.getString("gemini_model_name", "") != geminiModel) {
                    prefs.edit().putString("gemini_model_name", geminiModel).apply()
                }
                ModelScanner.ensureDirectories()
                val activePersonasSet = PersonaManager.getActivePersonas(context)
                val activePersonaName = activePersonasSet.firstOrNull() ?: PersonaManager.DEFAULT_PERSONA
                val cachedPersonasList = PersonaManager.getPersonas(context, forceRescan = false)
                
                var localPath = savedPath ?: ""
                if (localPath.isBlank() || !File(localPath).exists() || !ModelScanner.isBaseModelFile(File(localPath))) {
                    val cachedModels = ModelScanner.getBaseModels(context, forceRescan = true).filter { ModelScanner.isBaseModelFile(it) }
                    if (cachedModels.isNotEmpty()) {
                        val preferred = cachedModels.firstOrNull { ModelScanner.isQuantizedModel(it) } ?: cachedModels.first()
                        localPath = preferred.absolutePath
                        prefs.edit().putString("local_model_path", localPath).apply()
                    }
                }
                val rawDraft = AgentSettingsRepository.getDraftModelPath(context).ifBlank { prefs.getString("draft_model_path", "") ?: "" }
                val draftPath = if (rawDraft.isNotBlank() && rawDraft != "STANDALONE" && File(rawDraft).exists()) rawDraft else "STANDALONE"

                val activeKeysList = geminiKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                var effectiveModelType = modelType
                if (effectiveModelType == ModelType.GEMINI && activeKeysList.isEmpty() && localPath.isNotBlank()) {
                    effectiveModelType = ModelType.GGUF
                }
                
                val config = AgentConfig(
                    preferredModelType = effectiveModelType,
                    localModelPath = localPath,
                    draftModelPath = draftPath,
                    gpuLayers = prefs.getInt("gpu_layers", 0),
                    useMmap = prefs.getBoolean("use_mmap", true),
                    draftUseMmap = prefs.getBoolean("draft_use_mmap", true),
                    pinModelInRam = prefs.getBoolean("pin_model_in_ram", false),
                    geminiApiKeys = geminiKeys,
                    geminiModelName = geminiModel,
                    serverHost = prefs.getString("server_host", "0.0.0.0") ?: "0.0.0.0",
                    serverPort = prefs.getInt("server_port", 8088),
                    contextLength = savedContext,
                    threadCount = prefs.getInt("thread_count", 4).coerceIn(1, 32),
                    systemPrompt = prefs.getString("system_prompt", "") ?: "",
                    temperature = prefs.getFloat("temperature", 0.30f),
                    activePersona = activePersonaName,
                    activePersonas = activePersonasSet,
                    sdSteps = prefs.getInt("sd_steps", 0),
                    sdCfgScale = prefs.getFloat("sd_cfg_scale", 0f),
                    sdWidth = prefs.getInt("sd_width", 256),
                    sdHeight = prefs.getInt("sd_height", 256),
                    sdLowMemoryMode = prefs.getBoolean("sd_low_memory_mode", true),
                    sdThreads = prefs.getInt("sd_threads", 4),
                    sdEngineMode = prefs.getString("sd_engine_mode", "SD_TURBO") ?: "SD_TURBO",
                    sdComputeDevice = prefs.getString("sd_compute_device", "AUTO") ?: "AUTO",
                    ttsEnabled = false
                )

                val savedKeyIndex = prefs.getInt("current_key_index", 0).coerceIn(0, (activeKeysList.size - 1).coerceAtLeast(0))

                _uiState.update { 
                    it.copy(
                        config = config, 
                        totalKeys = activeKeysList.size,
                        currentKeyIndex = savedKeyIndex,
                        activePersona = activePersonaName,
                        activePersonas = activePersonasSet,
                        availablePersonas = cachedPersonasList,
                        activeModelName = if (localPath.isNotBlank()) File(localPath).nameWithoutExtension else "GEMMA-4-E2B Q4",
                        isTtsEnabled = false
                    ) 
                }

                if (chatDb == null) chatDb = ChatDatabaseHelper(context)
                refreshSessions()
                startNewSession()
                initTaskSystem(context)
                startScheduler()

                if (config.preferredModelType == ModelType.GGUF && config.localModelPath.isNotBlank()) {
                    triggerModelLoad(context, config.localModelPath)
                } else if (config.preferredModelType == ModelType.GEMINI && activeKeysList.isNotEmpty()) {
                    loadGeminiModel(config.geminiApiKeys, config.geminiModelName, context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "initialize failed", e)
            }
        }
    }

    suspend fun triggerModelLoad(context: Context, path: String): Unit = withContext(Dispatchers.IO) {
        val shouldStart = synchronized(modelLoadLock) {
            if (isModelLoadingInProgress) {
                if (currentLoadingPath != path) {
                    pendingModelLoad = context.applicationContext to path
                }
                false
            } else {
                isModelLoadingInProgress = true
                currentLoadingPath = path
                true
            }
        }
        if (!shouldStart) return@withContext

        modelLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val modelName = File(path).nameWithoutExtension
                _uiState.update {
                    it.copy(
                        localModelLoading = true,
                        localModelLoaded = false,
                        modelLoadFailed = false,
                        loadingProgress = 0f,
                        loadingStatusText = "Preparing $modelName..."
                    )
                }
                ModelEngineService.start(context, "Loading local model: ${modelName.take(36)}")
                
                val config = _uiState.value.config
                val client = GGUFModelClient().apply {
                    serverHost = config.serverHost
                    serverPort = config.serverPort
                    contextLength = config.contextLength.coerceIn(1024, 4096)
                    threadCount = config.threadCount.coerceIn(1, 8)
                    maxTokens = config.maxTokens.coerceIn(1, 8192)
                    temperature = config.temperature
                    useMmap = config.useMmap
                    gpuLayers = config.gpuLayers
                }
                ggufClient = client
                var latestLoadStatus = "Preparing $modelName..."
                client.loadModel(
                    context = context,
                    path = path,
                    onStatus = { status ->
                        latestLoadStatus = status
                        addSystemLog(status)
                        _uiState.update {
                            if (it.localModelLoading) it.copy(loadingStatusText = status.take(240)) else it
                        }
                    },
                    onProgress = { p, s -> _uiState.update { it.copy(loadingProgress = p, loadingStatusText = s) } },
                    onLoaded = {
                        if (client.isLoaded) {
                            _uiState.update { it.copy(localModelLoaded = true, localModelLoading = false, activeModelType = ModelType.GGUF, activeModelName = modelName) }
                            ModelEngineService.updateStatus(context, "Local model ready: ${modelName.take(36)}")
                        } else {
                            _uiState.update {
                                it.copy(
                                    localModelLoaded = false,
                                    localModelLoading = false,
                                    modelLoadFailed = true,
                                    loadingStatusText = latestLoadStatus.take(240)
                                )
                            }
                            ModelEngineService.updateStatus(context, "Local model startup failed")
                        }
                    }
                )
                if (client.isLoaded) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val personaPrompt = getEffectivePersonaPrompt(context)
                            val systemPrompt = buildSystemPrompt(
                                context = context,
                                forLocalModel = true,
                                dynamicPersonaPrompt = personaPrompt
                            )
                            withTimeoutOrNull(30_000L) {
                                client.warmupPersonaKVCache(systemPrompt)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Local model persona warmup failed: ${e.message}")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Local model startup failed for $path", e)
                _uiState.update {
                    it.copy(
                        localModelLoaded = false,
                        localModelLoading = false,
                        modelLoadFailed = true,
                        loadingStatusText = "Model startup error: ${e.message ?: "unknown error"}"
                    )
                }
                ModelEngineService.updateStatus(context, "Local model startup error")
            } finally {
                val pending = synchronized(modelLoadLock) {
                    isModelLoadingInProgress = false
                    currentLoadingPath = null
                    pendingModelLoad.also { pendingModelLoad = null }
                }
                if (pending != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        triggerModelLoad(pending.first, pending.second)
                    }
                }
            }
        }
    }

    private val modelLoadLock = Any()
    private var modelLoadJob: Job? = null
    private var pendingModelLoad: Pair<Context, String>? = null
    @Volatile private var isModelLoadingInProgress = false
    @Volatile private var currentLoadingPath: String? = null

    private fun loadGeminiModel(apiKeysRaw: String, modelName: String = DEFAULT_GEMINI_MODEL, context: Context? = null) {
        val safeModelName = sanitizeGeminiModelName(modelName)
        val keys = apiKeysRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (keys.isEmpty()) return
        
        val targetCtx = context ?: appContext ?: MainActivity.instance
        val savedIdx = targetCtx?.let { prefs(it).getInt("current_key_index", 0) } ?: _uiState.value.currentKeyIndex
        val safeInitialIdx = savedIdx.coerceIn(0, keys.size - 1)

        closeAllModels()
        geminiClient = GeminiModelClient(
            apiKeys = keys,
            initialKeyIndex = safeInitialIdx,
            onKeyRotated = { idx ->
                _uiState.update { it.copy(currentKeyIndex = idx, totalKeys = keys.size) }
                targetCtx?.let { prefs(it).edit().putInt("current_key_index", idx).apply() }
            },
            onKeyValidated = { idx ->
                _uiState.update { it.copy(currentKeyIndex = idx, totalKeys = keys.size) }
                targetCtx?.let { prefs(it).edit().putInt("current_key_index", idx).apply() }
            },
            onKeyUsed = { key, tokensUsed ->
                targetCtx?.let {
                    KeyUsageManager.incrementUsage(it.applicationContext, key, safeModelName, tokensUsed)
                }
            },
            getKeyUsage = { key ->
                targetCtx?.let { KeyUsageManager.getTokenUsage(it.applicationContext, key, safeModelName) } ?: 0L
            },
            onModelAutoSwitched = { validModelName ->
                _uiState.update { it.copy(
                    activeModelName = validModelName,
                    config = it.config.copy(geminiModelName = validModelName)
                ) }
                targetCtx?.let { prefs(it).edit().putString("gemini_model_name", validModelName).apply() }
            }
        )
        geminiClient?.modelName = safeModelName
        geminiClient?.maxTokens = _uiState.value.config.maxTokens.coerceIn(1, 8192)
        geminiClient?.temperature = _uiState.value.config.temperature
        geminiClient?.isLoaded = true

        _uiState.update { it.copy(
            initPhase = InitializationPhase.SYSTEM_READY,
            activeModelType = ModelType.GEMINI,
            activeModelName = safeModelName,
            totalKeys = keys.size,
            currentKeyIndex = safeInitialIdx,
            modelLoadFailed = false
        ) }
    }

    private fun isModelLoaded(): Boolean = (ggufClient?.isLoaded == true) || (geminiClient?.isLoaded == true)
    fun isReadyForBackgroundTasks(): Boolean = isModelLoaded()

    fun closeAllModels() {
        synchronized(modelLoadLock) {
            pendingModelLoad = null
        }
        modelLoadJob?.cancel()
        modelLoadJob = null
        GGUFModelClient.stopGlobalServer()
        ggufClient?.close()
        ggufClient = null
        geminiClient = null
        _uiState.update { it.copy(localModelLoaded = false, localModelLoading = false, modelLoadFailed = false, activeModelType = null) }
    }

    @Volatile private var cachedStaticPrefix: String? = null
    @Volatile private var cachedPrefixKey: String = ""

    enum class DetectedLanguage {
        ARABIC, ENGLISH, FRENCH, SPANISH
    }

    fun detectLanguage(text: String): DetectedLanguage {
        val clean = text.trim()
        if (clean.isBlank()) return DetectedLanguage.ENGLISH
        val arabicCharCount = clean.count { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\u08A0'..'\u08FF' }
        if (arabicCharCount > 0) return DetectedLanguage.ARABIC

        val lower = clean.lowercase(Locale.ROOT)
        val frenchWords = setOf("bonjour", "salut", "merci", "comment", "pourquoi", "avec", "dans", "pour", "cette", "faire", "qui", "est", "sont", "une", "des", "les")
        val words = lower.split(Regex("\\s+")).map { it.trim('?', '!', '.', ',', ';', ':', '\'', '"') }
        if (words.any { it in frenchWords } || lower.contains("ça va") || lower.contains("s'il vous")) {
            return DetectedLanguage.FRENCH
        }
        val spanishWords = setOf("hola", "gracias", "por", "favor", "como", "estas", "qué", "que", "para", "buenos", "dias", "tardes", "noches", "amigo")
        if (words.any { it in spanishWords }) {
            return DetectedLanguage.SPANISH
        }
        return DetectedLanguage.ENGLISH
    }

    fun isSimpleGreeting(text: String): Boolean {
        val clean = text.trim().lowercase(Locale.ROOT)
            .replace("!", "").replace(".", "").replace("?", "").replace("،", "").replace(",", "")
            .trim()
        val exactGreetings = setOf(
            "hello", "hi", "hey", "howdy", "greetings", "good morning", "good afternoon", "good evening",
            "how are you", "how are you doing", "hello how are you", "hi how are you", "hey how are you",
            "hello there", "hi there", "hey there", "who are you", "what is your name",
            "مرحبا", "مرحباً", "اهلا", "أهلا", "أهلاً", "سلام", "سلام عليكم", "السلام عليكم", "السلام عليكم ورحمة الله",
            "صباح الخير", "مساء الخير", "تحياتي", "أهلا وسهلا", "اهلاً وسهلاً", "هلا", "كيف حالك", "كيفك", "شخبارك",
            "مرحبا كيف حالك", "اهلا كيف حالك", "السلام عليكم كيفك", "من انت", "من أنت", "ما اسمك",
            "bonjour", "salut", "coucou", "bonsoir", "comment vas-tu", "comment allez-vous",
            "hola", "yo", "ca va", "ça va", "buenas tardes", "buenos dias"
        )
        return clean in exactGreetings
    }

    fun isGreeting(text: String): Boolean = isSimpleGreeting(text)

    private fun isCodeRequest(prompt: String): Boolean {
        if (isSimpleGreeting(prompt)) return false
        val lower = prompt.lowercase(Locale.ROOT)

        // Exclude terminal and operational execution requests
        val terminalExecutionKeywords = listOf(
            "باش", "ترمنال", "طرفية", "سطر الأوامر", "نفذ", "شغل", "افحص", "تفقد", "امسح", "نظف", "تحقق",
            "bash", "terminal", "shell", "run", "execute", "exec", "check", "inspect"
        )
        val isExplicitWriting = listOf("اكتب كود", "أنشئ تطبيق", "اصنع تطبيق", "طور تطبيق", "write code", "create app", "develop app").any { lower.contains(it) }

        if (terminalExecutionKeywords.any { lower.contains(it) } && !isExplicitWriting) {
            return false
        }

        val arabicKeywords = listOf(
            "كود", "برمج", "تطبيق", "صفحة", "موقع", "أنشئ", "اصنع", "اكتب", "برنامج",
            "حاسبة", "لعبة", "طور", "دالة", "خوارزمية", "بايثون"
        )
        val englishKeywords = listOf(
            "code", "function", "class", "algorithm", "implement", "program",
            "develop", "app", "html", "css", "javascript", "python", "kotlin",
            "java", "sql", "write code"
        )
        return arabicKeywords.any { lower.contains(it) } || englishKeywords.any { lower.contains(it) }
    }

    private fun buildSystemPrompt(
        context: Context? = null,
        forLocalModel: Boolean = false,
        dynamicPersonaPrompt: String? = null,
        isCodeRequest: Boolean = false,
        isGreeting: Boolean = false,
        userPrompt: String = ""
    ): String {
        val config = _uiState.value.config
        val actualContext = context ?: appContext ?: MainActivity.instance
        val customPrompt = config.systemPrompt.trim()
        val effectivePersona = (dynamicPersonaPrompt ?: _customPersonaPrompt.value.ifBlank {
            actualContext?.let { PersonaDataStore.getCustomPersonaPromptSync(it) } ?: PersonaDataStore.DEFAULT_PROMPT
        }).trim()
        val isDefaultPersona = effectivePersona == PersonaManager.UNIFIED_CORE_PROMPT.trim()
        val useCompactLocalPersona = forLocalModel &&
            actualContext?.let { DeviceProfile.detect(it).totalRamMb < 8_192L } == true
        val localPersona = if (useCompactLocalPersona && isDefaultPersona) {
            """You are ATLAS-AI, a capable and concise technical assistant. Follow the user's active persona.
                |Be accurate; never claim an action succeeded without verified tool output.
                |For actionable tasks, invoke the appropriate tool immediately using its required format.
                |Run shell tasks through the configured Kali chroot. Preserve user context and respond in the user's language.""".trimMargin()
        } else {
            effectivePersona
        }

        val detectedLang = detectLanguage(userPrompt)

        val antiMonologueDirective = if (isCodeRequest) {
            "\n\nCRITICAL: For code generation, bypass reasoning and start immediately with markdown fence."
        } else ""

        val greetingDirective = if (isGreeting) {
            when (detectedLang) {
                DetectedLanguage.ARABIC -> "\n\n[GREETING]: Reply as the model with one short, natural Arabic greeting. Do not introduce yourself, list your roles, or explain anything unless the user explicitly asks who you are."
                DetectedLanguage.FRENCH -> "\n\n[GREETING]: Reply as the model with one short, natural French greeting. Do not introduce yourself or explain anything unless explicitly asked."
                DetectedLanguage.SPANISH -> "\n\n[GREETING]: Reply as the model with one short, natural Spanish greeting. Do not introduce yourself or explain anything unless explicitly asked."
                DetectedLanguage.ENGLISH -> "\n\n[GREETING]: Reply as the model with one short, natural English greeting. Do not introduce yourself, list your roles, or explain anything unless the user explicitly asks who you are."
            }
        } else ""

        val languageEnforcement = when (detectedLang) {
            DetectedLanguage.ARABIC -> """
[CRITICAL LANGUAGE DIRECTIVE - ARABIC (ABSOLUTE PRIORITY)]:
User prompt is in ARABIC. You MUST reply 100% in ARABIC.
DO NOT use English or other languages except for technical commands/code syntax.
""".trimIndent()
            DetectedLanguage.ENGLISH -> """
[CRITICAL LANGUAGE DIRECTIVE - ENGLISH (ABSOLUTE PRIORITY)]:
User prompt is in ENGLISH. You MUST reply strictly and 100% in ENGLISH ONLY.
NEVER OUTPUT ARABIC CHARACTERS, WORDS, OR SENTENCES UNDER ANY CIRCUMSTANCES.
Even if previous turns or chat history were in Arabic, you MUST ignore past Arabic turns and reply strictly in ENGLISH.
""".trimIndent()
            DetectedLanguage.FRENCH -> """
[CRITICAL LANGUAGE DIRECTIVE - FRENCH (ABSOLUTE PRIORITY)]:
User prompt is in FRENCH. You MUST reply 100% in FRENCH ONLY.
""".trimIndent()
            DetectedLanguage.SPANISH -> """
[CRITICAL LANGUAGE DIRECTIVE - SPANISH (ABSOLUTE PRIORITY)]:
User prompt is in SPANISH. You MUST reply 100% in SPANISH ONLY.
""".trimIndent()
        }

        val universalGroundingDirective = """

## UNIVERSAL TOOL DISPATCHING & ZERO-HALLUCINATION EXECUTION:
1. MANDATORY FIRST-TURN TOOL INVOCATION:
   - When the user asks you to perform an operational action (e.g. open an app, check RAM/CPU/storage, run a bash command, search the web, write a file, calculate with Python):
   - You MUST output the tool call `<invoke name="...">` IMMEDIATELY in your very first response turn!
   - You are STRICTLY FORBIDDEN from stating in conversational text that you performed the action or checked the system without actually calling the tool!
   - All tasks execute right inside this ATLAS-AI agent interface. Never exit or return to launcher.
2. HOST OS vs EXTERNAL WORLD:
   - Use `open_app(package_name)` to launch Android applications dynamically.
   - Use `run_shell(cmd)` ONLY for local host OS operations (file manipulation, local hardware, RAM, storage, process management, shell commands). NEVER run terminal diagnostic commands (such as `uptime`, `date`, `free`) to answer external world questions.
   - Use `web_search(query)` for ALL real-world facts, current events, live prices, documentation, sports, weather, entity queries, and online knowledge.
   - Use `fetch_web_content(url)` to retrieve clean, in-depth text content from URLs found via web search.
   - Use `run_python(script)` to calculate algebra, integrals, calculus, and scientific computations locally with zero hallucination.
   - Use `write_file(path, content)` to create or overwrite local files.
   - Use `read_file_range(path, start_line, line_count)` to inspect large files efficiently without exhausting memory.
   - Use `schedule_task(description, command, delay_minutes)` to register delayed hardware alarms.
3. MANDATORY SYNTHESIS TURN (CRITICAL):
   - You must NOT emit redundant tool calls. Once findings are provided, you are STRICTLY FORBIDDEN from calling the tool again.
   - You MUST analyze the findings and deliver a clear, structured summary directly to the user in the same turn. State verified numbers, scores, and real metrics directly. NEVER output prompt templates or XML tags back to the user.
""".trimIndent()

        if (forLocalModel) {
            val custom = if (customPrompt.isNotBlank()) "\n$customPrompt" else ""
            if (isGreeting) {
                return """$languageEnforcement

$localPersona$greetingDirective$custom
""".trimIndent()
            }
            return """$languageEnforcement

$localPersona
Response style: Answer the user's actual question directly and with only the detail it calls for. Keep simple questions to one or two sentences. Do not add an introduction, self-description, role list, or unsolicited explanation; describe yourself only when explicitly asked.
Autonomy: Continue multi-step tasks through planning, tool execution, verification, and a final summary. Ask the user only when a required fact, choice, or authorization cannot be safely inferred; state exactly what is needed and do not claim completion while blocked.
Tools: open_app(package_name), run_shell(command), write_file(path, content), web_search(query), run_python(script), fetch_web_content(url), read_file_range(path, start_line, line_count), list_tasks(), resume_task(task_id, user_input).
Format: <invoke name="tool_name"><parameter name="param">value</parameter></invoke>$custom
CRITICAL EXECUTION RULES:
- ZERO HALLUCINATION: Never claim you opened an app or ran a command without invoking the tool!
- IMMEDIATE EXECUTION: Whenever an action is requested, emit <invoke name="..."> on the FIRST turn.
- TERMINAL ACTIONS: To run shell/bash commands or inspect system resources, you MUST ALWAYS invoke run_shell: <invoke name="run_shell"><parameter name="command">command</parameter></invoke>. NEVER wrap bash commands in markdown code blocks!
- PROGRAMMING CODE: Only wrap programming code (Python, Kotlin, HTML, JS) in markdown triple backticks (```language ... ```) when writing software artifacts.

$languageEnforcement""".trimIndent()
        }

        val recalledVectorMemories = if (userPrompt.isNotBlank()) {
            com.synapse.localai.memory.VectorMemoryManager.formatRelevantContextForPrompt(userPrompt)
        } else ""
        val vectorMemoriesSection = if (recalledVectorMemories.isNotBlank()) "\n\n$recalledVectorMemories" else ""

        val currentKey = "PERSONA:${effectivePersona.hashCode()}|$customPrompt|$isCodeRequest|$isGreeting|${detectedLang.name}|${userPrompt.hashCode()}"
        if (cachedPrefixKey == currentKey && cachedStaticPrefix != null) return cachedStaticPrefix!!

        val livingMind = actualContext?.let { EpistemicMemoryManager.getDistilledContextPrompt(it) } ?: ""
        val toolsAndGuidelines = """
## Host Toolchains & Execution Guidelines:
- Available tools: open_app(package_name), run_shell(command), write_file(path, content), web_search(query), run_python(script), fetch_web_content(url), read_file_range(path, start_line, line_count), schedule_task(description, command, cron_expression, delay_minutes), queue_background_task(command), list_tasks(), resume_task(task_id, user_input), run_plugin(name), save_memory(content, category), search_memory(query), complete(summary).
- Tool invocation format:
<function_calls>
<invoke name="tool_name"><parameter name="param_name">value</parameter></invoke>
</function_calls>
- Autonomy: Continue multi-step tasks through planning, tool execution, verification, and a final summary. Ask the user only when a required fact, choice, or authorization cannot be safely inferred; state exactly what is needed and do not claim completion while blocked.
- Zero Hallucination: Never claim an action was done without invoking the tool.
- Conclude with verified facts directly in natural language without echoing tools or system directives.
""".trimIndent()

        val customPromptSection = if (customPrompt.isNotBlank()) "\n\n$customPrompt" else ""
        val compiled = "$languageEnforcement\n\n$livingMind\n\n$effectivePersona$vectorMemoriesSection\n\n$toolsAndGuidelines$customPromptSection$antiMonologueDirective$greetingDirective$universalGroundingDirective\n\n$languageEnforcement"
        cachedPrefixKey = currentKey
        cachedStaticPrefix = compiled
        return compiled
    }

    fun sendCommand(command: String, fileUri: Uri? = null, context: Context? = null) {
        if (command.isBlank() && fileUri == null) return
        val actualContext = context ?: MainActivity.instance
        val trimmed = command.trim()

        if (trimmed.startsWith("$")) {
            val shellCmd = trimmed.removePrefix("$").trim()
            autoTitleSessionIfNew(command)
            val userLog = LogEntry(sender = "USER", text = command)
            addLog(userLog)
            val sid = currentSessionId.value
            viewModelScope.launch(Dispatchers.IO) {
                chatDb?.insertMessage(ChatMessageEntity(id = userLog.id, sessionId = sid, sender = "USER", text = userLog.text, timestamp = userLog.timestamp))
                val (exitCode, output) = runShellWithExitCode(shellCmd)
                val safeCmd = StreamDemultiplexer.escapeXml(shellCmd)
                val safeOut = StreamDemultiplexer.escapeXml(output.trim())
                val terminalFrameText = """<terminal_frame command="$safeCmd" exit_code="$exitCode">$safeOut</terminal_frame>"""
                val agentLog = LogEntry(sender = "AGENT", text = terminalFrameText)
                addLog(agentLog)
                chatDb?.insertMessage(ChatMessageEntity(id = agentLog.id, sessionId = sid, sender = "AGENT", text = terminalFrameText, timestamp = agentLog.timestamp))
                refreshSessions()
            }
            return
        }

        if (trimmed.startsWith("/") && !trimmed.startsWith("/image", ignoreCase = true) && MediaIntentClassifier.classify(trimmed) is MediaIntent.TextDialogue) {
            val slashCmd = trimmed.substringBefore(" ").lowercase()
            autoTitleSessionIfNew(command)
            val userLog = LogEntry(sender = "USER", text = command)
            addLog(userLog)
            val sid = currentSessionId.value
            viewModelScope.launch(Dispatchers.IO) {
                chatDb?.insertMessage(ChatMessageEntity(id = userLog.id, sessionId = sid, sender = "USER", text = userLog.text, timestamp = userLog.timestamp))
                refreshSessions()
            }
            when (slashCmd) {
                "/clear" -> actualContext?.let { clearConversation(it) }
                "/preview" -> openLiveCanvas()
                else -> addLog(LogEntry(sender = "SYSTEM", text = "Available commands: /clear, /preview"))
            }
            return
        }

        if (_uiState.value.isProcessing) {
            synchronized(pendingCommands) { pendingCommands.add(command) }
            addLog(LogEntry(sender = "SYSTEM", text = "Command queued (${pendingCommands.size} in queue)"))
            return
        }

        autoTitleSessionIfNew(command)
        val userLog = LogEntry(sender = "USER", text = command.takeIf { it.isNotBlank() } ?: "[Attached File]")
        addLog(userLog)
        val sid = currentSessionId.value

        viewModelScope.launch(Dispatchers.IO) {
            chatDb?.insertMessage(ChatMessageEntity(id = userLog.id, sessionId = sid, sender = "USER", text = userLog.text, timestamp = userLog.timestamp))
            refreshSessions()
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
                actualContext?.let { ModelEngineService.start(it, "ATLAS-AI: ${finalCommand.take(40)}") }
                val imageBase64List = mutableListOf<String>()

                val modelType = _uiState.value.activeModelType
                if (modelType == null || !isModelLoaded()) {
                    addLog(LogEntry(sender = "AGENT", text = "No model active. Please configure Gemini API keys or load a local GGUF model.", isError = false))
                    return@launch
                }

                synchronized(conversationHistory) { conversationHistory.add("user" to finalCommand) }

                val mediaIntent = if (finalCommand.trim().startsWith("/image", ignoreCase = true)) {
                    val cleanPrompt = finalCommand.trim().removePrefix("/image").trim()
                    MediaIntent.GenerateImage(prompt = cleanPrompt, rawInput = finalCommand, isArabic = isArabic(cleanPrompt))
                } else {
                    MediaIntentClassifier.classify(finalCommand)
                }

                if (mediaIntent !is MediaIntent.TextDialogue) {
                    when (mediaIntent) {
                        is MediaIntent.GenerateImage -> {
                            val rawImagePrompt = mediaIntent.prompt
                            val liveLogId = UUID.randomUUID().toString()
                            val initialLiveLog = LogEntry(id = liveLogId, sender = "AGENT", text = "🎨 Synthesizing visual...", isThinking = true)
                            _uiState.update { it.copy(logs = it.logs + initialLiveLog, currentPhase = AgentActionPhase.EXECUTING_TOOL) }
                            
                            val isCloud = modelType == ModelType.GEMINI && _uiState.value.config.geminiApiKeys.isNotBlank()
                            val result = if (isCloud) imageRepository.generateImage(rawImagePrompt) else executeSequentialImageSynthesis(rawImagePrompt)
                            
                            when (result) {
                                is ImageResult.Success -> {
                                    val savedFile = saveImageToDisk(result.imageBytes, rawImagePrompt)
                                    val pathNote = if (savedFile != null) "\n📁 Saved to: ${savedFile.absolutePath}" else ""
                                    val successText = "✔ Visual Generated Successfully for: \"$rawImagePrompt\"$pathNote"
                                    _uiState.update { state ->
                                        val updatedLogs = state.logs.toMutableList()
                                        val idx = updatedLogs.indexOfLast { it.id == liveLogId }
                                        val entry = LogEntry(id = liveLogId, sender = "AGENT", text = successText, isThinking = false, imageBytes = result.imageBytes)
                                        if (idx >= 0) updatedLogs[idx] = entry else updatedLogs.add(entry)
                                        state.copy(logs = updatedLogs, isProcessing = false, currentPhase = AgentActionPhase.COMPLETING)
                                    }
                                }
                                is ImageResult.Error -> {
                                    _uiState.update { state ->
                                        val updatedLogs = state.logs.toMutableList()
                                        val idx = updatedLogs.indexOfLast { it.id == liveLogId }
                                        val entry = LogEntry(id = liveLogId, sender = "AGENT", text = "✖ Image Generation Failed: ${result.message}", isError = true)
                                        if (idx >= 0) updatedLogs[idx] = entry else updatedLogs.add(entry)
                                        state.copy(logs = updatedLogs, isProcessing = false, currentPhase = AgentActionPhase.COMPLETING)
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                    refreshSessions()
                    return@launch
                }

                val dynamicPersona = getEffectivePersonaPrompt(actualContext)
                val isGreetingPrompt = isGreeting(finalCommand)
                val isCode = !isGreetingPrompt && isCodeRequest(finalCommand)
                val systemPrompt = buildSystemPrompt(actualContext, modelType == ModelType.GGUF, dynamicPersona, isCode, isGreeting = isGreetingPrompt, userPrompt = finalCommand)

                var keepGoing = true
                var iterationCount = 0
                var toolsUsedThisTurn = false
                var lastActionUsed: String? = null
                var toolCallCount = 0
                var forceFinalTurn = false
                var activeGgufPromptPrefix: String? = null

                while (keepGoing && iterationCount < MAX_TASK_ITERATIONS) {
                    iterationCount++
                    if (iterationCount > 1) {
                        activeGgufPromptPrefix = null
                    }
                    val responseSb = StringBuilder()
                    val liveLogId = UUID.randomUUID().toString()
                    var streamInterrupted = false
                    var interruptMsg = ""

                    val liveDiskStreamer = ToolExecutor.LiveFileStreamer(
                        baseDir = ToolExecutor.BASE_DIR,
                        onStatusUpdate = { status -> _uiState.update { it.copy(activeStatusDetail = status, currentPhase = AgentActionPhase.GENERATING_CODE) } }
                    )

                    val isSecondTurnAnalysis = toolsUsedThisTurn && iterationCount > 1
                    val initialPhase = if (isSecondTurnAnalysis) AgentActionPhase.ANALYZING_OUTPUT else if (isCode) AgentActionPhase.GENERATING_CODE else AgentActionPhase.COMPLETING
                    val initialDetail = if (isSecondTurnAnalysis) "Analyzing search & tool results..." else if (isCode) "Drafting Code..." else "Replying..."

                    val initialLiveLog = LogEntry(id = liveLogId, sender = "AGENT", text = "", thinkingText = "", isThinking = false)
                    _uiState.update { it.copy(logs = it.logs + initialLiveLog, currentPhase = initialPhase, activeStatusDetail = initialDetail) }

                    var continuationCycle = 1
                    var continueRecursion = true
                    val continuationPromptPayload = "Continue generation directly from the last emitted token."

                    val historyCopy = synchronized(conversationHistory) {
                        trimConversationHistoryToContextLimit()
                        val historyList = conversationHistory.takeLast(MAX_HISTORY_MESSAGES).toList()
                        historyList.mapIndexed { idx, (role, content) ->
                            val isOlder = idx < historyList.size - 2
                            val safeContent = if (isOlder && role == "assistant") compactHistoryForQuota(content) else content
                            role to safeContent
                        }.filter { it.second.trim().isNotBlank() }
                    }

                    try {
                        var lastUiUpdateTime = 0L

                        val pushUiUpdate = { rawText: String ->
                            val (fastThinkingText, fastIsThinking) = StreamDemultiplexer.extractStreamThinkingFast(rawText)
                            liveDiskStreamer.onChunk(rawText)

                            _uiState.update { state ->
                                val updatedLogs = state.logs.toMutableList()
                                val lastIdx = updatedLogs.indexOfLast { it.id == liveLogId }
                                if (lastIdx >= 0) {
                                    updatedLogs[lastIdx] = updatedLogs[lastIdx].copy(
                                        text = cleanVisibleText(rawText),
                                        thinkingText = fastThinkingText,
                                        isThinking = fastIsThinking
                                    )
                                }
                                state.copy(logs = updatedLogs)
                            }
                            scrollSignal.tryEmit(Unit)
                        }

                        while (continueRecursion && !streamInterrupted) {
                            val stream = when (modelType) {
                                ModelType.GGUF -> {
                                    val activeArch = ggufClient?.currentMetadata?.architecture ?: ChatTemplateEngine.Architecture.GEMMA
                                    val ggufPrompt = if (continuationCycle == 1) {
                                        activeGgufPromptPrefix ?: run {
                                            val p = ChatTemplateEngine.formatPrompt(historyCopy, systemPrompt, activeArch, addAssistantPrompt = true)
                                            activeGgufPromptPrefix = p
                                            p
                                        }
                                    } else {
                                        (activeGgufPromptPrefix ?: "") + responseSb.toString() + ChatTemplateEngine.formatContinuationTurn(activeArch, continuationPromptPayload)
                                    }
                                    val turnTemp = if (isCode) 0.15f else if (isSecondTurnAnalysis) 0.20f else null
                                    ggufClient!!.generateStreamWithRawPrompt(ggufPrompt, overrideTemperature = turnTemp) { tps ->
                                        _uiState.update { it.copy(tokensPerSecond = tps) }
                                    }
                                }
                                ModelType.GEMINI -> {
                                    val json = JSONArray()
                                    var lastRole = ""
                                    val turnDetectedLang = detectLanguage(finalCommand)
                                    val langInstructionSuffix = when (turnDetectedLang) {
                                        DetectedLanguage.ENGLISH -> "\n\n[System Directive: User query is in English. Reply strictly 100% in English only. Never output Arabic.]"
                                        DetectedLanguage.ARABIC -> "\n\n[توجيه النظام: رسالة المستخدم بالعربية. أجب باللغة العربية حصراً.]"
                                        DetectedLanguage.FRENCH -> "\n\n[Directive Système: Message en français. Répondez strictement en français.]"
                                        DetectedLanguage.SPANISH -> "\n\n[Directiva del Sistema: Mensaje en español. Responda estrictamente en español.]"
                                    }
                                    historyCopy.forEachIndexed { idx, (role, content) ->
                                        val geminiRole = when (role.lowercase()) {
                                            "assistant", "model" -> "model"
                                            else -> "user"
                                        }
                                        val finalTurnContent = if (idx == historyCopy.size - 1 && geminiRole == "user") {
                                            content + langInstructionSuffix
                                        } else {
                                            content
                                        }
                                        if (geminiRole == lastRole && json.length() > 0) {
                                            val prev = json.getJSONObject(json.length() - 1)
                                            prev.put("content", prev.getString("content") + "\n\n" + finalTurnContent)
                                        } else {
                                            val m = JSONObject().put("role", geminiRole).put("content", finalTurnContent)
                                            if (idx == historyCopy.size - 1 && imageBase64List.isNotEmpty() && continuationCycle == 1) {
                                                m.put("images", JSONArray(imageBase64List))
                                            }
                                            json.put(m)
                                            lastRole = geminiRole
                                        }
                                    }
                                    if (continuationCycle > 1) {
                                        json.put(JSONObject().put("role", "model").put("content", responseSb.toString()))
                                        json.put(JSONObject().put("role", "user").put("content", continuationPromptPayload))
                                    }
                                    if (json.length() > 0 && json.getJSONObject(json.length() - 1).getString("role") == "model") {
                                        val finalTurnPrompt = when (turnDetectedLang) {
                                            DetectedLanguage.ENGLISH -> "Deliver your verified final response now strictly in English only. Do NOT use Arabic."
                                            DetectedLanguage.ARABIC -> "قدم إجابتك وتحليلك النهائي الآن باللغة العربية."
                                            DetectedLanguage.FRENCH -> "Présentez votre réponse finale vérifiée en français maintenant."
                                            DetectedLanguage.SPANISH -> "Entregue su respuesta final verificada en español ahora."
                                        }
                                        json.put(JSONObject().put("role", "user").put("content", finalTurnPrompt))
                                    }

                                    geminiClient!!.generateStreamWithOllamaMessages(json, systemPrompt) {
                                        if (continuationCycle == 1) {
                                            responseSb.setLength(0)
                                            liveDiskStreamer.reset()
                                            pushUiUpdate("")
                                        }
                                    }
                                }
                            }

                            try {
                                // Decoupled Consumer: Micro-batches stream over 66ms (10Hz-15Hz) windows
                                stream.microBatch(windowMs = 66L).collect { chunk ->
                                    responseSb.append(chunk)

                                    val now = System.currentTimeMillis()
                                    if (now - lastUiUpdateTime >= 66L) {
                                        lastUiUpdateTime = now
                                        pushUiUpdate(responseSb.toString())
                                    }

                                    // Fast-path: only check tool call barrier when chunk contains closing delimiter '>', '}', or '`'
                                    if (modelType == ModelType.GGUF && !forceFinalTurn &&
                                        (chunk.contains('>') || chunk.contains('}') || chunk.contains('`') || chunk.contains(')')) &&
                                        StreamDemultiplexer.hasCompletedToolCall(responseSb.takeLast(350).toString())) {
                                        throw SynchronousToolBarrierException()
                                    }
                                }
                            } catch (e: Throwable) {
                                if (e is SynchronousToolBarrierException || e.cause is SynchronousToolBarrierException || e.message?.contains("Synchronous ReAct Barrier") == true) {
                                    continueRecursion = false
                                } else throw e
                            }

                            if (!forceFinalTurn && StreamDemultiplexer.hasCompletedToolCall(responseSb.takeLast(350).toString())) {
                                val truncated = StreamDemultiplexer.truncateAfterToolCall(responseSb.toString())
                                responseSb.setLength(0)
                                responseSb.append(truncated)
                                continueRecursion = false
                            }

                            pushUiUpdate(responseSb.toString())
                            continueRecursion = false
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        if (e is SynchronousToolBarrierException || e.message?.contains("Synchronous ReAct Barrier") == true) {
                            continueRecursion = false
                        } else {
                            streamInterrupted = true
                            throw e
                        }
                    } catch (e: Exception) {
                        val lowerErr = (e.message ?: "").lowercase()
                        val isTokenCeiling = lowerErr.contains("max_tokens") || lowerErr.contains("token limit")
                        val isCancellation = lowerErr.contains("cancel") || lowerErr.contains("closed")
                        val hasToolCall = StreamDemultiplexer.hasCompletedToolCall(responseSb.takeLast(350).toString())

                        if (isTokenCeiling) {
                            Log.i(TAG, "Suppressed single-turn token error banner: ${e.message}")
                        } else if (isCancellation && hasToolCall) {
                            Log.i(TAG, "Tool call completion intercepted cleanly.")
                        } else {
                            streamInterrupted = true
                            interruptMsg = e.message ?: e.javaClass.simpleName
                            _uiState.update { it.copy(streamInterruptMsg = interruptMsg) }
                        }
                    }

                    val fullResponse = responseSb.toString()
                    val demuxedFull = StreamDemultiplexer.demux(fullResponse)
                    val writtenFilesFromStream = liveDiskStreamer.closeAll(fullResponse)

                    val parsedToolCalls = StreamDemultiplexer.parseAllToolCalls(fullResponse)
                    val validToolCalls = parsedToolCalls.filter { call ->
                        when (call.name.trim().lowercase()) {
                            "web_search", "search", "google_search" -> {
                                val q = call.params["query"] ?: call.params["q"] ?: call.params.values.firstOrNull() ?: ""
                                q.trim().isNotBlank()
                            }
                            "run_shell", "execute_shell", "terminal", "bash", "sh" -> {
                                val c = call.params["command"] ?: call.params["cmd"] ?: call.params.values.firstOrNull() ?: ""
                                c.trim().isNotBlank()
                            }
                            "run_python", "python", "py_eval" -> {
                                val s = call.params["script"] ?: call.params["code"] ?: call.params.values.firstOrNull() ?: ""
                                s.trim().isNotBlank()
                            }
                            "fetch_web_content", "fetch_url", "read_url" -> {
                                val u = call.params["url"] ?: call.params.values.firstOrNull() ?: ""
                                u.trim().isNotBlank()
                            }
                            "read_file_range", "read_lines" -> {
                                val p = call.params["path"] ?: call.params["file"] ?: ""
                                p.trim().isNotBlank()
                            }
                            else -> true
                        }
                    }

                    if (fullResponse.isNotBlank()) {
                        if (streamInterrupted && validToolCalls.isEmpty()) {
                            val assistantReply = if (writtenFilesFromStream.isNotEmpty()) ToolExecutor.sanitizePostExecutionSummary(fullResponse, writtenFilesFromStream) else fullResponse
                            val cleanHistoryReply = stripThinkingForHistory(assistantReply)
                            val displayText = if (cleanHistoryReply.isNotBlank()) cleanHistoryReply else assistantReply.ifBlank { "[Stream Interrupted: $interruptMsg]" }
                            val cleanDisplay = cleanVisibleText(displayText)
                            _uiState.update { state ->
                                val updated = state.logs.map { log -> if (log.id == liveLogId) log.copy(text = cleanDisplay, thinkingText = demuxedFull.thinkingText, isThinking = false) else log }
                                state.copy(logs = updated)
                            }
                            if (cleanDisplay.isNotBlank()) {
                                synchronized(conversationHistory) {
                                    conversationHistory.add("model" to cleanDisplay)
                                    trimConversationHistoryToContextLimit()
                                }
                            }
                            keepGoing = false
                        } else if (validToolCalls.isNotEmpty() && !forceFinalTurn) {
                            toolsUsedThisTurn = true
                            val primaryCall = validToolCalls.first()
                            val action = primaryCall.name.trim().lowercase()
                            lastActionUsed = action
                            val params = primaryCall.params
                            val rawExplanatoryText = StreamDemultiplexer.extractPreambleProse(fullResponse, primaryCall.rawMatch)
                            toolCallCount++

                            val detectedLang = detectLanguage(command)

                            val hasArabicInPreamble = rawExplanatoryText.any { it in '\u0600'..'\u06FF' }
                            val isPreambleValidForLanguage = when (detectedLang) {
                                DetectedLanguage.ENGLISH -> !hasArabicInPreamble
                                DetectedLanguage.ARABIC -> hasArabicInPreamble
                                else -> !hasArabicInPreamble
                            }

                            val explanatoryText = if (rawExplanatoryText.isNotBlank() && isPreambleValidForLanguage) {
                                cleanVisibleText(rawExplanatoryText)
                            } else {
                                when (detectedLang) {
                                    DetectedLanguage.ARABIC -> when (action) {
                                        "run_shell", "execute_shell", "terminal", "bash", "sh" -> "سأقوم بتنفيذ الأمر وفحص النظام الآن:"
                                        "web_search", "search", "google_search" -> "سأقوم بالبحث والتحقق من المصادر الآن:"
                                        "click", "click_node", "tap" -> "سأقوم بالنقر والتفاعل مع العنصر المحدد الآن:"
                                        "swipe", "scroll" -> "سأقوم بتنفيذ حركة التمرير على الشاشة الآن:"
                                        "input_text", "type_text" -> "سأقوم بكتابة النص في الحقل المحدد الآن:"
                                        "capture_screen", "screenshot" -> "سأقوم بالتقاط لقطة للشاشة الآن:"
                                        "ocr_screen", "screen_vision" -> "سأقوم بتحليل عناصر الشاشة واستخراج النصوص الآن:"
                                        else -> "جاري تنفيذ العملية المطلوبة:"
                                    }
                                    DetectedLanguage.FRENCH -> when (action) {
                                        "run_shell", "execute_shell", "terminal", "bash", "sh" -> "Exécution de la commande système en cours :"
                                        "web_search", "search", "google_search" -> "Recherche d'informations vérifiées en cours :"
                                        "click", "click_node", "tap" -> "Interaction avec l'élément sélectionné en cours :"
                                        "swipe", "scroll" -> "Exécution du geste sur l'écran en cours :"
                                        "input_text", "type_text" -> "Saisie du texte dans le champ cible en cours :"
                                        "capture_screen", "screenshot" -> "Capture de l'écran en cours :"
                                        "ocr_screen", "screen_vision" -> "Analyse visuelle de l'écran en cours :"
                                        else -> "Exécution de l'opération demandée :"
                                    }
                                    DetectedLanguage.SPANISH -> when (action) {
                                        "run_shell", "execute_shell", "terminal", "bash", "sh" -> "Ejecutando comando de diagnóstico del sistema ahora:"
                                        "web_search", "search", "google_search" -> "Buscando información verificada ahora:"
                                        "click", "click_node", "tap" -> "Interactuando con el elemento en pantalla ahora:"
                                        "swipe", "scroll" -> "Realizando gesto en pantalla ahora:"
                                        "input_text", "type_text" -> "Ingresando texto en el campo objetivo ahora:"
                                        "capture_screen", "screenshot" -> "Capturando pantalla ahora:"
                                        "ocr_screen", "screen_vision" -> "Analizando elementos visuales de la pantalla ahora:"
                                        else -> "Ejecutando la operación solicitada:"
                                    }
                                    DetectedLanguage.ENGLISH -> when (action) {
                                        "run_shell", "execute_shell", "terminal", "bash", "sh" -> "Executing system diagnostic command now:"
                                        "web_search", "search", "google_search" -> "Searching for verified information now:"
                                        "click", "click_node", "tap" -> "Interacting with target screen element now:"
                                        "swipe", "scroll" -> "Performing gesture on screen now:"
                                        "input_text", "type_text" -> "Injecting text into target field now:"
                                        "capture_screen", "screenshot" -> "Capturing screen buffer now:"
                                        "ocr_screen", "screen_vision" -> "Analyzing screen visuals and text elements now:"
                                        else -> "Executing requested operation:"
                                    }
                                }
                            }

                            val langFinalInstruction = when (detectedLang) {
                                DetectedLanguage.ENGLISH -> "Deliver your final, comprehensive, and wise analysis directly to the user strictly in ENGLISH ONLY. Under no circumstances use Arabic words or letters."
                                DetectedLanguage.ARABIC -> "قدم تحليلك النهائي والشامل والموثوق مباشرة للمستخدم باللغة العربية حصراً."
                                DetectedLanguage.FRENCH -> "Présentez votre analyse finale et complète directement à l'utilisateur strictement en FRANÇAIS."
                                DetectedLanguage.SPANISH -> "Entregue su análisis final y completo directamente al usuario estrictamente en ESPAÑOL."
                            }

                            when (action) {
                                "run_shell", "execute_shell", "terminal", "bash", "sh" -> {
                                    val rawCmd = params["command"] ?: params["cmd"] ?: params.values.firstOrNull() ?: ""
                                    _uiState.update { it.copy(currentPhase = AgentActionPhase.EXECUTING_TOOL, activeStatusDetail = "Executing: $rawCmd...") }
                                    val heredoc = interceptHeredocFileWrite(rawCmd)
                                    val (finalCmd, exitCode, output) = if (heredoc != null) heredoc else {
                                        val (code, out) = executeSafeProcess(rawCmd)
                                        Triple(rawCmd, code, out)
                                    }
                                    val safeCmd = StreamDemultiplexer.escapeXml(finalCmd)
                                    val safeOut = StreamDemultiplexer.escapeXml(output.trim())
                                    val terminalFrame = """<terminal_frame command="$safeCmd" exit_code="$exitCode">$safeOut</terminal_frame>"""
                                    val lockedSegmentText = if (explanatoryText.isNotBlank()) "$explanatoryText\n\n$terminalFrame" else terminalFrame

                                    _uiState.update { state ->
                                        val updated = state.logs.map { log -> if (log.id == liveLogId) log.copy(text = lockedSegmentText, thinkingText = demuxedFull.thinkingText, isThinking = false) else log }
                                        state.copy(logs = updated, activeStatusDetail = "Analyzing output metrics...", currentPhase = AgentActionPhase.ANALYZING_OUTPUT)
                                    }
                                    val sid = currentSessionId.value
                                    val dbText = if (demuxedFull.thinkingText.isNotBlank()) "<think>${demuxedFull.thinkingText}</think>\n$lockedSegmentText" else lockedSegmentText
                                    viewModelScope.launch(Dispatchers.IO) {
                                        chatDb?.insertMessage(ChatMessageEntity(id = liveLogId, sessionId = sid, sender = "AGENT", text = dbText, timestamp = System.currentTimeMillis()))
                                        refreshSessions()
                                    }

                                    val observationMsg = """[Command Output (Exit code $exitCode)]:
$output

MANDATORY PROTOCOL:
- If further diagnostic commands, file inspections, or operational actions are required to finish the objective completely, emit the next tool call now.
- Do NOT close the session prematurely. Never say "I will do this" without running the tool.
- Once all necessary actions are finished, deliver your structured analysis and final conclusion directly to the user.
$langFinalInstruction"""

                                    synchronized(conversationHistory) {
                                        val assistantTurn = if (explanatoryText.isNotBlank()) "$explanatoryText\n${primaryCall.rawMatch}" else primaryCall.rawMatch
                                        conversationHistory.add("model" to assistantTurn)
                                        conversationHistory.add("user" to observationMsg)
                                        trimConversationHistoryToContextLimit()
                                    }
                                }
                                "web_search", "search", "google_search" -> {
                                    val searchQuery = params["query"] ?: params["q"] ?: params.values.firstOrNull() ?: ""
                                    _uiState.update { it.copy(activeStatusDetail = "Searching: $searchQuery...", currentPhase = AgentActionPhase.EXECUTING_TOOL) }
                                    val searchData = try { WebSearchTool.search(searchQuery) } catch (e: Exception) { "Error executing web search: ${e.message}" }
                                    val searchSummary = "🔍 Web Search: $searchQuery"
                                    val lockedSegmentText = if (explanatoryText.isNotBlank()) "$explanatoryText\n\n$searchSummary" else searchSummary

                                    _uiState.update { state ->
                                        val updated = state.logs.map { log -> if (log.id == liveLogId) log.copy(text = lockedSegmentText, thinkingText = demuxedFull.thinkingText, isThinking = false) else log }
                                        state.copy(logs = updated, activeStatusDetail = "Synthesizing answer from search results...", currentPhase = AgentActionPhase.ANALYZING_OUTPUT)
                                    }
                                    val sid = currentSessionId.value
                                    val dbText = if (demuxedFull.thinkingText.isNotBlank()) "<think>${demuxedFull.thinkingText}</think>\n$lockedSegmentText" else lockedSegmentText
                                    viewModelScope.launch(Dispatchers.IO) {
                                        chatDb?.insertMessage(ChatMessageEntity(id = liveLogId, sessionId = sid, sender = "AGENT", text = dbText, timestamp = System.currentTimeMillis()))
                                        refreshSessions()
                                    }

                                    val truncatedSearch = truncateSearchObservation(searchData, maxChars = 1500, maxLines = 25)
                                    val observationMsg = """[Web Search Results for "$searchQuery"]:
$truncatedSearch

MANDATORY PROTOCOL:
- If you need to fetch full content from a URL via fetch_web_content or run further checks, emit the tool call now.
- Otherwise, deliver your verified final answer directly to the user.
$langFinalInstruction"""

                                    synchronized(conversationHistory) {
                                        val assistantTurn = if (explanatoryText.isNotBlank()) "$explanatoryText\n${primaryCall.rawMatch}" else primaryCall.rawMatch
                                        conversationHistory.add("model" to assistantTurn)
                                        conversationHistory.add("user" to observationMsg)
                                        trimConversationHistoryToContextLimit()
                                    }
                                }
                                else -> {
                                    val (usedTools, summary) = executeToolLoop(fullResponse, actualContext)
                                    val cleanSummary = summary ?: ""
                                    val isUiInspection = action in setOf(
                                        "ocr_screen", "screen_vision", "capture_screen"
                                    )
                                    val userFacingSummary = if (isUiInspection) {
                                        when (detectedLang) {
                                            DetectedLanguage.ARABIC -> "🔍 تم فحص وتحليل عناصر واجهة الشاشة بنجاح."
                                            DetectedLanguage.FRENCH -> "🔍 Éléments de l'interface analysés en arrière-plan."
                                            DetectedLanguage.SPANISH -> "🔍 Elementos de la interfaz analizados en segundo plano."
                                            DetectedLanguage.ENGLISH -> "🔍 Active screen UI elements inspected silently."
                                        }
                                    } else {
                                        cleanSummary
                                    }
                                    val lockedSegmentText = when {
                                        explanatoryText.isNotBlank() && userFacingSummary.isNotBlank() -> "$explanatoryText\n\n$userFacingSummary"
                                        explanatoryText.isNotBlank() -> explanatoryText
                                        userFacingSummary.isNotBlank() -> userFacingSummary
                                        else -> ""
                                    }
                                    _uiState.update { state ->
                                        val updated = state.logs.map { log -> if (log.id == liveLogId) log.copy(text = lockedSegmentText, thinkingText = demuxedFull.thinkingText, isThinking = false) else log }
                                        state.copy(logs = updated, activeStatusDetail = "Analyzing tool observation...", currentPhase = AgentActionPhase.ANALYZING_OUTPUT)
                                    }
                                    val sid = currentSessionId.value
                                    val dbText = if (demuxedFull.thinkingText.isNotBlank()) "<think>${demuxedFull.thinkingText}</think>\n$lockedSegmentText" else lockedSegmentText
                                    viewModelScope.launch(Dispatchers.IO) {
                                        chatDb?.insertMessage(ChatMessageEntity(id = liveLogId, sessionId = sid, sender = "AGENT", text = dbText, timestamp = System.currentTimeMillis()))
                                        refreshSessions()
                                    }

                                    val isUiTool = action in setOf(
                                        "open_app", "launch_app", "click", "click_node", "tap",
                                        "swipe", "scroll", "input_text", "type_text",
                                        "capture_screen", "ocr_screen", "screen_vision"
                                    )
                                    val truncatedSummary = sanitizeProcessOutput(cleanSummary, maxChars = 1200, maxLines = 25)
                                    val observationMsg = if (isUiTool) {
                                        """[UI Automation Action Observation (SILENT - PARSE IN THOUGHT PROCESS ONLY)]:
$truncatedSummary

CRITICAL GUARDRAIL: Analyze and parse this UI observation SILENTLY in your thought process.
- DO NOT leak, repeat, or output raw XML, node dumps, bounds, or hierarchy trees into your visible response.
- If more steps are needed, call the next tool.
- Your final reply must be strictly a concise, natural language summary in the user's language without any raw data logs or XML tags.
$langFinalInstruction"""
                                    } else {
                                        """[Tool Execution Findings]:
$truncatedSummary

MANDATORY INSTRUCTION: Tool execution is finished. Do NOT call any further tools.
$langFinalInstruction"""
                                    }

                                    synchronized(conversationHistory) {
                                        val assistantTurn = if (explanatoryText.isNotBlank()) "$explanatoryText\n${primaryCall.rawMatch}" else primaryCall.rawMatch
                                        conversationHistory.add("model" to assistantTurn)
                                        conversationHistory.add("user" to observationMsg)
                                        trimConversationHistoryToContextLimit()
                                    }
                                }
                            }

                            forceFinalTurn = toolCallCount >= MAX_TOOL_CALLS_PER_TURN
                            activeGgufPromptPrefix = null
                            keepGoing = true
                        } else if (
                            fullResponse.isNotBlank() &&
                            (if (modelType == ModelType.GGUF) {
                                ggufClient?.lastFinishReason == "MAX_TOKENS"
                            } else {
                                geminiClient?.lastFinishReason == "MAX_TOKENS"
                            })
                        ) {
                            val partialReply = cleanVisibleText(stripThinkingForHistory(fullResponse))
                            _uiState.update { state ->
                                val updated = state.logs.map { log ->
                                    if (log.id == liveLogId) {
                                        log.copy(text = partialReply, thinkingText = demuxedFull.thinkingText, isThinking = false)
                                    } else log
                                }
                                state.copy(
                                    logs = updated,
                                    currentPhase = AgentActionPhase.ANALYZING_OUTPUT,
                                    activeStatusDetail = "Continuing response after model output limit..."
                                )
                            }
                            val continuationInstruction = when (detectLanguage(command)) {
                                DetectedLanguage.ARABIC ->
                                    "[متابعة تلقائية] انتهى حد التوليد لهذه الدفعة. تابع مباشرةً من آخر نقطة دون تكرار ما سبق. حافظ على السياق والخطة ونفّذ ما تبقى. إذا كان إكمال المهمة يتطلب معلومة لا يمكن استنتاجها بأمان، اسأل المستخدم عن تلك المعلومة المحددة فقط ولا تدّعِ إكمال المهمة."
                                DetectedLanguage.FRENCH ->
                                    "[Continuation automatique] La limite de sortie de ce lot est atteinte. Reprenez exactement où vous vous êtes arrêté, sans répétition, et poursuivez la tâche. Si une information indispensable ne peut pas être déduite sans risque, demandez uniquement cette précision et ne prétendez pas avoir terminé."
                                DetectedLanguage.SPANISH ->
                                    "[Continuación automática] Se alcanzó el límite de salida de este bloque. Continúa exactamente desde donde te detuviste, sin repetir, y completa la tarea. Si falta un dato imprescindible que no se pueda inferir con seguridad, pregunta únicamente por ese dato y no afirmes que terminaste."
                                DetectedLanguage.ENGLISH ->
                                    "[Automatic continuation] This output chunk reached its model limit. Continue directly from where you stopped without repeating prior text, and finish the task. If essential information cannot be safely inferred, ask the user only for that specific information and do not claim the task is complete."
                            }
                            synchronized(conversationHistory) {
                                conversationHistory.add("model" to compactContinuationContext(partialReply))
                                conversationHistory.add("user" to continuationInstruction)
                                trimConversationHistoryToContextLimit()
                            }
                            val sid = currentSessionId.value
                            viewModelScope.launch(Dispatchers.IO) {
                                chatDb?.insertMessage(
                                    ChatMessageEntity(
                                        id = liveLogId,
                                        sessionId = sid,
                                        sender = "AGENT",
                                        text = partialReply,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                            }
                            keepGoing = true
                            activeGgufPromptPrefix = null
                        } else {
                            _uiState.update { it.copy(currentPhase = AgentActionPhase.COMPLETING) }
                            val assistantReply = if (writtenFilesFromStream.isNotEmpty()) ToolExecutor.sanitizePostExecutionSummary(fullResponse, writtenFilesFromStream) else fullResponse
                            val cleanHistoryReply = stripThinkingForHistory(assistantReply)
                            val cleanDisplay = cleanVisibleText(cleanHistoryReply)
                            _uiState.update { state ->
                                val updated = state.logs.map { log -> if (log.id == liveLogId) log.copy(text = cleanDisplay, thinkingText = demuxedFull.thinkingText, isThinking = false) else log }
                                state.copy(logs = updated)
                            }
                            val sid = currentSessionId.value
                            val dbText = if (demuxedFull.thinkingText.isNotBlank()) "<think>${demuxedFull.thinkingText}</think>\n$cleanDisplay" else cleanDisplay
                            viewModelScope.launch(Dispatchers.IO) {
                                chatDb?.insertMessage(ChatMessageEntity(id = liveLogId, sessionId = sid, sender = "AGENT", text = dbText, timestamp = System.currentTimeMillis()))
                                refreshSessions()

                                val detectedPath = ToolExecutor.detectFilePath(fullResponse)
                                EpistemicMemoryManager.recordInteraction(actualContext, command, fullResponse, detectedPath)
                            }
                            if (cleanDisplay.isNotBlank()) {
                                synchronized(conversationHistory) {
                                    conversationHistory.add("model" to cleanDisplay)
                                    trimConversationHistoryToContextLimit()
                                }
                            }
                            keepGoing = false
                            activeGgufPromptPrefix = null
                        }
                    } else {
                        val detectedLang = detectLanguage(command)
                        val errorText = when {
                            streamInterrupted && interruptMsg.isNotBlank() -> "⚠️ $interruptMsg"
                            streamInterrupted -> when (detectedLang) {
                                DetectedLanguage.ARABIC -> "⚠️ انقطع الاتصال بالنموذج قبل الرد. يرجى التحقق من اسم النموذج والمفاتيح والمحاولة مجدداً."
                                DetectedLanguage.FRENCH -> "⚠️ Connexion interrompue avant la réponse du modèle. Veuillez vérifier la clé et le nom du modèle."
                                DetectedLanguage.SPANISH -> "⚠️ Conexión interrumpida antes de que el modelo respondiera. Verifique el nombre del modelo."
                                DetectedLanguage.ENGLISH -> "⚠️ Connection interrupted before model responded. Please verify model name and retry."
                            }
                            else -> when (detectedLang) {
                                DetectedLanguage.ARABIC -> "⚠️ لم يتم استلام أي رد من النموذج. تأكد من صحة اسم وإصدار النموذج المكتوب في الإعدادات."
                                DetectedLanguage.FRENCH -> "⚠️ Aucune réponse reçue du modèle. Veuillez vérifier le nom du modèle dans les paramètres."
                                DetectedLanguage.SPANISH -> "⚠️ Respuesta vacía recibida del modelo. Verifique el nombre del modelo en los ajustes."
                                DetectedLanguage.ENGLISH -> "⚠️ Empty response received from model. Please verify model name in Settings."
                            }
                        }
                        _uiState.update { state ->
                            val updated = state.logs.map { log ->
                                if (log.id == liveLogId) log.copy(text = errorText, isThinking = false, isError = true)
                                else log
                            }
                            state.copy(logs = updated, streamInterruptMsg = errorText)
                        }
                        keepGoing = false
                        activeGgufPromptPrefix = null
                    }
                }
                if (keepGoing && iterationCount >= MAX_TASK_ITERATIONS) {
                    val pauseText = when (detectLanguage(command)) {
                        DetectedLanguage.ARABIC -> "⚠️ أُوقفت المهمة مؤقتاً بعد بلوغ حدّ الأمان البالغ $MAX_TASK_ITERATIONS خطوة. لم أعتبرها مكتملة؛ أرسل «تابع» لاستئنافها."
                        DetectedLanguage.FRENCH -> "⚠️ La tâche est en pause après la limite de sécurité de $MAX_TASK_ITERATIONS étapes. Elle n'est pas marquée comme terminée ; envoyez « continuer » pour la reprendre."
                        DetectedLanguage.SPANISH -> "⚠️ La tarea quedó en pausa al alcanzar el límite de seguridad de $MAX_TASK_ITERATIONS pasos. No está marcada como completada; envía «continuar» para reanudarla."
                        DetectedLanguage.ENGLISH -> "⚠️ The task was paused at the $MAX_TASK_ITERATIONS-step safety limit and is not marked complete. Send “continue” to resume."
                    }
                    addLog(LogEntry(sender = "SYSTEM", text = pauseText, isError = true))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isProcessing = false, activeStatusDetail = "Generation stopped by user.", currentPhase = AgentActionPhase.IDLE, streamInterruptMsg = null) }
            } catch (e: Exception) {
                addLog(LogEntry(sender = "SYSTEM", text = "Mission Error: ${e.message}", isError = true))
            } finally {
                _uiState.update { it.copy(isProcessing = false, currentPhase = AgentActionPhase.IDLE, activeStatusDetail = "Idle / Ready", streamInterruptMsg = null) }
                if (actualContext != null) {
                    saveConversation(actualContext)
                    ModelEngineService.updateStatus(actualContext, "ATLAS-AI • Ready")
                }
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
        val modelType = _uiState.value.activeModelType ?: throw IllegalStateException("No model loaded")
        if (!isModelLoaded()) throw IllegalStateException("Model not ready")
        
        val ctx = appContext ?: MainActivity.instance
        ctx?.let { ModelEngineService.start(it, "Background: ${command.take(40)}") }
        val pm = ctx?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "synapse:BackgroundTask")
        wakeLock?.acquire(10 * 60 * 1000L)
        
        try {
            val savedTask = taskId?.let { taskMemory?.getTask(it) }
            val taskHistory = decodeTaskCheckpoint(savedTask?.executionCheckpoint.orEmpty())
            if (taskHistory.isEmpty()) taskHistory.add("user" to command)
            else if (taskHistory.first().first == "user") taskHistory[0] = "user" to command
            var lastProgressHash = savedTask?.lastProgressHash.orEmpty()
            var stalledCount = savedTask?.stalledCount ?: 0
            if (savedTask?.progress?.startsWith("Tool execution interrupted") == true) {
                taskHistory.add(
                    "user" to "The app restarted while a tool action may have been executing. Before repeating any action, inspect the current state and verify whether it already succeeded. Do not repeat irreversible actions blindly."
                )
                taskMemory?.saveExecutionCheckpoint(
                    taskId,
                    encodeTaskCheckpoint(taskHistory),
                    "Recovered an interrupted tool action; verifying state before retry"
                )
            }
            
            val dynamicPersona = getEffectivePersonaPrompt(ctx)
            val systemPrompt = buildSystemPrompt(ctx, modelType == ModelType.GGUF, dynamicPersona, userPrompt = command) +
                """

PERSISTENT TASK EXECUTION:
- Keep the original objective and user context in view across every step. Track a concise plan and update it from verified tool observations.
- Continue autonomously while safe and useful; do not stop merely because an output chunk ended.
- Before reporting completion, verify the objective against actual tool results. Never report an unverified action as complete.
- If an essential user decision, fact, or authorization is missing, stop and respond exactly as: NEEDS_USER_INPUT: <one specific question>. Do not guess or claim completion.
""".trimIndent()
            val resultSb = StringBuilder()
            var iterations = 0
            var keepGoing = true
            
            while (keepGoing && iterations < MAX_TASK_ITERATIONS) {
                if (taskId != null) {
                    val status = taskMemory?.getTask(taskId)?.status
                    if (status == null || status == TaskStatus.CANCELLED) {
                        throw kotlinx.coroutines.CancellationException("Task was cancelled or removed before completion")
                    }
                }
                
                iterations++
                taskMemory?.saveExecutionCheckpoint(
                    taskId,
                    encodeTaskCheckpoint(taskHistory),
                    "Generating and analyzing step $iterations"
                )
                val responseSb = StringBuilder()
                
                val stream = when (modelType) {
                    ModelType.GGUF -> ggufClient!!.generateStreamWithHistory(taskHistory, systemPrompt)
                    ModelType.GEMINI -> {
                        val json = JSONArray()
                        taskHistory.forEach { (r, c) -> 
                            val geminiRole = when (r.lowercase()) { "assistant", "model" -> "model"; else -> "user" }
                            json.put(JSONObject().put("role", geminiRole).put("content", c)) 
                        }
                        geminiClient!!.generateStreamWithOllamaMessages(json, systemPrompt) { responseSb.setLength(0) }
                    }
                }
                
                stream.collect { responseSb.append(it) }
                val response = responseSb.toString()
                if (response.isBlank()) {
                    throw IllegalStateException("Background task received an empty model response; it was not marked complete.")
                }
                
                val reply = cleanVisibleText(response).trim()
                taskHistory.add("model" to stripThinkingForHistory(reply))
                while (taskHistory.size > 10) taskHistory.removeAt(1)

                if (reply.startsWith("NEEDS_USER_INPUT:", ignoreCase = true)) {
                    val question = reply.substringAfter(':').trim().ifBlank {
                        "Please provide the missing information needed to continue this task."
                    }
                    taskMemory?.saveExecutionCheckpoint(
                        taskId,
                        encodeTaskCheckpoint(taskHistory),
                        question
                    )
                    throw TaskNeedsUserInputException(question)
                }

                val parsedCalls = StreamDemultiplexer.parseAllToolCalls(response)
                if (parsedCalls.isNotEmpty()) {
                    taskMemory?.saveExecutionCheckpoint(
                        taskId,
                        encodeTaskCheckpoint(taskHistory),
                        "Tool execution interrupted; verifying before any retry"
                    )
                }
                val (usedTools, summary) = executeToolLoop(response, ctx)

                val outputLimitReached = if (modelType == ModelType.GGUF) {
                    ggufClient?.lastFinishReason == "MAX_TOKENS"
                } else {
                    geminiClient?.lastFinishReason == "MAX_TOKENS"
                }
                if (outputLimitReached && !usedTools) {
                    taskHistory[taskHistory.lastIndex] =
                        "model" to compactContinuationContext(taskHistory.last().second)
                    taskHistory.add(
                        "user" to "Original task: ${command.take(1200)}\nThis output chunk reached its model token limit. Continue directly from the exact stopping point without repeating prior content, and complete the task. If essential information is missing and cannot be safely inferred, ask the user only for that information."
                    )
                    while (taskHistory.size > 10) taskHistory.removeAt(1)
                    taskMemory?.saveExecutionCheckpoint(
                        taskId,
                        encodeTaskCheckpoint(taskHistory),
                        "Continuing after model output limit"
                    )
                    continue
                }

                if (!usedTools) {
                    resultSb.append(summary ?: reply)
                    keepGoing = false
                } else {
                    if (summary != null && summary.isNotBlank()) {
                        taskHistory.add("user" to "[Tool Execution Observation]:\n$summary\n\nPlease proceed to synthesize your findings or complete the task.")
                        while (taskHistory.size > 10) taskHistory.removeAt(1)
                    }
                    val progressHash = MessageDigest.getInstance("SHA-256")
                        .digest("$response\n${summary.orEmpty()}".toByteArray(StandardCharsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    stalledCount = if (progressHash == lastProgressHash) stalledCount + 1 else 0
                    lastProgressHash = progressHash
                    if (stalledCount >= 2) {
                        taskMemory?.saveExecutionCheckpoint(
                            taskId,
                            encodeTaskCheckpoint(taskHistory),
                            "Task paused after repeated identical tool results",
                            progressHash,
                            stalledCount
                        )
                        throw TaskNeedsUserInputException(
                            "The task repeated the same tool request and result without making progress. Please clarify the desired outcome or provide the missing information before resuming."
                        )
                    }
                    if (stalledCount == 1) {
                        taskHistory.add(
                            "user" to "Progress check: the previous tool request and observation repeated without progress. Do not repeat that same action. Reassess the plan, choose a different safe approach, or ask the user for the specific missing information."
                        )
                    }
                    taskMemory?.saveExecutionCheckpoint(
                        taskId,
                        encodeTaskCheckpoint(taskHistory),
                        summary?.take(300).orEmpty().ifBlank { "Tool step $iterations completed; continuing" },
                        lastProgressHash,
                        stalledCount
                    )
                }
            }
            if (keepGoing) {
                throw IllegalStateException(
                    "Background task paused at the $MAX_TASK_ITERATIONS-step safety limit without being marked complete."
                )
            }
            val finalResult = resultSb.toString().ifBlank {
                throw IllegalStateException("Background task ended without a final response.")
            }
            taskMemory?.saveExecutionCheckpoint(
                taskId,
                encodeTaskCheckpoint(taskHistory),
                "Final response prepared; verified task completion",
                lastProgressHash,
                stalledCount
            )
            return finalResult
        } finally {
            ctx?.let { ModelEngineService.updateStatus(it, "ATLAS-AI • Ready") }
            if (wakeLock?.isHeld == true) wakeLock.release()
            updateTaskState()
        }
    }

    /** Returns true when the active model is a cloud/remote model (Gemini). */
    fun isCloudMode(): Boolean = _uiState.value.activeModelType == ModelType.GEMINI

    /**
     * Single-pass LLM call used by [MultiWorkerOrchestrator] for each ReAct step.
     */
    suspend fun runSingleLlmPass(
        systemPrompt: String,
        userPrompt: String,
        onChunk: ((String) -> Unit)? = null
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val modelType = _uiState.value.activeModelType ?: return@withContext ""
        val history = mutableListOf("user" to userPrompt)
        val sb = StringBuilder()
        try {
            val stream = when (modelType) {
                ModelType.GGUF -> ggufClient!!.generateStreamWithHistory(history, systemPrompt)
                ModelType.GEMINI -> {
                    val json = org.json.JSONArray()
                    json.put(org.json.JSONObject().put("role", "user").put("content", userPrompt))
                    geminiClient!!.generateStreamWithOllamaMessages(json, systemPrompt) { sb.setLength(0) }
                }
            }
            stream.collect { chunk -> sb.append(chunk); onChunk?.invoke(chunk) }
        } catch (e: Exception) {
            android.util.Log.w("AgentViewModel", "runSingleLlmPass error: ${e.message}")
        }
        sb.toString()
    }

    private suspend fun runProcessWithWatchdog(cmd: String, shell: String, timeoutMs: Long): Pair<Int, String> {
        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        val currentPath = env["PATH"] ?: "/system/bin:/system/xbin"
        env["PATH"] = "$currentPath:/system/bin:/system/xbin:/data/local/tmp"

        val process = pb.start()
        try { process.outputStream.close() } catch (_: Exception) {}

        val outputSb = StringBuffer()
        val maxOutputChars = 64 * 1024
        val readerThread = Thread({
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(2048)
                    var charsRead: Int
                    while (reader.read(buffer).also { charsRead = it } != -1) {
                        synchronized(outputSb) {
                            val remaining = maxOutputChars - outputSb.length
                            if (remaining > 0) outputSb.append(buffer, 0, minOf(charsRead, remaining))
                        }
                    }
                }
            } catch (_: Exception) {}
        }, "shell-watchdog-reader")
        readerThread.isDaemon = true
        readerThread.start()

        val startedAt = System.currentTimeMillis()
        var lastProgressUpdate = startedAt
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (process.waitFor(1, TimeUnit.SECONDS)) break

                val now = System.currentTimeMillis()
                val elapsedMs = now - startedAt
                if (timeoutMs > 0L && elapsedMs >= timeoutMs) {
                    process.destroyForcibly()
                    readerThread.join(500)
                    return -1 to "[Process timed out after ${timeoutMs / 1000}s and was terminated]\n$outputSb"
                }
                if (now - lastProgressUpdate >= 15_000L) {
                    val elapsedSeconds = elapsedMs / 1000
                    _uiState.update {
                        it.copy(
                            currentPhase = AgentActionPhase.EXECUTING_TOOL,
                            activeStatusDetail = "Command still running in background (${elapsedSeconds}s); waiting for completion..."
                        )
                    }
                    lastProgressUpdate = now
                }
            }
            readerThread.join(1000)
            val exitCode = try { process.exitValue() } catch (_: Exception) { 0 }
            return exitCode to outputSb.toString()
        } catch (e: kotlinx.coroutines.CancellationException) {
            process.destroyForcibly()
            throw e
        } finally {
            try { process.inputStream?.close() } catch (_: Exception) {}
            try { process.errorStream?.close() } catch (_: Exception) {}
            try { process.outputStream?.close() } catch (_: Exception) {}
            try { process.destroy() } catch (_: Exception) {}
        }
    }

    suspend fun runShellWithExitCode(cmd: String, timeoutMs: Long = 0L): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (cmd.isBlank()) return@withContext -1 to "[Process completed with no output]"
        val tryRoot = _uiState.value.isRootAvailable
        try {
            if (tryRoot) {
                try {
                    val (exitCode, lines) = RootShellManager.execWithExitCodeAsync(cmd)
                    return@withContext exitCode to sanitizeProcessOutput(lines.joinToString("\n"))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@withContext -1 to sanitizeProcessOutput("Root command execution failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            executeSafeProcess(cmd, timeoutMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            -1 to sanitizeProcessOutput("Error executing command: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun runShellInternal(cmd: String, timeoutMs: Long = 0L): String = runShellWithExitCode(cmd, timeoutMs).second

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
            if (line.trim() == delimiter) break
            contentLines.add(line)
        }

        val content = contentLines.joinToString("\n")
        return try {
            val resolved = ToolExecutor.resolvePath(rawPath, ToolExecutor.BASE_DIR)
            val file = File(resolved)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            if (isAppend) file.appendText(content, StandardCharsets.UTF_8) else file.writeText(content, StandardCharsets.UTF_8)
            val op = if (isAppend) "Appended to" else "Wrote"
            Triple(if (isAppend) "cat >> $rawPath" else "cat > $rawPath", 0, "[Native I/O Intercept]: $op ${content.length} chars directly to $resolved")
        } catch (e: Exception) {
            Triple("cat > $rawPath", 1, "Error: ${e.message}")
        }
    }

    private suspend fun executeToolLoop(response: String, context: Context? = null): Pair<Boolean, String?> {
        val actualContext = context ?: appContext ?: MainActivity.instance
        val toolCalls = StreamDemultiplexer.parseAllToolCalls(response)
        if (toolCalls.isEmpty()) return false to null

        val summaries = mutableListOf<String>()
        var actionToolUsed = false
        return try {
            for (call in toolCalls) {
                val action = call.name.trim().lowercase()
                val params = call.params
                val callHash = computeCallHash(action, params)

                if (executedCallsThisTurn.contains(callHash) || inFlightCalls.contains(callHash)) continue
                inFlightCalls.add(callHash)

                try {
                    when (action) {
                        "open_app", "launch_app" -> {
                            actionToolUsed = true
                            val target = params["package_name"] ?: params["package"] ?: params["app_name"] ?: params.values.firstOrNull() ?: ""
                            if (target.isNotBlank()) summaries.add(ToolHandler.handleOpenApp(target, actualContext).summary)
                        }
                        "web_search", "search", "google_search" -> {
                            val q = params["query"] ?: params["q"] ?: params.values.firstOrNull() ?: ""
                            if (q.trim().isNotBlank()) {
                                actionToolUsed = true
                                val searchData = try { WebSearchTool.search(q.trim()) } catch (e: Exception) { "Search error: ${e.message}" }
                                val truncatedSearch = truncateSearchObservation(searchData, maxChars = 1200, maxLines = 18)
                                summaries.add("🔍 Web Search: ${q.trim()}\n\n[RESULTS]:\n$truncatedSearch")
                            }
                        }
                        "run_python", "python", "py_eval" -> {
                            val script = params["script"] ?: params["code"] ?: params.values.firstOrNull() ?: ""
                            if (script.isNotBlank()) {
                                actionToolUsed = true
                                val output = PythonTool.execute(script)
                                summaries.add("✔ Tool(run_python) ─── [EXECUTED]\n$output")
                                addSystemLog("Python execution completed")
                            }
                        }
                        "fetch_web_content", "fetch_url", "read_url" -> {
                            val url = params["url"] ?: params.values.firstOrNull() ?: ""
                            if (url.isNotBlank()) {
                                actionToolUsed = true
                                val content = ContentReaderTool.fetchUrl(url)
                                summaries.add("✔ Tool(fetch_web_content: $url) ─── [FETCHED]\n$content")
                                addSystemLog("Fetched URL: $url")
                            }
                        }
                        "read_file_range", "read_lines" -> {
                            val path = params["path"] ?: params["file"] ?: ""
                            val start = params["start_line"]?.toIntOrNull() ?: 1
                            val count = params["line_count"]?.toIntOrNull() ?: 60
                            if (path.isNotBlank()) {
                                actionToolUsed = true
                                val range = ContentReaderTool.readFileRange(path, start, count)
                                summaries.add("✔ Tool(read_file_range: $path [$start..${start + count}])\n$range")
                                addSystemLog("Read range from: $path")
                            }
                        }
                        "schedule_task", "schedule" -> {
                            val desc = params["description"] ?: params["desc"] ?: params["title"] ?: "Scheduled Task"
                            val cmd = params["command"] ?: params["cmd"] ?: ""
                            val cron = params["cron"] ?: params["cron_expression"] ?: params["schedule"] ?: ""
                            actualContext?.let { initTaskSystem(it) }

                            if (cron.isNotBlank()) {
                                actionToolUsed = true
                                val taskId = taskEngine?.scheduleCronTask(desc, cmd, cron)
                                if (taskId != null) {
                                    summaries.add("✔ Recurring Task Scheduled:\n• Description: $desc\n• Command: $cmd\n• Cron Expression: $cron\n• Task ID: $taskId")
                                    addSystemLog("Scheduled cron task '$desc' ($cron)")
                                } else {
                                    summaries.add("✖ Failed to schedule task: Invalid cron expression '$cron'")
                                }
                                updateTaskState()
                            } else {
                                val delayMinutes = params["delay_minutes"]?.toLongOrNull() ?: params["delay"]?.toLongOrNull() ?: 1L
                                val executeAt = System.currentTimeMillis() + (delayMinutes.coerceAtLeast(1L) * 60_000L)
                                val task = AgentTask(
                                    description = desc,
                                    command = cmd,
                                    executeAt = executeAt,
                                    status = TaskStatus.PENDING,
                                    source = "agent_scheduler"
                                )
                                taskMemory?.addTask(task)
                                actualContext?.let { TaskAlarmReceiver.scheduleTaskAlarm(it, task.id, executeAt) }
                                updateTaskState()
                                actionToolUsed = true
                                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(executeAt))
                                summaries.add("✔ Task Scheduled:\n• Description: $desc\n• Command: $cmd\n• Scheduled run time: $timeStr (in $delayMinutes minute(s))\n• Task ID: ${task.id}")
                                addSystemLog("Scheduled task '$desc' for $timeStr")
                            }
                        }
                        "run_shell", "execute_shell", "terminal", "bash", "sh" -> {
                            val cmd = params["command"] ?: params["cmd"] ?: params.values.firstOrNull() ?: ""
                            if (cmd.isNotBlank()) {
                                actionToolUsed = true
                                val (exitCode, output) = runShellWithExitCode(cmd)
                                summaries.add("✔ Tool(run_shell: $cmd) [exit: $exitCode]\n$output")
                                addSystemLog("Executed shell: $cmd (exit: $exitCode)")
                            }
                        }
                        "write_file", "save_file", "create_file" -> {
                            val path = params["path"] ?: params["file"] ?: ""
                            val content = params["content"] ?: params["text"] ?: params["data"] ?: ""
                            if (path.isNotBlank()) {
                                actionToolUsed = true
                                val res = ToolHandler.handleWriteFile(path, content)
                                summaries.add(res.summary)
                            }
                        }
                        "click", "click_node", "tap" -> {
                            val target = params["target"] ?: params["text"] ?: params["id"] ?: params["coords"] ?: params.values.firstOrNull() ?: ""
                            if (target.isNotBlank()) {
                                actionToolUsed = true
                                val result = UiAutomationTool.click(target, actualContext)
                                summaries.add(result)
                                addSystemLog("Clicked UI target: $target")
                            }
                        }
                        "swipe", "scroll" -> {
                            val direction = params["direction"] ?: params["coords"] ?: params.values.firstOrNull() ?: "up"
                            actionToolUsed = true
                            val result = UiAutomationTool.swipe(direction, actualContext)
                            summaries.add(result)
                            addSystemLog("Swiped UI: $direction")
                        }
                        "input_text", "type_text" -> {
                            val text = params["text"] ?: params["content"] ?: params.values.firstOrNull() ?: ""
                            val target = params["target"] ?: params["id"]
                            if (text.isNotBlank()) {
                                actionToolUsed = true
                                val result = UiAutomationTool.inputText(text, target, actualContext)
                                summaries.add(result)
                                addSystemLog("Injected UI text: $text")
                            }
                        }
                        "capture_screen", "screenshot", "take_screenshot" -> {
                            val path = params["save_path"] ?: params["path"]
                            actionToolUsed = true
                            val result = ScreenVisionTool.captureScreen(path)
                            summaries.add(result)
                            addSystemLog("Captured screen buffer")
                        }
                        "ocr_screen", "screen_vision", "read_screen_text" -> {
                            val path = params["image_path"] ?: params["path"]
                            actionToolUsed = true
                            val result = ScreenVisionTool.ocrScreen(path, actualContext)
                            summaries.add(result)
                            addSystemLog("Processed screen vision & OCR")
                        }
                        "queue_background_task", "background_task", "queue_task" -> {
                            val cmd = params["command"] ?: params["cmd"] ?: params.values.firstOrNull() ?: ""
                            val desc = params["description"] ?: params["desc"] ?: params["title"] ?: cmd.take(50)
                            actualContext?.let { initTaskSystem(it) }
                            val taskId = taskEngine?.queueCommand(cmd, "agent")
                            updateTaskState()
                            actionToolUsed = true
                            summaries.add("✔ Background Task Queued:\n• Description: $desc\n• Command: $cmd\n• Task ID: $taskId\n• Status: Active in background queue")
                            addSystemLog("Queued background task: $desc")
                        }
                        "list_tasks", "get_tasks" -> {
                            actualContext?.let { initTaskSystem(it) }
                            updateTaskState()
                            val summary = taskMemory?.getTaskSummary() ?: "No active tasks"
                            val activeList = taskMemory?.getAllActiveTasks()?.joinToString("\n") { t ->
                                val dueStr = if (t.executeAt > 0) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t.executeAt)) else "immediate"
                                val progress = t.progress.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                                "• [${t.status}] ${t.description} (ID: ${t.id}, Run at: $dueStr)$progress"
                            } ?: "None"
                            actionToolUsed = true
                            summaries.add("📋 Task List Summary: $summary\n$activeList")
                        }
                        "resume_task", "continue_task" -> {
                            val taskId = params["task_id"] ?: params["id"] ?: ""
                            val userInput = params["user_input"] ?: params["clarification"] ?: params["answer"] ?: ""
                            actualContext?.let { initTaskSystem(it) }
                            actionToolUsed = true
                            val resumed = if (taskId.isNotBlank() && userInput.isNotBlank()) {
                                taskEngine?.resumeTask(taskId, userInput) ?: false
                            } else {
                                false
                            }
                            updateTaskState()
                            summaries.add(
                                if (resumed) "✔ Task $taskId resumed with the user's clarification and queued to continue from its saved checkpoint."
                                else "✖ Could not resume the task. Verify the full task ID, provide a non-empty clarification, and ensure the task is waiting for user input."
                            )
                        }
                        "cancel_task" -> {
                            val targetId = params["task_id"] ?: params["id"] ?: params.values.firstOrNull() ?: ""
                            if (targetId.isNotBlank()) {
                                actualContext?.let { initTaskSystem(it) }
                                val cancelled = taskEngine?.cancelTask(targetId) ?: false
                                if (!cancelled) {
                                    taskMemory?.markCancelled(targetId)
                                }
                                updateTaskState()
                                actionToolUsed = true
                                summaries.add("✔ Task $targetId has been cancelled.")
                                addSystemLog("Cancelled task: $targetId")
                            }
                        }
                        "complete" -> {
                            val s = params["summary"] ?: params.values.firstOrNull() ?: ""
                            val taskName = params["task"] ?: params["title"] ?: "Task Execution"
                            val structuredSummary = TaskCompletionProtocol.generateStructuredSummary(
                                taskName = taskName,
                                actionDetails = s.ifBlank { "Task sequence concluded successfully." },
                                isSuccess = true
                            )
                            summaries.add(structuredSummary)
                            return false to summaries.joinToString("\n\n")
                        }
                        "run_plugin", "plugin", "exec_plugin" -> {
                            actualContext?.let { initTaskSystem(it) }
                            val pluginName = params["name"] ?: params["plugin"] ?: params["plugin_name"] ?: params.values.firstOrNull() ?: ""
                            if (pluginName.isNotBlank()) {
                                actionToolUsed = true
                                val pluginRes = com.synapse.localai.plugins.PluginRegistry.executePlugin(pluginName, params)
                                summaries.add(pluginRes.summary)
                                addSystemLog("Executed dynamic plugin: $pluginName")
                            }
                        }
                        "save_memory", "store_memory", "remember" -> {
                            actualContext?.let { initTaskSystem(it) }
                            val content = params["content"] ?: params["text"] ?: params["memory"] ?: params.values.firstOrNull() ?: ""
                            val category = params["category"] ?: params["type"] ?: "FACT"
                            if (content.isNotBlank()) {
                                actionToolUsed = true
                                val rec = com.synapse.localai.memory.VectorMemoryManager.store(content, category)
                                summaries.add("✔ Persistent Memory Saved [${rec.category}]:\n• Content: \"${rec.content}\"\n• Vector: 128-dim normalized embedding stored.")
                                addSystemLog("Saved vector memory: ${rec.id}")
                            }
                        }
                        "search_memory", "query_memory", "recall" -> {
                            actualContext?.let { initTaskSystem(it) }
                            val q = params["query"] ?: params["q"] ?: params.values.firstOrNull() ?: ""
                            if (q.isNotBlank()) {
                                actionToolUsed = true
                                val results = com.synapse.localai.memory.VectorMemoryManager.search(q, topK = 3)
                                val summary = if (results.isNotEmpty()) {
                                    buildString {
                                        appendLine("🔍 Semantic Memory Recall for \"$q\":")
                                        results.forEach { (rec, score) ->
                                            appendLine("• [${rec.category}] (similarity: ${String.format(Locale.US, "%.2f", score)}) ${rec.content}")
                                        }
                                    }
                                } else {
                                    "🔍 Semantic Memory: No close matches found for \"$q\"."
                                }
                                summaries.add(summary)
                                addSystemLog("Searched vector memory for: $q")
                            }
                        }
                        else -> {
                            actualContext?.let { initTaskSystem(it) }
                            if (com.synapse.localai.plugins.PluginRegistry.hasPlugin(action)) {
                                actionToolUsed = true
                                val pluginRes = com.synapse.localai.plugins.PluginRegistry.executePlugin(action, params)
                                summaries.add(pluginRes.summary)
                                addSystemLog("Executed dynamic plugin: $action")
                            }
                        }
                    }
                } finally {
                    inFlightCalls.remove(callHash)
                    executedCallsThisTurn.add(callHash)
                }
            }
            actionToolUsed to (if (summaries.isNotEmpty()) summaries.joinToString("\n\n") else null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed", e)
            false to null
        }
    }

    fun initTaskSystem(context: Context) {
        if (taskMemory == null) {
            val tm = TaskMemoryManager(context)
            taskMemory = tm
            val engine = BackgroundTaskEngine(tm)
            val daemon = MaintenanceDaemon(context, tm)
            engine.maintenanceDaemon = daemon
            taskEngine = engine
            taskEngine?.start()
        }
        com.synapse.localai.plugins.PluginRegistry.init(context)
        com.synapse.localai.memory.VectorMemoryManager.init(context)
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

    fun refreshSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            chatSessions.value = chatDb?.getAllSessions() ?: emptyList()
        }
    }

    fun startNewSession() {
        val newId = UUID.randomUUID().toString()
        currentSessionId.value = newId
        currentSessionTitle.value = "New Session"
        _uiState.update { it.copy(logs = emptyList(), resumptionNotice = null, activeWorkspacePath = null) }
        conversationHistory.clear()
        viewModelScope.launch(Dispatchers.IO) {
            chatDb?.insertSession(ChatSessionEntity(id = newId, title = "New Session", createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
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
                    text = cleanVisibleText(logText),
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
                    val role = if (l.sender == "USER") "user" else "model"
                    val cleanText = if (role == "model") {
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

    fun stopAgent() { 
        currentJob?.cancel()
        currentJob = null
        _uiState.update { it.copy(isProcessing = false) } 
    }

    fun addSystemLog(text: String) { Log.i(TAG, "SystemLog: $text") }
    private fun prefs(context: Context) = context.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)
    private fun addLog(entry: LogEntry) { _uiState.update { it.copy(logs = it.logs + entry) } }

    override fun onCleared() { 
        super.onCleared()
        taskEngine?.stop()
        closeAllModels() 
    }
}

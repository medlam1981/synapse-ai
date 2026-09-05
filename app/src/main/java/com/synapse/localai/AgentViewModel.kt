package com.synapse.localai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
import com.synapse.localai.orchestration.MultiWorkerOrchestrator

enum class ModelType { GGUF, GEMINI }

enum class AgentActionPhase {
    IDLE,
    THINKING,
    PLANNING,
    GENERATING_CODE,
    WRITING_FILE,
    EXECUTING_TOOL,
    COMPLETING
}

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val thinkingText: String = "",
    val isThinking: Boolean = false
)

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
    val geminiApiKeys: String = "",
    val geminiModelName: String = "gemini-2.0-flash",
    val serverHost: String = "0.0.0.0",
    val serverPort: Int = 8088,
    val contextLength: Int = 8192,
    val threadCount: Int = 4,
    val maxTokens: Int = -1,
    val temperature: Float = 0.7f,
    val enableVisualHistory: Boolean = false,
    val enableNeuralReflexes: Boolean = false,
    val enabledSpecializedAgents: Set<String> = emptySet(),
    val systemPrompt: String = "",
    val customToolsPrompt: String = "",
    val showApiKeyTracker: Boolean = true,
    val activePersona: String = PersonaManager.DEFAULT_PERSONA,
    val activePersonas: Set<String> = PersonaManager.DEFAULT_PERSONAS_SET
)

data class AgentUiState(
    val isProcessing: Boolean = false,
    val logs: List<LogEntry> = emptyList(),
    val streamVersion: Int = 0,
    val config: AgentConfig = AgentConfig(),
    val serviceActive: Boolean = false,
    val overlayActive: Boolean = false,
    val keyboardActive: Boolean = false,
    val localModelLoading: Boolean = false,
    val localModelLoaded: Boolean = false,
    val modelLoadFailed: Boolean = false,
    val loadingProgress: Float? = null,
    val loadingStatusText: String = "",
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
    val isNotificationActive: Boolean = false,
    val isRootAvailable: Boolean = false,
    val streamInterruptMsg: String? = null,
    val tokensPerSecond: Float = 0f,
    val activeStatusDetail: String = "Idle / Ready",
    val resumptionNotice: String? = null,
    val activeWorkspacePath: String? = null,
    val activePersona: String = PersonaManager.DEFAULT_PERSONA,
    val activePersonas: Set<String> = PersonaManager.DEFAULT_PERSONAS_SET,
    val availablePersonas: List<String> = emptyList()
)

class AgentViewModel : ViewModel() {

    companion object {
        private const val TAG = "SYNAPSE_CORE"
        private const val MAX_HISTORY_MESSAGES = 6
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
    private var currentJob: Job? = null
    private var schedulerJob: Job? = null
    private var visualMemoryJob: Job? = null
    private val scheduledTasks = Collections.synchronizedList(mutableListOf<ScheduledTask>())
    private val workflows = Collections.synchronizedMap(mutableMapOf<String, String>())
    
    private val pendingCommands = Collections.synchronizedList(mutableListOf<String>())
    
    private var coreMission: String = ""
    private val conversationHistory = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    // ── Turn-Level Tool Deduplication & Idempotency Lock ──────────────────────
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
        var clean = target.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (clean.contains("/")) {
            clean = clean.substringBefore("/")
        }
        val lower = clean.lowercase()

        val aliasMap = mapOf(
            "chrome" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "google-chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "web browser" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "settings" to "com.android.settings",
            "android settings" to "com.android.settings",
            "camera" to "com.android.camera2",
            "calculator" to "com.google.android.calculator",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "gmail" to "com.google.android.gm",
            "mail" to "com.google.android.gm",
            "email" to "com.google.android.gm",
            "files" to "com.google.android.documentsui",
            "file manager" to "com.google.android.documentsui",
            "play store" to "com.android.vending",
            "playstore" to "com.android.vending",
            "clock" to "com.google.android.deskclock",
            "contacts" to "com.google.android.contacts",
            "messages" to "com.google.android.apps.messaging",
            "sms" to "com.google.android.apps.messaging",
            "phone" to "com.google.android.dialer",
            "dialer" to "com.google.android.dialer"
        )

        aliasMap[lower]?.let { return it }

        if (clean.contains(".")) {
            return clean
        }

        if (pm != null) {
            try {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
                for (ri in resolveInfos) {
                    val label = ri.loadLabel(pm).toString().lowercase()
                    if (label == lower || label.contains(lower)) {
                        return ri.activityInfo.packageName
                    }
                }
            } catch (_: Exception) {}
        }

        return clean
    }

    fun launchApplication(target: String, context: Context? = null): Pair<Boolean, String> {
        val ctx = context ?: appContext ?: MainActivity.instance ?: return false to "Context unavailable"
        val pm = ctx.packageManager
        val packageName = resolvePackageName(target, pm)

        return try {
            val launchIntent = pm.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            } ?: return false to "Package '$packageName' not installed or has no launch intent"

            ctx.startActivity(launchIntent)

            val appLabel = try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                packageName
            }
            true to appLabel
        } catch (e: Exception) {
            false to (e.message ?: "Failed to start activity")
        }
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
        // 1. Remove fenced thought blocks
        text = text.replace(Regex("""```(?:thought|thinking|reasoning)\r?\n[\s\S]*?(?:```|$)""", RegexOption.IGNORE_CASE), "")
        // 2. Remove all closed thinking tags
        text = text.replace(Regex("""<\|?(?:think|thought|reasoning)\|?>[\s\S]*?</\|?(?:think|thought|reasoning)\|?>""", RegexOption.IGNORE_CASE), "")
        // 3. Remove unclosed thinking tags at end
        text = text.replace(Regex("""<\|?(?:think|thought|reasoning)\|?>[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        // 4. Remove tool calls and terminal frames
        text = text.replace(Regex("""<function_calls>[\s\S]*?</function_calls>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<invoke[\s\S]*?</invoke>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<function_calls>[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<invoke[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<terminal_frame[\s\S]*?</terminal_frame>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<terminal_frame[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""Shell output \(`[^`]+`\):\s*```(?:bash|sh)?\r?\n[\s\S]*?```""", RegexOption.IGNORE_CASE), "")
        // 5. Remove any orphan or stray tags
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
        if (!content.contains("```")) return content.take(1200)
        return content.replace(Regex("""```([a-zA-Z0-9_\-]*)\n([\s\S]*?)```""")) { mr ->
            val lang = mr.groupValues[1]
            val code = mr.groupValues[2]
            if (code.length > 200) {
                val preview = code.take(100).trimEnd()
                val lineCount = code.lines().size
                "```$lang\n$preview\n// ... [$lineCount lines deliverable saved to disk] ...\n```"
            } else {
                mr.value
            }
        }.take(1500)
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

    fun loadConfig(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "Starting loadConfig...")
        try {
            if (portalServer == null) {
                portalServer = PortalServer(this)
                portalServer?.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "PortalServer init failed", e)
        }

        try {
            val prefs = prefs(context)
            val savedPath = prefs.getString("local_model_path", null)
            
            var savedContext = prefs.getInt("context_length", 8192)
            if (savedContext < 4096) savedContext = 8192

            var savedThreads = prefs.getInt("thread_count", 2)
            if (savedThreads < 1) savedThreads = 2
            
            val modelTypeStr = prefs.getString("preferred_model_type", ModelType.GEMINI.name)
            val modelType = try { ModelType.valueOf(modelTypeStr!!) } catch (_: Exception) { ModelType.GEMINI }
            var geminiKeys = prefs.getString("gemini_api_keys", "") ?: ""
            var geminiModel = prefs.getString("gemini_model_name", "gemini-2.0-flash") ?: "gemini-2.0-flash"
            ModelScanner.ensureDirectories()
            val activePersonasSet = PersonaManager.getActivePersonas(context)
            val activePersonaName = activePersonasSet.firstOrNull() ?: PersonaManager.DEFAULT_PERSONA
            val cachedPersonasList = PersonaManager.getPersonas(context, forceRescan = false)
            
            var localPath = savedPath ?: ""
            if (localPath.isBlank()) {
                val cachedModels = ModelScanner.getBaseModels(context, forceRescan = false)
                if (cachedModels.isNotEmpty()) {
                    localPath = cachedModels.first().absolutePath
                }
            }
            val rawDraft = prefs.getString("draft_model_path", "") ?: ""
            val rawLower = rawDraft.lowercase()
            val draftIsInvalid = rawLower.contains("assistant") ||
                (rawLower.contains("bf16") && rawLower.contains("mtp-gemma-4") && !rawLower.contains("e2b")) ||
                (rawDraft.isNotBlank() && !File(rawDraft).exists())
            var draftPath = if (draftIsInvalid) "" else rawDraft
            if (localPath.isNotBlank() && draftPath.isNotBlank()) {
                val baseName = File(localPath).nameWithoutExtension.lowercase()
                val draftName = File(draftPath).nameWithoutExtension.lowercase()
                val isFamilyMatch = when {
                    baseName.contains("gemma") && draftName.contains("gemma") -> true
                    baseName.contains("qwen") && draftName.contains("qwen") -> true
                    baseName.contains("llama") && draftName.contains("llama") -> true
                    else -> false
                }
                if (!isFamilyMatch) {
                    draftPath = ""
                }
            }
            if (draftPath.isBlank() && localPath.isNotBlank()) {
                val matched = ModelScanner.findMatchingMtpModel(localPath, context)
                if (matched != null) {
                    draftPath = matched.absolutePath
                }
            }
            val gpuLayers = prefs.getInt("gpu_layers", 0)
            val useMmap = prefs.getBoolean("use_mmap", true)
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
                geminiApiKeys = geminiKeys,
                geminiModelName = geminiModel,
                serverHost = prefs.getString("server_host", "0.0.0.0") ?: "0.0.0.0",
                serverPort = prefs.getInt("server_port", 8088),
                contextLength = if (savedContext >= 2048) savedContext else 8192,
                threadCount = savedThreads,
                maxTokens = prefs.getInt("max_tokens", -1),
                temperature = prefs.getFloat("temperature", 0.7f),
                enableVisualHistory = prefs.getBoolean("enable_visual_history", false),
                enableNeuralReflexes = prefs.getBoolean("enable_neural_reflexes", false),
                enabledSpecializedAgents = prefs.getStringSet("enabled_agents", emptySet()) ?: emptySet(),
                systemPrompt = prefs.getString("system_prompt", "") ?: "",
                customToolsPrompt = prefs.getString("custom_tools_prompt", "") ?: "",
                showApiKeyTracker = prefs.getBoolean("show_api_key_tracker", true),
                activePersona = activePersonaName,
                activePersonas = activePersonasSet
            )

            // Cold-Start Memory: Restore last known working key index directly from disk
            val savedKeyIndex = prefs.getInt("current_key_index", 0)
            val safeKeyIndex = if (activeGeminiKeyCount > 0) savedKeyIndex.coerceIn(0, activeGeminiKeyCount - 1) else 0

            _uiState.update { 
                it.copy(
                    config = config, 
                    totalKeys = activeGeminiKeyCount,
                    currentKeyIndex = safeKeyIndex,
                    activePersona = activePersonaName,
                    activePersonas = activePersonasSet,
                    availablePersonas = cachedPersonasList
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

            // Pristine Default Workspace: Every cold launch always lands on a fresh, clean slate
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
                viewModelScope.launch {
                    triggerModelLoad(context, config.localModelPath)
                }
            } else if (config.preferredModelType == ModelType.GEMINI && activeGeminiKeyCount > 0) {
                loadGeminiModel(config.geminiApiKeys, config.geminiModelName, context)
            }

            updateServiceStatus(context)
        } catch (e: Exception) {
            Log.e(TAG, "initialize failed", e)
            addLog(LogEntry(sender = "SYSTEM", text = "Boot Error: ${e.message}", isError = true))
        }
    }

    fun updateServiceStatus(context: Context) {
        val isNotificationActive = try {
            val enabledListeners = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            enabledListeners.contains(context.packageName)
        } catch (e: Exception) {
            false
        }

        _uiState.update { it.copy(
            isNotificationActive = isNotificationActive
        ) }
    }

    fun saveConfig(context: Context, config: AgentConfig) {
        val finalConfig = if (config.contextLength < 2048) config.copy(contextLength = 8192) else config
        val oldConfig = _uiState.value.config
        val oldPath = oldConfig.localModelPath
        val oldGeminiKeys = oldConfig.geminiApiKeys
        val oldType = oldConfig.preferredModelType

        val activeKeys = finalConfig.geminiApiKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        _uiState.update { it.copy(config = finalConfig, totalKeys = activeKeys.size) }
        
        val prefs = prefs(context)
        prefs.edit().apply {
            putString("local_model_path", finalConfig.localModelPath)
            putString("draft_model_path", finalConfig.draftModelPath)
            putInt("gpu_layers", finalConfig.gpuLayers)
            putBoolean("use_mmap", finalConfig.useMmap)
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
            apply()
        }

        val oldDraftPath = oldConfig.draftModelPath
        val draftChanged = finalConfig.draftModelPath != oldDraftPath
        val modelChanged = finalConfig.localModelPath != oldPath
        val typeChanged = oldType != finalConfig.preferredModelType
        val notLoaded = !isModelLoaded()

        if (finalConfig.preferredModelType == ModelType.GGUF) {
            if (modelChanged || draftChanged || typeChanged || notLoaded) {
                closeAllModels()
                viewModelScope.launch {
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

    private var modelLoadJob: Job? = null
    @Volatile private var isModelLoadingInProgress = false

    suspend fun triggerModelLoad(context: Context, path: String) {
        modelLoadJob?.cancel()
        isModelLoadingInProgress = false

        modelLoadJob = viewModelScope.launch {
            if (isModelLoadingInProgress) return@launch
            isModelLoadingInProgress = true
            try {
                val type = detectModelType(path)
                val modelName = java.io.File(path).nameWithoutExtension
                
                _uiState.update { it.copy(
                    localModelLoading = true, 
                    localModelLoaded = false, 
                    modelLoadFailed = false,
                    activeModelType = null,
                    loadingProgress = 0.05f,
                    loadingStatusText = "Initializing Neural Core..."
                ) }
                
                if (type == ModelType.GGUF) {
                    try {
                        val config = _uiState.value.config
                        val client = GGUFModelClient().apply {
                            serverHost = config.serverHost
                            serverPort = config.serverPort
                            contextLength = if (config.contextLength >= 4096) config.contextLength else 8192
                            threadCount = config.threadCount
                            maxTokens = -1
                            temperature = config.temperature
                            draftModelPath = config.draftModelPath.ifBlank { null }
                            gpuLayers = config.gpuLayers
                            useMmap = config.useMmap
                        }
                        ggufClient = client
                        client.loadModel(
                            context = context,
                            path = path,
                            onStatus = { status -> addSystemLog(status) },
                            onProgress = { progress, statusText ->
                                _uiState.update { it.copy(loadingProgress = progress, loadingStatusText = statusText) }
                            },
                            onLoaded = {
                                isModelLoadingInProgress = false
                                if (client.isLoaded) {
                                    _uiState.update { it.copy(
                                        localModelLoaded = true, 
                                        localModelLoading = false, 
                                        modelLoadFailed = false,
                                        activeModelType = ModelType.GGUF, 
                                        activeModelName = modelName,
                                        loadingProgress = null,
                                        loadingStatusText = ""
                                    ) }
                                    addSystemLog("Local Model Active: $modelName")
                                    viewModelScope.launch(Dispatchers.IO) {
                                        val systemPrompt = buildSystemPrompt(context, forLocalModel = true)
                                        val primed = client.warmupPersonaKVCache(systemPrompt)
                                        if (primed) {
                                            addSystemLog("Persona KV Cache Primed & Pre-warmed ✓")
                                        }
                                    }
                                } else {
                                    _uiState.update { it.copy(
                                        localModelLoaded = false, 
                                        localModelLoading = false, 
                                        modelLoadFailed = true,
                                        loadingProgress = null,
                                        loadingStatusText = ""
                                    ) }
                                    addSystemLog("Model loading failed: $modelName")
                                }
                            }
                        )
                    } catch (t: Throwable) {
                        isModelLoadingInProgress = false
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
            } finally {
                isModelLoadingInProgress = false
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

        _uiState.update { it.copy(activeModelType = ModelType.GEMINI, activeModelName = modelName, totalKeys = keys.size, currentKeyIndex = safeInitialIdx, modelLoadFailed = false) }
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
        return """
## Host Action & System Toolchains:
You have direct programmatic access to the host Android system through these toolchains:

1. `run_shell(command: String)`: Execute terminal or shell commands directly on the host Android device with Superuser/Root permissions.
2. `web_search(query: String)`: Search the web silently for real-time facts, documentation, or live information.
3. `schedule_task(description: String, command: String, delay_minutes: Int)`: Schedule delayed headless background execution.
4. `queue_background_task(command: String)`: Queue a background automation script or command into the local Task Engine (tsk).
5. `complete(summary: String)`: Terminate and finalize an active multi-step automation mission.

Format for calling tools:
<function_calls>
<invoke name="tool_name">
<parameter name="param_name">value</parameter>
</invoke>
</function_calls>

## Autonomous Workspace File Engine:
To create or update files in the local workspace (`/sdcard/Medlam-Ai/...`), specify the file path immediately above a fenced code block:
file: /sdcard/Medlam-Ai/webapp/index.html
```html
<!DOCTYPE html>
<html>...</html>
```
The file execution engine automatically parses this block and writes the file directly to device storage.
""".trimIndent()
    }

    @Volatile
    private var cachedStaticPrefix: String? = null
    @Volatile
    private var cachedPrefixKey: String = ""

    private fun buildSystemPrompt(context: Context? = null, forLocalModel: Boolean = false): String {
        val config = _uiState.value.config
        val actualContext = context ?: MainActivity.instance
        val customPrompt = config.systemPrompt.trim()

        // Distilled high-density prompt for on-device GGUF execution (cuts CPU prefill latency from 32s to ~1s)
        if (forLocalModel) {
            val custom = if (customPrompt.isNotBlank()) "\n$customPrompt" else ""
            return """CRITICAL UI RULE: Never output raw shell commands, heredocs (cat << 'EOF'), or raw command logs directly into conversational dialogue. For creating or editing files, always call the dedicated write_file tool. To launch an Android app, always call open_app(package_name) natively; never pipe 'am start' through run_shell. When calling run_shell, supply clean, concise system commands only. Keep conversational responses focused on human-readable summaries, architectural analysis, and verified status reports.
You are an autonomous on-device CLI execution agent. You operate headlessly via shell commands and explicit file system writes. You have no accessibility service or screen-scraping capabilities. Tool calls must strictly follow schema definitions and must never be enclosed inside markdown code blocks or file streams. When performing an action, invoke the tool directly; do not generate HTML files to represent execution.
Always plan your reasoning inside <think>...</think> tags first before writing your final response.
When providing code, strictly enclose it in markdown code fences (```lang).
Format for calling tools:
<function_calls>
<invoke name="tool_name">
<parameter name="param_name">value</parameter>
</invoke>
</function_calls>
Available tools: open_app(package_name), run_shell(command), write_file(path, content), web_search(query), schedule_task(description, command, delay_minutes), queue_background_task(command), complete(summary).
Be direct, authentic, concise, and fast.$custom""".trimIndent()
        }

        val activePersonas = actualContext?.let { PersonaManager.getActivePersonas(it) } ?: emptySet()
        val currentKey = "${activePersonas.sorted().joinToString(",")}|$customPrompt"

        if (cachedPrefixKey == currentKey && cachedStaticPrefix != null) {
            return cachedStaticPrefix!!
        }

        // 1. Distilled Epistemic Mind (hardware, memory snapshot, user preferences)
        val livingMind = actualContext?.let { EpistemicMemoryManager.getDistilledContextPrompt(it) } ?: ""

        // 2. Runtime Context Concatenation of Active Personas (.md loaded strictly from /sdcard/Medlam-Ai/personas/)
        val activePersonasMarkdown = actualContext?.let { PersonaManager.getActivePersonasContent(it) }
            ?: PersonaManager.DEFAULT_CODING_PERSONA

        // 3. Operational Toolchains & Execution Guidelines (High-density, token-efficient format)
        val toolsAndGuidelines = """
## Host Toolchains & Execution Guidelines:
CRITICAL UI RULE: Never output raw shell commands, heredocs (cat << 'EOF'), or raw command logs directly into conversational dialogue. For creating or editing files, always call the dedicated write_file tool. To launch an Android app, always call open_app(package_name) natively; never pipe 'am start' through run_shell. When calling run_shell, supply clean, concise system commands only. Keep conversational responses focused on human-readable summaries, architectural analysis, and verified status reports.
You are an autonomous on-device CLI execution agent. You operate headlessly via shell commands and explicit file system writes. You have no accessibility service or screen-scraping capabilities. Tool calls must strictly follow schema definitions and must never be enclosed inside markdown code blocks or file streams. When performing an action, invoke the tool directly; do not generate HTML files to represent execution.
- Plan thoughts inside <think>...</think> tags before executing tools or deliverables.
- App Launching: Use open_app(package_name) (e.g. com.android.chrome) to launch Android applications natively. Do NOT pipe am start through run_shell.
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

        val compiled = "$livingMind\n\n$activePersonasMarkdown\n\n$toolsAndGuidelines$customPromptSection"
        cachedPrefixKey = currentKey
        cachedStaticPrefix = compiled
        return compiled
    }

    fun sendCommand(command: String, fileUri: Uri? = null, context: Context? = null) {
        if (command.isBlank() && fileUri == null) return
        
        if (_uiState.value.isProcessing) {
            synchronized(pendingCommands) {
                pendingCommands.add(command)
            }
            addLog(LogEntry(sender = "SYSTEM", text = "Command queued (${pendingCommands.size} in queue)"))
            return
        }
        
        val actualContext = context ?: MainActivity.instance
        autoTitleSessionIfNew(command)
        val userLog = LogEntry(sender = "USER", text = command.takeIf { it.isNotBlank() } ?: "[Attached File]")
        addLog(userLog)
        val sid = currentSessionId.value
        val detectedInitialPath = ToolExecutor.detectFilePath(command)

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
            val sid = currentSessionId.value
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
                    activeWorkspacePath = null,
                    lastExecutionResult = "",
                    streamInterruptMsg = null
                ) 
            }
            
            try {
                var finalCommand = command
                val imageBase64List = mutableListOf<String>()

                // Silent background web search pre-fetch for live weather & real-time queries
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

                // ── Multi-Worker MCP Orchestration Pipeline ─────────────────
                if (MultiWorkerOrchestrator.isOrchestrationCandidate(finalCommand)) {
                    val isCloud = modelType == ModelType.GEMINI
                    val liveLogId = UUID.randomUUID().toString()
                    val initialLiveLog = LogEntry(
                        id = liveLogId,
                        sender = "AGENT",
                        text = "> $finalCommand\n\n⚡ Orchestrator: Initializing Multi-Worker MCP Pipeline...",
                        isThinking = false
                    )
                    _uiState.update { 
                        it.copy(
                            logs = it.logs + initialLiveLog,
                            currentPhase = AgentActionPhase.EXECUTING_TOOL,
                            activeStatusDetail = "Orchestrating Multi-Worker Pipeline..."
                        ) 
                    }

                    val orchestrator = MultiWorkerOrchestrator(
                        sessionId = sid,
                        isCloudMode = isCloud,
                        modelInvoker = { sysPrompt, usrPrompt ->
                            executeSingleModelCall(modelType, sysPrompt, usrPrompt)
                        },
                        onTelemetryUpdate = { activeCount ->
                            _uiState.update { it.copy(taskQueueSize = activeCount) }
                        },
                        onTerminalLogUpdate = { cliText ->
                            _uiState.update { state ->
                                val updatedLogs = state.logs.toMutableList()
                                val idx = updatedLogs.indexOfLast { it.id == liveLogId }
                                if (idx >= 0) {
                                    updatedLogs[idx] = updatedLogs[idx].copy(text = cliText)
                                }
                                state.copy(logs = updatedLogs, streamVersion = state.streamVersion + 1)
                            }
                        },
                        shellExecutor = { cmd -> runShellInternal(cmd) }
                    )

                    val finalCliLog = orchestrator.execute(finalCommand)

                    _uiState.update { state ->
                        val updatedLogs = state.logs.toMutableList()
                        val idx = updatedLogs.indexOfLast { it.id == liveLogId }
                        if (idx >= 0) {
                            updatedLogs[idx] = updatedLogs[idx].copy(text = finalCliLog)
                        }
                        state.copy(
                            logs = updatedLogs,
                            isProcessing = false,
                            currentPhase = AgentActionPhase.COMPLETING,
                            activeStatusDetail = "Ready / Operational",
                            taskQueueSize = 0
                        )
                    }

                    synchronized(conversationHistory) {
                        conversationHistory.add("assistant" to finalCliLog)
                        while (conversationHistory.size > MAX_HISTORY_MESSAGES) conversationHistory.removeAt(0)
                    }

                    chatDb?.insertMessage(
                        ChatMessageEntity(
                            id = liveLogId,
                            sessionId = sid,
                            sender = "AGENT",
                            text = finalCliLog,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    refreshSessions()
                    return@launch
                }

                val systemPrompt = buildSystemPrompt(actualContext, forLocalModel = modelType == ModelType.GGUF)
                var keepGoing = true
                var iterationCount = 0
                var toolsUsedThisTurn = false

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

                    // 1. Immediate Post-Prompt Reasoning Anchor
                    val initialLiveLog = LogEntry(
                        id = liveLogId,
                        sender = "AGENT",
                        text = "",
                        thinkingText = "",
                        isThinking = true
                    )
                    _uiState.update { it.copy(
                        logs = it.logs + initialLiveLog,
                        currentPhase = AgentActionPhase.THINKING,
                        activeStatusDetail = "Thinking & Planning...",
                        streamInterruptMsg = null
                    ) }

                    var continuationCycle = 1
                    var totalAccumulatedTokens = 0
                    var continueRecursion = true
                    var hitStorageLimit = false
                    val continuationPromptPayload = "Continue generation exactly from the last emitted token. Do not repeat prior lines. Continue directly with the remaining code/text."

                    try {
                        val historyCopy = synchronized(conversationHistory) {
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
                        var lastUiUpdateTime = 0L
                        var pendingUiUpdate = false

                        val pushUiUpdate = { rawText: String ->
                            val demuxed = StreamDemultiplexer.demux(rawText)
                            liveDiskStreamer.onChunk(rawText)

                            val hasCodeBlock = demuxed.blocks.any { it is StreamDemultiplexer.DemuxBlock.Code }
                            val isCodeStreaming = demuxed.blocks.any { it is StreamDemultiplexer.DemuxBlock.Code && !it.isClosed }
                            val detectedPath = demuxed.blocks.filterIsInstance<StreamDemultiplexer.DemuxBlock.Code>().lastOrNull()?.filePath ?: ToolExecutor.detectFilePath(rawText)
                            val isWritingFile = rawText.contains("<invoke name=\"write_to_file\"") || (detectedPath != null && isCodeStreaming)
                            val isToolInvoking = rawText.contains("<invoke")

                            val statusDetail = when {
                                demuxed.isThinking -> "Thinking & Planning..."
                                isWritingFile -> {
                                    val path = detectedPath ?: "/sdcard/Medlam-Ai/..."
                                    "Writing to $path..."
                                }
                                isToolInvoking -> {
                                    val toolName = Regex("<invoke name=\"([^\"]+)\"").find(rawText)?.groupValues?.get(1)
                                    if (toolName != null) "Executing Tool / Task ($toolName)..." else "Executing Tool / Task..."
                                }
                                isCodeStreaming || hasCodeBlock -> "Drafting Code & Architecture..."
                                demuxed.blocks.isNotEmpty() -> "Drafting Code & Architecture..."
                                else -> "Thinking & Planning..."
                            }

                            val currentPhase = when {
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
                                    streamVersion = state.streamVersion + 1,
                                    logs = updatedLogs
                                )
                            }
                        }

                        while (continueRecursion && !streamInterrupted) {
                            // Hardware Storage Sentinel Guardrail before continuation cycle
                            if (continuationCycle > 1) {
                                if (StorageSentinel.isStorageCritical()) {
                                    hitStorageLimit = true
                                    liveDiskStreamer.flushAndSeal()
                                    val sentinelMsg = "⚡ Storage Sentinel: Disk limit reached (< 500MB remaining). Stream gracefully sealed."
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
                                            activeStatusDetail = "⚡ Idle / Ready",
                                            currentPhase = AgentActionPhase.IDLE
                                        )
                                    }
                                    break
                                }
                            }

                            val cycleStartOffset = responseSb.length

                            val stream = when (modelType) {
                                ModelType.GGUF -> {
                                    val ggufHistory = if (continuationCycle == 1) {
                                        historyCopy
                                    } else {
                                        historyCopy + listOf(
                                            "assistant" to responseSb.toString(),
                                            "user" to continuationPromptPayload
                                        )
                                    }
                                    ggufClient!!.generateStreamWithHistory(
                                        history = ggufHistory,
                                        systemPrompt = systemPrompt,
                                        onSpeedUpdate = { tps ->
                                            _uiState.update { it.copy(tokensPerSecond = tps) }
                                        }
                                    )
                                }
                                ModelType.GEMINI -> {
                                    val json = JSONArray()
                                    historyCopy.forEachIndexed { i, (r, c) ->
                                        val m = JSONObject().put("role", r).put("content", c)
                                        if (i == historyCopy.size - 1 && imageBase64List.isNotEmpty() && continuationCycle == 1) {
                                            m.put("images", JSONArray(imageBase64List))
                                        }
                                        json.put(m)
                                    }
                                    if (continuationCycle > 1) {
                                        json.put(JSONObject().put("role", "model").put("content", responseSb.toString()))
                                        json.put(JSONObject().put("role", "user").put("content", continuationPromptPayload))
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

                            stream.collect { chunk ->
                                responseSb.append(chunk)
                                val now = System.currentTimeMillis()
                                // Real-time letter-by-letter stream: 33ms (~30 FPS) throttle gives instant smooth UI updates while preventing CPU starvation
                                if (now - lastUiUpdateTime >= 33L) {
                                    lastUiUpdateTime = now
                                    pushUiUpdate(responseSb.toString())
                                    pendingUiUpdate = false
                                } else {
                                    pendingUiUpdate = true
                                }
                            }

                            if (pendingUiUpdate) {
                                pushUiUpdate(responseSb.toString())
                            }

                            // Intercept finishReason at stream processor level
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
                                // Direct-to-Disk Stream Sinks: Commit tokens to storage & keep file open
                                liveDiskStreamer.flush()

                                val formattedChunkTokens = NumberFormat.getNumberInstance(Locale.US).format(chunkTokensEmitted)
                                val chunkFinalizedMsg = "⚡ Generation Chunk $continuationCycle finalized ($formattedChunkTokens tokens) -> Storage Synced"
                                val nextCycleMsg = "⚡ Auto-extending token stream [Cycle ${continuationCycle + 1}]..."
                                Log.i(TAG, "$chunkFinalizedMsg\n$nextCycleMsg")

                                _uiState.update { state ->
                                    state.copy(
                                        activeStatusDetail = "⚡ Auto-extending token stream [Cycle ${continuationCycle + 1}]...",
                                        currentPhase = AgentActionPhase.GENERATING_CODE,
                                        isProcessing = true
                                    )
                                }

                                continuationCycle++
                                continueRecursion = true
                            } else {
                                // Clean termination with STOP or natural stream completion
                                continueRecursion = false
                                val formattedTotalTokens = NumberFormat.getNumberInstance(Locale.US).format(totalAccumulatedTokens)
                                val completeMsg = "⚡ Execution Complete [Total Tokens: $formattedTotalTokens]"
                                Log.i(TAG, completeMsg)

                                _uiState.update { state ->
                                    state.copy(
                                        activeStatusDetail = "⚡ Ready [${formattedTotalTokens} tokens]"
                                    )
                                }
                            }
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

                    // Finalize and auto-flush any in-flight file streaming with full synthesis
                    val writtenFilesFromStream = liveDiskStreamer.closeAll(fullResponse)

                    if (hitStorageLimit) {
                        keepGoing = false
                        _uiState.update { state ->
                            state.copy(
                                isProcessing = false,
                                activeStatusDetail = "⚡ Idle / Ready",
                                currentPhase = AgentActionPhase.IDLE
                            )
                        }
                    }

                    if (fullResponse.isNotBlank()) {
                        // Strict No-Echo Protocol: Sanitize post-execution response to eliminate code regurgitation
                        val assistantReply = if (writtenFilesFromStream.isNotEmpty()) {
                            ToolExecutor.sanitizePostExecutionSummary(fullResponse, writtenFilesFromStream)
                        } else {
                            fullResponse
                        }
                        // Strip internal thinking tags before adding to conversation history to save tokens
                        val cleanHistoryReply = stripThinkingForHistory(assistantReply)
                        synchronized(conversationHistory) {
                            conversationHistory.add("assistant" to if (cleanHistoryReply.isNotBlank()) cleanHistoryReply else "[Execution in progress]")
                            while (conversationHistory.size > MAX_HISTORY_MESSAGES) conversationHistory.removeAt(0)
                        }

                        if (streamInterrupted) {
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
                            val (usedTools, summary) = executeToolLoop(fullResponse, actualContext)
                            val cleanSummary = summary ?: ""
                            if (usedTools) {
                                toolsUsedThisTurn = true
                                _uiState.update { it.copy(currentPhase = AgentActionPhase.EXECUTING_TOOL, activeStatusDetail = "Synthesizing tool results...") }
                                val proseOnly = cleanHistoryReply
                                    .replace(Regex("""<(?:tool_calls|function_calls)>[\s\S]*?</(?:tool_calls|function_calls)>""", RegexOption.IGNORE_CASE), "")
                                    .replace(Regex("""<invoke[\s\S]*?</invoke>""", RegexOption.IGNORE_CASE), "")
                                    .replace(Regex("""<(?:tool_calls|function_calls)>[\s\S]*$""", RegexOption.IGNORE_CASE), "")
                                    .replace(Regex("""<invoke[\s\S]*$""", RegexOption.IGNORE_CASE), "")
                                    .trim()
                                val toolResultText = when {
                                    proseOnly.isNotBlank() && cleanSummary.isNotBlank() && !proseOnly.contains(cleanSummary) -> "$proseOnly\n\n$cleanSummary"
                                    proseOnly.isNotBlank() -> proseOnly
                                    cleanSummary.isNotBlank() -> cleanSummary
                                    else -> ""
                                }
                                _uiState.update { state ->
                                    val updated = state.logs.map { log ->
                                        if (log.id == liveLogId) log.copy(text = toolResultText, thinkingText = demuxedFull.thinkingText, isThinking = false) else log
                                    }
                                    state.copy(logs = updated)
                                }
                                val sid = currentSessionId.value
                                val dbText = if (demuxedFull.thinkingText.isNotBlank()) "<think>${demuxedFull.thinkingText}</think>\n$toolResultText" else toolResultText
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

                                synchronized(conversationHistory) {
                                    val toolFeedback = "[System Tool Execution Output]:\n${summary ?: "Tool executed successfully."}\n\nPlease analyze the above results and present your complete final response to the user."
                                    conversationHistory.add("user" to toolFeedback)
                                    while (conversationHistory.size > MAX_HISTORY_MESSAGES) conversationHistory.removeAt(0)
                                }
                                keepGoing = true
                            } else {
                                _uiState.update { it.copy(currentPhase = AgentActionPhase.COMPLETING) }
                                val proseOnly = cleanHistoryReply
                                    .replace(Regex("""<(?:tool_calls|function_calls)>[\s\S]*?</(?:tool_calls|function_calls)>""", RegexOption.IGNORE_CASE), "")
                                    .replace(Regex("""<invoke[\s\S]*?</invoke>""", RegexOption.IGNORE_CASE), "")
                                    .replace(Regex("""<(?:tool_calls|function_calls)>[\s\S]*$""", RegexOption.IGNORE_CASE), "")
                                    .replace(Regex("""<invoke[\s\S]*$""", RegexOption.IGNORE_CASE), "")
                                    .trim()
                                val finalText = when {
                                    proseOnly.isNotBlank() && cleanSummary.isNotBlank() && !proseOnly.contains(cleanSummary) -> "$proseOnly\n\n$cleanSummary"
                                    proseOnly.isNotBlank() -> proseOnly
                                    cleanSummary.isNotBlank() -> cleanSummary
                                    else -> ""
                                }
                                _uiState.update { state ->
                                    val updated = state.logs.map { log ->
                                        if (log.id == liveLogId) log.copy(text = finalText, thinkingText = demuxedFull.thinkingText, isThinking = false) else log
                                    }
                                    state.copy(logs = updated)
                                }
                                val sid = currentSessionId.value
                                val dbText = if (demuxedFull.thinkingText.isNotBlank()) "<think>${demuxedFull.thinkingText}</think>\n$finalText" else finalText
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
                                keepGoing = false
                            }
                        }
                    } else {
                        keepGoing = false
                    }
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
            viewModelScope.launch {
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
            
            val systemPrompt = buildSystemPrompt(null, forLocalModel = modelType == ModelType.GGUF)
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
        userPrompt: String
    ): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val continuationPromptPayload = "Continue generation exactly from the last emitted token. Do not repeat prior lines. Continue directly with the remaining code/text."
        var continueRecursion = true
        var cycle = 1

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
                        }
                    }
                    ModelType.GGUF -> {
                        val history = if (cycle == 1) {
                            listOf("user" to userPrompt)
                        } else {
                            listOf("user" to userPrompt, "assistant" to sb.toString(), "user" to continuationPromptPayload)
                        }
                        ggufClient?.generateStreamWithHistory(history, systemPrompt)?.collect { chunk ->
                            sb.append(chunk)
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
        sb.toString()
    }

    private fun runProcessWithWatchdog(cmd: String, shell: String, timeoutMs: Long): Pair<Int, String> {
        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.redirectErrorStream(true) // Pipe deadlock prevention: merge stderr into stdout
        val env = pb.environment()
        val currentPath = env["PATH"] ?: "/system/bin:/system/xbin"
        env["PATH"] = "$currentPath:/system/bin:/system/xbin:/data/local/tmp"

        val process = pb.start()

        // Close stdin immediately: prevents interactive commands or subshells from waiting for user input
        try {
            process.outputStream.close()
        } catch (_: Exception) {}

        val outputSb = StringBuilder()
        val readerThread = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(2048)
                    var charsRead: Int
                    while (reader.read(buffer).also { charsRead = it } != -1) {
                        outputSb.append(buffer, 0, charsRead)
                        if (outputSb.length > 512 * 1024) {
                            outputSb.append("\n[Output truncated: exceeded 512KB]")
                            break
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        readerThread.isDaemon = true
        readerThread.start()

        val exitedInTime = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!exitedInTime) {
            Log.w(TAG, "Shell process timed out after ${timeoutMs}ms, destroying forcibly: $cmd")
            try {
                process.destroyForcibly()
            } catch (_: Exception) {}
            try {
                readerThread.join(500)
            } catch (_: Exception) {}
            val partial = outputSb.toString().trim()
            val timeoutMsg = "[Process Timed Out after ${timeoutMs / 1000}s - Process Terminated]"
            return -1 to (if (partial.isNotBlank()) "$timeoutMsg\nPartial output:\n$partial" else timeoutMsg)
        }

        try {
            readerThread.join(1000)
        } catch (_: Exception) {}
        val exitCode = try { process.exitValue() } catch (_: Exception) { 0 }
        return exitCode to outputSb.toString()
    }

    suspend fun runShellWithExitCode(cmd: String, timeoutMs: Long = 15_000L): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (cmd.isBlank()) return@withContext -1 to "Error: Shell command is blank"

        val tryRoot = _uiState.value.isRootAvailable
        try {
            if (tryRoot) {
                try {
                    val (exitCode, output) = runProcessWithWatchdog(cmd, "su", timeoutMs)
                    if (exitCode == 0 || (!output.contains("su: not found", ignoreCase = true) && !output.contains("permission denied", ignoreCase = true))) {
                        return@withContext exitCode to output.ifBlank { "[Command completed with exit code $exitCode (no output)]" }
                    }
                    Log.w(TAG, "su command returned exit code $exitCode or permission error, falling back to sh")
                } catch (e: Exception) {
                    Log.w(TAG, "su execution failed ($e), falling back to sh")
                }
            }

            val (exitCode, output) = runProcessWithWatchdog(cmd, "sh", timeoutMs)
            exitCode to output.ifBlank { "[Command completed with exit code $exitCode (no output)]" }
        } catch (e: Exception) {
            -1 to "Error executing command: ${e.message ?: e.javaClass.simpleName}"
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

        val clean = response.replace("```xml", "").replace("```", "").trim()
        val hasFunctionCalls = clean.contains("<function_calls>") || clean.contains("<tool_calls>")
        val hasInvoke = clean.contains("<invoke")

        if (!hasInvoke) return@withTimeoutOrNull false to null

        val block = if (hasFunctionCalls) {
            val start = if (clean.contains("<function_calls>")) clean.indexOf("<function_calls>") + 16 else clean.indexOf("<tool_calls>") + 12
            var b = clean.substring(start)
            val end = if (b.contains("</function_calls>")) b.indexOf("</function_calls>") else if (b.contains("</tool_calls>")) b.indexOf("</tool_calls>") else -1
            if (end != -1) b.substring(0, end) else b
        } else {
            clean
        }

        val summaries = mutableListOf<String>()
        var actionToolUsed = false
        try {
            val iRegex = Regex("<invoke name=[\"'](.*?)[\"']>(.*?)(?:</invoke>|$)", RegexOption.DOT_MATCHES_ALL)

            for (m in iRegex.findAll(block)) {
                val action = m.groupValues[1].trim().lowercase()
                val innerBody = m.groupValues[2].trim()
                _uiState.update { it.copy(activeStatusDetail = "Executing Tool / Task ($action)...") }
                val params = StreamDemultiplexer.extractToolParams(innerBody)

                // ── Idempotency Gate ──────────────────────────────────────────
                val callHash = computeCallHash(action, params)
                if (executedCallsThisTurn.contains(callHash) || inFlightCalls.contains(callHash)) {
                    Log.d(TAG, "Idempotency Lock: Suppressed duplicate tool call [$action] with hash $callHash")
                    val preview = when (action) {
                        "open_app", "launch_app", "start_app" -> {
                            val target = params["package_name"] ?: params["package"] ?: params["app_name"] ?: params["app"] ?: innerBody.trim()
                            resolvePackageName(target, actualContext?.packageManager)
                        }
                        "run_shell", "execute_shell", "terminal", "bash", "sh" -> {
                            params["command"] ?: params["cmd"] ?: innerBody.trim()
                        }
                        else -> params.values.firstOrNull() ?: innerBody.take(40)
                    }
                    val statusMsg = when (action) {
                        "open_app", "launch_app", "start_app" -> "$preview brought to front."
                        else -> "Identical operation already executed in active turn."
                    }
                    summaries.add("⚡ Tool($action: $preview) ─── [DUPLICATE SUPPRESSED]\n✔ Status: $statusMsg")
                    continue
                }

                inFlightCalls.add(callHash)
                try {
                    when (action) {
                        "open_app", "launch_app", "start_app" -> {
                            actionToolUsed = true
                            val target = params["package_name"] ?: params["package"] ?: params["app_name"] ?: params["app"] ?: params["name"] ?: innerBody.removeSurrounding("\"").trim()
                            if (target.isNotBlank()) {
                                val (success, appNameOrErr) = launchApplication(target, actualContext)
                                val resolvedPkg = resolvePackageName(target, actualContext?.packageManager)
                                if (success) {
                                    summaries.add("⚡ Tool(open_app: $resolvedPkg) ─── [LAUNCHED]\n✔ Status: $appNameOrErr brought to front.")
                                    addSystemLog("Native app launch: $appNameOrErr ($resolvedPkg)")
                                } else {
                                    summaries.add("⚡ Tool(open_app: $target) ─── [FAILED]\n✖ Status: $appNameOrErr")
                                    addSystemLog("Launch failed: $target ($appNameOrErr)")
                                }
                            }
                        }
                        "run_shell", "execute_shell", "terminal", "bash", "sh" -> {
                            val rawCmd = params["command"] ?: params["cmd"] ?: innerBody.removeSurrounding("\"").trim()
                            if (rawCmd.isNotBlank()) {
                                actionToolUsed = true
                                val trimmedCmd = rawCmd.trim()

                                // Fast-Path Native Intent Routing: Intercept am start for zero-latency app launch
                                if (trimmedCmd.startsWith("am start", ignoreCase = true)) {
                                    val extractedTarget = extractTargetFromAmStart(trimmedCmd)
                                    if (extractedTarget != null) {
                                        val openAppParams = mapOf("package_name" to extractedTarget)
                                        val openAppHash = computeCallHash("open_app", openAppParams)
                                        if (executedCallsThisTurn.contains(openAppHash) || inFlightCalls.contains(openAppHash)) {
                                            Log.d(TAG, "Idempotency Lock: Intercepted duplicate am start as open_app [$extractedTarget]")
                                            val resolvedPkg = resolvePackageName(extractedTarget, actualContext?.packageManager)
                                            summaries.add("⚡ Tool(open_app: $resolvedPkg) ─── [DUPLICATE SUPPRESSED]\n✔ Status: $resolvedPkg already brought to front in active turn.")
                                        } else {
                                            inFlightCalls.add(openAppHash)
                                            try {
                                                val (success, appLabel) = launchApplication(extractedTarget, actualContext)
                                                if (success) {
                                                    val resolvedPkg = resolvePackageName(extractedTarget, actualContext?.packageManager)
                                                    executedCallsThisTurn.add(openAppHash)
                                                    summaries.add("⚡ Tool(open_app: $resolvedPkg) ─── [LAUNCHED]\n✔ Status: $appLabel brought to front.")
                                                    addSystemLog("Native fast-path launch (intercepted am start): $appLabel ($resolvedPkg)")
                                                } else {
                                                    // Fallback to real shell execution if native intent fails
                                                    val (code, out) = runShellWithExitCode(rawCmd)
                                                    val safeCmd = StreamDemultiplexer.escapeXml(rawCmd)
                                                    val safeOut = StreamDemultiplexer.escapeXml(out.trim())
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
                                val safeOut = StreamDemultiplexer.escapeXml(output.trim())
                                summaries.add("""<terminal_frame command="$safeCmd" exit_code="$exitCode">$safeOut</terminal_frame>""")
                            }
                        }
                        "write_file", "write_to_file", "create_file", "edit_file" -> {
                            val path = params["path"] ?: params["file_path"] ?: params["target_file"] ?: params["filepath"] ?: ""
                            val content = params["content"] ?: params["code"] ?: params["text"] ?: ""
                            if (path.isNotBlank()) {
                                actionToolUsed = true
                                val resolved = ToolExecutor.resolvePath(path, ToolExecutor.BASE_DIR)
                                val f = File(resolved)
                                f.parentFile?.let { if (!it.exists()) it.mkdirs() }
                                f.writeText(content, java.nio.charset.StandardCharsets.UTF_8)
                                addSystemLog("File written natively: ${f.absolutePath} (${f.length()} bytes)")
                                summaries.add("⚡ Tool(write_file: ${f.name}) ─── [SAVED]\n✔ Status: Wrote ${f.length()} bytes to ${f.absolutePath}")
                            }
                        }
                        "web_search", "search", "google_search" -> {
                            val q = params["query"] ?: params["q"] ?: innerBody.removeSurrounding("\"").trim()
                            if (q.isNotBlank()) {
                                actionToolUsed = true
                                val searchRes = WebSearchTool.search(q)
                                summaries.add(searchRes)
                            }
                        }
                        "schedule_task" -> {
                            val desc = params["description"] ?: "Scheduled Task"
                            val cmd = params["command"] ?: params["cmd"] ?: innerBody.trim()
                            val delay = params["delay_minutes"]?.toIntOrNull() ?: 5
                            val tid = taskEngine?.queueCommand(cmd, source = "AGENT") ?: UUID.randomUUID().toString().take(8)
                            updateTaskState()
                            summaries.add("Scheduled background task [tsk #$tid in $delay min]: $desc ($cmd)")
                        }
                        "queue_background_task", "queue_task" -> {
                            val cmd = params["command"] ?: params["cmd"] ?: innerBody.trim()
                            val tid = taskEngine?.queueCommand(cmd, source = "AGENT") ?: UUID.randomUUID().toString().take(8)
                            updateTaskState()
                            summaries.add("Queued background task [tsk #$tid]: $cmd")
                        }
                        "complete" -> {
                            val s = params["summary"] ?: innerBody
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
            val systemPrompt = buildSystemPrompt(context)
            ggufClient?.warmupPersonaKVCache(systemPrompt)
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
            val systemPrompt = buildSystemPrompt(context)
            ggufClient?.warmupPersonaKVCache(systemPrompt)
        }
    }

    fun setActivePersona(personaName: String, context: Context) {
        togglePersona(personaName, context)
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
                .filter { !it.text.startsWith("⚡ Execution Complete") && !it.text.startsWith("⚡ Generation Chunk") }
            val restoredLogs = messages.map { msg ->
                val parsed = parseThoughtAndContent(msg.text)
                val logText = if (msg.sender == "AGENT") {
                    if (parsed.content.isNotBlank()) parsed.content else if (parsed.thought.isNotBlank()) "" else msg.text
                } else {
                    msg.text
                }
                LogEntry(
                    id = msg.id,
                    sender = msg.sender,
                    text = logText,
                    isError = msg.isError,
                    timestamp = msg.timestamp,
                    thinkingText = parsed.thought,
                    isThinking = false
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

    fun stopAgent() { currentJob?.cancel(); currentJob = null; _uiState.update { it.copy(isProcessing = false) } }

    fun addSystemLog(text: String) { Log.i(TAG, "SystemLog: $text") }
    
    private fun prefs(context: Context) = context.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)
    
    private fun addLog(entry: LogEntry) { _uiState.update { it.copy(logs = it.logs + entry) } }

    fun dismissResumptionNotice() {
        _uiState.update { it.copy(resumptionNotice = null) }
    }

    override fun onCleared() { 
        super.onCleared()
        taskEngine?.stop()
        closeAllModels() 
    }
}

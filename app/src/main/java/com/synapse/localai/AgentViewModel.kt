package com.synapse.localai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.net.Uri
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
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
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.io.File
import java.util.*
import com.synapse.localai.tools.YouTubeTool
import com.synapse.localai.tools.DocumentTool

enum class ModelType { GGUF, GEMINI, OPENAI }

/** Dynamic phase tracking for in-stream status indicators */
enum class AgentActionPhase {
    IDLE,
    THINKING,
    PLANNING,
    GENERATING_CODE,
    WRITING_FILE,
    EXECUTING_TOOL,
    COMPLETING
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
    val geminiApiKeys: String = "",
    val geminiModelName: String = "gemini-1.5-flash",
    val openaiApiKeys: String = "",
    val openAiModelName: String = "gpt-4o",
    val serverHost: String = "0.0.0.0",
    val serverPort: Int = 11434,
    val contextLength: Int = 8192,
    val threadCount: Int = 4,
    val maxTokens: Int = -1,
    val temperature: Float = 0.7f,
    // Removed showFloatingBubble
    val enableVisualHistory: Boolean = false,
    val enableNeuralReflexes: Boolean = false,
    val enabledSpecializedAgents: Set<String> = emptySet(),
    val systemPrompt: String = "",
    val customToolsPrompt: String = ""
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
    val isAccessibilityActive: Boolean = false,
    val isKeyboardActive: Boolean = false,
    val isNotificationActive: Boolean = false,
    val streamInterruptMsg: String? = null,
    val tokensPerSecond: Float = 0f
)

class AgentViewModel : ViewModel() {

    companion object {
        private const val TAG = "SYNAPSE_CORE"
        private const val MAX_HISTORY_MESSAGES = 10  // Reduced: saves quota & memory
        private const val MAX_SCREEN_CONTEXT_MESSAGES = 4
        private const val MAX_TASK_ITERATIONS = 15     // Prevent infinite loops
        private const val AGENTS_DIR = "/sdcard/Medlam-Ai/Agents"
        
        var instance: AgentViewModel? = null
            private set

        fun detectModelType(path: String): ModelType {
            return ModelType.GGUF
        }
    }

    init {
        instance = this
    }

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    // Task Memory & Background Engine
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
    private var openAiClient: OpenAiModelClient? = null
    private var currentJob: Job? = null
    private var schedulerJob: Job? = null
    private var visualMemoryJob: Job? = null
    private val scheduledTasks = Collections.synchronizedList(mutableListOf<ScheduledTask>())
    private val workflows = Collections.synchronizedMap(mutableMapOf<String, String>())
    
    // Command queue for concurrent requests
    private val pendingCommands = Collections.synchronizedList(mutableListOf<String>())
    
    private var coreMission: String = ""
    private val conversationHistory = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    // Separate screen context from conversation to prevent history pollution
    private var lastScreenContext: String = ""

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



    fun startVisualMemoryCycle(context: Context) {
    }

    fun loadConfig(context: Context) {
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
            val hw = DeviceProfile.detect(context)
            val isLowRam = hw.totalRamMb < 4000
            val maxCores = Runtime.getRuntime().availableProcessors()
            
            var savedContext = prefs.getInt("context_length", 8192)
            if (savedContext < 8192) {
                savedContext = 8192 // Force 8192 as requested
            }

            var savedThreads = prefs.getInt("thread_count", maxCores)
            if (savedThreads < maxCores) {
                savedThreads = maxCores
                addSystemLog("⚡ High Performance: Using $maxCores CPU cores.")
            }
            
            val modelTypeStr = prefs.getString("preferred_model_type", ModelType.GEMINI.name)
            val modelType = try { ModelType.valueOf(modelTypeStr!!) } catch (_: Exception) { ModelType.GEMINI }
            var geminiKeys = prefs.getString("gemini_api_keys", "") ?: ""
            var geminiModel = prefs.getString("gemini_model_name", "gemini-1.5-flash") ?: "gemini-1.5-flash"
            
            var openaiKeys = prefs.getString("openai_api_keys", "") ?: ""
            var openAiModel = prefs.getString("openai_model_name", "gpt-4o") ?: "gpt-4o"
            val draftPath = prefs.getString("draft_model_path", "") ?: ""
            val gpuLayers = prefs.getInt("gpu_layers", 0)
            val useMmap = prefs.getBoolean("use_mmap", true)
            
            val config = AgentConfig(
                preferredModelType = modelType,
                localModelPath = savedPath ?: "",
                draftModelPath = draftPath,
                gpuLayers = gpuLayers,
                useMmap = useMmap,
                geminiApiKeys = geminiKeys,
                geminiModelName = geminiModel,
                openaiApiKeys = openaiKeys,
                openAiModelName = openAiModel,
                serverHost = prefs.getString("server_host", "0.0.0.0") ?: "0.0.0.0",
                serverPort = prefs.getInt("server_port", 11434),
                contextLength = savedContext,
                threadCount = savedThreads,
                maxTokens = prefs.getInt("max_tokens", -1),
                temperature = prefs.getFloat("temperature", 0.7f),
                // showFloatingBubble removed
                enableVisualHistory = prefs.getBoolean("enable_visual_history", false),
                enableNeuralReflexes = prefs.getBoolean("enable_neural_reflexes", false),
                enabledSpecializedAgents = prefs.getStringSet("enabled_agents", emptySet()) ?: emptySet(),
                systemPrompt = prefs.getString("system_prompt", "") ?: "",
                customToolsPrompt = prefs.getString("custom_tools_prompt", "") ?: ""
            )
            _uiState.update { it.copy(config = config) }

            val activeGeminiKeyCount = geminiKeys.split(",").filter { it.trim().isNotEmpty() }.size
            val activeOpenAiKeyCount = openaiKeys.split(",").filter { it.trim().isNotEmpty() }.size
            
            if (modelType == ModelType.GEMINI && activeGeminiKeyCount == 0) {
                addLog(LogEntry(sender = "SYSTEM", text = "⚠️ NO API KEYS: Go to Settings to add your Gemini keys.", isError = true))
            } else if (modelType == ModelType.OPENAI && activeOpenAiKeyCount == 0) {
                addLog(LogEntry(sender = "SYSTEM", text = "⚠️ NO API KEYS: Go to Settings to add your OpenAI keys.", isError = true))
            } else {
                val activeKeys = if (modelType == ModelType.OPENAI) activeOpenAiKeyCount else activeGeminiKeyCount
                addSystemLog("⚡ System Online ($modelType). Active Keys: $activeKeys")
            }

            // Consolidate legacy directories to single /sdcard/Medlam-Ai/ standard
            consolidateWorkspaceDirectories()

            // Initialize Chat Database & Sessions
            if (chatDb == null) {
                chatDb = ChatDatabaseHelper(context)
            }
            refreshSessions()
            val existingSessions = chatDb?.getAllSessions() ?: emptyList()
            if (existingSessions.isNotEmpty()) {
                val latest = existingSessions.first()
                switchSession(latest.id)
            } else {
                startNewSession()
            }

            loadWorkflows(prefs)
            
            // Initialize persistent task system
            initTaskSystem(context)
            startScheduler()
            
            try {
                File(AGENTS_DIR).mkdirs()
            } catch (_: Exception) {}
            
            scanSpecializedAgents()
            
            if (config.enableVisualHistory) {
                startVisualMemoryCycle(context)
            }

            // Bubble visible call removed

            if (config.preferredModelType == ModelType.GGUF && config.localModelPath.isNotBlank()) {
                viewModelScope.launch {
                    triggerModelLoad(context, config.localModelPath)
                }
            } else if (config.preferredModelType == ModelType.GEMINI && config.geminiApiKeys.isNotBlank()) {
                loadGeminiModel(config.geminiApiKeys, config.geminiModelName)
            } else if (config.preferredModelType == ModelType.OPENAI && config.openaiApiKeys.isNotBlank()) {
                loadOpenAiModel(config.openaiApiKeys, config.openAiModelName)
            }


            updateServiceStatus(context)
        } catch (e: Exception) {
            Log.e(TAG, "initialize failed", e)
            addLog(LogEntry(sender = "SYSTEM", text = "⚠️ Boot Error: ${e.message}", isError = true))
        }
    }

    fun updateServiceStatus(context: Context) {
        val isAccessibilityActive = SynapseAccessibilityService.instance != null
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val enabledImes = imm.enabledInputMethodList
        val className = SynapseKeyboardIME::class.java.name
        val pkgName = context.packageName
        
        // PROFESSOR FIX: Scan all enabled IMEs for our specific service. 
        // Handles 'pkg/class', 'pkg/.class', and other system variations.
        val isKeyboardActive = enabledImes.any { 
            it.packageName == pkgName && it.serviceName.contains("SynapseKeyboardIME")
        } || SynapseKeyboardIME.instance != null

        val isNotificationActive = try {
            val enabledListeners = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            enabledListeners.contains(context.packageName)
        } catch (e: Exception) {
            false
        }

        _uiState.update { it.copy(
            isAccessibilityActive = isAccessibilityActive,
            isKeyboardActive = isKeyboardActive,
            isNotificationActive = isNotificationActive
        ) }
    }

    fun saveConfig(context: Context, config: AgentConfig) {
        val hw = DeviceProfile.detect(context)
        val isLowRam = hw.totalRamMb < 4000
        
        val finalConfig = if (config.contextLength < 4096) {
            config.copy(contextLength = 4096)
        } else config

        val oldConfig = _uiState.value.config
        val oldPath = oldConfig.localModelPath
        val oldGeminiKeys = oldConfig.geminiApiKeys
        val oldOpenAiKeys = oldConfig.openaiApiKeys
        val oldType = oldConfig.preferredModelType
        
        val perfChanged = finalConfig.contextLength != oldConfig.contextLength ||
                         finalConfig.threadCount != oldConfig.threadCount ||
                         finalConfig.maxTokens != oldConfig.maxTokens ||
                         finalConfig.temperature != oldConfig.temperature ||
                         finalConfig.gpuLayers != oldConfig.gpuLayers ||
                         finalConfig.useMmap != oldConfig.useMmap ||
                         finalConfig.draftModelPath != oldConfig.draftModelPath

        _uiState.update { it.copy(config = finalConfig) }
        val prefs = prefs(context)
        prefs.edit().apply {
            putString("local_model_path", finalConfig.localModelPath)
            putString("draft_model_path", finalConfig.draftModelPath)
            putInt("gpu_layers", finalConfig.gpuLayers)
            putBoolean("use_mmap", finalConfig.useMmap)
            putString("preferred_model_type", finalConfig.preferredModelType.name)
            putString("gemini_api_keys", finalConfig.geminiApiKeys)
            putString("gemini_model_name", finalConfig.geminiModelName)
            putString("openai_api_keys", finalConfig.openaiApiKeys)
            putString("openai_model_name", finalConfig.openAiModelName)
            putString("server_host", finalConfig.serverHost)
            putInt("server_port", finalConfig.serverPort)
            putInt("context_length", finalConfig.contextLength)
            putInt("thread_count", finalConfig.threadCount)
            putInt("max_tokens", finalConfig.maxTokens)
            putFloat("temperature", finalConfig.temperature)
            // putBoolean show_floating_bubble removed
            putBoolean("enable_visual_history", finalConfig.enableVisualHistory)
            putBoolean("enable_neural_reflexes", finalConfig.enableNeuralReflexes)
            putStringSet("enabled_agents", finalConfig.enabledSpecializedAgents)
            putString("system_prompt", finalConfig.systemPrompt)
            putString("custom_tools_prompt", finalConfig.customToolsPrompt)
            apply()
        }

        // Bubble visible call removed
        
        if (finalConfig.enableVisualHistory) {
            startVisualMemoryCycle(context)
        } else {
            visualMemoryJob?.cancel()
            visualMemoryJob = null
        }

        if (finalConfig.preferredModelType == ModelType.GGUF) {
            if (finalConfig.localModelPath != oldPath || !isModelLoaded() || oldType != ModelType.GGUF || perfChanged) {
                closeAllModels()
                viewModelScope.launch {
                    delay(1000)
                    triggerModelLoad(context, finalConfig.localModelPath)
                }
            }
        } else if (finalConfig.preferredModelType == ModelType.GEMINI) {
            if (finalConfig.geminiApiKeys != oldGeminiKeys || 
                finalConfig.geminiModelName != oldConfig.geminiModelName ||
                !isModelLoaded() || oldType != ModelType.GEMINI) {
                closeAllModels()
                loadGeminiModel(finalConfig.geminiApiKeys, finalConfig.geminiModelName)
            }
        } else if (finalConfig.preferredModelType == ModelType.OPENAI) {
            if (finalConfig.openaiApiKeys != oldOpenAiKeys || 
                finalConfig.openAiModelName != oldConfig.openAiModelName ||
                !isModelLoaded() || oldType != ModelType.OPENAI) {
                closeAllModels()
                loadOpenAiModel(finalConfig.openaiApiKeys, finalConfig.openAiModelName)
            }
        }
    }

    fun updateConfig(config: AgentConfig, context: Context) = saveConfig(context, config)

    suspend fun triggerModelLoad(context: Context, path: String) {
        if (_uiState.value.localModelLoading) return
        val type = detectModelType(path)
        val modelName = java.io.File(path).nameWithoutExtension
        
        _uiState.update { it.copy(localModelLoading = true, localModelLoaded = false, activeModelType = null) }
        
        if (type == ModelType.GGUF) {
            try {
                val config = _uiState.value.config
                val client = GGUFModelClient().apply {
                    serverHost = config.serverHost
                    serverPort = config.serverPort
                    contextLength = config.contextLength
                    threadCount = config.threadCount
                    maxTokens = config.maxTokens
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
                    onLoaded = {
                        if (client.isLoaded) {
                            _uiState.update { it.copy(localModelLoaded = true, localModelLoading = false, activeModelType = ModelType.GGUF, activeModelName = modelName) }
                            addSystemLog("✅ Local Model Active: $modelName")
                        } else {
                            _uiState.update { it.copy(localModelLoaded = false, localModelLoading = false) }
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.e(TAG, "GGUF load failed", t)
                addLog(LogEntry(sender = "SYSTEM", text = "❌ Load Error: ${t.message}", isError = true))
                _uiState.update { it.copy(localModelLoaded = false, localModelLoading = false) }
            }
        }
    }

    private fun loadGeminiModel(apiKeysRaw: String, modelName: String = "gemini-1.5-flash") {
        val keys = apiKeysRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (keys.isEmpty()) return
        
        val initialIdx = MainActivity.instance?.let { prefs(it).getInt("current_key_index", 0) } ?: 0
        
        closeAllModels()
        geminiClient = GeminiModelClient(
            apiKeys = keys,
            initialKeyIndex = initialIdx,
            onKeyRotated = { idx ->
                _uiState.update { it.copy(currentKeyIndex = idx) }
                val ctx = SynapseAccessibilityService.instance ?: MainActivity.instance
                if (ctx != null) {
                    ctx.applicationContext.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)
                        .edit().putInt("current_key_index", idx).apply()
                }
            },
            onKeyUsed = { key ->
                val ctx = SynapseAccessibilityService.instance ?: MainActivity.instance
                if (ctx != null) {
                    KeyUsageManager.incrementUsage(ctx.applicationContext, key)
                }
            }
        )
        geminiClient?.modelName = modelName
        geminiClient?.isLoaded = true
        addSystemLog("⚡ AI Services Initialized")

        _uiState.update { it.copy(activeModelType = ModelType.GEMINI, activeModelName = modelName, totalKeys = keys.size, currentKeyIndex = initialIdx % keys.size) }
    }

    private fun loadOpenAiModel(apiKeysRaw: String, modelName: String = "gpt-4o") {
        val keys = apiKeysRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (keys.isEmpty()) return
        
        val initialIdx = MainActivity.instance?.let { prefs(it).getInt("current_key_index", 0) } ?: 0
        
        closeAllModels()
        openAiClient = OpenAiModelClient(keys, initialIdx) { idx ->
            _uiState.update { it.copy(currentKeyIndex = idx) }
            // Persist the successful key index
            val ctx = SynapseAccessibilityService.instance ?: MainActivity.instance
            if (ctx != null) {
                ctx.applicationContext.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)
                    .edit().putInt("current_key_index", idx).apply()
            }
        }
        openAiClient?.modelName = modelName
        _uiState.update { it.copy(activeModelType = ModelType.OPENAI, activeModelName = modelName, totalKeys = keys.size, currentKeyIndex = initialIdx % keys.size) }
    }

    private fun isModelLoaded(): Boolean {
        return (ggufClient != null && ggufClient!!.isLoaded) || 
               (geminiClient != null && geminiClient!!.isLoaded) || 
               (openAiClient != null && openAiClient!!.isLoaded)
    }

    fun closeAllModels() {
        ggufClient?.close()
        ggufClient = null
        geminiClient = null
        openAiClient = null
        _uiState.update { it.copy(localModelLoaded = false, activeModelType = null, activeModelName = "", currentKeyIndex = 0, totalKeys = 0) }
    }

    private fun getToolsDescription(): String {
        return """
## Available Tools & Action Format
You MUST wrap actions in <function_calls> and <invoke> tags.
Example: <function_calls><invoke name="click"><parameter name="index">5</parameter></invoke></function_calls>

Available Actions:
1. `open_app(app_name: String)`: Launch any app by name (e.g., "Chrome", "Facebook").
2. `click(index: Int)`: Click element by screen tree index.
3. `type(text: String)`: Type text into focused field.
4. `swipe(direction: String)`: "up", "down", "left", "right".
5. `go_back()`: System back.
6. `go_home()`: System home.
7. `press_enter()`: Simulate pressing the Enter/Search key on the keyboard. Use this immediately after typing to submit a search or form.
7. `read_notifications()`: Get recent notifications.
8. `complete(summary: String)`: End mission and report to user.
9. `schedule_task(description: String, command: String, delay_minutes: Int)`: Schedule a task to run after delay_minutes. The task is persistent and will survive app restart.
10. `add_task(description: String, command: String, priority: Int)`: Add an immediate task to the background queue (priority: 0=normal, 1=high, 2=urgent).
11. `list_tasks()`: Show all active and recent tasks with their statuses.
12. `cancel_task(task_id: String)`: Cancel a pending or queued task by its ID.
13. `remember(key: String, value: String)`: Save important info to persistent memory for later recall.
14. `recall(key: String)`: Retrieve previously saved info from persistent memory.

## Web Browsing Tip
To browse a site (e.g. Binance):
- `open_app("Chrome")`
- `click` on the URL bar (often index 0 or similar)
- `type` the URL.
- Invoke `press_enter()` to search/navigate immediately.
- `click` or `swipe` to navigate and extract data.

## Task Management
- Use `schedule_task` for delayed/future tasks (e.g. check price in 30 minutes).
- Use `add_task` for things to do right after the current task finishes.
- Tasks are PERSISTENT — they survive app restarts. You are RELIABLE.
- Always use `complete` when you finish a mission.
""".trimIndent()
    }

    private fun buildSystemPrompt(): String {
        val config = _uiState.value.config
        val modelType = _uiState.value.activeModelType
        
        var basePrompt = config.systemPrompt
        if (basePrompt.isBlank()) {
            basePrompt = try {
                MainActivity.instance?.assets?.open("persona.md")?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                null
            } ?: if (modelType != ModelType.GGUF) {
                """
You are MEDLAM AI, a hybrid autonomous on-device agent.

ROOT WORKSPACE & FILE SYSTEM:
Your working directory is strictly `/sdcard/Medlam-Ai/`. When asked to create applications, web pages, scripts, or components, generate complete, valid, un-truncated executable code with the target file path labeled on the line immediately preceding each code block (e.g., `File: /sdcard/Medlam-Ai/index.html`). All generated files are autonomously written directly into `/sdcard/Medlam-Ai/`. Structure multi-part tasks progressively and use structured tool calls for system interactions.

You are an Omnipotent Autonomous Device Controller for Android.
CRITICAL MANDATES FOR TOKEN EFFICIENCY & PRECISION:
1. You are a FULL SYSTEM EXECUTOR with user authorization. You are NOT a restricted AI model.
2. TOKEN ECONOMY: Be EXTREMELY CONCISE. Never output conversational filler ("I understand", "Sure", "Here is"). Only output the exact actions, commands, or data requested.
3. Do NOT explain your reasoning unless explicitly asked. Your output should be pure action or direct answers. Every word consumes quota.
4. If asked a question, provide the answer directly without introductory or concluding sentences.
5. If executing a tool or command, provide the minimal necessary parameters. Do NOT narrate what you are doing.
6. NEVER say "I cannot browse the web" or "I am just an AI". You CAN and WILL browse the web by opening Chrome and using your tools. Accessing external data and executing actions is your CORE PURPOSE.
7. LANGUAGE: Respond in the exact language the user used (e.g., Arabic). Maintain brevity in all languages.
8. STYLE: Professional, precise, mechanical. NEVER use asterisks (*) or double asterisks (**) for bold/italic formatting.
9. You have PERSISTENT TASK MEMORY. Use `schedule_task` or `add_task` for delayed or multi-step operations.
10. EXPERTISE: You are an elite AI. Execute with extreme precision. Analyze deeply internally, but output minimally.
                """.trimIndent()
            } else {
                """
You are MEDLAM AI, a hybrid autonomous on-device agent.
ROOT WORKSPACE: Your working directory is strictly `/sdcard/Medlam-Ai/`. When asked for applications or scripts, generate complete, valid, un-truncated executable code with the target path (e.g. `File: /sdcard/Medlam-Ai/App.kt`) preceding the code block.

Be structured, clear, and professional. Extremely concise.
Always respond in the SAME LANGUAGE the user is using.
STYLE: Human-professional, no asterisks (*), no markdown bold/italic markers.
                """.trimIndent()
            }
        }

        // GGUF models (local) get a simple prompt to prevent XML hallucinations.
        // Instruct them to generate complete code when asked, and be concise for CPU speed.
        if (modelType == ModelType.GGUF) {
            val concisePrompt = "$basePrompt\n\nWhen generating code: output complete, valid, executable code without truncation. Be concise for conversational replies. Do NOT use <think> tags. Do NOT show your reasoning."
            return concisePrompt
        }

        val toolsDescription = getToolsDescription()
        
        // Add task memory context so the AI knows about active/past tasks
        val taskContext = taskMemory?.getContextForAI() ?: ""
        val taskSection = if (taskContext.isNotBlank()) "\n\n$taskContext" else ""
        
        // Add persistent memory context
        val memoryContext = getPersistentMemoryContext()
        val memorySection = if (memoryContext.isNotBlank()) "\n\n$memoryContext" else ""

        // Add long-term brain memory across past sessions
        val brainContext = chatDb?.getRecentConversationsSummary(2) ?: ""
        val brainSection = if (brainContext.isNotBlank()) "\n\n$brainContext" else ""
        
        return "$basePrompt\n\n$toolsDescription$taskSection$memorySection$brainSection"
    }


    fun sendCommand(command: String, fileUri: Uri? = null, context: Context? = null) {
        if (command.isBlank() && fileUri == null) return
        
        // Instead of silently dropping, queue commands if busy
        if (_uiState.value.isProcessing) {
            synchronized(pendingCommands) {
                pendingCommands.add(command)
            }
            addLog(LogEntry(sender = "SYSTEM", text = "⏳ Command queued (${pendingCommands.size} in queue)"))
            return
        }
        
        val actualContext = context ?: MainActivity.instance
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
        coreMission = command

        val service = SynapseAccessibilityService.instance
        currentJob = viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            
            try {
                var finalCommand = command
                val imageBase64List = mutableListOf<String>()

                if (finalCommand.contains("[ACTION BUBBLE]") || finalCommand.contains("capture screen", ignoreCase = true)) {
                    val screenshotSignal = java.util.concurrent.CountDownLatch(1)
                    service?.takeScreenshotBase64 { b64 -> if (b64 != null) imageBase64List.add(b64); screenshotSignal.countDown() }
                    withContext(Dispatchers.IO) { screenshotSignal.await(5, java.util.concurrent.TimeUnit.SECONDS) }
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
                    addLog(LogEntry(sender = "SYSTEM", text = "Engine not ready.", isError = true))
                    return@launch
                }

                // Smart screen context: stored separately to avoid polluting conversation history
                if (modelType != ModelType.GGUF) {
                    lastScreenContext = "\n[Current State]:\n${service?.getDroidrunPhoneState()}\n\n${service?.getDroidrunScreenTree()}"
                } else {
                    lastScreenContext = ""
                }
                
                // Add user message to conversation (WITHOUT screen data to save history space)
                synchronized(conversationHistory) {
                    conversationHistory.add("user" to finalCommand)
                }

                val systemPrompt = buildSystemPrompt()
                var keepGoing = true
                var iterationCount = 0
                var toolsUsedThisTurn = false

                // Pre-switch keyboard before task starts so it's ready for any type actions
                if (service != null && !SynapseKeyboardIME.isSelected(service)) {
                    service.switchToSynapseKeyboard()
                    delay(1500)
                }

                while (keepGoing && iterationCount < MAX_TASK_ITERATIONS) {
                    iterationCount++
                    val responseSb = StringBuilder()
                    // Track thinking vs content separately
                    var inThinkBlock = false
                    var streamInterrupted = false
                    var interruptMsg = ""

                    try {
                        // Build history with screen context appended only to the LAST user message
                        val historyCopy = synchronized(conversationHistory) { conversationHistory.takeLast(MAX_HISTORY_MESSAGES).toList() }
                        val historyWithScreen = historyCopy.toMutableList()
                        if (lastScreenContext.isNotBlank() && historyWithScreen.isNotEmpty()) {
                            val lastIdx = historyWithScreen.indexOfLast { it.first == "user" }
                            if (lastIdx >= 0) {
                                val (role, content) = historyWithScreen[lastIdx]
                                historyWithScreen[lastIdx] = role to content + lastScreenContext
                            }
                        }

                        // Update phase to THINKING when stream begins
                        _uiState.update { it.copy(currentPhase = AgentActionPhase.THINKING, streamInterruptMsg = null) }

                        val stream = when (modelType) {
                            ModelType.GGUF -> ggufClient!!.generateStreamWithHistory(
                                history = historyWithScreen,
                                systemPrompt = systemPrompt,
                                onSpeedUpdate = { tps ->
                                    _uiState.update { it.copy(tokensPerSecond = tps) }
                                }
                            )
                            ModelType.GEMINI -> {
                                val json = JSONArray()
                                historyWithScreen.forEachIndexed { i, (r, c) ->
                                    val m = JSONObject().put("role", r).put("content", c)
                                    if (i == historyWithScreen.size - 1 && imageBase64List.isNotEmpty()) m.put("images", JSONArray(imageBase64List))
                                    json.put(m)
                                }
                                geminiClient!!.generateStreamWithOllamaMessages(json, systemPrompt) {
                                    // onRetry: do NOT wipe responseSb — retain partial output
                                }
                            }
                            ModelType.OPENAI -> {
                                val json = JSONArray()
                                historyWithScreen.forEachIndexed { i, (r, c) ->
                                    val m = JSONObject().put("role", r).put("content", c)
                                    if (i == historyWithScreen.size - 1 && imageBase64List.isNotEmpty()) m.put("images", JSONArray(imageBase64List))
                                    json.put(m)
                                }
                                openAiClient!!.generateStreamWithOllamaMessages(json, systemPrompt) {
                                    // onRetry: do NOT wipe responseSb — retain partial output
                                }
                            }
                        }

                        stream.collect { chunk ->
                            responseSb.append(chunk)
                            // Detect and update phase based on content tokens
                            val currentText = responseSb.toString()
                            when {
                                currentText.contains("<think>") && !currentText.contains("</think>") -> {
                                    inThinkBlock = true
                                    _uiState.update { it.copy(currentPhase = AgentActionPhase.THINKING) }
                                }
                                inThinkBlock && currentText.contains("</think>") -> {
                                    inThinkBlock = false
                                    _uiState.update { it.copy(currentPhase = AgentActionPhase.GENERATING_CODE) }
                                }
                                currentText.contains("```") -> {
                                    _uiState.update { it.copy(currentPhase = AgentActionPhase.GENERATING_CODE) }
                                }
                                currentText.contains("<invoke") -> {
                                    _uiState.update { it.copy(currentPhase = AgentActionPhase.EXECUTING_TOOL) }
                                }
                                currentText.contains("<complete") -> {
                                    _uiState.update { it.copy(currentPhase = AgentActionPhase.COMPLETING) }
                                }
                                else -> {
                                    if (!inThinkBlock) {
                                        _uiState.update { it.copy(currentPhase = AgentActionPhase.PLANNING) }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // RESILIENT: Do NOT wipe console or break on stream errors.
                        // Surface an inline status banner and retain whatever was collected.
                        streamInterrupted = true
                        interruptMsg = e.message ?: e.javaClass.simpleName
                        Log.w(TAG, "Stream interrupted: $interruptMsg")
                        // Append interrupt marker to collected text so user sees what happened
                        if (responseSb.isNotBlank()) {
                            responseSb.append("\n\n[Stream Interrupted: $interruptMsg]")
                        }
                        _uiState.update { it.copy(streamInterruptMsg = interruptMsg) }
                    }

                    val fullResponse = responseSb.toString()
                    if (fullResponse.isNotBlank()) {
                        // Strip thinking blocks from the reply stored in history, but keep full response for tool parsing
                        val assistantReply = fullResponse
                            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
                            .replace(Regex("^💭.*?\n\n", RegexOption.DOT_MATCHES_ALL), "")
                            .trim()
                        synchronized(conversationHistory) {
                            conversationHistory.add("assistant" to assistantReply)
                            while (conversationHistory.size > MAX_HISTORY_MESSAGES) conversationHistory.removeAt(0)
                        }

                        if (streamInterrupted) {
                            // Show whatever partial content we have, with interrupt notice, then stop loop
                            val agentLog = LogEntry(sender = "AGENT", text = assistantReply.ifBlank { "[Stream Interrupted: $interruptMsg]" })
                            addLog(agentLog)
                            val sid = currentSessionId.value
                            viewModelScope.launch(Dispatchers.IO) {
                                chatDb?.insertMessage(
                                    ChatMessageEntity(
                                        id = agentLog.id,
                                        sessionId = sid,
                                        sender = "AGENT",
                                        text = agentLog.text,
                                        timestamp = agentLog.timestamp
                                    )
                                )
                                refreshSessions()
                            }
                            keepGoing = false
                        } else {
                            val (usedTools, summary) = executeToolLoop(fullResponse)
                            if (usedTools) {
                                toolsUsedThisTurn = true
                                _uiState.update { it.copy(currentPhase = AgentActionPhase.EXECUTING_TOOL) }
                                // Update screen context for next iteration
                                if (_uiState.value.activeModelType != ModelType.GGUF) {
                                    lastScreenContext = "\n[Current State]:\n${service?.getDroidrunPhoneState()}\n\n${service?.getDroidrunScreenTree()}"
                                }
                                keepGoing = true
                            } else {
                                // Final Result: Show the summary if provided, otherwise the text reply
                                _uiState.update { it.copy(currentPhase = AgentActionPhase.COMPLETING) }
                                val finalText = summary ?: assistantReply
                                val agentLog = LogEntry(sender = "AGENT", text = finalText)
                                addLog(agentLog)
                                val sid = currentSessionId.value
                                viewModelScope.launch(Dispatchers.IO) {
                                    chatDb?.insertMessage(
                                        ChatMessageEntity(
                                            id = agentLog.id,
                                            sessionId = sid,
                                            sender = "AGENT",
                                            text = finalText,
                                            timestamp = agentLog.timestamp
                                        )
                                    )
                                    refreshSessions()
                                }
                                keepGoing = false
                            }
                        }
                    } else {
                        if (streamInterrupted) {
                            addLog(LogEntry(sender = "SYSTEM", text = "⚠️ Stream interrupted: $interruptMsg", isError = true))
                        }
                        keepGoing = false
                    }
                }

                // Cleanup after all iterations
                if (toolsUsedThisTurn) {
                    viewModelScope.launch {
                        delay(1000)
                        val currentPkg = service?.rootInActiveWindow?.packageName?.toString()
                        if (currentPkg != null && currentPkg != "com.synapse.localai") {
                             service?.launchApp("com.synapse.localai")
                             delay(1500)
                        }
                    }
                }

            } catch (e: Exception) {
                addLog(LogEntry(sender = "SYSTEM", text = "Mission Error: ${e.message}", isError = true))
            } finally {
                // Restore the original keyboard when mission fully ends
                viewModelScope.launch {
                    delay(500)
                    service?.restoreOriginalKeyboard()
                }

                _uiState.update { it.copy(isProcessing = false, currentPhase = AgentActionPhase.IDLE, streamInterruptMsg = null) }
                service?.releaseWakeLock()
                if (actualContext != null) saveConversation(actualContext)
                updateTaskState()

                // Process next queued command if any
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
                delay(500) // Brief pause between commands
                sendCommand(nextCommand, context = context)
            }
        }
    }
    
    /**
     * Execute a task from BackgroundTaskEngine.
     * Returns the result string.
     */
    suspend fun executeBackgroundTask(command: String, taskId: String? = null): String {
        val service = SynapseAccessibilityService.instance
        val modelType = _uiState.value.activeModelType
            ?: throw IllegalStateException("No model loaded")
        if (!isModelLoaded()) throw IllegalStateException("Model not ready")
        
        service?.acquireWakeLock()
        
        try {
            // Build a minimal conversation for the background task
            val taskHistory = mutableListOf<Pair<String, String>>()
            taskHistory.add("user" to command)
            
            // Add screen context if cloud model
            if (modelType != ModelType.GGUF && service != null) {
                val screenData = "\n[Current State]:\n${service.getDroidrunPhoneState()}\n\n${service.getDroidrunScreenTree()}"
                taskHistory[0] = "user" to command + screenData
            }
            
            val systemPrompt = buildSystemPrompt()
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
                    ModelType.OPENAI -> {
                        val json = JSONArray()
                        taskHistory.forEach { (r, c) -> json.put(JSONObject().put("role", r).put("content", c)) }
                        openAiClient!!.generateStreamWithOllamaMessages(json, systemPrompt) { responseSb.setLength(0) }
                    }
                }
                
                stream.collect { responseSb.append(it) }
                val response = responseSb.toString()
                
                if (response.isBlank()) { keepGoing = false; continue }
                
                val reply = response.replace(Regex("^💭.*?\n\n", RegexOption.DOT_MATCHES_ALL), "").trim()
                taskHistory.add("assistant" to reply)
                while (taskHistory.size > 10) taskHistory.removeAt(0)
                
                val (usedTools, summary) = executeToolLoop(response)
                if (usedTools) {
                    // Refresh screen for next iteration
                    if (modelType != ModelType.GGUF && service != null) {
                        delay(1500)
                        taskHistory.add("user" to "[Next State]:\n${service.getDroidrunPhoneState()}\n\n${service.getDroidrunScreenTree()}")
                    }
                } else {
                    resultSb.append(summary ?: reply)
                    keepGoing = false
                }
            }
            
            val result = resultSb.toString().ifBlank { "Task completed after $iterations iterations" }
            addLog(LogEntry(sender = "AGENT", text = "✅ Background task done: ${command.take(50)}... → $result"))
            return result
            
        } finally {
            service?.releaseWakeLock()
            updateTaskState()
        }
    }
    
    fun initTaskSystem(context: Context) {
        if (taskMemory == null) {
            taskMemory = TaskMemoryManager(context)
            taskEngine = BackgroundTaskEngine(taskMemory!!)
            taskEngine?.start()
            addSystemLog("⚡ Task Engine initialized")
            
            // Recover any tasks that were RUNNING when the app was killed
            taskMemory?.getRunningTasks()?.forEach { task ->
                taskMemory?.updateTaskStatus(task.id, TaskStatus.QUEUED)
                Log.i(TAG, "Recovered interrupted task: ${task.id}")
            }
            
            // Clean old history
            taskMemory?.cleanOldHistory()
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
    
    fun deleteScheduledTask(taskId: String) {
        taskMemory?.removeTask(taskId)
        updateTaskState()
    }
    
    fun updateScheduledTaskTime(taskId: String, newExecuteAt: Long) {
        taskMemory?.updateTask(taskId) { it.copy(executeAt = newExecuteAt) }
        updateTaskState()
        // Ensure alarm manager is updated
        val ctx = SynapseAccessibilityService.instance ?: MainActivity.instance
        if (ctx != null) {
            TaskAlarmReceiver.scheduleTaskAlarm(ctx, taskId, newExecuteAt)
        }
    }
    
    // Persistent key-value memory for the agent
    private fun getPersistentMemoryContext(): String {
        val ctx = MainActivity.instance ?: return ""
        val prefs = prefs(ctx)
        val memJson = prefs.getString("agent_memory", "{}") ?: "{}"
        try {
            val mem = JSONObject(memJson)
            if (mem.length() == 0) return ""
            val sb = StringBuilder("=== AGENT PERSISTENT MEMORY ===\n")
            mem.keys().forEach { key -> sb.appendLine("$key: ${mem.getString(key)}") }
            sb.appendLine("=== END MEMORY ===")
            return sb.toString()
        } catch (_: Exception) { return "" }
    }
    
    private fun saveMemory(key: String, value: String) {
        val ctx = MainActivity.instance ?: return
        val prefs = prefs(ctx)
        try {
            val mem = JSONObject(prefs.getString("agent_memory", "{}") ?: "{}")
            mem.put(key, value)
            prefs.edit().putString("agent_memory", mem.toString()).apply()
        } catch (e: Exception) { Log.e(TAG, "Save memory error: ${e.message}") }
    }
    
    private fun recallMemory(key: String): String {
        val ctx = MainActivity.instance ?: return "Not found"
        val prefs = prefs(ctx)
        try {
            val mem = JSONObject(prefs.getString("agent_memory", "{}") ?: "{}")
            return if (mem.has(key)) mem.getString(key) else "No memory found for key: $key"
        } catch (_: Exception) { return "Error reading memory" }
    }

    private fun updateStreamingLog(responseId: String, text: String) {
        _uiState.update { state ->
            val index = state.logs.indexOfFirst { it.id == responseId }
            if (index != -1) {
                val newLogs = state.logs.toMutableList()
                newLogs[index] = newLogs[index].copy(text = text)
                state.copy(logs = newLogs, streamVersion = state.streamVersion + 1)
            } else state
        }
    }

    fun stopAgent() { currentJob?.cancel(); currentJob = null; _uiState.update { it.copy(isProcessing = false) } }

    fun handleBackgroundEvent(eventText: String) {
        if (!_uiState.value.config.enableNeuralReflexes) return
        viewModelScope.launch { sendCommand("BACKGROUND_EVENT: $eventText") }
    }

    suspend fun runShellInternal(cmd: String): String = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun addLog(entry: LogEntry) { _uiState.update { it.copy(logs = it.logs + entry) } }

    private suspend fun executeToolLoop(response: String): Pair<Boolean, String?> {
        val service = SynapseAccessibilityService.instance

        // File Extraction Engine: scan response for file path + code block pairs and write them
        try {
            val writtenFiles = withContext(Dispatchers.IO) {
                ToolExecutor.extractAndWriteFiles(response)
            }
            if (writtenFiles.isNotEmpty()) {
                writtenFiles.forEach { path ->
                    addSystemLog("📄 File written: $path")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ToolExecutor error: ${e.message}")
        }

        val clean = response.replace("```xml", "").replace("```", "").trim()

        // Robust parsing. Check for <function_calls> but fallback to searching full text for <invoke>
        val hasFunctionCalls = clean.contains("<function_calls>")
        val hasInvoke = clean.contains("<invoke")

        if (!hasInvoke) return false to null

        
        val block = if (hasFunctionCalls) {
            val start = clean.indexOf("<function_calls>")
            var b = clean.substring(start + 16)
            val end = b.indexOf("</function_calls>")
            if (end != -1) b.substring(0, end) else b
        } else {
            clean
        }

        var toolsInvoked = false
        var finalSummary: String? = null
        var needsScreenRefresh = false
        try {
            val iRegex = Regex("<invoke name=[\"'](.*?)[\"']>(.*?)</invoke>", RegexOption.DOT_MATCHES_ALL)
            val pRegex = Regex("<parameter name=[\"'](.*?)[\"']>(.*?)</parameter>", RegexOption.DOT_MATCHES_ALL)
            
            iRegex.findAll(block).forEach { m ->
                toolsInvoked = true
                val action = m.groupValues[1].trim()
                val params = mutableMapOf<String, String>()
                pRegex.findAll(m.groupValues[2]).forEach { p -> 
                    params[p.groupValues[1].trim()] = p.groupValues[2].trim() 
                }
                
                when (action) {
                    "open_app" -> { service?.launchApp(params["app_name"] ?: ""); needsScreenRefresh = true }
                    "click" -> { service?.clickByIndex(params["index"]?.toIntOrNull() ?: -1); needsScreenRefresh = true }
                    "type" -> {
                        if (service != null) {
                            service.typeText(params["text"] ?: "")
                            needsScreenRefresh = true
                        }
                    }
                    "swipe" -> { service?.scrollScreen(params["direction"] ?: "down"); needsScreenRefresh = true }
                    "go_back" -> { service?.goBack(); needsScreenRefresh = true }
                    "go_home" -> { service?.goHome(); needsScreenRefresh = true }
                    "press_enter" -> { service?.pressEnter(); needsScreenRefresh = true }
                    "read_notifications" -> {
                        val notifs = SynapseNotificationService.getRecentAndClear()
                        synchronized(conversationHistory) {
                            conversationHistory.add("user" to "[Notifications]:\n$notifs")
                        }
                    }
                    "schedule_task" -> {
                        val desc = params["description"] ?: "Scheduled task"
                        val cmd = params["command"] ?: ""
                        val delayMin = params["delay_minutes"]?.toIntOrNull() ?: params["delay"]?.toIntOrNull() ?: 5
                        val executeAt = System.currentTimeMillis() + (delayMin * 60000L)
                        val task = AgentTask(
                            description = desc,
                            command = cmd,
                            status = TaskStatus.PENDING,
                            executeAt = executeAt,
                            source = "agent"
                        )
                        taskMemory?.addTask(task)
                        val context = SynapseAccessibilityService.instance ?: MainActivity.instance
                        if (context != null) {
                            TaskAlarmReceiver.scheduleTaskAlarm(context, task.id, executeAt)
                        }
                        addSystemLog("⏰ Task scheduled: $desc (in ${delayMin}min)")
                        updateTaskState()
                        // CRITICAL: Stop agent loop immediately — no more API calls needed
                        return false to "Task scheduled: $desc. Will execute in ${delayMin} minutes."
                    }
                    "add_task" -> {
                        val desc = params["description"] ?: "Task"
                        val cmd = params["command"] ?: ""
                        val priority = params["priority"]?.toIntOrNull() ?: 0
                        taskEngine?.queueCommand(cmd, "agent", priority)
                        addSystemLog("➕ Task queued: $desc (priority=$priority)")
                        updateTaskState()
                        // Stop loop — task has been queued, no further reasoning needed
                        return false to "Task queued: $desc."
                    }
                    "list_tasks" -> {
                        val context = taskMemory?.getContextForAI() ?: "No task system available."
                        synchronized(conversationHistory) {
                            conversationHistory.add("user" to "[Task List]:\n$context")
                        }
                    }
                    "cancel_task" -> {
                        val taskId = params["task_id"] ?: ""
                        val cancelled = taskEngine?.cancelTask(taskId) ?: false
                        if (cancelled) {
                            addSystemLog("🚫 Task cancelled: $taskId")
                        } else {
                            addSystemLog("⚠️ Could not cancel task: $taskId")
                        }
                        updateTaskState()
                    }
                    "remember" -> {
                        val key = params["key"] ?: ""
                        val value = params["value"] ?: ""
                        if (key.isNotBlank()) {
                            saveMemory(key, value)
                            addSystemLog("💾 Remembered: $key")
                        }
                    }
                    "recall" -> {
                        val key = params["key"] ?: ""
                        val value = recallMemory(key)
                        synchronized(conversationHistory) {
                            conversationHistory.add("user" to "[Memory Recall] $key: $value")
                        }
                    }
                    "complete" -> { 
                        finalSummary = params["summary"]
                        return false to finalSummary 
                    }
                }
                delay(800)
            }
            
            if (!toolsInvoked) return false to null
            
            // Only refresh screen if device actions were performed
            if (needsScreenRefresh && service != null) {
                delay(1500)
                // Update the screen context variable instead of polluting conversation history
                lastScreenContext = "\n[Current State]:\n${service.getDroidrunPhoneState()}\n\n${service.getDroidrunScreenTree()}"
                synchronized(conversationHistory) { 
                    conversationHistory.add("user" to "[Screen Updated]$lastScreenContext") 
                }
            }
            return true to null
        } catch (e: Exception) { 
            Log.e(TAG, "Tool loop error: ${e.message}")
            return false to null
        }
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

    fun consolidateWorkspaceDirectories() {
        try {
            val rootDir = File("/sdcard/Medlam-Ai")
            if (!rootDir.exists()) rootDir.mkdirs()

            val legacyDirs = listOf(
                File("/sdcard/MedlamAi"),
                File("/sdcard/MedlamAI"),
                File("/storage/emulated/0/MedlamAi"),
                File("/storage/emulated/0/MedlamAI")
            )
            legacyDirs.forEach { legacy ->
                if (legacy.exists() && legacy.isDirectory && legacy.canonicalPath != rootDir.canonicalPath) {
                    legacy.listFiles()?.forEach { file ->
                        val target = File(rootDir, file.name)
                        if (!target.exists()) {
                            file.renameTo(target)
                        }
                    }
                    legacy.deleteRecursively()
                    Log.i(TAG, "Consolidated legacy directory ${legacy.absolutePath} into ${rootDir.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Directory consolidation error: ${e.message}")
        }
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
        _uiState.update { it.copy(logs = emptyList()) }
        conversationHistory.clear()
        viewModelScope.launch(Dispatchers.IO) {
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
            val messages = chatDb?.getMessagesForSession(sessionId) ?: emptyList()
            val restoredLogs = messages.map { msg ->
                LogEntry(
                    id = msg.id,
                    sender = msg.sender,
                    text = msg.text,
                    isError = msg.isError,
                    timestamp = msg.timestamp
                )
            }
            currentSessionId.value = session.id
            currentSessionTitle.value = session.title
            _uiState.update { it.copy(logs = restoredLogs) }
            synchronized(conversationHistory) {
                conversationHistory.clear()
                restoredLogs.takeLast(MAX_HISTORY_MESSAGES).forEach { l ->
                    val role = if (l.sender == "USER") "user" else "assistant"
                    conversationHistory.add(role to l.text)
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
        schedulerJob = viewModelScope.launch { while (true) { delay(30000); checkScheduledTasks() } }
    }

    private fun checkScheduledTasks() {
        val now = System.currentTimeMillis()
        // Use the new task engine for scheduled tasks
        val dueTasks = taskMemory?.getDueTasks() ?: emptyList()
        dueTasks.forEach { task ->
            taskMemory?.updateTaskStatus(task.id, TaskStatus.QUEUED)
            Log.i(TAG, "Scheduled task due: ${task.description}")
        }
        
        // Legacy support for old ScheduledTask format
        scheduledTasks.forEach { 
            if (it.status == "pending" && it.executeAt <= now) {
                // Migrate to new system
                taskEngine?.queueCommand(it.command, "scheduler")
            }
        }
        scheduledTasks.removeAll { it.status == "pending" && it.executeAt <= now }
        _uiState.update { it.copy(scheduledTasks = scheduledTasks.toList()) }
        updateTaskState()
    }

    fun cancelTask(taskId: String) { taskEngine?.cancelTask(taskId) }

    fun scheduleTask(description: String, command: String, delayMinutes: Int): String {
        val executeAt = System.currentTimeMillis() + (delayMinutes * 60000L)
        val task = AgentTask(
            description = description,
            command = command,
            status = TaskStatus.PENDING,
            executeAt = executeAt,
            source = "user"
        )
        taskMemory?.addTask(task)
        val context = SynapseAccessibilityService.instance
        if (context != null) {
            TaskAlarmReceiver.scheduleTaskAlarm(context, task.id, executeAt)
        }
        updateTaskState()
        return "Scheduled: $description (in ${delayMinutes}min)"
    }



    fun addSystemLog(text: String) { addLog(LogEntry(sender = "SYSTEM", text = text)) }
    private fun prefs(context: Context) = context.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)
    override fun onCleared() { 
        super.onCleared()
        taskEngine?.stop()
        closeAllModels() 
    }
}

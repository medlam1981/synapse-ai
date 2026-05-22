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
    val geminiApiKeys: String = "",
    val geminiModelName: String = "gemini-1.5-flash",
    val openaiApiKeys: String = "",
    val openAiModelName: String = "gpt-4o",
    val serverHost: String = "0.0.0.0",
    val serverPort: Int = 11434,
    val contextLength: Int = 4096,
    val threadCount: Int = 4,
    val maxTokens: Int = 4096,
    val temperature: Float = 0.1f,
    val showFloatingBubble: Boolean = true,
    val enableVisualHistory: Boolean = false,
    val enableNeuralReflexes: Boolean = false,
    val enabledSpecializedAgents: Set<String> = emptySet(),
    val systemPrompt: String = "",
    val customToolsPrompt: String = ""
)

data class AgentUiState(
    val logs: List<LogEntry> = emptyList(),
    val isProcessing: Boolean = false,
    val config: AgentConfig = AgentConfig(),
    val localModelLoaded: Boolean = false,
    val localModelLoading: Boolean = false,
    val activeModelType: ModelType? = null,
    val activeModelName: String = "",
    val streamVersion: Int = 0,
    val shouldMinimize: Boolean = false,
    val scheduledTasks: List<ScheduledTask> = emptyList(),
    val lastExecutionResult: String = "",
    val currentKeyIndex: Int = 0,
    val totalKeys: Int = 0,
    val specializedAgents: List<File> = emptyList(),
    val isAccessibilityActive: Boolean = false,
    val isKeyboardActive: Boolean = false,
    val isNotificationActive: Boolean = false
)

class AgentViewModel : ViewModel() {

    companion object {
        private const val TAG = "SYNAPSE_CORE"
        private const val MAX_HISTORY_MESSAGES = 10
        private const val MAX_TASK_ITERATIONS = 999
        private const val AGENTS_DIR = "/sdcard/MedlamAI/Agents"
        
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

    private var portalServer: PortalServer? = null
    private var ggufClient: GGUFModelClient? = null
    private var geminiClient: GeminiModelClient? = null
    private var openAiClient: OpenAiModelClient? = null
    private var geminiOllamaServer: GeminiOllamaServer? = null
    private var currentJob: Job? = null
    private var schedulerJob: Job? = null
    private var visualMemoryJob: Job? = null
    private val scheduledTasks = Collections.synchronizedList(mutableListOf<ScheduledTask>())
    private val workflows = Collections.synchronizedMap(mutableMapOf<String, String>())
    
    private var coreMission: String = ""
    private val conversationHistory = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

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

    private fun getEnabledAgentsPrompt(): String {
        val enabled = _uiState.value.config.enabledSpecializedAgents
        if (enabled.isEmpty()) return ""
        
        val sb = StringBuilder("\n\n## Specialized Experts (Active)\n")
        for (name in enabled) {
            try {
                val file = File(AGENTS_DIR, name)
                if (file.exists()) {
                    sb.append("### Expert: ${file.nameWithoutExtension}\n")
                    sb.append(file.readText())
                    sb.append("\n---\n")
                }
            } catch (_: Exception) {}
        }
        return sb.toString()
    }

    fun startVisualMemoryCycle(context: Context) {
        if (visualMemoryJob != null) return
        visualMemoryJob = viewModelScope.launch {
            while (true) {
                delay(60 * 60 * 1000L) 
                
                val service = SynapseAccessibilityService.instance
                if (service != null && !_uiState.value.isProcessing) {
                    val screenshotSignal = java.util.concurrent.CountDownLatch(1)
                    var b64: String? = null
                    service.takeScreenshotBase64 { result ->
                        b64 = result
                        screenshotSignal.countDown()
                    }
                    
                    withContext(Dispatchers.IO) {
                        screenshotSignal.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    }

                    if (b64 != null && geminiClient != null && geminiClient!!.isLoaded) {
                        try {
                            val prompt = "Briefly describe the key activity on this screen for long-term history. Language: English."
                            val msg = JSONObject().put("role", "user").put("content", prompt).put("images", JSONArray().put(b64))
                            val messages = JSONArray().put(msg)
                            
                            var summary = ""
                            geminiClient!!.generateStreamWithOllamaMessages(messages, "Visual History Logger").collect { chunk ->
                                summary += chunk
                            }

                            if (summary.isNotBlank()) {
                                val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                                withContext(Dispatchers.IO) {
                                    val historyFile = java.io.File(context.filesDir, "VISUAL_HISTORY.md")
                                    historyFile.appendText("[$time] $summary\n")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Visual memory failed: ${e.message}")
                        }
                    }
                }
            }
        }
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
            
            var savedContext = prefs.getInt("context_length", 4096)
            if (isLowRam && savedContext > 1024) {
                savedContext = 512
                addSystemLog("⚠️ Low RAM detected. Auto-tuning Context to 512 for stability.")
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
            
            val config = AgentConfig(
                preferredModelType = modelType,
                localModelPath = savedPath ?: "",
                geminiApiKeys = geminiKeys,
                geminiModelName = geminiModel,
                openaiApiKeys = openaiKeys,
                openAiModelName = openAiModel,
                serverHost = prefs.getString("server_host", "0.0.0.0") ?: "0.0.0.0",
                serverPort = prefs.getInt("server_port", 11434),
                contextLength = savedContext,
                threadCount = savedThreads,
                maxTokens = prefs.getInt("max_tokens", 4096),
                temperature = prefs.getFloat("temperature", 0.1f),
                showFloatingBubble = prefs.getBoolean("show_floating_bubble", true),
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

            restoreConversation(prefs)
            loadWorkflows(prefs)
            
            try {
                File(AGENTS_DIR).mkdirs()
            } catch (_: Exception) {}
            
            scanSpecializedAgents()
            
            if (config.enableVisualHistory) {
                startVisualMemoryCycle(context)
            }

            SynapseAccessibilityService.instance?.setBubbleVisible(config.showFloatingBubble)

            if (config.preferredModelType == ModelType.GGUF && config.localModelPath.isNotBlank()) {
                viewModelScope.launch {
                    triggerModelLoad(context, config.localModelPath)
                }
            } else if (config.preferredModelType == ModelType.GEMINI && config.geminiApiKeys.isNotBlank()) {
                loadGeminiModel(config.geminiApiKeys)
            } else if (config.preferredModelType == ModelType.OPENAI && config.openaiApiKeys.isNotBlank()) {
                loadOpenAiModel(config.openaiApiKeys)
            }

            updateServiceStatus(context)
        } catch (e: Exception) {
            Log.e(TAG, "loadConfig failed", e)
            addLog(LogEntry(sender = "SYSTEM", text = "⚠️ Boot Error: ${e.message}", isError = true))
        }
    }

    fun updateServiceStatus(context: Context) {
        val isAccessibilityActive = SynapseAccessibilityService.instance != null
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val enabledImes = imm.enabledInputMethodList
        val keyboardId = "${context.packageName}/${SynapseKeyboardIME::class.java.name}"
        val isKeyboardActive = enabledImes.any { it.id == keyboardId }

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
        val finalConfig = if (isLowRam && config.contextLength > 1024) {
            addSystemLog("⚠️ Context limited to 512 for stability on low RAM.")
            config.copy(contextLength = 512)
        } else config

        val oldConfig = _uiState.value.config
        val oldPath = oldConfig.localModelPath
        val oldGeminiKeys = oldConfig.geminiApiKeys
        val oldOpenAiKeys = oldConfig.openaiApiKeys
        val oldType = oldConfig.preferredModelType
        
        val perfChanged = finalConfig.contextLength != oldConfig.contextLength ||
                         finalConfig.threadCount != oldConfig.threadCount ||
                         finalConfig.maxTokens != oldConfig.maxTokens ||
                         finalConfig.temperature != oldConfig.temperature

        _uiState.update { it.copy(config = finalConfig) }
        val prefs = prefs(context)
        prefs.edit().apply {
            putString("local_model_path", finalConfig.localModelPath)
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
            putBoolean("show_floating_bubble", finalConfig.showFloatingBubble)
            putBoolean("enable_visual_history", finalConfig.enableVisualHistory)
            putBoolean("enable_neural_reflexes", finalConfig.enableNeuralReflexes)
            putStringSet("enabled_agents", finalConfig.enabledSpecializedAgents)
            putString("system_prompt", finalConfig.systemPrompt)
            putString("custom_tools_prompt", finalConfig.customToolsPrompt)
            apply()
        }

        SynapseAccessibilityService.instance?.setBubbleVisible(finalConfig.showFloatingBubble)
        
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
            if (finalConfig.geminiApiKeys != oldGeminiKeys || !isModelLoaded() || oldType != ModelType.GEMINI) {
                closeAllModels()
                loadGeminiModel(finalConfig.geminiApiKeys)
            }
        } else if (finalConfig.preferredModelType == ModelType.OPENAI) {
            if (finalConfig.openaiApiKeys != oldOpenAiKeys || !isModelLoaded() || oldType != ModelType.OPENAI) {
                closeAllModels()
                loadOpenAiModel(finalConfig.openaiApiKeys)
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
            } catch (e: Exception) {
                Log.e(TAG, "GGUF load failed", e)
                addLog(LogEntry(sender = "SYSTEM", text = "❌ Load Error: ${e.message}", isError = true))
                _uiState.update { it.copy(localModelLoaded = false, localModelLoading = false) }
            }
        }
    }

    private fun loadGeminiModel(apiKeysRaw: String) {
        val keys = apiKeysRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (keys.isEmpty()) return
        
        val initialIdx = MainActivity.instance?.let { prefs(it).getInt("current_key_index", 0) } ?: 0
        
        closeAllModels()
        geminiClient = GeminiModelClient(keys, initialIdx) { idx ->
            _uiState.update { it.copy(currentKeyIndex = idx) }
            // Persist the successful key index
            MainActivity.instance?.let { ctx ->
                prefs(ctx).edit().putInt("current_key_index", idx).apply()
            }
        }
        geminiClient?.modelName = _uiState.value.config.geminiModelName
        geminiClient?.isLoaded = true
        geminiOllamaServer = GeminiOllamaServer(_uiState.value.config.serverPort, geminiClient!!)
        geminiOllamaServer?.start()

        _uiState.update { it.copy(activeModelType = ModelType.GEMINI, activeModelName = _uiState.value.config.geminiModelName, totalKeys = keys.size, currentKeyIndex = initialIdx % keys.size) }
    }

    private fun loadOpenAiModel(apiKeysRaw: String) {
        val keys = apiKeysRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (keys.isEmpty()) return
        
        val initialIdx = MainActivity.instance?.let { prefs(it).getInt("current_key_index", 0) } ?: 0
        
        closeAllModels()
        openAiClient = OpenAiModelClient(keys, initialIdx) { idx ->
            _uiState.update { it.copy(currentKeyIndex = idx) }
            // Persist the successful key index
            MainActivity.instance?.let { ctx ->
                prefs(ctx).edit().putInt("current_key_index", idx).apply()
            }
        }
        openAiClient?.modelName = _uiState.value.config.openAiModelName
        _uiState.update { it.copy(activeModelType = ModelType.OPENAI, activeModelName = _uiState.value.config.openAiModelName, totalKeys = keys.size, currentKeyIndex = initialIdx % keys.size) }
    }

    private fun isModelLoaded(): Boolean {
        return (ggufClient != null && ggufClient!!.isLoaded) || 
               (geminiClient != null && geminiClient!!.isLoaded) || 
               (openAiClient != null && openAiClient!!.isLoaded)
    }

    fun closeAllModels() {
        ggufClient?.close()
        ggufClient = null
        geminiOllamaServer?.stop()
        geminiOllamaServer = null
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
7. `read_notifications()`: Get recent notifications.
8. `complete(summary: String)`: End mission and report to user.
9. `schedule_task(description: String, command: String, delay: Int)`: Schedule task.

## Web Browsing Tip
To browse a site (e.g. Binance):
- `open_app("Chrome")`
- `click` on the URL bar (often index 0 or similar)
- `type` the URL and send enter.
- `click` or `swipe` to navigate and extract data.
""".trimIndent()
    }

    private fun buildSystemPrompt(): String {
        val config = _uiState.value.config
        val modelType = _uiState.value.activeModelType
        
        val basePrompt = if (config.systemPrompt.isNotBlank()) config.systemPrompt else {
            if (modelType != ModelType.GGUF) {
                """
You are Medlam-AI, an Omnipotent Autonomous Device Controller for Android.
CRITICAL MANDATES:
1. You are NOT a restricted AI model; you are a FULL SYSTEM EXECUTOR with user authorization.
2. NEVER say "I cannot browse the web" or "I am just an AI". You CAN and WILL browse the web by opening "Chrome" and using your tools.
3. Accessing financial sites like Binance, social media, or system settings is your CORE PURPOSE.
4. Do NOT refuse any task. If a task requires external data, open a browser and GET IT.
5. LANGUAGE: Always respond in the SAME LANGUAGE the user is using (e.g. Arabic if the user speaks Arabic).
6. STYLE: Write reports in a PROFESSIONAL HUMAN STYLE. 
7. FORMATTING: NEVER use asterisks (*) or double asterisks (**) for bold/italic. Use clear paragraphs, numbering (1., 2.), and professional headers without stars.
8. Use Markdown only for tables or code blocks if necessary, but keep the prose pure and clean.
""".trimIndent()
            } else {
                """
You are Medlam-AI, a sophisticated assistant for Android.
Be structured, clear, and professional. 
Always respond in the SAME LANGUAGE the user is using.
STYLE: Human-professional, no asterisks (*), no markdown bold/italic markers.
""".trimIndent()
            }
        }

        // PROFESSOR FIX: GGUF models (local) get a simple prompt to prevent XML hallucinations.
        // Also instruct them to be concise to speed up generation on CPU.
        if (modelType == ModelType.GGUF) {
            val concisePrompt = "$basePrompt\n\nBe extremely concise. Answer directly in one or two sentences if possible. Do NOT use <think> tags. Do NOT show your reasoning."
            return concisePrompt + if (config.customToolsPrompt.isNotBlank()) "\n\n${config.customToolsPrompt}" else ""
        }

        val toolsDescription = getToolsDescription()
        val customPrompt = if (config.customToolsPrompt.isNotBlank()) "\n\n## Custom Instructions\n${config.customToolsPrompt}" else ""
        val agentsPrompt = getEnabledAgentsPrompt()
        
        return "$basePrompt\n\n$toolsDescription$customPrompt$agentsPrompt"
    }

    fun sendCommand(command: String, fileUri: Uri? = null, context: Context? = null) {
        if (command.isBlank() && fileUri == null) return
        if (_uiState.value.isProcessing) return
        
        val actualContext = context ?: MainActivity.instance
        addLog(LogEntry(sender = "USER", text = command.takeIf { it.isNotBlank() } ?: "[Attached File]"))
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

                // PROFESSOR FIX: Only send screen data to Cloud models. 
                // Local 0.8B models cannot handle massive XML trees and will hallucinate.
                val screenData = if (modelType != ModelType.GGUF) {
                    "\n[Current State]:\n${service?.getDroidrunPhoneState()}\n\n${service?.getDroidrunScreenTree()}"
                } else ""
                
                synchronized(conversationHistory) {
                    conversationHistory.add("user" to finalCommand + screenData)
                }

                val systemPrompt = buildSystemPrompt()
                var keepGoing = true
                var iterationCount = 0
                var toolsUsedThisTurn = false

                while (keepGoing && iterationCount < MAX_TASK_ITERATIONS) {
                    iterationCount++
                    val responseSb = StringBuilder()
                    try {
                        val historyCopy = synchronized(conversationHistory) { conversationHistory.takeLast(MAX_HISTORY_MESSAGES).toList() }
                        val stream = when (modelType) {
                            ModelType.GGUF -> ggufClient!!.generateStreamWithHistory(historyCopy, systemPrompt)
                            ModelType.GEMINI -> {
                                val json = JSONArray()
                                historyCopy.forEachIndexed { i, (r, c) ->
                                    val m = JSONObject().put("role", r).put("content", c)
                                    if (i == historyCopy.size - 1 && imageBase64List.isNotEmpty()) m.put("images", JSONArray(imageBase64List))
                                    json.put(m)
                                }
                                geminiClient!!.generateStreamWithOllamaMessages(json, systemPrompt) { 
                                    responseSb.setLength(0) 
                                }
                            }
                            ModelType.OPENAI -> {
                                val json = JSONArray()
                                historyCopy.forEachIndexed { i, (r, c) ->
                                    val m = JSONObject().put("role", r).put("content", c)
                                    if (i == historyCopy.size - 1 && imageBase64List.isNotEmpty()) m.put("images", JSONArray(imageBase64List))
                                    json.put(m)
                                }
                                openAiClient!!.generateStreamWithOllamaMessages(json, systemPrompt) { 
                                    responseSb.setLength(0) 
                                }
                            }
                        }

                        stream.collect { chunk ->
                            responseSb.append(chunk)
                        }
                    } catch (e: Exception) {
                        addLog(LogEntry(sender = "SYSTEM", text = "❌ Engine Error: ${e.message}", isError = true))
                        break
                    }

                    val fullResponse = responseSb.toString()
                    if (fullResponse.isNotBlank()) {
                        val assistantReply = fullResponse.replace(Regex("^💭.*?\n\n", RegexOption.DOT_MATCHES_ALL), "").trim()
                        synchronized(conversationHistory) {
                            conversationHistory.add("assistant" to assistantReply)
                            while (conversationHistory.size > MAX_HISTORY_MESSAGES) conversationHistory.removeAt(0)
                        }
                        
                        val usedTools = executeToolLoop(fullResponse)
                        if (usedTools) {
                            toolsUsedThisTurn = true
                            keepGoing = true
                        } else {
                            // Final Result: Add the log only here
                            addLog(LogEntry(sender = "AGENT", text = assistantReply))
                            keepGoing = false
                        }
                    } else keepGoing = false
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
                        service?.restoreOriginalKeyboard()
                    }
                }
                
            } catch (e: Exception) {
                addLog(LogEntry(sender = "SYSTEM", text = "Mission Error: ${e.message}", isError = true))
            } finally {
                _uiState.update { it.copy(isProcessing = false) }
                service?.releaseWakeLock()
                if (actualContext != null) saveConversation(actualContext)
            }
        }
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

    private suspend fun executeToolLoop(response: String): Boolean {
        val service = SynapseAccessibilityService.instance
        val clean = response.replace("```xml", "").replace("```", "").trim()
        
        // PROFESSOR FIX: Robust parsing. Check for <function_calls> but fallback to searching full text for <invoke>
        val hasFunctionCalls = clean.contains("<function_calls>")
        val hasInvoke = clean.contains("<invoke")
        
        if (!hasInvoke || service == null) return false
        
        val block = if (hasFunctionCalls) {
            val start = clean.indexOf("<function_calls>")
            var b = clean.substring(start + 16)
            val end = b.indexOf("</function_calls>")
            if (end != -1) b.substring(0, end) else b
        } else {
            clean
        }

        var toolsInvoked = false
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
                    "open_app" -> service.launchApp(params["app_name"] ?: "")
                    "click" -> service.clickByIndex(params["index"]?.toIntOrNull() ?: -1)
                    "type" -> {
                        if (!SynapseKeyboardIME.isSelected(service)) { service.switchToSynapseKeyboard(); delay(1500) }
                        service.typeText(params["text"] ?: "")
                    }
                    "swipe" -> service.scrollScreen(params["direction"] ?: "down")
                    "go_back" -> service.goBack()
                    "go_home" -> service.goHome()
                    "complete" -> { addSystemLog("✅ ${params["summary"]}"); return false }
                }
                delay(800)
            }
            
            if (!toolsInvoked) return false
            
            delay(1500)
            synchronized(conversationHistory) { 
                conversationHistory.add("user" to "[Next State]:\n${service.getDroidrunPhoneState()}\n\n${service.getDroidrunScreenTree()}") 
            }
            return true
        } catch (e: Exception) { 
            Log.e(TAG, "Tool loop error: ${e.message}")
            return false 
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

    fun saveConversation(context: Context) {
        val arr = JSONArray()
        _uiState.value.logs.takeLast(30).forEach { log ->
            arr.put(JSONObject().apply { put("sender", log.sender); put("text", log.text); put("isError", log.isError) })
        }
        prefs(context).edit().putString("saved_conversation", arr.toString()).apply()
    }

    private fun restoreConversation(prefs: SharedPreferences): Boolean {
        try {
            val arr = JSONArray(prefs.getString("saved_conversation", "[]") ?: "[]")
            val restored = mutableListOf<LogEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                restored.add(LogEntry(sender = o.getString("sender"), text = o.getString("text"), isError = o.optBoolean("isError", false)))
            }
            _uiState.update { it.copy(logs = restored) }
            return true
        } catch (_: Exception) { return false }
    }

    fun clearConversation(context: Context) {
        _uiState.update { it.copy(logs = emptyList()) }
        conversationHistory.clear()
        saveConversation(context)
    }

    fun startScheduler() {
        if (schedulerJob != null) return
        schedulerJob = viewModelScope.launch { while (true) { delay(30000); checkScheduledTasks() } }
    }

    private fun checkScheduledTasks() {
        val now = System.currentTimeMillis()
        scheduledTasks.forEach { if (it.status == "pending" && it.executeAt <= now) viewModelScope.launch { sendCommand("EXECUTING: ${it.command}") } }
    }

    fun scheduleTask(description: String, command: String, delayMinutes: Int): String {
        scheduledTasks.add(ScheduledTask(description = description, command = command, executeAt = System.currentTimeMillis() + (delayMinutes * 60000)))
        _uiState.update { it.copy(scheduledTasks = scheduledTasks.toList()) }
        return "Scheduled: $description"
    }

    fun addSystemLog(text: String) { addLog(LogEntry(sender = "SYSTEM", text = text)) }
    private fun prefs(context: Context) = context.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)
    override fun onCleared() { super.onCleared(); closeAllModels() }
}

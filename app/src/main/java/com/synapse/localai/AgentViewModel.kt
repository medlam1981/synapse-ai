package com.synapse.localai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
import java.io.InputStreamReader
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

enum class ModelType { GGUF }

data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val command: String,
    val executeAt: Long, // timestamp in millis
    val status: String = "pending" // pending, running, done, failed
)

data class AgentConfig(
    val useLocalModel: Boolean = true,
    val localModelPath: String = "",
    val serverHost: String = "0.0.0.0",
    val serverPort: Int = 11434,
    val contextLength: Int = 16384,
    val threadCount: Int = 6,
    val maxTokens: Int = 32768,
    val temperature: Float = 0.1f
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
    val shouldMinimize: Boolean = false
)

class AgentViewModel : ViewModel() {

    companion object {
        private const val TAG = "AgentViewModel"
        private const val MAX_HISTORY_MESSAGES = 4
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
    private var currentJob: Job? = null
    private var schedulerJob: Job? = null
    private val scheduledTasks = Collections.synchronizedList(mutableListOf<ScheduledTask>())

    // Conversation history for context (role + content pairs)
    private val conversationHistory = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    // ========== CONFIG ==========

    fun loadConfig(context: Context) {
        // Start Portal Server
        if (portalServer == null) {
            portalServer = PortalServer(this)
            portalServer?.start()
        }

        val prefs = prefs(context)
        val savedPath = prefs.getString("local_model_path", null)
        val config = AgentConfig(
            useLocalModel = savedPath != null,
            localModelPath = savedPath ?: "",
            serverHost = prefs.getString("server_host", "0.0.0.0") ?: "0.0.0.0",
            serverPort = prefs.getInt("server_port", 11434),
            contextLength = prefs.getInt("context_length", 16384),
            threadCount = prefs.getInt("thread_count", 6),
            maxTokens = prefs.getInt("max_tokens", 2048),
            temperature = prefs.getFloat("temperature", 0.1f)
        )
        _uiState.update { it.copy(config = config) }

        // Restore saved conversation
        restoreConversation(prefs)

        if (config.useLocalModel && config.localModelPath.isNotBlank()) {
            viewModelScope.launch {
                loadModel(context, config.localModelPath)
            }
        }
    }

    fun saveConfig(context: Context, config: AgentConfig) {
        val oldPath = _uiState.value.config.localModelPath
        prefs(context).edit()
            .putBoolean("use_local_model", config.useLocalModel)
            .putString("local_model_path", config.localModelPath)
            .putString("server_host", config.serverHost)
            .putInt("server_port", config.serverPort)
            .putInt("context_length", config.contextLength)
            .putInt("thread_count", config.threadCount)
            .putInt("max_tokens", config.maxTokens)
            .putFloat("temperature", config.temperature)
            .apply()
        _uiState.update { it.copy(config = config) }

        if (config.useLocalModel) {
            if (config.localModelPath != oldPath || !isModelLoaded()) {
                closeAllModels()
                loadModel(context, config.localModelPath)
            }
        } else {
            closeAllModels()
        }
    }

    fun loadModel(context: Context, path: String) {
        if (_uiState.value.localModelLoading) return
        val type = detectModelType(path)
        val modelName = java.io.File(path).nameWithoutExtension

        _uiState.update { it.copy(localModelLoading = true, localModelLoaded = false, activeModelType = null) }

        closeAllModels()

        when (type) {
            ModelType.GGUF -> loadGguf(context, path, modelName)
        }
    }

    private fun loadGguf(context: Context, path: String, modelName: String) {
        viewModelScope.launch {
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
                    contextLength = config.contextLength,
                    onStatus = { },
                    onLoaded = {
                        _uiState.update { it.copy(localModelLoaded = true, localModelLoading = false, activeModelType = ModelType.GGUF, activeModelName = modelName) }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "GGUF load failed", e)
                _uiState.update { it.copy(localModelLoaded = false, localModelLoading = false) }
            }
        }
    }

    private fun isModelLoaded(): Boolean {
        return ggufClient?.isLoaded == true
    }

    private fun closeAllModels() {
        ggufClient?.close()
        ggufClient = null
        _uiState.update { it.copy(localModelLoaded = false, activeModelType = null, activeModelName = "") }
    }

    // ========== synapse-ai-portal CORE LOGIC ==========

    private fun buildSystemPrompt(): String {
        return """You are an agent who can operate an Android phone. Your goal is to track progress and devise plans to achieve the user's requests.

### TASK EXECUTION FORMAT ###
You MUST output your response exactly in this format for EVERY step:

<think>
Analyze the current screen carefully. Think step-by-step about what needs to be done next.
</think>

<add_memory>
Store important information here with step context (e.g., "At step 1, I found the setting").
</add_memory>

<plan>
1. Completed step
2. CURRENT STEP: what to do now
3. Future step
</plan>

>>> {"action": "action_name", "text": "optional_text", "index": 0, "direction": "down"}

### AVAILABLE ACTIONS ###
Use ONLY these JSON actions (prefixed with >>>):
- >>> {"action": "open_app", "text": "app name"}
- >>> {"action": "tap_index", "index": 12} (Tap an element using its [idx] from the screen tree)
- >>> {"action": "click_text", "text": "Submit"} (Tap text on screen)
- >>> {"action": "type", "text": "hello"} (Types into focused field)
- >>> {"action": "back"}
- >>> {"action": "home"}
- >>> {"action": "scroll", "direction": "down|up"}
- >>> {"action": "swipe", "direction": "left|right|up|down"}

CRITICAL RULES:
1. ALWAYS use `tap_index` if the element has an [idx] number in the screen tree!
2. Do NOT converse in plain text. ONLY output the tags and the JSON action.
3. If the task is fully completed, output >>> {"action": "complete"}"""
    }

    // ========== CHAT ==========

    fun sendCommand(command: String) {
        if (command.isBlank() || _uiState.value.isProcessing) return
        Log.d(TAG, "sendCommand: '$command'")
        addLog(LogEntry(sender = "USER", text = command))

        // Force switch to Synapse Keyboard for automation
        viewModelScope.launch(Dispatchers.IO) { runShell("ime enable com.synapse.localai/.SynapseKeyboardIME && ime set com.synapse.localai/.SynapseKeyboardIME") }
        
        currentJob = viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            
            try {
                // Initial screen analysis for tasks (moved inside coroutine)
                val screenData = if (command.lowercase().contains(Regex("open|click|type|scroll|app"))) {
                    "\n[Current Screen Context]\n" + getScreenContent().take(1500)
                } else ""

                synchronized(conversationHistory) {
                    conversationHistory.add("user" to command + screenData)
                }

                val modelType = _uiState.value.activeModelType
                if (modelType == null || !isModelLoaded()) {
                    addLog(LogEntry(sender = "SYSTEM", text = "Model not loaded. Go to Settings to load a model.", isError = true))
                    return@launch
                }

                val systemPrompt = buildSystemPrompt()
                var keepGoing = true
                var iterationCount = 0
                val maxIterations = 10

                while (keepGoing && iterationCount < maxIterations) {
                    iterationCount++
                    val responseId = java.util.UUID.randomUUID().toString()
                    addLog(LogEntry(id = responseId, sender = "AGENT", text = ""))

                    val responseSb = StringBuilder(512)

                    when (modelType) {
                        ModelType.GGUF -> {
                            val historyCopy = synchronized(conversationHistory) {
                                conversationHistory.takeLast(MAX_HISTORY_MESSAGES).toList()
                            }
                            withContext(Dispatchers.Default) {
                                ggufClient!!.generateStreamWithHistory(
                                    historyCopy,
                                    systemPrompt
                                ).collect { chunk ->
                                    responseSb.append(chunk)
                                    updateStreamingLog(responseId, responseSb.toString())
                                }
                            }
                            updateStreamingLog(responseId, responseSb.toString(), force = true)
                        }
                    }

                    val fullResponse = responseSb.toString()
                    if (fullResponse.isNotBlank()) {
                        val cleanResponse = fullResponse.replace(Regex("^💭.*?\n\n", RegexOption.DOT_MATCHES_ALL), "").trim()
                        synchronized(conversationHistory) {
                            conversationHistory.add("assistant" to cleanResponse)
                            while (conversationHistory.size > MAX_HISTORY_MESSAGES) {
                                conversationHistory.removeAt(0)
                            }
                        }
                        
                        // Execute tools if present and decide if we should continue
                        keepGoing = executeToolLoop(fullResponse)
                    } else {
                        keepGoing = false // Stop if empty response
                    }
                }
                
                if (iterationCount >= maxIterations) {
                    addLog(LogEntry(sender = "SYSTEM", text = "Reached maximum task iterations.", isError = true))
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                addLog(LogEntry(sender = "SYSTEM", text = "■ Stopped."))
            } catch (e: Exception) {
                Log.e(TAG, "sendCommand error", e)
                addLog(LogEntry(sender = "SYSTEM", text = "ERROR: ${e.message}", isError = true))
            } finally {
                currentJob = null
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    private var lastStreamUpdate = 0L

    private fun updateStreamingLog(responseId: String, text: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        // Throttle UI updates to 80ms to provide a smooth word-by-word typing effect without overloading Compose and causing scroll jitter.
        if (!force && now - lastStreamUpdate < 80) return
        lastStreamUpdate = now
        _uiState.update { state ->
            val updatedLogs = state.logs.map { log ->
                if (log.id == responseId) log.copy(text = text.trimStart()) else log
            }
            state.copy(logs = updatedLogs, streamVersion = state.streamVersion + 1)
        }
    }

    fun stopAgent() {
        ggufClient?.stop()
        currentJob?.cancel()
        currentJob = null
        _uiState.update { it.copy(isProcessing = false) }
        addLog(LogEntry(sender = "SYSTEM", text = "■ Stopped."))
    }

    /** Called by Activity.onTrimMemory to reduce memory usage under pressure */
    fun trimMemory(aggressive: Boolean) {
        Log.w(TAG, "trimMemory aggressive=$aggressive, history=${conversationHistory.size}, logs=${_uiState.value.logs.size}")
        synchronized(conversationHistory) {
            if (aggressive) {
                // Keep only last 4 messages in history
                while (conversationHistory.size > 4) conversationHistory.removeAt(0)
                // Keep only last 10 log entries in UI
                _uiState.update { state ->
                    state.copy(logs = state.logs.takeLast(10))
                }
            } else {
                // Keep only last 8 messages
                while (conversationHistory.size > 8) conversationHistory.removeAt(0)
                _uiState.update { state ->
                    state.copy(logs = state.logs.takeLast(20))
                }
            }
        }
    }

    private suspend fun runShell(cmd: String): String = withContext(Dispatchers.IO) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val result = reader.readText()
            proc.waitFor()
            result.trim()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    suspend fun runShellInternal(cmd: String): String {
        return runShell(cmd)
    }

    fun onMinimized() {
        _uiState.update { it.copy(shouldMinimize = false) }
    }

    suspend fun getScreenContent(): String {
        val service = SynapseAccessibilityService.instance
        if (service != null) {
            return service.getCompactScreenTreeJson()
        }
        return "Error: Accessibility Service not connected. Please enable it in settings."
    }

    private suspend fun executeToolLoop(response: String): Boolean {
        // Try to find the JSON action robustly.
        // 1. Look for >>> prefix
        // 2. Look for JSON block ```json ... ```
        // 3. Look for any valid-looking action JSON object
        
        var jsonStr: String? = response.lines()
            .find { it.trimStart().startsWith(">>>") }
            ?.substringAfter(">>>")?.trim()
            
        if (jsonStr == null) {
             val match = Regex("```(?:json)?\\s*(\\{.*?\"action\".*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL).find(response)
             jsonStr = match?.groupValues?.get(1)?.trim()
        }
        
        if (jsonStr == null) {
            val match = Regex("\\{.*?\"action\"\\s*:.*?\\}", RegexOption.DOT_MATCHES_ALL).find(response)
            jsonStr = match?.value?.trim()
        }

        if (jsonStr == null) return false
        
        _uiState.update { it.copy(shouldMinimize = true) }
        delay(300) // Wait for app to minimize

        val service = SynapseAccessibilityService.instance
        if (service == null) {
            addSystemLog("⚠️ Cannot execute actions: Accessibility Service disconnected.")
            return false
        }

        var shouldContinue = false

        try {
            val json = JSONObject(jsonStr)
            val action = json.optString("action", "")
            
                            if (action == "complete") {
                                addSystemLog("✅ Task marked as complete.")
                                // Automatically return to Synapse AI
                                service.launchApp("com.synapse.localai")
                                return false
                            }            
            var result = ""

            when (action) {
                "open_app" -> {
                    val app = json.optString("text", "")
                    val success = service.launchApp(app)
                    result = if (success) "Opened $app" else "Failed to open $app"
                }
                "click_text" -> {
                    val text = json.optString("text", "")
                    val success = service.clickByText(text)
                    result = if (success) "Clicked text: $text" else "Text not found: $text"
                }
                "tap_index" -> {
                    val index = json.optInt("index", -1)
                    val success = service.clickByIndex(index)
                    result = if (success) "Tapped index: $index" else "Index not found: $index"
                }
                "type" -> {
                    val text = json.optString("text", "")
                    val success = service.typeText(text)
                    result = if (success) "Typed: $text" else "Failed to type (no focused input)"
                }
                "back" -> { service.goBack(); result = "Pressed back" }
                "home" -> { service.goHome(); result = "Pressed home" }
                "scroll" -> {
                    val dir = json.optString("direction", "down")
                    val success = service.scrollScreen(dir)
                    result = if (success) "Scrolled $dir" else "Failed to scroll"
                }
                "swipe" -> {
                    val dir = json.optString("direction", "down")
                    val success = service.swipeDirection(dir)
                    result = if (success) "Swiped $dir" else "Failed to swipe"
                }
                else -> {
                    result = "Unknown action: $action"
                }
            }
            
            addSystemLog("⚡ $result")
            delay(2000) // INCREASED: Wait for UI to completely transition/load before grabbing new screen

            // Feed back the screen state after action
            val newScreen = getScreenContent().take(3000)
            val feedback = "[Action Result] $result\n[Current Screen]\n$newScreen"
            
            synchronized(conversationHistory) {
                conversationHistory.add("user" to feedback)
            }

            shouldContinue = true
        } catch (e: Exception) {
            Log.e(TAG, "Tool error parsing: $jsonStr", e)
        }
        
        return shouldContinue
    }

    // ========== CONVERSATION PERSISTENCE ==========

    fun saveConversation(context: Context) {
        val prefs = prefs(context)
        val logs = _uiState.value.logs.filter { it.sender != "SYSTEM" }
        val jsonArray = JSONArray()
        for (log in logs.takeLast(50)) {
            jsonArray.put(JSONObject().put("sender", log.sender).put("text", log.text))
        }
        // Save history pairs too
        val histArray = JSONArray()
        val historyCopy = synchronized(conversationHistory) {
            conversationHistory.takeLast(MAX_HISTORY_MESSAGES).toList()
        }
        for ((role, content) in historyCopy) {
            histArray.put(JSONObject().put("role", role).put("content", content))
        }
        prefs.edit()
            .putString("saved_logs", jsonArray.toString())
            .putString("saved_history", histArray.toString())
            .apply()
        Log.d(TAG, "Conversation saved (${logs.size} messages)")
    }

    private fun restoreConversation(prefs: SharedPreferences): Boolean {
        val logsJson = prefs.getString("saved_logs", null) ?: return false
        val histJson = prefs.getString("saved_history", null)

        try {
            val jsonArray = JSONArray(logsJson)
            if (jsonArray.length() == 0) return false

            val restoredLogs = mutableListOf<LogEntry>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                restoredLogs.add(LogEntry(
                    sender = obj.getString("sender"),
                    text = obj.getString("text")
                ))
            }
            _uiState.update { it.copy(logs = restoredLogs) }

            // Restore history pairs
            if (histJson != null) {
                val histArray = JSONArray(histJson)
                synchronized(conversationHistory) {
                    conversationHistory.clear()
                    for (i in 0 until histArray.length()) {
                        val obj = histArray.getJSONObject(i)
                        conversationHistory.add(obj.getString("role") to obj.getString("content"))
                    }
                }
            }

            Log.d(TAG, "Conversation restored (${restoredLogs.size} messages, ${conversationHistory.size} history)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore conversation", e)
            return false
        }
    }

    fun clearConversation(context: Context) {
        synchronized(conversationHistory) {
            conversationHistory.clear()
        }
        _uiState.update { it.copy(logs = emptyList()) }
        prefs(context).edit().remove("saved_logs").remove("saved_history").apply()
        addSystemLog("Conversation cleared. 🗑️")
    }

    // ========== TASK SCHEDULING ==========

    fun startScheduler() {
        if (schedulerJob != null) return
        schedulerJob = viewModelScope.launch {
            while (true) {
                delay(10_000) // Check every 10 seconds
                checkScheduledTasks()
            }
        }
        Log.i(TAG, "Task scheduler started")
    }

    private fun checkScheduledTasks() {
        val now = System.currentTimeMillis()
        val readyTasks = synchronized(scheduledTasks) {
            scheduledTasks.filter { it.status == "pending" && it.executeAt <= now }
        }
        for (task in readyTasks) {
            synchronized(scheduledTasks) {
                val idx = scheduledTasks.indexOfFirst { it.id == task.id }
                if (idx >= 0) scheduledTasks[idx] = task.copy(status = "running")
            }
            addSystemLog("⏰ Running scheduled task: ${task.description}")
            sendCommand(task.command)
            synchronized(scheduledTasks) {
                val idx = scheduledTasks.indexOfFirst { it.id == task.id }
                if (idx >= 0) scheduledTasks[idx] = scheduledTasks[idx].copy(status = "done")
            }
        }
    }

    fun scheduleTask(description: String, command: String, delayMinutes: Int): String {
        val executeAt = System.currentTimeMillis() + (delayMinutes * 60_000L)
        val task = ScheduledTask(
            description = description,
            command = command,
            executeAt = executeAt
        )
        synchronized(scheduledTasks) { scheduledTasks.add(task) }
        startScheduler()
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(executeAt))
        Log.i(TAG, "Task scheduled: '${task.description}' at $timeStr")
        return "Scheduled '${task.description}' for $timeStr (in ${delayMinutes}min)"
    }

    fun listScheduledTasks(): String {
        val tasks = synchronized(scheduledTasks) { scheduledTasks.toList() }
        if (tasks.isEmpty()) return "No scheduled tasks"
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        return tasks.joinToString("\n") { t ->
            "[${t.status}] ${t.description} — ${fmt.format(Date(t.executeAt))}"
        }
    }

    fun cancelScheduledTask(description: String): Boolean {
        return synchronized(scheduledTasks) {
            val idx = scheduledTasks.indexOfFirst {
                it.status == "pending" && it.description.contains(description, ignoreCase = true)
            }
            if (idx >= 0) { scheduledTasks.removeAt(idx); true } else false
        }
    }

    // ========== HELPERS ==========

    private fun addLog(entry: LogEntry) { _uiState.update { it.copy(logs = it.logs + entry) } }
    private fun addSystemLog(text: String) { addLog(LogEntry(sender = "SYSTEM", text = text)) }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)

    override fun onCleared() {
        super.onCleared()
        schedulerJob?.cancel()
        closeAllModels()
    }
}

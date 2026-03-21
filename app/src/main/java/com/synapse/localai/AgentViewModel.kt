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
    val contextLength: Int = 4096,
    val threadCount: Int = 6,
    val maxTokens: Int = 4096,
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
        private const val MAX_TASK_ITERATIONS = 25
        
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
            contextLength = prefs.getInt("context_length", 4096),
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
        return """You are Droidrun — a sharp, clever agent that controls Android devices through tools. You've got a dry sense of humor and you read screens like a pro. Be concise.

You will be given a task to perform. You should:
1. Analyze the current device state and screenshot context.
2. Explain your reasoning.
3. Call the appropriate tool(s) using the XML format below.
4. When the task is complete, call `complete`.

## Tool Call Format

To call a tool, wrap it in <function_calls> tags:

<function_calls>
<invoke name="TOOL_NAME">
<parameter name="PARAM_NAME">value</parameter>
</invoke>
</function_calls>

Multiple <invoke> blocks in one <function_calls> block are executed sequentially.

## Available Tools

- open_app: Parameters: `text` (App label or package name)
- tap_index: Parameters: `index` (Integer [idx] from screen)
- click_text: Parameters: `text`
- type: Parameters: `text`
- back: (No parameters)
- home: (No parameters)
- scroll: Parameters: `direction` (up, down, left, right)
- swipe: Parameters: `direction` (up, down, left, right)
- complete: (No parameters)

## Important Rules
- State your intent before calling tools.
- ALWAYS use `tap_index` if the target has an [idx]!
- Use ONLY the XML format for actions.
- Messages in <Action Results> are outcomes of your previous steps."""
    }

    // ========== CHAT ==========

    fun sendCommand(command: String) {
        if (command.isBlank() || _uiState.value.isProcessing) return
        Log.d(TAG, "sendCommand: '$command'")
        addLog(LogEntry(sender = "USER", text = command))

        // Auto-keyboard switch removed to prevent force-looping issues
        
        currentJob = viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            
            try {
                // Initial screen analysis for tasks (moved inside coroutine)
                val service = SynapseAccessibilityService.instance
                val screenData = if (command.lowercase().contains(Regex("open|click|type|scroll|app"))) {
                    val appList = service?.getInstalledApps() ?: ""
                    "\n[Current Screen Context]\n" + getScreenContent().take(1500) + "\n\n$appList"
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
                    Log.i(TAG, "RAW AGENT RESPONSE: $fullResponse")
                    
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
                // Magic! Switch back to original keyboard when iteration loop finishes
                SynapseAccessibilityService.instance?.restoreOriginalKeyboard()
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
        
        // Magic! Switch back to original keyboard
        SynapseAccessibilityService.instance?.restoreOriginalKeyboard()
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
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
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
        val service = SynapseAccessibilityService.instance
        
        // Relaxed regex to catch block even if closing tag is missing or malformed
        val functionCallsRegex = "<function_calls>(.*?)(?:</function_calls>|$)".toRegex(RegexOption.DOT_MATCHES_ALL)
        val functionCallsMatch = functionCallsRegex.find(response)

        if (functionCallsMatch == null) {
            Log.w(TAG, "No valid <function_calls> XML found in response.")
            val currentScreen = getScreenContent().take(2000)
            val feedback = "[System Error] You did not provide a valid tool call. You MUST wrap your action(s) in <function_calls> and <invoke> tags.\n\n[Current Screen Context]\n$currentScreen"
            addSystemLog("⚠️ Agent deviated from format. Sending XML course-correction nudge...")
            synchronized(conversationHistory) {
                conversationHistory.add("user" to feedback)
            }
            service?.launchApp("com.synapse.localai")
            return true // Continue loop to give it a chance to output the action
        }
        
        _uiState.update { it.copy(shouldMinimize = true) }
        delay(300) // Wait for app to minimize

        if (service == null) {
            addSystemLog("⚠️ Cannot execute actions: Accessibility Service disconnected.")
            return false
        }

        var shouldContinue = false

        try {
            var invokesBlock = functionCallsMatch.groupValues[1]
            
            // Normalize self-closing tags like <invoke name="back" /> to <invoke name="back"></invoke>
            invokesBlock = invokesBlock.replace(Regex("<invoke\\s+name=[\"']([^\"']+)[\"'][^>]*/>")) { match ->
                "<invoke name=\"${match.groupValues[1]}\"></invoke>"
            }
            
            // Match invoke block even if </invoke> is missing (matches until next <invoke or end of string)
            val invokeRegex = "<invoke\\s+name=[\"']([^\"']+)[\"'][^>]*>(.*?)(?=</invoke>|<invoke|$)".toRegex(RegexOption.DOT_MATCHES_ALL)
            val paramRegex = "<parameter\\s+name=[\"']([^\"']+)[\"'][^>]*>(.*?)(?=</parameter>|<parameter|$)".toRegex(RegexOption.DOT_MATCHES_ALL)
            
            val invokes = invokeRegex.findAll(invokesBlock).toList()
            
            if (invokes.isEmpty()) {
                val feedback = "[System Error] Empty <function_calls> block or malformed <invoke> tags. You must use <invoke name=\"...\">...</invoke>."
                synchronized(conversationHistory) { conversationHistory.add("user" to feedback) }
                service.launchApp("com.synapse.localai")
                return true
            }

            val results = mutableListOf<String>()
            var isComplete = false

            for (invokeMatch in invokes) {
                val action = invokeMatch.groupValues[1]
                var paramsBlock = invokeMatch.groupValues[2]
                
                // Normalize self-closing parameters <parameter name="x" />
                paramsBlock = paramsBlock.replace(Regex("<parameter\\s+name=[\"']([^\"']+)[\"'][^>]*/>")) { match ->
                    "<parameter name=\"${match.groupValues[1]}\"></parameter>"
                }
                
                val params = mutableMapOf<String, String>()
                paramRegex.findAll(paramsBlock).forEach { match ->
                    params[match.groupValues[1]] = match.groupValues[2].trim()
                }
                
                if (action == "complete") {
                    addSystemLog("✅ Task marked as complete.")
                    isComplete = true
                    break
                }            
                
                var result = ""

                when (action) {
                    "open_app" -> {
                        val app = params["text"] ?: ""
                        val success = service.launchApp(app)
                        result = if (success) "Opened $app" else "Failed to open $app"
                    }
                    "click_text" -> {
                        val text = params["text"] ?: ""
                        val success = service.clickByText(text)
                        result = if (success) "Clicked text: $text" else "Text not found: $text"
                    }
                    "tap_index", "click" -> {
                        val indexStr = params["index"] ?: "-1"
                        val index = indexStr.toIntOrNull() ?: -1
                        val success = service.clickByIndex(index)
                        result = if (success) "Tapped index: $index" else "Index not found: $index"
                    }
                    "type" -> {
                        val text = params["text"] ?: ""
                        val success = service.typeText(text)
                        result = if (success) "Typed: $text" else "Failed to type (no focused input)"
                    }
                    "back" -> { service.goBack(); result = "Pressed back" }
                    "home" -> { service.goHome(); result = "Pressed home" }
                    "scroll" -> {
                        val dir = params["direction"] ?: "down"
                        val success = service.scrollScreen(dir)
                        result = if (success) "Scrolled $dir" else "Failed to scroll"
                    }
                    "swipe" -> {
                        val dir = params["direction"] ?: "down"
                        val success = service.swipeDirection(dir)
                        result = if (success) "Swiped $dir" else "Failed to swipe"
                    }
                    else -> {
                        result = "Unknown action: $action"
                    }
                }
                
                addSystemLog("⚡ $result")
                results.add(result)
                delay(1500) // Delay between batched actions
            }

            if (isComplete) {
                service.launchApp("com.synapse.localai")
                return false
            }
            
            delay(3000) // INCREASED: Wait for UI to settle after all actions before grabbing new screen

            val combinedResult = results.joinToString("\n")
            val newScreen = getScreenContent().take(3000)
            val feedback = "<Action Results>\n$combinedResult\n</Action Results>\n\n[Current Screen]\n$newScreen"
            
            synchronized(conversationHistory) {
                conversationHistory.add("user" to feedback)
            }

            shouldContinue = true
        } catch (e: Exception) {
            Log.e(TAG, "Tool error parsing XML", e)
            val feedback = "[System Error] Failed to parse XML action: ${e.message}."
            addSystemLog("⚠️ Action format error, asking agent to retry.")
            synchronized(conversationHistory) {
                conversationHistory.add("user" to feedback)
            }
            service.launchApp("com.synapse.localai")
            shouldContinue = true
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

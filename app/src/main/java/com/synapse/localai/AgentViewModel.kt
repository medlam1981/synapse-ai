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
import java.util.*

enum class ModelType { GGUF, GEMINI }

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
    val geminiApiKeys: String = "",
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
    val shouldMinimize: Boolean = false,
    val scheduledTasks: List<ScheduledTask> = emptyList(),
    val lastExecutionResult: String = ""
)

class AgentViewModel : ViewModel() {

    companion object {
        private const val TAG = "SYNAPSE_CORE"
        private const val MAX_HISTORY_MESSAGES = 10
        private const val MAX_TASK_ITERATIONS = 999
        
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
    private var geminiOllamaServer: GeminiOllamaServer? = null
    private var currentJob: Job? = null
    private var schedulerJob: Job? = null
    private val scheduledTasks = Collections.synchronizedList(mutableListOf<ScheduledTask>())
    
    // Core Mission tracking (Droidrun Logic)
    private var coreMission: String = ""

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
        val useLocal = prefs.getBoolean("use_local_model", false) // Default to Cloud
        var geminiKeys = prefs.getString("gemini_api_keys", "") ?: ""
        
        val config = AgentConfig(
            useLocalModel = useLocal,
            localModelPath = savedPath ?: "",
            geminiApiKeys = geminiKeys,
            serverHost = prefs.getString("server_host", "0.0.0.0") ?: "0.0.0.0",
            serverPort = prefs.getInt("server_port", 11434),
            contextLength = prefs.getInt("context_length", 4096),
            threadCount = prefs.getInt("thread_count", 6),
            maxTokens = prefs.getInt("max_tokens", 2048),
            temperature = prefs.getFloat("temperature", 0.1f)
        )
        _uiState.update { it.copy(config = config) }

        val activeKeyCount = geminiKeys.split(",").filter { it.trim().isNotEmpty() }.size
        if (!useLocal && activeKeyCount == 0) {
            addLog(LogEntry(sender = "SYSTEM", text = "⚠️ NO API KEYS: Go to Settings to add your Gemini keys.", isError = true))
        } else {
            addSystemLog("⚡ System Online. Active Keys: $activeKeyCount")
        }

        // Restore saved conversation
        restoreConversation(prefs)

        if (config.useLocalModel && config.localModelPath.isNotBlank()) {
            // Only load local model if explicitly in Local Mode
            viewModelScope.launch {
                loadModel(context, config.localModelPath)
            }
        } else if (!config.useLocalModel && config.geminiApiKeys.isNotBlank()) {
            // Initialize Gemini if in Cloud Mode
            loadGeminiModel(config.geminiApiKeys)
        }
    }

    fun saveConfig(context: Context, config: AgentConfig) {
        val oldPath = _uiState.value.config.localModelPath
        val oldKeys = _uiState.value.config.geminiApiKeys
        val oldUseLocal = _uiState.value.config.useLocalModel
        
        prefs(context).edit()
            .putBoolean("use_local_model", config.useLocalModel)
            .putString("local_model_path", config.localModelPath)
            .putString("gemini_api_keys", config.geminiApiKeys)
            .putString("server_host", config.serverHost)
            .putInt("server_port", config.serverPort)
            .putInt("context_length", config.contextLength)
            .putInt("thread_count", config.threadCount)
            .putInt("max_tokens", config.maxTokens)
            .putFloat("temperature", config.temperature)
            .apply()
        _uiState.update { it.copy(config = config) }

        if (config.useLocalModel) {
            if (config.localModelPath != oldPath || !isModelLoaded() || !oldUseLocal) {
                closeAllModels()
                loadModel(context, config.localModelPath)
            }
        } else {
            if (config.geminiApiKeys != oldKeys || !isModelLoaded() || oldUseLocal) {
                closeAllModels()
                loadGeminiModel(config.geminiApiKeys)
            }
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
            ModelType.GEMINI -> { /* Gemini is loaded via loadGeminiModel */ }
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

    private fun loadGeminiModel(apiKeysRaw: String) {
        val keys = apiKeysRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (keys.isEmpty()) return
        
        _uiState.update { it.copy(localModelLoading = true, localModelLoaded = false, activeModelType = null) }
        
        try {
            val config = _uiState.value.config
            val client = GeminiModelClient(
                apiKeys = keys,
                onKeyRotated = { index ->
                    addSystemLog("🔄 Zero Rate Limit: Switched to API Key #$index")
                }
            ).apply {
                maxTokens = config.maxTokens
                temperature = config.temperature
            }
            geminiClient = client
            geminiOllamaServer?.stop()
            geminiOllamaServer = GeminiOllamaServer(11434, client)
            geminiOllamaServer?.start()
            _uiState.update { 
                it.copy(
                                        localModelLoaded = true,
                                        localModelLoading = false,
                                        activeModelType = ModelType.GEMINI,
                                        activeModelName = "gemini-2.5-flash"
                                    )
                                }        } catch (e: Exception) {
            Log.e(TAG, "Gemini load failed", e)
            _uiState.update { it.copy(localModelLoaded = false, localModelLoading = false) }
        }
    }

    private fun isModelLoaded(): Boolean {
        return ggufClient?.isLoaded == true || geminiClient?.isLoaded == true
    }

    private fun closeAllModels() {
        ggufClient?.close()
        ggufClient = null
        geminiOllamaServer?.stop()
        geminiOllamaServer = null
        geminiClient?.close()
        geminiClient = null
        _uiState.update { it.copy(localModelLoaded = false, activeModelType = null, activeModelName = "") }
    }

    // ========== Medlam-AI-PORTAL CORE LOGIC ==========

    private fun buildSystemPrompt(): String {
        return """You are Medlam-AI — a sharp, clever agent that controls Android devices through tools. You read screens like a pro. Be concise.

IMPORTANT: Detect the user's language (including Moroccan Darija) and ALWAYS reply in that same language.

You will be given a task to perform. You should:
1. Analyze the current device state and UI elements.
2. Explain your reasoning.
3. Call the appropriate tool(s) using the XML format below.
4. When the task is complete, call `complete`.

## Tool Call Format
<function_calls>
<invoke name="TOOL_NAME">
<parameter name="PARAM_NAME">value</parameter>
</invoke>
</function_calls>

Multiple <invoke> blocks in one <function_calls> block are executed sequentially.

## Available Tools
- open_app: Parameters: `text` (App label or package name)
- tap_index: Parameters: `index` (The integer index from the UI list)
- click_text: Parameters: `text`
- type: Parameters: `text` (Types into the focused field)
- back, home: (No parameters)
- scroll, swipe: Parameters: `direction` (up, down, left, right)
- complete: Parameters: `text` (Final answer to user)

## Important Rules
- ALWAYS use `tap_index` if the target element has an index number!
- The UI list follows this schema: 'index. className: resourceId; checkedState, text - bounds(x1,y1,x2,y2)'
- If you searched and see the result, click the index immediately.
- Use ONLY the XML format for actions."""
    }

    // ========== CHAT ==========

    fun sendCommand(command: String, fileUri: Uri? = null, context: Context? = null) {
        if (command.isBlank() && fileUri == null) return
        if (_uiState.value.isProcessing) return
        
        val displayCommand = if (command.isNotBlank()) command else "[Attached File]"
        Log.i(TAG, ">>> sendCommand initiated: '$displayCommand'")
        addLog(LogEntry(sender = "USER", text = displayCommand))
        
        coreMission = command

        val service = SynapseAccessibilityService.instance
        if (service != null && !SynapseKeyboardIME.isSelected(service)) {
            service.switchToSynapseKeyboard()
        }
        
        service?.acquireWakeLock()

        currentJob = viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            
            try {
                // Process File Attachment
                var finalCommand = command
                val imageBase64List = mutableListOf<String>()

                if (fileUri != null && context != null) {
                    val contentResolver = context.contentResolver
                    val type = contentResolver.getType(fileUri) ?: ""
                    
                    if (type.startsWith("image/")) {
                        val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, fileUri)
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        imageBase64List.add(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                        if (finalCommand.isBlank()) finalCommand = "Please analyze this image."
                    } else {
                        // Try reading as text
                        try {
                            val text = contentResolver.openInputStream(fileUri)?.bufferedReader()?.use { it.readText() } ?: ""
                            finalCommand += "\n\n[FILE_CONTENT]:\n$text"
                        } catch (e: Exception) {
                            addSystemLog("⚠️ Could not read file as text: ${e.message}")
                        }
                    }
                }

                delay(2000) 

                val initialPhoneState = service?.getDroidrunPhoneState() ?: ""
                val initialScreenTree = service?.getDroidrunScreenTree() ?: ""
                val timeNow = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                
                val screenData = "\n[Current Time: $timeNow]\n$initialPhoneState\n\n$initialScreenTree"

                synchronized(conversationHistory) {
                    conversationHistory.add("user" to finalCommand + screenData)
                }

                val modelType = _uiState.value.activeModelType
                if (modelType == null || !isModelLoaded()) {
                    addLog(LogEntry(sender = "SYSTEM", text = "Model not loaded. Go to Settings to load a model.", isError = true))
                    return@launch
                }

                val systemPrompt = buildSystemPrompt()
                var keepGoing = true
                var iterationCount = 0

                while (keepGoing && iterationCount < MAX_TASK_ITERATIONS) {
                    iterationCount++
                    val responseId = java.util.UUID.randomUUID().toString()
                    addLog(LogEntry(id = responseId, sender = "AGENT", text = ""))

                    val responseSb = StringBuilder(512)

                    try {
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
                            ModelType.GEMINI -> {
                                // For the first turn of a multimodal request, we need a special call
                                // but our current architecture uses a list of pairs.
                                // Let's hack the first message if images exist.
                                val geminiHistory = synchronized(conversationHistory) {
                                    conversationHistory.takeLast(MAX_HISTORY_MESSAGES).toList()
                                }
                                
                                // Convert to JSONArray for our internal bridge format
                                val messagesJson = JSONArray()
                                for (i in 0 until geminiHistory.size) {
                                    val msg = JSONObject().put("role", geminiHistory[i].first).put("content", geminiHistory[i].second)
                                    // Add images to the VERY LAST user message if it's the start of the task
                                    if (i == geminiHistory.size - 1 && imageBase64List.isNotEmpty()) {
                                        val imgs = JSONArray()
                                        imageBase64List.forEach { imgs.put(it) }
                                        msg.put("images", imgs)
                                    }
                                    messagesJson.put(msg)
                                }

                                withContext(Dispatchers.Default) {
                                    geminiClient!!.generateStreamWithOllamaMessages(
                                        messagesJson,
                                        systemPrompt
                                    ).collect { chunk ->
                                        responseSb.append(chunk)
                                        updateStreamingLog(responseId, responseSb.toString())
                                    }
                                }
                                updateStreamingLog(responseId, responseSb.toString(), force = true)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Inference failed at iteration $iterationCount", e)
                        val errorMsg = e.localizedMessage ?: "Unknown Error"
                        addLog(LogEntry(sender = "SYSTEM", text = "Inference Failed: $errorMsg", isError = true))
                        
                        if (errorMsg.contains("All") && errorMsg.contains("keys")) {
                            addSystemLog("❌ Mission Aborted: All API Keys are currently unavailable.")
                            keepGoing = false
                            break
                        }
                        
                        addSystemLog("⚠️ Attempting one-time recovery nudge...")
                        delay(3000)
                        val phoneState = service?.getDroidrunPhoneState() ?: ""
                        val screenTree = service?.getDroidrunScreenTree() ?: ""
                        synchronized(conversationHistory) {
                            conversationHistory.add("user" to "[System Recovery] An error occurred. Mission: $coreMission\n$phoneState\n\n$screenTree")
                        }
                        keepGoing = true
                        continue
                    }

                    val fullResponse = responseSb.toString()
                    Log.d("SYNAPSE_DEBUG", "Iteration $iterationCount fullResponse length: ${fullResponse.length}")
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
                        Log.d("SYNAPSE_DEBUG", "executeToolLoop returned keepGoing=$keepGoing")
                    } else {
                        Log.w(TAG, "Iteration $iterationCount: Empty response. Nudging...")
                        val phoneState = service?.getDroidrunPhoneState() ?: ""
                        val screenTree = service?.getDroidrunScreenTree() ?: ""
                        val nudge = "[System Notice] You returned an empty response. MISSION: $coreMission\n$phoneState\n\n$screenTree"
                        synchronized(conversationHistory) {
                            conversationHistory.add("user" to nudge)
                        }
                        keepGoing = true 
                    }
                }
                
                if (iterationCount >= MAX_TASK_ITERATIONS) {
                    Log.w(TAG, "Reached maximum task iterations ($MAX_TASK_ITERATIONS)")
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
                val s = SynapseAccessibilityService.instance
                s?.restoreOriginalKeyboard()
                s?.releaseWakeLock()
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

    private suspend fun executeToolLoop(response: String): Boolean {
        val service = SynapseAccessibilityService.instance
        
        // Strip markdown blocks if present
        var cleanResponse = response.replace("```xml", "").replace("```", "").trim()
        
        // Find the start of function_calls
        val startIndex = cleanResponse.indexOf("<function_calls>")
        
        if (startIndex == -1) {
            Log.w(TAG, "No valid <function_calls> XML found in response.")
            val phoneState = service?.getDroidrunPhoneState() ?: ""
            val screenTree = service?.getDroidrunScreenTree() ?: ""
            val feedback = "[System Error] You did not provide a valid tool call. You MUST wrap your action(s) in <function_calls> and <invoke> tags.\n\n[CORE MISSION: $coreMission]\n$phoneState\n\n$screenTree"
            addSystemLog("⚠️ Agent deviated from format. Sending XML course-correction nudge...")
            synchronized(conversationHistory) {
                conversationHistory.add("user" to feedback)
            }
            service?.launchApp("com.synapse.localai")
            return true // Continue loop to give it a chance to output the action
        }
        
        // Extract everything after <function_calls>
        var invokesBlock = cleanResponse.substring(startIndex + "<function_calls>".length)
        
        // Strip trailing </function_calls> if it exists
        val endIndex = invokesBlock.indexOf("</function_calls>")
        if (endIndex != -1) {
            invokesBlock = invokesBlock.substring(0, endIndex)
        }
        
        _uiState.update { it.copy(shouldMinimize = true) }
        delay(300) // Wait for app to minimize

        if (service == null) {
            addSystemLog("⚠️ Cannot execute actions: Accessibility Service disconnected.")
            return false
        }

        var shouldContinue = false

        try {
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
                    val finalMessage = params["text"] ?: "Task completed."
                    addSystemLog("✅ $finalMessage")
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
                        val indexStr = params["index"] ?: params["i"] ?: "-1"
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
                        val moved = service.scrollScreen(dir)
                        result = if (moved) "Scrolled $dir successfully." 
                                 else "CRITICAL: End of Page reached. Cannot scroll $dir further. Your target MUST be on this screen."
                    }
                    "swipe" -> {
                        val dir = params["direction"] ?: "down"
                        val success = service.swipeDirection(dir)
                        result = if (success) "Swiped $dir" else "Failed to swipe"
                    }
                    "read_notifications" -> {
                        val notifications = SynapseNotificationService.getRecentAndClear()
                        result = "Recent Notifications:\n$notifications"
                    }
                    "schedule_task" -> {
                        val desc = params["description"] ?: "Task"
                        val cmd = params["command"] ?: ""
                        val delay = params["delay"]?.toIntOrNull() ?: 1
                        if (cmd.isNotBlank()) {
                            result = scheduleTask(desc, cmd, delay)
                        } else {
                            result = "Failed to schedule: command is empty"
                        }
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
            
            delay(4000) // INCREASED: Long delay to ensure UI animations (like scrolling) finish completely

            val combinedResult = results.joinToString("\n")
            _uiState.update { it.copy(lastExecutionResult = combinedResult) }
            
            val phoneState = service.getDroidrunPhoneState()
            val screenTree = service.getDroidrunScreenTree()
            
            val feedback = "[CORE MISSION: $coreMission]\n$phoneState\n\n<Action Results>\n$combinedResult\n</Action Results>\n\n$screenTree"
            
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
        _uiState.update { it.copy(logs = emptyList(), lastExecutionResult = "") }
        prefs(context).edit().remove("saved_logs").remove("saved_history").apply()
    }

    // ========== TASK SCHEDULING ==========

    private fun updateUiTasks() {
        _uiState.update { it.copy(scheduledTasks = synchronized(scheduledTasks) { scheduledTasks.toList() }) }
    }

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
            updateUiTasks()
            addSystemLog("⏰ Running scheduled task: ${task.description}")
            sendCommand(task.command)
            synchronized(scheduledTasks) {
                val idx = scheduledTasks.indexOfFirst { it.id == task.id }
                if (idx >= 0) scheduledTasks[idx] = scheduledTasks[idx].copy(status = "done")
            }
            updateUiTasks()
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
        updateUiTasks()
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
            if (idx >= 0) { 
                scheduledTasks.removeAt(idx)
                updateUiTasks()
                true 
            } else false
        }
    }

    // ========== HELPERS ==========

    private fun addLog(entry: LogEntry) { _uiState.update { it.copy(logs = it.logs + entry) } }
    fun addSystemLog(text: String) { addLog(LogEntry(sender = "SYSTEM", text = text)) }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)

    override fun onCleared() {
        super.onCleared()
        schedulerJob?.cancel()
        closeAllModels()
    }
}

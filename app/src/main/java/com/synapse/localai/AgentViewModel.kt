package com.synapse.localai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

enum class ModelType { LITERTLM, GGUF }

data class AgentConfig(
    val useLocalModel: Boolean = true,
    val localModelPath: String = "",
    val serverHost: String = "127.0.0.1",
    val serverPort: Int = 8080,
    val contextLength: Int = 2048,
    val threadCount: Int = 2,
    val overlayEnabled: Boolean = false,
    val overlaySize: Float = 1.0f
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
    val accessibilityConnected: Boolean = false
)

class AgentViewModel : ViewModel() {

    companion object {
        private const val TAG = "AgentViewModel"
        private const val MAX_HISTORY_MESSAGES = 20

        fun detectModelType(path: String): ModelType {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "gguf", "bin" -> ModelType.GGUF
                else -> ModelType.LITERTLM
            }
        }
    }

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private val litertlmClient: LocalModelClient = LocalModelClient.shared
    private var ggufClient: GGUFModelClient? = null
    private var currentJob: Job? = null

    // Conversation history for context (role + content pairs)
    private val conversationHistory = mutableListOf<Pair<String, String>>()

    // ========== CONFIG ==========

    fun loadConfig(context: Context) {
        val prefs = prefs(context)
        val savedPath = prefs.getString("local_model_path", null)
        val config = AgentConfig(
            useLocalModel = savedPath != null,
            localModelPath = savedPath ?: "",
            serverHost = prefs.getString("server_host", "127.0.0.1") ?: "127.0.0.1",
            serverPort = prefs.getInt("server_port", 8080),
            contextLength = prefs.getInt("context_length", 2048),
            threadCount = prefs.getInt("thread_count", 2),
            overlayEnabled = prefs.getBoolean("overlay_enabled", false),
            overlaySize = prefs.getFloat("overlay_size", 1.0f)
        )
        _uiState.update { it.copy(config = config) }

        // Apply overlay settings
        applyOverlayConfig(config)

        // Restore saved conversation
        val restored = restoreConversation(prefs)
        if (restored) {
            addSystemLog("Welcome back! Previous conversation restored. 🧠")
        } else {
            addSystemLog("Welcome to Synapse AI! 🧠\nGo to ⚙️ Settings to select and load an AI model.")
        }

        if (config.useLocalModel && config.localModelPath.isNotBlank()) {
            loadModel(context, config.localModelPath)
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
            .putBoolean("overlay_enabled", config.overlayEnabled)
            .putFloat("overlay_size", config.overlaySize)
            .apply()
        _uiState.update { it.copy(config = config) }

        // Apply overlay settings
        applyOverlayConfig(config)

        addSystemLog("> Config saved.")

        if (config.useLocalModel) {
            if (config.localModelPath != oldPath || !isModelLoaded()) {
                closeAllModels()
                loadModel(context, config.localModelPath)
            }
        } else {
            closeAllModels()
        }
    }

    private fun applyOverlayConfig(config: AgentConfig) {
        val service = SynapseAccessibilityService.instance ?: return
        service.setOverlayScale(config.overlaySize)
        service.setOverlayEnabled(config.overlayEnabled)
        if (config.overlayEnabled) {
            service.refreshOverlay()
        }
    }

    fun loadModel(context: Context, path: String) {
        if (_uiState.value.localModelLoading) return
        val type = detectModelType(path)
        val modelName = java.io.File(path).nameWithoutExtension

        _uiState.update { it.copy(localModelLoading = true, localModelLoaded = false, activeModelType = null) }
        addSystemLog("> Loading ${type.name} model: $modelName...")

        closeAllModels()

        when (type) {
            ModelType.LITERTLM -> loadLitertlm(context, path, modelName)
            ModelType.GGUF -> loadGguf(context, path, modelName)
        }
    }

    private fun loadLitertlm(context: Context, path: String, modelName: String) {
        viewModelScope.launch {
            try {
                litertlmClient.loadModel(context, path)
                _uiState.update { it.copy(localModelLoaded = true, localModelLoading = false, activeModelType = ModelType.LITERTLM, activeModelName = modelName) }
                addSystemLog("> LiteRT model loaded: $modelName ✓")
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT-LM load failed", e)
                _uiState.update { it.copy(localModelLoaded = false, localModelLoading = false) }
                addLog(LogEntry(sender = "SYSTEM", text = "LiteRT-LM failed: ${e.message}", isError = true))
            }
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
                }
                ggufClient = client
                client.loadModel(
                    context = context,
                    path = path,
                    contextLength = 2048,
                    onStatus = { status -> addSystemLog("> $status") },
                    onLoaded = {
                        _uiState.update { it.copy(localModelLoaded = true, localModelLoading = false, activeModelType = ModelType.GGUF, activeModelName = modelName) }
                        addSystemLog("> GGUF model loaded: $modelName ✓")
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "GGUF load failed", e)
                _uiState.update { it.copy(localModelLoaded = false, localModelLoading = false) }
                addLog(LogEntry(sender = "SYSTEM", text = "GGUF load failed: ${e.message}", isError = true))
            }
        }
    }

    private fun isModelLoaded(): Boolean {
        return litertlmClient.isLoaded || (ggufClient?.isLoaded == true)
    }

    private fun closeAllModels() {
        litertlmClient.close()
        ggufClient?.close()
        ggufClient = null
        _uiState.update { it.copy(localModelLoaded = false, activeModelType = null, activeModelName = "") }
    }

    // ========== SYSTEM PROMPT WITH TIME ==========

    private fun buildSystemPrompt(): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss (EEEE)", Locale.ENGLISH).format(Date())
        val tz = TimeZone.getDefault().displayName
        val hasA11y = SynapseAccessibilityService.isConnected
        val toolsSection = if (hasA11y) """

You have DEVICE CONTROL capabilities. When the user asks you to perform an action on the device, respond with the tool command on its own line prefixed with >>>.

Available tools:
— APP CONTROL —
>>> LAUNCH <app_name>             — Open an app (e.g. >>> LAUNCH Chrome)
>>> STOP_APP <app_name>           — Force stop an app
>>> PACKAGES                      — List all installed apps
>>> OPEN_URL <url>                — Open a URL in browser
>>> APP_SETTINGS <app_name>       — Open app info/settings page

— GESTURES —
>>> CLICK <x> <y>                — Tap screen at coordinates
>>> CLICK_TEXT <text>             — Tap on visible text/button
>>> TAP_INDEX <n>                 — Tap element by index (from SCREEN_TREE)
>>> LONG_PRESS <x> <y>           — Long press at coordinates
>>> SWIPE <x1> <y1> <x2> <y2>   — Swipe between two points
>>> SWIPE_UP                      — Swipe up from center
>>> SWIPE_DOWN                    — Swipe down from center
>>> SWIPE_LEFT                    — Swipe left from center
>>> SWIPE_RIGHT                   — Swipe right from center

— TEXT INPUT —
>>> TYPE <text>                   — Set text in focused field
>>> APPEND <text>                 — Append text to focused field
>>> DELETE <n>                    — Delete last n characters
>>> CLEAR                         — Clear focused text field

— NAVIGATION —
>>> BACK                          — Press back button
>>> HOME                          — Go to home screen
>>> RECENTS                       — Open recent apps
>>> NOTIFICATIONS                 — Open notification shade
>>> SCROLL UP                     — Scroll up (uses scrollable element)
>>> SCROLL DOWN                   — Scroll down
>>> SCROLL LEFT                   — Scroll left
>>> SCROLL RIGHT                  — Scroll right

— DEVICE STATE —
>>> READ_SCREEN                   — Read all text visible on screen
>>> SCREEN_TREE                   — Get UI element tree with indices
>>> SCREEN_SUMMARY                — Quick screen state overview
>>> SCREENSHOT                    — Take a screenshot (base64)
>>> CURRENT_APP                   — Get current foreground app

— HUD OVERLAY —
>>> HUD_ON                        — Enable HUD overlay (shows element indices on screen)
>>> HUD_OFF                       — Disable HUD overlay
>>> HUD_REFRESH                   — Refresh HUD overlay with current screen elements

— SYSTEM SETTINGS —
>>> VOLUME <0-100>                — Set media volume
>>> BRIGHTNESS <0-100>            — Set screen brightness
>>> OPEN_SETTINGS                 — Open Android settings
>>> OPEN_WIFI                     — Open WiFi settings
>>> OPEN_BLUETOOTH                — Open Bluetooth settings
>>> OPEN_DISPLAY                  — Open display settings
>>> OPEN_SOUND                    — Open sound settings
>>> OPEN_BATTERY                  — Open battery settings

Rules:
- Put each command on its own line starting with >>>
- You can use multiple tools in one response
- After each tool, briefly explain what you did
- For clicking buttons, prefer CLICK_TEXT when you know the text
- Use SCREEN_TREE or SCREEN_SUMMARY to see what's on screen first
- TAP_INDEX is the most precise way to tap (uses element index from SCREEN_TREE)
- Use SWIPE_UP/DOWN/LEFT/RIGHT for easy navigation
- For apps, use their common name (Chrome, Settings, WhatsApp)
- When navigating, use SCREEN_TREE → TAP_INDEX workflow for precision""" else ""

        return """You are Synapse AI, a powerful and friendly local AI assistant running on the user's Android device.

Current date and time: $now
Timezone: $tz
Device: Android (local on-device inference)${if (hasA11y) "\nDevice Control: ENABLED (Accessibility Service active)" else "\nDevice Control: DISABLED (enable in Android Accessibility Settings)"}

Instructions:
- Answer questions clearly, thoroughly, and helpfully.
- You have full knowledge of the conversation history. Use it to maintain context.
- If asked about the time, date, or day, use the current date/time provided above.
- Be honest about your limitations: you run locally and cannot browse the internet or access external services.
- Never stop mid-sentence. Always complete your thoughts and answers fully.
- If a response is long, structure it with headings, bullet points, or numbered lists.
- Be friendly, informative, and conversational.$toolsSection"""
    }

    // ========== CHAT ==========

    fun sendCommand(command: String) {
        if (command.isBlank() || _uiState.value.isProcessing) return
        Log.d(TAG, "sendCommand: '$command'")
        addLog(LogEntry(sender = "USER", text = command))
        conversationHistory.add("user" to command)
        _uiState.update { it.copy(isProcessing = true, accessibilityConnected = SynapseAccessibilityService.isConnected) }

        currentJob = viewModelScope.launch {
            try {
                val modelType = _uiState.value.activeModelType
                if (modelType == null || !isModelLoaded()) {
                    addLog(LogEntry(sender = "SYSTEM", text = "Model not loaded. Go to Settings to load a model.", isError = true))
                    return@launch
                }

                val responseId = java.util.UUID.randomUUID().toString()
                addLog(LogEntry(id = responseId, sender = "AGENT", text = ""))

                var fullResponse = ""
                val systemPrompt = buildSystemPrompt()

                when (modelType) {
                    ModelType.LITERTLM -> {
                        val contextPrompt = buildContextForLitertlm(command)
                        litertlmClient.generateResponseStream(contextPrompt, systemPrompt)
                            .collect { chunk ->
                                fullResponse += chunk
                                updateStreamingLog(responseId, fullResponse)
                            }
                    }
                    ModelType.GGUF -> {
                        ggufClient!!.generateStreamWithHistory(
                            conversationHistory.takeLast(MAX_HISTORY_MESSAGES),
                            systemPrompt
                        ).collect { chunk ->
                            fullResponse += chunk
                            updateStreamingLog(responseId, fullResponse)
                        }
                    }
                }

                if (fullResponse.isBlank()) {
                    _uiState.update { state -> state.copy(logs = state.logs.filter { it.id != responseId }) }
                    addLog(LogEntry(sender = "SYSTEM", text = "Model returned empty response.", isError = true))
                } else {
                    val cleanResponse = fullResponse.replace(Regex("^💭.*?\n\n", RegexOption.DOT_MATCHES_ALL), "").trim()
                    conversationHistory.add("assistant" to cleanResponse)
                    while (conversationHistory.size > MAX_HISTORY_MESSAGES) {
                        conversationHistory.removeAt(0)
                    }

                    // Execute any tool commands in the response
                    executeToolCommands(fullResponse)
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

    // ========== TOOL EXECUTION ==========

    private suspend fun executeToolCommands(response: String) {
        val service = SynapseAccessibilityService.instance ?: return

        // Look for >>> commands first
        val toolLines = response.lines().filter { it.trimStart().startsWith(">>>") }

        if (toolLines.isEmpty()) return

        for (line in toolLines) {
            val cmd = line.trimStart().removePrefix(">>>").trim()
            if (cmd.isEmpty()) continue

            try {
                val result = executeOneCommand(service, cmd)
                if (result != null) {
                    addSystemLog("⚡ $result")
                }
                kotlinx.coroutines.delay(300)
            } catch (e: Exception) {
                Log.e(TAG, "Tool exec failed: $cmd", e)
                addSystemLog("⚠ Tool error: ${e.message}")
            }
        }
    }

    private suspend fun executeOneCommand(service: SynapseAccessibilityService, cmd: String): String? {
        val parts = cmd.split(" ", limit = 2)
        val action = parts[0].uppercase()
        val args = if (parts.size > 1) parts[1].trim() else ""

        return when (action) {
            // --- APP CONTROL ---
            "LAUNCH" -> {
                val ok = service.launchApp(args)
                if (ok) "Launched: $args" else "App not found: $args"
            }
            "STOP_APP" -> {
                val ok = service.stopApp(args)
                if (ok) "Stopped: $args" else "Failed to stop: $args"
            }
            "PACKAGES" -> {
                val list = service.getInstalledPackages()
                val preview = list.take(1200)
                addLog(LogEntry(sender = "SYSTEM", text = "📦 Installed apps:\n$preview${if (list.length > 1200) "\n..." else ""}"))
                null
            }
            "OPEN_URL" -> {
                val ok = service.openUrl(args)
                if (ok) "Opened URL: $args" else "Failed to open URL"
            }
            "APP_SETTINGS" -> {
                val ok = service.openAppSettings(args)
                if (ok) "Opened settings for: $args" else "Failed to open app settings"
            }

            // --- GESTURES ---
            "CLICK" -> {
                val coords = args.split(" ").mapNotNull { it.toFloatOrNull() }
                if (coords.size >= 2) {
                    service.performClick(coords[0], coords[1])
                    kotlinx.coroutines.delay(200)
                    "Clicked at (${coords[0].toInt()}, ${coords[1].toInt()})"
                } else null
            }
            "CLICK_TEXT" -> {
                val ok = service.clickByText(args)
                if (ok) "Clicked: \"$args\"" else "Text not found: \"$args\""
            }
            "TAP_INDEX" -> {
                val index = args.trim().toIntOrNull()
                if (index != null) {
                    val ok = service.clickByIndex(index)
                    if (ok) "Tapped element #$index" else "Element #$index not found"
                } else null
            }
            "LONG_PRESS" -> {
                val coords = args.split(" ").mapNotNull { it.toFloatOrNull() }
                if (coords.size >= 2) {
                    service.performLongPress(coords[0], coords[1])
                    kotlinx.coroutines.delay(1300)
                    "Long pressed at (${coords[0].toInt()}, ${coords[1].toInt()})"
                } else null
            }
            "SWIPE" -> {
                val coords = args.split(" ").mapNotNull { it.toFloatOrNull() }
                if (coords.size >= 4) {
                    service.performSwipe(coords[0], coords[1], coords[2], coords[3])
                    kotlinx.coroutines.delay(500)
                    "Swiped (${coords[0].toInt()},${coords[1].toInt()}) → (${coords[2].toInt()},${coords[3].toInt()})"
                } else null
            }

            // --- TEXT INPUT ---
            "TYPE" -> {
                val ok = service.typeText(args)
                if (ok) "Typed text" else "No editable field focused"
            }
            "APPEND" -> {
                val ok = service.appendText(args)
                if (ok) "Appended text" else "No editable field focused"
            }
            "DELETE" -> {
                val count = args.trim().toIntOrNull() ?: 1
                val ok = service.deleteText(count)
                if (ok) "Deleted $count char(s)" else "No editable field or empty"
            }
            "CLEAR" -> {
                val ok = service.typeText("")
                if (ok) "Cleared text field" else "No editable field focused"
            }

            // --- NAVIGATION ---
            "BACK" -> { service.goBack(); "Pressed Back" }
            "HOME" -> { service.goHome(); "Pressed Home" }
            "RECENTS" -> { service.openRecents(); "Opened Recents" }
            "NOTIFICATIONS" -> { service.openNotifications(); "Opened Notifications" }
            "SCROLL" -> {
                val ok = service.scrollScreen(args)
                if (ok) "Scrolled $args" else "Scroll failed"
            }

            // --- DIRECTIONAL SWIPE ---
            "SWIPE_UP" -> {
                service.swipeDirection("UP")
                kotlinx.coroutines.delay(500)
                "Swiped up"
            }
            "SWIPE_DOWN" -> {
                service.swipeDirection("DOWN")
                kotlinx.coroutines.delay(500)
                "Swiped down"
            }
            "SWIPE_LEFT" -> {
                service.swipeDirection("LEFT")
                kotlinx.coroutines.delay(500)
                "Swiped left"
            }
            "SWIPE_RIGHT" -> {
                service.swipeDirection("RIGHT")
                kotlinx.coroutines.delay(500)
                "Swiped right"
            }

            // --- DEVICE STATE ---
            "READ_SCREEN" -> {
                val text = service.getScreenText()
                val preview = text.take(500)
                addLog(LogEntry(sender = "SYSTEM", text = "📱 Screen text:\n$preview${if (text.length > 500) "\n..." else ""}"))
                null
            }
            "SCREEN_TREE" -> {
                val tree = service.getCompactScreenTreeJson()
                val preview = tree.take(1200)
                addLog(LogEntry(sender = "SYSTEM", text = "🌳 UI Tree:\n$preview${if (tree.length > 1200) "\n..." else ""}"))
                null
            }
            "SCREEN_SUMMARY" -> {
                val summary = service.getScreenSummary()
                addLog(LogEntry(sender = "SYSTEM", text = "📊 $summary"))
                null
            }
            "SCREENSHOT" -> {
                var result: String? = null
                val latch = java.util.concurrent.CountDownLatch(1)
                service.takeScreenshotBase64 { base64 ->
                    result = base64
                    latch.countDown()
                }
                latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                if (result != null) {
                    addLog(LogEntry(sender = "SYSTEM", text = "📸 Screenshot captured (${result!!.length / 1024}KB base64)"))
                    null
                } else "Screenshot failed"
            }
            "CURRENT_APP" -> {
                val pkg = service.getCurrentPackage()
                if (pkg.isNotEmpty()) "Current app: $pkg" else "Could not detect current app"
            }

            // --- HUD OVERLAY ---
            "HUD_ON" -> {
                service.setOverlayEnabled(true)
                service.refreshOverlay()
                "HUD overlay enabled"
            }
            "HUD_OFF" -> {
                service.setOverlayEnabled(false)
                "HUD overlay disabled"
            }
            "HUD_REFRESH" -> {
                service.refreshOverlay()
                "HUD overlay refreshed (${service.overlayManager?.getElementCount() ?: 0} elements)"
            }

            // --- SYSTEM SETTINGS ---
            "VOLUME" -> {
                val pct = args.trim().toIntOrNull()
                if (pct != null) {
                    val ok = service.setVolume(pct)
                    if (ok) "Volume set to $pct%" else "Failed to set volume"
                } else {
                    val vol = service.getVolume()
                    "Current volume: $vol%"
                }
            }
            "BRIGHTNESS" -> {
                val pct = args.trim().toIntOrNull()
                if (pct != null) {
                    val ok = service.setBrightness(pct)
                    if (ok) "Brightness set to $pct%" else "Failed to set brightness"
                } else null
            }
            "OPEN_SETTINGS" -> { service.openSystemSettings(); "Opened Settings" }
            "OPEN_WIFI" -> { service.openWifiSettings(); "Opened WiFi Settings" }
            "OPEN_BLUETOOTH" -> { service.openBluetoothSettings(); "Opened Bluetooth Settings" }
            "OPEN_DISPLAY" -> { service.openDisplaySettings(); "Opened Display Settings" }
            "OPEN_SOUND" -> { service.openSoundSettings(); "Opened Sound Settings" }
            "OPEN_BATTERY" -> { service.openBatterySettings(); "Opened Battery Settings" }

            else -> {
                Log.w(TAG, "Unknown tool: $action")
                null
            }
        }
    }

    private fun buildContextForLitertlm(currentMessage: String): String {
        // For LiteRT-LM, build a simple text context from recent history
        val recent = conversationHistory.takeLast(MAX_HISTORY_MESSAGES).dropLast(1) // exclude current
        if (recent.isEmpty()) return currentMessage

        val sb = StringBuilder()
        sb.appendLine("Previous conversation:")
        for ((role, content) in recent) {
            val label = if (role == "user") "User" else "Assistant"
            sb.appendLine("$label: $content")
        }
        sb.appendLine()
        sb.appendLine("User: $currentMessage")
        return sb.toString()
    }

    private fun updateStreamingLog(responseId: String, text: String) {
        _uiState.update { state ->
            val updatedLogs = state.logs.map { log ->
                if (log.id == responseId) log.copy(text = text.trim()) else log
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
        for ((role, content) in conversationHistory.takeLast(MAX_HISTORY_MESSAGES)) {
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
                conversationHistory.clear()
                for (i in 0 until histArray.length()) {
                    val obj = histArray.getJSONObject(i)
                    conversationHistory.add(obj.getString("role") to obj.getString("content"))
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
        conversationHistory.clear()
        _uiState.update { it.copy(logs = emptyList()) }
        prefs(context).edit().remove("saved_logs").remove("saved_history").apply()
        addSystemLog("Conversation cleared. 🗑️")
    }

    // ========== HELPERS ==========

    private fun addLog(entry: LogEntry) { _uiState.update { it.copy(logs = it.logs + entry) } }
    private fun addSystemLog(text: String) { addLog(LogEntry(sender = "SYSTEM", text = text)) }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)

    override fun onCleared() {
        super.onCleared()
        closeAllModels()
    }
}

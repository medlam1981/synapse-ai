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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

enum class ModelType { LITERTLM, GGUF }

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
    val serverHost: String = "127.0.0.1",
    val serverPort: Int = 8080,
    val contextLength: Int = 1024,
    val threadCount: Int = 6,
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
        private const val MAX_HISTORY_MESSAGES = 4

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
    private var schedulerJob: Job? = null
    private val scheduledTasks = Collections.synchronizedList(mutableListOf<ScheduledTask>())

    // Conversation history for context (role + content pairs)
    private val conversationHistory = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    // ========== CONFIG ==========

    fun loadConfig(context: Context) {
        val prefs = prefs(context)
        val savedPath = prefs.getString("local_model_path", null)
        val config = AgentConfig(
            useLocalModel = savedPath != null,
            localModelPath = savedPath ?: "",
            serverHost = prefs.getString("server_host", "127.0.0.1") ?: "127.0.0.1",
            serverPort = prefs.getInt("server_port", 8080),
            contextLength = prefs.getInt("context_length", 1024),
            threadCount = prefs.getInt("thread_count", 6),
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
        // Scale first, then visual display toggle (data collection always runs)
        service.setOverlayScale(config.overlaySize)
        service.setOverlayVisible(config.overlayEnabled)
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
                    contextLength = config.contextLength,
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
        val hasA11y = SynapseAccessibilityService.isConnected

        // Show pending scheduled tasks
        val taskInfo = synchronized(scheduledTasks) {
            val taskList = scheduledTasks.filter { it.status == "pending" }
            if (taskList.isNotEmpty()) {
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                "\nPending tasks: " + taskList.joinToString(", ") { "${it.description}@${fmt.format(Date(it.executeAt))}" }
            } else ""
        }

        val toolsSection = if (hasA11y) """

You control this Android device. Use >>> commands to act.
WORKFLOW: Read screen → Think → Act → Read result.

TOOLS:
>>> SCREEN_TREE — See elements with indices
>>> READ_SCREEN — Read text on screen
>>> LAUNCH <app> — Open app
>>> TAP_INDEX <n> — Tap element by index
>>> CLICK_TEXT <text> — Tap text on screen
>>> CLICK <x> <y> — Tap coordinates
>>> TYPE <text> — Type in field
>>> BACK / HOME — Navigate
>>> SWIPE_UP / SWIPE_DOWN / SWIPE_LEFT / SWIPE_RIGHT
>>> SCROLL UP / SCROLL DOWN
>>> OPEN_SETTINGS / OPEN_WIFI / OPEN_BLUETOOTH
>>> NOTIFICATIONS — Pull down notifications
>>> VOLUME <0-100> / BRIGHTNESS <0-100>
>>> PACKAGES — List apps
>>> OPEN_URL <url> — Open URL
>>> LONG_PRESS <x> <y>
>>> SWIPE <x1> <y1> <x2> <y2>
>>> SCHEDULE <minutes> <task> — Schedule for later
>>> LIST_TASKS / CANCEL_TASK <name>

RULES:
- Always use >>> SCREEN_TREE before tapping
- Use >>> TAP_INDEX with index numbers from SCREEN_TREE
- One >>> command per line
- Think step-by-step, then act

Example "enable WiFi":
>>> OPEN_WIFI
>>> SCREEN_TREE
(find toggle index)
>>> TAP_INDEX <n>""" else ""

        return """You are Synapse AI, an intelligent agent on this Android device.
Time: $now$taskInfo${if (hasA11y) "\nDevice Control: ENABLED" else ""}
You think step-by-step, make decisions, and execute tasks.
Always complete your answers.$toolsSection"""
    }

    // ========== CHAT ==========

    fun sendCommand(command: String) {
        if (command.isBlank() || _uiState.value.isProcessing) return
        Log.d(TAG, "sendCommand: '$command'")
        addLog(LogEntry(sender = "USER", text = command))
        synchronized(conversationHistory) {
            conversationHistory.add("user" to command)
        }
        _uiState.update { it.copy(isProcessing = true, accessibilityConnected = SynapseAccessibilityService.isConnected) }

        // Switch to Synapse keyboard when agent starts thinking
        SynapseAccessibilityService.instance?.switchToSynapseKeyboard()

        currentJob = viewModelScope.launch {
            try {
                val modelType = _uiState.value.activeModelType
                if (modelType == null || !isModelLoaded()) {
                    addLog(LogEntry(sender = "SYSTEM", text = "Model not loaded. Go to Settings to load a model.", isError = true))
                    return@launch
                }

                val responseId = java.util.UUID.randomUUID().toString()
                addLog(LogEntry(id = responseId, sender = "AGENT", text = ""))

                val responseSb = StringBuilder(512)
                val systemPrompt = buildSystemPrompt()

                when (modelType) {
                    ModelType.LITERTLM -> {
                        val contextPrompt = buildContextForLitertlm(command)
                        litertlmClient.generateResponseStream(contextPrompt, systemPrompt)
                            .collect { chunk ->
                                responseSb.append(chunk)
                                updateStreamingLog(responseId, responseSb.toString())
                            }
                    }
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
                        // Ensure final text is explicitly updated to avoid missing the last throttled chunk
                        updateStreamingLog(responseId, responseSb.toString(), force = true)
                    }
                }

                val fullResponse = responseSb.toString()
                if (fullResponse.isBlank()) {
                    _uiState.update { state -> state.copy(logs = state.logs.filter { it.id != responseId }) }
                    addLog(LogEntry(sender = "SYSTEM", text = "Model returned empty response.", isError = true))
                } else {
                    val cleanResponse = fullResponse.replace(Regex("^💭.*?\n\n", RegexOption.DOT_MATCHES_ALL), "").trim()
                    synchronized(conversationHistory) {
                        conversationHistory.add("assistant" to cleanResponse)
                        while (conversationHistory.size > MAX_HISTORY_MESSAGES) {
                            conversationHistory.removeAt(0)
                        }
                    }

                    // Execute tool commands and feed results back to model
                    val toolFeedback = executeToolCommandsWithFeedback(fullResponse)
                    if (toolFeedback != null) {
                        runToolFeedbackLoop(toolFeedback, systemPrompt, 5)
                    }
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                addLog(LogEntry(sender = "SYSTEM", text = "■ Stopped."))
            } catch (e: Exception) {
                Log.e(TAG, "sendCommand error", e)
                addLog(LogEntry(sender = "SYSTEM", text = "ERROR: ${e.message}", isError = true))
            } finally {
                // Restore original keyboard when agent finishes
                SynapseAccessibilityService.instance?.restoreKeyboard()
                currentJob = null
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    // ========== TOOL EXECUTION WITH FEEDBACK LOOP ==========

    /** Execute tools and return feedback string if model needs to see results, null otherwise */
    private suspend fun executeToolCommandsWithFeedback(response: String): String? {
        val service = SynapseAccessibilityService.instance ?: return null
        val toolLines = response.lines().filter { it.trimStart().startsWith(">>>") }
        if (toolLines.isEmpty()) return null

        val feedbackParts = mutableListOf<String>()
        var hadUIAction = false

        for (line in toolLines) {
            val cmd = line.trimStart().removePrefix(">>>").trim()
            if (cmd.isEmpty()) continue

            try {
                val result = executeOneCommand(service, cmd)
                if (result != null) {
                    addSystemLog("⚡ $result")
                    feedbackParts.add("[${cmd.split(" ")[0].uppercase()}] $result")
                }

                val action = cmd.split(" ", limit = 2)[0].uppercase()

                // After UI-changing actions, wait for screen to settle
                if (action in UI_CHANGING_COMMANDS) {
                    kotlinx.coroutines.delay(1000)
                    hadUIAction = true
                }

                // Collect explicit screen data feedback
                if (action in FEEDBACK_COMMANDS) {
                    val feedbackData = getToolFeedbackData(service, action)
                    if (feedbackData != null) {
                        feedbackParts.add("[$action]\n$feedbackData")
                    }
                }

                kotlinx.coroutines.delay(200)
            } catch (e: Exception) {
                Log.e(TAG, "Tool exec failed: $cmd", e)
                addSystemLog("⚠ Tool error: ${e.message}")
                feedbackParts.add("[ERROR] $cmd: ${e.message}")
            }
        }

        // After UI actions, auto-capture screen state so model can see what happened
        if (hadUIAction) {
            try {
                val screenData = service.getCompactScreenTreeJson().take(1200)
                feedbackParts.add("[SCREEN after actions]\n$screenData")
            } catch (e: Exception) {
                Log.e(TAG, "Auto screen capture failed", e)
            }
        }

        return if (feedbackParts.isNotEmpty()) feedbackParts.joinToString("\n\n") else null
    }

    /** Commands that produce data the model needs to see */
    private val FEEDBACK_COMMANDS = setOf(
        "SCREEN_TREE", "READ_SCREEN", "SCREEN_SUMMARY", "CURRENT_APP", "PACKAGES"
    )

    /** Commands that change the UI and need time to settle */
    private val UI_CHANGING_COMMANDS = setOf(
        "LAUNCH", "TAP_INDEX", "CLICK", "CLICK_TEXT", "LONG_PRESS",
        "SWIPE", "SWIPE_UP", "SWIPE_DOWN", "SWIPE_LEFT", "SWIPE_RIGHT",
        "BACK", "HOME", "RECENTS", "NOTIFICATIONS",
        "SCROLL", "TYPE", "APPEND", "CLEAR",
        "OPEN_SETTINGS", "OPEN_WIFI", "OPEN_BLUETOOTH", "OPEN_DISPLAY",
        "OPEN_SOUND", "OPEN_BATTERY", "OPEN_URL", "APP_SETTINGS"
    )

    /** Get the actual data output for feedback commands */
    private fun getToolFeedbackData(service: SynapseAccessibilityService, action: String): String? {
        return when (action) {
            "SCREEN_TREE" -> service.getCompactScreenTreeJson().take(1200)
            "READ_SCREEN" -> service.getScreenText().take(1000)
            "SCREEN_SUMMARY" -> service.getScreenSummary()
            "CURRENT_APP" -> service.getCurrentPackage()
            "PACKAGES" -> service.getInstalledPackages().take(1000)
            else -> null
        }
    }

    /** Run feedback loop: feed tool results back to model, let it respond, repeat */
    private suspend fun runToolFeedbackLoop(initialFeedback: String, systemPrompt: String, maxIterations: Int) {
        var feedback = initialFeedback
        var iterations = 0

        while (iterations < maxIterations) {
            iterations++

            // Add tool results as a user message for the model to process
            val feedbackMsg = "[Tool Results - analyze and continue]\n$feedback"
            synchronized(conversationHistory) {
                conversationHistory.add("user" to feedbackMsg)
                while (conversationHistory.size > MAX_HISTORY_MESSAGES) {
                    conversationHistory.removeAt(0)
                }
            }

            // Generate model response
            val responseId = java.util.UUID.randomUUID().toString()
            addLog(LogEntry(id = responseId, sender = "AGENT", text = ""))

            var fullResponse = ""
            try {
                val modelType = _uiState.value.activeModelType ?: break
                val responseSb = StringBuilder(512)
                when (modelType) {
                    ModelType.LITERTLM -> {
                        val contextPrompt = buildContextForLitertlm(feedbackMsg)
                        litertlmClient.generateResponseStream(contextPrompt, systemPrompt)
                            .collect { chunk ->
                                responseSb.append(chunk)
                                updateStreamingLog(responseId, responseSb.toString())
                            }
                    }
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
                        // Ensure final text is explicitly updated to avoid missing the last throttled chunk
                        updateStreamingLog(responseId, responseSb.toString(), force = true)
                    }
                }
                fullResponse = responseSb.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Feedback loop error: ${e.message}")
                break
            }

            if (fullResponse.isBlank()) {
                _uiState.update { state -> state.copy(logs = state.logs.filter { it.id != responseId }) }
                break
            }

            val cleanResponse = fullResponse.replace(Regex("^💭.*?\n\n", RegexOption.DOT_MATCHES_ALL), "").trim()
            synchronized(conversationHistory) {
                conversationHistory.add("assistant" to cleanResponse)
                while (conversationHistory.size > MAX_HISTORY_MESSAGES) {
                    conversationHistory.removeAt(0)
                }
            }

            // Execute any new tool commands
            val newFeedback = executeToolCommandsWithFeedback(fullResponse)
            if (newFeedback == null) break // No more tool commands, done
            feedback = newFeedback
        }

        if (iterations >= maxIterations) {
            addSystemLog("⚠ Agent reached max tool steps ($maxIterations)")
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

            // --- TASK SCHEDULING ---
            "SCHEDULE" -> {
                // Format: SCHEDULE <minutes> <command to run>
                // e.g. SCHEDULE 30 OPEN_WIFI
                val scheduleParts = args.split(" ", limit = 2)
                val minutes = scheduleParts.getOrNull(0)?.toIntOrNull()
                val taskCmd = scheduleParts.getOrNull(1)?.trim()
                if (minutes != null && !taskCmd.isNullOrBlank()) {
                    scheduleTask(taskCmd, taskCmd, minutes)
                } else {
                    "Usage: SCHEDULE <minutes> <description or command>"
                }
            }
            "LIST_TASKS" -> {
                listScheduledTasks()
            }
            "CANCEL_TASK" -> {
                if (args.isNotBlank() && cancelScheduledTask(args)) {
                    "Cancelled task: $args"
                } else {
                    "No pending task found matching: $args"
                }
            }

            else -> {
                Log.w(TAG, "Unknown tool: $action")
                null
            }
        }
    }

    private fun buildContextForLitertlm(currentMessage: String): String {
        // For LiteRT-LM, build a simple text context from recent history
        val recent = synchronized(conversationHistory) {
            conversationHistory.takeLast(MAX_HISTORY_MESSAGES).dropLast(1) // exclude current
        }
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

    private var lastStreamUpdate = 0L

    private fun updateStreamingLog(responseId: String, text: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        // Throttle UI updates to every 150ms to reduce recomposition/shaking during streaming
        if (!force && now - lastStreamUpdate < 150 && text.length > 10) return
        lastStreamUpdate = now
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
        SynapseAccessibilityService.instance?.restoreKeyboard()
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

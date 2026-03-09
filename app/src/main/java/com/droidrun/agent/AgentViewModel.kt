package com.droidrun.agent

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

enum class ModelType { LITERTLM, GGUF }

data class AgentConfig(
    val useLocalModel: Boolean = true,
    val localModelPath: String = "/sdcard/Download/gemma3-1b.litertlm"
)

data class AgentUiState(
    val logs: List<LogEntry> = emptyList(),
    val isProcessing: Boolean = false,
    val config: AgentConfig = AgentConfig(),
    val localModelLoaded: Boolean = false,
    val localModelLoading: Boolean = false,
    val activeModelType: ModelType? = null,
    val activeModelName: String = "",
    val streamVersion: Int = 0
)

class AgentViewModel : ViewModel() {

    companion object {
        private const val TAG = "AgentViewModel"

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

    // ========== CONFIG ==========

    fun loadConfig(context: Context) {
        val prefs = prefs(context)
        val config = AgentConfig(
            useLocalModel = prefs.getBoolean("use_local_model", true),
            localModelPath = prefs.getString("local_model_path", "/sdcard/Download/gemma3-1b.litertlm") ?: "/sdcard/Download/gemma3-1b.litertlm"
        )
        _uiState.update { it.copy(config = config) }
        addSystemLog("Synapse AI initialized.")

        if (config.useLocalModel) {
            loadModel(context, config.localModelPath)
        }
    }

    fun saveConfig(context: Context, config: AgentConfig) {
        val oldPath = _uiState.value.config.localModelPath
        prefs(context).edit()
            .putBoolean("use_local_model", config.useLocalModel)
            .putString("local_model_path", config.localModelPath)
            .apply()
        _uiState.update { it.copy(config = config) }
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
                val client = GGUFModelClient()
                ggufClient = client
                client.loadModel(path, contextLength = 512) {
                    _uiState.update { it.copy(localModelLoaded = true, localModelLoading = false, activeModelType = ModelType.GGUF, activeModelName = modelName) }
                    addSystemLog("> GGUF model loaded: $modelName ✓")
                }
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

    // ========== CHAT ==========

    fun sendCommand(command: String) {
        if (command.isBlank() || _uiState.value.isProcessing) return
        Log.d(TAG, "sendCommand: '$command'")
        addLog(LogEntry(sender = "USER", text = command))
        _uiState.update { it.copy(isProcessing = true) }

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

                when (modelType) {
                    ModelType.LITERTLM -> {
                        litertlmClient.generateResponseStream(command, LOCAL_CHAT_PROMPT)
                            .collect { chunk ->
                                fullResponse += chunk
                                updateStreamingLog(responseId, fullResponse)
                            }
                    }
                    ModelType.GGUF -> {
                        ggufClient!!.generateStream(command, LOCAL_CHAT_PROMPT)
                            .collect { chunk ->
                                fullResponse += chunk
                                updateStreamingLog(responseId, fullResponse)
                            }
                    }
                }

                if (fullResponse.isBlank()) {
                    _uiState.update { state -> state.copy(logs = state.logs.filter { it.id != responseId }) }
                    addLog(LogEntry(sender = "SYSTEM", text = "Model returned empty response.", isError = true))
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

private const val LOCAL_CHAT_PROMPT = """You are Synapse AI, a helpful assistant.
Answer questions clearly and concisely.
Be friendly and informative.
If you don't know something, say so honestly."""

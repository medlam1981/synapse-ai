package com.synapse.localai

import android.content.Context
import android.content.SharedPreferences

/**
 * AgentSettingsRepository: Centralized persistent settings repository.
 * Strictly persists configuration changes immediately to disk, preventing silent resets.
 */
object AgentSettingsRepository {
    private const val PREFS_NAME = "synapse_config"
    private const val LEGACY_PREFS_NAME = "synapse_prefs"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDraftModelPath(context: Context): String {
        return getPrefs(context).getString("draft_model_path", "") ?: ""
    }

    fun setDraftModelPath(context: Context, path: String) {
        val trimmed = path.trim()
        getPrefs(context).edit().putString("draft_model_path", trimmed).commit()
        try {
            context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString("draft_model_path", trimmed).commit()
        } catch (_: Exception) {}
    }

    fun getLocalModelPath(context: Context): String {
        return getPrefs(context).getString("local_model_path", "") ?: ""
    }

    fun setLocalModelPath(context: Context, path: String) {
        val trimmed = path.trim()
        getPrefs(context).edit().putString("local_model_path", trimmed).commit()
    }

    fun saveConfig(context: Context, config: AgentConfig) {
        getPrefs(context).edit().apply {
            putString("local_model_path", config.localModelPath)
            putString("draft_model_path", config.draftModelPath)
            putInt("gpu_layers", config.gpuLayers)
            putBoolean("use_mmap", config.useMmap)
            putBoolean("draft_use_mmap", config.draftUseMmap)
            putString("preferred_model_type", config.preferredModelType.name)
            putString("gemini_api_keys", config.geminiApiKeys)
            putString("gemini_model_name", config.geminiModelName)
            putString("server_host", config.serverHost)
            putInt("server_port", config.serverPort)
            putInt("context_length", config.contextLength)
            putInt("thread_count", config.threadCount)
            putInt("max_tokens", config.maxTokens)
            putFloat("temperature", config.temperature)
            putBoolean("enable_visual_history", config.enableVisualHistory)
            putBoolean("enable_neural_reflexes", config.enableNeuralReflexes)
            putStringSet("enabled_agents", config.enabledSpecializedAgents)
            putString("system_prompt", config.systemPrompt)
            putString("custom_tools_prompt", config.customToolsPrompt)
            putBoolean("show_api_key_tracker", config.showApiKeyTracker)
        }.commit()

        try {
            context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString("draft_model_path", config.draftModelPath).commit()
        } catch (_: Exception) {}
    }
}

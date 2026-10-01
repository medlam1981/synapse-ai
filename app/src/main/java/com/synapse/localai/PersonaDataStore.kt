package com.synapse.localai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.personaDataStore: DataStore<Preferences> by preferencesDataStore(name = "persona_preferences")

object PersonaDataStore {
    val KEY_CUSTOM_PERSONA_PROMPT = stringPreferencesKey("custom_persona_prompt")

    val DEFAULT_PROMPT: String
        get() = PersonaManager.UNIFIED_CORE_PROMPT.trim()

    private fun isLegacyDefault(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return true
        if (prompt.contains("Worker 1") ||
            prompt.contains("Worker 2") ||
            prompt.contains("ChatGPT") ||
            !prompt.contains("ATLAS-AI") ||
            !prompt.contains("GREETINGS") ||
            !prompt.contains("STRICT LANGUAGE RULE FOR GREETINGS") ||
            !prompt.contains("CORE OPERATIONAL DOCTRINE") ||
            !prompt.contains("TERMINAL COMMAND EXECUTION") ||
            !prompt.contains("DYNAMIC MIRRORING")) {
            return true
        }
        return false
    }

    fun getCustomPersonaPromptFlow(context: Context): Flow<String> {
        return context.personaDataStore.data.map { preferences ->
            val saved = preferences[KEY_CUSTOM_PERSONA_PROMPT]
            if (!saved.isNullOrBlank() && !isLegacyDefault(saved)) saved else DEFAULT_PROMPT
        }
    }

    suspend fun getCustomPersonaPrompt(context: Context): String {
        return try {
            val prefs = context.personaDataStore.data.first()
            val prompt = prefs[KEY_CUSTOM_PERSONA_PROMPT]
            if (!prompt.isNullOrBlank() && !isLegacyDefault(prompt)) prompt else DEFAULT_PROMPT
        } catch (_: Exception) {
            getCustomPersonaPromptSync(context)
        }
    }

    suspend fun saveCustomPersonaPrompt(context: Context, prompt: String) {
        val trimmed = prompt.trim()
        val toSave = if (trimmed.isNotBlank() && !isLegacyDefault(trimmed)) trimmed else DEFAULT_PROMPT
        try {
            context.personaDataStore.edit { preferences ->
                preferences[KEY_CUSTOM_PERSONA_PROMPT] = toSave
            }
        } catch (_: Exception) {}
        try {
            AgentSettingsRepository.getPrefs(context)
                .edit()
                .putString("custom_persona_prompt", toSave)
                .apply()
        } catch (_: Exception) {}
    }

    fun getCustomPersonaPromptSync(context: Context): String {
        return try {
            val saved = AgentSettingsRepository.getPrefs(context).getString("custom_persona_prompt", null)
            if (!saved.isNullOrBlank() && !isLegacyDefault(saved)) saved else DEFAULT_PROMPT
        } catch (_: Exception) {
            DEFAULT_PROMPT
        }
    }
}

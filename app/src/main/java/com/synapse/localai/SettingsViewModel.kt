package com.synapse.localai

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * SettingsViewModel: Manages persistent settings and the editable dynamic persona prompt
 * backed by Android DataStore (Preferences).
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context get() = getApplication<Application>().applicationContext

    private val _customPersonaPrompt = MutableStateFlow(PersonaDataStore.DEFAULT_PROMPT)
    val customPersonaPrompt: StateFlow<String> = _customPersonaPrompt.asStateFlow()

    init {
        viewModelScope.launch {
            _customPersonaPrompt.value = PersonaDataStore.getCustomPersonaPrompt(context)
            PersonaDataStore.getCustomPersonaPromptFlow(context).collectLatest { prompt ->
                _customPersonaPrompt.value = prompt
            }
        }
    }

    fun updatePersonaPrompt(newPrompt: String) {
        _customPersonaPrompt.value = newPrompt
        viewModelScope.launch {
            PersonaDataStore.saveCustomPersonaPrompt(context, newPrompt)
        }
    }

    fun resetPersonaPromptToDefault() {
        val defaultVal = PersonaDataStore.DEFAULT_PROMPT
        _customPersonaPrompt.value = defaultVal
        viewModelScope.launch {
            PersonaDataStore.saveCustomPersonaPrompt(context, defaultVal)
        }
    }
}

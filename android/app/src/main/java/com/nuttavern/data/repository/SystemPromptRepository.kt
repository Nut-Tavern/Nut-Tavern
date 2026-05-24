package com.nuttavern.data.repository

import com.nuttavern.data.local.SettingsDataStore
import com.nuttavern.data.model.SystemPromptConfig
import com.nuttavern.data.model.defaultSystemPrompts
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class SystemPromptRepository @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) {
    private val _systemPrompts = MutableStateFlow<List<SystemPromptConfig>>(emptyList())
    val systemPrompts: Flow<List<SystemPromptConfig>> = _systemPrompts.asStateFlow()
    val systemPromptsState: List<SystemPromptConfig> get() = _systemPrompts.value

    suspend fun initialize() {
        val saved = settingsDataStore.getSystemPrompts()
        _systemPrompts.value = if (saved.isEmpty()) defaultSystemPrompts else saved
    }

    suspend fun updateSystemPrompt(promptId: String, prompt: String) {
        val updated = _systemPrompts.value.map { config ->
            if (config.id == promptId) config.copy(prompt = prompt.take(6000)) else config
        }
        _systemPrompts.value = updated
        settingsDataStore.saveSystemPrompts(updated)
    }
}
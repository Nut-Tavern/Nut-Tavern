package com.nuttavern.data.repository

import com.nuttavern.data.local.SettingsDataStore
import com.nuttavern.data.model.AssistantConfig
import com.nuttavern.data.model.defaultAssistants
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AssistantRepository @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) {
    private val _assistants = MutableStateFlow<List<AssistantConfig>>(emptyList())
    val assistants: Flow<List<AssistantConfig>> = _assistants.asStateFlow()
    val assistantsState: List<AssistantConfig> get() = _assistants.value

    private val _defaultAssistantId = MutableStateFlow("chat-assistant")
    val defaultAssistantId: Flow<String> = _defaultAssistantId.asStateFlow()
    val defaultAssistantIdState: String get() = _defaultAssistantId.value

    suspend fun initialize() {
        val saved = settingsDataStore.getAssistants()
        val assistants = normalizeAssistants(if (saved.isEmpty()) defaultAssistants else saved)
        _assistants.value = assistants

        val savedDefaultAssistantId = settingsDataStore.getDefaultAssistantId()
        val defaultAssistantId = getValidDefaultAssistantId(savedDefaultAssistantId, assistants)
        _defaultAssistantId.value = defaultAssistantId
        if (defaultAssistantId != savedDefaultAssistantId) {
            settingsDataStore.saveDefaultAssistantId(defaultAssistantId)
        }
    }

    suspend fun addAssistant(assistant: AssistantConfig) {
        val updated = normalizeAssistants(listOf(assistant) + _assistants.value)
        _assistants.value = updated
        settingsDataStore.saveAssistants(updated)
    }

    suspend fun updateAssistant(id: String, name: String? = null, summary: String? = null, systemPrompt: String? = null) {
        val updated = normalizeAssistants(_assistants.value.map { assistant ->
            if (assistant.id != id) return@map assistant
            assistant.copy(
                name = name?.take(40) ?: assistant.name,
                summary = summary?.take(120) ?: assistant.summary,
                systemPrompt = systemPrompt?.take(6000) ?: assistant.systemPrompt,
            )
        })
        _assistants.value = updated
        settingsDataStore.saveAssistants(updated)
    }

    suspend fun reorderAssistants(orderedIds: List<String>) {
        val currentAssistants = _assistants.value
        if (orderedIds.isEmpty() || currentAssistants.isEmpty()) return

        val assistantsById = currentAssistants.associateBy { it.id }
        val orderedAssistants = orderedIds.mapNotNull { assistantsById[it] }
        val missingAssistants = currentAssistants.filterNot { assistant -> orderedIds.contains(assistant.id) }
        if (orderedAssistants.isEmpty()) return

        val updated = normalizeAssistants(orderedAssistants + missingAssistants)
        _assistants.value = updated
        settingsDataStore.saveAssistants(updated)
    }

    suspend fun setDefaultAssistant(id: String) {
        if (_assistants.value.any { it.id == id }) {
            _defaultAssistantId.value = id
            settingsDataStore.saveDefaultAssistantId(id)
        }
    }

    suspend fun deleteAssistant(id: String): Boolean {
        val currentAssistants = _assistants.value
        if (currentAssistants.size <= 1) return false
        if (currentAssistants.none { it.id == id }) return false

        val updatedAssistants = normalizeAssistants(currentAssistants.filterNot { it.id == id })
        val updatedDefaultAssistantId = getValidDefaultAssistantId(_defaultAssistantId.value, updatedAssistants)

        _assistants.value = updatedAssistants
        settingsDataStore.saveAssistants(updatedAssistants)

        if (updatedDefaultAssistantId != _defaultAssistantId.value) {
            _defaultAssistantId.value = updatedDefaultAssistantId
            settingsDataStore.saveDefaultAssistantId(updatedDefaultAssistantId)
        }

        return true
    }

    private fun getValidDefaultAssistantId(defaultAssistantId: String, assistants: List<AssistantConfig>): String {
        return defaultAssistantId.takeIf { id -> assistants.any { it.id == id } }
            ?: assistants.firstOrNull()?.id
            ?: "chat-assistant"
    }

    private fun normalizeAssistants(assistants: List<AssistantConfig>): List<AssistantConfig> {
        return assistants.mapIndexedNotNull { index, assistant ->
            val id = assistant.id.ifBlank { "assistant-${System.currentTimeMillis()}-$index" }
            val name = assistant.name.ifBlank { "未命名助手" }.take(40)
            val summary = assistant.summary.take(120)
            val systemPrompt = assistant.systemPrompt.ifBlank {
                "请清晰、准确地帮助用户完成当前任务。"
            }.take(6000)
            AssistantConfig(
                id = id,
                name = name,
                summary = summary,
                systemPrompt = systemPrompt,
            )
        }.distinctBy { it.id }
    }
}

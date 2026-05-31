package com.nuttavern.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuttavern.data.model.AssistantConfig
import com.nuttavern.data.model.SystemPromptConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nuttavern_settings")

/**
 * 杂项设置(Assistant、SystemPrompt 等)持久化。Provider 列表已迁移到独立的
 * [ProviderDataStore],本类只负责剩下的几类轻量配置。
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()

    private companion object {
        val KEY_ASSISTANTS = stringPreferencesKey("assistants_json")
        val KEY_DEFAULT_ASSISTANT_ID = stringPreferencesKey("default_assistant_id")
        val KEY_SYSTEM_PROMPTS = stringPreferencesKey("system_prompts_json")
        val KEY_LAST_CONVERSATION_ID = stringPreferencesKey("last_conversation_id")
        val KEY_LAST_CHARACTER_ID = stringPreferencesKey("last_character_id")
        val KEY_LAST_PERSONA_ID = stringPreferencesKey("last_persona_id")
        val KEY_LAST_PRESET_ID = stringPreferencesKey("last_preset_id")
        val KEY_LAST_THINKING_LEVEL = stringPreferencesKey("last_thinking_level")
    }

    suspend fun getAssistants(): List<AssistantConfig> {
        val json = context.dataStore.data.map { prefs ->
            prefs[KEY_ASSISTANTS]
        }.first()

        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<AssistantConfig>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveAssistants(assistants: List<AssistantConfig>) {
        val json = gson.toJson(assistants)
        context.dataStore.edit { prefs -> prefs[KEY_ASSISTANTS] = json }
    }

    suspend fun getDefaultAssistantId(): String {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_DEFAULT_ASSISTANT_ID] ?: "chat-assistant"
        }.first()
    }

    suspend fun saveDefaultAssistantId(id: String) {
        context.dataStore.edit { prefs -> prefs[KEY_DEFAULT_ASSISTANT_ID] = id }
    }

    suspend fun getSystemPrompts(): List<SystemPromptConfig> {
        val json = context.dataStore.data.map { prefs ->
            prefs[KEY_SYSTEM_PROMPTS]
        }.first()

        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<SystemPromptConfig>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveSystemPrompts(prompts: List<SystemPromptConfig>) {
        val json = gson.toJson(prompts)
        context.dataStore.edit { prefs -> prefs[KEY_SYSTEM_PROMPTS] = json }
    }

    /**
     * 上次会话占位态(关 app 前的最后一次"当前会话 / 角色 / 身份 / 预设"组合)。
     *
     * 四个字段一起读写:启动时 ChatViewModel 用它们恢复"上次切到没会话的角色"这种**非会话锁定的占位态**,
     * 否则只能从 conversations 表挑最新一条,会把"切到空角色 / 切到空身份 / 切到空预设"等用户预选丢掉。
     *
     * - 空字符串等价于 null,统一返回 null,调用方按"未持久化"处理。
     * - 任意一项为 null 都允许独立持久化,例如"无角色但有身份"的占位态。
     */
    data class LastChatState(
        val conversationId: String?,
        val characterId: String?,
        val personaId: String?,
        val presetId: String?,
        /** 上次会话思考量(序列化字符串,见 [com.nuttavern.data.model.ThinkingLevel.serialize])。 */
        val thinkingLevel: String?,
    )

    suspend fun getLastChatState(): LastChatState {
        val prefs = context.dataStore.data.first()
        return LastChatState(
            conversationId = prefs[KEY_LAST_CONVERSATION_ID]?.takeIf { it.isNotBlank() },
            characterId = prefs[KEY_LAST_CHARACTER_ID]?.takeIf { it.isNotBlank() },
            personaId = prefs[KEY_LAST_PERSONA_ID]?.takeIf { it.isNotBlank() },
            presetId = prefs[KEY_LAST_PRESET_ID]?.takeIf { it.isNotBlank() },
            thinkingLevel = prefs[KEY_LAST_THINKING_LEVEL]?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun saveLastChatState(state: LastChatState) {
        context.dataStore.edit { prefs ->
            putOrRemove(prefs, KEY_LAST_CONVERSATION_ID, state.conversationId)
            putOrRemove(prefs, KEY_LAST_CHARACTER_ID, state.characterId)
            putOrRemove(prefs, KEY_LAST_PERSONA_ID, state.personaId)
            putOrRemove(prefs, KEY_LAST_PRESET_ID, state.presetId)
            putOrRemove(prefs, KEY_LAST_THINKING_LEVEL, state.thinkingLevel)
        }
    }

    private fun putOrRemove(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        value: String?,
    ) {
        if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value
    }
}

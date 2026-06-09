package com.nuttavern.data.tools

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 内置工具配置持久化。独立 DataStore 文件 `nuttavern_builtin_tools.preferences_pb`,
 * 与 [ToolsSettingsRepository]、Provider / Persona / Preset 都分开,遵循"单文件单职责、
 * 反序列化失败爆炸半径最小"的约定。
 *
 * 反序列化失败兜底:返回默认 [LocalToolsSettings],等价于"全新装机";走 [errorReporter] 上报。
 */
private val Context.localToolsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nuttavern_builtin_tools",
)

@Singleton
class LocalToolsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val errorReporter: (String, Throwable) -> Unit = { stage, error ->
        android.util.Log.w("LocalToolsRepository", stage, error)
    }

    private companion object {
        val KEY_SETTINGS = stringPreferencesKey("local_tools_settings_v1_json")
    }

    val settings: Flow<LocalToolsSettings> = context.localToolsDataStore.data.map { prefs ->
        decode(prefs[KEY_SETTINGS])
    }

    suspend fun update(transform: (LocalToolsSettings) -> LocalToolsSettings) {
        context.localToolsDataStore.edit { prefs ->
            val current = decode(prefs[KEY_SETTINGS])
            val next = transform(current)
            prefs[KEY_SETTINGS] = json.encodeToString(LocalToolsSettings.serializer(), next)
        }
    }

    private fun decode(raw: String?): LocalToolsSettings {
        if (raw.isNullOrBlank()) return LocalToolsSettings()
        return runCatching { json.decodeFromString(LocalToolsSettings.serializer(), raw) }
            .recover { error ->
                if (error is SerializationException || error is IllegalArgumentException) {
                    errorReporter("decode", error)
                    LocalToolsSettings()
                } else {
                    throw error
                }
            }
            .getOrThrow()
    }
}

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
 * 工具设置持久化。独立 DataStore 文件 `nuttavern_tools.preferences_pb`,与其他模块分开。
 *
 * 当前只承载 [ToolsSettings] 一个数据袋(工具调用引擎自身的高级行为)。后续接入 MCP 服务器
 * 列表 / 内置工具开关时,**单独建独立的 DataStore 文件**,不要往这里塞 — 单文件单职责,
 * 反序列化失败的爆炸半径越小越好。
 *
 * 反序列化失败兜底:返回默认 [ToolsSettings],等价于"全新装机";走 [errorReporter] 上报。
 */
private val Context.toolsDataStore: DataStore<Preferences> by preferencesDataStore(name = "nuttavern_tools")

@Singleton
class ToolsSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * 反序列化失败上报钩子。当前固定走 stderr。需要接 Sentry / Logger / 单测断言时,
     * 把它提升为构造参数注入即可。
     */
    private val errorReporter: (String, Throwable) -> Unit = { context, error ->
        android.util.Log.w("ToolsSettingsRepository", context, error)
    }

    private companion object {
        val KEY_SETTINGS = stringPreferencesKey("tools_settings_v1_json")
    }

    val settings: Flow<ToolsSettings> = context.toolsDataStore.data.map { prefs ->
        decode(prefs[KEY_SETTINGS])
    }

    suspend fun update(transform: (ToolsSettings) -> ToolsSettings) {
        context.toolsDataStore.edit { prefs ->
            val current = decode(prefs[KEY_SETTINGS])
            val next = transform(current)
            prefs[KEY_SETTINGS] = json.encodeToString(ToolsSettings.serializer(), next)
        }
    }

    private fun decode(raw: String?): ToolsSettings {
        if (raw.isNullOrBlank()) return ToolsSettings()
        return runCatching { json.decodeFromString(ToolsSettings.serializer(), raw) }
            .recover { error ->
                if (error is SerializationException || error is IllegalArgumentException) {
                    errorReporter("decode", error)
                    ToolsSettings()
                } else {
                    throw error
                }
            }
            .getOrThrow()
    }
}

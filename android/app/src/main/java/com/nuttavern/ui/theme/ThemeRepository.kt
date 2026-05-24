package com.nuttavern.ui.theme

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

/**
 * 主题偏好持久化。
 *
 * 当前阶段只存"主题 id"和"明暗模式"两项,后续接入自定义主题时再追加种子色 / 名称字段。
 *
 * 用独立的 DataStore 文件 `nuttavern_theme.preferences_pb`,与 `nuttavern_settings`
 * 分离:主题在 UI 启动早期就要读取(决定首屏配色),而 settings 还要做 ProviderConfig
 * 的 JSON 反序列化等较重操作,放一起会拖慢 Theme 订阅。
 */
private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "nuttavern_theme")

@Singleton
class ThemeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        val KEY_THEME_ID = stringPreferencesKey("theme_id")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }

    /**
     * 当前选择的主题 id。`null` 时由 UI 兜底到 [ThemePresets.Default]。
     */
    val themeId: Flow<String?> = context.themeDataStore.data.map { prefs ->
        prefs[KEY_THEME_ID]
    }

    /**
     * 当前明暗模式,默认 [ThemeMode.System]。反序列化失败兜底到 System。
     */
    val themeMode: Flow<ThemeMode> = context.themeDataStore.data.map { prefs ->
        parseThemeMode(prefs[KEY_THEME_MODE])
    }

    suspend fun setThemeId(id: String) {
        context.themeDataStore.edit { prefs -> prefs[KEY_THEME_ID] = id }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { prefs -> prefs[KEY_THEME_MODE] = mode.name }
    }

    private fun parseThemeMode(raw: String?): ThemeMode {
        if (raw.isNullOrBlank()) return ThemeMode.System
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.System)
    }
}

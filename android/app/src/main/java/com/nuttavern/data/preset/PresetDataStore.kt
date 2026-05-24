package com.nuttavern.data.preset

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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 预设持久化。独立 DataStore 文件 `nuttavern_presets.preferences_pb`,与 Provider / Settings /
 * Persona 都分开:
 *
 * - 预设字段量大(本仓库对齐酒馆,单份预设 50+ 字段),与轻量偏好混在一起会拖慢启动;
 * - 预设之间几乎不共享字段,扁平 JSON 即可,不需要 Room;
 * - 任意字段反序列化失败兜底为"全新装机",不影响别的偏好。
 *
 * 与 [PersonaDataStore] 同模式:`mutate { snapshot -> snapshot.copy(...) }` 单次 edit 原子提交,
 * 顺序数组 `presetOrder` 与 `presets` 列表分离维护。
 */
private val Context.presetDataStore: DataStore<Preferences> by preferencesDataStore(name = "nuttavern_presets")

@Singleton
class PresetDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val errorReporter: (String, Throwable) -> Unit = { ctx, error ->
        android.util.Log.w("PresetDataStore", ctx, error)
    }

    private val presetListSerializer = ListSerializer(Preset.serializer())
    private val orderListSerializer = ListSerializer(String.serializer())

    private companion object {
        val KEY_PRESETS = stringPreferencesKey("presets_v1_json")
        val KEY_DEFAULT_PRESET_ID = stringPreferencesKey("default_preset_id_v1")
        val KEY_PRESET_ORDER = stringPreferencesKey("preset_order_v1_json")
    }

    /** 当前快照,作为 [mutate] 的原子读写单元。 */
    data class Snapshot(
        val presets: List<Preset>,
        val defaultPresetId: String?,
        val orderedIds: List<String>,
    )

    val presetsFlow: Flow<List<Preset>> = context.presetDataStore.data.map { prefs ->
        decodePresets(prefs[KEY_PRESETS])
    }

    val defaultPresetIdFlow: Flow<String?> = context.presetDataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_PRESET_ID]
    }

    val orderFlow: Flow<List<String>> = context.presetDataStore.data.map { prefs ->
        decodeOrder(prefs[KEY_PRESET_ORDER])
    }

    suspend fun mutate(transform: (Snapshot) -> Snapshot) {
        context.presetDataStore.edit { prefs ->
            val current = Snapshot(
                presets = decodePresets(prefs[KEY_PRESETS]),
                defaultPresetId = prefs[KEY_DEFAULT_PRESET_ID],
                orderedIds = decodeOrder(prefs[KEY_PRESET_ORDER]),
            )
            val next = transform(current)
            prefs[KEY_PRESETS] = json.encodeToString(presetListSerializer, next.presets)
            prefs[KEY_PRESET_ORDER] = json.encodeToString(orderListSerializer, next.orderedIds)
            if (next.defaultPresetId == null) {
                prefs.remove(KEY_DEFAULT_PRESET_ID)
            } else {
                prefs[KEY_DEFAULT_PRESET_ID] = next.defaultPresetId
            }
        }
    }

    private fun decodePresets(raw: String?): List<Preset> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(presetListSerializer, raw) }
            .recover { error ->
                if (error is SerializationException || error is IllegalArgumentException) {
                    errorReporter("decodePresets", error)
                    emptyList()
                } else {
                    throw error
                }
            }
            .getOrThrow()
    }

    private fun decodeOrder(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(orderListSerializer, raw) }
            .recover { error ->
                if (error is SerializationException || error is IllegalArgumentException) {
                    errorReporter("decodeOrder", error)
                    emptyList()
                } else {
                    throw error
                }
            }
            .getOrThrow()
    }
}

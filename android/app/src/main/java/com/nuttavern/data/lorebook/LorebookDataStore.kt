package com.nuttavern.data.lorebook

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LorebookDataStore"

private val Context.lorebookDataStore: DataStore<Preferences> by preferencesDataStore(name = "nuttavern_lorebooks")

private val KEY_LOREBOOKS = stringPreferencesKey("lorebooks_v1_json")
private val KEY_ORDER = stringPreferencesKey("lorebook_order_v1_json")
private val KEY_GLOBAL_SELECTED = stringPreferencesKey("lorebook_global_selected_v1_json")

/**
 * 世界书持久化层。独立 DataStore 文件 `nuttavern_lorebooks.preferences_pb`。
 *
 * 存储结构:
 * - `lorebooks_v1_json`:所有世界书的 JSON 数组
 * - `lorebook_order_v1_json`:拖排顺序(id 列表)
 * - `lorebook_global_selected_v1_json`:全局选中的世界书 id 列表
 */
@Singleton
class LorebookDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Snapshot(
        val lorebooks: List<Lorebook>,
        val order: List<String>,
        val globalSelectedIds: List<String>,
    )

    val snapshot: Flow<Snapshot> = context.lorebookDataStore.data.map { prefs ->
        val lorebooks = prefs[KEY_LOREBOOKS]?.let { raw ->
            try {
                json.decodeFromString<List<Lorebook>>(raw)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to decode lorebooks", e)
                emptyList()
            }
        } ?: emptyList()

        val order = prefs[KEY_ORDER]?.let { raw ->
            try {
                json.decodeFromString<List<String>>(raw)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to decode order", e)
                emptyList()
            }
        } ?: emptyList()

        val globalSelectedIds = prefs[KEY_GLOBAL_SELECTED]?.let { raw ->
            try {
                json.decodeFromString<List<String>>(raw)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to decode global selected", e)
                emptyList()
            }
        } ?: emptyList()

        Snapshot(lorebooks, order, globalSelectedIds)
    }

    suspend fun save(snapshot: Snapshot) {
        context.lorebookDataStore.edit { prefs ->
            prefs[KEY_LOREBOOKS] = json.encodeToString(snapshot.lorebooks)
            prefs[KEY_ORDER] = json.encodeToString(snapshot.order)
            prefs[KEY_GLOBAL_SELECTED] = json.encodeToString(snapshot.globalSelectedIds)
        }
    }
}

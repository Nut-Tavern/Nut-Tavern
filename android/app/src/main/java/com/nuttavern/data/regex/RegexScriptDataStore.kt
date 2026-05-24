package com.nuttavern.data.regex

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
 * 用户级正则持久化。独立 DataStore 文件 `nuttavern_regex.preferences_pb`。
 *
 * 顶层结构:
 * - [KEY_GROUPS]:正则组列表(含组内规则)
 * - [KEY_ORPHAN_SCRIPTS]:不属于任何组的散规则列表
 * - [KEY_TOP_LEVEL_ORDER]:顶层排列顺序 — 组 id 与散规则 id 共享一个 order 列表,
 *   决定执行时的先后顺序(组 id 展开为组内全部规则,散规则 id 直接取规则)
 *
 * 兼容性:旧版本只有 [KEY_SCRIPTS_LEGACY] / [KEY_ORDER_LEGACY]。首次读取时若新 key 为空
 * 但旧 key 有数据,自动迁移:旧规则全部变成散规则,旧 order 直接复用。
 */
private val Context.regexDataStore: DataStore<Preferences> by preferencesDataStore(name = "nuttavern_regex")

@Singleton
class RegexScriptDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val errorReporter: (String, Throwable) -> Unit = { ctx, error ->
        android.util.Log.w("RegexScriptDataStore", ctx, error)
    }

    private val groupListSerializer = ListSerializer(RegexGroup.serializer())
    private val scriptListSerializer = ListSerializer(RegexScript.serializer())
    private val orderListSerializer = ListSerializer(String.serializer())

    private companion object {
        val KEY_GROUPS = stringPreferencesKey("regex_groups_v2_json")
        val KEY_ORPHAN_SCRIPTS = stringPreferencesKey("regex_orphan_scripts_v2_json")
        val KEY_TOP_LEVEL_ORDER = stringPreferencesKey("regex_top_level_order_v2_json")

        // 旧版 key,仅用于一次性迁移读取,不再写入
        val KEY_SCRIPTS_LEGACY = stringPreferencesKey("regex_scripts_v1_json")
        val KEY_ORDER_LEGACY = stringPreferencesKey("regex_order_v1_json")
    }

    data class Snapshot(
        val groups: List<RegexGroup>,
        val orphanScripts: List<RegexScript>,
        /** 顶层顺序:组 id 与散规则 id 混排。 */
        val topLevelOrder: List<String>,
    )

    val snapshotFlow: Flow<Snapshot> = context.regexDataStore.data.map { prefs ->
        readSnapshot(prefs)
    }

    suspend fun mutate(transform: (Snapshot) -> Snapshot) {
        context.regexDataStore.edit { prefs ->
            val current = readSnapshot(prefs)
            val next = transform(current)
            prefs[KEY_GROUPS] = json.encodeToString(groupListSerializer, next.groups)
            prefs[KEY_ORPHAN_SCRIPTS] = json.encodeToString(scriptListSerializer, next.orphanScripts)
            prefs[KEY_TOP_LEVEL_ORDER] = json.encodeToString(orderListSerializer, next.topLevelOrder)
        }
    }

    private fun readSnapshot(prefs: Preferences): Snapshot {
        val groupsRaw = prefs[KEY_GROUPS]
        val orphanRaw = prefs[KEY_ORPHAN_SCRIPTS]
        val orderRaw = prefs[KEY_TOP_LEVEL_ORDER]

        // 新 key 有数据 → 直接用新格式
        if (!groupsRaw.isNullOrBlank() || !orphanRaw.isNullOrBlank()) {
            return Snapshot(
                groups = decodeGroups(groupsRaw),
                orphanScripts = decodeScripts(orphanRaw),
                topLevelOrder = decodeOrder(orderRaw),
            )
        }

        // 新 key 全空 → 尝试从旧 key 迁移
        val legacyScripts = decodeScripts(prefs[KEY_SCRIPTS_LEGACY])
        val legacyOrder = decodeOrder(prefs[KEY_ORDER_LEGACY])
        if (legacyScripts.isEmpty()) {
            return Snapshot(emptyList(), emptyList(), emptyList())
        }
        // 旧规则全部变成散规则,旧 order 直接复用
        return Snapshot(
            groups = emptyList(),
            orphanScripts = legacyScripts,
            topLevelOrder = legacyOrder,
        )
    }

    private fun decodeGroups(raw: String?): List<RegexGroup> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(groupListSerializer, raw) }
            .recover { error ->
                if (error is SerializationException || error is IllegalArgumentException) {
                    errorReporter("decodeGroups", error)
                    emptyList()
                } else throw error
            }
            .getOrThrow()
    }

    private fun decodeScripts(raw: String?): List<RegexScript> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(scriptListSerializer, raw) }
            .recover { error ->
                if (error is SerializationException || error is IllegalArgumentException) {
                    errorReporter("decodeScripts", error)
                    emptyList()
                } else throw error
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
                } else throw error
            }
            .getOrThrow()
    }
}

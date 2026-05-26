package com.nuttavern.data.regex

import com.nuttavern.data.repository.ConversationRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 用户级正则仓库。
 *
 * # 顶层结构
 *
 * 顶层是**组 + 散规则混合列表**,由 [RegexScriptDataStore.Snapshot.topLevelOrder] 决定顺序。
 * - 组([RegexGroup]):组本身是启用单位,[RegexGroup.enabled] 控制整组是否参与执行。
 * - 散规则([RegexScript]):不属于任何组,各自独立 [RegexScript.disabled] 开关。
 *
 * # 执行展开
 *
 * [expandedEnabledScripts] 按顶层顺序展开所有**用户级启用**的规则:
 * - 启用的组 → 展开组内全部规则(组内按 [RegexGroup.scripts] 顺序)
 * - 启用的散规则(disabled=false)→ 直接加入
 *
 * ChatViewModel 用这个 Flow 作为"用户级默认启用列表"的快照来源;
 * 会话级引用 id 列表由 ConversationEntity 单独维护,与本仓库解耦。
 *
 * # 三作用域
 *
 * - 用户级(本仓库):GLOBAL 作用域,跨角色 / 预设统一执行
 * - 角色卡内嵌([com.nuttavern.data.character.Character.regexScripts]):SCOPED 作用域
 * - 预设内嵌([com.nuttavern.data.preset.Preset.extensions] `regex_scripts`):PRESET 作用域
 *
 * 执行优先级:GLOBAL → SCOPED → PRESET(对齐酒馆)。
 */
@Singleton
class RegexScriptRepository @Inject constructor(
    private val dataStore: RegexScriptDataStore,
    private val conversationRepository: ConversationRepository,
) {

    val snapshot: Flow<RegexScriptDataStore.Snapshot> = dataStore.snapshotFlow
        .distinctUntilChanged()

    /**
     * 按顶层顺序展开所有用户级启用的规则。
     *
     * 启用的组 → 展开组内全部规则;启用的散规则(disabled=false)→ 直接加入。
     * 顺序:topLevelOrder 中 id 出现的先后。
     */
    val expandedEnabledScripts: Flow<List<RegexScript>> = snapshot.map { s ->
        expandEnabled(s)
    }.distinctUntilChanged()

    // ─── 组 CRUD ──────────────────────────────────────────────────────────────

    suspend fun upsertGroup(group: RegexGroup) {
        dataStore.mutate { s ->
            val idx = s.groups.indexOfFirst { it.id == group.id }
            val nextGroups = if (idx >= 0) {
                s.groups.toMutableList().apply { set(idx, group) }
            } else {
                s.groups + group
            }
            val nextOrder = if (idx < 0 && group.id !in s.topLevelOrder) {
                s.topLevelOrder + group.id
            } else {
                s.topLevelOrder
            }
            s.copy(groups = nextGroups, topLevelOrder = nextOrder)
        }
    }

    suspend fun deleteGroup(groupId: String) {
        dataStore.mutate { s ->
            s.copy(
                groups = s.groups.filterNot { it.id == groupId },
                topLevelOrder = s.topLevelOrder.filterNot { it == groupId },
            )
        }
        // 联动清理:所有会话快照里的引用 id 同步移除,避免悬空。
        conversationRepository.removeRegexGroupIdFromAllConversations(groupId)
    }

    suspend fun duplicateGroup(groupId: String) {
        dataStore.mutate { s ->
            val original = s.groups.find { it.id == groupId } ?: return@mutate s
            val newId = java.util.UUID.randomUUID().toString()
            val duplicated = original.copy(
                id = newId,
                name = "${original.name}*",
                scripts = original.scripts.map { it.copy(id = java.util.UUID.randomUUID().toString()) },
            )
            val insertIndex = s.topLevelOrder.indexOf(groupId)
            val newOrder = s.topLevelOrder.toMutableList().apply {
                add(if (insertIndex >= 0) insertIndex + 1 else size, newId)
            }
            s.copy(groups = s.groups + duplicated, topLevelOrder = newOrder)
        }
    }

    suspend fun toggleGroupEnabled(groupId: String, enabled: Boolean) {
        dataStore.mutate { s ->
            s.copy(
                groups = s.groups.map { g ->
                    if (g.id == groupId) g.copy(enabled = enabled) else g
                },
            )
        }
    }

    suspend fun renameGroup(groupId: String, name: String) {
        dataStore.mutate { s ->
            s.copy(
                groups = s.groups.map { g ->
                    if (g.id == groupId) g.copy(name = name) else g
                },
            )
        }
    }

    // ─── 组内规则 CRUD ────────────────────────────────────────────────────────

    suspend fun upsertScriptInGroup(groupId: String, script: RegexScript) {
        dataStore.mutate { s ->
            s.copy(
                groups = s.groups.map { g ->
                    if (g.id != groupId) return@map g
                    val idx = g.scripts.indexOfFirst { it.id == script.id }
                    val nextScripts = if (idx >= 0) {
                        g.scripts.toMutableList().apply { set(idx, script) }
                    } else {
                        g.scripts + script
                    }
                    g.copy(scripts = nextScripts)
                },
            )
        }
    }

    suspend fun deleteScriptFromGroup(groupId: String, scriptId: String) {
        dataStore.mutate { s ->
            s.copy(
                groups = s.groups.map { g ->
                    if (g.id != groupId) g
                    else g.copy(scripts = g.scripts.filterNot { it.id == scriptId })
                },
            )
        }
    }

    suspend fun reorderScriptsInGroup(groupId: String, orderedIds: List<String>) {
        dataStore.mutate { s ->
            s.copy(
                groups = s.groups.map { g ->
                    if (g.id != groupId) return@map g
                    val byId = g.scripts.associateBy { it.id }
                    val ordered = orderedIds.mapNotNull(byId::get)
                    val tail = g.scripts.filter { it.id !in orderedIds }
                    g.copy(scripts = ordered + tail)
                },
            )
        }
    }

    // ─── 散规则 CRUD ──────────────────────────────────────────────────────────

    suspend fun upsertOrphan(script: RegexScript) {
        dataStore.mutate { s ->
            val idx = s.orphanScripts.indexOfFirst { it.id == script.id }
            val nextOrphans = if (idx >= 0) {
                s.orphanScripts.toMutableList().apply { set(idx, script) }
            } else {
                s.orphanScripts + script
            }
            val nextOrder = if (idx < 0 && script.id !in s.topLevelOrder) {
                s.topLevelOrder + script.id
            } else {
                s.topLevelOrder
            }
            s.copy(orphanScripts = nextOrphans, topLevelOrder = nextOrder)
        }
    }

    suspend fun deleteOrphan(scriptId: String) {
        dataStore.mutate { s ->
            s.copy(
                orphanScripts = s.orphanScripts.filterNot { it.id == scriptId },
                topLevelOrder = s.topLevelOrder.filterNot { it == scriptId },
            )
        }
        // 联动清理:所有会话快照里的引用 id 同步移除。
        conversationRepository.removeOrphanRegexIdFromAllConversations(scriptId)
    }

    suspend fun duplicateOrphan(scriptId: String) {
        dataStore.mutate { s ->
            val original = s.orphanScripts.find { it.id == scriptId } ?: return@mutate s
            val newId = java.util.UUID.randomUUID().toString()
            val duplicated = original.copy(id = newId, scriptName = "${original.scriptName}*")
            val insertIndex = s.topLevelOrder.indexOf(scriptId)
            val newOrder = s.topLevelOrder.toMutableList().apply {
                add(if (insertIndex >= 0) insertIndex + 1 else size, newId)
            }
            s.copy(orphanScripts = s.orphanScripts + duplicated, topLevelOrder = newOrder)
        }
    }

    suspend fun toggleOrphanEnabled(scriptId: String, disabled: Boolean) {
        dataStore.mutate { s ->
            s.copy(
                orphanScripts = s.orphanScripts.map { script ->
                    if (script.id == scriptId) script.copy(disabled = disabled) else script
                },
            )
        }
    }

    /**
     * 批量设置组和散规则的启用状态。用于 Picker Sheet 的"应用"操作。
     *
     * @param enabledGroupIds 应该启用的组 id 集合(不在集合中的组设为 disabled)
     * @param enabledOrphanIds 应该启用的散规则 id 集合(不在集合中的散规则设为 disabled)
     */
    suspend fun applyEnabledState(enabledGroupIds: Set<String>, enabledOrphanIds: Set<String>) {
        dataStore.mutate { s ->
            s.copy(
                groups = s.groups.map { g -> g.copy(enabled = g.id in enabledGroupIds) },
                orphanScripts = s.orphanScripts.map { script ->
                    script.copy(disabled = script.id !in enabledOrphanIds)
                },
            )
        }
    }

    // ─── 顶层排序 ─────────────────────────────────────────────────────────────

    suspend fun reorderTopLevel(orderedIds: List<String>) {
        dataStore.mutate { s ->
            val knownIds = (s.groups.map { it.id } + s.orphanScripts.map { it.id }).toSet()
            val cleanedHead = orderedIds.filter { it in knownIds }
            val tail = knownIds - cleanedHead.toSet()
            s.copy(topLevelOrder = cleanedHead + tail)
        }
    }

    // ─── 内部工具 ─────────────────────────────────────────────────────────────

    private fun expandEnabled(s: RegexScriptDataStore.Snapshot): List<RegexScript> {
        val groupById = s.groups.associateBy { it.id }
        val orphanById = s.orphanScripts.associateBy { it.id }
        val result = mutableListOf<RegexScript>()
        val knownIds = (s.groups.map { it.id } + s.orphanScripts.map { it.id }).toSet()
        val ordered = s.topLevelOrder.filter { it in knownIds }
        val tail = knownIds - ordered.toSet()

        for (id in ordered + tail) {
            val group = groupById[id]
            if (group != null) {
                if (group.enabled) result.addAll(group.scripts)
                continue
            }
            val orphan = orphanById[id]
            if (orphan != null && !orphan.disabled) {
                result.add(orphan)
            }
        }
        return result
    }
}

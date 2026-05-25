package com.nuttavern.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuttavern.data.regex.RegexGroup
import com.nuttavern.data.regex.RegexScript
import com.nuttavern.data.regex.RegexScriptDataStore
import com.nuttavern.data.regex.RegexScriptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 用户级正则 ViewModel。列表页 / 组内列表页 / 编辑页 / 抽屉 picker 共用。
 *
 * 只管用户级(原 GLOBAL 作用域)。角色卡内嵌正则走 CharacterRegexEditor(纯 UI,无 ViewModel)。
 */
@HiltViewModel
class RegexScriptViewModel @Inject constructor(
    private val repository: RegexScriptRepository,
) : ViewModel() {

    val snapshot: StateFlow<RegexScriptDataStore.Snapshot> = repository.snapshot.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RegexScriptDataStore.Snapshot(emptyList(), emptyList(), emptyList()),
    )

    // ─── 顶层列表(组 + 散规则混合,按 topLevelOrder 排序)────────────────────

    data class TopLevelItem(
        val id: String,
        val isGroup: Boolean,
        val group: RegexGroup? = null,
        val orphan: RegexScript? = null,
    )

    val topLevelItems: StateFlow<List<TopLevelItem>> = snapshot.map { s ->
        buildTopLevelItems(s)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private fun buildTopLevelItems(s: RegexScriptDataStore.Snapshot): List<TopLevelItem> {
        val groupById = s.groups.associateBy { it.id }
        val orphanById = s.orphanScripts.associateBy { it.id }
        val knownIds = (s.groups.map { it.id } + s.orphanScripts.map { it.id }).toSet()
        val ordered = s.topLevelOrder.filter { it in knownIds }
        val tail = knownIds - ordered.toSet()
        return (ordered + tail).mapNotNull { id ->
            val group = groupById[id]
            if (group != null) return@mapNotNull TopLevelItem(id, true, group = group)
            val orphan = orphanById[id]
            if (orphan != null) return@mapNotNull TopLevelItem(id, false, orphan = orphan)
            null
        }
    }

    // ─── 组 CRUD ──────────────────────────────────────────────────────────────

    fun newGroup(): RegexGroup = RegexGroup(id = UUID.randomUUID().toString(), name = "")

    fun upsertGroup(group: RegexGroup) {
        viewModelScope.launch { repository.upsertGroup(group) }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch { repository.deleteGroup(groupId) }
    }

    fun toggleGroupEnabled(groupId: String, enabled: Boolean) {
        viewModelScope.launch { repository.toggleGroupEnabled(groupId, enabled) }
    }

    fun renameGroup(groupId: String, name: String) {
        viewModelScope.launch { repository.renameGroup(groupId, name) }
    }

    // ─── 组内规则 CRUD ────────────────────────────────────────────────────────

    fun newScriptInGroup(): RegexScript = RegexScript(id = UUID.randomUUID().toString())

    fun upsertScriptInGroup(groupId: String, script: RegexScript) {
        viewModelScope.launch { repository.upsertScriptInGroup(groupId, script) }
    }

    fun deleteScriptFromGroup(groupId: String, scriptId: String) {
        viewModelScope.launch { repository.deleteScriptFromGroup(groupId, scriptId) }
    }

    fun reorderScriptsInGroup(groupId: String, orderedIds: List<String>) {
        viewModelScope.launch { repository.reorderScriptsInGroup(groupId, orderedIds) }
    }

    fun findGroupById(groupId: String): Flow<RegexGroup?> = snapshot.map { s ->
        s.groups.firstOrNull { it.id == groupId }
    }

    // ─── 散规则 CRUD ──────────────────────────────────────────────────────────

    fun newOrphanScript(): RegexScript = RegexScript(id = UUID.randomUUID().toString())

    fun upsertOrphan(script: RegexScript) {
        viewModelScope.launch { repository.upsertOrphan(script) }
    }

    fun deleteOrphan(scriptId: String) {
        viewModelScope.launch { repository.deleteOrphan(scriptId) }
    }

    fun duplicateGroup(groupId: String) {
        viewModelScope.launch { repository.duplicateGroup(groupId) }
    }

    fun duplicateOrphan(scriptId: String) {
        viewModelScope.launch { repository.duplicateOrphan(scriptId) }
    }

    fun toggleOrphanEnabled(scriptId: String, disabled: Boolean) {
        viewModelScope.launch { repository.toggleOrphanEnabled(scriptId, disabled) }
    }

    fun findOrphanById(scriptId: String): Flow<RegexScript?> = snapshot.map { s ->
        s.orphanScripts.firstOrNull { it.id == scriptId }
    }

    // ─── 顶层排序 ─────────────────────────────────────────────────────────────

    fun reorderTopLevel(orderedIds: List<String>) {
        viewModelScope.launch { repository.reorderTopLevel(orderedIds) }
    }

    // ─── 按 id 查规则(编辑路径)─────────────────────────────────────────────

    /**
     * 按 id 查规则,**覆盖散规则 + 所有组内规则**,不管启用与否。
     *
     * 编辑路径专用:用户从列表 / 抽屉 picker / 组内列表点进编辑页时,必须能拿到原始记录,即使
     * 该规则被禁用或所在组被禁用。**不能用 [RegexScriptRepository.expandedEnabledScripts]**:
     * 那个流过滤了 disabled 散规则和未启用组,会让编辑页拿到 null → 显示"规则不存在"。
     *
     * 散规则 + 组内规则的 id 全局唯一(`UUID.randomUUID()`),不需要带组 id。
     */
    fun findById(id: String): Flow<RegexScript?> = snapshot.map { s ->
        s.orphanScripts.firstOrNull { it.id == id }
            ?: s.groups.asSequence()
                .flatMap { it.scripts.asSequence() }
                .firstOrNull { it.id == id }
    }
}

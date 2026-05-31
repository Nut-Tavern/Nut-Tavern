package com.nuttavern.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.data.lorebook.LorebookRepository
import com.nuttavern.data.lorebook.LorebookSillyTavernCodec
import com.nuttavern.data.persona.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LorebookViewModel @Inject constructor(
    private val repository: LorebookRepository,
    private val personaRepository: PersonaRepository,
) : ViewModel() {

    val lorebooks: StateFlow<List<Lorebook>> = repository.lorebooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val globalSelectedIds: StateFlow<List<String>> = repository.globalSelectedIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun findById(id: String): Flow<Lorebook?> = repository.findById(id)

    fun newLorebook(): Lorebook = repository.newLorebook()

    fun upsert(lorebook: Lorebook) {
        viewModelScope.launch { repository.upsert(lorebook) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            personaRepository.clearLorebookBinding(id)
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch { repository.duplicate(id) }
    }

    fun reorder(orderedIds: List<String>) {
        viewModelScope.launch { repository.reorder(orderedIds) }
    }

    /**
     * 导入酒馆独立世界书文件 JSON。codec 解析(含 map→list / characterFilter 重命名 / displayIndex
     * 排序)后赋新 UUID + 文件名作书名,直接 upsert(永远新增,无 id 冲突)。解析失败回调 [onError],
     * 不写入仓库。
     *
     * @param jsonText 酒馆世界书 JSON 文本
     * @param bookName 书名(取自文件名)
     */
    fun importFromSillyTavern(
        jsonText: String,
        bookName: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val lorebook = runCatching {
            LorebookSillyTavernCodec.decodeFromSillyTavern(jsonText, bookName)
        }.getOrElse { error ->
            onError(error.message ?: "世界书格式无法解析")
            return
        }
        viewModelScope.launch {
            repository.upsert(lorebook)
            onSuccess(lorebook.name)
        }
    }

    /**
     * 导出指定世界书为酒馆独立世界书文件 JSON 文本。世界书不存在则不回调。
     */
    fun exportToSillyTavern(lorebookId: String, onReady: (fileName: String, jsonText: String) -> Unit) {
        viewModelScope.launch {
            val lorebook = repository.lorebooks.first().firstOrNull { it.id == lorebookId } ?: return@launch
            val jsonText = LorebookSillyTavernCodec.encodeToSillyTavern(lorebook)
            onReady(lorebook.name.ifBlank { "lorebook" }, jsonText)
        }
    }

    fun toggleGlobalSelected(id: String, selected: Boolean) {
        viewModelScope.launch { repository.toggleGlobalSelected(id, selected) }
    }

    // 条目操作
    fun newEntry(lorebook: Lorebook): LorebookEntry = repository.newEntry(lorebook)

    fun upsertEntry(lorebookId: String, entry: LorebookEntry, currentEntries: List<LorebookEntry>) {
        viewModelScope.launch {
            val existing = currentEntries.indexOfFirst { it.uid == entry.uid }
            val updated = if (existing >= 0) {
                currentEntries.toMutableList().apply { this[existing] = entry }
            } else {
                currentEntries + entry
            }
            repository.updateEntries(lorebookId, updated)
        }
    }

    fun deleteEntry(lorebookId: String, entryUid: Int, currentEntries: List<LorebookEntry>) {
        viewModelScope.launch {
            repository.updateEntries(lorebookId, currentEntries.filter { it.uid != entryUid })
        }
    }

    fun toggleEntryEnabled(lorebookId: String, entryUid: Int, disabled: Boolean, currentEntries: List<LorebookEntry>) {
        viewModelScope.launch {
            val updated = currentEntries.map { if (it.uid == entryUid) it.copy(disable = disabled) else it }
            repository.updateEntries(lorebookId, updated)
        }
    }

    fun reorderEntries(lorebookId: String, orderedEntries: List<LorebookEntry>) {
        viewModelScope.launch {
            repository.updateEntries(lorebookId, orderedEntries)
        }
    }

    fun duplicateEntry(lorebookId: String, entryUid: Int, currentEntries: List<LorebookEntry>) {
        viewModelScope.launch {
            val source = currentEntries.firstOrNull { it.uid == entryUid } ?: return@launch
            val maxUid = currentEntries.maxOfOrNull { it.uid } ?: 0
            val copy = source.copy(
                uid = maxUid + 1,
                comment = source.comment + "*",
            )
            val insertIndex = currentEntries.indexOfFirst { it.uid == entryUid } + 1
            val updated = currentEntries.toMutableList().apply { add(insertIndex, copy) }
            repository.updateEntries(lorebookId, updated)
        }
    }
}

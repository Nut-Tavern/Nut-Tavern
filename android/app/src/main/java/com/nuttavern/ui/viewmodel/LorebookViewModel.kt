package com.nuttavern.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.data.lorebook.LorebookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LorebookViewModel @Inject constructor(
    private val repository: LorebookRepository,
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
        viewModelScope.launch { repository.delete(id) }
    }

    fun duplicate(id: String) {
        viewModelScope.launch { repository.duplicate(id) }
    }

    fun reorder(orderedIds: List<String>) {
        viewModelScope.launch { repository.reorder(orderedIds) }
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
}

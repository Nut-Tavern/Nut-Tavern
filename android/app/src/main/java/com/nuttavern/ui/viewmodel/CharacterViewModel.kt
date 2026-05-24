package com.nuttavern.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuttavern.data.character.Character
import com.nuttavern.data.character.CharacterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 角色卡 ViewModel。承载列表页和编辑页的数据。
 *
 * - 列表页订阅 [characters]。
 * - 编辑页通过 [findById] 取角色快照(普通 Flow,不挂 stateIn,避免 viewModelScope 累积订阅);
 *   编辑过程的本地草稿由 Screen 自己保留,保存时整体 [upsert] 写回。
 */
@HiltViewModel
class CharacterViewModel @Inject constructor(
    private val repository: CharacterRepository,
) : ViewModel() {

    val characters: StateFlow<List<Character>> = repository.characters.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /**
     * 取一份角色快照流(普通 Flow,直接走 Room 单条查询,**不**挂 stateIn)。
     *
     * 编辑页的草稿用 `rememberSaveable`,初值通过 `collectAsState` 一次性获取即可,
     * 不需要持续订阅。返回 StateFlow 会让每次调用都在 viewModelScope 里挂一个新
     * stateIn job,长期重复编辑会泄漏。
     */
    fun findById(id: String): Flow<Character?> {
        return repository.observeCharacterById(id)
    }

    fun newCharacter(): Character = Character()

    fun upsert(character: Character) {
        viewModelScope.launch { repository.upsert(character) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    /**
     * 列表页拖动结束后提交角色顺序。
     */
    fun reorderCharacters(orderedIds: List<String>) {
        viewModelScope.launch { repository.reorder(orderedIds) }
    }
}

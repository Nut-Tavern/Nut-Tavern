package com.nuttavern.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuttavern.data.preset.Preset
import com.nuttavern.data.preset.PresetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 预设 ViewModel(设置页 / 编辑页 / 抽屉 picker 共用)。
 *
 * 这里只关心**预设管理本身**:列表展示、增删、默认设置、排序、单条查询。
 * **不**关心"当前会话用哪个预设" — 那是 [ChatViewModel.currentPresetId] 的职责,
 * 持久化到 `conversations.presetId` 字段,生命周期与会话绑定。
 *
 * - 列表页订阅 [items],已包含默认标志;
 * - 编辑页通过 [findById] 取一次预设快照(普通 Flow,不挂 stateIn,避免 viewModelScope 累积订阅);
 * - 抽屉 picker 切换会话预设调 [ChatViewModel.selectPresetForCurrentConversation],
 *   写回当前会话的 presetId,本 ViewModel 不参与。
 */
@HiltViewModel
class PresetViewModel @Inject constructor(
    private val repository: PresetRepository,
) : ViewModel() {

    /**
     * 列表项视图模型。
     *
     * @property preset 预设本体。"是否内置默认预设"用 [Preset.isBuiltInDefault] 直接判断,
     *   不再在本类型上冗余字段。
     * @property isDefault 是否当前默认预设。
     */
    data class PresetListItem(
        val preset: Preset,
        val isDefault: Boolean,
    )

    val items: StateFlow<List<PresetListItem>> = combine(
        repository.presets,
        repository.defaultPresetId,
    ) { presets, defaultId ->
        presets.map { preset ->
            PresetListItem(
                preset = preset,
                isDefault = preset.id == defaultId,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = listOf(
            PresetListItem(
                preset = Preset.default(),
                isDefault = true,
            ),
        ),
    )

    /**
     * 取一份预设快照流(普通 Flow,**不**挂 stateIn)。编辑页一次性获取后用本地草稿。
     */
    fun findById(id: String): Flow<Preset?> = repository.presets
        .map { list -> list.firstOrNull { it.id == id } }

    /** 新建一份空预设(基于默认预设的字段默认值,但分配新 id 和空 name)。 */
    fun newPreset(): Preset = Preset.default().copy(
        id = java.util.UUID.randomUUID().toString(),
        name = "",
        description = "",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )

    fun upsert(preset: Preset) {
        viewModelScope.launch {
            repository.upsert(preset.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun duplicate(id: String) {
        viewModelScope.launch { repository.duplicate(id) }
    }

    fun setDefault(id: String) {
        viewModelScope.launch { repository.setDefault(id) }
    }

    fun reorderPresets(orderedIds: List<String>) {
        viewModelScope.launch { repository.reorder(orderedIds) }
    }
}

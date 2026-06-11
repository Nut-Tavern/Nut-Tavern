package com.nuttavern.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuttavern.data.tools.LocalToolsRepository
import com.nuttavern.data.tools.LocalToolsSettings
import com.nuttavern.network.ChatTool
import com.nuttavern.network.ChatToolRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 内置工具设置 ViewModel。
 *
 * 管理 [LocalToolsSettings](各工具默认启用 / 各工具调用前确认),并暴露注册表里的工具定义
 * 供设置页枚举。与 [ToolsViewModel](工具调用引擎高级设置)分开,单文件单职责。
 */
@HiltViewModel
class LocalToolsViewModel @Inject constructor(
    private val repository: LocalToolsRepository,
    private val toolRegistry: ChatToolRegistry,
) : ViewModel() {

    val settings: StateFlow<LocalToolsSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LocalToolsSettings(),
    )

    /** 注册表里的全部内置工具定义(展示用,不代表启用状态)。 */
    val tools: List<ChatTool> = toolRegistry.tools

    /** 批量启用 / 禁用一组工具(单工具传单元素集合,工具组把组内全部 id 一起增删)。 */
    fun setToolsEnabled(toolIds: Set<String>, enabled: Boolean) {
        viewModelScope.launch {
            repository.update { current ->
                val next = if (enabled) {
                    current.enabledToolIds + toolIds
                } else {
                    current.enabledToolIds - toolIds
                }
                current.copy(enabledToolIds = next)
            }
        }
    }

    /** 批量设置一组工具是否调用前确认。 */
    fun setToolsApprovalRequired(toolIds: Set<String>, required: Boolean) {
        viewModelScope.launch {
            repository.update { current ->
                val next = if (required) {
                    current.approvalRequiredToolIds + toolIds
                } else {
                    current.approvalRequiredToolIds - toolIds
                }
                current.copy(approvalRequiredToolIds = next)
            }
        }
    }

    /** 回写工具展示单元顺序(设置页拖动排序后调用)。orderKey 见 [com.nuttavern.network.ToolUnit]。 */
    fun setToolOrder(orderKeys: List<String>) {
        viewModelScope.launch {
            repository.update { it.copy(toolOrder = orderKeys) }
        }
    }
}

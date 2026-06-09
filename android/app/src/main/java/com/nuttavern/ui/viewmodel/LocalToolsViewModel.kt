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
 * 管理 [LocalToolsSettings](全局默认开关 / 人工确认 / 各工具启用集),并暴露注册表里的工具定义
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

    fun setDefaultEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.update { it.copy(defaultEnabled = enabled) }
        }
    }

    fun setRequireApproval(required: Boolean) {
        viewModelScope.launch {
            repository.update { it.copy(requireApproval = required) }
        }
    }

    fun setToolEnabled(toolId: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.update { current ->
                val next = if (enabled) {
                    current.enabledToolIds + toolId
                } else {
                    current.enabledToolIds - toolId
                }
                current.copy(enabledToolIds = next)
            }
        }
    }
}

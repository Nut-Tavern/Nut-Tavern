package com.nuttavern.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuttavern.data.tools.ToolReasoningMode
import com.nuttavern.data.tools.ToolsSettings
import com.nuttavern.data.tools.ToolsSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 工具设置 ViewModel。
 *
 * 当前只管 [ToolsSettings] 数据袋(tool_call_recurse_limit / tool_reasoning_mode)。
 * 后续接入 MCP 服务器列表 / 内置工具开关时,**新建独立的 ViewModel**(McpServerViewModel /
 * BuiltInToolViewModel),不要往这里塞 — 设置面板单文件单职责。
 */
@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val repository: ToolsSettingsRepository,
) : ViewModel() {
    val settings: StateFlow<ToolsSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ToolsSettings(),
    )

    fun setRecurseLimit(value: Int) {
        val clamped = value.coerceIn(
            ToolsSettings.MIN_RECURSE_LIMIT,
            ToolsSettings.MAX_RECURSE_LIMIT,
        )
        viewModelScope.launch {
            repository.update { it.copy(toolCallRecurseLimit = clamped) }
        }
    }

    fun setReasoningMode(mode: ToolReasoningMode) {
        viewModelScope.launch {
            repository.update { it.copy(toolReasoningMode = mode) }
        }
    }
}

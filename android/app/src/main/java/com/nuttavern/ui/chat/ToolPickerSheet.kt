package com.nuttavern.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuttavern.network.ChatTool
import com.nuttavern.network.ToolUnit
import com.nuttavern.network.buildToolUnits
import com.nuttavern.ui.components.NutTavernSheetTitle
import com.nuttavern.ui.regex.MultiSelectPickerCard

/**
 * 右侧栏的"内置工具选择"抽屉。
 *
 * 与世界书 / 正则选择抽屉同一形态:暂存每个工具在当前会话的启用状态,只有点"应用"才回写。
 * 下滑 / 点外部关闭 = 取消修改。同组工具合并成一张卡用一个总开关一起开关。展示顺序跟随
 * 内置工具页拖动排序([toolOrder]),与设置页口径一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPickerSheet(
    visible: Boolean,
    tools: List<ChatTool>,
    enabledToolIds: Set<String>,
    toolOrder: List<String>,
    onApply: (enabledToolIds: Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 暂存启用状态:打开时以当前会话启用集为初值,点"应用"才回写。
    var selectedToolIds by remember(visible) { mutableStateOf(enabledToolIds.toSet()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NutTavernSheetTitle(title = "内置工具", modifier = Modifier.weight(1f))
                TextButton(onClick = { onApply(selectedToolIds) }) { Text("应用") }
            }

            if (tools.isEmpty()) {
                Text(
                    text = "暂无可用内置工具",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                val units = remember(tools, toolOrder) { buildToolUnits(tools, toolOrder) }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(units, key = { it.orderKey }) { unit ->
                        // 组开关状态:组内工具全部启用才算"开";切换时整组一起增删。
                        val allEnabled = unit.toolIds.all { it in selectedToolIds }
                        MultiSelectPickerCard(
                            title = unit.pickerTitle,
                            subtitle = unit.pickerSubtitle,
                            enabled = allEnabled,
                            onToggle = { enabled ->
                                selectedToolIds = if (enabled) selectedToolIds + unit.toolIds
                                else selectedToolIds - unit.toolIds
                            },
                        )
                    }
                }
            }
        }
    }
}

private val ToolUnit.pickerTitle: String
    get() = when (this) {
        is ToolUnit.SingleTool -> tool.displayName
        is ToolUnit.Group -> group.displayName
    }

private val ToolUnit.pickerSubtitle: String
    get() = when (this) {
        is ToolUnit.SingleTool -> toolPickerSubtitle(tool.name, tool.description)
        is ToolUnit.Group -> group.description
    }

private fun toolPickerSubtitle(name: String, description: String): String =
    if (name == "get_current_time") "获取设备当前本地时间" else description.ifBlank { name }

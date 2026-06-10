package com.nuttavern.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuttavern.data.tools.ConversationToolMode
import com.nuttavern.ui.components.NutTavernSelectableRow
import com.nuttavern.ui.components.NutTavernSheetTitle

/**
 * 右侧栏"内置工具"会话级开关选择。
 *
 * 点选即应用并关闭:本会话启用 / 本会话关闭。新会话默认值只在创建会话时固化,这里不再提供
 * "跟随全局"选项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolModePickerSheet(
    visible: Boolean,
    currentMode: ConversationToolMode,
    onSelect: (ConversationToolMode) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NutTavernSheetTitle(
                title = "本会话内置工具",
                description = "控制当前会话是否允许模型调用内置工具，只影响这一个会话。",
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    NutTavernSelectableRow(
                        title = "本会话启用工具",
                        subtitle = "允许模型在这个会话里调用已启用的内置工具",
                        selected = currentMode != ConversationToolMode.FORCE_OFF,
                        onClick = { onSelect(ConversationToolMode.FORCE_ON) },
                    )
                }
                item {
                    NutTavernSelectableRow(
                        title = "本会话关闭工具",
                        subtitle = "这个会话不向模型下发任何内置工具",
                        selected = currentMode == ConversationToolMode.FORCE_OFF,
                        onClick = { onSelect(ConversationToolMode.FORCE_OFF) },
                    )
                }
            }
        }
    }
}

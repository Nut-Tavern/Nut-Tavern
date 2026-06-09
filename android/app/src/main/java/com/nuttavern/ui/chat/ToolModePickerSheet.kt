package com.nuttavern.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * 右侧栏"内置工具"会话级开关三态选择。
 *
 * 点选即应用并关闭:跟随全局 / 本会话强制开启 / 本会话强制关闭。"跟随全局"行副标题动态展示
 * 当前全局默认开关状态,让用户清楚跟随后的实际效果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolModePickerSheet(
    visible: Boolean,
    currentMode: ConversationToolMode,
    globalDefaultEnabled: Boolean,
    onSelect: (ConversationToolMode) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val followSubtitle = if (globalDefaultEnabled) {
        "当前全局默认:启用"
    } else {
        "当前全局默认:关闭"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NutTavernSheetTitle(
                title = "本会话内置工具",
                description = "控制当前会话是否允许模型调用内置工具,只影响这一个会话。",
            )
            NutTavernSelectableRow(
                title = "跟随全局默认",
                subtitle = followSubtitle,
                selected = currentMode == ConversationToolMode.FOLLOW_GLOBAL,
                onClick = { onSelect(ConversationToolMode.FOLLOW_GLOBAL) },
            )
            NutTavernSelectableRow(
                title = "本会话强制启用",
                subtitle = "无视全局默认,这个会话始终允许调用工具",
                selected = currentMode == ConversationToolMode.FORCE_ON,
                onClick = { onSelect(ConversationToolMode.FORCE_ON) },
            )
            NutTavernSelectableRow(
                title = "本会话强制关闭",
                subtitle = "无视全局默认,这个会话始终不调用工具",
                selected = currentMode == ConversationToolMode.FORCE_OFF,
                onClick = { onSelect(ConversationToolMode.FORCE_OFF) },
            )
        }
    }
}

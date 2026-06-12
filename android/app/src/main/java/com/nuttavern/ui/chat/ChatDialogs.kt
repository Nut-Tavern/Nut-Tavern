package com.nuttavern.ui.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.nuttavern.data.model.ConversationSummary
import com.nuttavern.data.model.Message
import com.nuttavern.network.ToolApprovalDetails
import com.nuttavern.ui.components.NutTavernSheetTitle

@Composable
internal fun RegenerateMessageDialog(
    message: Message?,
    onConfirm: (Message) -> Unit,
    onDismiss: () -> Unit,
) {
    if (message == null) return

    val isUserMessage = message.role == "user"
    // 两种语义(中间 assistant 不再有重生入口,UI 层就不会传中间 assistant 进来):
    //   1. 用户消息:基于这条消息追加新 assistant(对齐酒馆末条 user option_regenerate);
    //      中间 user 会同时丢弃之后所有消息再重生(Nut Tavern 既有 user 重走语义)。
    //   2. 末条 assistant:删除当前回复(含所有 swipes)并重生(对齐酒馆 option_regenerate)。
    val title = if (isUserMessage) "重新生成回复" else "重新生成"
    val description = when {
        isUserMessage -> "将丢弃这条消息之后的所有回复并重新生成。"
        // 多候选 assistant:重生会把整批候选连同当前回复一起删掉,必须显式提示。
        message.hasMultipleSwipes -> "将删除当前回复并重新生成。原回复的所有候选会一起丢失。"
        // 单候选 assistant:本来就只有一条回复,提"所有候选会一起丢失"是冗余且让人困惑。
        else -> "将删除当前回复并重新生成。"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(message) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun DeleteMessageDialog(
    message: Message?,
    onConfirm: (Message, deleteCurrentSwipeOnly: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (message == null) return

    // 删除语义对齐酒馆 deleteSwipe(script.js:9279) + .mes_edit_delete(script.js:1656):
    // - 多候选:只删当前选中的候选,本条消息保留(可撤回 = 切回其他候选);
    // - 单候选 / 无候选:整条消息删除,前后拼接(不可撤回)。
    val deleteCurrentSwipeOnly = message.hasMultipleSwipes
    val title = if (deleteCurrentSwipeOnly) "删除当前候选" else "删除消息"
    val description = if (deleteCurrentSwipeOnly) {
        val remaining = message.swipes.size - 1
        "将删除当前选中的候选,这条消息还会保留 $remaining 个候选可切换。"
    } else {
        "确定要删除这条消息吗？此操作不可撤销。"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(message, deleteCurrentSwipeOnly) }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun RenameConversationDialog(
    conversation: ConversationSummary?,
    onConfirm: (ConversationSummary, String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (conversation == null) return

    var input by rememberSaveable(conversation.id) {
        mutableStateOf(conversation.title)
    }
    val trimmed = input.trim()
    val canConfirm = trimmed.isNotBlank() && trimmed != conversation.title

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("会话名称") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(conversation, trimmed) },
                enabled = canConfirm,
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun DeleteConversationDialog(
    conversation: ConversationSummary?,
    onConfirm: (ConversationSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    if (conversation == null) return

    val title = conversation.title.ifBlank { "未命名会话" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除会话", style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                text = "确定要删除「$title」吗？该会话内的所有消息都会一并删除,且不可撤销。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(conversation) }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 模型调用内置工具前的人工确认弹窗。
 *
 * 仅在对应工具开启"调用前确认"或工具自身标记高风险时弹出。底部抽屉与"工具调用详情"统一样式,内容可上下滚动。
 * 点外部 / 下滑关闭等同拒绝(onDismissRequest = onDeny),避免误触把挂起的工具调用悬空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolApprovalDialog(
    displayName: String,
    argumentsJson: String,
    details: ToolApprovalDetails? = null,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDeny,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            NutTavernSheetTitle(
                title = "允许调用工具？",
                description = "模型请求调用「$displayName」。",
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (details != null) {
                if (!details.description.isNullOrBlank()) {
                    Text(
                        text = details.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (details.diffs.isNotEmpty()) {
                    ToolDiffPreview(diffs = details.diffs)
                    Spacer(modifier = Modifier.height(12.dp))
                }
                details.warnings.forEach { warning ->
                    Text(
                        text = "⚠ $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            } else if (argumentsJson.isNotBlank()) {
                Text(
                    text = "参数: $argumentsJson",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDeny) {
                    Text("拒绝", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onAllow) { Text("允许") }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

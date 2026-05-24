package com.nuttavern.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@Composable
internal fun RegenerateMessageDialog(
    message: Message?,
    onConfirm: (Message) -> Unit,
    onDismiss: () -> Unit,
) {
    if (message == null) return

    val description = if (message.role == "user") {
        "将丢弃这条消息之后的所有回复并重新生成。"
    } else {
        "将丢弃当前这条回复并重新生成。"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新生成", style = MaterialTheme.typography.titleLarge) },
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
    onConfirm: (Message) -> Unit,
    onDismiss: () -> Unit,
) {
    if (message == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除消息", style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                text = "确定要删除这条消息吗？此操作不可撤销。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(message) }) {
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
            Column(modifier = Modifier.fillMaxWidth()) {
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


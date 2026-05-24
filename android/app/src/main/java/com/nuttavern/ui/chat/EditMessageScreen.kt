package com.nuttavern.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.nuttavern.data.model.Message
import com.nuttavern.ui.components.NutTavernUiTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditMessageFullScreen(
    message: Message?,
    content: String,
    onContentChange: (String) -> Unit,
    onConfirm: (Message, String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (message == null) return

    BackHandler(enabled = true) { onDismiss() }

    val normalizedContent = content.trim()
    val isContentValid = normalizedContent.isNotBlank()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = "编辑消息",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Lucide.X, "取消编辑")
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = { onConfirm(message, normalizedContent) },
                                enabled = isContentValid,
                            ) {
                                Text("保存")
                            }
                        },
                    )
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding()
                        .padding(NutTavernUiTokens.PageHorizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(NutTavernUiTokens.SectionSpacing),
                ) {
                    OutlinedTextField(
                        value = content,
                        onValueChange = onContentChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        label = { Text("消息内容") },
                        isError = !isContentValid,
                        supportingText = if (!isContentValid) {
                            { Text("消息不能为空") }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

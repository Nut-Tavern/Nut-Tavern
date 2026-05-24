package com.nuttavern.ui.components

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

/**
 * 全屏文本编辑器(沉浸式 Dialog)。
 *
 * 用途:Composer 草稿、用户身份描述、未来角色卡描述等长文本字段的全屏编辑入口。
 * 调用方维持本地草稿态,在 [onSave] 回调中决定如何持久化;关闭走 [onDismiss],
 * 是否保留草稿由调用方决定。
 *
 * 视觉规则:
 * - 顶部 [TopAppBar]:左 X 关闭、中 [title]、右"完成"。
 * - 主体:单 [OutlinedTextField] 占满高度,自动跟键盘 imePadding。
 * - 不接 ViewModel,纯受控组件,便于在不同上下文中复用。
 *
 * 使用示例(摘自 [com.nuttavern.ui.chat.DraftEditFullScreen]):
 * ```
 * NutTavernFullScreenTextEditor(
 *     visible = showFullScreen,
 *     title = "编辑草稿",
 *     fieldLabel = "草稿内容",
 *     value = draft,
 *     onValueChange = { draft = it },
 *     onSave = { commitDraft(); showFullScreen = false },
 *     onDismiss = { showFullScreen = false },
 * )
 * ```
 *
 * 不在本组件做"未保存提醒",外层调用方需要时自行包一层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutTavernFullScreenTextEditor(
    visible: Boolean,
    title: String,
    fieldLabel: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    saveLabel: String = "完成",
    placeholder: String? = null,
) {
    if (!visible) return

    BackHandler(enabled = true) { onDismiss() }

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
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Lucide.X, "关闭全屏编辑")
                            }
                        },
                        actions = {
                            TextButton(onClick = onSave) {
                                Text(saveLabel)
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
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        label = { Text(fieldLabel) },
                        placeholder = placeholder?.let { { Text(it) } },
                    )
                }
            }
        }
    }
}

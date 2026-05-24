package com.nuttavern.ui.chat

import androidx.compose.runtime.Composable
import com.nuttavern.ui.components.NutTavernFullScreenTextEditor

/**
 * Composer 草稿全屏编辑入口。
 *
 * 真实实现已抽到 [NutTavernFullScreenTextEditor],本文件保留这个名字让 ChatScreen
 * 调用点不动。后续若 Composer 需要更复杂的工具栏 / 计数器,在这一层包装即可。
 */
@Composable
internal fun DraftEditFullScreen(
    visible: Boolean,
    content: String,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    NutTavernFullScreenTextEditor(
        visible = visible,
        title = "编辑草稿",
        fieldLabel = "草稿内容",
        value = content,
        onValueChange = onContentChange,
        onSave = onSave,
        onDismiss = onDismiss,
    )
}

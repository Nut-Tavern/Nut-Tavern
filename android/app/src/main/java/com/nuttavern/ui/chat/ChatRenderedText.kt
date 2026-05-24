package com.nuttavern.ui.chat

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.nuttavern.ui.chat.markdown.ChatMarkdown

/**
 * 聊天消息文本渲染入口。
 *
 * - `renderMarkdown = false`:走纯 [Text]。用于用户气泡(纯文本输入,markdown 解析容易误伤
 *   `*` `_` 等字符)和思考链(字号 bodySmall,markdown 默认排版与它不协调)。
 * - `renderMarkdown = true`:走自有渲染层 [ChatMarkdown]。AST → Compose 直渲,
 *   流式期间不进入 loading 态,中间态稳定。
 */
internal enum class ChatRenderedTextRole {
    Message,
    Reasoning,
}

@Composable
internal fun ChatRenderedText(
    content: String,
    modifier: Modifier = Modifier,
    textStyleRole: ChatRenderedTextRole = ChatRenderedTextRole.Message,
    selectable: Boolean = true,
    color: Color = chatRenderedTextColor(textStyleRole),
    renderMarkdown: Boolean = false,
) {
    if (content.isBlank()) return

    if (renderMarkdown) {
        ChatMarkdown(
            content = content,
            color = color,
            modifier = modifier,
        )
        return
    }

    val textStyle = chatRenderedTextStyle(textStyleRole)
    val textContent: @Composable () -> Unit = {
        Text(
            text = content,
            modifier = modifier,
            style = textStyle,
            color = color,
        )
    }

    if (selectable) {
        SelectionContainer {
            textContent()
        }
    } else {
        textContent()
    }
}

@Composable
private fun chatRenderedTextStyle(textStyleRole: ChatRenderedTextRole): TextStyle {
    return when (textStyleRole) {
        ChatRenderedTextRole.Message -> MaterialTheme.typography.bodyLarge
        ChatRenderedTextRole.Reasoning -> MaterialTheme.typography.bodySmall
    }
}

@Composable
private fun chatRenderedTextColor(textStyleRole: ChatRenderedTextRole): Color {
    return when (textStyleRole) {
        ChatRenderedTextRole.Message -> MaterialTheme.colorScheme.onSurface
        ChatRenderedTextRole.Reasoning -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

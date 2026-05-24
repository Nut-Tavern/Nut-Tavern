package com.nuttavern.ui.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.RefreshCcw
import com.composables.icons.lucide.Trash2
import com.nuttavern.data.model.GeneratedContentSanitizer
import com.nuttavern.data.model.Message
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow

@Composable
internal fun ChatMessageRow(
    message: Message,
    maxMessageWidth: Dp,
    onCopyMessage: (Message) -> Unit,
    onEditMessage: (Message) -> Unit,
    onRegenerateMessage: (Message) -> Unit,
    onDeleteMessage: (Message) -> Unit,
) {
    val isUserMessage = message.role == "user"
    val alignment = if (isUserMessage) Alignment.CenterEnd else Alignment.CenterStart
    val visibleContent = if (isUserMessage) {
        message.content
    } else {
        GeneratedContentSanitizer.sanitizeGeneratedDisplayText(message.content)
    }
    val visibleReasoningContent = GeneratedContentSanitizer.sanitizeGeneratedDisplayText(message.reasoningContent)

    var actionsSheetVisible by remember(message.id) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    // 只接收长按手势,不消费短按。这样既不会出现 ripple 高亮波动,也不会阻断
    // assistant 全宽 Markdown / user 气泡内的文本选择(SelectionContainer 与
    // markdown 的链接点击)。
    val longPressModifier = Modifier.pointerInput(message.id) {
        detectTapGestures(
            onLongPress = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                actionsSheetVisible = true
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = MESSAGE_ROW_HORIZONTAL_PADDING,
                end = MESSAGE_ROW_HORIZONTAL_PADDING,
                top = 4.dp,
                bottom = 4.dp,
            ),
    ) {
        if (!isUserMessage && visibleReasoningContent.isNotBlank()) {
            // ChatReasoningBlock 自身决定宽度(折叠态非全宽);这里给个外层 padding 让它贴左对齐。
            ChatReasoningBlock(
                reasoningContent = visibleReasoningContent,
                reasoningDurationMillis = message.reasoningDurationMillis,
                isStreaming = false,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (visibleContent.isNotBlank()) {
            if (isUserMessage) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = alignment,
                ) {
                    Surface(
                        modifier = Modifier
                            .widthIn(max = maxMessageWidth)
                            .clip(MaterialTheme.shapes.large)
                            .then(longPressModifier),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        ChatRenderedText(
                            content = visibleContent,
                            textStyleRole = ChatRenderedTextRole.Message,
                            selectable = false,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                        )
                    }
                }
            } else {
                ChatRenderedText(
                    content = visibleContent,
                    textStyleRole = ChatRenderedTextRole.Message,
                    selectable = false,
                    color = MaterialTheme.colorScheme.onSurface,
                    renderMarkdown = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(longPressModifier)
                        .padding(top = 12.dp, bottom = 12.dp),
                )
            }
        }
    }

    if (actionsSheetVisible) {
        MessageActionsSheet(
            isUserMessage = isUserMessage,
            onDismiss = { actionsSheetVisible = false },
            onCopy = {
                actionsSheetVisible = false
                onCopyMessage(message)
            },
            onEdit = {
                actionsSheetVisible = false
                onEditMessage(message)
            },
            onRegenerate = {
                actionsSheetVisible = false
                onRegenerateMessage(message)
            },
            onDelete = {
                actionsSheetVisible = false
                onDeleteMessage(message)
            },
        )
    }
}

@Composable
internal fun StreamingMessageRow(
    content: String,
    reasoningContent: String,
    reasoningDurationMillis: Long,
) {
    val visibleContent = GeneratedContentSanitizer.sanitizeGeneratedDisplayText(content)
    val visibleReasoningContent = GeneratedContentSanitizer.sanitizeGeneratedDisplayText(reasoningContent)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = MESSAGE_ROW_HORIZONTAL_PADDING,
                end = MESSAGE_ROW_HORIZONTAL_PADDING,
                top = 4.dp,
                bottom = 4.dp,
            ),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 12.dp),
            ) {
                if (visibleReasoningContent.isNotBlank()) {
                    ChatReasoningBlock(
                        reasoningContent = visibleReasoningContent,
                        reasoningDurationMillis = reasoningDurationMillis,
                        isStreaming = true,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (visibleContent.isNotBlank()) {
                    // 流式与最终态共用同一套自有 markdown 渲染:
                    // - 首屏同步解析,无 loading 空白
                    // - 后续 token 抵达走 Dispatchers.Default + mapLatest 异步重解析
                    // - UI 始终用最新 ast 渲染,不进入 loading 态、不抖动
                    ChatRenderedText(
                        content = visibleContent,
                        textStyleRole = ChatRenderedTextRole.Message,
                        selectable = false,
                        color = MaterialTheme.colorScheme.onSurface,
                        renderMarkdown = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Spacer(modifier = Modifier.height(4.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    isUserMessage: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
        ) {
            NutTavernGroupSection {
                NutTavernIconRow(
                    icon = Lucide.Copy,
                    title = "复制",
                    onClick = onCopy,
                )
                NutTavernGroupDivider()
                NutTavernIconRow(
                    icon = Lucide.Pencil,
                    title = "编辑",
                    onClick = onEdit,
                )
                NutTavernGroupDivider()
                NutTavernIconRow(
                    icon = Lucide.RefreshCcw,
                    title = if (isUserMessage) "重新生成回复" else "重新生成",
                    onClick = onRegenerate,
                )
            }
            NutTavernGroupSection {
                NutTavernIconRow(
                    icon = Lucide.Trash2,
                    title = "删除",
                    destructive = true,
                    onClick = onDelete,
                )
            }
        }
    }
}

private val MESSAGE_ROW_HORIZONTAL_PADDING = 12.dp

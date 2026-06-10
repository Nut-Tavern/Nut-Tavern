package com.nuttavern.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.RefreshCcw
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.nuttavern.data.model.GeneratedContentSanitizer
import com.nuttavern.data.model.ImageAttachment
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
    // 第一批沿用"先思考块再正文"的固定渲染:从 parts 抽出正文文本与思考块。
    // 有序穿插(groupMessageParts)在第三批接入。
    val messageText = message.text
    val visibleContent = if (isUserMessage) {
        messageText
    } else {
        GeneratedContentSanitizer.sanitizeGeneratedDisplayText(messageText)
    }
    val reasoningPart = message.reasoning
    val visibleReasoningContent = reasoningPart
        ?.let { GeneratedContentSanitizer.sanitizeGeneratedDisplayText(it.text) }
        .orEmpty()

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
                reasoningDurationMillis = reasoningPart?.durationMillis ?: 0L,
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
        // 附件图片放在文字下方,与气泡同侧对齐。点击可全屏放大查看。
        if (isUserMessage && message.attachments.isNotEmpty()) {
            if (visibleContent.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
            }
            MessageAttachmentImages(
                attachments = message.attachments,
                maxMessageWidth = maxMessageWidth,
                alignment = alignment,
            )
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

/**
 * 用户消息已发送的图片缩略图。横向排列,与气泡同侧对齐(用户消息靠右)。
 * 点击缩略图打开全屏可缩放预览([ImagePreviewDialog])。
 */
@Composable
private fun MessageAttachmentImages(
    attachments: List<ImageAttachment>,
    maxMessageWidth: Dp,
    alignment: Alignment,
) {
    var previewPath by remember { mutableStateOf<String?>(null) }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = maxMessageWidth)
                .wrapContentWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            attachments.forEach { attachment ->
                AsyncImage(
                    model = attachment.path,
                    contentDescription = "已发送图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(120.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { previewPath = attachment.path },
                )
            }
        }
    }

    previewPath?.let { path ->
        ImagePreviewDialog(
            model = path,
            onDismiss = { previewPath = null },
        )
    }
}

/**
 * 全屏图片预览。双指缩放 + 拖动平移,点击空白 / 返回键 / 右上角关闭键关闭。
 *
 * 自写原因:M3 没有图片查看器组件;Coil 的 AsyncImage 不带手势缩放。这里用 graphicsLayer +
 * detectTransformGestures 实现最小可用缩放查看,缩放区间 1x~5x,缩小到 1x 时归位平移。
 */
@Composable
private fun ImagePreviewDialog(
    model: Any?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onDismiss() })
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = model,
                contentDescription = "图片预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = "关闭预览",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
internal fun StreamingMessageRow(
    content: String,
    reasoningContent: String,
    reasoningDurationMillis: Long,
    currentToolActivity: String? = null,
) {
    val visibleContent = GeneratedContentSanitizer.sanitizeGeneratedDisplayText(content)
    val visibleReasoningContent = GeneratedContentSanitizer.sanitizeGeneratedDisplayText(reasoningContent)
    // 确定当前阶段 (0: 生成回复中, 1: 调用工具, 2: 思考中, 3: 等待回调)
    val stage = when {
        visibleContent.isNotBlank() -> 0
        currentToolActivity != null -> 1
        visibleReasoningContent.isNotBlank() -> 2
        else -> 3
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
                // 所有阶段统一用 M3 加载环 + 对应文案,只切文案不切控件形态
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.defaultMinSize(minHeight = 24.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    AnimatedContent(
                        targetState = stage,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "StreamingStageText"
                    ) { targetStage ->
                        val text = when (targetStage) {
                            0 -> "生成中..."
                            1 -> "正在调用: ${currentToolActivity}"
                            2 -> "思考中..."
                            else -> "等待响应..."
                        }
                        // 生成回复中正文已在上方流式渲染,这里文案弱化,避免与正文抢视觉
                        val textColor = if (targetStage == 0) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        androidx.compose.material3.Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor,
                        )
                    }
                }
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

package com.nuttavern.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCcw
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.nuttavern.data.model.GeneratedContentSanitizer
import com.nuttavern.data.model.FileAttachment
import com.nuttavern.data.model.ImageAttachment
import com.nuttavern.data.model.Message
import com.nuttavern.data.model.MessagePart
import com.nuttavern.ui.components.FileAttachmentPill
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow

@Composable
internal fun ChatMessageRow(
    message: Message,
    maxMessageWidth: Dp,
    isLastAssistantMessage: Boolean,
    toolDisplayName: (String) -> String,
    onCopyMessage: (Message) -> Unit,
    onEditMessage: (Message) -> Unit,
    onRegenerateMessage: (Message) -> Unit,
    onGenerateNewSwipeMessage: (Message) -> Unit,
    onSelectSwipe: (messageId: String, targetIndex: Int) -> Unit,
    onDeleteMessage: (Message) -> Unit,
) {
    val isUserMessage = message.role == "user"
    val alignment = if (isUserMessage) Alignment.CenterEnd else Alignment.CenterStart

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
            ),
    ) {
        if (isUserMessage) {
            UserMessageContent(
                message = message,
                maxMessageWidth = maxMessageWidth,
                alignment = alignment,
                longPressModifier = longPressModifier,
            )
        } else {
            AssistantMessageContent(
                message = message,
                toolDisplayName = toolDisplayName,
                longPressModifier = longPressModifier,
            )
        }
    }

    if (actionsSheetVisible) {
        MessageActionsSheet(
            isUserMessage = isUserMessage,
            isLastAssistantMessage = isLastAssistantMessage,
            // swipe 切换组:任意 assistant 消息只要存在多个候选都展示(对齐酒馆 swipe-picker
            // canOpenSwipePickerForMessage:不要求末条,只要 swipes>1 && 非 user)。这样中间消息
            // (历史末条被继续聊后下沉、首条 alternateGreetings)的多 swipe 也能直接切换查看,
            // 不会被 DeleteMessageDialog "保留 N 个候选可切换" 的文案承诺骗。
            showSwipeSwitcher = !isUserMessage && message.hasMultipleSwipes,
            swipeIndex = message.swipeIndex,
            swipeCount = message.swipes.size,
            onSelectSwipe = { targetIndex -> onSelectSwipe(message.id, targetIndex) },
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
            onGenerateNewSwipe = {
                actionsSheetVisible = false
                onGenerateNewSwipeMessage(message)
            },
            onDelete = {
                actionsSheetVisible = false
                onDeleteMessage(message)
            },
        )
    }
}

/**
 * 用户消息内容:正文气泡(靠右) + 下方图片附件。用户消息只含 Text part,不会有思考 / 工具块。
 */
@Composable
private fun UserMessageContent(
    message: Message,
    maxMessageWidth: Dp,
    alignment: Alignment,
    longPressModifier: Modifier,
) {
    val visibleContent = message.text
    if (visibleContent.isNotBlank()) {
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
    }
    // 文件附件药丸:位于气泡和图片附件之间,与气泡同侧对齐。点击触发系统 ACTION_VIEW。
    if (message.fileAttachments.isNotEmpty()) {
        if (visibleContent.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
        }
        MessageFileAttachments(
            fileAttachments = message.fileAttachments,
            maxMessageWidth = maxMessageWidth,
            alignment = alignment,
        )
    }
    // 附件图片放在文字下方,与气泡同侧对齐。点击可全屏放大查看。
    if (message.attachments.isNotEmpty()) {
        if (visibleContent.isNotBlank() || message.fileAttachments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
        }
        MessageAttachmentImages(
            attachments = message.attachments,
            maxMessageWidth = maxMessageWidth,
            alignment = alignment,
        )
    }
}

/**
 * 助手消息内容:按 [groupMessageParts] 分组后有序渲染——
 * 连续的思考 / 工具调用聚成时间线块(竖排紧凑卡片),正文 Text 打断成独立 markdown 块。
 */
@Composable
private fun AssistantMessageContent(
    message: Message,
    toolDisplayName: (String) -> String,
    longPressModifier: Modifier,
) {
    val blocks = remember(message.parts) { message.parts.groupMessageParts() }
    blocks.forEachIndexed { index, block ->
        if (index > 0) {
            Spacer(modifier = Modifier.height(4.dp))
        }
        when (block) {
            is MessagePartBlock.Timeline -> TimelineBlock(
                steps = block.steps,
                toolDisplayName = toolDisplayName,
            )
            is MessagePartBlock.Body -> {
                val visibleContent =
                    GeneratedContentSanitizer.sanitizeGeneratedDisplayText(block.text.text)
                if (visibleContent.isNotBlank()) {
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
    }
}

/**
 * 时间线块:思考项各自独立成卡,连续的工具调用聚成一张合并折叠卡。
 * 段与段之间 4dp 间距,共用 surfaceContainerLow 底色形成视觉成组。
 */
@Composable
private fun TimelineBlock(
    steps: List<MessagePart>,
    toolDisplayName: (String) -> String,
) {
    val segments = remember(steps) { steps.splitTimelineSegments() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is TimelineSegment.Thinking -> ChatReasoningBlock(
                    reasoningContent = segment.reasoning.text,
                    reasoningDurationMillis = segment.reasoning.durationMillis,
                    isStreaming = false,
                )
                is TimelineSegment.ToolGroup -> ChatToolCallGroupBlock(
                    toolCalls = segment.toolCalls,
                    toolDisplayName = toolDisplayName,
                )
            }
        }
    }
}

/**
 * 消息内文件附件:横向 [FlowRow] 自适应换行,与气泡同侧对齐。
 * 点击药丸触发 [openFileWithSystemViewer],由系统选择文本编辑器打开。
 *
 * 使用 [FlowRow] 而非 [Row]:文件名长度差异大,单行 Row 会被气泡最大宽度截断;FlowRow 在
 * 单行容不下时自动换到下一行,每个药丸都按内容宽度展示(单行 ellipsis 兜底极长名称)。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageFileAttachments(
    fileAttachments: List<FileAttachment>,
    maxMessageWidth: Dp,
    alignment: Alignment,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        // user 侧气泡靠右,药丸排列也要从右起;assistant 侧靠左,默认 spacedBy 即可。
        // 这里 alignment 由父级传入(user=CenterEnd,assistant=CenterStart),据此决定对齐方向。
        val flowAlignment = if (alignment == Alignment.CenterEnd) Alignment.End else Alignment.Start
        FlowRow(
            modifier = Modifier
                .widthIn(max = maxMessageWidth)
                .wrapContentWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, flowAlignment),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            fileAttachments.forEach { file ->
                FileAttachmentPill(
                    fileName = file.fileName,
                    onClick = { openFileWithSystemViewer(context, file) },
                )
            }
        }
    }
}

/**
 * 触发系统 ACTION_VIEW 让用户用文本编辑器打开附件。
 *
 * 走 FileProvider 把 filesDir/chat-files/ 子目录通过 content:// URI 暴露,并在 Intent 上挂
 * [Intent.FLAG_GRANT_READ_URI_PERMISSION] 把临时读权限授给目标 app。
 *
 * 失败兜底:文件被外部清理 / 设备没装文本编辑器 / FileProvider 配置异常 → 弹 Toast,
 * 不抛异常导致气泡崩溃。
 */
private fun openFileWithSystemViewer(context: Context, attachment: FileAttachment) {
    val file = java.io.File(attachment.path)
    if (!file.exists()) {
        Toast.makeText(context, "附件文件已不存在", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = try {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    } catch (e: IllegalArgumentException) {
        // FileProvider 路径未在 provider_paths.xml 里授权时会抛 IAE
        Toast.makeText(context, "无法分享文件:${attachment.fileName}", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        // mimeType 空(历史落库异常 / 旧数据)兜底成 text/plain,避免 ACTION_VIEW 找不到 handler。
        // 当前白名单已保证不会落空 mime,这里只是防御性兜底。
        val effectiveMime = attachment.mimeType.ifBlank { "text/plain" }
        setDataAndType(uri, effectiveMime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // 走 createChooser 的两个理由:
    // 1. Android 11+ 包可见性限制:即使 Manifest 声明了 <queries>,某些 ROM 上 resolveActivity
    //    对带 data + type 的 Intent 仍会误报 null。直接 startActivity + ActivityNotFoundException
    //    兜底比"先 resolve 再发"更可靠。
    // 2. 用户体验:每次都让用户选用哪个 app 打开,不被默认 app 锁死(文本编辑器场景常需切换)。
    val chooser = Intent.createChooser(intent, "用以下应用打开 ${attachment.fileName}").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "未找到可打开 ${attachment.fileName} 的应用", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        // FileProvider 临时授权链路异常时会抛 SecurityException
        Toast.makeText(context, "无法打开:${attachment.fileName}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 用户消息已发送的图片缩略图。横向排列,与气泡同侧对齐(用户消息靠右)。
 * 点击缩略图打开全屏可缩放预览([ImagePreviewDialog])。
 */
@Composable
private fun MessageAttachmentImages(
    attachments: List<ImageAttachment>,    maxMessageWidth: Dp,
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
    isLastAssistantMessage: Boolean,
    showSwipeSwitcher: Boolean,
    swipeIndex: Int,
    swipeCount: Int,
    onSelectSwipe: (targetIndex: Int) -> Unit,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onGenerateNewSwipe: () -> Unit,
    onDelete: () -> Unit,
) {
    // 重生 / 生成新候选 / 删除三类操作的显隐与文案,对齐酒馆:
    // - user 消息:仅"重新生成回复"(末条 user => 追加新 assistant;中间 user => 删后续重生);
    // - 末条 assistant:同时给出"重新生成"(破坏式,删本条 + 重生)与"生成新候选"(swipe 追加);
    // - 中间 assistant:不暴露任何重生入口(酒馆中间消息没有 regenerate 按钮)。
    val showRegenerateRow = isUserMessage || isLastAssistantMessage
    val showGenerateNewSwipeRow = !isUserMessage && isLastAssistantMessage

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
            if (showSwipeSwitcher) {
                NutTavernGroupSection {
                    SwipeSwitcherRow(
                        swipeIndex = swipeIndex,
                        swipeCount = swipeCount,
                        onSelectSwipe = onSelectSwipe,
                    )
                }
            }
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
                if (showRegenerateRow) {
                    NutTavernGroupDivider()
                    NutTavernIconRow(
                        icon = Lucide.RefreshCcw,
                        title = if (isUserMessage) "重新生成回复" else "重新生成",
                        onClick = onRegenerate,
                    )
                }
                if (showGenerateNewSwipeRow) {
                    NutTavernGroupDivider()
                    NutTavernIconRow(
                        icon = Lucide.Plus,
                        title = "生成新候选",
                        onClick = onGenerateNewSwipe,
                    )
                }
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

/**
 * swipe 候选切换行:左箭头切上一条、居中显示"候选回复 N / M"、右箭头切下一条。
 * 到边界时对应箭头禁用。切换不关抽屉,方便连续切换;计数随消息重组实时更新。
 */
@Composable
private fun SwipeSwitcherRow(
    swipeIndex: Int,
    swipeCount: Int,
    onSelectSwipe: (targetIndex: Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(
            onClick = { onSelectSwipe(swipeIndex - 1) },
            enabled = swipeIndex > 0,
        ) {
            Icon(
                imageVector = Lucide.ChevronLeft,
                contentDescription = "上一条候选",
            )
        }
        Text(
            text = "候选回复 ${swipeIndex + 1} / $swipeCount",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(
            onClick = { onSelectSwipe(swipeIndex + 1) },
            enabled = swipeIndex < swipeCount - 1,
        ) {
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = "下一条候选",
            )
        }
    }
}

private val MESSAGE_ROW_HORIZONTAL_PADDING = 16.dp

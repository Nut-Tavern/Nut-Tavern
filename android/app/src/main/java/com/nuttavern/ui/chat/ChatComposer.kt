package com.nuttavern.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.FileUp
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.X
import com.nuttavern.data.model.EffortTier
import com.nuttavern.data.model.ImageAttachment
import com.nuttavern.data.model.Provider
import com.nuttavern.data.model.ThinkingLevel
import com.nuttavern.ui.components.NutTavernAlphaTokens
import com.nuttavern.ui.components.NutTavernComposerTokens
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow
import com.nuttavern.ui.components.NutTavernNumericField
import com.nuttavern.ui.components.NumericParser
import com.nuttavern.ui.components.NutTavernInputActionButton
import com.nuttavern.ui.components.NutTavernInputPrimaryButton
import com.nuttavern.ui.components.NutTavernInputToolbarButton
import com.nuttavern.ui.components.NutTavernSelectableRow
import com.nuttavern.ui.components.NutTavernSelectedCheckIcon
import com.nuttavern.ui.components.NutTavernShapeTokens
import com.nuttavern.ui.components.NutTavernSheetTitle
import com.nuttavern.ui.components.NutTavernUiTokens

@Composable
fun ChatComposer(
    draft: String,
    isReplying: Boolean,
    currentProvider: Provider?,
    currentModelName: String,
    currentThinkingLevel: ThinkingLevel,
    pendingAttachments: List<ImageAttachment>,
    imageInputSupported: Boolean,
    onAddImage: (ByteArray, String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onOpenModelPicker: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendDraft: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onSelectThinkingLevel: (ThinkingLevel) -> Unit,
    bottomPadding: Dp = NutTavernComposerTokens.RestingBottomPadding,
) {
    var draftEditorVisible by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf<ComposerSheet?>(null) }

    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "image/jpeg"
            // 读 URI 字节在主线程做一次性小 IO;大图上限由 ViewModel 校验拒绝。
            val bytes = runCatching {
                resolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) onAddImage(bytes, mime)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Composer 在 Scaffold.bottomBar 槽位:Activity adjustResize 不会自动让 bottomBar 跟键盘走,
            // 必须显式消费 IME / navigationBars。
            //
            // 关键:用 union 而不是链式两个 padding。链式 imePadding().navigationBarsPadding() 会**叠加**
            // 两个 type 的 bottom — 键盘抬起时 ime.bottom 已经覆盖 navigationBars 区域,再加一遍
            // navigationBarsPadding 就会在 Composer 与键盘顶部之间多出一段导航栏高度的空白。
            // union 取两个 inset 的最大值,正好对应"键盘存在时贴键盘 / 没有键盘时贴 nav bar"。
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = bottomPadding),
            shape = RoundedCornerShape(NutTavernShapeTokens.ComposerOuter),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (pendingAttachments.isNotEmpty()) {
                    PendingAttachmentStrip(
                        attachments = pendingAttachments,
                        onRemove = onRemoveImage,
                    )
                }

                ComposerTextInput(
                    value = draft,
                    onValueChange = onDraftChange,
                    onOpenDraftEditor = { draftEditorVisible = true },
                )

                ChatComposerToolbar(
                    currentProvider = currentProvider,
                    modelName = currentModelName,
                    isReplying = isReplying,
                    canSend = draft.isNotBlank() || pendingAttachments.isNotEmpty(),
                    onOpenAttachmentSheet = { activeSheet = ComposerSheet.Attachments },
                    onOpenModelPicker = onOpenModelPicker,
                    onOpenThinkingSheet = { activeSheet = ComposerSheet.Thinking },
                    onSend = { onSendDraft(draft) },
                    onStop = onStopGeneration,
                )
            }
        }
    }

    when (activeSheet) {
        ComposerSheet.Attachments -> AttachmentImportSheet(
            imageInputSupported = imageInputSupported,
            onPickImage = {
                activeSheet = null
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onDismiss = { activeSheet = null },
        )
        ComposerSheet.Thinking -> ThinkingLevelSheet(
            currentLevel = currentThinkingLevel,
            onSelectLevel = onSelectThinkingLevel,
            onDismiss = { activeSheet = null },
        )
        null -> Unit
    }

    var fullScreenDraft by remember(draftEditorVisible) { mutableStateOf(draft) }
    DraftEditFullScreen(
        visible = draftEditorVisible,
        content = fullScreenDraft,
        onContentChange = { input -> fullScreenDraft = input },
        onSave = {
            onDraftChange(fullScreenDraft)
            draftEditorVisible = false
        },
        onDismiss = { draftEditorVisible = false },
    )
}

@Composable
private fun ComposerTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    onOpenDraftEditor: () -> Unit,
) {
    val hasExplicitLineBreak = value.contains('\n')
    val rowVerticalAlignment = if (hasExplicitLineBreak) {
        Alignment.Top
    } else {
        Alignment.CenterVertically
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NutTavernComposerTokens.InputMinHeight, max = NutTavernComposerTokens.InputMaxHeight),
        shape = RoundedCornerShape(NutTavernShapeTokens.ComposerInner),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 14.dp,
                    end = 4.dp,
                    top = ComposerTextInputVerticalPadding,
                    bottom = ComposerTextInputVerticalPadding,
                ),
            verticalAlignment = rowVerticalAlignment,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val textStyle = MaterialTheme.typography.bodyLarge.merge(
                TextStyle(color = MaterialTheme.colorScheme.onSurface),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = ComposerSingleLineTextHeight, max = 92.dp),
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                minLines = 1,
                maxLines = 8,
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = ComposerSingleLineTextHeight),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isBlank()) {
                            Text(
                                text = "和 AI 聊天…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            NutTavernInputActionButton(
                icon = Lucide.Maximize2,
                contentDescription = "全屏编辑草稿",
                onClick = onOpenDraftEditor,
            )
        }
    }
}

@Composable
private fun ChatComposerToolbar(
    currentProvider: Provider?,
    modelName: String,
    isReplying: Boolean,
    canSend: Boolean,
    onOpenAttachmentSheet: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenThinkingSheet: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NutTavernUiTokens.InputToolbarHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        NutTavernInputToolbarButton(
            icon = Lucide.Paperclip,
            contentDescription = "附件导入",
            onClick = onOpenAttachmentSheet,
        )

        NutTavernInputToolbarButton(
            contentDescription = "切换模型",
            onClick = onOpenModelPicker,
        ) {
            ProviderIconBadge(
                provider = currentProvider,
                modelName = modelName,
                modifier = Modifier.size(26.dp),
            )
        }

        NutTavernInputToolbarButton(
            icon = Lucide.Brain,
            contentDescription = "设置思考量",
            onClick = onOpenThinkingSheet,
        )

        Spacer(modifier = Modifier.weight(1f))

        if (isReplying) {
            NutTavernInputPrimaryButton(
                icon = Lucide.CircleStop,
                contentDescription = "停止生成",
                destructive = true,
                onClick = onStop,
            )
        } else {
            NutTavernInputPrimaryButton(
                icon = Lucide.Send,
                contentDescription = "发送",
                enabled = canSend,
                onClick = onSend,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentImportSheet(
    imageInputSupported: Boolean,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NutTavernSheetTitle(
                title = "附件导入",
                description = if (imageInputSupported) {
                    "选择照片发送给支持图片输入的模型。拍照与文件上传暂未接入。"
                } else {
                    "当前模型不支持图片输入。拍照与文件上传暂未接入。"
                },
            )
            // 横向 3 等分 Tile,与 IconRow 形态不同,保留自写。
            // 替换条件:出现两次以上、或附件入口需要新增视觉变体时,沉淀到设计系统。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DisabledAttachmentTile("拍照", Lucide.Camera, Modifier.weight(1f))
                AttachmentTile(
                    title = "照片",
                    icon = Lucide.Image,
                    enabled = imageInputSupported,
                    onClick = onPickImage,
                    modifier = Modifier.weight(1f),
                )
                DisabledAttachmentTile("上传文件", Lucide.FileUp, Modifier.weight(1f))
            }
        }
    }
}

/** 可点击的附件 Tile;[enabled]=false 时灰显不可点(模型不支持图片输入)。 */
@Composable
private fun AttachmentTile(
    title: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!enabled) {
        DisabledAttachmentTile(title, icon, modifier, subtitle = "不支持")
        return
    }
    Surface(
        onClick = onClick,
        modifier = modifier.height(84.dp),
        shape = RoundedCornerShape(NutTavernShapeTokens.AttachmentTile),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun DisabledAttachmentTile(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String = "暂未支持",
) {
    Surface(
        modifier = modifier.height(84.dp),
        shape = RoundedCornerShape(NutTavernShapeTokens.AttachmentTile),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NutTavernAlphaTokens.Decorative),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** Composer 顶部待发图片预览条:横向缩略图 + 右上角删除按钮。 */
@Composable
private fun PendingAttachmentStrip(
    attachments: List<ImageAttachment>,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            Box(modifier = Modifier.size(64.dp)) {
                AsyncImage(
                    model = attachment.path,
                    contentDescription = "待发送图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(NutTavernShapeTokens.AttachmentTile)),
                )
                Surface(
                    onClick = { onRemove(attachment.id) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(20.dp),
                ) {
                    Icon(
                        Lucide.X,
                        contentDescription = "移除图片",
                        modifier = Modifier.padding(3.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingLevelSheet(
    currentLevel: ThinkingLevel,
    onSelectLevel: (ThinkingLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    val isCustom = currentLevel is ThinkingLevel.Budget
    val customTokens = (currentLevel as? ThinkingLevel.Budget)?.tokens ?: ThinkingDefaultCustomTokens
    // skipPartiallyExpanded + 固定 0.6f 高度,对齐项目其它 Picker sheet(AGENTS 组件规范 8)。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionLabelBottomSpacing),
        ) {
            NutTavernSheetTitle(
                title = "思考量",
                description = "控制模型的思考强度，会话级生效。自动表示不指定，由模型自行决定。",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                NutTavernGroupSection {
                    thinkingPresetOptions.forEach { option ->
                        val selected = !isCustom && currentLevel == option.level
                        NutTavernIconRow(
                            icon = Lucide.Brain,
                            title = option.label,
                            subtitle = option.description,
                            trailing = if (selected) {
                                { NutTavernSelectedCheckIcon() }
                            } else {
                                null
                            },
                            onClick = { onSelectLevel(option.level) },
                        )
                        NutTavernGroupDivider()
                    }
                    NutTavernIconRow(
                        icon = Lucide.Brain,
                        title = "自定义",
                        subtitle = "指定具体的思考 token 预算",
                        trailing = if (isCustom) {
                            { NutTavernSelectedCheckIcon() }
                        } else {
                            null
                        },
                        onClick = { onSelectLevel(ThinkingLevel.Budget(customTokens)) },
                    )
                    NutTavernGroupDivider()
                    // 输入框常驻,只在选中"自定义"时可编辑:高度恒定,sheet 不会因增删而跳动。
                    NutTavernNumericField(
                        label = "思考 token 预算",
                        value = customTokens,
                        onValueChange = { tokens ->
                            tokens?.let { onSelectLevel(ThinkingLevel.Budget(it)) }
                        },
                        parser = NumericParser.IntParser,
                        min = ThinkingLevel.MIN_BUDGET_TOKENS,
                        max = ThinkingLevel.MAX_BUDGET_TOKENS,
                        enabled = isCustom,
                        helperText = "范围 ${ThinkingLevel.MIN_BUDGET_TOKENS} ~ ${ThinkingLevel.MAX_BUDGET_TOKENS}",
                    )
                }
            }
        }
    }
}

/** 思考量固定档位选项(关闭 / 自动 / 五档努力度),"自定义"单独处理。 */
private data class ThinkingPresetOption(
    val label: String,
    val description: String,
    val level: ThinkingLevel,
)

private val thinkingPresetOptions = listOf(
    ThinkingPresetOption("关闭", "不进行思考", ThinkingLevel.Off),
    ThinkingPresetOption("自动", "由模型自行决定思考强度", ThinkingLevel.Auto),
    ThinkingPresetOption("极低", "极低思考强度", ThinkingLevel.Effort(EffortTier.MINIMAL)),
    ThinkingPresetOption("低", "低思考强度", ThinkingLevel.Effort(EffortTier.LOW)),
    ThinkingPresetOption("中", "中等思考强度", ThinkingLevel.Effort(EffortTier.MEDIUM)),
    ThinkingPresetOption("高", "高思考强度", ThinkingLevel.Effort(EffortTier.HIGH)),
    ThinkingPresetOption("极高", "最高思考强度", ThinkingLevel.Effort(EffortTier.MAX)),
)

private const val ThinkingDefaultCustomTokens = 4_096

private enum class ComposerSheet {
    Attachments,
    Thinking,
}

private val ComposerSingleLineTextHeight = 30.dp
private val ComposerTextInputVerticalPadding = 5.dp

package com.nuttavern.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.FileUp
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Server
import com.nuttavern.data.model.Provider
import com.nuttavern.data.model.ThinkingLevel
import com.nuttavern.ui.components.NutTavernAlphaTokens
import com.nuttavern.ui.components.NutTavernComposerTokens
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow
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
    draftThinkingLevel: ThinkingLevel,
    onOpenModelPicker: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendDraft: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onSelectThinkingLevel: (ThinkingLevel) -> Unit,
    bottomPadding: Dp = NutTavernComposerTokens.RestingBottomPadding,
) {
    var draftEditorVisible by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf<ComposerSheet?>(null) }

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
                ComposerTextInput(
                    value = draft,
                    onValueChange = onDraftChange,
                    onOpenDraftEditor = { draftEditorVisible = true },
                )

                ChatComposerToolbar(
                    currentProvider = currentProvider,
                    modelName = currentModelName,
                    isReplying = isReplying,
                    canSend = draft.isNotBlank(),
                    onOpenAttachmentSheet = { activeSheet = ComposerSheet.Attachments },
                    onOpenModelPicker = onOpenModelPicker,
                    onOpenThinkingSheet = { activeSheet = ComposerSheet.Thinking },
                    onOpenMcpSheet = { activeSheet = ComposerSheet.Mcp },
                    onSend = { onSendDraft(draft) },
                    onStop = onStopGeneration,
                )
            }
        }
    }

    when (activeSheet) {
        ComposerSheet.Attachments -> AttachmentImportSheet(
            onDismiss = { activeSheet = null },
        )
        ComposerSheet.Thinking -> ThinkingLevelSheet(
            currentLevel = draftThinkingLevel,
            onSelectLevel = onSelectThinkingLevel,
            onDismiss = { activeSheet = null },
        )
        ComposerSheet.Mcp -> McpInfoSheet(onDismiss = { activeSheet = null })
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
    onOpenMcpSheet: () -> Unit,
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

        NutTavernInputToolbarButton(
            icon = Lucide.Server,
            contentDescription = "第三方 MCP",
            onClick = onOpenMcpSheet,
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
                description = "拍照、照片和文件上传暂未接入；这里不会申请权限或打开系统选择器。",
            )
            // 横向 3 等分 Tile,与 IconRow 形态不同,保留自写。
            // 替换条件:出现两次以上、或附件入口需要新增视觉变体时,沉淀到设计系统。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DisabledAttachmentTile("拍照", Lucide.Camera, Modifier.weight(1f))
                DisabledAttachmentTile("照片", Lucide.Image, Modifier.weight(1f))
                DisabledAttachmentTile("上传文件", Lucide.FileUp, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DisabledAttachmentTile(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
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
            Text("暂未支持", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpInfoSheet(
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
            verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionLabelBottomSpacing),
        ) {
            NutTavernSheetTitle(
                title = "第三方 MCP",
                description = "这里指第三方 MCP 服务器能力，不包含工作区文件读写权限。本轮仅说明入口，不提供假开关。",
            )
            // 占位项,后续接入会话级 MCP 配置后改为真实跳转。当前点击不响应,
            // 描述文字明确说明"暂未接入",避免假按钮误导。
            NutTavernGroupSection {
                NutTavernIconRow(
                    icon = Lucide.Server,
                    title = "MCP 服务器",
                    subtitle = "暂未接入会话级第三方 MCP 配置",
                    onClick = {},
                )
                NutTavernGroupDivider()
                NutTavernIconRow(
                    icon = Lucide.Cpu,
                    title = "工具权限",
                    subtitle = "后续仅控制第三方 MCP 工具，不代表工作区读写权限",
                    onClick = {},
                )
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionLabelBottomSpacing),
        ) {
            NutTavernSheetTitle(
                title = "思考量",
                description = "本轮为草稿级 UI 状态，不会改变 API 请求参数。",
            )
            NutTavernGroupSection {
                ThinkingLevel.entries.forEachIndexed { index, level ->
                    val selected = currentLevel == level
                    NutTavernIconRow(
                        icon = Lucide.Brain,
                        title = level.label,
                        subtitle = thinkingLevelDescription(level),
                        trailing = if (selected) {
                            { NutTavernSelectedCheckIcon() }
                        } else {
                            null
                        },
                        onClick = { onSelectLevel(level) },
                    )
                    if (index < ThinkingLevel.entries.lastIndex) {
                        NutTavernGroupDivider()
                    }
                }
            }
        }
    }
}

private enum class ComposerSheet {
    Attachments,
    Thinking,
    Mcp,
}

private val ComposerSingleLineTextHeight = 30.dp
private val ComposerTextInputVerticalPadding = 5.dp

private fun thinkingLevelDescription(level: ThinkingLevel): String {
    return when (level) {
        ThinkingLevel.LOW -> "更轻量的界面选择，当前不改变请求"
        ThinkingLevel.MEDIUM -> "默认状态，当前不改变请求"
        ThinkingLevel.HIGH -> "高思考量占位，当前不改变请求"
    }
}

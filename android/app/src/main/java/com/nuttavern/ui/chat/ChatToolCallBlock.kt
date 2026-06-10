package com.nuttavern.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wrench
import com.nuttavern.data.model.MessagePart
import com.nuttavern.ui.chat.markdown.ChatMarkdown
import com.nuttavern.ui.components.NutTavernSheetTitle

/**
 * 一组连续工具调用的合并折叠卡。视觉与 [ChatReasoningBlock] 折叠态对齐:
 * surfaceContainerLow 圆角卡片 + Lucide 扳手图标 + 标题 + chevron。
 *
 * 交互按工具数量分两种:
 * - 单个工具:卡片直接点击弹详情抽屉(无展开态,chevron 用 Right 表示"进下一级")。
 * - 多个工具:默认折叠,标题显示"调用了 N 个工具",点卡片展开 / 收起内嵌工具行列表(chevron 用 Down / Up);
 *   每个工具行再点击弹各自的详情抽屉。
 *
 * 不复用 [ChatReasoningBlock]:思考块展开的是单段文本,工具组展开的是可点击的工具行列表 + 二级抽屉,
 * 内容结构不同。两者共用同一套视觉 token 保持协调。
 *
 * 状态可视化:落库后的工具调用都是已完成态。[MessagePart.ToolCall.denied] 为真时对应工具标红。
 */
@Composable
internal fun ChatToolCallGroupBlock(
    toolCalls: List<MessagePart.ToolCall>,
    toolDisplayName: (String) -> String,
    modifier: Modifier = Modifier,
) {
    if (toolCalls.isEmpty()) return
    if (toolCalls.size == 1) {
        SingleToolCallCard(
            toolCall = toolCalls.first(),
            displayName = toolDisplayName(toolCalls.first().toolName),
            modifier = modifier,
        )
        return
    }
    MergedToolCallsCard(
        toolCalls = toolCalls,
        toolDisplayName = toolDisplayName,
        modifier = modifier,
    )
}

/** 单个工具调用卡:点击直接弹详情抽屉,无展开态。 */
@Composable
private fun SingleToolCallCard(
    toolCall: MessagePart.ToolCall,
    displayName: String,
    modifier: Modifier = Modifier,
) {
    var detailVisible by remember { mutableStateOf(false) }

    val accentColor = toolAccentColor(toolCall.denied)
    val title = if (toolCall.denied) "$displayName（已拒绝）" else displayName

    Surface(
        modifier = modifier
            .wrapContentWidth()
            .clip(MaterialTheme.shapes.large)
            .semantics { role = Role.Button }
            .clickable { detailVisible = true },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Lucide.Wrench,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = accentColor,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = accentColor,
            )
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = "查看工具调用详情",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (detailVisible) {
        ToolCallDetailSheet(
            toolCall = toolCall,
            displayName = displayName,
            onDismiss = { detailVisible = false },
        )
    }
}

/** 多个工具调用合并卡:默认折叠"调用了 N 个工具",点头部展开内嵌工具行列表。 */
@Composable
private fun MergedToolCallsCard(
    toolCalls: List<MessagePart.ToolCall>,
    toolDisplayName: (String) -> String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var detailToolCall by remember { mutableStateOf<MessagePart.ToolCall?>(null) }

    val hasDenied = toolCalls.any { it.denied }
    val headerColor = toolAccentColor(hasDenied)
    val widthModifier = if (expanded) Modifier.fillMaxWidth() else Modifier.wrapContentWidth()

    Surface(
        modifier = modifier
            .then(widthModifier)
            .clip(MaterialTheme.shapes.large)
            .semantics {
                role = Role.Button
                stateDescription = if (expanded) "已展开" else "已收起"
            }
            .clickable { expanded = !expanded },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = if (expanded) Modifier.fillMaxWidth() else Modifier.wrapContentWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Lucide.Wrench,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = headerColor,
                )
                Text(
                    text = "调用了 ${toolCalls.size} 个工具",
                    style = MaterialTheme.typography.titleSmall,
                    color = headerColor,
                    modifier = if (expanded) Modifier.weight(1f) else Modifier,
                )
                Icon(
                    imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = if (expanded) "收起工具列表" else "展开工具列表",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(durationMillis = 180)) + fadeIn(animationSpec = tween(durationMillis = 120)),
                exit = shrinkVertically(animationSpec = tween(durationMillis = 160)) + fadeOut(animationSpec = tween(durationMillis = 100)),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    toolCalls.forEach { toolCall ->
                        MergedToolCallRow(
                            toolCall = toolCall,
                            displayName = toolDisplayName(toolCall.toolName),
                            onClick = { detailToolCall = toolCall },
                        )
                    }
                }
            }
        }
    }

    detailToolCall?.let { toolCall ->
        ToolCallDetailSheet(
            toolCall = toolCall,
            displayName = toolDisplayName(toolCall.toolName),
            onDismiss = { detailToolCall = null },
        )
    }
}

/** 合并卡展开后的单个工具行:点击弹该工具的详情抽屉。 */
@Composable
private fun MergedToolCallRow(
    toolCall: MessagePart.ToolCall,
    displayName: String,
    onClick: () -> Unit,
) {
    val accentColor = toolAccentColor(toolCall.denied)
    val title = if (toolCall.denied) "$displayName（已拒绝）" else displayName

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Lucide.Wrench,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = accentColor,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = accentColor,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Lucide.ChevronRight,
            contentDescription = "查看工具调用详情",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun toolAccentColor(denied: Boolean) = if (denied) {
    MaterialTheme.colorScheme.error
} else {
    MaterialTheme.colorScheme.secondary
}

/**
 * 工具调用详情抽屉:调用参数 + 返回结果各一个 JSON 代码块。
 * JSON 块复用 markdown 的 ```json 围栏渲染([ChatMarkdown]),自带头部栏 + 复制按钮 + 横向滚动 + 等宽字体。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolCallDetailSheet(
    toolCall: MessagePart.ToolCall,
    displayName: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            NutTavernSheetTitle(
                title = "工具调用详情",
                description = displayName,
            )
            Spacer(modifier = Modifier.height(8.dp))

            ToolCallJsonSection(label = "调用参数", json = toolCall.arguments)
            Spacer(modifier = Modifier.height(16.dp))
            ToolCallJsonSection(
                label = if (toolCall.denied) "结果（已拒绝）" else "返回结果",
                json = toolCall.result,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 一段带标题的 JSON 代码块。空 JSON 退化为 `{}`,保证代码块始终可见、不留空白。 */
@Composable
private fun ToolCallJsonSection(
    label: String,
    json: String,
) {
    val normalizedJson = json.ifBlank { "{}" }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        ChatMarkdown(
            content = "```json\n$normalizedJson\n```",
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

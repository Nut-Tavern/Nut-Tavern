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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import com.nuttavern.data.model.GeneratedContentSanitizer

private enum class ReasoningDisplayState {
    Collapsed,
    Expanded,
}

/**
 * 思考链可点击展开的卡片。视觉上是单行容器,概念上不属于"分组"。
 *
 * 不复用 [com.nuttavern.ui.components.NutTavernGroupCard] / `NutTavernIconRow` 的原因:
 * 1. 需要展开 / 收起的内嵌内容,IconRow 不提供这种容器形态。
 * 2. 头部行需要展示动态时长 + 状态文案 + chevron 三段,且整块要可点击。
 *
 * 视觉规则:
 * - 默认折叠,流式期间也保持折叠(用户主动展开才显示思考内容);
 * - 折叠态用 [Modifier.wrapContentWidth] 让卡片只包内容,不再撑满整行,与气泡不并排;
 * - 展开态恢复 [Modifier.fillMaxWidth],思考内容能完整阅读。
 *
 * 替换条件:如果未来 IconRow 增加 `expandedContent` 槽,或者出现第二个"可展开容器"
 * 场景,把这里抽到设计系统。
 */
@Composable
internal fun ChatReasoningBlock(
    reasoningContent: String,
    reasoningDurationMillis: Long,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val visibleReasoningContent = GeneratedContentSanitizer.sanitizeGeneratedDisplayText(reasoningContent)
    if (visibleReasoningContent.isBlank()) return

    // 默认折叠,流式期间也不展开。用户点击后保持其手动状态。
    var displayState by remember { mutableStateOf(ReasoningDisplayState.Collapsed) }

    val containerShape = MaterialTheme.shapes.large
    val durationLabel = formatReasoningDuration(reasoningDurationMillis, isStreaming)
    val isExpanded = displayState == ReasoningDisplayState.Expanded

    // 折叠态:卡片宽度跟随内容(图标 + 时长 + 状态 + chevron),不撑满;
    // 展开态:必须撑满,内嵌的思考链文本要换行阅读。
    val widthModifier = if (isExpanded) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.wrapContentWidth()
    }

    Surface(
        modifier = modifier
            .then(widthModifier)
            .clip(containerShape)
            .semantics {
                role = Role.Button
                stateDescription = when (displayState) {
                    ReasoningDisplayState.Collapsed -> "已收起"
                    ReasoningDisplayState.Expanded -> "已展开"
                }
            }
            .clickable {
                displayState = when (displayState) {
                    ReasoningDisplayState.Collapsed -> ReasoningDisplayState.Expanded
                    ReasoningDisplayState.Expanded -> ReasoningDisplayState.Collapsed
                }
            },
        shape = containerShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = if (isExpanded) Modifier.fillMaxWidth() else Modifier.wrapContentWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Lucide.Sparkles,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                if (durationLabel.isNotBlank()) {
                    Text(
                        text = durationLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Text(
                    text = if (isStreaming) "思考中" else "思考",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    // 展开态用 weight 把 chevron 推到右边;折叠态去掉 weight 让整行紧凑。
                    modifier = if (isExpanded) Modifier.weight(1f) else Modifier,
                )
                Icon(
                    imageVector = if (isExpanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = if (isExpanded) "收起思考" else "展开思考",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(durationMillis = 180)) + fadeIn(animationSpec = tween(durationMillis = 120)),
                exit = shrinkVertically(animationSpec = tween(durationMillis = 160)) + fadeOut(animationSpec = tween(durationMillis = 100)),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ChatRenderedText(
                        content = visibleReasoningContent,
                        textStyleRole = ChatRenderedTextRole.Reasoning,
                        selectable = false,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

internal fun formatReasoningDuration(durationMillis: Long, isStreaming: Boolean): String {
    if (durationMillis <= 0L) {
        return if (isStreaming) "计时中" else ""
    }

    // 流式期间走整秒,避免 100ms 心跳让数字抖动;完成后保留一位小数,便于复盘。
    return if (isStreaming) {
        formatStreamingDuration(durationMillis)
    } else {
        formatFinalDuration(durationMillis)
    }
}

private fun formatStreamingDuration(durationMillis: Long): String {
    val totalSeconds = maxOf(1L, durationMillis / 1000L)
    if (totalSeconds < 60L) return "${totalSeconds}秒"

    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "${minutes}分${seconds}秒"
}

private fun formatFinalDuration(durationMillis: Long): String {
    val totalTenths = maxOf(1L, (durationMillis + 99L) / 100L)
    if (totalTenths < 600L) {
        return "${totalTenths / 10L}.${totalTenths % 10L}秒"
    }

    val minutes = totalTenths / 600L
    val secondsTenths = totalTenths % 600L
    return "${minutes}分${secondsTenths / 10L}.${secondsTenths % 10L}秒"
}

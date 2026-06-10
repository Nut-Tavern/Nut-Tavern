package com.nuttavern.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wrench
import com.nuttavern.ui.components.NutTavernEntityCard
import com.nuttavern.ui.components.NutTavernEntityStatusPill
import com.nuttavern.ui.components.NutTavernEntitySwitch

/**
 * 聊天页右侧工作台 - 侧栏专用的工具卡片。
 *
 * 不使用万能卡片，而是基于 [NutTavernEntityCard] 为“侧栏内狭小空间”做了尾部紧凑适配。
 * 包含：工具图标、名称、说明、两个状态 Pill（会话开关、需确认标识）以及启用开关。
 */
@Composable
internal fun SidebarToolCard(
    title: String,
    subtitle: String,
    enabledForConversation: Boolean,
    approvalRequired: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    NutTavernEntityCard(
        title = title,
        subtitle = subtitle,
        leading = {
            Icon(
                imageVector = Lucide.Wrench,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = null,
        modifier = modifier,
        trailing = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NutTavernEntityStatusPill(
                    label = if (enabledForConversation) "本会话启用" else "本会话关闭",
                    container = if (enabledForConversation) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    content = if (enabledForConversation) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                NutTavernEntityStatusPill(
                    label = if (approvalRequired) "需确认" else "无需确认",
                    container = if (approvalRequired) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    content = if (approvalRequired) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                NutTavernEntitySwitch(
                    checked = enabledForConversation,
                    onCheckedChange = onEnabledChange,
                )
            }
        },
    )
}

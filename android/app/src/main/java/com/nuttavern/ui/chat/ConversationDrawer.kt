package com.nuttavern.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.BookUser
import com.composables.icons.lucide.ChartColumn
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquareText
import com.composables.icons.lucide.PanelLeftClose
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Settings
import com.nuttavern.data.model.ConversationSummary

@Composable
internal fun ConversationDrawer(
    conversations: List<ConversationSummary>,
    currentConversationId: String,
    currentCharacter: com.nuttavern.data.character.Character?,
    onSelectConversation: (String) -> Unit,
    onLongPressConversation: (ConversationSummary) -> Unit,
    onNewConversation: () -> Unit,
    onOpenCharacterPicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChatDrawerSurface(modifier = modifier) {
        ChatDrawerHeader(
            title = "对话",
            closeIcon = Lucide.PanelLeftClose,
            closeContentDescription = "关闭对话侧栏",
            onDismiss = onDismiss,
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Lucide.Settings,
                        contentDescription = "设置",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenStats) {
                    Icon(
                        imageVector = Lucide.ChartColumn,
                        contentDescription = "统计",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )

        Column(modifier = Modifier.fillMaxSize()) {
            NewConversationCta(onClick = onNewConversation)

            Box(modifier = Modifier.weight(1f)) {
                if (conversations.isEmpty()) {
                    ChatDrawerEmptyText(text = "暂无会话")
                } else {
                    ConversationList(
                        conversations = conversations,
                        currentConversationId = currentConversationId,
                        onSelectConversation = onSelectConversation,
                        onLongPressConversation = onLongPressConversation,
                    )
                }
            }

            HorizontalDivider()
            CharacterCardEntry(
                currentCharacter = currentCharacter,
                onClick = onOpenCharacterPicker,
            )
        }
    }
}

/**
 * 抽屉顶部"新建对话"主操作。
 *
 * 配色与未选中的会话条目一致(`surface` + `onSurface`),通过 `+` 图标和文字
 * 表明这是动作而非记录。形态用 `shapes.large` 圆角 + 16dp 横向外边距与列表
 * 条目区分,但视觉重量与列表一致,避免抢戏。
 *
 * 替换条件:设计系统沉淀出"抽屉级 CTA"组件后替换。
 */
@Composable
private fun NewConversationCta(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(MaterialTheme.shapes.large),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Lucide.Plus,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "新建对话",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ConversationList(
    conversations: List<ConversationSummary>,
    currentConversationId: String,
    onSelectConversation: (String) -> Unit,
    onLongPressConversation: (ConversationSummary) -> Unit,
) {
    val conversationsByGroup = remember(conversations) {
        conversations.groupBy { conversation ->
            conversation.groupLabel.ifBlank { "会话" }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        conversationsByGroup.forEach { (groupLabel, groupConversations) ->
            item(key = "group-$groupLabel") {
                ChatDrawerSectionTitle(text = groupLabel)
            }
            items(groupConversations, key = { conversation -> conversation.id }) { conversation ->
                ConversationDrawerRow(
                    conversation = conversation,
                    selected = conversation.id == currentConversationId,
                    onClick = { onSelectConversation(conversation.id) },
                    onLongPress = { onLongPressConversation(conversation) },
                )
            }
        }
    }
}

/**
 * 会话条目自写而非复用 M3 [androidx.compose.material3.NavigationDrawerItem]:
 * NavigationDrawerItem 内部用 `selectable` 包了短按事件,会优先消费 down,导致
 * 外层挂的长按手势永远拿不到事件。这里直接用 [Surface] + [combinedClickable]
 * 同时承载短按选择和长按弹菜单,选中态用背景色 + 主色字体区分。
 *
 * 替换条件:M3 出现支持 long-click 的 drawer item,或者我们的设计系统沉淀出
 * 同等组件后再替换。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationDrawerRow(
    conversation: ConversationSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val titleColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subtitleColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            ),
        color = containerColor,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Lucide.MessageSquareText,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = titleColor,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = conversation.title.ifBlank { "未命名会话" },
                    style = MaterialTheme.typography.labelLarge,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = conversation.lastMessageTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 抽屉底部"当前角色"入口:36dp 圆角图标方块 leading + 标题副标题 + chevron 大行。
 *
 * 自写而非走 [com.nuttavern.ui.components.NutTavernIconRow]:
 * 1. IconRow 默认是分组卡片内的行,这里是抽屉底部独立栏,需要 surface 顶到底边、
 *    使用更醒目的 leading 头像槽位(后续接入头像图片后会替换 Lucide 图标为 AsyncImage)。
 * 2. 与设置页"用户身份"行的"动态文案"模式一致 — title / 副标题实时反映当前选中角色。
 *
 * [currentCharacter] 为 null 时显示"未选择角色 / 点击切换角色卡片"占位文案。
 */
@Composable
private fun CharacterCardEntry(
    currentCharacter: com.nuttavern.data.character.Character?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = MaterialTheme.shapes.medium,
                color = if (currentCharacter != null) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Lucide.BookUser,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (currentCharacter != null) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = currentCharacter?.name?.ifBlank { "未命名角色" } ?: "未选择角色",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = characterEntrySubtitle(currentCharacter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun characterEntrySubtitle(character: com.nuttavern.data.character.Character?): String {
    if (character == null) return "点击切换角色卡片"
    val description = character.description.replace('\n', ' ').trim()
    return when {
        description.isNotBlank() -> description
        character.creator.isNotBlank() -> "by ${character.creator}"
        else -> "点击切换角色卡片"
    }
}




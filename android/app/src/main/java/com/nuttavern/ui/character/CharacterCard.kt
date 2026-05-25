package com.nuttavern.ui.character

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.BookUser
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.nuttavern.data.character.Character

/**
 * 角色卡片(长卡片形态)。
 *
 * 列表页、Picker Sheet 共用同一个卡片视觉。区别只在主区域点击行为和右侧动作:
 * - 列表页:主区域点击 → 进编辑;右侧 [editButton] / [dragHandle] 全可见。
 * - Picker Sheet:主区域点击 → 切换角色并立即关闭抽屉;只显示 [editButton],无拖把手。
 *
 * 调用方负责:
 * - 通过 [onClick] 处理主区域点击;
 * - 用 [isCurrent] 控制是否显示 "使用中" 胶囊;
 * - 用 [editButton] / [dragHandle] 提供具体的尾部 Composable;不传则不渲染对应槽位。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CharacterCard(
    character: Character,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    isCurrent: Boolean = false,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (elevated) 6.dp else 0.dp,
        shadowElevation = if (elevated) 6.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CharacterAvatarPlaceholder(modifier = Modifier.size(32.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = character.name.ifBlank { "未命名角色" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = characterSubtitleDisplay(character),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isCurrent) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(percent = 50),
                ) {
                    Text(
                        text = "使用中",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            dragHandle?.invoke()
        }
    }
}

/**
 * 头像占位。当前阶段头像选择尚未接入,先用 secondaryContainer 圆形底 + BookUser 图标占位
 * (与用户身份的 tertiaryContainer + UserRound 在视觉上区分)。
 */
@Composable
internal fun CharacterAvatarPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Lucide.BookUser,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp),
            )
        }
    }
}

/**
 * 列表 / picker 里编辑键的标准外形。规则与 [com.nuttavern.ui.persona.UserPersonaEditIconButton] 一致:
 * 32dp Surface 触区,避免 M3 IconButton 48dp 撑高整行。
 */
@Composable
internal fun CharacterEditIconButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Lucide.Pencil,
                contentDescription = "编辑角色",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

internal fun characterSubtitleDisplay(character: Character): String {
    val description = character.description.replace('\n', ' ').trim()
    return when {
        description.isNotBlank() -> description
        character.creator.isNotBlank() -> "by ${character.creator}"
        else -> "暂无描述"
    }
}

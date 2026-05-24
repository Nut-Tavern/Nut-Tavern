package com.nuttavern.ui.persona

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
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.UserRound
import com.nuttavern.data.persona.UserPersona

/**
 * 用户身份列表卡片。
 *
 * 列表页和右侧栏抽屉共用同一个卡片视觉,区别只在主区域点击行为和右侧动作:
 * - 列表页:主区域点击 → 设默认弹窗;右侧 [defaultBadge] / [editButton] / [dragHandle] 全可见。
 * - 抽屉:主区域点击 → 切换会话身份;只显示 [defaultBadge] + [editButton],无拖把手。
 *
 * 调用方负责:
 * - 通过 [onClick] 处理主区域点击;
 * - 用 [defaultBadge] / [currentBadge] 控制是否显示 "默认" / "使用中" 胶囊;
 *   同时 true 时显示 "使用中"(语义上更具体);
 * - 用 [editButton] / [dragHandle] 提供具体的尾部 Composable;
 * - 用 [enabled] = false 时主区域不响应点击(用于伪卡 "无" 已是默认 / 已使用中的场景)。
 */
@Composable
internal fun UserPersonaCard(
    persona: UserPersona,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    elevated: Boolean = false,
    isDefault: Boolean = false,
    isCurrent: Boolean = false,
    editButton: (@Composable () -> Unit)? = null,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (elevated) 6.dp else 0.dp,
        shadowElevation = if (elevated) 6.dp else 0.dp,
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PersonaAvatarPlaceholder(persona = persona, modifier = Modifier.size(32.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = personaPrimaryDisplay(persona),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = personaSubtitleDisplay(persona),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                isCurrent -> StatusPill(
                    label = "使用中",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                isDefault -> StatusPill(
                    label = "默认",
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            editButton?.invoke()
            dragHandle?.invoke()
        }
    }
}

/**
 * 列表 / 抽屉里编辑键的标准外形。
 *
 * **不能用 M3 [androidx.compose.material3.IconButton]**:它的最小触摸目标是 48dp,
 * 会撑高整行超出卡片头像 32dp 的对齐基准,导致含编辑键的卡片与不含编辑键的卡片("无"伪卡)
 * 高度不一致。这里改用 Surface + 32dp 触区,与头像等高。
 */
@Composable
internal fun UserPersonaEditIconButton(onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Lucide.Pencil,
                contentDescription = "编辑用户身份",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 列表里拖把手的标准外形。仅在列表页使用,抽屉里不允许拖动排序。
 *
 * 调用方负责把 `Modifier.draggableHandle(...)` 接到这里返回的 Modifier 上。
 */
@Composable
internal fun UserPersonaDragHandle(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Lucide.GripVertical,
        contentDescription = "拖动排序",
        modifier = modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 头像占位。
 *
 * 当前阶段头像选择尚未接入(相册 + 落地到私有目录是后端阶段的事),先用一个圆形
 * `tertiaryContainer` 背景 + 用户图标占位;伪卡"无"用一个空圆。
 *
 * 后端阶段把这个组件改成"有 [UserPersona.avatarPath] 走 Coil 加载,无走当前占位"
 * 即可,调用方不变。SettingsDrawer 的"用户身份"行也复用这一份规则。
 */
@Composable
internal fun PersonaAvatarPlaceholder(persona: UserPersona, modifier: Modifier = Modifier) {
    if (persona.isNonePersona) {
        Surface(
            modifier = modifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) { Box {} }
        return
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Icon 按容器大小自适应,padding 取容器边长的 1/4 作为视觉留白,
            // 在 24dp(IconRow leading) / 40dp(列表卡片) / 96dp(编辑页头像)三种尺寸下都对齐。
            Icon(
                imageVector = Lucide.UserRound,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(NutTavernAvatarTokens.IconInsetFraction),
            )
        }
    }
}

private object NutTavernAvatarTokens {
    /** 头像 Icon 相对外圈的内边距(经验值,在 24/40/96dp 下视觉一致)。 */
    val IconInsetFraction = 5.dp
}

@Composable
private fun StatusPill(label: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(percent = 50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

internal fun personaPrimaryDisplay(persona: UserPersona): String {
    if (persona.isNonePersona) return persona.title.ifBlank { "无" }
    return persona.title.ifBlank { persona.name.ifBlank { "未命名身份" } }
}

internal fun personaSubtitleDisplay(persona: UserPersona): String {
    if (persona.isNonePersona) return "不拼接用户身份提示词"
    val description = persona.description.replace('\n', ' ').trim()
    return when {
        description.isNotBlank() -> description
        persona.name.isNotBlank() -> persona.name
        else -> "暂无身份描述"
    }
}

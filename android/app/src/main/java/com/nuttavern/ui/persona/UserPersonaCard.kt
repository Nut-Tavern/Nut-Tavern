package com.nuttavern.ui.persona

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.UserRound
import com.nuttavern.data.persona.UserPersona
import com.nuttavern.ui.components.NutTavernEntityCard
import com.nuttavern.ui.components.NutTavernEntityStatusPill
import java.io.File

/**
 * 用户身份列表卡片。走公共组件 [NutTavernEntityCard],视觉规格与其他实体列表统一
 * (横向间距 12dp、shapes.large、surfaceContainerHigh)。
 *
 * 列表页和右侧栏抽屉共用同一个卡片视觉,区别只在主区域点击行为和右侧动作:
 * - 列表页:主区域点击 → 设默认弹窗;右侧 [isDefault] / [dragHandle] 可见。
 * - 抽屉:主区域点击 → 切换会话身份;显示 [isDefault] / [isCurrent] 胶囊,无拖把手。
 *
 * 调用方负责:
 * - 通过 [onClick] 处理主区域点击;
 * - 用 [isDefault] / [isCurrent] 控制是否显示"默认" / "使用中"胶囊;
 *   同时 true 时显示"使用中"(语义上更具体);
 * - 用 [dragHandle] 提供拖把手;
 * - 用 [enabled] = false 时主区域不响应点击(用于伪卡"无"已是默认 / 已使用中的场景)。
 */
@Composable
internal fun UserPersonaCard(
    persona: UserPersona,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    elevated: Boolean = false,
    isDefault: Boolean = false,
    isCurrent: Boolean = false,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    val hasPill = isCurrent || isDefault
    NutTavernEntityCard(
        title = personaPrimaryDisplay(persona),
        titleFallback = "未命名身份",
        subtitle = personaSubtitleDisplay(persona),
        modifier = modifier,
        elevated = elevated,
        onClick = if (enabled) onClick else null,
        onLongClick = onLongClick,
        leading = { PersonaAvatarPlaceholder(persona = persona, modifier = Modifier.size(32.dp)) },
        trailing = if (hasPill || dragHandle != null) {
            {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        isCurrent -> NutTavernEntityStatusPill(
                            label = "使用中",
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        isDefault -> NutTavernEntityStatusPill(
                            label = "默认",
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    dragHandle?.invoke()
                }
            }
        } else null,
    )
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
 * 头像占位 / 渲染。
 *
 * - 伪卡"无":空圆;
 * - 真身份有 [UserPersona.avatarPath]:Coil 渲染本地图片文件;
 * - 真身份无头像:`tertiaryContainer` 圆 + 用户图标占位。
 *
 * SettingsDrawer 的"用户身份"行也复用这一份规则。
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
    val avatarPath = persona.avatarPath
    if (!avatarPath.isNullOrBlank()) {
        val context = LocalContext.current
        val imageRequest = remember(context, avatarPath) {
            ImageRequest.Builder(context).data(File(avatarPath)).build()
        }
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(CircleShape),
        )
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


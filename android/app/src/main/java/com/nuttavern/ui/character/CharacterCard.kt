package com.nuttavern.ui.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import com.composables.icons.lucide.BookUser
import com.composables.icons.lucide.Lucide
import com.nuttavern.data.character.Character
import com.nuttavern.ui.components.NutTavernEntityCard
import com.nuttavern.ui.components.NutTavernEntityStatusPill
import java.io.File

/**
 * 角色卡片(长卡片形态)。走公共组件 [NutTavernEntityCard],视觉规格与 Preset / 正则 / 世界书
 * 列表统一(横向间距 12dp、shapes.large、surfaceContainerHigh)。
 *
 * 列表页、Picker Sheet 共用同一个卡片视觉。区别只在主区域点击行为和右侧动作:
 * - 列表页:主区域点击 → 进编辑;右侧 [dragHandle] 可见。
 * - Picker Sheet:主区域点击 → 切换角色并立即关闭抽屉;用 [isCurrent] 显示"使用中"胶囊。
 *
 * 调用方负责:
 * - 通过 [onClick] 处理主区域点击;
 * - 用 [isCurrent] 控制是否显示"使用中"胶囊;
 * - 用 [dragHandle] 提供拖把手;不传则不渲染。
 */
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
    NutTavernEntityCard(
        title = character.name,
        titleFallback = "未命名角色",
        subtitle = characterSubtitleDisplay(character),
        modifier = modifier,
        elevated = elevated,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = { CharacterAvatarPlaceholder(avatarPath = character.avatarPath, modifier = Modifier.size(32.dp)) },
        trailing = if (isCurrent || dragHandle != null) {
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (isCurrent) {
                        NutTavernEntityStatusPill(
                            label = "使用中",
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    dragHandle?.invoke()
                }
            }
        } else null,
    )
}

/**
 * 头像占位 / 渲染。
 *
 * - 有 [avatarPath](角色卡导入产出 / 用户手动选图)→ Coil 渲染本地图片文件;
 * - 无 → secondaryContainer 圆形底 + BookUser 图标占位(与用户身份的 tertiaryContainer +
 *   UserRound 在视觉上区分)。
 */
@Composable
internal fun CharacterAvatarPlaceholder(
    avatarPath: String?,
    modifier: Modifier = Modifier,
) {
    if (avatarPath.isNullOrBlank()) {
        CharacterAvatarFallback(modifier)
        return
    }
    val context = LocalContext.current
    val imageRequest = remember(context, avatarPath) {
        ImageRequest.Builder(context)
            .data(File(avatarPath))
            .build()
    }
    AsyncImage(
        model = imageRequest,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(CircleShape),
    )
}

@Composable
private fun CharacterAvatarFallback(modifier: Modifier = Modifier) {
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

internal fun characterSubtitleDisplay(character: Character): String {
    val description = character.description.replace('\n', ' ').trim()
    return when {
        description.isNotBlank() -> description
        character.creator.isNotBlank() -> "by ${character.creator}"
        else -> "暂无描述"
    }
}


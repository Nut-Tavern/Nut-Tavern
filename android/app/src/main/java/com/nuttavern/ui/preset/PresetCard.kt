package com.nuttavern.ui.preset

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SlidersHorizontal
import com.nuttavern.data.preset.Preset
import com.nuttavern.ui.components.NutTavernEntityCard
import com.nuttavern.ui.components.NutTavernEntityDragHandle
import com.nuttavern.ui.components.NutTavernEntityEditIconButton
import com.nuttavern.ui.components.NutTavernEntityStatusPill

/**
 * 预设宽条卡片(列表页 / 抽屉 picker 共用)。
 *
 * 视觉规则委托给 [NutTavernEntityCard];本文件只组装预设特有的 trailing(状态胶囊 → 铅笔 → 拖把手)
 * 与副标(描述 / "启用 N / 共 M · 温度 X.XX")。
 *
 * 不保留头像槽:预设没有"图像"概念,真实头像图片不存在,占位图标会让标题贴边的视觉变冗余,与
 * CharacterCard / UserPersonaCard 那种"有真实头像"的语义不同,不为视觉对齐而强行加占位。
 */
@Composable
internal fun PresetCard(
    preset: Preset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    isDefault: Boolean = false,
    isCurrent: Boolean = false,
    editButton: (@Composable () -> Unit)? = null,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    NutTavernEntityCard(
        title = preset.name,
        titleFallback = "未命名预设",
        subtitle = presetSubtitleDisplay(preset),
        elevated = elevated,
        onClick = onClick,
        modifier = modifier,
        trailing = {
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
            editButton?.invoke()
            dragHandle?.invoke()
        },
    )
}

/**
 * 预设铅笔编辑按钮。包了一层是为了承载"编辑预设"的 contentDescription 语义,
 * 下层视觉规格走 [NutTavernEntityEditIconButton] 公共组件。
 */
@Composable
internal fun PresetEditIconButton(onClick: () -> Unit) {
    NutTavernEntityEditIconButton(
        onClick = onClick,
        contentDescription = "编辑预设",
    )
}

/**
 * 预设拖动把手。下层走 [NutTavernEntityDragHandle] 公共组件。
 */
@Composable
internal fun PresetDragHandle(modifier: Modifier = Modifier) {
    NutTavernEntityDragHandle(modifier = modifier)
}

/**
 * 抽屉"当前预设"行的圆形占位图标(对齐角色 / 用户身份头像槽视觉)。卡片本身不用,只在抽屉的
 * IconRow leading 槽里使用。
 */
@Composable
internal fun PresetAvatarPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Lucide.SlidersHorizontal,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp),
            )
        }
    }
}

internal fun presetSubtitleDisplay(preset: Preset): String {
    val description = preset.description.replace('\n', ' ').trim()
    return when {
        description.isNotBlank() -> description
        else -> {
            // 启用计数:取全局排序里 enabled 项的交集(对齐 PresetEditScreen 的统计口径)。
            // 没有全局排序时回退到"未列出 = 默认启用",此时启用 = 总数。
            val total = preset.prompts.size
            val globalOrder = preset.promptOrder
                .firstOrNull { it.characterId == com.nuttavern.data.preset.PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
            val enabled = if (globalOrder == null) total
            else preset.prompts.count { entry ->
                globalOrder.order.firstOrNull { it.identifier == entry.identifier }?.enabled ?: true
            }
            "启用 $enabled / 共 $total · 温度 ${"%.2f".format(preset.temperature)}"
        }
    }
}

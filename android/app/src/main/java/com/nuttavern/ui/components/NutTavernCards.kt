package com.nuttavern.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import kotlinx.coroutines.launch

/**
 * 模型卡片相关 token。维持"扁卡 + 行内能力胶囊"的视觉,与 rikkahub 的尺寸对齐
 * 但减一点垂直空间。
 *
 * 当前布局:**标题 + chips 同一行**(标题左 + chips 右),让卡片只占一行,适合密集列表;
 * 卡片高度典型 48dp 左右(垂直 padding 8dp + 内容 32dp 左右)。
 */
object NutTavernModelCardTokens {
    val ContainerVerticalPadding: Dp = 8.dp
    val ContainerHorizontalPadding: Dp = 14.dp
    val ContainerCorner: Dp = 12.dp
    val ContentSpacing: Dp = 12.dp
    val LeadingIconSize: Dp = 32.dp
    val LeadingIconPadding: Dp = 0.dp
    val TextRowSpacing: Dp = 4.dp
    val ChipRowSpacing: Dp = 4.dp
}

/**
 * 模型卡片(Provider 详情页 / 模型选择器中通用)。
 *
 * 视觉规则:
 * - `surfaceContainerLow` 背景 + 1.dp `outlineVariant` 描边;选中时换 `primaryContainer` + primary 描边;
 * - 两行布局:
 *   - 第一行:左图标 + 标题(weight + ellipsis)+ 行尾 [trailing] 槽;
 *   - 第二行:chips 横排,贴在标题底下,**所有能力 chip 在同一行平铺**(类型 / 输入→输出 / 工具 / 推理);
 * - 卡片高度典型 ≈56dp(垂直 padding 8dp + 标题 ~20dp + chips ~22dp + 行间距 4dp)。
 *
 * 不接受 `shape` / `padding` 自定义参数:整个项目要保持视觉一致。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NutTavernModelCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
    leading: @Composable (() -> Unit)? = null,
    title: String,
    chips: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val titleColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NutTavernModelCardTokens.ContainerCorner),
        color = containerColor,
        contentColor = titleColor,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = NutTavernModelCardTokens.ContainerHorizontalPadding,
                    vertical = NutTavernModelCardTokens.ContainerVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NutTavernModelCardTokens.ContentSpacing),
        ) {
            if (leading != null) {
                Box(
                    modifier = Modifier.size(NutTavernModelCardTokens.LeadingIconSize),
                    contentAlignment = Alignment.Center,
                ) {
                    leading()
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NutTavernModelCardTokens.TextRowSpacing),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chips != null) {
                    // 第二行:能力 chip 横排,**单行不换行**。chip 内容比卡片可用宽度还宽时,FlowRow
                    // 会自动换行(避免溢出),但典型场景下能完整放进一行,与产品要求一致。
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NutTavernModelCardTokens.ChipRowSpacing),
                        verticalArrangement = Arrangement.spacedBy(NutTavernModelCardTokens.TextRowSpacing),
                    ) {
                        chips()
                    }
                }
            }

            if (trailing != null) {
                trailing()
            }
        }
    }
}

/**
 * 能力胶囊。给"模型类型 / 输入模态 / 输出模态 / 能力"等用,统一一种形态。
 *
 * 视觉规则:
 * - 仅图标 + 短文字一行;不允许两行;长文必须省略;
 * - container / content 颜色由调用方传入,但建议优先使用 [NutTavernCapabilityChipColors] 提供的预设。
 */
@Composable
fun NutTavernCapabilityChip(
    text: String,
    icon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .height(22.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 纯图标版能力胶囊。无文字,只用容器色 + 居中图标传达语义。
 *
 * 设计取舍:
 * - 与 [NutTavernCapabilityChip] 共用容器 token 但去掉 Text,固定方形(22 x 22dp)避免不同 chip 宽窄不一;
 * - [contentDescription] 必填,留给可访问性 / 工具读屏。
 */
@Composable
fun NutTavernCapabilityIconChip(
    icon: ImageVector,
    contentDescription: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(
            modifier = Modifier.size(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/**
 * 模型卡里的能力胶囊配色预设。统一风格,避免散落取色。语义上分四组:
 * - [type]:模型类型,主色 container;
 * - [modality]:输入 / 输出模态,中性 surfaceContainerHighest;
 * - [tool]:工具能力,tertiary container;
 * - [reasoning]:推理能力,secondary container;
 */
object NutTavernCapabilityChipColors {
    @Composable
    fun type(): Pair<Color, Color> = MaterialTheme.colorScheme.primaryContainer to
        MaterialTheme.colorScheme.onPrimaryContainer

    @Composable
    fun modality(): Pair<Color, Color> = MaterialTheme.colorScheme.surfaceContainerHighest to
        MaterialTheme.colorScheme.onSurface

    @Composable
    fun tool(): Pair<Color, Color> = MaterialTheme.colorScheme.tertiaryContainer to
        MaterialTheme.colorScheme.onTertiaryContainer

    @Composable
    fun reasoning(): Pair<Color, Color> = MaterialTheme.colorScheme.secondaryContainer to
        MaterialTheme.colorScheme.onSecondaryContainer
}

// region 实体列表卡 (Preset / Regex / 后续 Lorebook、Character 共用)

/**
 * 实体列表卡(Preset / 正则规则 / 正则规则组 / 后续 Lorebook / Character 等共用)。
 *
 * 视觉规则**不接受参数化**,保证项目所有"实体列表"卡视觉一致:
 * - `MaterialTheme.shapes.large` 圆角
 * - `surfaceContainerHigh` 背景
 * - 横向 padding 16dp / 纵向 padding 14dp
 * - 内容元素 spacedBy 12dp
 * - 拖动态:`tonalElevation` + `shadowElevation` 都升 6dp,容器色不变
 *
 * 内容结构(从左到右):
 * - 可选 [leading] 槽(头像 / 占位图标 / 颜色块,典型 32dp);
 * - 标题 + 可选副标(占满中段,自动 ellipsis);
 * - [trailing] 槽:状态胶囊 / 编辑按钮 / Switch / 拖把手,业务方按需塞,**多个元素之间间距由外层
 *   `spacedBy 12dp` 自动控制,trailing 不需要自己再加 spacing**。
 *
 * 之所以不加 trailing 行高对齐机制:M3 Switch 自然高度比 IconButton 高 ~4dp,
 * 这是 Switch 触摸目标合规带来的固定差,强行 scale 会破坏点击区。视觉上 ~4dp 差不影响列表整体观感。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NutTavernEntityCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleFallback: String = "未命名",
    elevated: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leading != null) {
                leading()
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title.ifBlank { titleFallback },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                trailing()
            }
        }
    }

    val surfaceModifier = modifier
        .fillMaxWidth()
        .let { mod ->
            if (onClick != null || onLongClick != null) {
                mod
                    .clip(MaterialTheme.shapes.large)
                    .combinedClickable(
                        onClick = onClick ?: {},
                        onLongClick = onLongClick?.let { callback ->
                            {
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                callback()
                            }
                        },
                    )
            } else {
                mod
            }
        }

    Surface(
        modifier = surfaceModifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (elevated) 6.dp else 0.dp,
        shadowElevation = if (elevated) 6.dp else 0.dp,
    ) { content() }
}

/**
 * 实体卡通用"状态胶囊"。提供"默认 / 使用中 / 自定义"三种预设语义(由调用方传 [container] / [content])。
 */
@Composable
fun NutTavernEntityStatusPill(
    label: String,
    container: Color,
    content: Color,
) {
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

/**
 * 实体卡通用"铅笔编辑按钮"。32dp 触区,18dp 图标,onSurfaceVariant 色。
 *
 * 既可当 trailing 操作按钮,也可当 leading 主入口按钮(配合卡片整体 onClick=null,
 * 由这一个按钮承载"进编辑"动作)。
 */
@Composable
fun NutTavernEntityEditIconButton(
    onClick: () -> Unit,
    contentDescription: String = "编辑",
) {
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
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 实体卡通用"进入容器按钮"(FolderOpen)。32dp 触区,18dp 图标,与铅笔按钮视觉等齐。
 *
 * 用于"卡片代表一个容器,点击进入下级页"的场景(如规则组进组内列表)。
 * 与铅笔按钮共用同一种触区规格,放 leading 时整列卡片的 leading 列对齐。
 */
@Composable
fun NutTavernEntityEnterIconButton(
    onClick: () -> Unit,
    contentDescription: String = "进入",
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Lucide.FolderOpen,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 实体卡通用"拖动把手"。20dp 图标,onSurfaceVariant 色。
 * 调用方需要把 `Modifier.draggableHandle()` 传进 [modifier],由 ReorderableItem scope 提供。
 */
@Composable
fun NutTavernEntityDragHandle(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Lucide.GripVertical,
        contentDescription = "拖动排序",
        modifier = modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 实体卡通用"启用 Switch"。
 *
 * 关键点:用 [LocalMinimumInteractiveComponentSize] 把 Switch 的最小触摸尺寸从 48dp 收回到自然
 * 高度,避免在卡片 trailing 区把整张卡撑高 ~4dp。
 *
 * 这样视觉上 Switch 与 [NutTavernEntityEditIconButton] 等齐,卡片高度一致。
 * Switch 本身的命中区(36dp 高)对手指仍然足够,且整张卡 Surface.onClick 也能触发,不影响可达性。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutTavernEntitySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

// endregion

// region ── 实体长按操作 Sheet ──────────────────────────────────────────────────

/**
 * 实体长按操作菜单的单个操作项描述。
 *
 * @param icon 操作图标
 * @param title 操作标题
 * @param destructive 是否为危险操作(删除等),会单独分组并染 error 色
 * @param onClick 点击回调;Sheet 会在回调执行后自动关闭
 */
data class EntityAction(
    val icon: ImageVector,
    val title: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 实体列表页统一的长按操作 ModalBottomSheet。
 *
 * 内部管理 `sheetState` 的 hide 动画:点击操作项后先执行回调,再播放退出动画,
 * 最后通过 [onDismiss] 通知调用方清空状态。调用方只需在 `onDismiss` 里把
 * `longPressTarget = null` 即可,不需要自己处理 coroutine。
 *
 * 布局规则:
 * - 普通操作(destructive=false)装在第一组 [NutTavernGroupSection]
 * - 危险操作(destructive=true)装在第二组 [NutTavernGroupSection]
 * - 组内项之间用 [NutTavernGroupDivider] 分隔
 *
 * @param title Sheet 顶部标题(通常是实体名称)
 * @param actions 操作项列表,按传入顺序排列
 * @param onDismiss 关闭回调(用户下滑关闭或操作完成后触发)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutTavernEntityActionsSheet(
    title: String,
    actions: List<EntityAction>,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
        ) {
            NutTavernSheetTitle(title = title.ifBlank { "操作" })

            val normalActions = actions.filter { !it.destructive }
            val destructiveActions = actions.filter { it.destructive }

            if (normalActions.isNotEmpty()) {
                NutTavernGroupSection {
                    normalActions.forEachIndexed { index, action ->
                        if (index > 0) {
                            NutTavernGroupDivider()
                        }
                        NutTavernIconRow(
                            icon = action.icon,
                            title = action.title,
                            onClick = {
                                action.onClick()
                                scope.launch {
                                    sheetState.hide()
                                }.invokeOnCompletion {
                                    onDismiss()
                                }
                            },
                        )
                    }
                }
            }

            if (destructiveActions.isNotEmpty()) {
                NutTavernGroupSection {
                    destructiveActions.forEachIndexed { index, action ->
                        if (index > 0) {
                            NutTavernGroupDivider()
                        }
                        NutTavernIconRow(
                            icon = action.icon,
                            title = action.title,
                            destructive = true,
                            onClick = {
                                action.onClick()
                                scope.launch {
                                    sheetState.hide()
                                }.invokeOnCompletion {
                                    onDismiss()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// endregion

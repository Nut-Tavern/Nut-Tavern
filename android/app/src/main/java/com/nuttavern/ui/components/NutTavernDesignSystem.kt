package com.nuttavern.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide

/**
 * Nut Tavern 设计系统 / 组件规范(单一可信来源)。
 *
 * # 目标
 *
 * 一处定义视觉语言、一处提供可复用组件、一处记录组件使用约定。新增页面、对话框、
 * 抽屉时,先翻这个文件;它已经覆盖的场景不要私下重复写,它没覆盖的场景在这里
 * 增补,不要散落到业务文件里。
 *
 * # 分层
 *
 * 1. **颜色**:走 `MaterialTheme.colorScheme`,源在 `theme/Theme.kt`、`theme/Color.kt`。
 *    业务代码不要 hard-code Color。
 * 2. **排版**:走 `MaterialTheme.typography`,源在 `theme/Type.kt`。
 *    标题用 `titleMedium` / `titleLarge`,正文用 `bodyLarge` / `bodyMedium`,
 *    辅助说明用 `bodySmall`,小标签用 `labelLarge` / `labelMedium`。
 * 3. **形状**:走 `MaterialTheme.shapes` 或本文件下方的语义常量
 *    [NutTavernShapeTokens]。`RoundedCornerShape(24.dp)` 这种字面量只允许出现在
 *    Composer / 输入栏这种确定的视觉常量场景,新增时优先用 token。
 * 4. **尺寸**:原子尺寸在 [NutTavernUiTokens],组件级语义尺寸在 [NutTavernGroupTokens]。
 *    新增"几乎所有页面都会用"的常量时合并进来,不要 per-feature 自建 tokens。
 * 5. **组件**:见下方"组件目录"。
 *
 * # 组件目录
 *
 * ## 输入与选择(已沉淀,见 `NutTavernRows.kt`)
 * - [NutTavernSelectableRow]:选中态行,用于设置选项、模型选择、思考量等。
 * - [NutTavernInputActionButton]:输入框内的小圆形按钮(全屏编辑入口等)。
 * - [NutTavernInputPrimaryButton]:发送 / 停止主按钮。
 * - [NutTavernInputToolbarButton]:Composer 工具栏图标按钮。
 * - [NutTavernMessageActionButton]:消息气泡旁边的小图标按钮(已逐步退役)。
 *
 * ## 分组容器(本文件)
 * - [NutTavernGroupSection]:带小标题的整块,内部包一张分组卡。
 * - [NutTavernGroupCard]:`surfaceContainerHigh + shapes.large` 的圆角卡片,
 *   用于把"概念上同属一组"的行装在一起。设置抽屉、消息长按菜单、未来世界书 /
 *   正则等列表全部复用这一种容器。
 * - [NutTavernGroupDivider]:卡片内项目之间的细分隔线,固定 0.5dp、
 *   起始缩进 56.dp(对齐图标右侧)、`outlineVariant` 50% 透明度。
 * - [NutTavernSectionLabel]:卡片上方的小标题(`labelMedium / onSurfaceVariant`)。
 * - [NutTavernIconRow]:卡内的"图标 + 主标题 + 副标题 + 可选尾部"行,
 *   是设置抽屉项、消息操作项、菜单项等的统一形态。提供两个重载:
 *   - 默认 `icon: ImageVector` — 最常见的"小图标 + 文案"行;
 *   - `leading: @Composable () -> Unit` — leading 是头像 / 占位 / 复合内容时使用,
 *     padding / 排版 / 行为完全一致,确保所有图标行严格视觉对齐。
 *
 * ## Sheet / 对话框装饰(本文件)
 * - [NutTavernSheetTitle]:Sheet 顶部的"标题 + 说明"段落。
 * - [NutTavernSelectedCheckIcon]:`primary` 圆底 + 对勾,放在选项行尾部表示已选中。
 *
 * ## 全屏编辑(`NutTavernFullScreenTextEditor.kt`)
 * - [com.nuttavern.ui.components.NutTavernFullScreenTextEditor]:沉浸式 Dialog +
 *   单 OutlinedTextField,Composer 草稿、用户身份描述、未来角色卡描述等长文本字段共用。
 *
 * # 使用约定
 *
 * 1. 同一组操作或同一类配置必须装在同一张 [NutTavernGroupCard] 里。
 *    不要再用 `Spacer` + 散落的 Surface 假装"分组"。
 * 2. 危险操作(删除、清空、重置)单独一组,不与常规操作合并到同一张卡里。
 * 3. 卡片之间通过 [NutTavernGroupSection] 自带的间距分隔(默认 16.dp),
 *    不要再额外手写 `Spacer(height = 16.dp)`。
 * 4. 卡内项之间一律用 [NutTavernGroupDivider],不要换其他粗细 / 颜色。
 * 5. 行结构优先复用 [NutTavernIconRow];只有当 [NutTavernIconRow] 表达不了时
 *    才允许在 [NutTavernGroupCard] 里直接塞自定义内容,并在调用处用注释说明
 *    为什么不能复用、后续替换条件是什么。
 * 6. M3 原生组件(`NavigationDrawerItem`、`ListItem`、`MenuItem` 等)在交互行为
 *    完全匹配场景时优先使用,不要为了"风格统一"自写覆盖。会话抽屉里仍然
 *    使用 `NavigationDrawerItem`,因为它已经处理了选中态、icon、badge。
 *
 * # 何时新增组件,何时回到 M3
 *
 * - 该形态在项目里**已经出现 ≥ 3 次**且 M3 原生组件无法直接表达 → 抽到本文件。
 * - 只在一两个地方用 → 留在业务文件里,加 KDoc 说明边界。
 * - M3 原生组件可以直接覆盖 → 用原生,不要包装。
 *
 * # 落地清单
 *
 * - [x] 分组卡片 / 分组分隔线 / 小标题 / 图标行
 * - [x] Sheet 标题段(NutTavernSheetTitle)
 * - [x] 选中标识图标(NutTavernSelectedCheckIcon)
 * - [x] Composer 圆角 / 最大高度 token(NutTavernShapeTokens / NutTavernComposerTokens)
 * - [x] 全屏编辑器骨架([com.nuttavern.ui.components.NutTavernFullScreenTextEditor]——
 *       Dialog + Scaffold + TopAppBar 受控版本,Composer / 用户身份内容 / 后续角色描述都复用)
 * - [x] 实体列表卡([com.nuttavern.ui.components.NutTavernEntityCard] + 配套
 *       NutTavernEntityStatusPill / NutTavernEntityEditIconButton / NutTavernEntityDragHandle /
 *       NutTavernEntitySwitch)。Preset / 正则规则 / 正则规则组 / 后续 Lorebook / Character 列表
 *       共用。视觉规则不接受参数化:`shapes.large + surfaceContainerHigh + 16/14 padding +
 *       spacedBy 12dp + 6dp elevation 拖动态`。Switch 用 `LocalMinimumInteractiveComponentSize` 收回到
 *       自然高度,避免把卡片撑高 ~4dp。
 * - [ ] 空态(目前在 `EmptyState.kt`,未来并入)
 * - [ ] NutTavernSelectableRow 与 NutTavernIconRow 长期合并(都是图标 + 标题 + 副标题 +
 *       尾部,差别只在选中态语义)
 *
 * 改动这个文件前,先看 `android/AGENTS.md` 中"组件规范"段落,保证文档与代码同步。
 */

/**
 * 分组卡片相关的语义尺寸。这里只放"组件级"常量,原子尺寸继续放 [NutTavernUiTokens]。
 */
object NutTavernGroupTokens {
    /** 卡片之间(也就是 [NutTavernGroupSection] 之间)的标准垂直间距。 */
    val SectionSpacing = 16.dp

    /** 小标题与下方卡片之间的距离。 */
    val SectionLabelBottomSpacing = 8.dp

    /** 小标题相对屏幕边缘的左 padding,与卡片内部内容左 padding 视觉对齐。 */
    val SectionLabelStartPadding = 4.dp

    /** 卡内分隔线起始缩进,等于"图标 24dp + 图标右侧间距 16dp + 卡片左 padding 16dp"。 */
    val DividerStartInset = 56.dp

    /** 行内图标尺寸(IconRow 主图标)。 */
    val IconRowLeadingIconSize = 24.dp

    /** 行内尾部图标尺寸(IconRow 尾部辅助 chevron 等)。 */
    val IconRowTrailingIconSize = 18.dp

    /** 行水平 padding。 */
    val IconRowHorizontalPadding = 16.dp

    /** 行垂直 padding。 */
    val IconRowVerticalPadding = 12.dp

    /** 行内主标题与图标之间的间距,也是图标行整体的内容间距。 */
    val IconRowContentSpacing = 16.dp

    /** 主标题与副标题的间距。 */
    val IconRowTextSpacing = 2.dp
}

/**
 * 形状语义 token。把项目里需要的非默认圆角集中在这里,避免 `RoundedCornerShape(24.dp)`
 * 这种字面量在业务代码里漂移。
 */
object NutTavernShapeTokens {
    /** Composer 外框圆角(对应输入栏视觉常量)。 */
    val ComposerOuter = 24.dp

    /** Composer 文本输入区圆角。 */
    val ComposerInner = 20.dp

    /** Composer 附件 Sheet 内的横向 Tile 圆角。 */
    val AttachmentTile = 18.dp

    /** 头像占位方框圆角(用户身份 / 角色卡编辑页 / 列表网格)。 */
    val AvatarPlaceholder = 20.dp

    /** 搜索栏胶囊形状(percent = 50 等价)。 */
    val SearchBar = 50
}

/**
 * Composer(消息输入栏)专属尺寸 token。圆角放 [NutTavernShapeTokens]。
 */
object NutTavernComposerTokens {
    /** 文本输入区允许的最大高度,超过后内部滚动。 */
    val InputMaxHeight = 112.dp

    /** 文本输入区单行基础高度,Composer 与外部一致使用,避免漂移。 */
    val InputMinHeight = 40.dp

    /** Composer 容器停靠时(键盘未弹起)的底部留白。 */
    val RestingBottomPadding = 12.dp
}

/**
 * 带小标题的分组段落。是右侧设置抽屉、消息长按菜单、未来世界书等列表的标准容器。
 *
 * 用法:
 * ```
 * NutTavernGroupSection(title = "生成与工具") {
 *     NutTavernIconRow(...)
 *     NutTavernGroupDivider()
 *     NutTavernIconRow(...)
 * }
 * ```
 *
 * 不传 title 时只渲染卡片本身,不留小标题占位。
 */
@Composable
fun NutTavernGroupSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionLabelBottomSpacing),
    ) {
        if (!title.isNullOrBlank()) {
            NutTavernSectionLabel(text = title)
        }
        NutTavernGroupCard(content = content)
    }
}

/**
 * 分组卡片容器。`surfaceContainerHigh` 背景 + `shapes.large` 圆角。
 * 调用方负责往里放 [NutTavernIconRow] 或自定义内容,以及在项目之间放
 * [NutTavernGroupDivider]。
 */
@Composable
fun NutTavernGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

/**
 * 卡内项目之间的细分隔线。0.5dp + 默认 56dp 起始缩进 + outlineVariant α 0.5。
 *
 * 缩进默认对齐 [NutTavernIconRow] 的图标右侧。如果卡内行不带 leading icon
 * (例如 `OutlinedTextField` 直挂在卡内),用 `inset = 0.dp` 让 divider 从卡片左侧起,
 * 视觉上不会"飘在右半边"。
 */
@Composable
fun NutTavernGroupDivider(inset: androidx.compose.ui.unit.Dp = NutTavernGroupTokens.DividerStartInset) {
    HorizontalDivider(
        modifier = Modifier.padding(start = inset),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * 卡片上方的小标题。`labelMedium / onSurfaceVariant`,左 padding 与卡片内容对齐。
 */
@Composable
fun NutTavernSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(start = NutTavernGroupTokens.SectionLabelStartPadding),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 卡内"图标 + 主标题 + 可选副标题 + 可选尾部"行。
 *
 * - [trailing] 不传时,如果 [showTrailingChevron] 为 true,自动渲染右向箭头,
 *   表示"点进二级页"。需要自定义尾部内容(开关、徽标)时传 [trailing] 即可。
 * - [destructive] 用于删除等危险操作,文字与图标染成 `error`。
 * - [subtitle] 留空表示单行行,行高自动收紧。
 *
 * 调用方负责把多个 [NutTavernIconRow] 装在 [NutTavernGroupCard] / [NutTavernGroupSection] 内,
 * 项目之间手动放 [NutTavernGroupDivider]。
 */
@Composable
fun NutTavernIconRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    destructive: Boolean = false,
    showTrailingChevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val primaryColor = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    NutTavernIconRow(
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(NutTavernGroupTokens.IconRowLeadingIconSize),
                tint = primaryColor,
            )
        },
        title = title,
        onClick = onClick,
        modifier = modifier,
        subtitle = subtitle,
        destructive = destructive,
        showTrailingChevron = showTrailingChevron,
        trailing = trailing,
    )
}

/**
 * [NutTavernIconRow] 的 leading-slot 版本。当 leading 不是普通 ImageVector(头像、占位、
 * 复合 leading)时使用,行的 padding / 排版 / 行为完全一致,确保页面里所有"图标行"在视觉上
 * 严格对齐。
 */
@Composable
fun NutTavernIconRow(
    leading: @Composable () -> Unit,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    destructive: Boolean = false,
    showTrailingChevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val primaryColor = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryColor = if (destructive) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = NutTavernGroupTokens.IconRowHorizontalPadding,
                    vertical = NutTavernGroupTokens.IconRowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.IconRowContentSpacing),
        ) {
            Box(
                modifier = Modifier.size(NutTavernGroupTokens.IconRowLeadingIconSize),
                contentAlignment = Alignment.Center,
            ) {
                leading()
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.IconRowTextSpacing),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = primaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                trailing != null -> trailing()
                showTrailingChevron -> {
                    Icon(
                        imageVector = Lucide.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(NutTavernGroupTokens.IconRowTrailingIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Sheet / 对话框开头的"标题 + 说明"段落。说明可空。
 *
 * 用法:出现在 [androidx.compose.material3.ModalBottomSheet] 内容顶部,或全屏对话框
 * 顶部的副标题区域。`titleLarge / onSurface` 加 `bodySmall / onSurfaceVariant`。
 */
@Composable
fun NutTavernSheetTitle(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(top = 8.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 选中标识图标:`primary` 圆底 + `onPrimary` 对勾。用于选项行的尾部表示"已选中"。
 */
@Composable
fun NutTavernSelectedCheckIcon(
    modifier: Modifier = Modifier,
    contentDescription: String? = "已选中",
) {
    Box(
        modifier = modifier
            .size(28.dp)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Lucide.Check,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

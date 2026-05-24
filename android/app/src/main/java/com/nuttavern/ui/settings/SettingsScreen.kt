package com.nuttavern.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BookOpenText
import com.composables.icons.lucide.BookUser
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Regex
import com.composables.icons.lucide.ServerCog
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.SunMoon
import com.composables.icons.lucide.TableProperties
import com.composables.icons.lucide.UserRoundCog
import com.composables.icons.lucide.Wrench
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow
import com.nuttavern.ui.theme.ThemeMode
import com.nuttavern.ui.viewmodel.SettingsViewModel

/**
 * 设置一级页。承载主题模式、二级页入口与"待接入"占位入口。
 *
 * 入口结构由产品确认,顺序固定:
 * 1. 颜色模式(行内三态)/ 显示设置 / 提供商
 * 2. 角色 / 用户身份
 * 3. 预设 / 正则 / 世界书 / 表格设置
 * 4. MCP / Skill
 * 5. 更多设置
 *
 * 还没接入的入口暂时弹 snackbar 提示,UI 上保持完整结构,避免后续接入时再来重排。
 * 具体入口接入时,把回调改成对应的 onNavigate 即可。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenThemeSettings: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPersonas: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenRegex: () -> Unit,
    onOpenTools: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val currentTheme by viewModel.currentTheme.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingFeatureNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingFeatureNotice) {
        val name = pendingFeatureNotice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar("$name 暂未接入")
        pendingFeatureNotice = null
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
        ) {
            item(key = "theme-and-providers") {
                NutTavernGroupSection {
                    ThemeModeRow(
                        themeMode = themeMode,
                        onSelectMode = viewModel::selectThemeMode,
                    )
                    NutTavernGroupDivider()
                    NutTavernIconRow(
                        icon = Lucide.Palette,
                        title = "显示设置",
                        subtitle = "主题:${currentTheme.displayName}",
                        showTrailingChevron = true,
                        onClick = onOpenThemeSettings,
                    )
                    NutTavernGroupDivider()
                    NutTavernIconRow(
                        icon = Lucide.ServerCog,
                        title = "提供商",
                        subtitle = "配置大模型提供商",
                        showTrailingChevron = true,
                        onClick = onOpenProviders,
                    )
                }
            }

            item(key = "characters") {
                NutTavernGroupSection {
                    NutTavernIconRow(
                        icon = Lucide.BookUser,
                        title = "角色",
                        subtitle = "管理角色卡",
                        showTrailingChevron = true,
                        onClick = onOpenCharacters,
                    )
                    NutTavernGroupDivider()
                    NutTavernIconRow(
                        icon = Lucide.UserRoundCog,
                        title = "用户身份",
                        subtitle = "管理用户身份",
                        showTrailingChevron = true,
                        onClick = onOpenPersonas,
                    )
                }
            }

            item(key = "prompts") {
                NutTavernGroupSection {
                    NutTavernIconRow(
                        icon = Lucide.SlidersHorizontal,
                        title = "预设",
                        subtitle = "管理预设",
                        showTrailingChevron = true,
                        onClick = onOpenPresets,
                    )
                    NutTavernGroupDivider()
                    NutTavernIconRow(
                        icon = Lucide.Regex,
                        title = "正则",
                        subtitle = "管理正则",
                        showTrailingChevron = true,
                        onClick = onOpenRegex,
                    )
                    NutTavernGroupDivider()
                    NutTavernIconRow(
                        icon = Lucide.BookOpenText,
                        title = "世界书",
                        subtitle = "管理世界书",
                        showTrailingChevron = true,
                        onClick = { pendingFeatureNotice = "世界书" },
                    )
                    NutTavernGroupDivider()
                    NutTavernIconRow(
                        icon = Lucide.TableProperties,
                        title = "表格设置",
                        subtitle = "管理变量与记忆表格",
                        showTrailingChevron = true,
                        onClick = { pendingFeatureNotice = "表格设置" },
                    )
                }
            }

            item(key = "extensions") {
                NutTavernGroupSection {
                    NutTavernIconRow(
                        icon = Lucide.Boxes,
                        title = "工具",
                        subtitle = "管理工具",
                        showTrailingChevron = true,
                        onClick = onOpenTools,
                    )
                    NutTavernGroupDivider()
                    NutTavernIconRow(
                        icon = Lucide.Puzzle,
                        title = "Skill",
                        subtitle = "管理技能",
                        showTrailingChevron = true,
                        onClick = { pendingFeatureNotice = "Skill" },
                    )
                }
            }

            item(key = "more") {
                NutTavernGroupSection {
                    NutTavernIconRow(
                        icon = Lucide.Wrench,
                        title = "更多设置",
                        subtitle = "应用锁、清缓存、导出备份",
                        showTrailingChevron = true,
                        onClick = { pendingFeatureNotice = "更多设置" },
                    )
                }
            }
        }
    }
}

/**
 * 颜色模式行(单行 + 右侧胶囊触发器)。
 *
 * 视觉规则:
 * - 整行单行高(`IconRowVerticalPadding * 2 + ~24dp 内容`),图标与标题在水平方向上下居中。
 * - 行尾是一颗"胶囊"按钮(状态文本 + 下拉箭头),点击判定 **只在胶囊**。
 *   行的其他空白处不响应点击,避免和"标题"产生"哪儿都能点"的歧义感。
 *
 * 实现细节:
 * - 不直接复用 [NutTavernIconRow]。后者整行 `Surface(onClick)` 一起响应,
 *   会让点击判定外溢到行其他位置;改造 IconRow 反而会污染它的语义。
 * - 胶囊用 `surfaceContainerHighest` + 圆角 50%,在 `surfaceContainerHigh`
 *   的卡片背景上有足够对比;不引入 primary 色,避免视觉过重。
 *
 * 后续如果出现第二个"图标 + 标题 + 胶囊"行(例如"对话密度"快速切换),
 * 抽到设计系统里复用,本文件只保留调用。
 */
@Composable
private fun ThemeModeRow(
    themeMode: ThemeMode,
    onSelectMode: (ThemeMode) -> Unit,
) {
    val options = listOf(
        Triple(ThemeMode.System, Lucide.Monitor, "跟随系统"),
        Triple(ThemeMode.Light, Lucide.Sun, "浅色"),
        Triple(ThemeMode.Dark, Lucide.Moon, "深色"),
    )
    val currentLabel = options.firstOrNull { it.first == themeMode }?.third ?: "跟随系统"

    Row(
        modifier = Modifier
            .padding(
                horizontal = NutTavernGroupTokens.IconRowHorizontalPadding,
                vertical = NutTavernGroupTokens.IconRowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.IconRowContentSpacing),
    ) {
        Icon(
            imageVector = Lucide.SunMoon,
            contentDescription = null,
            modifier = Modifier.size(NutTavernGroupTokens.IconRowLeadingIconSize),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "颜色模式",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ThemeModePillButton(
            label = currentLabel,
            options = options,
            onSelectMode = onSelectMode,
        )
    }
}

@Composable
private fun ThemeModePillButton(
    label: String,
    options: List<Triple<ThemeMode, androidx.compose.ui.graphics.vector.ImageVector, String>>,
    onSelectMode: (ThemeMode) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        Surface(
            onClick = { menuExpanded = true },
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Lucide.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(NutTavernGroupTokens.IconRowTrailingIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            options.forEach { (mode, icon, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    leadingIcon = {
                        Icon(imageVector = icon, contentDescription = null)
                    },
                    onClick = {
                        onSelectMode(mode)
                        menuExpanded = false
                    },
                )
            }
        }
    }
}

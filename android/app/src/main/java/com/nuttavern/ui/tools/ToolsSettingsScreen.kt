package com.nuttavern.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wrench
import com.nuttavern.data.tools.ToolReasoningMode
import com.nuttavern.data.tools.ToolsSettings
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow
import com.nuttavern.ui.components.NutTavernNumericField
import com.nuttavern.ui.components.NumericParser
import com.nuttavern.ui.components.NutTavernSectionLabel
import com.nuttavern.ui.viewmodel.ToolsViewModel

/**
 * 工具二级页。
 *
 * 结构:
 * 1. **入口区**:内置工具 / MCP 服务器 — 点击进对应三级页(当前都未接入,弹"待接入");
 * 2. **本地工具与 tool 调用高级设置**:
 *    - 工具调用递归上限([ToolsSettings.toolCallRecurseLimit])
 *    - 工具推理模式([ToolReasoningMode])
 *
 * 当前 MVP 只承载这两个全局开关 + 两个二级占位入口。MCP 服务器列表 / 内置工具开关
 * 各走独立模块 + 独立 DataStore,以"工具"作为入口聚合。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsSettingsScreen(
    onBack: () -> Unit,
    onOpenLocalTools: () -> Unit,
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
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
                title = { Text("工具") },
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
            item(key = "entries") {
                NutTavernGroupSection {
                    NutTavernIconRow(
                        icon = Lucide.Wrench,
                        title = "内置工具",
                        subtitle = "客户端自带的工具,按工具开关",
                        showTrailingChevron = true,
                        onClick = onOpenLocalTools,
                    )
                    NutTavernGroupDivider()
                    NutTavernIconRow(
                        icon = Lucide.Boxes,
                        title = "MCP 服务器",
                        subtitle = "第三方 MCP 服务管理",
                        showTrailingChevron = true,
                        onClick = { pendingFeatureNotice = "MCP 服务器" },
                    )
                }
            }

            item(key = "advanced-label") {
                NutTavernSectionLabel(text = "本地工具与 tool 调用高级设置管理")
            }
            item(key = "advanced") {
                NutTavernGroupSection {
                    RecurseLimitRow(
                        value = settings.toolCallRecurseLimit,
                        onChange = viewModel::setRecurseLimit,
                    )
                    NutTavernGroupDivider()
                    ReasoningModeRow(
                        value = settings.toolReasoningMode,
                        onClick = { pendingFeatureNotice = "工具推理模式" },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecurseLimitRow(
    value: Int,
    onChange: (Int) -> Unit,
) {
    NutTavernNumericField(
        label = "工具调用递归上限",
        value = value,
        onValueChange = { it?.let(onChange) },
        parser = NumericParser.IntParser,
        min = 1,
        max = ToolsSettings.MAX_RECURSE_LIMIT,
        helperText = "模型一轮回复内连续触发工具调用的最大次数,默认 5,上限 ${ToolsSettings.MAX_RECURSE_LIMIT}",
    )
}

@Composable
private fun ReasoningModeRow(
    value: ToolReasoningMode,
    onClick: () -> Unit,
) {
    NutTavernIconRow(
        icon = Lucide.Boxes,
        title = "工具推理模式",
        subtitle = "暂未接入工具调用带 reasoning 传参, 当前固定不传递 reasoning",
        onClick = onClick,
        trailing = {
            Text(
                text = reasoningModeLabel(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    )
}

private fun reasoningModeLabel(mode: ToolReasoningMode): String = when (mode) {
    ToolReasoningMode.DISABLED -> "禁用"
    ToolReasoningMode.SINCE_LAST_USER -> "最近一轮起"
    ToolReasoningMode.ACTIVE_CHAIN -> "完整链"
}

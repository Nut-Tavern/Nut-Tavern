package com.nuttavern.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingFeatureNotice by remember { mutableStateOf<String?>(null) }
    var pickReasoningMode by remember { mutableStateOf(false) }

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
                        onClick = { pendingFeatureNotice = "内置工具" },
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
                        onClick = { pickReasoningMode = true },
                    )
                }
            }
        }
    }

    if (pickReasoningMode) {
        ReasoningModePickerDialog(
            current = settings.toolReasoningMode,
            onSelect = {
                viewModel.setReasoningMode(it)
                pickReasoningMode = false
            },
            onDismiss = { pickReasoningMode = false },
        )
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "工具推理模式",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = reasoningModeLabel(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ReasoningModePickerDialog(
    current: ToolReasoningMode,
    onSelect: (ToolReasoningMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择工具推理模式") },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToolReasoningMode.entries.forEach { mode ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (mode == current) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = if (mode == current) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        onClick = { onSelect(mode) },
                    ) {
                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = reasoningModeLabel(mode),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = reasoningModeDescription(mode),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (mode == current) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun reasoningModeLabel(mode: ToolReasoningMode): String = when (mode) {
    ToolReasoningMode.DISABLED -> "禁用"
    ToolReasoningMode.SINCE_LAST_USER -> "最近一轮起"
    ToolReasoningMode.ACTIVE_CHAIN -> "完整链"
}

private fun reasoningModeDescription(mode: ToolReasoningMode): String = when (mode) {
    ToolReasoningMode.DISABLED -> "不带任何上一轮的 reasoning(默认,最省 token)"
    ToolReasoningMode.SINCE_LAST_USER -> "只带最近一条用户消息之后的 reasoning(平衡)"
    ToolReasoningMode.ACTIVE_CHAIN -> "带整个工具调用链的 reasoning(最贵,适合长工具链)"
}

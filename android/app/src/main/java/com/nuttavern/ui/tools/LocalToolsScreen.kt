package com.nuttavern.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.ToggleRight
import com.nuttavern.ui.components.NutTavernEntityCard
import com.nuttavern.ui.components.NutTavernEntitySwitch
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow
import com.nuttavern.ui.components.NutTavernSectionLabel
import com.nuttavern.ui.viewmodel.LocalToolsViewModel

/**
 * 内置工具三级页(设置 → 工具 → 内置工具)。
 *
 * 结构:
 * 1. **全局设置组**:新会话默认启用开关 + 执行前人工确认开关(行内 [NutTavernEntitySwitch]);
 * 2. **可用工具组**:每个内置工具一张 [NutTavernEntityCard],trailing 是启用开关。
 *
 * 会话级开关(强制开/关/跟随)在右侧栏单独提供,这里只管全局默认与单工具启用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalToolsScreen(
    onBack: () -> Unit,
    viewModel: LocalToolsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("内置工具") },
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
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
        ) {
            item(key = "global") {
                NutTavernGroupSection {
                    NutTavernIconRow(
                        icon = Lucide.ToggleRight,
                        title = "新会话默认启用",
                        subtitle = "未单独配置的会话跟随此开关",
                        onClick = { viewModel.setDefaultEnabled(!settings.defaultEnabled) },
                        trailing = {
                            NutTavernEntitySwitch(
                                checked = settings.defaultEnabled,
                                onCheckedChange = viewModel::setDefaultEnabled,
                            )
                        },
                    )
                    NutTavernGroupDivider()
                    NutTavernIconRow(
                        icon = Lucide.ShieldAlert,
                        title = "执行前需人工确认",
                        subtitle = "模型调用工具时弹窗询问是否允许",
                        onClick = { viewModel.setRequireApproval(!settings.requireApproval) },
                        trailing = {
                            NutTavernEntitySwitch(
                                checked = settings.requireApproval,
                                onCheckedChange = viewModel::setRequireApproval,
                            )
                        },
                    )
                }
            }

            item(key = "tools-label") {
                NutTavernSectionLabel(text = "可用工具")
            }

            if (viewModel.tools.isEmpty()) {
                item(key = "tools-empty") {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Text(
                            text = "暂无可用工具",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(
                    viewModel.tools,
                    key = { it.id },
                ) { tool ->
                    val subtitle = if (tool.name == "get_current_time") {
                        "获取设备当前本地时间"
                    } else {
                        "${tool.name}: ${tool.description}"
                    }
                    NutTavernEntityCard(
                        title = tool.displayName,
                        subtitle = subtitle,
                        trailing = {
                            NutTavernEntitySwitch(
                                checked = tool.id in settings.enabledToolIds,
                                onCheckedChange = { viewModel.setToolEnabled(tool.id, it) },
                            )
                        },
                    )
                }
            }
        }
    }
}

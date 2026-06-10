package com.nuttavern.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ToggleRight
import com.composables.icons.lucide.Wrench
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
 * 1. **全局设置组**:新会话默认启用工具总开关(行内 [NutTavernEntitySwitch]);
 * 2. **可用工具组**:每个内置工具一个分组,工具信息行配置默认启用,下方行配置调用确认。
 *
 * 会话级开关在右侧栏单独提供,这里只管新会话默认与单工具默认。
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
                        title = "新会话默认启用工具",
                        subtitle = "只影响之后创建的会话，已有会话不跟随变化",
                        onClick = { viewModel.setDefaultEnabled(!settings.defaultEnabled) },
                        trailing = {
                            NutTavernEntitySwitch(
                                checked = settings.defaultEnabled,
                                onCheckedChange = viewModel::setDefaultEnabled,
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
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
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
                    val enabledByDefault = tool.id in settings.enabledToolIds
                    val approvalRequired = tool.id in settings.approvalRequiredToolIds
                    NutTavernGroupSection(modifier = Modifier.fillMaxWidth()) {
                        NutTavernIconRow(
                            icon = Lucide.Wrench,
                            title = tool.displayName,
                            subtitle = subtitle,
                            onClick = { viewModel.setToolEnabled(tool.id, !enabledByDefault) },
                            trailing = {
                                NutTavernEntitySwitch(
                                    checked = enabledByDefault,
                                    onCheckedChange = { viewModel.setToolEnabled(tool.id, it) },
                                )
                            },
                        )
                        NutTavernGroupDivider()
                        NutTavernIconRow(
                            icon = Lucide.Check,
                            title = "调用前确认",
                            subtitle = "模型请求调用这个工具时先弹窗确认",
                            onClick = { viewModel.setToolApprovalRequired(tool.id, !approvalRequired) },
                            trailing = {
                                NutTavernEntitySwitch(
                                    checked = approvalRequired,
                                    onCheckedChange = { viewModel.setToolApprovalRequired(tool.id, it) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

package com.nuttavern.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ToggleRight
import com.composables.icons.lucide.Wrench
import com.nuttavern.data.tools.LocalToolsSettings
import com.nuttavern.network.ChatTool
import com.nuttavern.network.ToolUnit
import com.nuttavern.network.buildToolUnits
import com.nuttavern.ui.components.NutTavernCapabilityChip
import com.nuttavern.ui.components.NutTavernCapabilityChipColors
import com.nuttavern.ui.components.NutTavernEntityCard
import com.nuttavern.ui.components.NutTavernEntityDragHandle
import com.nuttavern.ui.components.NutTavernEntitySwitch
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow
import com.nuttavern.ui.components.NutTavernSectionLabel
import com.nuttavern.ui.components.NutTavernSheetTitle
import com.nuttavern.ui.viewmodel.LocalToolsViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 内置工具三级页(设置 → 工具 → 内置工具)。
 *
 * 每个内置工具(或工具组)一张标准实体卡 [NutTavernEntityCard]:左 Wrench 图标 + 标题副标,
 * 尾部竖排两枚状态胶囊(默认启用态 / 确认态)+ 拖动把手。长按或点击卡片弹 [ToolConfigSheet]
 * 配置这两个开关;拖动把手排序,顺序持久化并同步到右侧栏快选列表。
 *
 * 会话级临时开关在右侧栏单独提供,这里只管新会话默认启用、调用前确认与展示顺序。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalToolsScreen(
    onBack: () -> Unit,
    viewModel: LocalToolsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val units = remember(viewModel.tools, settings.toolOrder) {
        buildToolUnits(viewModel.tools, settings.toolOrder)
    }

    // 拖动时的本地顺序:以持久化顺序为初值,拖动改本地态,松手才回写。
    var localOrder by remember(units) { mutableStateOf(units) }
    var configuringKey by remember { mutableStateOf<String?>(null) }
    val configuring = localOrder.firstOrNull { it.orderKey == configuringKey }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // 按 key 定位:列表里还有 label / empty 等非拖动项,直接用全局 index 会与只含工具的
        // localOrder 错位越界。拖到非工具项上(toKey 不在 localOrder)时忽略,保持原顺序。
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        val current = localOrder.toMutableList()
        val fromIndex = current.indexOfFirst { it.orderKey == fromKey }
        val toIndex = current.indexOfFirst { it.orderKey == toKey }
        if (fromIndex < 0 || toIndex < 0) return@rememberReorderableLazyListState
        current.add(toIndex, current.removeAt(fromIndex))
        localOrder = current
    }

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
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
        ) {
            item(key = "tools-label") {
                NutTavernSectionLabel(text = "可用工具")
            }

            if (localOrder.isEmpty()) {
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
                items(localOrder, key = { it.orderKey }) { unit ->
                    ReorderableItem(state = reorderState, key = unit.orderKey) { isDragging ->
                        LocalToolCard(
                            unit = unit,
                            enabledByDefault = unit.isEnabledIn(settings),
                            approvalRequired = unit.isEffectiveApprovalRequiredIn(settings),
                            elevated = isDragging,
                            onClick = { configuringKey = unit.orderKey },
                            dragHandle = {
                                NutTavernEntityDragHandle(
                                    modifier = Modifier.draggableHandle(
                                        onDragStopped = {
                                            viewModel.setToolOrder(localOrder.map { it.orderKey })
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (configuring != null) {
        ToolConfigSheet(
            title = configuring.unitTitle,
            subtitle = configuring.unitSubtitle,
            enabledByDefault = configuring.isEnabledIn(settings),
            approvalRequired = configuring.isUserApprovalRequiredIn(settings),
            hasForcedApproval = configuring.hasForcedApproval,
            onSetEnabled = { viewModel.setToolsEnabled(configuring.toolIds, it) },
            onSetApprovalRequired = { viewModel.setToolsApprovalRequired(configuring.toolIds, it) },
            onDismiss = { configuringKey = null },
        )
    }
}

/** 单个工具 / 一个工具组的展示卡:左图标 + 标题副标 + 尾部竖排状态胶囊 + 拖动把手。 */
@Composable
private fun LocalToolCard(
    unit: ToolUnit,
    enabledByDefault: Boolean,
    approvalRequired: Boolean,
    elevated: Boolean,
    onClick: () -> Unit,
    dragHandle: @Composable () -> Unit,
) {
    NutTavernEntityCard(
        title = unit.unitTitle,
        subtitle = unit.unitSubtitle,
        elevated = elevated,
        onClick = onClick,
        onLongClick = onClick,
        leading = {
            Icon(
                imageVector = Lucide.Wrench,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailing = {
            // 单枚合并胶囊承载"默认启用 + 是否需确认",避免双胶囊撑高卡片或抢副标宽度。
            ToolStatusPill(enabled = enabledByDefault, approvalRequired = approvalRequired)
            Spacer(Modifier.size(4.dp))
            dragHandle()
        },
    )
}

/**
 * 工具状态胶囊:文案统一「默认启用」/「默认关闭」保持对齐,确认状态靠图标体现。
 *
 * - 关闭:「默认关闭」中性色,无确认图标(关态下确认无意义);
 * - 启用:「默认启用」主色,需确认用 Check 图标、免确认用 ToggleRight 图标。
 */
@Composable
private fun ToolStatusPill(enabled: Boolean, approvalRequired: Boolean) {
    if (!enabled) {
        NutTavernCapabilityChip(text = "默认关闭")
        return
    }
    val (container, content) = NutTavernCapabilityChipColors.type()
    NutTavernCapabilityChip(
        text = "默认启用",
        icon = if (approvalRequired) Lucide.Check else Lucide.ToggleRight,
        containerColor = container,
        contentColor = content,
    )
}

/** 工具长按配置 Sheet:默认启用 + 调用前确认两个开关。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolConfigSheet(
    title: String,
    subtitle: String,
    enabledByDefault: Boolean,
    approvalRequired: Boolean,
    hasForcedApproval: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onSetApprovalRequired: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
        ) {
            NutTavernSheetTitle(title = title, description = subtitle)

            NutTavernGroupSection {
                NutTavernIconRow(
                    icon = Lucide.ToggleRight,
                    title = "新会话默认启用",
                    subtitle = "只影响之后创建的会话，已有会话不跟随变化",
                    onClick = { onSetEnabled(!enabledByDefault) },
                    trailing = {
                        NutTavernEntitySwitch(
                            checked = enabledByDefault,
                            onCheckedChange = onSetEnabled,
                        )
                    },
                )
                NutTavernGroupDivider()
                NutTavernIconRow(
                    icon = Lucide.Check,
                    title = if (hasForcedApproval) "低风险工具也需确认" else "调用前确认",
                    subtitle = if (hasForcedApproval) {
                        "写操作始终强制确认；此开关只控制只读/低风险工具是否也询问"
                    } else {
                        "模型请求调用时先弹窗确认"
                    },
                    onClick = { onSetApprovalRequired(!approvalRequired) },
                    trailing = {
                        NutTavernEntitySwitch(
                            checked = approvalRequired,
                            onCheckedChange = onSetApprovalRequired,
                        )
                    },
                )
            }
        }
    }
}

/** 组内全部工具都默认启用才算"默认启用"。 */
private fun ToolUnit.isEnabledIn(settings: LocalToolsSettings): Boolean =
    toolIds.all { it in settings.enabledToolIds }

/** 用户额外开启的确认:组内全部工具都在设置集合里才算开启。 */
private fun ToolUnit.isUserApprovalRequiredIn(settings: LocalToolsSettings): Boolean =
    toolIds.all { it in settings.approvalRequiredToolIds }

/** 实际生效确认 = 工具自身强制确认 OR 用户额外确认。 */
private fun ToolUnit.isEffectiveApprovalRequiredIn(settings: LocalToolsSettings): Boolean =
    hasForcedApproval || isUserApprovalRequiredIn(settings)

/** 是否包含自身强制确认的工具(例如世界书写工具)。 */
private val ToolUnit.hasForcedApproval: Boolean
    get() = when (this) {
        is ToolUnit.SingleTool -> tool.needsApproval
        is ToolUnit.Group -> tools.any { it.needsApproval }
    }

private val ToolUnit.unitTitle: String
    get() = when (this) {
        is ToolUnit.SingleTool -> tool.displayName
        is ToolUnit.Group -> group.displayName
    }

private val ToolUnit.unitSubtitle: String
    get() = when (this) {
        is ToolUnit.SingleTool -> singleToolSubtitle(tool)
        is ToolUnit.Group -> group.description
    }

private fun singleToolSubtitle(tool: ChatTool): String =
    if (tool.name == "get_current_time") "获取设备当前本地时间" else "${tool.name}: ${tool.description}"

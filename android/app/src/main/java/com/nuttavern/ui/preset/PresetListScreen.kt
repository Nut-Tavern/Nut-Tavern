package com.nuttavern.ui.preset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.nuttavern.data.preset.Preset
import com.nuttavern.ui.components.NutTavernShapeTokens
import com.nuttavern.ui.viewmodel.PresetViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

internal const val NEW_PRESET_PLACEHOLDER_ID = "__new__"

/**
 * 预设列表页。
 *
 * 视觉规则与 [com.nuttavern.ui.character.CharacterListScreen] / [com.nuttavern.ui.persona.UserPersonaListScreen]
 * 完全对齐:
 * - 单列宽条卡,顶部胶囊搜索框 + 右上角 "+" 新建;
 * - 拖把手长按拖动,松手提交;过滤态下不允许拖动;
 * - 默认胶囊在卡片右侧;点击卡片主区域 → 设默认弹窗,点编辑键 → 进编辑页;
 * - 内置默认预设([Preset.DEFAULT_PRESET_ID])可编辑可设默认,但**不可删**(由仓库兜底)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetListScreen(
    onBack: () -> Unit,
    onOpenPresetDetail: (presetId: String) -> Unit,
    viewModel: PresetViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    var pendingDefault by remember { mutableStateOf<Preset?>(null) }
    var longPressPreset by remember { mutableStateOf<Preset?>(null) }
    var longPressIsDefault by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current

    val isFiltering = query.isNotBlank()
    val filteredItems = remember(items, query) {
        if (!isFiltering) items
        else items.filter { item ->
            val preset = item.preset
            preset.name.contains(query, ignoreCase = true) ||
                preset.description.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("预设") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenPresetDetail(NEW_PRESET_PLACEHOLDER_ID) }) {
                        Icon(Lucide.Plus, contentDescription = "新增预设")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PresetSearchBar(value = query, onValueChange = { query = it })

            when {
                filteredItems.isEmpty() && isFiltering -> PresetListEmpty("没有匹配的预设")
                // 注意:filteredItems 非过滤态下不可能为空 — 仓库 ensureDefaultPresent 保证
                // 至少有内置默认预设。这里不写"暂无预设"分支,避免死代码。
                else -> PresetListBody(
                    items = filteredItems,
                    reorderable = !isFiltering,
                    onEdit = onOpenPresetDetail,
                    onLongPress = { preset, isDefault ->
                        longPressPreset = preset
                        longPressIsDefault = isDefault
                    },
                    onCommitOrder = viewModel::reorderPresets,
                )
            }
        }
    }

    val target = pendingDefault
    if (target != null) {
        SetDefaultPresetDialog(
            preset = target,
            onConfirm = {
                viewModel.setDefault(target.id)
                pendingDefault = null
            },
            onDismiss = { pendingDefault = null },
        )
    }

    // 长按菜单
    if (longPressPreset != null) {
        val lp = longPressPreset!!
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { longPressPreset = null },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                com.nuttavern.ui.components.NutTavernSheetTitle(title = lp.name.ifBlank { "操作" })
                if (!longPressIsDefault) {
                    com.nuttavern.ui.components.NutTavernSelectableRow(
                        title = "设为默认", selected = false,
                        onClick = { pendingDefault = lp; longPressPreset = null },
                    )
                }
                com.nuttavern.ui.components.NutTavernSelectableRow(
                    title = "复制", selected = false,
                    onClick = {
                        viewModel.duplicate(lp.id)
                        longPressPreset = null
                    },
                )
                com.nuttavern.ui.components.NutTavernSelectableRow(
                    title = "导出", selected = false,
                    onClick = {
                        android.widget.Toast.makeText(context, "功能开发中", android.widget.Toast.LENGTH_SHORT).show()
                        longPressPreset = null
                    },
                )
                if (!lp.isBuiltInDefault) {
                    com.nuttavern.ui.components.NutTavernSelectableRow(
                        title = "删除", selected = false,
                        onClick = {
                            viewModel.delete(lp.id)
                            longPressPreset = null
                        },
                    )
                }
                Spacer(Modifier.padding(bottom = 16.dp))
            }
        }
    }
}

@Composable
private fun PresetSearchBar(value: String, onValueChange: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(percent = NutTavernShapeTokens.SearchBar),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Lucide.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.isEmpty()) {
                    Text(
                        text = "搜索预设",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PresetListBody(
    items: List<PresetViewModel.PresetListItem>,
    reorderable: Boolean,
    onEdit: (String) -> Unit,
    onLongPress: (Preset, isDefault: Boolean) -> Unit,
    onCommitOrder: (List<String>) -> Unit,
) {
    var localOrder by remember { mutableStateOf(items) }
    LaunchedEffect(items) {
        val localIds = localOrder.map { it.preset.id }.toSet()
        val upstreamIds = items.map { it.preset.id }.toSet()
        localOrder = if (localIds == upstreamIds) {
            val byId = items.associateBy { it.preset.id }
            localOrder.mapNotNull { byId[it.preset.id] }
        } else {
            items
        }
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localOrder = localOrder.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(localOrder, key = { it.preset.id }) { item ->
            ReorderableItem(state = reorderState, key = item.preset.id) { isDragging ->
                PresetCard(
                    preset = item.preset,
                    isDefault = item.isDefault,
                    elevated = isDragging,
                    onClick = { onEdit(item.preset.id) },
                    onLongClick = { onLongPress(item.preset, item.isDefault) },
                    dragHandle = {
                        if (reorderable) {
                            PresetDragHandle(
                                modifier = Modifier.draggableHandle(
                                    onDragStopped = { onCommitOrder(localOrder.map { it.preset.id }) },
                                ),
                            )
                        } else {
                            Spacer(Modifier.size(20.dp))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PresetListEmpty(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetDefaultPresetDialog(
    preset: Preset,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val displayName = preset.name.ifBlank { "未命名预设" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设为默认预设?") },
        text = {
            Text(
                "将「$displayName」设为默认预设。\n\n" +
                    "默认预设只影响新会话的初始预设;已有会话的预设不会改变。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确认设为默认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

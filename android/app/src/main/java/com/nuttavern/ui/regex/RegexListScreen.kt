package com.nuttavern.ui.regex

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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.FileUp
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Trash2
import com.nuttavern.ui.components.NutTavernShapeTokens
import com.nuttavern.ui.viewmodel.RegexScriptViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

internal const val NEW_REGEX_PLACEHOLDER_ID = "__new_regex__"
internal const val NEW_REGEX_GROUP_PLACEHOLDER_ID = "__new_group__"

/**
 * 用户级正则列表页。顶层是组 + 散规则混合列表。
 *
 * - 组行:点击进组内列表([onOpenRegexGroup]);右侧整组启用 Switch + 拖把手
 * - 散规则行:点击进编辑页([onOpenRegexDetail]);右侧独立启用 Switch + 拖把手
 * - 顶栏 "+" 下拉:新建规则组 / 新建正则规则
 * - 搜索过滤态下不允许拖动
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegexListScreen(
    onBack: () -> Unit,
    onOpenRegexDetail: (regexId: String) -> Unit,
    onOpenRegexGroup: (groupId: String) -> Unit,
    viewModel: RegexScriptViewModel = hiltViewModel(),
) {
    val topLevelItems by viewModel.topLevelItems.collectAsState()
    var query by remember { mutableStateOf("") }
    var showAddMenu by remember { mutableStateOf(false) }

    val isFiltering = query.isNotBlank()
    val filteredItems = remember(topLevelItems, query) {
        if (!isFiltering) topLevelItems
        else topLevelItems.filter { item ->
            val name = item.group?.name ?: item.orphan?.scriptName ?: ""
            name.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("正则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Lucide.Plus, contentDescription = "新建正则")
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("新建规则组") },
                                onClick = {
                                    showAddMenu = false
                                    onOpenRegexGroup(NEW_REGEX_GROUP_PLACEHOLDER_ID)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("新建正则") },
                                onClick = {
                                    showAddMenu = false
                                    onOpenRegexDetail(NEW_REGEX_PLACEHOLDER_ID)
                                },
                            )
                        }
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
            RegexSearchBar(value = query, onValueChange = { query = it })

            when {
                filteredItems.isEmpty() && isFiltering -> RegexListEmpty("没有找到匹配的规则")
                filteredItems.isEmpty() -> RegexListEmpty("暂无正则规则,点击右上角新建")
                else -> RegexTopLevelList(
                    items = filteredItems,
                    reorderable = !isFiltering,
                    onGroupClick = onOpenRegexGroup,
                    onOrphanClick = onOpenRegexDetail,
                    onToggleGroup = { id, enabled -> viewModel.toggleGroupEnabled(id, enabled) },
                    onToggleOrphan = { id, disabled -> viewModel.toggleOrphanEnabled(id, disabled) },
                    onDuplicateGroup = { id -> viewModel.duplicateGroup(id) },
                    onDuplicateOrphan = { id -> viewModel.duplicateOrphan(id) },
                    onDeleteGroup = { id -> viewModel.deleteGroup(id) },
                    onDeleteOrphan = { id -> viewModel.deleteOrphan(id) },
                    onCommitOrder = { orderedIds -> viewModel.reorderTopLevel(orderedIds) },
                )
            }
        }
    }
}

@Composable
private fun RegexSearchBar(value: String, onValueChange: (String) -> Unit) {
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
                        text = "搜索规则名",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegexTopLevelList(
    items: List<RegexScriptViewModel.TopLevelItem>,
    reorderable: Boolean,
    onGroupClick: (String) -> Unit,
    onOrphanClick: (String) -> Unit,
    onToggleGroup: (id: String, enabled: Boolean) -> Unit,
    onToggleOrphan: (id: String, disabled: Boolean) -> Unit,
    onDuplicateGroup: (id: String) -> Unit,
    onDuplicateOrphan: (id: String) -> Unit,
    onDeleteGroup: (id: String) -> Unit,
    onDeleteOrphan: (id: String) -> Unit,
    onCommitOrder: (List<String>) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var longPressTarget by remember { mutableStateOf<LongPressTarget?>(null) }
    var localOrder by remember { mutableStateOf(items) }
    LaunchedEffect(items) {
        val localIds = localOrder.map { it.id }.toSet()
        val upstreamIds = items.map { it.id }.toSet()
        localOrder = if (localIds == upstreamIds) {
            val byId = items.associateBy { it.id }
            localOrder.mapNotNull { byId[it.id] }
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
        items(localOrder, key = { it.id }) { item ->
            ReorderableItem(state = reorderState, key = item.id) { isDragging ->
                if (item.isGroup) {
                    val group = item.group ?: return@ReorderableItem
                    com.nuttavern.ui.components.NutTavernEntityCard(
                        title = group.name,
                        titleFallback = "未命名规则组",
                        subtitle = if (group.scripts.isEmpty()) "暂无规则" else "共 ${group.scripts.size} 条规则",
                        elevated = isDragging,
                        onClick = { onGroupClick(group.id) },
                        onLongClick = { longPressTarget = LongPressTarget.Group(group.id, group.name, group.enabled) },
                        trailing = {
                            if (group.enabled) {
                                com.nuttavern.ui.components.NutTavernEntityStatusPill(
                                    label = "启用",
                                    container = MaterialTheme.colorScheme.primaryContainer,
                                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            if (reorderable) {
                                com.nuttavern.ui.components.NutTavernEntityDragHandle(
                                    modifier = Modifier.draggableHandle(
                                        onDragStopped = { onCommitOrder(localOrder.map { it.id }) },
                                    ),
                                )
                            } else {
                                Spacer(Modifier.size(20.dp))
                            }
                        },
                    )
                } else {
                    val script = item.orphan ?: return@ReorderableItem
                    com.nuttavern.ui.components.NutTavernEntityCard(
                        title = script.scriptName,
                        titleFallback = "未命名规则",
                        subtitle = regexScriptSubtitle(script),
                        elevated = isDragging,
                        onClick = { onOrphanClick(script.id) },
                        onLongClick = { longPressTarget = LongPressTarget.Orphan(script.id, script.scriptName, !script.disabled) },
                        trailing = {
                            if (!script.disabled) {
                                com.nuttavern.ui.components.NutTavernEntityStatusPill(
                                    label = "启用",
                                    container = MaterialTheme.colorScheme.primaryContainer,
                                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            if (reorderable) {
                                com.nuttavern.ui.components.NutTavernEntityDragHandle(
                                    modifier = Modifier.draggableHandle(
                                        onDragStopped = { onCommitOrder(localOrder.map { it.id }) },
                                    ),
                                )
                            } else {
                                Spacer(Modifier.size(20.dp))
                            }
                        },
                    )
        }
    }

    // 长按菜单 Sheet
    val target = longPressTarget
    if (target != null) {
        com.nuttavern.ui.components.NutTavernEntityActionsSheet(
            title = target.name,
            actions = buildList {
                add(com.nuttavern.ui.components.EntityAction(
                    icon = Lucide.Check,
                    title = if (target.enabled) "设为默认关闭" else "设为默认开启",
                    onClick = {
                        when (target) {
                            is LongPressTarget.Group -> onToggleGroup(target.id, !target.enabled)
                            is LongPressTarget.Orphan -> onToggleOrphan(target.id, target.enabled)
                        }
                    },
                ))
                add(com.nuttavern.ui.components.EntityAction(
                    icon = Lucide.Copy,
                    title = "复制",
                    onClick = {
                        when (target) {
                            is LongPressTarget.Group -> onDuplicateGroup(target.id)
                            is LongPressTarget.Orphan -> onDuplicateOrphan(target.id)
                        }
                    },
                ))
                add(com.nuttavern.ui.components.EntityAction(
                    icon = Lucide.FileUp,
                    title = "导出",
                    onClick = {
                        android.widget.Toast.makeText(context, "功能开发中", android.widget.Toast.LENGTH_SHORT).show()
                    },
                ))
                add(com.nuttavern.ui.components.EntityAction(
                    icon = Lucide.Trash2,
                    title = "删除",
                    destructive = true,
                    onClick = {
                        when (target) {
                            is LongPressTarget.Group -> onDeleteGroup(target.id)
                            is LongPressTarget.Orphan -> onDeleteOrphan(target.id)
                        }
                    },
                ))
            },
            onDismiss = { longPressTarget = null },
        )
    }
}
        item(key = "hint") {
            Text(
                text = "长按卡片进行更多操作",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private sealed class LongPressTarget(val id: String, val name: String, val enabled: Boolean) {
    class Group(id: String, name: String, enabled: Boolean) : LongPressTarget(id, name, enabled)
    class Orphan(id: String, name: String, enabled: Boolean) : LongPressTarget(id, name, enabled)
}

/**
 * 规则副标:用规则的执行时机 + 生效范围拼装,而不是塞原始 regex 字符串。
 * 用户更需要"这条规则什么时候、对什么生效"这层信息。
 */
internal fun regexScriptSubtitle(script: com.nuttavern.data.regex.RegexScript): String {
    val timing = com.nuttavern.data.regex.RegexExecutionTiming.from(script)
    val timingText = when (timing) {
        com.nuttavern.data.regex.RegexExecutionTiming.AFTER_GENERATION -> "接收消息时"
        com.nuttavern.data.regex.RegexExecutionTiming.AFTER_GENERATION_AND_EDIT -> "接收和编辑消息时"
        com.nuttavern.data.regex.RegexExecutionTiming.DISPLAY_ONLY -> "显示消息时"
        com.nuttavern.data.regex.RegexExecutionTiming.PROMPT_ONLY -> "发送消息时"
        com.nuttavern.data.regex.RegexExecutionTiming.DISPLAY_AND_PROMPT -> "显示和发送时"
        com.nuttavern.data.regex.RegexExecutionTiming.DISPLAY_AND_EDIT -> "显示和编辑时"
        com.nuttavern.data.regex.RegexExecutionTiming.PROMPT_AND_EDIT -> "发送和编辑时"
        com.nuttavern.data.regex.RegexExecutionTiming.DISPLAY_PROMPT_AND_EDIT -> "显示、发送和编辑时"
    }
    val placements = script.placement.mapNotNull { value ->
        when (value) {
            com.nuttavern.data.regex.RegexPlacement.USER_INPUT.value -> "用户输入"
            com.nuttavern.data.regex.RegexPlacement.AI_OUTPUT.value -> "角色回复"
            else -> null
        }
    }
    return if (placements.isEmpty()) timingText
    else "${placements.joinToString("、")} · $timingText"
}

@Composable
private fun RegexListEmpty(text: String) {
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

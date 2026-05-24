package com.nuttavern.ui.regex

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Plus
import com.nuttavern.data.regex.RegexGroup
import com.nuttavern.data.regex.RegexScript
import com.nuttavern.ui.viewmodel.RegexScriptViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 组内规则列表页。
 *
 * - 顶栏显示组名,右侧溢出菜单:重命名 / 删除组
 * - 列表:组内规则卡片(对齐顶层散规则卡片视觉,**无独立 Switch**,组本身是启用单位)
 * - 顶栏 "+" 新建组内规则(走 NEW_REGEX_PLACEHOLDER_ID 路径,保存才落库)
 *
 * 新建组([NEW_REGEX_GROUP_PLACEHOLDER_ID])时先在此页输入组名,保存后由路由把当前页**替换**
 * 成真实组页,用户直接进入新组(不是回列表再点)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegexGroupScreen(
    groupId: String,
    onBack: () -> Unit,
    onNavigateToGroup: (groupId: String) -> Unit,
    onOpenScriptDetail: (groupId: String, scriptId: String) -> Unit,
    viewModel: RegexScriptViewModel = hiltViewModel(),
) {
    if (groupId == NEW_REGEX_GROUP_PLACEHOLDER_ID) {
        RegexGroupCreateScreen(
            onCreated = { newGroupId -> onNavigateToGroup(newGroupId) },
            onBack = onBack,
            viewModel = viewModel,
        )
        return
    }

    val group by remember(groupId, viewModel) {
        viewModel.findGroupById(groupId)
    }.collectAsState(initial = null)

    // 区分"加载中"和"已删除":只有 Flow 真实 emit 过非 null 值之后再次变 null,才判定为"已删除"。
    //
    // 不能用 rememberSaveable + 简单的"hasEmitted 推断",原因:从组内规则编辑页 popBackStack 回到本页时
    // saved 状态恢复 hasEmitted = true,而 collectAsState 的 initial = null 还会持续一帧,
    // 这会被误判成"已删除"立刻再 onBack 一次,直接越级回到 RegexList。
    var hasEmitted by remember(groupId) { mutableStateOf(false) }
    LaunchedEffect(group) {
        if (group != null) hasEmitted = true
    }

    val currentGroup = group
    if (currentGroup == null) {
        if (hasEmitted) {
            // 组在本页打开后被删除(或外部因素清理)→ 自动返回。
            LaunchedEffect(Unit) { onBack() }
        }
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (hasEmitted) "规则组已删除" else "加载中") },
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
        ) { _ -> }
        return
    }

    RegexGroupContent(
        group = currentGroup,
        onBack = onBack,
        onOpenScriptDetail = { scriptId -> onOpenScriptDetail(groupId, scriptId) },
        onRename = { name -> viewModel.renameGroup(groupId, name) },
        onDeleteGroup = {
            viewModel.deleteGroup(groupId)
            onBack()
        },
        onAddScript = {
            // 走占位 id,在编辑页保存才落库;避免"添加按钮 → 列表残留空规则"。
            onOpenScriptDetail(groupId, NEW_REGEX_PLACEHOLDER_ID)
        },
        onReorder = { orderedIds -> viewModel.reorderScriptsInGroup(groupId, orderedIds) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegexGroupCreateScreen(
    onCreated: (groupId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: RegexScriptViewModel,
) {
    var name by rememberSaveable { mutableStateOf("") }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("新建规则组") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val group = viewModel.newGroup().copy(name = name.trim())
                            viewModel.upsertGroup(group)
                            onCreated(group.id)
                        },
                        enabled = name.isNotBlank(),
                    ) { Text("创建") }
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("规则组名称") },
                placeholder = { Text("例如:格式处理") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegexGroupContent(
    group: RegexGroup,
    onBack: () -> Unit,
    onOpenScriptDetail: (scriptId: String) -> Unit,
    onRename: (String) -> Unit,
    onDeleteGroup: () -> Unit,
    onAddScript: () -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = group.name.ifBlank { "未命名规则组" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onAddScript) {
                        Icon(Lucide.Plus, contentDescription = "新建规则")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Lucide.EllipsisVertical, contentDescription = "更多操作")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("重命名规则组") },
                                onClick = {
                                    showMenu = false
                                    showRenameDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "删除规则组",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
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
        if (group.scripts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(top = 48.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = "组内还没有规则,点 + 新建",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            GroupScriptList(
                scripts = group.scripts,
                contentPadding = padding,
                onScriptClick = onOpenScriptDetail,
                onCommitOrder = onReorder,
            )
        }
    }

    if (showRenameDialog) {
        RenameGroupDialog(
            currentName = group.name,
            onConfirm = { newName ->
                onRename(newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除规则组?") },
            text = { Text("组内的所有规则都将被删除,此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteGroup()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun GroupScriptList(
    scripts: List<RegexScript>,
    contentPadding: PaddingValues,
    onScriptClick: (scriptId: String) -> Unit,
    onCommitOrder: (List<String>) -> Unit,
) {
    var localOrder by remember { mutableStateOf(scripts) }
    LaunchedEffect(scripts) {
        val localIds = localOrder.map { it.id }.toSet()
        val upstreamIds = scripts.map { it.id }.toSet()
        localOrder = if (localIds == upstreamIds) {
            val byId = scripts.associateBy { it.id }
            localOrder.mapNotNull { byId[it.id] }
        } else {
            scripts
        }
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localOrder = localOrder.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(localOrder, key = { it.id }) { script ->
            ReorderableItem(state = reorderState, key = script.id) { isDragging ->
                // 复用顶层散规则卡片,关闭 Switch:组内规则没有独立启用开关,组本身是启用单位。
                RegexScriptCard(
                    name = script.scriptName,
                    subtitle = regexScriptSubtitle(script),
                    enabled = true,
                    showSwitch = false,
                    elevated = isDragging,
                    onEdit = { onScriptClick(script.id) },
                    onToggleEnabled = {},
                    dragHandle = {
                        Icon(
                            imageVector = Lucide.GripVertical,
                            contentDescription = "拖动排序",
                            modifier = Modifier
                                .size(20.dp)
                                .draggableHandle(
                                    onDragStopped = {
                                        onCommitOrder(localOrder.map { it.id })
                                    },
                                ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun RenameGroupDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名规则组") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("规则组名称") },
                placeholder = { Text("输入规则组名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

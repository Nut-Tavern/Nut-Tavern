package com.nuttavern.ui.lorebook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.FileUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.ui.components.NutTavernEntityCard
import com.nuttavern.ui.components.NutTavernEntityDragHandle
import com.nuttavern.ui.components.NutTavernEntityEditIconButton
import com.nuttavern.ui.components.NutTavernEntitySwitch
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow
import com.nuttavern.ui.components.NutTavernLabeledTextField
import com.nuttavern.ui.components.NutTavernNumericField
import com.nuttavern.ui.components.NumericParser
import com.nuttavern.ui.viewmodel.LorebookViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 世界书详情页(二级页)。基于预设编辑页的二分 Tab 结构:
 *
 * - Tab 0 "条目":条目列表(编辑按钮 + Switch + 拖把手)+ "+" 新建条目
 * - Tab 1 "设置":世界书全局设置 + 重命名 + 删除
 *
 * 顶栏:返回 + "编辑世界书" + 保存按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LorebookDetailScreen(
    lorebookId: String,
    onBack: () -> Unit,
    onEditEntry: (lorebookId: String, entryUid: Int) -> Unit,
    viewModel: LorebookViewModel = hiltViewModel(),
) {
    val lorebook by remember(lorebookId, viewModel) {
        viewModel.findById(lorebookId)
    }.collectAsState(initial = null)

    var hasEmitted by remember(lorebookId) { mutableStateOf(false) }
    LaunchedEffect(lorebook) {
        if (lorebook != null) hasEmitted = true
    }

    val currentBook = lorebook
    if (currentBook == null) {
        if (hasEmitted) {
            LaunchedEffect(Unit) { onBack() }
        }
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (hasEmitted) "世界书已删除" else "加载中") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, "返回") }
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

    LorebookDetailContent(
        initial = currentBook,
        onBack = onBack,
        onSave = { viewModel.upsert(it) },
        onDelete = { viewModel.delete(lorebookId); onBack() },
        onEditEntry = { uid -> onEditEntry(lorebookId, uid) },
        onAddEntry = {
            val entry = viewModel.newEntry(currentBook)
            viewModel.upsertEntry(lorebookId, entry, currentBook.entries)
            onEditEntry(lorebookId, entry.uid)
        },
        onToggleEntry = { uid, disabled ->
            viewModel.toggleEntryEnabled(lorebookId, uid, disabled, currentBook.entries)
        },
        onReorderEntries = { ordered ->
            viewModel.reorderEntries(lorebookId, ordered)
        },
        onDuplicateEntry = { uid ->
            viewModel.duplicateEntry(lorebookId, uid, currentBook.entries)
        },
        onDeleteEntry = { uid ->
            viewModel.deleteEntry(lorebookId, uid, currentBook.entries)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LorebookDetailContent(
    initial: Lorebook,
    onBack: () -> Unit,
    onSave: (Lorebook) -> Unit,
    onDelete: () -> Unit,
    onEditEntry: (entryUid: Int) -> Unit,
    onAddEntry: () -> Unit,
    onToggleEntry: (uid: Int, disabled: Boolean) -> Unit,
    onReorderEntries: (List<LorebookEntry>) -> Unit,
    onDuplicateEntry: (uid: Int) -> Unit,
    onDeleteEntry: (uid: Int) -> Unit,
) {
    // 设置 Tab 的草稿(名称 + 全局设置),条目操作即时生效不走草稿
    var draft by remember(initial.id) { mutableStateOf(initial) }
    // 当外部 entries 变化时同步到 draft(条目操作即时落库,draft 只管设置字段)
    LaunchedEffect(initial) { draft = draft.copy(entries = initial.entries) }

    val settingsDirty = draft.name != initial.name ||
        draft.description != initial.description ||
        draft.scanDepth != initial.scanDepth ||
        draft.tokenBudget != initial.tokenBudget ||
        draft.budgetCap != initial.budgetCap ||
        draft.recursiveScanning != initial.recursiveScanning ||
        draft.caseSensitive != initial.caseSensitive ||
        draft.matchWholeWords != initial.matchWholeWords ||
        draft.maxRecursionSteps != initial.maxRecursionSteps ||
        draft.minActivations != initial.minActivations ||
        draft.minActivationsDepthMax != initial.minActivationsDepthMax ||
        draft.includeNames != initial.includeNames ||
        draft.overflowAlert != initial.overflowAlert ||
        draft.useGroupScoring != initial.useGroupScoring ||
        draft.characterStrategy != initial.characterStrategy

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val triggerBack: () -> Unit = {
        if (settingsDirty) showUnsavedDialog = true else onBack()
    }
    BackHandler { triggerBack() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("编辑世界书") },
                navigationIcon = {
                    IconButton(onClick = triggerBack) { Icon(Lucide.ArrowLeft, "返回") }
                },
                actions = {
                    if (selectedTab == 0) {
                        IconButton(onClick = onAddEntry) {
                            Icon(Lucide.Plus, contentDescription = "新建条目")
                        }
                    }
                    if (settingsDirty) {
                        TextButton(onClick = { onSave(draft) }) { Text("保存") }
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
                .padding(padding),
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("条目") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("设置") })
            }

            when (selectedTab) {
                0 -> EntriesTab(
                    entries = initial.entries,
                    onEditEntry = onEditEntry,
                    onToggleEntry = onToggleEntry,
                    onReorderEntries = onReorderEntries,
                    onDuplicateEntry = onDuplicateEntry,
                    onDeleteEntry = onDeleteEntry,
                )
                1 -> SettingsTab(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onShowDeleteDialog = { showDeleteDialog = true },
                )
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("有未保存的修改") },
            text = { Text("世界书设置有修改,退出会丢失。") },
            confirmButton = {
                TextButton(onClick = { showUnsavedDialog = false; onSave(draft); onBack() }) {
                    Text("保存并退出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedDialog = false; onBack() }) { Text("不保存退出") }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除世界书?") },
            text = { Text("「${draft.name.ifBlank { "未命名世界书" }}」及其所有条目将被永久删除。") },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteDialog = false; onDelete() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

// region 条目 Tab

@Composable
private fun EntriesTab(
    entries: List<LorebookEntry>,
    onEditEntry: (uid: Int) -> Unit,
    onToggleEntry: (uid: Int, disabled: Boolean) -> Unit,
    onReorderEntries: (List<LorebookEntry>) -> Unit,
    onDuplicateEntry: (uid: Int) -> Unit,
    onDeleteEntry: (uid: Int) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var longPressEntry by remember { mutableStateOf<LorebookEntry?>(null) }

    if (entries.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = "暂无条目,点右上角 + 新建",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    var localOrder by remember { mutableStateOf(entries) }
    LaunchedEffect(entries) {
        val localUids = localOrder.map { it.uid }.toSet()
        val upstreamUids = entries.map { it.uid }.toSet()
        localOrder = if (localUids == upstreamUids) {
            val byUid = entries.associateBy { it.uid }
            localOrder.mapNotNull { byUid[it.uid] }
        } else {
            entries
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
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(localOrder, key = { it.uid }) { entry ->
            ReorderableItem(state = reorderState, key = entry.uid) { isDragging ->
                NutTavernEntityCard(
                    title = entry.comment.ifBlank { "未命名条目" },
                    subtitle = entrySubtitle(entry),
                    elevated = isDragging,
                    onClick = { onEditEntry(entry.uid) },
                    onLongClick = { longPressEntry = entry },
                    trailing = {
                        NutTavernEntitySwitch(
                            checked = !entry.disable,
                            onCheckedChange = { enabled -> onToggleEntry(entry.uid, !enabled) },
                        )
                        NutTavernEntityDragHandle(
                            modifier = Modifier.draggableHandle(
                                onDragStopped = { onReorderEntries(localOrder) },
                            ),
                        )
                    },
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

    // 长按菜单
    if (longPressEntry != null) {
        val entry = longPressEntry!!
        com.nuttavern.ui.components.NutTavernEntityActionsSheet(
            title = entry.comment.ifBlank { "未命名条目" },
            actions = listOf(
                com.nuttavern.ui.components.EntityAction(
                    icon = Lucide.Copy,
                    title = "复制",
                    onClick = { onDuplicateEntry(entry.uid) },
                ),
                com.nuttavern.ui.components.EntityAction(
                    icon = Lucide.Trash2,
                    title = "删除",
                    destructive = true,
                    onClick = { onDeleteEntry(entry.uid) },
                ),
            ),
            onDismiss = { longPressEntry = null },
        )
    }
}

private fun entrySubtitle(entry: LorebookEntry): String {
    if (entry.constant) return "常驻"
    val keys = entry.key.take(3).joinToString(", ")
    return keys.ifBlank { "无关键词" }
}

// endregion

// region 设置 Tab

private enum class MutualExclusionTarget { MAX_RECURSION, MIN_ACTIVATIONS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab(
    draft: Lorebook,
    onDraftChange: (Lorebook) -> Unit,
    onShowDeleteDialog: () -> Unit,
) {
    var pendingMutualExclusion by remember { mutableStateOf<Pair<MutualExclusionTarget, Int>?>(null) }
    var showStrategySheet by remember { mutableStateOf(false) }

    // 互斥确认弹窗
    pendingMutualExclusion?.let { (target, value) ->
        val otherLabel = when (target) {
            MutualExclusionTarget.MAX_RECURSION -> "最少激活条目数"
            MutualExclusionTarget.MIN_ACTIVATIONS -> "最大递归步数"
        }
        AlertDialog(
            onDismissRequest = { pendingMutualExclusion = null },
            title = { Text("互斥配置") },
            text = { Text("此设置与「$otherLabel」互斥,确认修改将把「$otherLabel」重置为 0。") },
            confirmButton = {
                TextButton(onClick = {
                    when (target) {
                        MutualExclusionTarget.MAX_RECURSION ->
                            onDraftChange(draft.copy(maxRecursionSteps = value, minActivations = 0, minActivationsDepthMax = 0))
                        MutualExclusionTarget.MIN_ACTIVATIONS ->
                            onDraftChange(draft.copy(minActivations = value, maxRecursionSteps = 0))
                    }
                    pendingMutualExclusion = null
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { pendingMutualExclusion = null }) { Text("取消") }
            },
        )
    }

    // 合并策略选择 Sheet
    if (showStrategySheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showStrategySheet = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
            ) {
                com.nuttavern.ui.components.NutTavernSheetTitle(title = "合并策略")
                NutTavernGroupSection {
                    com.nuttavern.ui.components.NutTavernSelectableRow(
                        title = "角色优先",
                        subtitle = "角色条目排前面,预算不够时角色条目优先注入",
                        selected = draft.characterStrategy == com.nuttavern.data.lorebook.WiCharacterStrategy.CHARACTER_FIRST,
                        onClick = {
                            onDraftChange(draft.copy(characterStrategy = com.nuttavern.data.lorebook.WiCharacterStrategy.CHARACTER_FIRST))
                            showStrategySheet = false
                        },
                    )
                    NutTavernGroupDivider()
                    com.nuttavern.ui.components.NutTavernSelectableRow(
                        title = "全局优先",
                        subtitle = "全局条目排前面",
                        selected = draft.characterStrategy == com.nuttavern.data.lorebook.WiCharacterStrategy.GLOBAL_FIRST,
                        onClick = {
                            onDraftChange(draft.copy(characterStrategy = com.nuttavern.data.lorebook.WiCharacterStrategy.GLOBAL_FIRST))
                            showStrategySheet = false
                        },
                    )
                    NutTavernGroupDivider()
                    com.nuttavern.ui.components.NutTavernSelectableRow(
                        title = "均匀混合",
                        subtitle = "全局和角色条目统一按 order 排序",
                        selected = draft.characterStrategy == com.nuttavern.data.lorebook.WiCharacterStrategy.EVENLY,
                        onClick = {
                            onDraftChange(draft.copy(characterStrategy = com.nuttavern.data.lorebook.WiCharacterStrategy.EVENLY))
                            showStrategySheet = false
                        },
                    )
                }
            }
        }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
    ) {
        item(key = "basic") {
            NutTavernGroupSection {
                NutTavernLabeledTextField(
                    label = "名称",
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it)) },
                    placeholder = "世界书名称",
                    singleLine = true,
                )
                NutTavernGroupDivider()
                NutTavernLabeledTextField(
                    label = "描述",
                    value = draft.description,
                    onValueChange = { onDraftChange(draft.copy(description = it)) },
                    placeholder = "可选,仅本地展示",
                    minLines = 1,
                )
            }
        }

        item(key = "scan-settings") {
            NutTavernGroupSection {
                NutTavernNumericField(
                    label = "扫描深度",
                    value = draft.scanDepth,
                    onValueChange = { it?.let { v -> onDraftChange(draft.copy(scanDepth = v)) } },
                    parser = NumericParser.IntParser,
                    helperText = "向上看多少条消息进行关键词匹配",
                    min = 0,
                    max = 1000,
                )
                NutTavernGroupDivider()
                NutTavernNumericField(
                    label = "Token 预算",
                    value = draft.tokenBudget,
                    onValueChange = { it?.let { v -> onDraftChange(draft.copy(tokenBudget = v)) } },
                    parser = NumericParser.IntParser,
                    helperText = "所有激活条目的总 token 上限(百分比)",
                    min = 0,
                    max = 100,
                )
                NutTavernGroupDivider()
                NutTavernNumericField(
                    label = "预算硬上限",
                    value = draft.budgetCap,
                    onValueChange = { it?.let { v -> onDraftChange(draft.copy(budgetCap = v)) } },
                    parser = NumericParser.IntParser,
                    helperText = "0 = 不限制;非零时取 min(百分比预算, 此值)",
                    min = 0,
                )
                NutTavernGroupDivider()
                NutTavernNumericField(
                    label = "最大递归步数",
                    value = draft.maxRecursionSteps,
                    onValueChange = { newValue ->
                        val v = newValue ?: return@NutTavernNumericField
                        if (v > 0 && draft.minActivations > 0) {
                            pendingMutualExclusion = MutualExclusionTarget.MAX_RECURSION to v
                        } else {
                            onDraftChange(draft.copy(maxRecursionSteps = v))
                        }
                    },
                    parser = NumericParser.IntParser,
                    helperText = "0 = 不限制递归深度",
                    min = 0,
                )
                NutTavernGroupDivider()
                NutTavernNumericField(
                    label = "最少激活条目数",
                    value = draft.minActivations,
                    onValueChange = { newValue ->
                        val v = newValue ?: return@NutTavernNumericField
                        if (v > 0 && draft.maxRecursionSteps > 0) {
                            pendingMutualExclusion = MutualExclusionTarget.MIN_ACTIVATIONS to v
                        } else {
                            onDraftChange(draft.copy(minActivations = v))
                        }
                    },
                    parser = NumericParser.IntParser,
                    helperText = "不够则扩大扫描深度继续找;0 = 禁用",
                    min = 0,
                )
                if (draft.minActivations > 0) {
                    NutTavernGroupDivider()
                    NutTavernNumericField(
                        label = "最少激活扩展深度上限",
                        value = draft.minActivationsDepthMax,
                        onValueChange = { it?.let { v -> onDraftChange(draft.copy(minActivationsDepthMax = v)) } },
                        parser = NumericParser.IntParser,
                        helperText = "0 = 不限制,只受聊天长度约束",
                        min = 0,
                    )
                }
            }
        }

        item(key = "strategy") {
            NutTavernGroupSection {
                NutTavernIconRow(
                    icon = Lucide.Boxes,
                    title = "合并策略",
                    subtitle = when (draft.characterStrategy) {
                        com.nuttavern.data.lorebook.WiCharacterStrategy.EVENLY -> "均匀混合"
                        com.nuttavern.data.lorebook.WiCharacterStrategy.CHARACTER_FIRST -> "角色优先"
                        com.nuttavern.data.lorebook.WiCharacterStrategy.GLOBAL_FIRST -> "全局优先"
                        else -> "角色优先"
                    },
                    onClick = { showStrategySheet = true },
                    showTrailingChevron = true,
                )
            }
        }

        item(key = "flags") {
            NutTavernGroupSection {
                SwitchRow(
                    label = "递归扫描",
                    subtitle = "激活条目的内容可以触发其他条目",
                    checked = draft.recursiveScanning,
                    onCheckedChange = { onDraftChange(draft.copy(recursiveScanning = it)) },
                )
                NutTavernGroupDivider()
                SwitchRow(
                    label = "大小写敏感",
                    subtitle = "关键词匹配区分大小写",
                    checked = draft.caseSensitive,
                    onCheckedChange = { onDraftChange(draft.copy(caseSensitive = it)) },
                )
                NutTavernGroupDivider()
                SwitchRow(
                    label = "整词匹配",
                    subtitle = "关键词必须是完整单词,不匹配子串",
                    checked = draft.matchWholeWords,
                    onCheckedChange = { onDraftChange(draft.copy(matchWholeWords = it)) },
                )
                NutTavernGroupDivider()
                SwitchRow(
                    label = "包含发言者名称",
                    subtitle = "扫描缓冲区每条消息前加\"Name: \"",
                    checked = draft.includeNames,
                    onCheckedChange = { onDraftChange(draft.copy(includeNames = it)) },
                )
                NutTavernGroupDivider()
                SwitchRow(
                    label = "互斥组评分模式",
                    subtitle = "同组条目按关键词命中数竞争,得分低的淘汰",
                    checked = draft.useGroupScoring,
                    onCheckedChange = { onDraftChange(draft.copy(useGroupScoring = it)) },
                )
            }
        }

        item(key = "delete") {
            NutTavernGroupSection {
                NutTavernIconRow(
                    icon = Lucide.Trash2,
                    title = "删除世界书",
                    subtitle = "删除后不可恢复",
                    destructive = true,
                    onClick = onShowDeleteDialog,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// endregion

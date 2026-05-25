package com.nuttavern.ui.lorebook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.data.lorebook.SelectiveLogic
import com.nuttavern.data.lorebook.WiPosition
import com.nuttavern.data.lorebook.WiRole
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow
import com.nuttavern.ui.components.NutTavernLabeledTextField
import com.nuttavern.ui.components.NutTavernNumericField
import com.nuttavern.ui.components.NutTavernSelectableRow
import com.nuttavern.ui.components.NutTavernSheetTitle
import com.nuttavern.ui.components.NumericParser
import com.nuttavern.ui.viewmodel.LorebookViewModel

/**
 * 世界书条目编辑页(三级页)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LorebookEntryEditScreen(
    lorebookId: String,
    entryUid: Int,
    onBack: () -> Unit,
    viewModel: LorebookViewModel = hiltViewModel(),
) {
    val lorebook by remember(lorebookId, viewModel) {
        viewModel.findById(lorebookId)
    }.collectAsState(initial = null)

    val currentBook = lorebook ?: return
    val initial = currentBook.entries.find { it.uid == entryUid } ?: run {
        onBack()
        return
    }

    var draft by remember(entryUid) { mutableStateOf(initial) }
    val isDirty = draft != initial
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    val triggerBack: () -> Unit = {
        if (isDirty) showDiscardDialog = true else onBack()
    }
    BackHandler { triggerBack() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("编辑条目") },
                navigationIcon = {
                    IconButton(onClick = triggerBack) { Icon(Lucide.ArrowLeft, "返回") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.upsertEntry(lorebookId, draft, currentBook.entries)
                            onBack()
                        },
                        enabled = isDirty,
                    ) { Text("保存") }
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
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
        ) {
            // 基础
            item(key = "basic") {
                NutTavernGroupSection {
                    NutTavernLabeledTextField(
                        label = "标题",
                        value = draft.comment,
                        onValueChange = { draft = draft.copy(comment = it) },
                        placeholder = "条目名称,仅用于列表显示",
                        singleLine = true,
                    )
                    NutTavernGroupDivider()
                    NutTavernLabeledTextField(
                        label = "内容",
                        value = draft.content,
                        onValueChange = { draft = draft.copy(content = it) },
                        placeholder = "激活后注入到 prompt 的文本",
                        minLines = 4,
                    )
                    NutTavernGroupDivider()
                    SwitchRow(
                        label = "常驻",
                        subtitle = "不需要关键词触发,始终注入",
                        checked = draft.constant,
                        onCheckedChange = { draft = draft.copy(constant = it) },
                    )
                }
            }

            // 关键词
            item(key = "keywords") {
                NutTavernGroupSection {
                    NutTavernLabeledTextField(
                        label = "主关键词",
                        value = draft.key.joinToString(", "),
                        onValueChange = { raw ->
                            draft = draft.copy(key = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                        },
                        placeholder = "逗号分隔,任一命中即触发",
                        supportingText = "当前 ${draft.key.size} 个关键词",
                    )
                    NutTavernGroupDivider()
                    NutTavernLabeledTextField(
                        label = "次要关键词",
                        value = draft.keysecondary.joinToString(", "),
                        onValueChange = { raw ->
                            draft = draft.copy(keysecondary = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                        },
                        placeholder = "逗号分隔,配合下方逻辑使用",
                    )
                    NutTavernGroupDivider()
                    EnumRow(
                        label = "次要关键词逻辑",
                        value = draft.selectiveLogic,
                        options = listOf(
                            SelectiveLogic.AND_ANY to "任一命中",
                            SelectiveLogic.NOT_ALL to "不全部命中",
                            SelectiveLogic.NOT_ANY to "全部不命中",
                            SelectiveLogic.AND_ALL to "全部命中",
                        ),
                        onSelect = { draft = draft.copy(selectiveLogic = it) },
                    )
                }
            }

            // 注入控制
            item(key = "injection") {
                NutTavernGroupSection {
                    EnumRow(
                        label = "注入位置",
                        value = draft.position,
                        options = listOf(
                            WiPosition.BEFORE to "角色描述之前",
                            WiPosition.AFTER to "角色描述之后",
                            WiPosition.AN_TOP to "Author's Note 之前",
                            WiPosition.AN_BOTTOM to "Author's Note 之后",
                            WiPosition.AT_DEPTH to "按深度插入",
                            WiPosition.EM_TOP to "示例消息之前",
                            WiPosition.EM_BOTTOM to "示例消息之后",
                        ),
                        onSelect = { draft = draft.copy(position = it) },
                    )
                    if (draft.position == WiPosition.AT_DEPTH) {
                        NutTavernGroupDivider()
                        NutTavernNumericField(
                            label = "深度",
                            value = draft.depth,
                            onValueChange = { it?.let { v -> draft = draft.copy(depth = v) } },
                            parser = NumericParser.IntParser,
                            helperText = "倒数第 N 条消息之前插入",
                            min = 0,
                            max = 1000,
                        )
                        NutTavernGroupDivider()
                        EnumRow(
                            label = "角色",
                            value = draft.role,
                            options = listOf(
                                WiRole.SYSTEM to "系统",
                                WiRole.USER to "用户",
                                WiRole.ASSISTANT to "助手",
                            ),
                            onSelect = { draft = draft.copy(role = it) },
                        )
                    }
                    NutTavernGroupDivider()
                    NutTavernNumericField(
                        label = "排序权重",
                        value = draft.order,
                        onValueChange = { it?.let { v -> draft = draft.copy(order = v) } },
                        parser = NumericParser.IntParser,
                        helperText = "数字越大越先注入,默认 100",
                        min = 0,
                    )
                }
            }

            // 高级(折叠)
            item(key = "advanced-header") {
                NutTavernExpandableHeader(
                    title = "高级设置",
                    expanded = advancedExpanded,
                    onClick = { advancedExpanded = !advancedExpanded },
                )
            }
            if (advancedExpanded) {
                item(key = "advanced") {
                    NutTavernGroupSection {
                        NutTavernLabeledTextField(
                            label = "互斥组",
                            value = draft.group,
                            onValueChange = { draft = draft.copy(group = it) },
                            placeholder = "同组内只激活权重最高的一个",
                            singleLine = true,
                        )
                        NutTavernGroupDivider()
                        NutTavernNumericField(
                            label = "组内权重",
                            value = draft.groupWeight,
                            onValueChange = { it?.let { v -> draft = draft.copy(groupWeight = v) } },
                            parser = NumericParser.IntParser,
                            min = 0,
                        )
                        NutTavernGroupDivider()
                        SwitchRow(
                            label = "组内强制激活",
                            subtitle = "即使权重低也强制激活",
                            checked = draft.groupOverride,
                            onCheckedChange = { draft = draft.copy(groupOverride = it) },
                        )
                        NutTavernGroupDivider()
                        NutTavernNumericField(
                            label = "激活概率",
                            value = draft.probability,
                            onValueChange = { it?.let { v -> draft = draft.copy(probability = v) } },
                            parser = NumericParser.IntParser,
                            helperText = "0-100,100 = 必定激活",
                            min = 0,
                            max = 100,
                        )
                        NutTavernGroupDivider()
                        SwitchRow(
                            label = "启用概率判断",
                            checked = draft.useProbability,
                            onCheckedChange = { draft = draft.copy(useProbability = it) },
                        )
                        NutTavernGroupDivider()
                        SwitchRow(
                            label = "忽略 Token 预算",
                            subtitle = "即使超出预算也强制注入",
                            checked = draft.ignoreBudget,
                            onCheckedChange = { draft = draft.copy(ignoreBudget = it) },
                        )
                        NutTavernGroupDivider()
                        SwitchRow(
                            label = "递归时跳过",
                            subtitle = "递归扫描阶段不激活此条目",
                            checked = draft.excludeRecursion,
                            onCheckedChange = { draft = draft.copy(excludeRecursion = it) },
                        )
                        NutTavernGroupDivider()
                        SwitchRow(
                            label = "阻止触发递归",
                            subtitle = "此条目的内容不触发其他条目",
                            checked = draft.preventRecursion,
                            onCheckedChange = { draft = draft.copy(preventRecursion = it) },
                        )
                        NutTavernGroupDivider()
                        SwitchRow(
                            label = "标题加入内容",
                            subtitle = "把标题也拼到注入内容前面",
                            checked = draft.addMemo,
                            onCheckedChange = { draft = draft.copy(addMemo = it) },
                        )
                    }
                }
            }

            // 删除
            item(key = "delete") {
                NutTavernGroupSection {
                    NutTavernIconRow(
                        icon = Lucide.Trash2,
                        title = "删除条目",
                        subtitle = "从世界书中永久移除",
                        destructive = true,
                        onClick = { showDeleteDialog = true },
                    )
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃修改?") },
            text = { Text("当前条目还有未保存的修改,关闭后会丢失。") },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) { Text("放弃修改") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除条目?") },
            text = { Text("「${draft.comment.ifBlank { "未命名条目" }}」将被永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteEntry(lorebookId, entryUid, currentBook.entries)
                    onBack()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

// region 内部组件

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumRow(
    label: String,
    value: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val displayValue = options.firstOrNull { it.first == value }?.second ?: value.toString()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = { showSheet = true },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(text = displayValue, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NutTavernSheetTitle(title = label)
                options.forEach { (optionValue, optionLabel) ->
                    NutTavernSelectableRow(
                        title = optionLabel,
                        selected = optionValue == value,
                        onClick = { onSelect(optionValue); showSheet = false },
                    )
                }
                Spacer(Modifier.padding(bottom = 16.dp))
            }
        }
    }
}

@Composable
private fun NutTavernExpandableHeader(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// endregion

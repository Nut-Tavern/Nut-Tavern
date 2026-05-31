package com.nuttavern.ui.regex

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.nuttavern.data.regex.RegexExecutionTiming
import com.nuttavern.data.regex.RegexPlacement
import com.nuttavern.data.regex.RegexScript
import com.nuttavern.data.regex.SubstituteRegex
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow
import com.nuttavern.ui.components.NutTavernLabeledTextField
import com.nuttavern.ui.viewmodel.RegexScriptViewModel
import kotlinx.serialization.json.Json

/**
 * 正则脚本编辑页。
 *
 * 字段排布(纵向自上而下):
 * 1. 启用开关(单卡)
 * 2. 名字 + 查找正则 + 替换为 + 修剪掉(基础信息卡)
 *    - "查找正则"右上角紫色 `< >` 图标 = 测试模式触发器(展开后浮一张测试卡)
 * 3. 作用范围(胶囊多选;Slash / 世界书 / 推理块标"待接入"且 disabled)
 * 4. 执行时机(派生 enum 5 选 1 胶囊)
 * 5. 查找时的宏(SubstituteRegex 3 选 1 胶囊)
 * 6. 消息深度(min ~ max 范围输入)
 * 7. 删除卡(仅 [allowDelete] 时显示)
 *
 * 复用入口:
 * - 全局正则:RegexListScreen → 本页(`onSave` 走 [RegexScriptViewModel.upsert]);
 * - 角色正则:[com.nuttavern.ui.character.CharacterRegexEditor] 复用 [RegexScriptFormBody];
 * - 预设正则:第二批做。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegexEditScreen(
    regexId: String,
    groupId: String?,
    onBack: () -> Unit,
    viewModel: RegexScriptViewModel = hiltViewModel(),
) {
    // 决定保存 / 删除走哪条仓库路径:有 groupId → 组内规则;无 → 散规则。
    val onSave: (RegexScript) -> Unit = { edited ->
        if (groupId.isNullOrBlank()) viewModel.upsertOrphan(edited)
        else viewModel.upsertScriptInGroup(groupId, edited)
    }
    val onDeleteAction: (String) -> Unit = { id ->
        if (groupId.isNullOrBlank()) viewModel.deleteOrphan(id)
        else viewModel.deleteScriptFromGroup(groupId, id)
    }

    if (regexId == NEW_REGEX_PLACEHOLDER_ID) {
        val newDraftSeed = rememberSaveable(stateSaver = RegexScriptSaver) {
            mutableStateOf(
                if (groupId.isNullOrBlank()) viewModel.newOrphanScript()
                else viewModel.newScriptInGroup()
            )
        }
        RegexEditScreenContent(
            initial = newDraftSeed.value,
            allowDelete = false,
            onSave = { edited ->
                onSave(edited)
                onBack()
            },
            onDelete = onBack,
            onBack = onBack,
        )
        return
    }

    val source by remember(regexId, viewModel) {
        viewModel.findById(regexId)
    }.collectAsState(initial = null)
    val script = source

    if (script == null) {
        LoadingRegexEditor(onBack = onBack)
        return
    }

    RegexEditScreenContent(
        initial = script,
        allowDelete = true,
        onSave = { edited ->
            onSave(edited)
            onBack()
        },
        onDelete = {
            onDeleteAction(script.id)
            onBack()
        },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RegexEditScreenContent(
    initial: RegexScript,
    allowDelete: Boolean,
    onSave: (RegexScript) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by rememberSaveable(initial.id, stateSaver = RegexScriptSaver) {
        mutableStateOf(initial)
    }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val isDirty = draft != initial

    fun attemptBack() {
        if (isDirty) showUnsavedDialog = true else onBack()
    }
    BackHandler(enabled = isDirty) { attemptBack() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (initial.scriptName.isBlank()) "新建正则" else "编辑正则") },
                navigationIcon = {
                    IconButton(onClick = ::attemptBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onSave(draft) }, enabled = isDirty) {
                        Icon(Lucide.Check, contentDescription = "保存")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        RegexScriptFormBody(
            draft = draft,
            onDraftChange = { draft = it },
            allowDelete = allowDelete,
            onDeleteRequest = { showDeleteDialog = true },
            contentPadding = padding,
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确定删除此规则?") },
            text = { Text("删除后规则数据将永久丢失,且无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
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

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("保存修改?") },
            text = { Text("规则内容已被修改,直接退出将丢失这些修改。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        onSave(draft)
                    },
                ) { Text("保存并退出") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        onBack()
                    },
                ) { Text("放弃修改") }
            },
        )
    }
}

/**
 * 正则规则字段表单(纯 UI,无 ViewModel)。用户级 / 角色卡内嵌 / 预设内嵌三路编辑流共用。
 *
 * 两卡布局:
 * - 卡一(基础配置):规则名称 / 正则表达式 / 替换内容 / 预先过滤
 * - 卡二(高级配置):生效范围(抽屉) / 生效时机(抽屉) / 参数替换(抽屉) / 消息深度 / 规则测试
 *
 * 启用开关不在此表单 — 归属上一层(列表行 Switch / 组开关)。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun RegexScriptFormBody(
    draft: RegexScript,
    onDraftChange: (RegexScript) -> Unit,
    allowDelete: Boolean,
    onDeleteRequest: () -> Unit,
    contentPadding: PaddingValues,
) {
    val runRegex = rememberRegexTestRunner()
    var testExpanded by rememberSaveable(draft.id) { mutableStateOf(false) }
    var testInput by rememberSaveable(draft.id) { mutableStateOf("") }
    val testResult = remember(draft, testInput) {
        if (testInput.isEmpty()) RegexTestResult.Empty
        else runCatching { runRegex(draft, testInput) }
            .fold(
                onSuccess = { RegexTestResult.Output(it) },
                onFailure = { RegexTestResult.Error(it.message ?: "正则执行失败") },
            )
    }

    var showPlacementSheet by remember { mutableStateOf(false) }
    var showTimingSheet by remember { mutableStateOf(false) }
    var showSubstituteSheet by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
    ) {
        // ─── 卡一:基础配置 ─────────────────────────────────────────────────
        item(key = "basics") {
            NutTavernGroupSection(title = "基础配置") {
                NutTavernLabeledTextField(
                    label = "规则名称",
                    value = draft.scriptName,
                    onValueChange = { onDraftChange(draft.copy(scriptName = it)) },
                    singleLine = true,
                    supportingText = "显示用的名字",
                )
                NutTavernGroupDivider(inset = 0.dp)
                NutTavernLabeledTextField(
                    label = "查找规则",
                    value = draft.findRegex,
                    onValueChange = { onDraftChange(draft.copy(findRegex = it)) },
                    minLines = 2,
                    supportingText = "用 JS 正则字面量,标志位 i / s / m 可叠加",
                )
                NutTavernGroupDivider(inset = 0.dp)
                NutTavernLabeledTextField(
                    label = "替换内容",
                    value = draft.replaceString,
                    onValueChange = { onDraftChange(draft.copy(replaceString = it)) },
                    minLines = 2,
                    supportingText = "用 {{match}} 引用整段匹配,用 \$1 / \$2 引用括号里的内容",
                )
                NutTavernGroupDivider(inset = 0.dp)
                NutTavernLabeledTextField(
                    label = "预先过滤",
                    value = draft.trimStrings.joinToString(", "),
                    onValueChange = { raw ->
                        onDraftChange(draft.copy(trimStrings = parseTrimStrings(raw)))
                    },
                    singleLine = true,
                    supportingText = "匹配前先剔除这些片段,用逗号分隔",
                )
            }
        }

        // ─── 卡二:高级配置 ─────────────────────────────────────────────────
        item(key = "advanced") {
            NutTavernGroupSection(title = "高级配置") {
                // 生效范围 — 点击弹抽屉
                SettingEntryRow(
                    title = "生效范围",
                    value = placementDisplayText(draft.placement),
                    onClick = { showPlacementSheet = true },
                )
                NutTavernGroupDivider(inset = 0.dp)
                // 生效时机 — 点击弹抽屉
                SettingEntryRow(
                    title = "生效时机",
                    value = timingDisplayText(RegexExecutionTiming.from(draft)),
                    onClick = { showTimingSheet = true },
                )
                NutTavernGroupDivider(inset = 0.dp)
                // 参数替换 — 点击弹抽屉
                SettingEntryRow(
                    title = "匹配宏变量",
                    value = substituteDisplayText(SubstituteRegex.fromValue(draft.substituteRegex)),
                    onClick = { showSubstituteSheet = true },
                )
                NutTavernGroupDivider(inset = 0.dp)
                // 消息深度
                DepthRangeRow(
                    minDepth = draft.minDepth,
                    maxDepth = draft.maxDepth,
                    onChange = { min, max ->
                        onDraftChange(draft.copy(minDepth = min, maxDepth = max))
                    },
                )
                NutTavernGroupDivider()
                // 规则测试
                TestModeBlock(
                    expanded = testExpanded,
                    onToggle = { testExpanded = !testExpanded },
                    input = testInput,
                    result = testResult,
                    onInputChange = { testInput = it },
                )
            }
        }

        // ─── 删除卡 ───────────────────────────────────────────────────────
        if (allowDelete) {
            item(key = "danger") {
                NutTavernGroupSection {
                    NutTavernIconRow(
                        icon = Lucide.Trash2,
                        title = "删除规则",
                        subtitle = "删除后规则无法恢复",
                        destructive = true,
                        onClick = onDeleteRequest,
                    )
                }
            }
        }
    }

    // ─── 三个 Sheet ───────────────────────────────────────────────────────
    if (showPlacementSheet) {
        PlacementSheet(
            selected = draft.placement,
            onDismiss = { showPlacementSheet = false },
            onChange = { onDraftChange(draft.copy(placement = it)) },
        )
    }
    if (showTimingSheet) {
        TimingSheet(
            selected = RegexExecutionTiming.from(draft),
            onDismiss = { showTimingSheet = false },
            onChange = { timing -> onDraftChange(timing.applyTo(draft)) },
        )
    }
    if (showSubstituteSheet) {
        SubstituteSheet(
            selected = SubstituteRegex.fromValue(draft.substituteRegex),
            onDismiss = { showSubstituteSheet = false },
            onChange = { mode -> onDraftChange(draft.copy(substituteRegex = mode.value)) },
        )
    }
}

// ─── 私有组件:Sheet + 工具函数 ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacementSheet(
    selected: List<Int>,
    onDismiss: () -> Unit,
    onChange: (List<Int>) -> Unit,
) {
    // 快捷命令(SLASH_COMMAND)在 Nut Tavern 不实现,从 UI 选项里去除;enum 仍保留,
    // 用于 round-trip 兼容从酒馆导入的脚本(导入后字段保留,UI 不显示而已)。
    val items = listOf(
        RegexPlacement.USER_INPUT to ("用户输入" to "如:发送前规范化标点"),
        RegexPlacement.AI_OUTPUT to ("角色回复" to "如:折叠 <thinking> 块"),
        RegexPlacement.WORLD_INFO to ("世界书" to "对世界书条目生效(待接入)"),
        RegexPlacement.REASONING to ("推理内容" to "对推理块内容生效(待接入)"),
    )
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.nuttavern.ui.components.NutTavernSheetTitle(title = "选择生效范围")
            items.forEach { (placement, labelPair) ->
                val (label, subtitle) = labelPair
                val isSelected = placement.value in selected
                val isEnabled = placement != RegexPlacement.WORLD_INFO &&
                    placement != RegexPlacement.REASONING
                com.nuttavern.ui.components.NutTavernSelectableRow(
                    title = label,
                    subtitle = subtitle,
                    selected = isSelected,
                    enabled = isEnabled,
                    onClick = {
                        onChange(
                            if (isSelected) selected - placement.value
                            else selected + placement.value,
                        )
                    },
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimingSheet(
    selected: RegexExecutionTiming,
    onDismiss: () -> Unit,
    onChange: (RegexExecutionTiming) -> Unit,
) {
    // 8 个选项分两段:5 个常用典型 + 3 个不常用(组合带"编辑时也重跑")。视觉上不分组,
    // 只在副标里把"编辑时也重跑"这层语义讲清楚。
    val items = listOf(
        RegexExecutionTiming.AFTER_GENERATION to ("接收消息时" to "收到角色回复后执行替换。会改变聊天记录"),
        RegexExecutionTiming.AFTER_GENERATION_AND_EDIT to ("接收和编辑消息时" to "收到回复或手动编辑消息时执行。会改变聊天记录"),
        RegexExecutionTiming.DISPLAY_ONLY to ("显示消息时" to "消息在界面上渲染时执行。不改变聊天记录"),
        RegexExecutionTiming.PROMPT_ONLY to ("发送消息时" to "消息发送给角色前执行。不改变聊天记录"),
        RegexExecutionTiming.DISPLAY_AND_PROMPT to ("显示和发送时" to "渲染和发送时都执行。不改变聊天记录"),
        RegexExecutionTiming.DISPLAY_AND_EDIT to ("显示和编辑时" to "渲染时和手动编辑时都执行。不改变聊天记录"),
        RegexExecutionTiming.PROMPT_AND_EDIT to ("发送和编辑时" to "发送给角色时和手动编辑时都执行。不改变聊天记录"),
        RegexExecutionTiming.DISPLAY_PROMPT_AND_EDIT to ("显示、发送和编辑时" to "渲染 / 发送 / 编辑时全部执行。不改变聊天记录"),
    )
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.nuttavern.ui.components.NutTavernSheetTitle(title = "选择生效时机")
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items) { (timing, labelPair) ->
                    val (label, subtitle) = labelPair
                    com.nuttavern.ui.components.NutTavernSelectableRow(
                        title = label,
                        subtitle = subtitle,
                        selected = selected == timing,
                        onClick = {
                            onChange(timing)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubstituteSheet(
    selected: SubstituteRegex,
    onDismiss: () -> Unit,
    onChange: (SubstituteRegex) -> Unit,
) {
    val items = listOf(
        SubstituteRegex.NONE to ("直接匹配" to "将宏变量视为普通字面文本"),
        SubstituteRegex.RAW to ("匹配实际值（原始）" to "先将宏变量替换为实际内容"),
        SubstituteRegex.ESCAPED to ("匹配实际值（转义）" to "替换实际内容并进行正则转义"),
    )
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.nuttavern.ui.components.NutTavernSheetTitle(title = "选择宏变量处理方式")
            items.forEach { (mode, labelPair) ->
                val (label, subtitle) = labelPair
                com.nuttavern.ui.components.NutTavernSelectableRow(
                    title = label,
                    subtitle = subtitle,
                    selected = selected == mode,
                    onClick = {
                        onChange(mode)
                        onDismiss()
                    },
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

/** 测试运行的三态:空 / 成功 / 失败。失败必须显式给用户看,不能被吞成"输出 = 输入"。 */
private sealed interface RegexTestResult {
    object Empty : RegexTestResult
    data class Output(val text: String) : RegexTestResult
    data class Error(val message: String) : RegexTestResult
}

@Composable
private fun TestModeBlock(
    expanded: Boolean,
    onToggle: () -> Unit,
    input: String,
    result: RegexTestResult,
    onInputChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.TextButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = if (expanded) "收起测试" else "测试匹配效果",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Text(
                text = "输入测试文本即时预览替换结果",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text("测试文本") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            val isError = result is RegexTestResult.Error
            val resultText = when (result) {
                is RegexTestResult.Empty -> ""
                is RegexTestResult.Output -> result.text
                is RegexTestResult.Error -> result.message
            }
            OutlinedTextField(
                value = resultText,
                onValueChange = {},
                readOnly = true,
                isError = isError,
                label = { Text(if (isError) "规则错误" else "替换结果") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DepthRangeRow(
    minDepth: Int?,
    maxDepth: Int?,
    onChange: (Int?, Int?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "消息深度",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "0 = 最新一条消息;留空表示不限制",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = minDepth?.toString().orEmpty(),
                onValueChange = { raw -> onChange(raw.trim().toIntOrNull(), maxDepth) },
                label = { Text("最小深度") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
            )
            Text(
                text = "~",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = maxDepth?.toString().orEmpty(),
                onValueChange = { raw -> onChange(minDepth, raw.trim().toIntOrNull()) },
                label = { Text("最大深度") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
            )
        }
    }
}

// ─── 显示文案工具 ─────────────────────────────────────────────────────────

private fun placementDisplayText(placement: List<Int>): String {
    if (placement.isEmpty()) return "未选择"
    val labels = placement.mapNotNull { value ->
        when (value) {
            RegexPlacement.USER_INPUT.value -> "用户输入"
            RegexPlacement.AI_OUTPUT.value -> "角色回复"
            RegexPlacement.SLASH_COMMAND.value -> "快捷命令"
            RegexPlacement.WORLD_INFO.value -> "世界书"
            RegexPlacement.REASONING.value -> "推理内容"
            else -> null
        }
    }
    return labels.joinToString("、")
}

private fun timingDisplayText(timing: RegexExecutionTiming): String = when (timing) {
    RegexExecutionTiming.AFTER_GENERATION -> "接收消息时"
    RegexExecutionTiming.AFTER_GENERATION_AND_EDIT -> "接收和编辑消息时"
    RegexExecutionTiming.DISPLAY_ONLY -> "显示消息时"
    RegexExecutionTiming.PROMPT_ONLY -> "发送消息时"
    RegexExecutionTiming.DISPLAY_AND_PROMPT -> "显示和发送时"
    RegexExecutionTiming.DISPLAY_AND_EDIT -> "显示和编辑时"
    RegexExecutionTiming.PROMPT_AND_EDIT -> "发送和编辑时"
    RegexExecutionTiming.DISPLAY_PROMPT_AND_EDIT -> "显示、发送和编辑时"
}

private fun substituteDisplayText(mode: SubstituteRegex): String = when (mode) {
    SubstituteRegex.NONE -> "直接匹配"
    SubstituteRegex.RAW -> "匹配实际值（原始）"
    SubstituteRegex.ESCAPED -> "匹配实际值（转义）"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadingRegexEditor(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("加载中") },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text("规则不存在或已被删除", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 无图标的选择行。标题左 + 当前值右 + ChevronRight。
 * 用于高级配置卡内的"点击弹抽屉"入口,不需要 leading icon。
 */
@Composable
private fun SettingEntryRow(
    title: String,
    value: String,
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
            Icon(
                imageVector = com.composables.icons.lucide.Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun parseTrimStrings(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return raw.split(',', '，')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

/**
 * RegexScript 的 SaveableStateRegistry Saver。
 *
 * - **save 端不容错**:`@Serializable` 类编码不应该失败,失败说明 schema 损坏,直接抛比静默
 *   返回 null 把问题暴露出来。
 * - **restore 端必须容错**:rememberSaveable 的契约是"返回 null 触发 init 重建"。异常 / 数据
 *   损坏时打 Log 上报,但返回 null 让 UI 继续工作,避免旋屏崩溃。
 */
private val RegexScriptSaver: Saver<RegexScript, String> = Saver(
    save = { value -> Json.encodeToString(RegexScript.serializer(), value) },
    restore = { stored ->
        try {
            Json.decodeFromString(RegexScript.serializer(), stored)
        } catch (e: Throwable) {
            android.util.Log.w("RegexScriptSaver", "restore failed, falling back to init", e)
            null
        }
    },
)

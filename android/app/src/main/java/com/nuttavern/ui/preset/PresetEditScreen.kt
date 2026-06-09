package com.nuttavern.ui.preset

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
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
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Regex
import com.composables.icons.lucide.Trash2
import com.nuttavern.data.preset.ContinuePostfix
import com.nuttavern.data.preset.NamesBehavior
import com.nuttavern.data.preset.Preset
import com.nuttavern.data.preset.PromptEntry
import com.nuttavern.data.preset.PromptOrderEntry
import com.nuttavern.data.preset.PromptOrderForCharacter
import com.nuttavern.data.preset.presetRegexScripts
import com.nuttavern.data.preset.withPresetRegexScripts
import com.nuttavern.ui.components.NutTavernEnumRow
import com.nuttavern.ui.components.NutTavernExpandableHeader
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow
import com.nuttavern.ui.components.NutTavernLabeledTextField
import com.nuttavern.ui.components.NutTavernNumericField
import com.nuttavern.ui.components.NutTavernSwitchRow
import com.nuttavern.ui.components.NumericParser
import com.nuttavern.ui.viewmodel.PresetViewModel
import kotlinx.serialization.json.Json
import sh.calvin.reorderable.ReorderableColumn

/**
 * 预设编辑页。
 *
 * 字段分组(对齐酒馆 chat completion preset UI 顺序):
 * 1. **基础**:name / description;
 * 2. **提示词条目**:prompts + prompt_order(全局排序);可拖排序、行内开关、点编辑键进
 *    [PromptEntryEditScreen];
 * 3. **生成参数**(折叠):temperature / top_p / max_tokens / context window / freq / pres / seed / n
 *    + reasoning_effort / verbosity / stream / function_calling / web_search;
 * 4. **拼接控制**(折叠):impersonation / new_chat / new_group_chat / new_example_chat /
 *    continue_nudge / scenario_format / personality_format / wi_format / group_nudge /
 *    send_if_empty;
 * 5. **API 行为**(折叠):names_behavior / continue_postfix / continue_prefill /
 *    squash_system_messages / use_sysprompt / media_inlining /
 *    custom_prompt_post_processing / bypass_status_check / show_thoughts /
 *    assistant_prefill / assistant_impersonation / max_context_unlocked /
 *    show_external_models / bind_preset_to_connection / tool_call_recurse_limit /
 *    tool_reasoning_mode;
 * 6. **删除卡**:仅在非内置预设时显示。
 *
 * 内置预设([Preset.DEFAULT_PRESET_ID])可改可设默认,但不可删,顶栏不显示删除按钮,
 * 删除卡也不渲染。
 */
@Composable
fun PresetEditScreen(
    presetId: String,
    onBack: () -> Unit,
    viewModel: PresetViewModel = hiltViewModel(),
) {
    if (presetId == NEW_PRESET_PLACEHOLDER_ID) {
        val newDraftSeed = rememberSaveable(stateSaver = PresetSaver) {
            mutableStateOf(viewModel.newPreset())
        }
        PresetEditScreenContent(
            initial = newDraftSeed.value,
            allowDelete = false,
            isNew = true,
            onSave = { edited ->
                viewModel.upsert(edited)
                onBack()
            },
            onDelete = onBack,
            onBack = onBack,
        )
        return
    }

    val source by remember(presetId, viewModel) {
        viewModel.findById(presetId)
    }.collectAsState(initial = null)
    val preset = source

    if (preset == null) {
        LoadingPresetEditor(onBack = onBack)
        return
    }

    PresetEditScreenContent(
        initial = preset,
        allowDelete = !preset.isBuiltInDefault,
        isNew = false,
        onSave = { edited ->
            viewModel.upsert(edited)
            onBack()
        },
        onDelete = {
            viewModel.delete(preset.id)
            onBack()
        },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadingPresetEditor(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("编辑预设") },
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetEditScreenContent(
    initial: Preset,
    onSave: (Preset) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    allowDelete: Boolean,
    isNew: Boolean,
) {
    var draft by rememberSaveable(initial.id, stateSaver = PresetSaver) {
        mutableStateOf(initial)
    }
    val isDirty = draft != initial
    val canSave = draft.name.isNotBlank()

    var advancedGenerationExpanded by remember { mutableStateOf(false) }
    var compositionExpanded by remember { mutableStateOf(false) }
    var apiBehaviorExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var entryEditorTarget by remember { mutableStateOf<PromptEntry?>(null) }
    var showPresetRegexEditor by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    val triggerBack: () -> Unit = {
        if (isDirty) showUnsavedDialog = true else onBack()
    }
    BackHandler(enabled = true) { triggerBack() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (isNew) "新建预设" else "编辑预设") },
                navigationIcon = {
                    IconButton(onClick = triggerBack) { Icon(Lucide.ArrowLeft, "返回") }
                },
                actions = {
                    TextButton(
                        onClick = { onSave(draft) },
                        enabled = canSave && isDirty,
                    ) { Text("保存") }
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
            androidx.compose.material3.TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                androidx.compose.material3.Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("条目") },
                )
                androidx.compose.material3.Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("设置") },
                )
            }

            when (selectedTab) {
                0 -> PromptsTabContent(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onEditEntry = { entry -> entryEditorTarget = entry },
                )
                1 -> SettingsTabContent(
                    draft = draft,
                    onDraftChange = { draft = it },
                    allowDelete = allowDelete,
                    advancedGenerationExpanded = advancedGenerationExpanded,
                    onAdvancedGenerationExpandedChange = { advancedGenerationExpanded = it },
                    compositionExpanded = compositionExpanded,
                    onCompositionExpandedChange = { compositionExpanded = it },
                    apiBehaviorExpanded = apiBehaviorExpanded,
                    onApiBehaviorExpandedChange = { apiBehaviorExpanded = it },
                    onShowDeleteDialog = { showDeleteDialog = true },
                    onOpenPresetRegexEditor = { showPresetRegexEditor = true },
                )
            }
        }
    }


    val entryTarget = entryEditorTarget
    if (entryTarget != null) {
        PromptEntryEditScreen(
            initial = entryTarget,
            onDismiss = { entryEditorTarget = null },
            onSave = { saved ->
                draft = draft.copy(
                    prompts = draft.prompts.map { if (it.identifier == saved.identifier) saved else it },
                )
                entryEditorTarget = null
            },
            // 内置 12 条系统条目不可删,不传 onDelete 即可隐藏删除卡。
            onDelete = if (entryTarget.identifier in BUILT_IN_PROMPT_IDS) {
                null
            } else {
                {
                    draft = draft.removePrompt(entryTarget.identifier)
                    entryEditorTarget = null
                }
            },
            // 内置条目不可取消链接;已链接的自定义条目可以取消链接。
            onUnlink = if (entryTarget.identifier in BUILT_IN_PROMPT_IDS) {
                null
            } else {
                {
                    draft = draft.unlinkPrompt(entryTarget.identifier)
                    entryEditorTarget = null
                }
            },
        )
    }

    if (showPresetRegexEditor) {
        PresetRegexEditor(
            scripts = draft.presetRegexScripts(),
            onChange = { next -> draft = draft.withPresetRegexScripts(next) },
            onBack = { showPresetRegexEditor = false },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除这份预设?") },
            text = { Text("「${draft.name.ifBlank { "未命名预设" }}」会被永久删除,关联的会话将退化为默认预设。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("有未保存的修改") },
            text = { Text("退出编辑会丢失这些修改。") },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    if (canSave) onSave(draft) else onBack()
                }) { Text(if (canSave) "保存并退出" else "继续编辑") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    onBack()
                }) { Text("不保存退出") }
            },
        )
    }
}

/**
 * 当前预设里启用的提示词条目数。计算时只看全局 prompt_order 中 enabled=true 的条目。
 */
private fun enabledPromptCount(preset: Preset): Int {
    val order = preset.promptOrder
        .firstOrNull { it.characterId == PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
        ?.order ?: return preset.prompts.size
    return order.count { it.enabled }
}

/**
 * 提示词条目可拖排序列表。
 *
 * 操作:
 * - 整行点击 → 弹出 [PromptEntryEditScreen] 编辑该条目;
 * - 行右侧 [Switch] → 切换 [PromptOrderEntry.enabled];
 * - 拖把手长按 → 排序后写回 [Preset.promptOrder]。
 *
 * 实现说明:外层是 LazyColumn,这里挂的是 [ReorderableColumn](非 Lazy 版本),
 * 提示词条目数量上限可控(酒馆 Default.json 12 条 + 自定义条目通常 < 30),非 Lazy 不会
 * 触发性能问题,且能避免在 LazyColumn 内嵌 LazyColumn 的滚动冲突。
 *
 * 数据写回:每次 enabled 切换 / 拖排序结束都直接 [onChange] 出新的 [Preset];编辑器只承载
 * 草稿,落库由保存按钮触发。
 */
@Composable
private fun PromptOrderList(
    preset: Preset,
    onChange: (Preset) -> Unit,
    onEditEntry: (PromptEntry) -> Unit,
) {
    val orderForGlobal = preset.promptOrder
        .firstOrNull { it.characterId == PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
    val effectiveOrder = orderForGlobal?.order
        ?: preset.prompts.map { PromptOrderEntry(identifier = it.identifier, enabled = true) }
    val byId = preset.prompts.associateBy { it.identifier }
    val ordered = remember(effectiveOrder, byId) {
        effectiveOrder.mapNotNull { entry ->
            byId[entry.identifier]?.let { OrderedPromptItem(it, entry) }
        }
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ReorderableColumn(
            modifier = Modifier.fillMaxWidth(),
            list = ordered,
            onSettle = { from, to ->
                val updated = ordered.toMutableList().apply { add(to, removeAt(from)) }
                onChange(preset.applyPromptOrder(updated.map { it.orderEntry }))
            },
        ) { index, item, _ ->
            key(item.entry.identifier) {
                PromptOrderRow(
                    entry = item.entry,
                    enabled = item.orderEntry.enabled,
                    onClick = { onEditEntry(item.entry) },
                    onToggleEnabled = { newEnabled ->
                        val updated = ordered.toMutableList().apply {
                            this[index] = item.copy(orderEntry = item.orderEntry.copy(enabled = newEnabled))
                        }
                        onChange(preset.applyPromptOrder(updated.map { it.orderEntry }))
                    },
                    dragHandleModifier = Modifier.draggableHandle(),
                )
                if (index < ordered.lastIndex) NutTavernGroupDivider()
            }
        }
    }
}

@Composable
private fun PromptOrderRow(
    entry: PromptEntry,
    enabled: Boolean,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    dragHandleModifier: Modifier,
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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.name.ifBlank { entry.identifier },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = promptEntrySubtitle(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggleEnabled,
            )
            Icon(
                imageVector = Lucide.GripVertical,
                contentDescription = "拖动排序",
                modifier = dragHandleModifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun promptEntrySubtitle(entry: PromptEntry): String {
    val parts = buildList {
        if (entry.marker) add("占位")
        add(entry.role.name.lowercase())
        if (entry.injectionPosition == com.nuttavern.data.preset.InjectionPosition.ABSOLUTE) {
            add("@${entry.injectionDepth}")
        }
        if (entry.forbidOverrides) add("禁止覆盖")
    }
    return parts.joinToString(" · ")
}

/**
 * 内置提示词条目 identifier 集合(对齐酒馆 Default.json 12 条系统条目)。
 *
 * 内置条目语义稳定 — 拼接管线对它们做特殊处理(marker / 角色卡覆盖等),
 * 用户**不能删除**;只能改 enabled / 改内容。
 */
private val BUILT_IN_PROMPT_IDS: Set<String> = setOf(
    "main",
    "nsfw",
    "dialogueExamples",
    "jailbreak",
    "chatHistory",
    "worldInfoAfter",
    "worldInfoBefore",
    "enhanceDefinitions",
    "charDescription",
    "charPersonality",
    "scenario",
    "personaDescription",
)

/** 新建一个空白自定义 prompt 条目(role = system,enabled = true)。 */
private fun newCustomPromptEntry(): PromptEntry = PromptEntry(
    identifier = java.util.UUID.randomUUID().toString(),
    name = "",
    role = com.nuttavern.data.preset.PromptRole.SYSTEM,
    content = "",
    systemPrompt = false,
    marker = false,
    forbidOverrides = false,
)

/**
 * 把新建的自定义条目同时追加到 [Preset.prompts] 与全局 prompt_order 末尾(默认 enabled = true)。
 */
private fun Preset.appendCustomPrompt(entry: PromptEntry): Preset {
    val updatedPrompts = prompts + entry
    val others = promptOrder.filter { it.characterId != PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
    val current = promptOrder
        .firstOrNull { it.characterId == PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
        ?.order
        ?: emptyList()
    val updatedOrder = current + PromptOrderEntry(identifier = entry.identifier, enabled = false)
    val global = PromptOrderForCharacter(
        characterId = PromptOrderForCharacter.GLOBAL_CHARACTER_ID,
        order = updatedOrder,
    )
    return copy(
        prompts = updatedPrompts,
        promptOrder = others + global,
    )
}

/** 把指定 identifier 的条目同时从 prompts 与全局 prompt_order 中移除。 */
private fun Preset.removePrompt(identifier: String): Preset {
    val updatedPrompts = prompts.filterNot { it.identifier == identifier }
    val others = promptOrder.filter { it.characterId != PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
    val current = promptOrder
        .firstOrNull { it.characterId == PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
        ?.order
        ?: emptyList()
    val updatedOrder = current.filterNot { it.identifier == identifier }
    val global = PromptOrderForCharacter(
        characterId = PromptOrderForCharacter.GLOBAL_CHARACTER_ID,
        order = updatedOrder,
    )
    return copy(
        prompts = updatedPrompts,
        promptOrder = others + global,
    )
}

/**
 * 把未链接条目链接到全局 prompt_order 末尾,默认 enabled=false。
 * prompts 定义不动(条目本身已经在 prompts 里)。
 */
private fun Preset.linkPrompt(identifier: String): Preset {
    val others = promptOrder.filter { it.characterId != PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
    val current = promptOrder
        .firstOrNull { it.characterId == PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
        ?.order
        ?: emptyList()
    // 已经在 order 里就不重复加
    if (current.any { it.identifier == identifier }) return this
    val updatedOrder = current + PromptOrderEntry(identifier = identifier, enabled = false)
    val global = PromptOrderForCharacter(
        characterId = PromptOrderForCharacter.GLOBAL_CHARACTER_ID,
        order = updatedOrder,
    )
    return copy(promptOrder = others + global)
}

/**
 * 取消链接:从全局 prompt_order 中移除该条目,但保留 prompts 定义。
 * 条目变成"未链接"状态,不参与拼接。
 */
private fun Preset.unlinkPrompt(identifier: String): Preset {
    val others = promptOrder.filter { it.characterId != PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
    val current = promptOrder
        .firstOrNull { it.characterId == PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
        ?.order
        ?: emptyList()
    val updatedOrder = current.filterNot { it.identifier == identifier }
    val global = PromptOrderForCharacter(
        characterId = PromptOrderForCharacter.GLOBAL_CHARACTER_ID,
        order = updatedOrder,
    )
    return copy(promptOrder = others + global)
}

private data class OrderedPromptItem(
    val entry: PromptEntry,
    val orderEntry: PromptOrderEntry,
)

/** 把拖排序 / 开关变更后的 order 列表写回预设的全局 prompt_order。 */
private fun Preset.applyPromptOrder(updated: List<PromptOrderEntry>): Preset {
    val others = promptOrder.filter { it.characterId != PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
    val global = PromptOrderForCharacter(
        characterId = PromptOrderForCharacter.GLOBAL_CHARACTER_ID,
        order = updated,
    )
    return copy(promptOrder = others + global)
}

/* ------------------------- 字段输入小组件 ------------------------- */

@Composable
private fun DoubleField(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    supportingText: String? = null,
    min: Double? = null,
    max: Double? = null,
) {
    NutTavernNumericField(
        label = label,
        value = value,
        onValueChange = { it?.let(onValueChange) },
        parser = NumericParser.DoubleParser,
        min = min,
        max = max,
        helperText = supportingText,
    )
}

@Composable
private fun IntField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    supportingText: String? = null,
    min: Int? = null,
    max: Int? = null,
) {
    NutTavernNumericField(
        label = label,
        value = value,
        onValueChange = { it?.let(onValueChange) },
        parser = NumericParser.IntParser,
        min = min,
        max = max,
        helperText = supportingText,
    )
}

private val PresetSaver: Saver<Preset, String> = Saver(
    save = { value -> Json.encodeToString(Preset.serializer(), value) },
    restore = { stored ->
        try {
            Json.decodeFromString(Preset.serializer(), stored)
        } catch (e: Throwable) {
            android.util.Log.w("PresetSaver", "restore failed, falling back to init", e)
            null
        }
    },
)

// region Tab 内容

/**
 * 条目 Tab:名称/描述 + 已链接条目列表(可拖排序+Switch) + 未链接条目列表(链接按钮)。
 */
@Composable
private fun PromptsTabContent(
    draft: Preset,
    onDraftChange: (Preset) -> Unit,
    onEditEntry: (PromptEntry) -> Unit,
) {
    val globalOrder = draft.promptOrder
        .firstOrNull { it.characterId == PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
        ?.order ?: emptyList()
    val linkedIds = globalOrder.map { it.identifier }.toSet()
    val unlinkedEntries = draft.prompts.filter { it.identifier !in linkedIds }

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
                    placeholder = "如「主预设」「角色扮演」",
                    singleLine = true,
                    isError = draft.name.isBlank(),
                    supportingText = if (draft.name.isBlank()) "名称不能为空" else null,
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

        item(key = "prompts-header") {
            NutTavernGroupSection {
                NutTavernIconRow(
                    icon = Lucide.Pencil,
                    title = "已链接条目",
                    subtitle = "${globalOrder.size} 条 · ${globalOrder.count { it.enabled }} 启用",
                    showTrailingChevron = false,
                    onClick = {},
                )
                NutTavernGroupDivider()
                NutTavernIconRow(
                    icon = Lucide.Plus,
                    title = "新建条目",
                    subtitle = "追加到末尾,默认禁用",
                    showTrailingChevron = false,
                    onClick = {
                        val newEntry = newCustomPromptEntry()
                        onDraftChange(draft.appendCustomPrompt(newEntry))
                        onEditEntry(newEntry)
                    },
                )
            }
        }

        item(key = "prompts-list") {
            PromptOrderList(
                preset = draft,
                onChange = onDraftChange,
                onEditEntry = onEditEntry,
            )
        }

        if (unlinkedEntries.isNotEmpty()) {
            item(key = "unlinked-header") {
                NutTavernGroupSection {
                    NutTavernIconRow(
                        icon = Lucide.Regex,
                        title = "未链接条目",
                        subtitle = "${unlinkedEntries.size} 条,不参与拼接",
                        showTrailingChevron = false,
                        onClick = {},
                    )
                }
            }
            item(key = "unlinked-list") {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        unlinkedEntries.forEachIndexed { index, entry ->
                            key(entry.identifier) {
                                UnlinkedPromptRow(
                                    entry = entry,
                                    onLink = { onDraftChange(draft.linkPrompt(entry.identifier)) },
                                    onEdit = { onEditEntry(entry) },
                                )
                                if (index < unlinkedEntries.lastIndex) NutTavernGroupDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 设置 Tab:生成参数 / 高级 / 拼接控制 / API 行为 / 预设正则 / 删除。
 */
@Composable
private fun SettingsTabContent(
    draft: Preset,
    onDraftChange: (Preset) -> Unit,
    allowDelete: Boolean,
    advancedGenerationExpanded: Boolean,
    onAdvancedGenerationExpandedChange: (Boolean) -> Unit,
    compositionExpanded: Boolean,
    onCompositionExpandedChange: (Boolean) -> Unit,
    apiBehaviorExpanded: Boolean,
    onApiBehaviorExpandedChange: (Boolean) -> Unit,
    onShowDeleteDialog: () -> Unit,
    onOpenPresetRegexEditor: () -> Unit,
) {
    val presetRegexScripts = remember(draft.extensions) { draft.presetRegexScripts() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
    ) {
        item(key = "generation-basic") {
            NutTavernGroupSection {
                DoubleField(
                    label = "温度 (Temperature)",
                    value = draft.temperature,
                    onValueChange = { onDraftChange(draft.copy(temperature = it)) },
                    supportingText = "0.0 ~ 2.0,越高越发散",
                    min = 0.0,
                    max = 2.0,
                )
                NutTavernGroupDivider()
                DoubleField(
                    label = "Top-P",
                    value = draft.topP,
                    onValueChange = { onDraftChange(draft.copy(topP = it)) },
                    supportingText = "0.0 ~ 1.0",
                    min = 0.0,
                    max = 1.0,
                )
                NutTavernGroupDivider()
                IntField(
                    label = "最大 Token 数",
                    value = draft.openaiMaxTokens,
                    onValueChange = { onDraftChange(draft.copy(openaiMaxTokens = it)) },
                    supportingText = "单次回复的 Token 上限",
                    min = 1,
                )
                NutTavernGroupDivider()
                IntField(
                    label = "最大上下文",
                    value = draft.openaiMaxContext,
                    onValueChange = { onDraftChange(draft.copy(openaiMaxContext = it)) },
                    supportingText = "上下文窗口上限,影响裁剪策略",
                    min = 1,
                )
            }
        }

        item(key = "generation-advanced-header") {
            NutTavernExpandableHeader(
                title = "高级生成参数",
                expanded = advancedGenerationExpanded,
                onClick = { onAdvancedGenerationExpandedChange(!advancedGenerationExpanded) },
            )
        }
        if (advancedGenerationExpanded) {
            item(key = "generation-advanced") {
                NutTavernGroupSection {
                    DoubleField(label = "频率惩罚", value = draft.frequencyPenalty, onValueChange = { onDraftChange(draft.copy(frequencyPenalty = it)) }, min = -2.0, max = 2.0)
                    NutTavernGroupDivider()
                    DoubleField(label = "存在惩罚", value = draft.presencePenalty, onValueChange = { onDraftChange(draft.copy(presencePenalty = it)) }, min = -2.0, max = 2.0)
                    NutTavernGroupDivider()
                    IntField(label = "Top-K", value = draft.topK, onValueChange = { onDraftChange(draft.copy(topK = it)) }, min = 0)
                    NutTavernGroupDivider()
                    DoubleField(label = "Top-A", value = draft.topA, onValueChange = { onDraftChange(draft.copy(topA = it)) }, min = 0.0, max = 1.0)
                    NutTavernGroupDivider()
                    DoubleField(label = "Min-P", value = draft.minP, onValueChange = { onDraftChange(draft.copy(minP = it)) }, min = 0.0, max = 1.0)
                    NutTavernGroupDivider()
                    DoubleField(label = "重复惩罚", value = draft.repetitionPenalty, onValueChange = { onDraftChange(draft.copy(repetitionPenalty = it)) }, min = 0.0, max = 2.0)
                    NutTavernGroupDivider()
                    IntField(label = "种子 (Seed)", value = draft.seed, onValueChange = { onDraftChange(draft.copy(seed = it)) }, supportingText = "-1 表示由后端随机", min = -1, max = Int.MAX_VALUE)
                    NutTavernGroupDivider()
                    IntField(label = "回复条数 (n)", value = draft.n, onValueChange = { onDraftChange(draft.copy(n = it)) }, min = 1)
                }
            }
            item(key = "request-flags") {
                NutTavernGroupSection {
                    NutTavernSwitchRow(label = "流式传输", subtitle = "当前客户端固定使用流式请求;此字段仅保留导入导出兼容", checked = draft.streamEnabled, onCheckedChange = { onDraftChange(draft.copy(streamEnabled = it)) })
                }
            }
        }

        item(key = "composition-header") {
            NutTavernExpandableHeader(title = "拼接控制", expanded = compositionExpanded, onClick = { onCompositionExpandedChange(!compositionExpanded) })
        }
        if (compositionExpanded) {
            item(key = "composition-prompts") {
                NutTavernGroupSection {
                    NutTavernLabeledTextField(label = "代替角色发言提示词", value = draft.impersonationPrompt, onValueChange = { onDraftChange(draft.copy(impersonationPrompt = it)) }, supportingText = "让模型替你发言时,作为系统指令插入")
                    NutTavernGroupDivider()
                    NutTavernLabeledTextField(label = "新聊天提示词", value = draft.newChatPrompt, onValueChange = { onDraftChange(draft.copy(newChatPrompt = it)) }, supportingText = "新聊天的首轮上下文提示")
                    NutTavernGroupDivider()
                    NutTavernLabeledTextField(label = "新群聊提示词", value = draft.newGroupChatPrompt, onValueChange = { onDraftChange(draft.copy(newGroupChatPrompt = it)) }, supportingText = "新群聊的首轮上下文提示")
                    NutTavernGroupDivider()
                    NutTavernLabeledTextField(label = "新示例聊天提示词", value = draft.newExampleChatPrompt, onValueChange = { onDraftChange(draft.copy(newExampleChatPrompt = it)) }, supportingText = "在角色卡示例对话之间插入的分隔提示")
                    NutTavernGroupDivider()
                    NutTavernLabeledTextField(label = "续写提示词", value = draft.continueNudgePrompt, onValueChange = { onDraftChange(draft.copy(continueNudgePrompt = it)) }, supportingText = "续写时追加到末尾的提示")
                    NutTavernGroupDivider()
                    NutTavernLabeledTextField(label = "群聊提示词", value = draft.groupNudgePrompt, onValueChange = { onDraftChange(draft.copy(groupNudgePrompt = it)) }, supportingText = "群聊中指定下一个发言角色的提示")
                }
            }
            item(key = "composition-formats") {
                NutTavernGroupSection {
                    NutTavernLabeledTextField(label = "场景格式模板", value = draft.scenarioFormat, onValueChange = { onDraftChange(draft.copy(scenarioFormat = it)) }, supportingText = "拼接角色场景段时套用;{{scenario}} 替换为实际内容")
                    NutTavernGroupDivider()
                    NutTavernLabeledTextField(label = "性格格式模板", value = draft.personalityFormat, onValueChange = { onDraftChange(draft.copy(personalityFormat = it)) }, supportingText = "拼接角色性格段时套用;{{personality}} 替换为实际内容")
                    NutTavernGroupDivider()
                    NutTavernLabeledTextField(label = "世界书格式模板", value = draft.wiFormat, onValueChange = { onDraftChange(draft.copy(wiFormat = it)) }, supportingText = "拼接世界书条目时套用;{0} 替换为条目内容")
                    NutTavernGroupDivider()
                    NutTavernEnumRow(label = "名称行为", value = draft.namesBehavior.name.lowercase(), options = listOf("none" to "不添加", "default" to "默认", "completion" to "补全模式", "content" to "消息内容"), onSelect = { onDraftChange(draft.copy(namesBehavior = NamesBehavior.valueOf(it.uppercase()))) }, optionDescriptions = mapOf("none" to "不在消息里加名字", "default" to "由后端决定", "completion" to "名字加在 content 开头", "content" to "用 name 字段传递"))
                    NutTavernGroupDivider()
                    NutTavernEnumRow(label = "续写后缀", value = draft.continuePostfix.name.lowercase(), options = listOf("none" to "无", "space" to "空格", "newline" to "换行", "double_newline" to "双换行"), onSelect = { onDraftChange(draft.copy(continuePostfix = ContinuePostfix.valueOf(it.uppercase()))) }, optionDescriptions = mapOf("none" to "续写时不加后缀", "space" to "加一个空格", "newline" to "加一个换行", "double_newline" to "加两个换行"))
                }
            }
        }

        item(key = "api-behavior-header") {
            NutTavernExpandableHeader(title = "API 行为", expanded = apiBehaviorExpanded, onClick = { onApiBehaviorExpandedChange(!apiBehaviorExpanded) })
        }
        if (apiBehaviorExpanded) {
            item(key = "api-behavior-1") {
                NutTavernGroupSection {
                    NutTavernSwitchRow(label = "续写预填", subtitle = "续写时把已有文本作为 assistant prefill 发送", checked = draft.continuePrefill, onCheckedChange = { onDraftChange(draft.copy(continuePrefill = it)) })
                    NutTavernGroupDivider()
                    NutTavernSwitchRow(label = "压缩系统消息", subtitle = "把连续的 system 消息合并成一条", checked = draft.squashSystemMessages, onCheckedChange = { onDraftChange(draft.copy(squashSystemMessages = it)) })
                    NutTavernGroupDivider()
                    NutTavernSwitchRow(label = "使用系统提示词", subtitle = "把 system 内容放到 API 的 system 字段", checked = draft.useSysprompt, onCheckedChange = { onDraftChange(draft.copy(useSysprompt = it)) })
                    NutTavernGroupDivider()
                    NutTavernSwitchRow(label = "内联媒体", subtitle = "把图片/音频 base64 拼进消息内容", checked = draft.mediaInlining, onCheckedChange = { onDraftChange(draft.copy(mediaInlining = it)) })
                    NutTavernGroupDivider()
                    NutTavernSwitchRow(label = "解锁最大上下文", subtitle = "无视已知模型上下文上限", checked = draft.maxContextUnlocked, onCheckedChange = { onDraftChange(draft.copy(maxContextUnlocked = it)) })
                }
            }
            item(key = "api-behavior-2") {
                NutTavernGroupSection {
                    NutTavernLabeledTextField(label = "助手预填", value = draft.assistantPrefill, onValueChange = { onDraftChange(draft.copy(assistantPrefill = it)) }, supportingText = "Claude 等支持 prefill 的后端会拼到 assistant 起始")
                    NutTavernGroupDivider()
                    NutTavernLabeledTextField(label = "代替角色发言场景的预填", value = draft.assistantImpersonation, onValueChange = { onDraftChange(draft.copy(assistantImpersonation = it)) }, supportingText = "代替角色发言时单独使用的 prefill,留空则沿用上方助手预填")
                }
            }
        }

        item(key = "preset-regex") {
            NutTavernGroupSection {
                NutTavernIconRow(
                    icon = Lucide.Regex,
                    title = "预设正则",
                    subtitle = presetRegexSubtitle(presetRegexScripts),
                    showTrailingChevron = true,
                    onClick = onOpenPresetRegexEditor,
                )
            }
        }

        if (allowDelete) {
            item(key = "delete") {
                NutTavernGroupSection {
                    NutTavernIconRow(icon = Lucide.Trash2, title = "删除预设", subtitle = "删除后不可恢复", destructive = true, onClick = onShowDeleteDialog)
                }
            }
        }
    }
}

/**
 * "预设正则"行 subtitle。空列表提示新建,否则给"总数 / 启用数"。
 */
private fun presetRegexSubtitle(scripts: List<com.nuttavern.data.regex.RegexScript>): String {
    if (scripts.isEmpty()) return "随预设生效的正则规则,点击新增"
    val enabled = scripts.count { !it.disabled }
    return "共 ${scripts.size} 条,启用 $enabled 条"
}

/**
 * 未链接条目行。与已链接条目视觉对齐,但 Switch 位置换成"链接"按钮。
 */
@Composable
private fun UnlinkedPromptRow(
    entry: PromptEntry,
    onLink: () -> Unit,
    onEdit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onEdit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.name.ifBlank { entry.identifier },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = promptEntrySubtitle(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onLink) { Text("链接") }
        }
    }
}

// endregion

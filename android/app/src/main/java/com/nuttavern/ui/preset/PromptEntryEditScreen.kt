package com.nuttavern.ui.preset

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.nuttavern.data.preset.GenerationType
import com.nuttavern.data.preset.InjectionPosition
import com.nuttavern.data.preset.PromptEntry
import com.nuttavern.data.preset.PromptRole
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernLabeledTextField
import com.nuttavern.ui.components.NutTavernNumericField
import com.nuttavern.ui.components.NutTavernSectionLabel
import com.nuttavern.ui.components.NumericParser
import kotlinx.serialization.json.Json

/**
 * 单条提示词条目的编辑页(全屏 Dialog 形态)。
 *
 * 本编辑页与预设编辑页([PresetEditScreen])解耦:接收 [initial] 草稿,通过 [onSave] 把
 * 编辑后的条目回填给预设编辑页的本地草稿,**不直接写仓库** — 仓库写入仍由预设保存时统一落库。
 *
 * 字段分组:
 * 1. **基础**:[PromptEntry.identifier](只读,系统条目固定 id)/ [PromptEntry.name] /
 *    [PromptEntry.role] / [PromptEntry.content];marker 条目的 content 不可编辑(由拼接管线
 *    运行时填充)。
 * 2. **注入控制**:[PromptEntry.injectionPosition] / [PromptEntry.injectionDepth] /
 *    [PromptEntry.injectionOrder] / [PromptEntry.injectionTrigger]。
 * 3. **行为开关**:[PromptEntry.systemPrompt] / [PromptEntry.forbidOverrides];marker 条目
 *    隐藏 forbid_overrides(没有内容覆盖一说)。
 *
 * 设计取舍:
 * - 走全屏 Dialog 而不是 Bottom Sheet:条目可能有较长 content,Dialog 沉浸式视觉与
 *   [com.nuttavern.ui.components.NutTavernFullScreenTextEditor] 保持一致;
 * - 关闭 = 直接丢弃改动,不弹"未保存"对话框 — 上层预设编辑页本身有未保存兜底,这里再加一层
 *   反而割裂。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptEntryEditScreen(
    initial: PromptEntry,
    onSave: (PromptEntry) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onUnlink: (() -> Unit)? = null,
) {
    BackHandler(enabled = true) { onDismiss() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            PromptEntryEditScreenContent(
                initial = initial,
                onSave = onSave,
                onDismiss = onDismiss,
                onDelete = onDelete,
                onUnlink = onUnlink,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptEntryEditScreenContent(
    initial: PromptEntry,
    onSave: (PromptEntry) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onUnlink: (() -> Unit)?,
) {
    var draft by rememberSaveable(initial.identifier, stateSaver = PromptEntrySaver) {
        mutableStateOf(initial)
    }
    val isDirty = draft != initial
    val canSave = draft.name.isNotBlank()
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showUnlinkDialog by remember { mutableStateOf(false) }

    val triggerDismiss: () -> Unit = {
        if (isDirty) showDiscardDialog = true else onDismiss()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("编辑提示词") },
                navigationIcon = {
                    IconButton(onClick = triggerDismiss) {
                        Icon(Lucide.X, contentDescription = "关闭")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { onSave(draft) },
                        enabled = canSave && isDirty,
                    ) { Text("完成") }
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
            item(key = "basic-label") {
                NutTavernSectionLabel(text = "基础")
            }
            item(key = "basic") {
                NutTavernGroupSection {
                    NutTavernLabeledTextField(
                        label = "名称",
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        placeholder = "如「主要提示词」「辅助提示词」",
                        singleLine = true,
                        isError = draft.name.isBlank(),
                        supportingText = if (draft.name.isBlank()) "名称不能为空" else null,
                    )
                    NutTavernGroupDivider()
                    EnumPickerRow(
                        label = "身份",
                        value = roleSerialName(draft.role),
                        options = PromptRole.entries.map { roleSerialName(it) },
                        onSelect = { picked -> draft = draft.copy(role = roleFromSerialName(picked)) },
                    )
                    if (!draft.marker) {
                        NutTavernGroupDivider()
                        NutTavernLabeledTextField(
                            label = "内容",
                            value = draft.content,
                            onValueChange = { draft = draft.copy(content = it) },
                            placeholder = "可使用 {{user}} / {{char}} / {{personality}} 等占位符",
                            minLines = 6,
                        )
                    } else {
                        NutTavernGroupDivider()
                        MarkerNotice()
                    }
                }
            }

            item(key = "injection-label") {
                NutTavernSectionLabel(text = "注入位置")
            }
            item(key = "injection") {
                NutTavernGroupSection {
                    EnumPickerRow(
                        label = "位置",
                        value = injectionPositionLabel(draft.injectionPosition),
                        options = InjectionPosition.entries.map { injectionPositionLabel(it) },
                        onSelect = { picked ->
                            draft = draft.copy(
                                injectionPosition = injectionPositionFromLabel(picked),
                            )
                        },
                    )
                    if (draft.injectionPosition == InjectionPosition.ABSOLUTE) {
                        NutTavernGroupDivider()
                        IntFieldRow(
                            label = "深度",
                            value = draft.injectionDepth,
                            onValueChange = { draft = draft.copy(injectionDepth = it) },
                            supportingText = "数字越大插入位置越靠上;4 表示倒数第 4 条之前",
                            min = 0,
                        )
                    }
                    NutTavernGroupDivider()
                    IntFieldRow(
                        label = "排序",
                        value = draft.injectionOrder,
                        onValueChange = { draft = draft.copy(injectionOrder = it) },
                        supportingText = "同深度时,数字小的排在前面;默认 100",
                        min = 0,
                    )
                    NutTavernGroupDivider()
                    InjectionTriggerRow(
                        triggers = draft.injectionTrigger,
                        onChange = { draft = draft.copy(injectionTrigger = it) },
                    )
                }
            }

            item(key = "behavior-label") {
                NutTavernSectionLabel(text = "行为")
            }
            item(key = "behavior") {
                NutTavernGroupSection {
                    SwitchEditRow(
                        label = "标记为系统条目",
                        subtitle = "如果角色卡里填了「系统提示」/「历史末尾指令」,这条会被替换",
                        checked = draft.systemPrompt,
                        onCheckedChange = { draft = draft.copy(systemPrompt = it) },
                    )
                    if (!draft.marker) {
                        NutTavernGroupDivider()
                        SwitchEditRow(
                            label = "禁止覆盖",
                            subtitle = "开启后,即使角色卡定义了对应字段也不会覆盖此条内容",
                            checked = draft.forbidOverrides,
                            onCheckedChange = { draft = draft.copy(forbidOverrides = it) },
                        )
                    }
                }
            }

            if (onUnlink != null) {
                item(key = "unlink") {
                    NutTavernGroupSection {
                        com.nuttavern.ui.components.NutTavernIconRow(
                            icon = Lucide.X,
                            title = "取消链接",
                            subtitle = "条目保留但不再参与拼接,可随时重新链接",
                            onClick = { showUnlinkDialog = true },
                        )
                    }
                }
            }

            if (onDelete != null) {
                item(key = "delete") {
                    NutTavernGroupSection {
                        com.nuttavern.ui.components.NutTavernIconRow(
                            icon = Lucide.Trash2,
                            title = "删除条目",
                            subtitle = "删除后会从该预设彻底移除,无法恢复",
                            destructive = true,
                            onClick = { showDeleteDialog = true },
                        )
                    }
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
                TextButton(onClick = {
                    showDiscardDialog = false
                    onDismiss()
                }) { Text("放弃修改") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }

    if (showDeleteDialog && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除条目?") },
            text = { Text("「${draft.name.ifBlank { draft.identifier }}」会被永久删除,无法恢复。") },
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

    if (showUnlinkDialog && onUnlink != null) {
        AlertDialog(
            onDismissRequest = { showUnlinkDialog = false },
            title = { Text("取消链接?") },
            text = { Text("「${draft.name.ifBlank { draft.identifier }}」将不再参与拼接,但条目定义保留,可随时重新链接。") },
            confirmButton = {
                TextButton(onClick = {
                    showUnlinkDialog = false
                    onUnlink()
                }) { Text("取消链接") }
            },
            dismissButton = {
                TextButton(onClick = { showUnlinkDialog = false }) { Text("返回") }
            },
        )
    }
}

@Composable
private fun MarkerNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "占位条目",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "实际内容由系统在发送时自动填充(聊天记录、角色描述、世界书等)。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumPickerRow(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    optionDescriptions: Map<String, String> = emptyMap(),
) {
    var showSheet by remember { mutableStateOf(false) }

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
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showSheet) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.nuttavern.ui.components.NutTavernSheetTitle(title = "选择$label")
                options.forEach { option ->
                    com.nuttavern.ui.components.NutTavernSelectableRow(
                        title = option,
                        subtitle = optionDescriptions[option],
                        selected = option == value,
                        onClick = {
                            onSelect(option)
                            showSheet = false
                        },
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 16.dp))
            }
        }
    }
}

@Composable
private fun IntFieldRow(
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

@Composable
private fun SwitchEditRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = { onCheckedChange(!checked) },
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
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
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
}

/**
 * 注入触发器多选行。空列表 = 任意生成类型都触发(对齐酒馆 select2 的 "All types (default)")。
 *
 * 这里用 [FilterChip] 做扁平多选,选项数固定 6 项,横向铺开高度可控。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InjectionTriggerRow(
    triggers: List<GenerationType>,
    onChange: (List<GenerationType>) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "筛选生成类型",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (triggers.isEmpty()) {
                    "未选则在所有生成类型下都生效"
                } else {
                    "仅在所选生成类型下生效"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GenerationType.entries.forEach { type ->
                    val selected = type in triggers
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = if (selected) triggers - type else triggers + type
                            onChange(next)
                        },
                        label = { Text(generationTypeLabel(type)) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
        }
    }
}

private fun roleSerialName(role: PromptRole): String = when (role) {
    PromptRole.SYSTEM -> "系统"
    PromptRole.USER -> "用户"
    PromptRole.ASSISTANT -> "助手"
}

private fun roleFromSerialName(value: String): PromptRole = when (value) {
    "用户" -> PromptRole.USER
    "助手" -> PromptRole.ASSISTANT
    else -> PromptRole.SYSTEM
}

private fun injectionPositionLabel(position: InjectionPosition): String = when (position) {
    InjectionPosition.RELATIVE -> "按预设顺序"
    InjectionPosition.ABSOLUTE -> "按深度插入到历史"
}

private fun injectionPositionFromLabel(label: String): InjectionPosition = when (label) {
    "按深度插入到历史" -> InjectionPosition.ABSOLUTE
    else -> InjectionPosition.RELATIVE
}

private fun generationTypeLabel(type: GenerationType): String = when (type) {
    GenerationType.NORMAL -> "正常"
    GenerationType.IMPERSONATE -> "代替角色发言"
    GenerationType.CONTINUE -> "续写"
    GenerationType.SWIPE -> "备选回复"
    GenerationType.REGENERATE -> "重新生成"
    GenerationType.QUIET -> "静默"
}

private val PromptEntrySaver: Saver<PromptEntry, String> = Saver(
    save = { value -> Json.encodeToString(PromptEntry.serializer(), value) },
    restore = { stored ->
        try {
            Json.decodeFromString(PromptEntry.serializer(), stored)
        } catch (e: Throwable) {
            android.util.Log.w("PromptEntrySaver", "restore failed, falling back to init", e)
            null
        }
    },
)

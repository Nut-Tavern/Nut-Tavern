package com.nuttavern.ui.character

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BookOpenText
import com.composables.icons.lucide.BookUser
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Regex
import com.composables.icons.lucide.Tags
import com.composables.icons.lucide.Trash2
import com.nuttavern.data.character.Character
import com.nuttavern.ui.components.NutTavernExpandableHeader
import com.nuttavern.ui.components.NutTavernFullScreenTextEditor
import com.nuttavern.ui.components.NutTavernGroupCard
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow
import com.nuttavern.ui.components.NutTavernLabeledTextField
import com.nuttavern.ui.components.NutTavernShapeTokens
import com.nuttavern.ui.viewmodel.CharacterViewModel
import kotlinx.serialization.json.Json

@Composable
fun CharacterEditScreen(
    characterId: String,
    onBack: () -> Unit,
    viewModel: CharacterViewModel = hiltViewModel(),
) {
    if (characterId == NEW_CHARACTER_PLACEHOLDER_ID) {
        val newDraftSeed = rememberSaveable(stateSaver = CharacterSaver) {
            mutableStateOf(viewModel.newCharacter())
        }
        CharacterEditScreenContent(
            initial = newDraftSeed.value,
            allowDelete = false,
            onSave = { edited ->
                viewModel.upsert(edited)
                onBack()
            },
            onDelete = onBack,
            onBack = onBack,
        )
        return
    }

    val source by remember(characterId, viewModel) {
        viewModel.findById(characterId)
    }.collectAsState(initial = null)
    val character = source

    if (character == null) {
        LoadingCharacterEditor(onBack = onBack)
        return
    }

    CharacterEditScreenContent(
        initial = character,
        onSave = { edited ->
            viewModel.upsert(edited)
            onBack()
        },
        onDelete = {
            viewModel.delete(character.id)
            onBack()
        },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadingCharacterEditor(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("编辑角色") },
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
private fun CharacterEditScreenContent(
    initial: Character,
    onSave: (Character) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    allowDelete: Boolean = true,
) {
    var draft by rememberSaveable(initial.id, stateSaver = CharacterSaver) {
        mutableStateOf(initial)
    }
    var tagText by rememberSaveable(initial.id) { mutableStateOf(initial.tags.joinToString(", ")) }
    var greetingsText by rememberSaveable(initial.id) { mutableStateOf(initial.alternateGreetings.joinToString("\n\n")) }
    val normalizedDraft = remember(draft, tagText, greetingsText) {
        draft.copy(
            tags = parseCommaSeparatedValues(tagText),
            alternateGreetings = parseParagraphValues(greetingsText),
        )
    }
    val isDirty = normalizedDraft != initial
    val canSave = normalizedDraft.name.isNotBlank()

    var advancedExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var fullScreenField by remember { mutableStateOf<CharacterTextField?>(null) }
    var showRegexEditor by remember { mutableStateOf(false) }
    var showLorebookBindSheet by remember { mutableStateOf(false) }
    var showCharacterBookEditor by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingNotice) {
        val name = pendingNotice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar("$name 暂未接入")
        pendingNotice = null
    }

    val triggerBack: () -> Unit = {
        if (isDirty) showUnsavedDialog = true else onBack()
    }
    BackHandler(enabled = true) { triggerBack() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("编辑角色") },
                navigationIcon = {
                    IconButton(onClick = triggerBack) { Icon(Lucide.ArrowLeft, "返回") }
                },
                actions = {
                    TextButton(
                        onClick = { onSave(normalizedDraft) },
                        enabled = canSave && isDirty,
                    ) { Text("保存") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            item(key = "avatar") {
                CharacterAvatarCard(onClick = { pendingNotice = "头像功能" })
            }
            item(key = "basic") {
                NutTavernGroupCard {
                    NutTavernLabeledTextField(
                        label = "角色名",
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        placeholder = "AI 扮演的角色名",
                        singleLine = true,
                        supportingText = if (canSave) null else "角色名不能为空",
                        isError = !canSave,
                    )
                    NutTavernGroupDivider(inset = 0.dp)
                    NutTavernLabeledTextField(
                        label = "角色描述",
                        value = draft.description,
                        onValueChange = { draft = draft.copy(description = it) },
                        placeholder = "角色身份、外貌、背景等",
                        minLines = 4,
                        onOpenFullScreen = { fullScreenField = CharacterTextField.Description },
                    )
                    NutTavernGroupDivider(inset = 0.dp)
                    NutTavernLabeledTextField(
                        label = "性格",
                        value = draft.personality,
                        onValueChange = { draft = draft.copy(personality = it) },
                        placeholder = "角色性格和说话风格",
                        minLines = 3,
                        onOpenFullScreen = { fullScreenField = CharacterTextField.Personality },
                    )
                    NutTavernGroupDivider(inset = 0.dp)
                    NutTavernLabeledTextField(
                        label = "场景",
                        value = draft.scenario,
                        onValueChange = { draft = draft.copy(scenario = it) },
                        placeholder = "当前故事背景或初始场景",
                        minLines = 3,
                        onOpenFullScreen = { fullScreenField = CharacterTextField.Scenario },
                    )
                }
            }
            item(key = "messages") {
                NutTavernGroupCard {
                    NutTavernLabeledTextField(
                        label = "首条消息",
                        value = draft.firstMessage,
                        onValueChange = { draft = draft.copy(firstMessage = it) },
                        placeholder = "新会话第一条 assistant 消息",
                        minLines = 3,
                        onOpenFullScreen = { fullScreenField = CharacterTextField.FirstMessage },
                    )
                    NutTavernGroupDivider(inset = 0.dp)
                    NutTavernLabeledTextField(
                        label = "示范对话",
                        value = draft.messageExample,
                        onValueChange = { draft = draft.copy(messageExample = it) },
                        placeholder = "用 <START> 分隔多段示范对话",
                        minLines = 4,
                        onOpenFullScreen = { fullScreenField = CharacterTextField.MessageExample },
                    )
                    NutTavernGroupDivider(inset = 0.dp)
                    NutTavernLabeledTextField(
                        label = "备用问候",
                        value = greetingsText,
                        onValueChange = { greetingsText = it },
                        placeholder = "每段问候用空行分隔;单段问候内不要留空行",
                        minLines = 3,
                    )
                }
            }
            item(key = "advanced-header") {
                NutTavernExpandableHeader(
                    title = "高级字段",
                    expanded = advancedExpanded,
                    onClick = { advancedExpanded = !advancedExpanded },
                )
            }
            item(key = "advanced-body") {
                AnimatedVisibility(visible = advancedExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing)) {
                        NutTavernGroupCard {
                            NutTavernLabeledTextField(
                                label = "系统提示",
                                value = draft.systemPrompt,
                                onValueChange = { draft = draft.copy(systemPrompt = it) },
                                placeholder = "角色专属 system prompt",
                                minLines = 3,
                                onOpenFullScreen = { fullScreenField = CharacterTextField.SystemPrompt },
                            )
                            NutTavernGroupDivider(inset = 0.dp)
                            NutTavernLabeledTextField(
                                label = "历史后指令",
                                value = draft.postHistoryInstructions,
                                onValueChange = { draft = draft.copy(postHistoryInstructions = it) },
                                placeholder = "拼在聊天历史之后的补充指令",
                                minLines = 3,
                                onOpenFullScreen = { fullScreenField = CharacterTextField.PostHistoryInstructions },
                            )
                            NutTavernGroupDivider(inset = 0.dp)
                            NutTavernLabeledTextField(
                                label = "创建者",
                                value = draft.creator,
                                onValueChange = { draft = draft.copy(creator = it) },
                                singleLine = true,
                            )
                            NutTavernGroupDivider(inset = 0.dp)
                            NutTavernLabeledTextField(
                                label = "角色版本",
                                value = draft.characterVersion,
                                onValueChange = { draft = draft.copy(characterVersion = it) },
                                singleLine = true,
                            )
                            NutTavernGroupDivider(inset = 0.dp)
                            NutTavernLabeledTextField(
                                label = "作者备注",
                                value = draft.creatorNotes,
                                onValueChange = { draft = draft.copy(creatorNotes = it) },
                                minLines = 3,
                                onOpenFullScreen = { fullScreenField = CharacterTextField.CreatorNotes },
                            )
                            NutTavernGroupDivider(inset = 0.dp)
                            NutTavernLabeledTextField(
                                label = "标签",
                                value = tagText,
                                onValueChange = { tagText = it },
                                placeholder = "用英文逗号或中文逗号分隔",
                                singleLine = true,
                            )
                            NutTavernGroupDivider(inset = 0.dp)
                            VerbosityRow(
                                value = draft.verbosity,
                                onChange = { draft = draft.copy(verbosity = it) },
                            )
                        }
                        NutTavernGroupSection {
                            NutTavernIconRow(
                                icon = Lucide.BookOpenText,
                                title = "角色世界书",
                                subtitle = if (draft.lorebookIds.isEmpty()) "未绑定"
                                    else "已绑定 ${draft.lorebookIds.size} 本",
                                showTrailingChevron = true,
                                onClick = { showLorebookBindSheet = true },
                            )
                            NutTavernGroupDivider()
                            NutTavernIconRow(
                                icon = Lucide.BookOpenText,
                                title = "角色内嵌世界书",
                                subtitle = characterBookSubtitle(draft.characterBook),
                                showTrailingChevron = true,
                                onClick = { showCharacterBookEditor = true },
                            )
                            NutTavernGroupDivider()
                            NutTavernIconRow(
                                icon = Lucide.Regex,
                                title = "角色专属正则",
                                subtitle = regexEntrySubtitle(draft.regexScripts),
                                showTrailingChevron = true,
                                onClick = { showRegexEditor = true },
                            )
                            NutTavernGroupDivider()
                            NutTavernIconRow(
                                icon = Lucide.Tags,
                                title = "导入 / 导出",
                                subtitle = "PNG / JSON 导入导出后续接入",
                                showTrailingChevron = true,
                                onClick = { pendingNotice = "角色导入导出" },
                            )
                        }
                    }
                }
            }
            if (allowDelete) {
                item(key = "danger") {
                    NutTavernGroupSection {
                        NutTavernIconRow(
                            icon = Lucide.Trash2,
                            title = "删除角色",
                            subtitle = "删除后角色卡信息将丢失",
                            destructive = true,
                            onClick = { showDeleteDialog = true },
                        )
                    }
                }
            }
        }
    }

    FullScreenCharacterField(
        field = fullScreenField,
        character = draft,
        onChange = { draft = it },
        onDismiss = { fullScreenField = null },
    )

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除角色?") },
            text = { Text("删除后角色卡信息将丢失,无法恢复。") },
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
            title = { Text("保存改动?") },
            text = { Text("当前角色还有未保存的修改,直接退出将丢失这些修改。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        if (canSave) onSave(normalizedDraft)
                    },
                    enabled = canSave,
                ) { Text("保存并退出") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showUnsavedDialog = false }) { Text("继续编辑") }
                    TextButton(
                        onClick = {
                            showUnsavedDialog = false
                            onBack()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("不保存退出") }
                }
            },
        )
    }

    if (showRegexEditor) {
        CharacterRegexEditor(
            scripts = draft.regexScripts,
            onChange = { next -> draft = draft.copy(regexScripts = next) },
            onBack = { showRegexEditor = false },
        )
    }

    if (showCharacterBookEditor) {
        CharacterLorebookEditor(
            characterBook = draft.characterBook,
            onChange = { next -> draft = draft.copy(characterBook = next) },
            onBack = { showCharacterBookEditor = false },
        )
    }

    if (showLorebookBindSheet) {
        CharacterLorebookBindSheet(
            boundIds = draft.lorebookIds.toSet(),
            onApply = { ids ->
                draft = draft.copy(lorebookIds = ids.toList())
                showLorebookBindSheet = false
            },
            onDismiss = { showLorebookBindSheet = false },
        )
    }
}

/**
 * "角色专属正则"行的 subtitle 文案。空列表时提示新增,否则给"总数 / 启用数"。
 */
private fun regexEntrySubtitle(scripts: List<com.nuttavern.data.regex.RegexScript>): String {
    if (scripts.isEmpty()) return "尚未添加,点击新增"
    val enabled = scripts.count { !it.disabled }
    return "共 ${scripts.size} 条,启用 $enabled 条"
}

@Composable
private fun CharacterAvatarCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(112.dp),
                shape = RoundedCornerShape(NutTavernShapeTokens.AvatarPlaceholder),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onClick,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Lucide.BookUser,
                        contentDescription = "选择角色头像",
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FullScreenCharacterField(
    field: CharacterTextField?,
    character: Character,
    onChange: (Character) -> Unit,
    onDismiss: () -> Unit,
) {
    val target = field ?: return
    NutTavernFullScreenTextEditor(
        visible = true,
        title = target.title,
        fieldLabel = target.label,
        value = target.read(character),
        onValueChange = { value -> onChange(target.write(character, value)) },
        onSave = onDismiss,
        onDismiss = onDismiss,
        placeholder = target.placeholder,
    )
}

private enum class CharacterTextField(
    val title: String,
    val label: String,
    val placeholder: String,
    val read: (Character) -> String,
    val write: (Character, String) -> Character,
) {
    Description(
        title = "编辑角色描述",
        label = "角色描述",
        placeholder = "角色身份、外貌、背景等",
        read = { it.description },
        write = { character, value -> character.copy(description = value) },
    ),
    Personality(
        title = "编辑性格",
        label = "性格",
        placeholder = "角色性格和说话风格",
        read = { it.personality },
        write = { character, value -> character.copy(personality = value) },
    ),
    Scenario(
        title = "编辑场景",
        label = "场景",
        placeholder = "当前故事背景或初始场景",
        read = { it.scenario },
        write = { character, value -> character.copy(scenario = value) },
    ),
    FirstMessage(
        title = "编辑首条消息",
        label = "首条消息",
        placeholder = "新会话第一条 assistant 消息",
        read = { it.firstMessage },
        write = { character, value -> character.copy(firstMessage = value) },
    ),
    MessageExample(
        title = "编辑示范对话",
        label = "示范对话",
        placeholder = "用 <START> 分隔多段示范对话",
        read = { it.messageExample },
        write = { character, value -> character.copy(messageExample = value) },
    ),
    SystemPrompt(
        title = "编辑系统提示",
        label = "系统提示",
        placeholder = "角色专属 system prompt",
        read = { it.systemPrompt },
        write = { character, value -> character.copy(systemPrompt = value) },
    ),
    PostHistoryInstructions(
        title = "编辑历史后指令",
        label = "历史后指令",
        placeholder = "拼在聊天历史之后的补充指令",
        read = { it.postHistoryInstructions },
        write = { character, value -> character.copy(postHistoryInstructions = value) },
    ),
    CreatorNotes(
        title = "编辑作者备注",
        label = "作者备注",
        placeholder = "作者说明、使用建议等",
        read = { it.creatorNotes },
        write = { character, value -> character.copy(creatorNotes = value) },
    ),
}

private fun parseCommaSeparatedValues(value: String): List<String> {
    return value.split(',', '，')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private fun parseParagraphValues(value: String): List<String> {
    return value.split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private val CharacterSaver: Saver<Character, String> = Saver(
    save = { value -> Json.encodeToString(Character.serializer(), value) },
    restore = { stored ->
        try {
            Json.decodeFromString(Character.serializer(), stored)
        } catch (e: Throwable) {
            android.util.Log.w("CharacterSaver", "restore failed, falling back to init", e)
            null
        }
    },
)

/**
 * 回复长度档位选择行。对齐酒馆 verbosity_levels(auto / low / medium / high)+ 自定义。
 *
 * - 空字符串 = auto = 不发送 verbosity 字段;
 * - "low" / "medium" / "high" 标准档位;
 * - 选"自定义"展开对话框输入任意字符串(为未来后端枚举如 minimal / max 留空间)。
 *
 * 字段挂在 Character 上是有意为之 — verbosity 与"这个角色想多话还是少话"语义贴合,跨预设
 * 复用时不希望被覆盖。详见 [Character.verbosity] KDoc。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerbosityRow(
    value: String,
    onChange: (String) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }

    val standardOptions = remember {
        listOf(
            "" to "自动",
            "low" to "低",
            "medium" to "中",
            "high" to "高",
        )
    }
    val isStandard = standardOptions.any { it.first == value }
    val displayText = standardOptions.firstOrNull { it.first == value }?.second
        ?: if (value.isBlank()) "自动" else value

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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "回复长度",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "控制回复长度。自动 = 让模型自己决定",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = displayText,
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
                com.nuttavern.ui.components.NutTavernSheetTitle(title = "选择回复长度")
                standardOptions.forEach { (raw, label) ->
                    com.nuttavern.ui.components.NutTavernSelectableRow(
                        title = label,
                        selected = isStandard && value == raw,
                        onClick = {
                            onChange(raw)
                            showSheet = false
                        },
                    )
                }
                com.nuttavern.ui.components.NutTavernSelectableRow(
                    title = "自定义",
                    subtitle = if (!isStandard && value.isNotBlank()) "当前值:$value" else null,
                    selected = !isStandard && value.isNotBlank(),
                    onClick = {
                        showSheet = false
                        showCustomDialog = true
                    },
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 16.dp))
            }
        }
    }

    if (showCustomDialog) {
        var draft by rememberSaveable {
            mutableStateOf(if (isStandard) "" else value)
        }
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("自定义回复长度") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "输入要发送给后端的档位值,例如 minimal / max。留空等同于自动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onChange(draft.trim())
                        showCustomDialog = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) { Text("取消") }
            },
        )
    }
}

/**
 * "角色内嵌世界书"行的 subtitle 文案。
 */
private fun characterBookSubtitle(book: com.nuttavern.data.character.CharacterBook?): String {
    if (book == null || book.entries.isEmpty()) return "尚未添加,点击新增"
    val enabled = book.entries.count { it.enabled }
    return "共 ${book.entries.size} 条,启用 $enabled 条"
}

/**
 * 角色绑定世界书选择 Sheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharacterLorebookBindSheet(
    boundIds: Set<String>,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val lorebookViewModel: com.nuttavern.ui.viewmodel.LorebookViewModel = hiltViewModel()
    val lorebooks by lorebookViewModel.lorebooks.collectAsState()
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedIds by remember { mutableStateOf(boundIds) }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f).padding(horizontal = 16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                com.nuttavern.ui.components.NutTavernSheetTitle(title = "绑定世界书", modifier = Modifier.weight(1f))
                TextButton(onClick = { onApply(selectedIds) }) { Text("应用") }
            }
            if (lorebooks.isEmpty()) {
                Text("暂无世界书,请先在设置中创建", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lorebooks, key = { it.id }) { book ->
                        com.nuttavern.ui.regex.MultiSelectPickerCard(
                            title = book.name.ifBlank { "未命名世界书" },
                            subtitle = if (book.entries.isEmpty()) "暂无条目" else "共 ${book.entries.size} 个条目",
                            enabled = book.id in selectedIds,
                            onToggle = { enabled -> selectedIds = if (enabled) selectedIds + book.id else selectedIds - book.id },
                        )
                    }
                }
            }
        }
    }
}

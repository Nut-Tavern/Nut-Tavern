package com.nuttavern.ui.persona

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.UserRound
import com.nuttavern.data.persona.PersonaPosition
import com.nuttavern.data.persona.PersonaRole
import com.nuttavern.data.persona.UserPersona
import com.nuttavern.ui.components.NutTavernFullScreenTextEditor
import com.nuttavern.ui.components.NutTavernExpandableHeader
import com.nuttavern.ui.components.NutTavernGroupCard
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow
import com.nuttavern.ui.components.NutTavernLabeledTextField
import com.nuttavern.ui.components.NutTavernNumericField
import com.nuttavern.ui.components.NumericParser
import com.nuttavern.ui.components.NutTavernShapeTokens
import com.nuttavern.ui.viewmodel.UserPersonaViewModel
import kotlinx.serialization.json.Json

/**
 * 用户身份编辑页。
 *
 * 视觉规则:
 * - 顶栏 "编辑用户身份" + 右侧 "保存"(name 为空 / 未改动时禁用);返回键带未保存提醒。
 * - 主区域顺序:头像方框 / 用户身份名 / 用户身份内容(多行 + 全屏入口)。
 * - 高级折叠区:身份卡标题 / 注入位置 / 注入深度(条件) / 注入角色 / 绑定世界书 / 绑定角色。
 *   绑定世界书 / 绑定角色按钮可见但点击弹"待接入"。
 * - 底部独立"删除身份"卡(危险操作分组),走二次确认弹窗;新建路径 [allowDelete] = false 时隐藏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserPersonaEditScreen(
    personaId: String,
    onBack: () -> Unit,
    viewModel: UserPersonaViewModel = hiltViewModel(),
) {
    // 新建路径:列表 "+" 按钮传占位 id 进来,这里直接起一份空草稿,**保存时才 upsert**。
    if (personaId == NEW_PERSONA_PLACEHOLDER_ID) {
        // 稳定 UUID:即便发生 process death,内层的 rememberSaveable(draft.id) 也能恢复同一份草稿。
        val newDraftSeed = rememberSaveable(stateSaver = UserPersonaSaver) {
            mutableStateOf(viewModel.newPersona())
        }
        UserPersonaEditScreenContent(
            initial = newDraftSeed.value,
            allowDelete = false,
            onSave = { edited ->
                viewModel.upsert(edited)
                onBack()
            },
            onDelete = onBack, // 新建草稿不会触发(allowDelete=false 已隐藏入口),回退兜底
            onBack = onBack,
        )
        return
    }

    // 编辑现有身份。findById 是普通 Flow(不挂 stateIn,避免 viewModelScope 累积订阅),
    // 这里 remember 一次,collectAsState 给 null 初值表示"还没拿到"。
    val source by remember(personaId, viewModel) {
        viewModel.findById(personaId)
    }.collectAsState(initial = null)
    val persona = source

    // 数据从仓库初次到达前显示空 Scaffold;到达后只把它当 "初值" 用,后续编辑都靠本地草稿。
    if (persona == null) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("编辑用户身份") },
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

    UserPersonaEditScreenContent(
        initial = persona,
        onSave = { edited ->
            viewModel.upsert(edited)
            onBack()
        },
        onDelete = {
            viewModel.delete(persona.id)
            onBack()
        },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserPersonaEditScreenContent(
    initial: UserPersona,
    onSave: (UserPersona) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    allowDelete: Boolean = true,
) {
    var draft by rememberSaveable(initial.id, stateSaver = UserPersonaSaver) {
        mutableStateOf(initial)
    }
    val isDirty = draft != initial
    val canSave = draft.name.isNotBlank()

    var showFullScreenDescription by remember { mutableStateOf(false) }
    var showPositionSheet by remember { mutableStateOf(false) }
    var showRoleSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

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
                title = { Text("编辑用户身份") },
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
                AvatarCard(onClick = { pendingNotice = "头像功能" })
            }
            item(key = "basics") {
                NutTavernGroupCard {
                    NutTavernLabeledTextField(
                        label = "用户身份名",
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        placeholder = "AI 会这样称呼你",
                        singleLine = true,
                        supportingText = if (canSave) null else "用户身份名不能为空",
                        isError = !canSave,
                    )
                    NutTavernGroupDivider(inset = 0.dp)
                    NutTavernLabeledTextField(
                        label = "用户身份内容",
                        value = draft.description,
                        onValueChange = { draft = draft.copy(description = it) },
                        placeholder = "告诉 AI 你是谁、说话风格等",
                        singleLine = false,
                        minLines = 4,
                        trailingAction = {
                            IconButton(onClick = { showFullScreenDescription = true }) {
                                Icon(Lucide.Maximize2, "全屏编辑用户身份内容")
                            }
                        },
                    )
                }
            }
            item(key = "advanced-header") {
                NutTavernExpandableHeader(
                    title = "高级",
                    expanded = advancedExpanded,
                    onClick = { advancedExpanded = !advancedExpanded },
                )
            }
            item(key = "advanced-body") {
                AnimatedVisibility(visible = advancedExpanded) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
                    ) {
                        NutTavernGroupCard {
                            NutTavernLabeledTextField(
                                label = "身份卡标题",
                                value = draft.title,
                                onValueChange = { draft = draft.copy(title = it) },
                                placeholder = "仅自己可见的标签",
                                singleLine = true,
                            )
                            NutTavernGroupDivider(inset = 0.dp)
                            EnumSelectorRow(
                                label = "注入位置",
                                value = draft.position.displayName,
                                onClick = { showPositionSheet = true },
                            )
                            if (draft.position == PersonaPosition.AT_DEPTH) {
                                NutTavernGroupDivider(inset = 0.dp)
                                DepthRow(
                                    value = draft.depth,
                                    onValueChange = { draft = draft.copy(depth = it) },
                                )
                            }
                            NutTavernGroupDivider(inset = 0.dp)
                            EnumSelectorRow(
                                label = "注入角色",
                                value = draft.role.displayName,
                                onClick = { showRoleSheet = true },
                            )
                        }
                        NutTavernGroupSection {
                            NutTavernIconRow(
                                icon = Lucide.BookOpenText,
                                title = "绑定世界书",
                                subtitle = "等世界书模块上线后启用",
                                showTrailingChevron = true,
                                onClick = { pendingNotice = "世界书绑定" },
                            )
                            NutTavernGroupDivider()
                            NutTavernIconRow(
                                icon = Lucide.BookUser,
                                title = "绑定角色",
                                subtitle = "切到对应角色时自动启用本身份",
                                showTrailingChevron = true,
                                onClick = { pendingNotice = "角色绑定" },
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
                            title = "删除身份",
                            subtitle = "删除后该身份的全部信息将丢失",
                            destructive = true,
                            onClick = { showDeleteDialog = true },
                        )
                    }
                }
            }
        }
    }

    NutTavernFullScreenTextEditor(
        visible = showFullScreenDescription,
        title = "编辑用户身份内容",
        fieldLabel = "用户身份内容",
        value = draft.description,
        onValueChange = { draft = draft.copy(description = it) },
        onSave = { showFullScreenDescription = false },
        onDismiss = { showFullScreenDescription = false },
        placeholder = "告诉 AI 你是谁、说话风格等",
    )

    PersonaPositionSheet(
        visible = showPositionSheet,
        selected = draft.position,
        onSelect = {
            draft = draft.copy(position = it)
            showPositionSheet = false
        },
        onDismiss = { showPositionSheet = false },
    )

    PersonaRoleSheet(
        visible = showRoleSheet,
        selected = draft.role,
        onSelect = {
            draft = draft.copy(role = it)
            showRoleSheet = false
        },
        onDismiss = { showRoleSheet = false },
    )

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除身份?") },
            text = { Text("删除后该身份的全部信息将丢失,无法恢复。") },
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
            text = { Text("当前身份还有未保存的修改,直接退出将丢失这些修改。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        if (canSave) onSave(draft)
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
}

/**
 * 头像方框。当前阶段点击仅提示"头像功能尚未接入";后端阶段把回调改成"启动相册选择"即可。
 */
@Composable
private fun AvatarCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(96.dp),
                shape = RoundedCornerShape(NutTavernShapeTokens.AvatarPlaceholder),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onClick,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Lucide.UserRound,
                        contentDescription = "选择头像",
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
    }
}

/**
 * 卡内"标签 + 当前值 + chevron"行,点击进 Sheet 选择器。
 *
 * **设计系统例外**:NutTavernIconRow 是"图标 + 标题"语义,没有"右侧值预览"槽。
 * 当前只在身份编辑页用,后续如果出现第二处再上提到设计系统(允许 NutTavernIconRow
 * 的 trailing slot 直接表达"值 + chevron"组合)。
 */
@Composable
private fun EnumSelectorRow(
    label: String,
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
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
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
}

/**
 * 注入深度数字输入。仅 [PersonaPosition.AT_DEPTH] 时显示。
 *
 * 走公共组件 [NutTavernNumericField]:支持清空中间态 / 超范围标红不写回,与预设、工具设置统一行为。
 */
@Composable
private fun DepthRow(value: Int, onValueChange: (Int) -> Unit) {
    NutTavernNumericField(
        label = "注入深度",
        value = value,
        onValueChange = { it?.let(onValueChange) },
        parser = NumericParser.IntParser,
        min = UserPersona.MIN_DEPTH,
        max = UserPersona.MAX_DEPTH,
        helperText = "从底向上数第 N 条消息后插入,范围 ${UserPersona.MIN_DEPTH}–${UserPersona.MAX_DEPTH}",
    )
}

/**
 * [UserPersona] 的 saveable Saver。
 *
 * 把 persona 序列化成 JSON 字符串存进 SaveableStateRegistry,旋转 / 进程死亡时草稿不丢。
 * 写入失败 / 反序列化失败时返回 null,Compose 会回退到 init 块重建。
 */
private val UserPersonaSaver: Saver<UserPersona, String> = Saver(
    save = { value -> Json.encodeToString(UserPersona.serializer(), value) },
    restore = { stored ->
        try {
            Json.decodeFromString(UserPersona.serializer(), stored)
        } catch (e: Throwable) {
            android.util.Log.w("UserPersonaSaver", "restore failed, falling back to init", e)
            null
        }
    },
)

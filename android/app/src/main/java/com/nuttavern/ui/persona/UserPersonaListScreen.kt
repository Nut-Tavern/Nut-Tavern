package com.nuttavern.ui.persona

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
import com.nuttavern.data.persona.UserPersona
import com.nuttavern.ui.components.NutTavernShapeTokens
import com.nuttavern.ui.viewmodel.UserPersonaViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 用户身份列表页。
 *
 * 视觉规则:
 * - 单列卡片,头像 + 标题(title 优先,留空回退到 name) + 描述摘要;
 * - "无"伪卡固定在第 0 位,不可拖、无编辑键、无拖把手,**且不参与搜索过滤**(永远显示);
 * - 真实身份按"创建顺序"排,新建追加到末尾;允许用户长按 [com.composables.icons.lucide.GripVertical] 拖动排序;
 * - 搜索框胶囊形,与 ProviderListScreen / CharacterListScreen 一致;搜索过滤态下不允许拖动;
 * - 当前默认身份在 title 位右侧加 "默认" 胶囊;
 * - 点击卡片主区域 → 设默认弹窗;已是默认的卡片主区域不响应。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserPersonaListScreen(
    onBack: () -> Unit,
    onOpenPersonaDetail: (personaId: String) -> Unit,
    viewModel: UserPersonaViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    var pendingDefault by remember { mutableStateOf<UserPersona?>(null) }
    var query by remember { mutableStateOf("") }

    val isFiltering = query.isNotBlank()
    // 搜索范围:title / name / description / 注入位置 displayName。命中任何字段都算匹配,
    // 与 CharacterListScreen 的"name + description + tags"风格对齐。
    // "无"伪卡始终保留在过滤结果里,作为"立刻切到无身份"的稳定入口。
    val filteredItems = remember(items, query) {
        if (!isFiltering) items
        else items.filter { item ->
            val persona = item.persona
            persona.isNonePersona ||
                persona.title.contains(query, ignoreCase = true) ||
                persona.name.contains(query, ignoreCase = true) ||
                persona.description.contains(query, ignoreCase = true) ||
                persona.position.displayName.contains(query, ignoreCase = true)
        }
    }
    val hasNoMatch = isFiltering &&
        filteredItems.none { !it.persona.isNonePersona }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("用户身份") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // 直接路由到编辑页传一个"新身份"占位 id;编辑页保存时才真正 upsert,
                        // 用户取消则不留下任何残骸,避免列表里出现空 name 身份。
                        onOpenPersonaDetail(NEW_PERSONA_PLACEHOLDER_ID)
                    }) {
                        Icon(Lucide.Plus, contentDescription = "新增身份")
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
            UserPersonaSearchBar(value = query, onValueChange = { query = it })

            UserPersonaListBody(
                items = filteredItems,
                reorderable = !isFiltering,
                showEmptyHint = hasNoMatch,
                emptyHintText = "没有匹配的身份",
                onCardClick = { persona, isDefault ->
                    if (!isDefault) pendingDefault = persona
                },
                onEdit = onOpenPersonaDetail,
                onCommitOrder = viewModel::reorderRealPersonas,
                modifier = Modifier.fillMaxSize(),
                )
            }
        }

    val target = pendingDefault
    if (target != null) {
        SetDefaultPersonaDialog(
            persona = target,
            onConfirm = {
                viewModel.setDefault(target.id)
                pendingDefault = null
            },
            onDismiss = { pendingDefault = null },
        )
    }
}

/**
 * 搜索栏。规则与 [com.nuttavern.ui.character.CharacterListScreen] 对齐:
 * 不使用 M3 DockedSearchBar,纯 BasicTextField + 胶囊外壳,避免展开 / 建议这套对纯过滤场景过重的交互。
 */
@Composable
private fun UserPersonaSearchBar(value: String, onValueChange: (String) -> Unit) {
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
                        text = "搜索身份",
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun UserPersonaListBody(
    items: List<UserPersonaViewModel.PersonaListItem>,
    reorderable: Boolean,
    showEmptyHint: Boolean,
    emptyHintText: String,
    onCardClick: (UserPersona, isDefault: Boolean) -> Unit,
    onEdit: (String) -> Unit,
    onCommitOrder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var longPressPersonaId by remember { mutableStateOf<String?>(null) }
    var longPressPersonaName by remember { mutableStateOf("") }
    var longPressIsDefault by remember { mutableStateOf(false) }
    // 列表里 "无" 伪卡固定第 0 位,不参与拖动;只对真实身份做 reorder。
    // 把伪卡 / 真实拆成两段,分别处理。
    val noneItem = items.firstOrNull { it.persona.isNonePersona }
    val realItems = remember(items) { items.filterNot { it.persona.isNonePersona } }
    var localOrder by remember { mutableStateOf(realItems) }
    // 同步策略:每次上游推新都跑这条 effect。
    // - 若 id 集合一致(拖动中或仅 isDefault 标志变化)→ 按 id 对齐 *替换* 本地条目,
    //   **保留 localOrder 当前的顺序**(继续拖动 / 提交后未来上游会同步);
    // - 若 id 集合不同(新增 / 删除 / 外部 reorder 已落库/ 搜索过滤变化)→ 整体替换为上游顺序。
    LaunchedEffect(realItems) {
        val localIdsSet = localOrder.map { it.persona.id }.toSet()
        val upstreamIdsSet = realItems.map { it.persona.id }.toSet()
        localOrder = if (localIdsSet == upstreamIdsSet) {
            val byId = realItems.associateBy { it.persona.id }
            localOrder.mapNotNull { byId[it.persona.id] }
        } else {
            realItems
        }
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // "无"伪卡占第 0 行,真实身份从第 1 行起;reorderable 给的索引是全列表索引,
        // 这里的 `from.index - 1` / `to.index - 1` 才是真实身份在 [localOrder] 里的位置。
        val fromIndex = from.index - REAL_LIST_OFFSET
        val toIndex = to.index - REAL_LIST_OFFSET
        if (fromIndex in localOrder.indices && toIndex in localOrder.indices) {
            localOrder = localOrder.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier = modifier,
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (noneItem != null) {
            item(key = noneItem.persona.id) {
                UserPersonaCard(
                    persona = noneItem.persona,
                    isDefault = noneItem.isDefault,
                    onClick = { onCardClick(noneItem.persona, noneItem.isDefault) },
                    dragHandle = { Spacer(Modifier.size(20.dp)) },
                )
            }
        }
        if (showEmptyHint) {
            item(key = "__empty_hint__") {
                UserPersonaListEmpty(text = emptyHintText)
            }
        }
        items(localOrder, key = { it.persona.id }) { item ->
            ReorderableItem(state = reorderState, key = item.persona.id) { isDragging ->
                UserPersonaCard(
                    persona = item.persona,
                    isDefault = item.isDefault,
                    elevated = isDragging,
                    onClick = { onEdit(item.persona.id) },
                    onLongClick = {
                        longPressPersonaId = item.persona.id
                        longPressPersonaName = item.persona.name
                        longPressIsDefault = item.isDefault
                    },
                    dragHandle = {
                        if (reorderable) {
                            UserPersonaDragHandle(
                                modifier = Modifier.draggableHandle(
                                    onDragStopped = { onCommitOrder(localOrder.map { it.persona.id }) },
                                ),
                            )
                        } else {
                            // 过滤态保留 20dp 占位维持行高,避免拖把手缺席让卡片宽度跳变。
                            Spacer(Modifier.size(20.dp))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun UserPersonaListEmpty(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
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
private fun SetDefaultPersonaDialog(
    persona: UserPersona,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val displayName = when {
        persona.isNonePersona -> "无"
        persona.title.isNotBlank() -> persona.title
        persona.name.isNotBlank() -> persona.name
        else -> "未命名身份"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设为默认身份?") },
        text = {
            Text(
                "将「$displayName」设为默认用户身份。\n\n" +
                    "如果当前角色卡或会话已绑定其他身份,会优先使用绑定的身份。",
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

private const val REAL_LIST_OFFSET = 1

/**
 * 路由占位 id。列表 "+" 按钮跳详情页时传这个值,详情页识别后用 [UserPersonaViewModel.newPersona] 起草稿,
 * **保存时才真正 upsert**;用户中途返回不会留下空身份。
 */
internal const val NEW_PERSONA_PLACEHOLDER_ID = "__new__"

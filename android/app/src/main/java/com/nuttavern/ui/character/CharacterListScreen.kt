package com.nuttavern.ui.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.nuttavern.data.character.Character
import com.nuttavern.ui.components.NutTavernShapeTokens
import com.nuttavern.ui.viewmodel.CharacterViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

internal const val NEW_CHARACTER_PLACEHOLDER_ID = "__new__"

/**
 * 角色列表页。
 *
 * 视觉规则:
 * - 单列长卡片,与 [CharacterCard] 同规格(32dp 头像 + 标题 + 副标题 + 32dp 编辑键 + 拖把手)。
 * - 搜索框胶囊形,与 ProviderListScreen 一致。
 *
 * 拖动排序:
 * - 卡片右侧 [Lucide.GripVertical] 把手长按拖动,松手提交到仓库。
 * - 不允许在搜索过滤态拖动:过滤后看到的不是真实顺序,拖动会让人困惑(对齐 Provider 列表)。
 *
 * 删除入口走编辑页底部"删除角色"卡(与 UserPersona 一致),列表卡只点击进编辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterListScreen(
    onBack: () -> Unit,
    onOpenCharacterDetail: (characterId: String) -> Unit,
    viewModel: CharacterViewModel = hiltViewModel(),
) {
    val characters by viewModel.characters.collectAsState()
    var query by remember { mutableStateOf("") }

    val isFiltering = query.isNotBlank()
    val filteredCharacters = remember(characters, query) {
        if (query.isBlank()) characters
        else characters.filter { character ->
            character.name.contains(query, ignoreCase = true) ||
                character.description.contains(query, ignoreCase = true) ||
                character.tags.any { it.contains(query, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("角色") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenCharacterDetail(NEW_CHARACTER_PLACEHOLDER_ID) }) {
                        Icon(Lucide.Plus, contentDescription = "新增角色")
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
            CharacterSearchBar(value = query, onValueChange = { query = it })

            when {
                filteredCharacters.isEmpty() && isFiltering -> CharacterListEmpty("没有匹配的角色")
                filteredCharacters.isEmpty() -> CharacterListEmpty("还没有角色,点右上角加号新建")
                else -> CharacterListBody(
                    characters = filteredCharacters,
                    reorderable = !isFiltering,
                    onOpenCharacterDetail = onOpenCharacterDetail,
                    onCommitOrder = viewModel::reorderCharacters,
                )
            }
        }
    }
}

/**
 * 搜索栏。不使用 M3 DockedSearchBar:其交互模型(展开/收起 + 建议列表)对纯过滤场景过重。
 */
@Composable
private fun CharacterSearchBar(value: String, onValueChange: (String) -> Unit) {
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
                        text = "搜索角色",
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

/**
 * 列表主体。
 *
 * - `localOrder` 在拖动过程中跟手实时更新,松手再调 [onCommitOrder] 把最终顺序提交到仓库,
 *   避免每帧把临时态打回 StateFlow → recompose 整列(对齐 ProviderListScreen)。
 * - 上游 [characters] 因为别处增 / 删 / 编辑变化时,通过 [LaunchedEffect] 同步重置 localOrder。
 */
@Composable
private fun CharacterListBody(
    characters: List<Character>,
    reorderable: Boolean,
    onOpenCharacterDetail: (String) -> Unit,
    onCommitOrder: (List<String>) -> Unit,
) {
    val lazyListState = rememberLazyListState()
    var localOrder by remember { mutableStateOf(characters) }
    LaunchedEffect(characters) { localOrder = characters }

    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localOrder = localOrder.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(localOrder, key = { it.id }) { character ->
            ReorderableItem(state = reorderState, key = character.id) { isDragging ->
                CharacterCard(
                    character = character,
                    elevated = isDragging,
                    onClick = { onOpenCharacterDetail(character.id) },
                    editButton = {
                        CharacterEditIconButton(onClick = { onOpenCharacterDetail(character.id) })
                    },
                    dragHandle = {
                        if (reorderable) {
                            CharacterDragHandle(
                                modifier = Modifier.draggableHandle(
                                    onDragStopped = {
                                        onCommitOrder(localOrder.map { it.id })
                                    },
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * 拖把手图标。规则与 [com.nuttavern.ui.persona.UserPersonaDragHandle] 一致。
 */
@Composable
private fun CharacterDragHandle(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Lucide.GripVertical,
        contentDescription = "拖动排序",
        modifier = modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CharacterListEmpty(text: String) {
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

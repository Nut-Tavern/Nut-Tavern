package com.nuttavern.ui.character

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.FileDown
import com.composables.icons.lucide.FileUp
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.nuttavern.data.character.Character
import com.nuttavern.ui.components.NutTavernEntityDragHandle
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
    val context = androidx.compose.ui.platform.LocalContext.current

    var longPressCharacterId by remember { mutableStateOf<String?>(null) }
    var longPressCharacterName by remember { mutableStateOf("") }

    // 导入角色卡:PNG(走 chunk 读取) / JSON(纯文本)两态,按 MIME 分流。解析失败 Toast 报错不写入。
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        if (exceedsImportSizeLimit(context, uri)) {
            android.widget.Toast.makeText(context, "文件过大,无法作为角色卡导入", android.widget.Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            android.widget.Toast.makeText(context, "无法读取文件", android.widget.Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val onSuccess: (String) -> Unit = { name ->
            android.widget.Toast.makeText(context, "已导入角色「$name」", android.widget.Toast.LENGTH_SHORT).show()
        }
        val onError: (String) -> Unit = { message ->
            android.widget.Toast.makeText(context, "导入失败: $message", android.widget.Toast.LENGTH_SHORT).show()
        }
        if (isPngBytes(bytes)) {
            viewModel.importFromPng(bytes, onSuccess, onError)
        } else {
            viewModel.importFromJson(String(bytes, Charsets.UTF_8), onSuccess, onError)
        }
    }

    // 导出 PNG:ViewModel 备好字节后用 CreateDocument 写文件。
    var pendingExportPng by remember { mutableStateOf<ByteArray?>(null) }
    val exportPngLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val bytes = pendingExportPng
        pendingExportPng = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法打开输出流")
        }.isSuccess
        android.widget.Toast.makeText(context, if (ok) "导出成功" else "导出失败", android.widget.Toast.LENGTH_SHORT).show()
    }

    // 导出 JSON:ViewModel 备好文本后用 CreateDocument 写文件。
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) } ?: error("无法打开输出流")
        }.isSuccess
        android.widget.Toast.makeText(context, if (ok) "导出成功" else "导出失败", android.widget.Toast.LENGTH_SHORT).show()
    }

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
                    IconButton(onClick = { importLauncher.launch(arrayOf("image/png", "application/json")) }) {
                        Icon(Lucide.FileDown, contentDescription = "导入角色")
                    }
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
                    onLongPress = { id, name -> longPressCharacterId = id; longPressCharacterName = name },
                    onCommitOrder = viewModel::reorderCharacters,
                )
            }
        }
    }

    // 长按菜单:复制 / 导出为图片(PNG) / 导出为 JSON / 删除
    if (longPressCharacterId != null) {
        com.nuttavern.ui.components.NutTavernEntityActionsSheet(
            title = longPressCharacterName,
            actions = listOf(
                com.nuttavern.ui.components.EntityAction(
                    icon = Lucide.Copy,
                    title = "复制",
                    onClick = { longPressCharacterId?.let { viewModel.duplicate(it) } },
                ),
                com.nuttavern.ui.components.EntityAction(
                    icon = Lucide.Image,
                    title = "导出为图片",
                    onClick = {
                        longPressCharacterId?.let { id ->
                            viewModel.exportToPng(
                                characterId = id,
                                onReady = { fileName, bytes ->
                                    pendingExportPng = bytes
                                    exportPngLauncher.launch("$fileName.png")
                                },
                                onNoAvatar = {
                                    android.widget.Toast.makeText(
                                        context,
                                        "需要先设置头像才能导出为图像,可改导出 JSON",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                },
                                onError = { message ->
                                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                                },
                            )
                        }
                    },
                ),
                com.nuttavern.ui.components.EntityAction(
                    icon = Lucide.FileUp,
                    title = "导出为 JSON",
                    onClick = {
                        longPressCharacterId?.let { id ->
                            viewModel.exportToJson(id) { fileName, jsonText ->
                                pendingExportJson = jsonText
                                exportJsonLauncher.launch("$fileName.json")
                            }
                        }
                    },
                ),
                com.nuttavern.ui.components.EntityAction(
                    icon = Lucide.Trash2,
                    title = "删除",
                    destructive = true,
                    onClick = { longPressCharacterId?.let { viewModel.delete(it) } },
                ),
            ),
            onDismiss = { longPressCharacterId = null },
        )
    }
}

/** PNG 文件头魔数:89 50 4E 47 0D 0A 1A 0A。用于导入时区分 PNG 卡与 JSON 卡。 */
private fun isPngBytes(bytes: ByteArray): Boolean {
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    if (bytes.size < signature.size) return false
    for (i in signature.indices) {
        if (bytes[i] != signature[i]) return false
    }
    return true
}

/** 角色卡导入文件大小上限。正常 PNG/JSON 卡远低于此,超过的多为误选或恶意大文件,直接拒绝防 OOM。 */
private const val MAX_IMPORT_FILE_SIZE_BYTES = 32L * 1024 * 1024

/** 查 SAF 文件大小,超过上限返回 true。大小未知(返回 -1)时放行,由后续读取兜底。 */
private fun exceedsImportSizeLimit(context: android.content.Context, uri: android.net.Uri): Boolean {
    val size = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull() ?: return false
    return size > MAX_IMPORT_FILE_SIZE_BYTES
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CharacterListBody(
    characters: List<Character>,
    reorderable: Boolean,
    onOpenCharacterDetail: (String) -> Unit,
    onLongPress: (id: String, name: String) -> Unit,
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
                    onLongClick = { onLongPress(character.id, character.name) },
                    dragHandle = {
                        if (reorderable) {
                            NutTavernEntityDragHandle(
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

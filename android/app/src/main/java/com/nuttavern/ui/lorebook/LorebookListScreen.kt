package com.nuttavern.ui.lorebook

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.FileDown
import com.composables.icons.lucide.FileUp
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.ui.components.NutTavernEntityCard
import com.nuttavern.ui.components.NutTavernEntityDragHandle
import com.nuttavern.ui.components.NutTavernShapeTokens
import com.nuttavern.ui.io.resolveImportFileName
import com.nuttavern.ui.viewmodel.LorebookViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 世界书列表页(一级页)。
 *
 * - "+" 下拉菜单:新建 / 导入
 * - 点击卡片进入二级页
 * - 长按弹菜单(复制/导出/删除)
 * - 无 Switch
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LorebookListScreen(
    onBack: () -> Unit,
    onOpenLorebook: (lorebookId: String) -> Unit,
    viewModel: LorebookViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lorebooks by viewModel.lorebooks.collectAsState()
    var query by remember { mutableStateOf("") }
    var longPressId by remember { mutableStateOf<String?>(null) }
    var longPressName by remember { mutableStateOf("") }

    // 导入酒馆世界书 JSON:文件名作书名,codec 解析失败 Toast 报错不写入。
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
        }.getOrNull()
        if (text.isNullOrBlank()) {
            android.widget.Toast.makeText(context, "无法读取文件", android.widget.Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val bookName = uri.resolveImportFileName(context, fallback = "导入世界书")
        viewModel.importFromSillyTavern(
            jsonText = text,
            bookName = bookName,
            onSuccess = { name ->
                android.widget.Toast.makeText(context, "已导入世界书「$name」", android.widget.Toast.LENGTH_SHORT).show()
            },
            onError = { message ->
                android.widget.Toast.makeText(context, "导入失败: $message", android.widget.Toast.LENGTH_SHORT).show()
            },
        )
    }

    // 导出酒馆世界书 JSON:先由 ViewModel 备好文本,再用 CreateDocument 写文件。
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
        }.isSuccess
        android.widget.Toast.makeText(
            context,
            if (ok) "导出成功" else "导出失败",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    val isFiltering = query.isNotBlank()
    val filteredItems = remember(lorebooks, query) {
        if (!isFiltering) lorebooks
        else lorebooks.filter { it.name.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("世界书") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                        Icon(Lucide.FileDown, contentDescription = "导入世界书")
                    }
                    IconButton(
                        onClick = {
                            val book = viewModel.newLorebook()
                            viewModel.upsert(book)
                            onOpenLorebook(book.id)
                        },
                    ) {
                        Icon(Lucide.Plus, contentDescription = "新建世界书")
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
            LorebookSearchBar(value = query, onValueChange = { query = it })

            when {
                filteredItems.isEmpty() && isFiltering -> LorebookListEmpty("没有找到匹配的世界书")
                filteredItems.isEmpty() -> LorebookListEmpty("暂无世界书,点右上角新建")
                else -> LorebookList(
                    items = filteredItems,
                    reorderable = !isFiltering,
                    onItemClick = onOpenLorebook,
                    onLongPress = { id, name -> longPressId = id; longPressName = name },
                    onCommitOrder = { orderedIds -> viewModel.reorder(orderedIds) },
                )
            }
        }
    }

    // 长按菜单
    if (longPressId != null) {
        com.nuttavern.ui.components.NutTavernEntityActionsSheet(
            title = longPressName,
            actions = listOf(
                com.nuttavern.ui.components.EntityAction(
                    icon = Lucide.Copy,
                    title = "复制",
                    onClick = { longPressId?.let { viewModel.duplicate(it) } },
                ),
                com.nuttavern.ui.components.EntityAction(
                    icon = Lucide.FileUp,
                    title = "导出",
                    onClick = {
                        longPressId?.let { id ->
                            viewModel.exportToSillyTavern(id) { fileName, jsonText ->
                                pendingExportJson = jsonText
                                exportLauncher.launch("$fileName.json")
                            }
                        }
                    },
                ),
                com.nuttavern.ui.components.EntityAction(
                    icon = Lucide.Trash2,
                    title = "删除",
                    destructive = true,
                    onClick = { longPressId?.let { viewModel.delete(it) } },
                ),
            ),
            onDismiss = { longPressId = null },
        )
    }
}

@Composable
private fun LorebookSearchBar(value: String, onValueChange: (String) -> Unit) {
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
                        text = "搜索世界书",
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

@Composable
private fun LorebookList(
    items: List<Lorebook>,
    reorderable: Boolean,
    onItemClick: (String) -> Unit,
    onLongPress: (id: String, name: String) -> Unit,
    onCommitOrder: (List<String>) -> Unit,
) {
    var localOrder by remember { mutableStateOf(items) }
    LaunchedEffect(items) {
        val localIds = localOrder.map { it.id }.toSet()
        val upstreamIds = items.map { it.id }.toSet()
        localOrder = if (localIds == upstreamIds) {
            val byId = items.associateBy { it.id }
            localOrder.mapNotNull { byId[it.id] }
        } else {
            items
        }
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localOrder = localOrder.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(localOrder, key = { it.id }) { lorebook ->
            ReorderableItem(state = reorderState, key = lorebook.id) { isDragging ->
                NutTavernEntityCard(
                    title = lorebook.name,
                    titleFallback = "未命名世界书",
                    subtitle = if (lorebook.entries.isEmpty()) "暂无条目"
                    else "共 ${lorebook.entries.size} 条",
                    elevated = isDragging,
                    onClick = { onItemClick(lorebook.id) },
                    onLongClick = { onLongPress(lorebook.id, lorebook.name) },
                    trailing = {
                        if (reorderable) {
                            NutTavernEntityDragHandle(
                                modifier = Modifier.draggableHandle(
                                    onDragStopped = { onCommitOrder(localOrder.map { it.id }) },
                                ),
                            )
                        } else {
                            Spacer(Modifier.size(20.dp))
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
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LorebookListEmpty(text: String) {
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

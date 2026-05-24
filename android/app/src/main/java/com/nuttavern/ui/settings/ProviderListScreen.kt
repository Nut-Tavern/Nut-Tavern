package com.nuttavern.ui.settings

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.nuttavern.data.model.Provider
import com.nuttavern.ui.chat.ProviderIconBadge
import com.nuttavern.ui.viewmodel.ProviderSettingsViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 提供商列表页。
 *
 * 视觉规则:
 * - 单列;每个 Provider 是独立 [Surface] 卡,启用 / 禁用通过背景色 + 状态徽章区分;
 * - 右上 `+` 直接新建一个空 Provider 并跳详情;协议在详情页里通过药丸切换。
 *
 * 拖动排序:
 * - 卡片右侧有 [Lucide.GripVertical] 把手,把手上的 `draggableHandle` 直接接管手势;
 * - 不允许在搜索过滤态拖动:过滤后看到的不是真实顺序,拖动会让人困惑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    onBack: () -> Unit,
    onOpenProviderDetail: (providerId: String) -> Unit,
    viewModel: ProviderSettingsViewModel = hiltViewModel(),
) {
    val providers by viewModel.providers.collectAsState()
    var query by remember { mutableStateOf("") }

    val isFiltering = query.isNotBlank()
    val filtered = remember(providers, query) {
        if (query.isBlank()) providers
        else providers.filter { it.name.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("提供商") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val provider = viewModel.newProvider()
                        viewModel.addProvider(provider)
                        onOpenProviderDetail(provider.id)
                    }) {
                        Icon(Lucide.Plus, contentDescription = "新增提供商")
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
            ProviderSearchBar(
                value = query,
                onValueChange = { query = it },
            )

            when {
                filtered.isEmpty() && isFiltering -> ProviderListEmpty(text = "没有匹配的提供商")
                filtered.isEmpty() -> ProviderListEmpty(text = "还没有提供商,点右上角加号新建")
                else -> ProviderListBody(
                    providers = filtered,
                    reorderable = !isFiltering,
                    onOpenProviderDetail = onOpenProviderDetail,
                    onCommitOrder = { orderedIds -> viewModel.reorderProviders(orderedIds) },
                )
            }
        }
    }
}

@Composable
private fun ProviderSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(percent = 50),
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
                        text = "搜索提供商",
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
private fun ProviderListEmpty(text: String) {
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

/**
 * 列表主体。
 *
 * - `localOrder` 在拖动过程中跟手实时更新,松手再调 [onCommitOrder] 把最终顺序提交到仓库,
 *   避免每帧把临时态打回 StateFlow → recompose 整列;
 * - 当外层 [providers] 因为别处操作变化(增 / 删 / 启用切换)时,通过 [LaunchedEffect] 同步重置
 *   `localOrder`,确保拖动后看到的就是仓库的真实状态。
 */
@Composable
private fun ProviderListBody(
    providers: List<Provider>,
    reorderable: Boolean,
    onOpenProviderDetail: (String) -> Unit,
    onCommitOrder: (List<String>) -> Unit,
) {
    val lazyListState = rememberLazyListState()
    var localOrder by remember { mutableStateOf(providers) }
    LaunchedEffect(providers) { localOrder = providers }

    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localOrder = localOrder.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(localOrder, key = { it.id }) { provider ->
            ReorderableItem(state = reorderState, key = provider.id) { isDragging ->
                ProviderListCard(
                    provider = provider,
                    elevated = isDragging,
                    onClick = { onOpenProviderDetail(provider.id) },
                    handle = {
                        if (reorderable) {
                            // reorderable 2.x:把手上挂 `this.draggableHandle`(ReorderableScope 提供),
                            // 长按触发 + 自带 haptic;停止拖动时调 onDragStopped 提交顺序。
                            Icon(
                                imageVector = Lucide.GripVertical,
                                contentDescription = "拖动排序",
                                modifier = Modifier
                                    .size(20.dp)
                                    .draggableHandle(
                                        onDragStopped = {
                                            onCommitOrder(localOrder.map { it.id })
                                        },
                                    ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ProviderListCard(
    provider: Provider,
    elevated: Boolean,
    onClick: () -> Unit,
    handle: @Composable () -> Unit,
) {
    val containerColor = if (provider.enabled) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val titleColor = if (provider.enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = titleColor,
        tonalElevation = if (elevated) 6.dp else 0.dp,
        shadowElevation = if (elevated) 6.dp else 0.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProviderIconBadge(provider = provider, modifier = Modifier.size(32.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = provider.name.ifBlank { "未命名提供商" },
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = providerSubtitle(provider),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProviderStatusBadge(enabled = provider.enabled)
            handle()
        }
    }
}

@Composable
private fun ProviderStatusBadge(enabled: Boolean) {
    val (label, container, content) = if (enabled) {
        Triple(
            "已启用",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
    } else {
        Triple(
            "未启用",
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(percent = 50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun providerSubtitle(provider: Provider): String {
    val typeLabel = when (provider) {
        is Provider.OpenAI -> "OpenAI 协议"
        is Provider.Google -> "Google 协议"
        is Provider.Claude -> "Claude 协议"
    }
    val modelCount = provider.models.size
    return if (modelCount == 0) typeLabel else "$typeLabel · $modelCount 个模型"
}

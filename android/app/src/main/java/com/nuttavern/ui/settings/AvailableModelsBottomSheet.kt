package com.nuttavern.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.nuttavern.data.model.Provider
import com.nuttavern.ui.chat.ProviderIconBadge
import com.nuttavern.ui.components.NutTavernModelCard
import com.nuttavern.ui.components.NutTavernModelCardTokens

/**
 * "管理模型"底部抽屉。
 *
 * 设计取舍:
 * - **状态由外部传入**。打开抽屉不再触发 fetchModels;数据由 ProviderDetailScreen 在进入模型 Tab
 *   时拉一次,抽屉只是渲染窗口。失败时由 [onRetry] 由外层重试,抽屉不持有 fetcher。
 * - 不再走"勾选 + 批量提交"流程。每行 +/× 立刻调 [onAddModel] / [onRemoveModel],
 *   抽屉自身不持有"待提交"中间态;数据源仍是仓库 [Provider.models],UI 通过 provider 引用
 *   被动刷新。这样添加和删除是一次操作,符合"管理模型"语义,而不是"批量加入"。
 * - 已添加 = +号旋转 45° 显示成 × + primary 高亮;未添加 = 同图标无旋转 + onSurfaceVariant。
 *   不引入第二个图标,避免 50% 中间态视觉跳动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AvailableModelsBottomSheet(
    provider: Provider,
    state: RemoteModelsState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onAddModel: (modelId: String) -> Unit,
    onRemoveModel: (modelId: String) -> Unit,
    onSwitchToManual: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var query by remember { mutableStateOf("") }

    val existingModelIds = remember(provider.models) { provider.models.map { it.modelId }.toSet() }
    val remoteTotal = (state as? RemoteModelsState.Loaded)?.modelIds?.size ?: 0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = 240.dp),
        ) {
            ManageModelsHeader(
                addedCount = existingModelIds.size,
                remoteTotal = remoteTotal,
                showRemoteTotal = state is RemoteModelsState.Loaded,
            )

            when (state) {
                is RemoteModelsState.Loading -> RemoteModelsLoading()
                is RemoteModelsState.Failed -> RemoteModelsFailed(
                    message = state.message,
                    onRetry = onRetry,
                )
                is RemoteModelsState.Loaded -> RemoteModelsLoaded(
                    provider = provider,
                    modelIds = state.modelIds,
                    existingModelIds = existingModelIds,
                    query = query,
                    onQueryChange = { query = it },
                    onToggle = { modelId, addOrRemove ->
                        if (addOrRemove) onAddModel(modelId) else onRemoveModel(modelId)
                    },
                )
            }

            ManageModelsFooter(
                onClose = onDismiss,
                onSwitchToManual = onSwitchToManual,
            )
        }
    }
}

@Composable
private fun ManageModelsHeader(
    addedCount: Int,
    remoteTotal: Int,
    showRemoteTotal: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "管理模型",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // 右侧"已启用 / 总数"。Loaded 之前只显示已启用,避免误导。
        Text(
            text = if (showRemoteTotal) "$addedCount / $remoteTotal" else addedCount.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RemoteModelsLoading() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text("正在拉取远端模型列表...", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RemoteModelsFailed(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun RemoteModelsLoaded(
    provider: Provider,
    modelIds: List<String>,
    existingModelIds: Set<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggle: (modelId: String, addOrRemove: Boolean) -> Unit,
) {
    val filtered = remember(modelIds, query) {
        if (query.isBlank()) modelIds
        else modelIds.filter { matchesQuery(it, query) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        ModelSearchBar(value = query, onValueChange = onQueryChange)
        Spacer(Modifier.height(12.dp))
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (modelIds.isEmpty()) "远端没有可用模型" else "没有匹配的模型",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it }) { modelId ->
                    val alreadyAdded = modelId in existingModelIds
                    AvailableModelRow(
                        provider = provider,
                        modelId = modelId,
                        alreadyAdded = alreadyAdded,
                        onToggle = { addOrRemove -> onToggle(modelId, addOrRemove) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AvailableModelRow(
    provider: Provider,
    modelId: String,
    alreadyAdded: Boolean,
    onToggle: (addOrRemove: Boolean) -> Unit,
) {
    // 远端只给 modelId,卡片里要显示能力 chips,这里现场用 ModelRegistry 推断一下;
    // 临时 Model 不写盘,只做渲染。已添加的模型应优先用本地真实记录,但抽屉里我们没有
    // 句柄,继续走推断 — 与添加后真实记录差异极小(用户没改过能力)。
    val inferredModel = remember(modelId) {
        com.nuttavern.data.registry.ModelRegistry.inferAll(modelId).let { inferred ->
            com.nuttavern.data.model.Model(
                id = "preview-$modelId",
                modelId = modelId,
                displayName = modelId,
                inputModalities = inferred.inputModalities,
                outputModalities = inferred.outputModalities,
                abilities = inferred.abilities,
            )
        }
    }
    NutTavernModelCard(
        title = modelId,
        onClick = { onToggle(!alreadyAdded) },
        leading = {
            ProviderIconBadge(
                provider = provider,
                modelName = modelId,
                modifier = Modifier.size(NutTavernModelCardTokens.LeadingIconSize),
            )
        },
        chips = { ModelCapabilityChips(model = inferredModel) },
        trailing = {
            AddRemoveSpinIcon(
                added = alreadyAdded,
                onClick = { onToggle(!alreadyAdded) },
            )
        },
    )
}

/**
 * "+ ⇄ ×" 旋转切换控件。同一个 `+` 通过旋转 45° 实现 + → × 视觉切换。
 *
 * - 未添加:0°,色 [MaterialTheme.colorScheme.onSurfaceVariant];
 * - 已添加:45° 等价于 ×,色 [MaterialTheme.colorScheme.primary];
 * - 不再外包 Surface 圆形底:直接 IconButton 铺,符合"去底色 + 加大尺寸"的产品要求;
 * - tween 180ms,与 M3 selection 动画对齐;
 * - 不用单独的 Close 图标,避免两套图标在中间态同时绘制造成抖动。
 */
@Composable
private fun AddRemoveSpinIcon(
    added: Boolean,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (added) 45f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "AddRemoveSpinRotation",
    )
    val tint = if (added) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            imageVector = Lucide.Plus,
            contentDescription = if (added) "移除模型" else "添加模型",
            tint = tint,
            modifier = Modifier
                .size(24.dp)
                .rotate(rotation),
        )
    }
}

@Composable
private fun ModelSearchBar(
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
                        text = "输入模型名称筛选",
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
private fun ManageModelsFooter(
    onClose: () -> Unit,
    onSwitchToManual: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onSwitchToManual, modifier = Modifier.weight(1f)) {
            Text("手动输入模型 ID")
        }
        TextButton(onClick = onClose) { Text("完成") }
    }
}

private fun matchesQuery(modelId: String, query: String): Boolean =
    query.isBlank() || modelId.contains(query.trim(), ignoreCase = true)

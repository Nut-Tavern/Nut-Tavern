package com.nuttavern.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuttavern.data.model.Provider
import com.nuttavern.ui.components.NutTavernModelCard
import com.nuttavern.ui.components.NutTavernSelectableRow
import com.nuttavern.ui.settings.ModelCapabilityChips

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    availableProviders: List<Provider>,
    currentProviderId: String,
    currentModelInternalId: String,
    onSelectProviderAndModel: (providerId: String, modelInternalId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val enabledProviders = availableProviders.filter { it.enabled }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "选择模型",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
            )

            if (enabledProviders.isEmpty()) {
                EmptyModelPickerState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (provider in enabledProviders) {
                        item(key = "provider-${provider.id}") {
                            ProviderGroupHeader(provider)
                        }

                        if (provider.models.isEmpty()) {
                            item(key = "empty-${provider.id}") {
                                NutTavernSelectableRow(
                                    title = "暂无模型",
                                    subtitle = "请在提供商设置中添加模型",
                                    enabled = false,
                                    leadingContent = { ProviderIconBadge(provider) },
                                    onClick = {},
                                )
                            }
                        } else {
                            items(provider.models, key = { model -> "${provider.id}-${model.id}" }) { model ->
                                val selected = provider.id == currentProviderId && model.id == currentModelInternalId
                                // 复用 Provider 详情页的模型卡片,保持视觉一致;选中态由 NutTavernModelCard
                                // 自带 primaryContainer + primary 描边,无需再加 trailing 勾选图标。
                                NutTavernModelCard(
                                    title = model.resolvedDisplayName,
                                    selected = selected,
                                    onClick = { onSelectProviderAndModel(provider.id, model.id) },
                                    leading = {
                                        ProviderIconBadge(provider, modelName = model.modelId)
                                    },
                                    chips = { ModelCapabilityChips(model = model) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderGroupHeader(provider: Provider) {
    Text(
        text = provider.name.ifBlank { "未命名提供商" },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun EmptyModelPickerState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NutTavernSelectableRow(
            title = "暂无已启用的提供商",
            subtitle = "请先在设置中启用并配置提供商",
            enabled = false,
            leadingContent = { ProviderIconBadge(null) },
            onClick = {},
        )
    }
}

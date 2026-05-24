package com.nuttavern.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuttavern.data.model.Provider
import com.nuttavern.ui.chat.ProviderIconBadge
import com.nuttavern.ui.chat.ProviderIconCatalog

/**
 * 提供商图标选择器底部抽屉。
 *
 * 视觉:
 * - 顶部"使用自动推断"行(空 iconKey),用户清掉手动选择回到自动模式;
 * - 主体是 4 列网格,每格一个 [ProviderIconBadge] + 中文名;
 * - 选中态(当前 iconKey 命中)走 primary 描边圆形 + primary 文字。
 *
 * 取舍:
 * - 不暴露"上传自定义图标":本轮只允许从内置库选,内置库由 [ProviderIconCatalog] 维护;
 * - 不接 ProviderIconBadge 之外的渲染路径:复用现有 SVG 加载和 tint 逻辑,避免维护两套。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderIconPickerSheet(
    provider: Provider,
    onDismiss: () -> Unit,
    onSelectIconKey: (iconKey: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentKey = provider.iconKey

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "选择图标",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            // 自动推断:把 iconKey 设为空字符串。
            AutoInferRow(
                isCurrent = currentKey.isBlank(),
                onClick = {
                    onSelectIconKey("")
                    onDismiss()
                },
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ProviderIconCatalog.entries, key = { it.key }) { entry ->
                    IconChoiceCell(
                        provider = provider,
                        iconKey = entry.key,
                        displayName = entry.displayName,
                        selected = entry.key == currentKey,
                        onClick = {
                            onSelectIconKey(entry.key)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoInferRow(
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val border = if (isCurrent) {
        androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    } else {
        androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = border,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = "使用自动推断(根据名称匹配)",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun IconChoiceCell(
    provider: Provider,
    iconKey: String,
    displayName: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val border = androidx.compose.foundation.BorderStroke(
        width = if (selected) 2.dp else 1.dp,
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = border,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 临时 Provider:把 iconKey 临时塞进当前 provider,让 Badge 显示对应图标。
            // 这里只读使用,不会持久化 — 真正写盘走 onSelectIconKey 回调。
            ProviderIconBadge(
                provider = provider.withIconKey(iconKey),
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

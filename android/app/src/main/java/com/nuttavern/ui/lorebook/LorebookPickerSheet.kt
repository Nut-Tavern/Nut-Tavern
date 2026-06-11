package com.nuttavern.ui.lorebook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuttavern.ui.components.NutTavernSheetTitle
import com.nuttavern.ui.regex.MultiSelectPickerCard
import com.nuttavern.ui.viewmodel.LorebookViewModel

/**
 * 右侧栏的"世界书选择"抽屉。
 *
 * 暂存 Switch 状态,只有点"应用"才写回当前会话的世界书选择。
 * 下滑/点外部关闭 = 取消修改。
 * 卡片无左侧图标,Switch 左侧有编辑键,点击后导航到世界书详情页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LorebookPickerSheet(
    visible: Boolean,
    selectedIds: Set<String>,
    onApply: (selectedIds: Set<String>) -> Unit,
    onEdit: (lorebookId: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: LorebookViewModel = hiltViewModel(),
) {
    if (!visible) return

    val lorebooks by viewModel.lorebooks.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 暂存选中状态
    var draftSelectedIds by remember(visible, selectedIds) { mutableStateOf(selectedIds) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .padding(horizontal = 16.dp),
        ) {
            // 标题行 + 应用按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NutTavernSheetTitle(title = "世界书", modifier = Modifier.weight(1f))
                TextButton(onClick = { onApply(draftSelectedIds) }) { Text("应用") }
            }

            if (lorebooks.isEmpty()) {
                Text(
                    text = "暂无世界书",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(lorebooks, key = { it.id }) { book ->
                        val entryCount = book.entries.size
                        MultiSelectPickerCard(
                            title = book.name.ifBlank { "未命名世界书" },
                            subtitle = if (entryCount == 0) "暂无条目" else "共 $entryCount 个条目",
                            enabled = book.id in draftSelectedIds,
                            onToggle = { enabled ->
                                draftSelectedIds = if (enabled) draftSelectedIds + book.id
                                else draftSelectedIds - book.id
                            },
                            onEdit = { onEdit(book.id) },
                        )
                    }
                }
            }
        }
    }
}

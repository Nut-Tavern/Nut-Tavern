package com.nuttavern.ui.regex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.nuttavern.ui.components.NutTavernSheetTitle
import com.nuttavern.ui.viewmodel.RegexScriptViewModel

/**
 * 右侧栏的"正则选择"抽屉。
 *
 * 暂存 Switch 状态,只有点"应用"才写入。下滑/点外部关闭 = 取消修改。
 * 卡片无左侧图标,Switch 左侧有编辑键。
 * 编辑键点击后关闭 Sheet 并导航到编辑页(组 → 组内列表,散 → 正则编辑)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegexPickerSheet(
    visible: Boolean,
    onApply: (enabledGroupIds: Set<String>, enabledOrphanIds: Set<String>) -> Unit,
    onEdit: (id: String, isGroup: Boolean) -> Unit,
    onDismiss: () -> Unit,
    viewModel: RegexScriptViewModel = hiltViewModel(),
) {
    if (!visible) return

    val topLevelItems by viewModel.topLevelItems.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 暂存启用状态
    var enabledGroupIds by remember(visible) {
        mutableStateOf(
            topLevelItems.filter { it.isGroup && (it.group?.enabled == true) }.map { it.id }.toSet()
        )
    }
    var enabledOrphanIds by remember(visible) {
        mutableStateOf(
            topLevelItems.filter { !it.isGroup && (it.orphan?.disabled == false) }.map { it.id }.toSet()
        )
    }

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
                NutTavernSheetTitle(title = "正则", modifier = Modifier.weight(1f))
                TextButton(onClick = { onApply(enabledGroupIds, enabledOrphanIds) }) {
                    Text("应用")
                }
            }

            if (topLevelItems.isEmpty()) {
                Text(
                    text = "暂无正则规则",
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
                    items(topLevelItems, key = { it.id }) { item ->
                        val isGroup = item.isGroup
                        val enabled = if (isGroup) item.id in enabledGroupIds else item.id in enabledOrphanIds
                        val title = if (isGroup) {
                            item.group?.name?.ifBlank { "未命名规则组" } ?: "未命名规则组"
                        } else {
                            item.orphan?.scriptName?.ifBlank { "未命名规则" } ?: "未命名规则"
                        }
                        val subtitle = if (isGroup) {
                            val count = item.group?.scripts?.size ?: 0
                            if (count == 0) "空规则组" else "共 $count 条规则"
                        } else null

                        MultiSelectPickerCard(
                            title = title,
                            subtitle = subtitle,
                            enabled = enabled,
                            onToggle = { newEnabled ->
                                if (isGroup) {
                                    enabledGroupIds = if (newEnabled) enabledGroupIds + item.id
                                    else enabledGroupIds - item.id
                                } else {
                                    enabledOrphanIds = if (newEnabled) enabledOrphanIds + item.id
                                    else enabledOrphanIds - item.id
                                }
                            },
                            onEdit = { onEdit(item.id, isGroup) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MultiSelectPickerCard(
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: (() -> Unit)? = null,
    locked: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Lucide.Pencil,
                        contentDescription = "编辑",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // locked:角色世界书在辅助世界书选择里强制勾选不可取消(纯前端约束,不写进 lorebookIds)
            Switch(checked = enabled, onCheckedChange = onToggle, enabled = !locked)
        }
    }
}

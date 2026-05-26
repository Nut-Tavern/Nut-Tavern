package com.nuttavern.ui.persona

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
import com.nuttavern.data.persona.UserPersona
import com.nuttavern.ui.components.NutTavernEntityStatusPill
import com.nuttavern.ui.components.NutTavernSheetTitle
import com.nuttavern.ui.viewmodel.UserPersonaViewModel

/**
 * 右侧栏的"用户身份选择"抽屉。
 *
 * 暂存选择,只有点"应用"才写入。下滑/点外部关闭 = 取消修改。
 * 顶部固定"不使用身份"行,下方列出所有真实身份。
 * 每张卡片右侧有编辑键,点击后关闭 Sheet 并导航到编辑页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaPickerSheet(
    visible: Boolean,
    currentPersonaId: String?,
    onApply: (personaId: String) -> Unit,
    onEdit: (personaId: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: UserPersonaViewModel = hiltViewModel(),
) {
    if (!visible) return

    val items by viewModel.items.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 暂存选中 id。null = "不使用身份"
    val effectiveInitial = currentPersonaId?.takeIf { it != UserPersona.NONE_PERSONA_ID }
    var selectedId by remember(visible) { mutableStateOf(effectiveInitial) }

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
                NutTavernSheetTitle(title = "用户身份", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    onApply(selectedId ?: UserPersona.NONE_PERSONA_ID)
                }) { Text("应用") }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // "不使用身份"固定行
                item(key = "__none__") {
                    NonePersonaRow(
                        selected = selectedId == null,
                        onClick = { selectedId = null },
                    )
                }

                // 真实身份列表
                val realItems = items.filter { !it.persona.isNonePersona }
                items(realItems, key = { it.persona.id }) { item ->
                    PersonaPickerCard(
                        name = item.persona.name.ifBlank { "未命名身份" },
                        subtitle = item.persona.description.take(60).ifBlank { null },
                        selected = item.persona.id == selectedId,
                        onClick = { selectedId = item.persona.id },
                        onEdit = { onEdit(item.persona.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NonePersonaRow(
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "不使用身份",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                NutTavernEntityStatusPill(
                    label = "使用中",
                    container = MaterialTheme.colorScheme.tertiary,
                    content = MaterialTheme.colorScheme.onTertiary,
                )
            }
        }
    }
}

@Composable
private fun PersonaPickerCard(
    name: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Lucide.Pencil,
                    contentDescription = "编辑",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
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
            if (selected) {
                NutTavernEntityStatusPill(
                    label = "使用中",
                    container = MaterialTheme.colorScheme.tertiary,
                    content = MaterialTheme.colorScheme.onTertiary,
                )
            }
        }
    }
}

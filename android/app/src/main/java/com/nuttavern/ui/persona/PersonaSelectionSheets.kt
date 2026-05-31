package com.nuttavern.ui.persona

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.nuttavern.data.persona.PersonaPosition
import com.nuttavern.data.persona.PersonaRole
import com.nuttavern.ui.components.NutTavernSelectedCheckIcon
import com.nuttavern.ui.components.NutTavernSelectableRow
import com.nuttavern.ui.components.NutTavernSheetTitle
import com.nuttavern.ui.viewmodel.CharacterViewModel
import com.nuttavern.ui.viewmodel.LorebookViewModel

/**
 * 注入位置选择抽屉。
 *
 * 5 个枚举值各一张卡,每张卡上下两行:主标题(中文显示名) + 副标题(说明)。
 * 选中态用 [NutTavernSelectedCheckIcon] 标记;点击后回写并关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonaPositionSheet(
    visible: Boolean,
    selected: PersonaPosition,
    onSelect: (PersonaPosition) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        EnumOptionsSheetBody(
            title = "注入位置",
            description = "用户身份描述如何加入到提示词",
            options = PersonaPosition.entries,
            displayName = { it.displayName },
            description2 = { it.description },
            selected = selected,
            onSelect = onSelect,
        )
    }
}

/**
 * 注入角色选择抽屉。3 个枚举值,与 [PersonaPositionSheet] 同形态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonaRoleSheet(
    visible: Boolean,
    selected: PersonaRole,
    onSelect: (PersonaRole) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        EnumOptionsSheetBody(
            title = "注入角色",
            description = "把身份描述以哪种对话角色发出",
            options = PersonaRole.entries,
            displayName = { it.displayName },
            description2 = { it.description },
            selected = selected,
            onSelect = onSelect,
        )
    }
}

/**
 * 枚举选项抽屉的通用骨架。
 *
 * 抽屉里逐行渲染选项卡:`surfaceContainerHigh` 圆角卡 + 主副标题 + 选中对勾。
 * 与 `NutTavernSelectableRow` 不同的地方在于这里固定要展示副标题(说明文案)
 * 且选项数量小,布局直接用 LazyColumn 不做复用优化。
 *
 * 不抽到通用组件的原因:目前只有用户身份的两个枚举用,UI 还没确定第三处复用,
 * 先内联在 persona 包里,出现第三处再上提。
 */
@Composable
private fun <T> EnumOptionsSheetBody(
    title: String,
    description: String,
    options: List<T>,
    displayName: (T) -> String,
    description2: (T) -> String,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        NutTavernSheetTitle(title = title, description = description)
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(options) { option ->
                EnumOptionCard(
                    title = displayName(option),
                    subtitle = description2(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun EnumOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val titleColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subtitleColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = titleColor,
        onClick = onClick,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                NutTavernSelectedCheckIcon(contentDescription = "已选中")
            }
        }
    }
}

// ── 世界书绑定 Sheet(单选) ──

/**
 * 用户身份绑定世界书选择抽屉。
 *
 * 对齐酒馆 persona lorebook:单选一本世界书,运行时作为额外来源参与激活扫描。
 * 选"无"清除绑定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonaLorebookBindSheet(
    visible: Boolean,
    currentLorebookId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    lorebookViewModel: LorebookViewModel = hiltViewModel(),
) {
    if (!visible) return

    val lorebooks by lorebookViewModel.lorebooks.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            NutTavernSheetTitle(
                title = "绑定世界书",
                description = "选中的世界书在使用本身份时自动参与激活扫描",
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // "无"选项
                item(key = "none") {
                    NutTavernSelectableRow(
                        title = "无",
                        subtitle = "不绑定世界书",
                        selected = currentLorebookId == null,
                        onClick = { onSelect(null) },
                    )
                }
                items(lorebooks, key = { it.id }) { book ->
                    NutTavernSelectableRow(
                        title = book.name.ifBlank { "未命名世界书" },
                        subtitle = "${book.entries.size} 条条目",
                        selected = book.id == currentLorebookId,
                        onClick = { onSelect(book.id) },
                    )
                }
            }
        }
    }
}

// ── 角色绑定 Sheet(多选) ──

/**
 * 用户身份绑定角色选择抽屉。
 *
 * 对齐酒馆 persona connections:多选角色,切到对应角色时自动启用本身份。
 * 暂存选择 + "应用"按钮确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonaCharacterBindSheet(
    visible: Boolean,
    currentConnections: List<String>,
    onApply: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    characterViewModel: CharacterViewModel = hiltViewModel(),
) {
    if (!visible) return

    val characters by characterViewModel.characters.collectAsState()
    var selectedIds by remember(currentConnections) {
        mutableStateOf(currentConnections.toSet())
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            NutTavernSheetTitle(
                title = "绑定角色",
                description = "切到选中的角色时自动启用本身份",
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (characters.isEmpty()) {
                    item {
                        Text(
                            text = "暂无角色",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                items(characters, key = { it.id }) { character ->
                    val checked = character.id in selectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedIds = if (checked) selectedIds - character.id
                                else selectedIds + character.id
                            }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { isChecked ->
                                selectedIds = if (isChecked) selectedIds + character.id
                                else selectedIds - character.id
                            },
                        )
                        Text(
                            text = character.name.ifBlank { "未命名角色" },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // 操作按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = { onApply(emptyList()) }) { Text("清除绑定") }
                TextButton(onClick = { onApply(selectedIds.toList()) }) { Text("应用") }
            }
        }
    }
}

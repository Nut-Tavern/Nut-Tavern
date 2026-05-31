package com.nuttavern.ui.lorebook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernSheetTitle
import com.nuttavern.ui.viewmodel.LorebookViewModel

/**
 * 世界书条目编辑页(三级页)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LorebookEntryEditScreen(
    lorebookId: String,
    entryUid: Int,
    onBack: () -> Unit,
    viewModel: LorebookViewModel = hiltViewModel(),
) {
    val lorebook by remember(lorebookId, viewModel) {
        viewModel.findById(lorebookId)
    }.collectAsState(initial = null)

    val currentBook = lorebook ?: return
    val initial = currentBook.entries.find { it.uid == entryUid } ?: run {
        onBack()
        return
    }

    var draft by remember(entryUid) { mutableStateOf(initial) }
    val isDirty = draft != initial
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCharacterFilterSheet by remember { mutableStateOf(false) }

    val triggerBack: () -> Unit = {
        if (isDirty) showDiscardDialog = true else onBack()
    }
    BackHandler { triggerBack() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("编辑条目") },
                navigationIcon = {
                    IconButton(onClick = triggerBack) { Icon(Lucide.ArrowLeft, "返回") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.upsertEntry(lorebookId, draft, currentBook.entries)
                            onBack()
                        },
                        enabled = isDirty,
                    ) { Text("保存") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
        ) {
            lorebookEntryFormItems(
                draft = draft,
                onDraftChange = { draft = it },
                onDeleteClick = { showDeleteDialog = true },
                onCharacterFilterClick = { showCharacterFilterSheet = true },
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃修改?") },
            text = { Text("当前条目还有未保存的修改,关闭后会丢失。") },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) { Text("放弃修改") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除条目?") },
            text = { Text("「${draft.comment.ifBlank { "未命名条目" }}」将被永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteEntry(lorebookId, entryUid, currentBook.entries)
                    // 不显式调用 onBack():删除后 entries 更新触发 recomposition,
                    // 行 find==null 的守卫会自动调用 onBack(),避免双重 popBackStack。
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }

    // 角色过滤器编辑 Sheet
    if (showCharacterFilterSheet) {
        CharacterFilterSheet(
            currentFilter = draft.characterFilter,
            onApply = { newFilter ->
                draft = draft.copy(characterFilter = newFilter)
                showCharacterFilterSheet = false
            },
            onDismiss = { showCharacterFilterSheet = false },
        )
    }
}

// region 角色过滤器 Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharacterFilterSheet(
    currentFilter: com.nuttavern.data.lorebook.CharacterFilter?,
    onApply: (com.nuttavern.data.lorebook.CharacterFilter?) -> Unit,
    onDismiss: () -> Unit,
) {
    val characterViewModel: com.nuttavern.ui.viewmodel.CharacterViewModel = hiltViewModel()
    val characters by characterViewModel.characters.collectAsState()

    var isExclude by remember { mutableStateOf(currentFilter?.isExclude ?: false) }
    var selectedIds by remember { mutableStateOf(currentFilter?.names?.toSet() ?: emptySet()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NutTavernSheetTitle(
                title = "角色过滤器",
                description = if (isExclude) "排除选中的角色(黑名单)" else "仅对选中的角色生效(白名单)",
            )

            // 白名单/黑名单切换
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("排除模式", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = isExclude, onCheckedChange = { isExclude = it })
            }

            // 角色列表
            LazyColumn(modifier = Modifier.weight(1f)) {
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
                        Checkbox(checked = checked, onCheckedChange = { isChecked ->
                            selectedIds = if (isChecked) selectedIds + character.id
                            else selectedIds - character.id
                        })
                        Text(
                            text = character.name.ifBlank { "未命名角色" },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
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
            }

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = { onApply(null) }) { Text("清除过滤") }
                TextButton(onClick = {
                    if (selectedIds.isEmpty()) {
                        onApply(null)
                    } else {
                        onApply(com.nuttavern.data.lorebook.CharacterFilter(
                            isExclude = isExclude,
                            names = selectedIds.toList(),
                        ))
                    }
                }) { Text("应用") }
            }
        }
    }
}

// endregion

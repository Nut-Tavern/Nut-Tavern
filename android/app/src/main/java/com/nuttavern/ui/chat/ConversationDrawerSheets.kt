package com.nuttavern.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Trash2
import com.nuttavern.data.model.ConversationSummary
import com.nuttavern.ui.character.CharacterCard
import com.nuttavern.ui.character.CharacterEditIconButton
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernGroupTokens
import com.nuttavern.ui.components.NutTavernIconRow
import com.nuttavern.ui.components.NutTavernSheetTitle
import com.nuttavern.ui.viewmodel.CharacterViewModel

/**
 * 长按对话条目时弹出的操作菜单。重命名属常规操作,删除属危险操作,按设计系统约定
 * 拆为两组卡片。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationActionsSheet(
    conversation: ConversationSummary?,
    onDismiss: () -> Unit,
    onRename: (ConversationSummary) -> Unit,
    onDelete: (ConversationSummary) -> Unit,
) {
    if (conversation == null) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(NutTavernGroupTokens.SectionSpacing),
        ) {
            NutTavernSheetTitle(
                title = conversation.title.ifBlank { "未命名会话" },
                description = conversation.lastMessageTime.ifBlank { null },
            )
            NutTavernGroupSection {
                NutTavernIconRow(
                    icon = Lucide.Pencil,
                    title = "重命名",
                    onClick = { onRename(conversation) },
                )
            }
            NutTavernGroupSection {
                NutTavernIconRow(
                    icon = Lucide.Trash2,
                    title = "删除",
                    destructive = true,
                    onClick = { onDelete(conversation) },
                )
            }
        }
    }
}

/**
 * 左侧栏底部"角色卡片"入口的弹出 sheet。
 *
 * 视觉规则:
 * - 标题 + 描述用 [NutTavernSheetTitle];
 * - 角色列表复用 [CharacterCard](32dp 头像 + 标题 + 副标题 + 编辑键),与设置 → 角色页同款;
 * - 选中态显示"使用中"胶囊;
 * - 没有"无角色"伪卡 — 想取消角色绑定走单独的"不绑定角色"行(待接入)。
 *
 * 行为契约:
 * - 点击主区域 → 调 [onSelectCharacter] 切换角色并立即关闭;
 * - 点击右侧编辑键 → 调 [onOpenCharacterDetail] 进角色编辑页;
 * - 列表为空 → 显示"还没有角色"提示。
 *
 * **不**在这里提供"新建角色"入口:与 PersonaPickerSheet 一致,新建从"设置 → 角色"走,
 * 抽屉只负责"选已有"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CharacterPickerSheet(
    currentCharacterId: String?,
    onSelectCharacter: (String) -> Unit,
    onOpenCharacterDetail: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: CharacterViewModel = hiltViewModel(),
) {
    val characters by viewModel.characters.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        ) {
            NutTavernSheetTitle(
                title = "切换角色",
                description = "选择当前会话使用的角色卡;新建角色请去设置 → 角色",
            )
            if (characters.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "还没有角色,请去设置 → 角色新建",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(characters, key = { it.id }) { character ->
                        CharacterCard(
                            character = character,
                            isCurrent = character.id == currentCharacterId,
                            onClick = { onSelectCharacter(character.id) },
                        )
                    }
                }
            }
        }
    }
}

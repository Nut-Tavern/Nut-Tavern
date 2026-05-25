package com.nuttavern.ui.persona

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuttavern.data.persona.UserPersona
import com.nuttavern.ui.components.NutTavernSheetTitle
import com.nuttavern.ui.viewmodel.UserPersonaViewModel

/**
 * 右侧栏的"用户身份选择"抽屉。
 *
 * 与 [UserPersonaListScreen] 同源 — 使用同一个 ViewModel 与 [UserPersonaCard]。
 * 区别:
 * - 顺序与设置页一致,但抽屉里没有拖把手;
 * - 没有"+新建身份"入口,新建必须从设置页走;
 * - 卡片主区域点击 = 切换当前会话身份并立即关闭抽屉,无二次确认;
 * - 卡片右侧编辑键 → 进编辑页(由 [onOpenPersonaDetail] 决定路由)。
 *
 * "当前正在使用"的身份由调用方传入([currentPersonaId])来判定 — 真源在
 * [com.nuttavern.ui.viewmodel.ChatViewModel.currentPersonaId],持久化到
 * `conversations.personaId`。null = 当前会话"无身份",picker 里"无"伪卡显示"使用中"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaPickerSheet(
    visible: Boolean,
    currentPersonaId: String?,
    onSelectPersona: (personaId: String) -> Unit,
    onOpenPersonaDetail: (personaId: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: UserPersonaViewModel = hiltViewModel(),
) {
    if (!visible) return

    val items by viewModel.items.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // null currentPersonaId 等价于"无"伪卡被选中;统一比较时把 null 抹平成伪卡 id。
    val effectiveCurrentId = currentPersonaId ?: UserPersona.NONE_PERSONA_ID

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
            NutTavernSheetTitle(title = "切换用户身份")
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.persona.id }) { item ->
                    UserPersonaCard(
                        persona = item.persona,
                        isDefault = item.isDefault,
                        isCurrent = item.persona.id == effectiveCurrentId,
                        onClick = {
                            onSelectPersona(item.persona.id)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}


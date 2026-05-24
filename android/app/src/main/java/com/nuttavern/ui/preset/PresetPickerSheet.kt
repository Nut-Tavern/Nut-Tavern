package com.nuttavern.ui.preset

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
import com.nuttavern.ui.components.NutTavernSheetTitle
import com.nuttavern.ui.viewmodel.PresetViewModel

/**
 * 右侧栏的"预设选择"抽屉。
 *
 * 与 [PresetListScreen] 同源 — 使用同一个 [PresetViewModel] 与 [PresetCard]。
 * 区别:
 * - 顺序与列表页一致,但抽屉里没有拖把手;
 * - 没有"+ 新建预设"入口,新建必须从设置页走;
 * - 卡片主区域点击 = 切换当前会话预设并立即关闭抽屉,无二次确认;
 * - 卡片右侧编辑键 → 进编辑页(由 [onOpenPresetDetail] 决定路由)。
 *
 * "当前正在使用"的预设由调用方传入([currentPresetId])来判定 — 真源在
 * [com.nuttavern.ui.viewmodel.ChatViewModel.currentPresetId],持久化到
 * `conversations.presetId`。仓库总会有兜底默认预设,所以这里不需要"无"伪卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetPickerSheet(
    visible: Boolean,
    currentPresetId: String?,
    onSelectPreset: (presetId: String) -> Unit,
    onOpenPresetDetail: (presetId: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: PresetViewModel = hiltViewModel(),
) {
    if (!visible) return

    val items by viewModel.items.collectAsState()
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
            NutTavernSheetTitle(title = "切换预设")
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.preset.id }) { item ->
                    PresetCard(
                        preset = item.preset,
                        isDefault = item.isDefault,
                        isCurrent = item.preset.id == currentPresetId,
                        onClick = {
                            onSelectPreset(item.preset.id)
                            onDismiss()
                        },
                        editButton = {
                            PresetEditIconButton(onClick = { onOpenPresetDetail(item.preset.id) })
                        },
                    )
                }
            }
        }
    }
}

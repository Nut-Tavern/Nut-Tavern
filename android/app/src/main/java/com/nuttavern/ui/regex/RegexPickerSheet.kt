package com.nuttavern.ui.regex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Regex
import com.nuttavern.ui.components.NutTavernEntityCard
import com.nuttavern.ui.components.NutTavernEntitySwitch
import com.nuttavern.ui.components.NutTavernSheetTitle
import com.nuttavern.ui.viewmodel.RegexScriptViewModel

/**
 * 右侧栏的"我的正则"抽屉。显示全部组 + 散规则,Switch 控制启用状态。
 *
 * 与 [RegexListScreen] 同源 — 共用 [RegexScriptViewModel.topLevelItems]。
 * 区别:
 * - 没有拖排序;
 * - 顶部一个"+"快捷新建按钮;
 * - 卡片视觉走 [NutTavernEntityCard] 公共组件,与设置页保持一致;
 * - leading:静态语义图标(组 = FolderOpen / 散规则 = Regex),无入口按钮 — 抽屉里
 *   不提供"进组"和"编辑"动作,这两个走设置页;
 * - trailing:启用 Switch,无拖把手。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegexPickerSheet(
    visible: Boolean,
    onCreateRegex: () -> Unit,
    onOpenRegexDetail: (regexId: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: RegexScriptViewModel = hiltViewModel(),
) {
    if (!visible) return

    val topLevelItems by viewModel.topLevelItems.collectAsState()
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    NutTavernSheetTitle(title = "我的正则")
                }
                IconButton(
                    onClick = {
                        onDismiss()
                        onCreateRegex()
                    },
                ) {
                    Icon(Lucide.Plus, contentDescription = "新建正则")
                }
            }

            if (topLevelItems.isEmpty()) {
                Text(
                    text = "还没有规则,点 + 新建",
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(topLevelItems, key = { it.id }) { item ->
                        if (item.isGroup) {
                            val group = item.group ?: return@items
                            NutTavernEntityCard(
                                title = group.name,
                                titleFallback = "未命名规则组",
                                subtitle = if (group.scripts.isEmpty()) "暂无规则"
                                else "共 ${group.scripts.size} 条规则",
                                onClick = null,
                                leading = {
                                    PickerLeadingIcon(icon = Lucide.FolderOpen)
                                },
                                trailing = {
                                    NutTavernEntitySwitch(
                                        checked = group.enabled,
                                        onCheckedChange = {
                                            viewModel.toggleGroupEnabled(group.id, it)
                                        },
                                    )
                                },
                            )
                        } else {
                            val script = item.orphan ?: return@items
                            NutTavernEntityCard(
                                title = script.scriptName,
                                titleFallback = "未命名规则",
                                subtitle = regexScriptSubtitle(script),
                                onClick = null,
                                leading = {
                                    PickerLeadingIcon(icon = Lucide.Regex)
                                },
                                trailing = {
                                    NutTavernEntitySwitch(
                                        checked = !script.disabled,
                                        onCheckedChange = { enabled ->
                                            viewModel.toggleOrphanEnabled(script.id, !enabled)
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 抽屉卡 leading 静态语义图标。32dp 框 + 18dp 图标,与设置页 leading 按钮触区视觉对齐
 * (抽屉内部不提供"进组 / 编辑"动作,所以这里是只读图标,没有 onClick)。
 */
@Composable
private fun PickerLeadingIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Box(
        modifier = Modifier.size(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

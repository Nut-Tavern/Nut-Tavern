package com.nuttavern.ui.persona

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuttavern.data.persona.PersonaPosition
import com.nuttavern.data.persona.PersonaRole
import com.nuttavern.ui.components.NutTavernSelectedCheckIcon
import com.nuttavern.ui.components.NutTavernSheetTitle

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

package com.nuttavern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 文件附件药丸 — 用于 Composer pending 区与气泡内附件区共用展示。
 *
 * 视觉规格(用户拍板,不参数化):
 * - 胶囊形(圆角 50%) `RoundedCornerShape(50)`
 * - 横向 12dp / 纵向 6dp 内边距,无图标无副标题
 * - 单行文件名 + ellipsis 截断,文件名长用宽度自适应
 * - 删除模式右侧带 `×` 按钮(可选);气泡内只读模式整体可点击触发系统打开
 * - 背景 `surfaceContainerHigh`,前景 `onSurface`
 *
 * **为什么不用 M3 AssistChip / SuggestionChip**(违反 AGENTS.md "组件规范" 第 5 条):
 * - SuggestionChip 不支持 `trailingIcon` 参数(M3 1.3.2)
 * - AssistChip 默认带 8dp 高度边框,与气泡内紧凑排版不一致;`elevation = null`
 *   关掉边框后整体偏方,不满足"胶囊"语义
 * - 用户已确认走自写 Surface,KDoc 标注替换条件:M3 升到 1.5+ 后若 SuggestionChip
 *   支持 trailingIcon 且 chip border 可关掉,改用 SuggestionChip。
 *
 * @param fileName 文件名(含扩展名)
 * @param onClick 点击整个药丸的回调;气泡内只读模式用于触发 ACTION_VIEW
 * @param onRemove 非空时显示 × 按钮;Composer pending 区传非 null,气泡内传 null
 */
@Composable
fun FileAttachmentPill(
    fileName: String,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val baseModifier = modifier
        .widthIn(max = 240.dp)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = 12.dp, vertical = 6.dp)

    Row(
        modifier = baseModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onRemove != null) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "移除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onRemove)
                    .padding(2.dp)
                    .size(14.dp),
            )
        }
    }
}

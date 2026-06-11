package com.nuttavern.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.PenLine
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCcw
import com.nuttavern.network.ToolDiffEntry
import com.nuttavern.network.ToolDiffField
import com.nuttavern.network.ToolDiffType

@Composable
internal fun ToolDiffPreview(diffs: List<ToolDiffEntry>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        diffs.forEach { entry ->
            DiffEntryCard(entry)
        }
    }
}

@Composable
private fun DiffEntryCard(entry: ToolDiffEntry) {
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val headerFg = MaterialTheme.colorScheme.onSurface
    val icon = when (entry.type) {
        ToolDiffType.ADDED -> Lucide.Plus
        ToolDiffType.DELETED -> Lucide.Minus
        ToolDiffType.MODIFIED -> Lucide.PenLine
        ToolDiffType.STATUS -> Lucide.RefreshCcw
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = headerFg)
                Text(text = entry.title, style = MaterialTheme.typography.titleSmall, color = headerFg)
            }
            if (entry.fields.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    entry.fields.forEach { field ->
                        DiffFieldRow(field, entry.type)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffFieldRow(field: ToolDiffField, type: ToolDiffType) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = field.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        )
        when (type) {
            ToolDiffType.ADDED -> {
                DiffLine(text = field.after ?: "", isAdd = true)
            }
            ToolDiffType.STATUS -> {
                DiffLine(text = field.before ?: "", isAdd = false)
                DiffLine(text = field.after ?: "", isAdd = true)
            }
            else -> {
                if (field.before != null) DiffLine(text = field.before, isAdd = false)
                if (field.after != null) DiffLine(text = field.after, isAdd = true)
            }
        }
    }
}

@Composable
private fun DiffLine(text: String, isAdd: Boolean, modifier: Modifier = Modifier) {
    // 现代 IDE 风格: 整行极淡背景(透明度 ~10%), 左侧固定宽度指示器带强对比色。
    // 文本颜色跟随主题(不强行变红绿)。
    val indicatorColor = if (isAdd) Color(0xFF4CAF50) else Color(0xFFF44336)
    val rowBgColor = indicatorColor.copy(alpha = 0.1f)
    val highlightBgColor = indicatorColor.copy(alpha = 0.35f)
    val textColor = MaterialTheme.colorScheme.onSurface

    val annotated = buildAnnotatedString {
        if (isAdd && text.contains("[+") && text.contains("+]")) {
            var currentStr = text
            while (currentStr.isNotEmpty()) {
                val startIdx = currentStr.indexOf("[+")
                if (startIdx == -1) {
                    append(currentStr)
                    break
                }
                append(currentStr.substring(0, startIdx))
                val endIdx = currentStr.indexOf("+]", startIdx + 2)
                if (endIdx == -1) {
                    append(currentStr.substring(startIdx))
                    break
                }
                val highlight = currentStr.substring(startIdx + 2, endIdx)
                withStyle(SpanStyle(background = highlightBgColor, color = textColor)) {
                    append(highlight)
                }
                currentStr = currentStr.substring(endIdx + 2)
            }
        } else if (!isAdd && text.contains("[-") && text.contains("-]")) {
            var currentStr = text
            while (currentStr.isNotEmpty()) {
                val startIdx = currentStr.indexOf("[-")
                if (startIdx == -1) {
                    append(currentStr)
                    break
                }
                append(currentStr.substring(0, startIdx))
                val endIdx = currentStr.indexOf("-]", startIdx + 2)
                if (endIdx == -1) {
                    append(currentStr.substring(startIdx))
                    break
                }
                val highlight = currentStr.substring(startIdx + 2, endIdx)
                withStyle(SpanStyle(background = highlightBgColor, color = textColor)) {
                    append(highlight)
                }
                currentStr = currentStr.substring(endIdx + 2)
            }
        } else {
            append(text)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBgColor)
            .height(IntrinsicSize.Min),
    ) {
        // 左侧指示条 (类似行号区的标记)
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(indicatorColor)
        )
        // 文本区
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

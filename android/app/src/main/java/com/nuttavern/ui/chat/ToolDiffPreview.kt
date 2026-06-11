package com.nuttavern.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    val (headerBg, headerFg, icon) = when (entry.type) {
        ToolDiffType.ADDED -> Triple(Color(0xFFE6F4EA), Color(0xFF1B5E20), Lucide.Plus)
        ToolDiffType.DELETED -> Triple(Color(0xFFFCE8E6), Color(0xFFC62828), Lucide.Minus)
        ToolDiffType.MODIFIED -> Triple(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface, Lucide.PenLine)
        ToolDiffType.STATUS -> Triple(Color(0xFFFEF7E0), Color(0xFFE65100), Lucide.RefreshCcw)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
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
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = field.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when (type) {
            ToolDiffType.ADDED -> {
                DiffLine(text = field.after ?: "", isAdd = true)
            }
            ToolDiffType.STATUS -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiffLine(text = field.before ?: "", isAdd = false, modifier = Modifier.weight(1f))
                    Icon(imageVector = Lucide.RefreshCcw, contentDescription = null, modifier = Modifier.size(14.dp))
                    DiffLine(text = field.after ?: "", isAdd = true, modifier = Modifier.weight(1f))
                }
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
    val bgColor = if (isAdd) Color(0xFFE6F4EA) else Color(0xFFFCE8E6)
    val textColor = if (isAdd) Color(0xFF1B5E20) else Color(0xFFC62828)

    val annotated = buildAnnotatedString {
        if (isAdd && text.contains("[+") && text.contains("+]")) {
            // 使用非正则的普通字符串拆分,因为带中括号和加号。
            // 文本形如: "前文[+新增+]后文"
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
                withStyle(SpanStyle(background = Color(0xFFC8E6C9), color = Color.Black)) {
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
                withStyle(SpanStyle(background = Color(0xFFFFCDD2), color = Color.Black)) {
                    append(highlight)
                }
                currentStr = currentStr.substring(endIdx + 2)
            }
        } else {
            append(text)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = textColor,
        )
    }
}

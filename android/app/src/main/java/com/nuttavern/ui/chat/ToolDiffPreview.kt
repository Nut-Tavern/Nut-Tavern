package com.nuttavern.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PenLine
import com.nuttavern.network.DiffLine
import com.nuttavern.network.DiffLineKind
import com.nuttavern.network.ToolDiffEntry
import com.nuttavern.network.ToolDiffField

/** diff 增删强调色:亮色用 GitHub 亮色值,暗色压暗避免刺眼。 */
private data class DiffPalette(val add: Color, val remove: Color, val fillAlpha: Float)

private val LightDiffPalette = DiffPalette(Color(0xFF2DA44E), Color(0xFFCF222E), 0.14f)
private val DarkDiffPalette = DiffPalette(Color(0xFF3FB950), Color(0xFFF85149), 0.16f)

/** 行号槽固定宽度,容纳到三位数行号不换行。 */
private val GutterWidth = 34.dp

@Composable
internal fun ToolDiffPreview(diffs: List<ToolDiffEntry>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        diffs.forEach { entry ->
            DiffEntryCard(entry)
        }
    }
}

@Composable
private fun DiffEntryCard(entry: ToolDiffEntry) {
    // 一个条目一个大标题,下面把每个修改类(字段)拆成独立的 diff 容器。
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Lucide.PenLine,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        entry.fields.forEach { field ->
            DiffFieldContainer(field)
        }
    }
}

@Composable
private fun DiffFieldContainer(field: ToolDiffField) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Text(
                text = field.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            )
            DiffFieldBody(field)
        }
    }
}

@Composable
private fun DiffFieldBody(field: ToolDiffField) {
    val palette = if (isSystemInDarkTheme()) DarkDiffPalette else LightDiffPalette
    val sharedScroll = rememberScrollState()
    // 固定行高,行号与正文严格同高;不另加垂直 padding,行与行紧贴无缝隙。
    val lineStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        lineHeight = 1.6.em,
    )

    // BoxWithConstraints 拿可视宽度;内层 defaultMinSize 让短行的高亮背景也铺满整行宽。
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val minRowWidth = maxWidth
        Box(modifier = Modifier.fillMaxWidth().horizontalScroll(sharedScroll)) {
            Column(modifier = Modifier.width(IntrinsicSize.Max).defaultMinSize(minWidth = minRowWidth)) {
                field.hunks.forEach { hunk ->
                    if (hunk.precededByGap) {
                        HunkSeparator()
                    }
                    hunk.lines.forEach { line -> DiffLineRow(line, lineStyle, palette) }
                }
                if (field.hasTrailingGap) {
                    HunkSeparator()
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine, lineStyle: TextStyle, palette: DiffPalette) {
    val accent = when (line.kind) {
        DiffLineKind.ADDED -> palette.add
        DiffLineKind.REMOVED -> palette.remove
        DiffLineKind.CONTEXT -> null
    }
    val rowBg = accent?.copy(alpha = palette.fillAlpha) ?: Color.Transparent
    val lineNumber = if (line.kind == DiffLineKind.REMOVED) line.oldLineNumber else line.newLineNumber

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(rowBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 行号槽:独立淡背景 + 左侧强调竖条,与正文区隔开。
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(accent ?: Color.Transparent),
            )
            Text(
                text = lineNumber?.toString().orEmpty(),
                style = lineStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier
                    .width(GutterWidth)
                    .padding(start = 4.dp, end = 8.dp),
            )
        }
        Text(
            text = line.text,
            style = lineStyle,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp),
        )
    }
}

@Composable
private fun HunkSeparator() {
    // hunk 之间的省略分隔:铺满整行的淡色横条,行号槽留空背景,右侧文字说明,清晰切割上下文。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(GutterWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Text(
            text = "省略未改动内容",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}

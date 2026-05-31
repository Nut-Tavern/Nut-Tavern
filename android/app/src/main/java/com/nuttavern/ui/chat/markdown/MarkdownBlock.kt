package com.nuttavern.ui.chat.markdown

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ClipEntry
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.SquareCheckBig
import kotlinx.coroutines.launch
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * 把 markdown AST 的"块级节点"渲染为 Compose 子树。
 *
 * 入口期望的 `node` 是 MARKDOWN_FILE 的直接子节点(paragraph / heading / list / ...),
 * 调用方负责遍历最外层 children。流式中间态出现的不完整块(只解析到一半的列表/代码块)
 * 由 jetbrains-markdown 容忍处理,这里"未识别类型 → 原文兜底",不会丢字。
 */
@Composable
internal fun RenderMarkdownBlock(
    node: ASTNode,
    rawText: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    when (node.type) {
        MarkdownElementTypes.PARAGRAPH -> RenderParagraph(node, rawText, color, modifier)

        MarkdownElementTypes.ATX_1 -> RenderHeading(1, node, rawText, color, modifier)
        MarkdownElementTypes.ATX_2 -> RenderHeading(2, node, rawText, color, modifier)
        MarkdownElementTypes.ATX_3 -> RenderHeading(3, node, rawText, color, modifier)
        MarkdownElementTypes.ATX_4 -> RenderHeading(4, node, rawText, color, modifier)
        MarkdownElementTypes.ATX_5 -> RenderHeading(5, node, rawText, color, modifier)
        MarkdownElementTypes.ATX_6 -> RenderHeading(6, node, rawText, color, modifier)
        MarkdownElementTypes.SETEXT_1 -> RenderHeading(1, node, rawText, color, modifier)
        MarkdownElementTypes.SETEXT_2 -> RenderHeading(2, node, rawText, color, modifier)

        MarkdownElementTypes.UNORDERED_LIST -> RenderList(
            listNode = node,
            rawText = rawText,
            ordered = false,
            depth = 0,
            color = color,
            modifier = modifier,
        )

        MarkdownElementTypes.ORDERED_LIST -> RenderList(
            listNode = node,
            rawText = rawText,
            ordered = true,
            depth = 0,
            color = color,
            modifier = modifier,
        )

        MarkdownElementTypes.BLOCK_QUOTE -> RenderBlockQuote(node, rawText, color, modifier)

        MarkdownElementTypes.CODE_FENCE -> RenderCodeFence(node, rawText, modifier)
        MarkdownElementTypes.CODE_BLOCK -> RenderCodeBlock(node, rawText, modifier)

        GFMElementTypes.TABLE -> RenderTable(node, rawText, color, modifier)

        MarkdownTokenTypes.HORIZONTAL_RULE -> HorizontalDivider(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = MarkdownTokens.HorizontalRulePadding),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        MarkdownElementTypes.HTML_BLOCK -> RenderHtmlBlockFallback(node, rawText, modifier)

        MarkdownElementTypes.LINK_DEFINITION,
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.WHITE_SPACE -> Unit // 忽略空白与定义,不产出节点

        else -> RenderRawFallback(node, rawText, color, modifier)
    }
}

@Composable
private fun RenderParagraph(
    node: ASTNode,
    rawText: String,
    color: Color,
    modifier: Modifier,
) {
    val text = buildInlineAnnotatedString(node, rawText)
    if (text.isEmpty()) return
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = paragraphStyle(),
        color = color,
    )
}

@Composable
private fun RenderHeading(
    level: Int,
    node: ASTNode,
    rawText: String,
    color: Color,
    modifier: Modifier,
) {
    val contentNode = node.children.firstOrNull {
        it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT
    } ?: node
    val text = buildInlineAnnotatedString(contentNode, rawText)
    if (text.isEmpty()) return
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = MarkdownTokens.HeadingTopSpacing,
                bottom = MarkdownTokens.HeadingBottomSpacing,
            ),
        style = headingStyle(level),
        color = color,
    )
}

@Composable
private fun RenderList(
    listNode: ASTNode,
    rawText: String,
    ordered: Boolean,
    depth: Int,
    color: Color,
    modifier: Modifier,
) {
    val items = listNode.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    if (items.isEmpty()) return

    var index = 1
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (depth == 0) 0.dp else MarkdownTokens.ListIndent),
        verticalArrangement = Arrangement.spacedBy(MarkdownTokens.ListItemSpacing),
    ) {
        items.forEach { item ->
            val bullet = if (ordered) "${index++}. " else bulletForDepth(depth)
            ListItemRow(
                bullet = bullet,
                item = item,
                rawText = rawText,
                depth = depth,
                color = color,
            )
        }
    }
}

private fun bulletForDepth(depth: Int): String = when (depth % 3) {
    0 -> "•  "
    1 -> "◦  "
    else -> "▪  "
}

/**
 * 识别 GFM 任务列表项:LIST_ITEM 直接含 CHECK_BOX 子节点(解析器产出独立节点,
 * 不是文本前缀)。返回勾选状态;非任务项返回 null。
 * 勾选判定:CHECK_BOX 原文 trim 后是否为 `[x]`(忽略大小写)。
 */
private fun detectCheckbox(item: ASTNode, rawText: String): Boolean? {
    val checkbox = item.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX } ?: return null
    val mark = checkbox.textIn(rawText).trim()
    return mark.equals("[x]", ignoreCase = true)
}

@Composable
private fun ListItemRow(
    bullet: String,
    item: ASTNode,
    rawText: String,
    depth: Int,
    color: Color,
) {
    val leading = buildLeadingInlineAnnotatedString(item, rawText)
    // 任务列表:CHECK_BOX 是独立 AST 节点,这里按节点判定,改用只读图标(聊天里不可勾选)。
    val checked = detectCheckbox(item, rawText)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        if (checked != null) {
            // Icon 无文字基线,Top 对齐会贴在行盒顶部偏高;下移半个行高差,视觉落在首行文字中线。
            Icon(
                imageVector = if (checked) Lucide.SquareCheckBig else Lucide.Square,
                contentDescription = if (checked) "已完成" else "未完成",
                tint = if (checked) MaterialTheme.colorScheme.primary else color,
                modifier = Modifier
                    .padding(
                        end = MarkdownTokens.TaskCheckboxEndPadding,
                        top = MarkdownTokens.TaskCheckboxTopPadding,
                    )
                    .size(MarkdownTokens.TaskCheckboxSize),
            )
        } else {
            // 与正文同基线对齐:Top 对齐会让符号比文字偏高一点点,基线对齐才贴合首行文字。
            Text(
                text = bullet,
                style = paragraphStyle(),
                color = color,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .alignByBaseline(),
            verticalArrangement = Arrangement.spacedBy(MarkdownTokens.ListItemSpacing),
        ) {
            if (leading.isNotEmpty()) {
                Text(
                    text = leading,
                    style = paragraphStyle(),
                    color = color,
                )
            }
            // 列表项里嵌套的子块(嵌套列表 / 代码块 / 引用 等)在这里继续渲染。
            item.children.forEach { child ->
                when (child.type) {
                    MarkdownElementTypes.UNORDERED_LIST -> RenderList(
                        listNode = child,
                        rawText = rawText,
                        ordered = false,
                        depth = depth + 1,
                        color = color,
                        modifier = Modifier,
                    )

                    MarkdownElementTypes.ORDERED_LIST -> RenderList(
                        listNode = child,
                        rawText = rawText,
                        ordered = true,
                        depth = depth + 1,
                        color = color,
                        modifier = Modifier,
                    )

                    MarkdownElementTypes.BLOCK_QUOTE -> RenderBlockQuote(
                        node = child,
                        rawText = rawText,
                        color = color,
                        modifier = Modifier,
                    )

                    MarkdownElementTypes.CODE_FENCE -> RenderCodeFence(child, rawText, Modifier)
                    MarkdownElementTypes.CODE_BLOCK -> RenderCodeBlock(child, rawText, Modifier)

                    GFMElementTypes.TABLE -> RenderTable(child, rawText, color, Modifier)

                    else -> Unit // 行内部分已并入 leading,这里只处理子块。
                }
            }
        }
    }
}

@Composable
private fun RenderBlockQuote(
    node: ASTNode,
    rawText: String,
    color: Color,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = blockQuoteBackground(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .width(MarkdownTokens.BlockQuoteIndicatorWidth)
                    .fillMaxHeight()
                    .background(blockQuoteIndicatorColor()),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = MarkdownTokens.BlockQuoteContentPadding,
                        end = MarkdownTokens.BlockQuoteContentPadding,
                        top = MarkdownTokens.BlockQuoteVerticalPadding,
                        bottom = MarkdownTokens.BlockQuoteVerticalPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(MarkdownTokens.BlockSpacing),
            ) {
                node.children.forEach { child ->
                    if (child.type == MarkdownTokenTypes.BLOCK_QUOTE) return@forEach
                    // 引用内段落用小字专属样式(blockQuoteStyle);其余子块(嵌套列表 / 代码块 等)
                    // 仍走通用递归,保持原有渲染。
                    if (child.type == MarkdownElementTypes.PARAGRAPH) {
                        val text = buildInlineAnnotatedString(child, rawText)
                        if (text.isNotEmpty()) {
                            Text(
                                text = text,
                                modifier = Modifier.fillMaxWidth(),
                                style = blockQuoteStyle(),
                                color = color,
                            )
                        }
                    } else {
                        RenderMarkdownBlock(
                            node = child,
                            rawText = rawText,
                            color = color,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderCodeFence(
    node: ASTNode,
    rawText: String,
    modifier: Modifier,
) {
    val language = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)
        ?.textIn(rawText)
        ?.trim()
        .orEmpty()
    val code = extractFenceCode(node, rawText)
    RenderCodeContainer(language = language, code = code, modifier = modifier)
}

@Composable
private fun RenderCodeBlock(
    node: ASTNode,
    rawText: String,
    modifier: Modifier,
) {
    // 缩进式代码块没有 language,内容是 CODE_LINE token 的拼接,保留原始换行。
    val lines = node.children
        .filter { it.type == MarkdownTokenTypes.CODE_LINE }
        .map { it.textIn(rawText) }
    val code = lines.joinToString(separator = "\n").trimEnd()
    RenderCodeContainer(language = "", code = code, modifier = modifier)
}

@Composable
private fun RenderCodeContainer(
    language: String,
    code: String,
    modifier: Modifier,
) {
    if (code.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = codeBlockBackground(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 头部栏:单独深色块(语言标签左 + 复制按钮右),与代码区分层。
            CodeBlockHeader(language = language, code = code)
            // 代码块横向滚动,避免长行折行破坏可读性。
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .horizontalScroll(scrollState)
                    .padding(MarkdownTokens.CodeBlockPadding),
            ) {
                Text(
                    text = code,
                    style = codeBlockTextStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 代码块头部栏:单独深色块,语言标签 + 复制按钮。流式过程中复制按钮始终可用(复制当前已解析的代码)。 */
@Composable
private fun CodeBlockHeader(
    language: String,
    code: String,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(codeBlockHeaderBackground())
            .padding(
                start = MarkdownTokens.CodeBlockPadding,
                end = MarkdownTokens.CodeBlockHeaderEndPadding,
                top = MarkdownTokens.CodeBlockHeaderVerticalPadding,
                bottom = MarkdownTokens.CodeBlockHeaderVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = language.ifBlank { "code" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(MarkdownTokens.CodeCopyButtonSize)
                .clip(RoundedCornerShape(6.dp))
                .clickable {
                    coroutineScope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.Copy,
                contentDescription = "复制代码",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MarkdownTokens.CodeCopyIconSize),
            )
        }
    }
}

private fun extractFenceCode(node: ASTNode, rawText: String): String {
    val builder = StringBuilder()
    var seenContent = false
    node.children.forEach { child ->
        when (child.type) {
            MarkdownTokenTypes.CODE_FENCE_CONTENT -> {
                builder.append(child.textIn(rawText))
                seenContent = true
            }

            MarkdownTokenTypes.EOL -> {
                if (seenContent) builder.append('\n')
            }

            else -> Unit
        }
    }
    return builder.toString().trimEnd('\n')
}

@Composable
private fun RenderTable(
    node: ASTNode,
    rawText: String,
    color: Color,
    modifier: Modifier,
) {
    val headerNode = node.findChildOfType(GFMElementTypes.HEADER)
    val rowNodes = node.children.filter { it.type == GFMElementTypes.ROW }
    val headers = headerNode?.let { extractCells(it, rawText) }.orEmpty()
    val rows = rowNodes.map { extractCells(it, rawText) }
    val columnCount = maxOf(
        headers.size,
        rows.maxOfOrNull { it.size } ?: 0,
    )
    if (columnCount == 0) return

    val alignments = parseColumnAlignments(node.textIn(rawText), columnCount)
    val borderColor = tableBorderColor()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        color = Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderColor),
    ) {
        // width(IntrinsicSize.Max) 让 Column 宽度等于最宽行的固有宽度,
        // 否则 horizontalScroll 给的是无限宽约束,内部 HorizontalDivider 的 fillMaxWidth() 会退化成 0,
        // 表现为水平分隔线消失。
        Column(modifier = Modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = 240.dp),
        ) {
            if (headers.isNotEmpty()) {
                TableRow(
                    cells = padCells(headers, columnCount),
                    isHeader = true,
                    rowBackground = tableHeaderBackground(),
                    alignments = alignments,
                    color = color,
                    borderColor = borderColor,
                )
            }
            rows.forEachIndexed { rowIndex, row ->
                TableRow(
                    cells = padCells(row, columnCount),
                    isHeader = false,
                    rowBackground = Color.Transparent,
                    alignments = alignments,
                    color = color,
                    borderColor = borderColor,
                    // 最后一行不画底部分隔线,避免与外边框重叠成双线。
                    drawBottomDivider = rowIndex < rows.lastIndex,
                )
            }
        }
    }
}

/**
 * 从表格原文解析每列对齐(GFM 分隔行 `:---:` / `:---` / `---:`)。
 * 分隔行是表格的第 2 个非空行;按 `|` 切列,看每列两端的冒号决定对齐。
 * 解析失败 / 越界一律退回 [TextAlign.Start],不影响表格渲染。
 */
private fun parseColumnAlignments(tableText: String, columnCount: Int): List<TextAlign> {
    val default = List(columnCount) { TextAlign.Start }
    val lines = tableText.lines().map { it.trim() }.filter { it.isNotBlank() }
    val separator = lines.getOrNull(1) ?: return default
    if (!separator.contains('-')) return default
    val specs = separator.trim('|').split('|').map { it.trim() }
    return List(columnCount) { i ->
        val spec = specs.getOrNull(i) ?: return@List TextAlign.Start
        val left = spec.startsWith(':')
        val right = spec.endsWith(':')
        when {
            left && right -> TextAlign.Center
            right -> TextAlign.End
            else -> TextAlign.Start
        }
    }
}

@Composable
private fun TableRow(
    cells: List<AnnotatedString>,
    isHeader: Boolean,
    rowBackground: Color,
    alignments: List<TextAlign>,
    color: Color,
    borderColor: Color,
    drawBottomDivider: Boolean = true,
) {
    // height(IntrinsicSize.Min) 让 Row 高度等于子元素最大固有高度,
    // 单元格之间的垂直分隔线 fillMaxHeight() 才能拿到完整高度。
    Row(modifier = Modifier
        .fillMaxWidth()
        .background(rowBackground)
        .height(IntrinsicSize.Min),
    ) {
        cells.forEachIndexed { index, cell ->
            val align = alignments.getOrElse(index) { TextAlign.Start }
            Box(
                modifier = Modifier
                    .widthIn(min = MarkdownTokens.TableMinColumnWidth)
                    .padding(
                        horizontal = MarkdownTokens.TableCellHorizontalPadding,
                        vertical = MarkdownTokens.TableCellVerticalPadding,
                    ),
            ) {
                Text(
                    text = cell,
                    style = if (isHeader) tableHeaderStyle() else tableCellStyle(),
                    color = color,
                    textAlign = align,
                )
            }
            // 竖向分隔线:与横线同规格(1dp + borderColor)。
            if (index < cells.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(borderColor),
                )
            }
        }
    }
    // 表头下分隔线加粗(2dp)以区分数据区;数据行用 1dp,最后一行不画(避免与外边框双线)。
    if (isHeader) {
        HorizontalDivider(
            thickness = MarkdownTokens.TableHeaderDividerThickness,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    } else if (drawBottomDivider) {
        HorizontalDivider(color = borderColor)
    }
}

@Composable
private fun extractCells(rowNode: ASTNode, rawText: String): List<AnnotatedString> {
    return rowNode.children
        .filter { it.type == GFMTokenTypes.CELL }
        .map { cell -> buildInlineAnnotatedString(cell, rawText) }
}

private fun padCells(
    cells: List<AnnotatedString>,
    columnCount: Int,
): List<AnnotatedString> {
    if (cells.size >= columnCount) return cells
    val empty = AnnotatedString("")
    return cells + List(columnCount - cells.size) { empty }
}

@Composable
private fun RenderHtmlBlockFallback(
    node: ASTNode,
    rawText: String,
    modifier: Modifier,
) {
    val text = node.textIn(rawText).trim()
    if (text.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = codeBlockBackground(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(MarkdownTokens.CodeBlockPadding),
            style = codeBlockTextStyle(),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RenderRawFallback(
    node: ASTNode,
    rawText: String,
    color: Color,
    modifier: Modifier,
) {
    val raw = node.textIn(rawText).trim()
    if (raw.isEmpty()) return
    Text(
        text = raw,
        modifier = modifier.fillMaxWidth(),
        style = paragraphStyle(),
        color = color,
    )
}

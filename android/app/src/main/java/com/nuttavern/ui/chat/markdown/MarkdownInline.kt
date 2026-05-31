package com.nuttavern.ui.chat.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * 把行内 AST 子树构造成 Compose [AnnotatedString]。
 *
 * 调用约定:传入的 `node` 通常是 PARAGRAPH / heading content / LIST_ITEM 的内联容器,
 * 内部递归处理 emph / strong / strikethrough / code / link 等。流式中间态可能出现
 * 不完整的 markdown(如未闭合的 `*`),解析器会以 TEXT token 兜底,渲染层不会抛异常。
 */
@Composable
internal fun buildInlineAnnotatedString(
    node: ASTNode,
    rawText: String,
): AnnotatedString {
    val context = inlineContext()
    return buildAnnotatedString {
        node.children.forEach { child ->
            appendInline(child, rawText, context)
        }
    }
}

/**
 * 直接渲染 LIST_ITEM 等容器中的"首段内联部分":跳过其中的子块(嵌套列表、引用、代码块),
 * 这些子块由 [MarkdownBlock] 单独承载。
 */
@Composable
internal fun buildLeadingInlineAnnotatedString(
    container: ASTNode,
    rawText: String,
): AnnotatedString {
    val context = inlineContext()
    return buildAnnotatedString {
        container.children.forEach { child ->
            if (child.type in BLOCK_TYPES_INSIDE_LIST_ITEM) return@forEach
            // 列表标记 token(bullet `- ` / number `1. ` / checkbox `[x] `)不是正文,
            // 由渲染层单独画(bullet Text / checkbox Icon),这里跳过,避免注入到文字里。
            if (child.type in LIST_MARKER_TOKENS) return@forEach
            appendInline(child, rawText, context)
        }
    }
}

private val LIST_MARKER_TOKENS = setOf(
    MarkdownTokenTypes.LIST_BULLET,
    MarkdownTokenTypes.LIST_NUMBER,
    GFMTokenTypes.CHECK_BOX,
)

private val BLOCK_TYPES_INSIDE_LIST_ITEM = setOf(
    MarkdownElementTypes.UNORDERED_LIST,
    MarkdownElementTypes.ORDERED_LIST,
    MarkdownElementTypes.BLOCK_QUOTE,
    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.CODE_BLOCK,
    GFMElementTypes.TABLE,
)

/** 行内渲染上下文:把所有需要 Composable 取色 / 取样式的工作集中提前完成。 */
@Composable
@ReadOnlyComposable
private fun inlineContext(): InlineContext = InlineContext(
    inlineCode = inlineCodeSpanStyle(),
    link = linkSpanStyle(),
    strong = strongSpanStyle(),
    emphasis = emphasisSpanStyle(),
    strikethrough = strikethroughSpanStyle(),
    autoLinkColor = MaterialTheme.colorScheme.primary,
)

private data class InlineContext(
    val inlineCode: SpanStyle,
    val link: SpanStyle,
    val strong: SpanStyle,
    val emphasis: SpanStyle,
    val strikethrough: SpanStyle,
    val autoLinkColor: androidx.compose.ui.graphics.Color,
)

private fun AnnotatedString.Builder.appendInline(
    node: ASTNode,
    rawText: String,
    ctx: InlineContext,
) {
    when (node.type) {
        MarkdownElementTypes.STRONG -> withStyle(ctx.strong) {
            appendInlineChildrenSkippingMarkers(node, rawText, ctx, EMPH_MARKER_TOKENS)
        }

        MarkdownElementTypes.EMPH -> withStyle(ctx.emphasis) {
            appendInlineChildrenSkippingMarkers(node, rawText, ctx, EMPH_MARKER_TOKENS)
        }

        GFMElementTypes.STRIKETHROUGH -> withStyle(ctx.strikethrough) {
            appendInlineChildrenSkippingMarkers(node, rawText, ctx, STRIKETHROUGH_MARKER_TOKENS)
        }

        MarkdownElementTypes.CODE_SPAN -> {
            val codeText = extractCodeSpanText(node, rawText)
            if (codeText.isNotEmpty()) {
                withStyle(ctx.inlineCode) {
                    append(codeText)
                }
            }
        }

        MarkdownElementTypes.INLINE_LINK -> appendInlineLink(node, rawText, ctx)

        MarkdownElementTypes.AUTOLINK -> appendAutolink(node, rawText, ctx)

        GFMTokenTypes.GFM_AUTOLINK -> appendGfmAutolink(node, rawText, ctx)

        MarkdownElementTypes.IMAGE -> append(extractImageAlt(node, rawText))

        MarkdownTokenTypes.HARD_LINE_BREAK -> append('\n')

        MarkdownTokenTypes.EOL -> append(' ')

        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
            // 引用式链接没有内嵌 destination,本轮不做查表,直接渲染原始文字。
            append(node.textIn(rawText))
        }

        // 容器型节点:递归处理子节点。
        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.LINK_TEXT,
        MarkdownElementTypes.LINK_LABEL -> {
            node.children.forEach { child -> appendInline(child, rawText, ctx) }
        }

        else -> {
            // 兜底:纯文本 token / 未识别类型 → 直接 append 原始片段。
            // 流式中间态出现的"半截"节点都走这里,保证不丢字。
            if (node.children.isEmpty()) {
                append(node.textIn(rawText))
            } else {
                node.children.forEach { child -> appendInline(child, rawText, ctx) }
            }
        }
    }
}

private val EMPH_MARKER_TOKENS = setOf(
    MarkdownTokenTypes.EMPH,
)

private val STRIKETHROUGH_MARKER_TOKENS = setOf(
    GFMTokenTypes.TILDE,
)

private fun AnnotatedString.Builder.appendInlineChildrenSkippingMarkers(
    node: ASTNode,
    rawText: String,
    ctx: InlineContext,
    markerTypes: Set<org.intellij.markdown.IElementType>,
) {
    node.children.forEach { child ->
        if (child.type in markerTypes) return@forEach
        appendInline(child, rawText, ctx)
    }
}

private fun AnnotatedString.Builder.appendInlineLink(
    node: ASTNode,
    rawText: String,
    ctx: InlineContext,
) {
    val linkText = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
    val destination = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.textIn(rawText)
        ?.trim()
        ?.removeSurrounding("<", ">")
        .orEmpty()
    val displayBuilder: AnnotatedString.Builder.() -> Unit = {
        if (linkText != null) {
            // LINK_TEXT 内部含中括号,跳过那两个 token,只渲染文字与子节点。
            linkText.children.forEach { child ->
                if (child.type == MarkdownTokenTypes.LBRACKET ||
                    child.type == MarkdownTokenTypes.RBRACKET
                ) return@forEach
                appendInline(child, rawText, ctx)
            }
        } else {
            append(node.textIn(rawText))
        }
    }
    if (destination.isNotBlank()) {
        withLink(
            LinkAnnotation.Url(
                url = destination,
                styles = TextLinkStyles(style = ctx.link),
            ),
        ) {
            displayBuilder()
        }
    } else {
        displayBuilder()
    }
}

private fun AnnotatedString.Builder.appendAutolink(
    node: ASTNode,
    rawText: String,
    ctx: InlineContext,
) {
    // AUTOLINK 形如 <https://example.com>,内部包含 LT/AUTOLINK/GT 三个子节点。
    val urlNode = node.findChildOfType(MarkdownTokenTypes.AUTOLINK)
        ?: node.findChildOfType(MarkdownTokenTypes.EMAIL_AUTOLINK)
    val url = urlNode?.textIn(rawText).orEmpty().trim()
    if (url.isBlank()) {
        append(node.textIn(rawText))
        return
    }
    val href = if (urlNode?.type == MarkdownTokenTypes.EMAIL_AUTOLINK && !url.startsWith("mailto:")) {
        "mailto:$url"
    } else {
        url
    }
    withLink(
        LinkAnnotation.Url(
            url = href,
            styles = TextLinkStyles(style = ctx.link),
        ),
    ) {
        append(url)
    }
}

private fun AnnotatedString.Builder.appendGfmAutolink(
    node: ASTNode,
    rawText: String,
    ctx: InlineContext,
) {
    val raw = node.textIn(rawText).trim()
    if (raw.isBlank()) {
        append(node.textIn(rawText))
        return
    }
    val href = when {
        raw.contains("@") && !raw.startsWith("http") -> "mailto:$raw"
        raw.startsWith("www.") -> "https://$raw"
        else -> raw
    }
    withLink(
        LinkAnnotation.Url(
            url = href,
            styles = TextLinkStyles(style = ctx.link),
        ),
    ) {
        append(raw)
    }
}

private fun extractCodeSpanText(node: ASTNode, rawText: String): String {
    // CODE_SPAN 内部由若干 BACKTICK + 文本组成,去掉首尾 backtick token 后拼接。
    val firstBacktickEnd = node.children.firstOrNull { it.type == MarkdownTokenTypes.BACKTICK }
        ?.endOffset
    val lastBacktickStart = node.children.lastOrNull { it.type == MarkdownTokenTypes.BACKTICK }
        ?.startOffset
    if (firstBacktickEnd != null && lastBacktickStart != null && firstBacktickEnd <= lastBacktickStart) {
        return rawText.safeSubstring(firstBacktickEnd, lastBacktickStart).trim()
    }
    return node.textIn(rawText).trim('`')
}

private fun extractImageAlt(node: ASTNode, rawText: String): String {
    val linkText = node.findChildOfType(MarkdownElementTypes.INLINE_LINK)
        ?.findChildOfType(MarkdownElementTypes.LINK_TEXT)
        ?: node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
    val alt = linkText?.children
        ?.filter { it.type != MarkdownTokenTypes.LBRACKET && it.type != MarkdownTokenTypes.RBRACKET }
        ?.joinToString(separator = "") { it.textIn(rawText) }
        ?.trim()
    return if (alt.isNullOrBlank()) "[图片]" else "[图片: $alt]"
}

internal fun ASTNode.textIn(rawText: String): String {
    return rawText.safeSubstring(startOffset, endOffset)
}

internal fun String.safeSubstring(start: Int, end: Int): String {
    val len = length
    if (len == 0) return ""
    val s = start.coerceIn(0, len)
    val e = end.coerceIn(s, len)
    return substring(s, e)
}

package com.nuttavern.ui.chat.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 聊天 markdown 渲染的样式与间距 token。
 *
 * 唯一可信来源:所有 [MarkdownBlock] 与 [MarkdownInline] 渲染都从这里取。
 * 改皮、调字号、调间距,只动这个文件。
 *
 * 字号策略:与之前 mikepenz 接入时定下的标准一致——
 * h1→headlineSmall(24sp)... h6→labelMedium,paragraph 走 bodyLarge,
 * 让聊天里的标题不至于压垮正文。
 */
internal object MarkdownTokens {

    val BlockSpacing: Dp = 8.dp
    val ListItemSpacing: Dp = 4.dp
    val ListIndent: Dp = 20.dp
    val HeadingTopSpacing: Dp = 12.dp
    val HeadingBottomSpacing: Dp = 4.dp
    val CodeBlockPadding: Dp = 12.dp
    val CodeBlockHeaderVerticalPadding: Dp = 4.dp
    val CodeBlockHeaderEndPadding: Dp = 4.dp
    val CodeCopyButtonSize: Dp = 28.dp
    val CodeCopyIconSize: Dp = 16.dp
    val BlockQuoteIndicatorWidth: Dp = 4.dp
    val BlockQuoteContentPadding: Dp = 12.dp
    val BlockQuoteVerticalPadding: Dp = 8.dp
    val TableCellHorizontalPadding: Dp = 12.dp
    val TableCellVerticalPadding: Dp = 10.dp
    val TableMinColumnWidth: Dp = 72.dp
    val TableHeaderDividerThickness: Dp = 2.dp
    val TaskCheckboxSize: Dp = 18.dp
    val TaskCheckboxEndPadding: Dp = 6.dp
    val TaskCheckboxTopPadding: Dp = 2.dp
    val HorizontalRulePadding: Dp = 12.dp
    val ImagePlaceholderPadding: Dp = 4.dp
}

@Composable
@ReadOnlyComposable
internal fun headingStyle(level: Int): TextStyle {
    val typography = MaterialTheme.typography
    return when (level.coerceIn(1, 6)) {
        1 -> typography.headlineSmall
        2 -> typography.titleLarge
        3 -> typography.titleMedium
        4 -> typography.titleSmall
        5 -> typography.labelLarge
        else -> typography.labelMedium
    }
}

@Composable
@ReadOnlyComposable
internal fun paragraphStyle(): TextStyle = MaterialTheme.typography.bodyLarge

@Composable
@ReadOnlyComposable
internal fun blockQuoteStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium

@Composable
@ReadOnlyComposable
internal fun codeBlockTextStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

@Composable
@ReadOnlyComposable
internal fun tableHeaderStyle(): TextStyle =
    MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)

@Composable
@ReadOnlyComposable
internal fun tableCellStyle(): TextStyle = MaterialTheme.typography.bodyMedium

/** 行内 code 的 SpanStyle:等宽字体 + 半透明 secondaryContainer 背景。 */
@Composable
@ReadOnlyComposable
internal fun inlineCodeSpanStyle(): SpanStyle = SpanStyle(
    fontFamily = FontFamily.Monospace,
    background = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
)

/** 链接 SpanStyle:primary 色 + 下划线。 */
@Composable
@ReadOnlyComposable
internal fun linkSpanStyle(): SpanStyle = SpanStyle(
    color = MaterialTheme.colorScheme.primary,
    textDecoration = TextDecoration.Underline,
)

@Composable
@ReadOnlyComposable
internal fun strongSpanStyle(): SpanStyle = SpanStyle(fontWeight = FontWeight.SemiBold)

@Composable
@ReadOnlyComposable
internal fun emphasisSpanStyle(): SpanStyle = SpanStyle(fontStyle = FontStyle.Italic)

@Composable
@ReadOnlyComposable
internal fun strikethroughSpanStyle(): SpanStyle =
    SpanStyle(textDecoration = TextDecoration.LineThrough)

/** 块级容器背景色:code block / blockquote 等用 surfaceContainerHigh / surfaceContainerLow。 */
@Composable
@ReadOnlyComposable
internal fun codeBlockBackground(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/** 代码块头部栏底色:比代码区更深一档,形成"语言标签+复制"独立深色条。 */
@Composable
@ReadOnlyComposable
internal fun codeBlockHeaderBackground(): Color =
    MaterialTheme.colorScheme.surfaceContainerHighest

@Composable
@ReadOnlyComposable
internal fun blockQuoteBackground(): Color = MaterialTheme.colorScheme.surfaceContainer

@Composable
@ReadOnlyComposable
internal fun blockQuoteIndicatorColor(): Color = MaterialTheme.colorScheme.primary

@Composable
@ReadOnlyComposable
internal fun tableBorderColor(): Color =
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

/** 表头行底色:与数据区区分(M3 container 系列本身就是柔和背景,不加 alpha)。 */
@Composable
@ReadOnlyComposable
internal fun tableHeaderBackground(): Color =
    MaterialTheme.colorScheme.surfaceContainer

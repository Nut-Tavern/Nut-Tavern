package com.nuttavern.ui.chat.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * 聊天专用 markdown 渲染入口。
 *
 * 解析层用 JetBrains 官方 [MarkdownParser] + [GFMFlavourDescriptor],
 * 渲染层走 [RenderMarkdownBlock] 直接 AST → Compose,跳过 HTML 中间层。
 *
 * 流式策略:
 * - 首屏同步解析,避免出现 loading 空白。
 * - 后续 content 变化通过 [snapshotFlow] + [Dispatchers.Default] 异步重解析,
 *   `mapLatest` 取消上一轮任务,UI 始终拿最新 ast 渲染上一帧,从不进入 loading 态。
 * - `(rawText, ast)` 在同一闭包内成对赋值,避免节点 offset 与字符串错位。
 *
 * 不支持(本轮):图片只画占位、无代码语法高亮、无 LaTeX / Mermaid / Citation,
 * 详见同包内 [MarkdownBlock] 与 [MarkdownInline]。
 */
@Composable
internal fun ChatMarkdown(
    content: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val parser = remember { MarkdownParser(GFMFlavourDescriptor()) }

    var snapshot by remember(parser) {
        mutableStateOf(MarkdownSnapshot(content, parser.buildMarkdownTreeFromString(content)))
    }

    val updatedContent by rememberUpdatedState(content)
    LaunchedEffect(parser) {
        @Suppress("OPT_IN_USAGE")
        snapshotFlow { updatedContent }
            .distinctUntilChanged()
            .drop(1) // 首屏已同步解析过,跳过 snapshotFlow 首发避免重复 parse
            .mapLatest { latest ->
                withContext(Dispatchers.Default) {
                    MarkdownSnapshot(latest, parser.buildMarkdownTreeFromString(latest))
                }
            }
            .collect { next -> snapshot = next }
    }

    val (rawText, ast) = snapshot
    val children = ast.children
    if (children.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MarkdownTokens.BlockSpacing),
    ) {
        children.forEach { node ->
            RenderMarkdownBlock(
                node = node,
                rawText = rawText,
                color = color,
            )
        }
    }
}

private data class MarkdownSnapshot(
    val rawText: String,
    val ast: ASTNode,
)

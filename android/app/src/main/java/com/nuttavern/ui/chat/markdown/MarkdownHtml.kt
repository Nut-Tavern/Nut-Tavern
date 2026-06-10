package com.nuttavern.ui.chat.markdown

/**
 * Markdown 里的 HTML 片段处理。
 *
 * 本仓库 markdown 渲染**不支持完整 HTML**(无 WebView、不做 DOM)。但聊天 / 角色卡内容里常见三类
 * HTML 片段需要正确对待,而不是当代码块原样吐出:
 *
 * 1. HTML 注释 `<!-- ... -->`:酒馆角色卡 / 世界书大量用注释做隐藏批注 → **隐藏**,不渲染。
 * 2. 换行标签 `<br>` / `<br/>` / `<br />`:模型常用来排版 → 转成换行。
 * 3. HTML 实体 `&amp;` `&lt;` `&#39;` `&nbsp;` 等:出现在普通文本里 → 解码成对应字符。
 *
 * 其余成对标签(`<i>` `<b>` `<div>` 等)按"剥离标签、保留文字"处理:标签本身不显示,被包裹的文字
 * 照常渲染。不实现样式语义(加粗 / 斜体请用 markdown 语法),只保证不把标签字面量塞给用户看。
 */
internal object MarkdownHtml {

    fun isComment(tag: String): Boolean {
        val trimmed = tag.trim()
        return trimmed.startsWith("<!--") && trimmed.endsWith("-->")
    }

    fun isLineBreak(tag: String): Boolean {
        val normalized = tag.trim().lowercase().replace(" ", "")
        return normalized == "<br>" || normalized == "<br/>"
    }

    /**
     * 解码常见 HTML 实体。只覆盖聊天里真实高频的具名实体 + 数字实体(十进制 / 十六进制),
     * 不做完整 HTML5 实体表(那是 WebView 的活)。无法识别的实体保持原样,不抛错。
     */
    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        val regex = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]+);")
        return regex.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) ->
                    body.substring(2).toIntOrNull(16)?.let { codePointToString(it) } ?: match.value
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let { codePointToString(it) } ?: match.value
                else -> NAMED_ENTITIES[body] ?: match.value
            }
        }
    }

    /**
     * 把块级 HTML 内容剥成可读纯文本:去掉注释、去掉标签、`<br>` 转换行、解码实体。
     * 全是注释 / 标签时返回空串,调用方据此跳过渲染(不产出空块)。
     */
    fun stripBlockHtml(raw: String): String {
        val withoutComments = COMMENT_REGEX.replace(raw, "")
        val withBreaks = BR_REGEX.replace(withoutComments, "\n")
        val withoutTags = TAG_REGEX.replace(withBreaks, "")
        return decodeEntities(withoutTags)
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
    }

    private fun codePointToString(codePoint: Int): String? {
        if (codePoint < 0 || codePoint > 0x10FFFF) return null
        return runCatching { String(Character.toChars(codePoint)) }.getOrNull()
    }

    private val COMMENT_REGEX = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val TAG_REGEX = Regex("</?[a-zA-Z][^>]*>")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
        "copy" to "\u00A9",
        "reg" to "\u00AE",
        "hellip" to "\u2026",
        "mdash" to "\u2014",
        "ndash" to "\u2013",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
        "trade" to "\u2122",
    )
}

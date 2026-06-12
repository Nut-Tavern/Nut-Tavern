package com.nuttavern.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [GeneratedContentSanitizer] 单测。
 *
 * 重点锁住"流式 token trim 策略"这条跨层不变量(ChatViewModel / ChatApiClient / Sanitizer
 * 三层互相绑死,任一层把双边 trim 改回去都会让流式 markdown 渲染瞬间错乱):
 *
 *   sanitizeGeneratedDisplayText 必须**只去末尾空白**,**前导空白与前导换行原样保留**。
 *
 * 历史踩坑:某次有人把 trimEnd() 改成 trim(),代码块 / 列表 / 标题在流式过程中视觉错乱
 * (markdown 段落分隔信号是前导 `\n\n`,被吞掉后块级结构层全错位)。本套测试就是防止
 * 这种回归再次发生。
 */
class GeneratedContentSanitizerTest {

    // ─── sanitizeProviderTextField ──────────────────────────────────────────

    @Test
    fun providerTextField_treatsLiteralNullAsEmpty() {
        // 部分后端把缺省值序列化为字符串 "null" / "NULL" 直接吐出来,不能让它被 UI 显示。
        assertEquals("", GeneratedContentSanitizer.sanitizeProviderTextField("null"))
        assertEquals("", GeneratedContentSanitizer.sanitizeProviderTextField("NULL"))
        assertEquals("", GeneratedContentSanitizer.sanitizeProviderTextField("  null  "))
    }

    @Test
    fun providerTextField_keepsRegularTextAndEmpty() {
        assertEquals("hello", GeneratedContentSanitizer.sanitizeProviderTextField("hello"))
        assertEquals("", GeneratedContentSanitizer.sanitizeProviderTextField(""))
        // 含 "null" 但不是字面 null 的文本必须原样保留,不能误清空。
        assertEquals("nullable field", GeneratedContentSanitizer.sanitizeProviderTextField("nullable field"))
    }

    // ─── sanitizeGeneratedDisplayText:前导空白保留这条不变量 ────────────────

    @Test
    fun generatedDisplayText_preservesLeadingSpaces() {
        // **关键不变量**:前导空格不能被吞,否则缩进型代码块在流式过程中会错位。
        assertEquals("  hello", GeneratedContentSanitizer.sanitizeGeneratedDisplayText("  hello"))
    }

    @Test
    fun generatedDisplayText_preservesLeadingNewlines() {
        // **关键不变量**:前导 `\n` / `\n\n` 是 markdown 段落分隔信号,吞掉会让代码块、
        // 列表、标题在流式过程中视觉错乱(块级结构层错位)。
        assertEquals("\n\nhello", GeneratedContentSanitizer.sanitizeGeneratedDisplayText("\n\nhello"))
        assertEquals("\nhello", GeneratedContentSanitizer.sanitizeGeneratedDisplayText("\nhello"))
    }

    @Test
    fun generatedDisplayText_trimsTrailingWhitespace() {
        // 末尾空白可以去:模型流式吐 token 时常带尾随 ` ` / `\n` / `\n\n`,渲染层去掉
        // 是为了避免每帧都让光标后面挂一坨空白闪烁。
        assertEquals("hello", GeneratedContentSanitizer.sanitizeGeneratedDisplayText("hello   "))
        assertEquals("hello", GeneratedContentSanitizer.sanitizeGeneratedDisplayText("hello\n\n"))
        assertEquals("hello", GeneratedContentSanitizer.sanitizeGeneratedDisplayText("hello \n \t"))
    }

    @Test
    fun generatedDisplayText_preservesLeadingButTrimsTrailingTogether() {
        // 综合场景:前导保留 + 末尾去掉,不能因为有前导就跳过末尾 trim。
        assertEquals("\n\nhello", GeneratedContentSanitizer.sanitizeGeneratedDisplayText("\n\nhello\n\n"))
        assertEquals("  hello", GeneratedContentSanitizer.sanitizeGeneratedDisplayText("  hello   "))
    }

    @Test
    fun generatedDisplayText_treatsLiteralNullAsEmpty() {
        // 字面 null 的处理应该和 sanitizeProviderTextField 对齐,生成端也可能吐 "null"。
        // 三种形态(精确小写 / 大小写不敏感 / 带 padding)都要清空,锁住 isMeaninglessNullText 复用。
        assertEquals("", GeneratedContentSanitizer.sanitizeGeneratedDisplayText("null"))
        assertEquals("", GeneratedContentSanitizer.sanitizeGeneratedDisplayText("NULL"))
        assertEquals("", GeneratedContentSanitizer.sanitizeGeneratedDisplayText("  NULL  "))
    }

    // ─── splitReasoningFromAnswer ───────────────────────────────────────────

    @Test
    fun splitReasoning_returnsOriginalContentWhenNoThinkTag() {
        val result = GeneratedContentSanitizer.splitReasoningFromAnswer("hello world")
        assertEquals("hello world", result.answerContent)
        assertEquals("", result.reasoningContent)
    }

    @Test
    fun splitReasoning_returnsEmptySplitForBlankInput() {
        // 空白输入直接返回双空,不要让正则跑空匹配。
        val emptyResult = GeneratedContentSanitizer.splitReasoningFromAnswer("")
        assertEquals("", emptyResult.answerContent)
        assertEquals("", emptyResult.reasoningContent)

        val blankResult = GeneratedContentSanitizer.splitReasoningFromAnswer("   \n  ")
        assertEquals("", blankResult.answerContent)
        assertEquals("", blankResult.reasoningContent)
    }

    @Test
    fun splitReasoning_extractsSingleThinkBlock() {
        val result = GeneratedContentSanitizer.splitReasoningFromAnswer(
            "<think>thinking step</think>final answer",
        )
        assertEquals("final answer", result.answerContent)
        assertEquals("thinking step", result.reasoningContent)
    }

    @Test
    fun splitReasoning_joinsMultipleThinkBlocksWithDoubleNewline() {
        // 多个 think 块用 `\n\n` 拼接 reasoning,answer 段去掉所有 think 块。
        val result = GeneratedContentSanitizer.splitReasoningFromAnswer(
            "<think>step a</think>part1<think>step b</think>part2",
        )
        assertEquals("part1part2", result.answerContent)
        assertEquals("step a\n\nstep b", result.reasoningContent)
    }

    @Test
    fun splitReasoning_handlesUnclosedThinkBlockForStreamingMidState() {
        // 流式中间帧:`<think>` 已开但 `</think>` 还没到。正则 `(?:</think>|$)` 兜底
        // 让中间态也能拆出 reasoning,answer 此时为空(还没流到答案段)。
        val result = GeneratedContentSanitizer.splitReasoningFromAnswer("<think>still thinking")
        assertEquals("", result.answerContent)
        assertEquals("still thinking", result.reasoningContent)
    }

    @Test
    fun splitReasoning_isCaseInsensitiveForThinkTag() {
        // 正则带 IGNORE_CASE,大小写混写也要能切。
        val result = GeneratedContentSanitizer.splitReasoningFromAnswer(
            "<THINK>upper case</THINK>answer",
        )
        assertEquals("answer", result.answerContent)
        assertEquals("upper case", result.reasoningContent)
    }

    @Test
    fun splitReasoning_supportsMultilineThinkBlock() {
        // 锁住正则 `[\s\S]*?` 的跨行能力。如果有人把 `[\s\S]` 改成 `.`(默认不跨行),
        // 单块多行 reasoning 就会捕获失败,这是隐藏回归点。
        val result = GeneratedContentSanitizer.splitReasoningFromAnswer(
            "<think>line1\nline2\nline3</think>answer",
        )
        assertEquals("answer", result.answerContent)
        assertEquals("line1\nline2\nline3", result.reasoningContent)
    }

    @Test
    fun splitReasoning_stripsOrphanClosingTagWhenAlsoMatchingFullBlock() {
        // 流式跨帧拼接边界场景:某帧只收到 `</think>` 残留(前面 `<think>` 在更早帧已被
        // 完整 think 块吃掉)。函数内 `replace(closingThinkTagRegex, "")` 这条 line 35
        // 兜底就是为这种残留服务。完整 think 块走 reasoningMatches 路径时,answer 段必须
        // 把残留 `</think>` 一起清掉,否则会污染答案显示。
        //
        // 注意:本测试触发的是"已有完整 think 块匹配 + 答案段还混了一个孤立 </think>"
        // 的场景。被测代码在 `reasoningMatches.isEmpty()` 时早返回,不走 closingThinkTag
        // 清洗,所以单纯 `"</think>answer"` 输入会保留 `</think>` 残留——那是有意的早返回
        // 行为,不在本测试断言范围内。
        val result = GeneratedContentSanitizer.splitReasoningFromAnswer(
            "<think>r</think>part1</think>part2",
        )
        assertEquals("part1part2", result.answerContent)
        assertEquals("r", result.reasoningContent)
    }
}

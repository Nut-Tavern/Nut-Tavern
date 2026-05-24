package com.nuttavern.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedContentSanitizerTest {
    @Test
    fun sanitizeProviderTextFieldDropsOnlyWholeNullValue() {
        assertEquals("", GeneratedContentSanitizer.sanitizeProviderTextField("null"))
        assertEquals("", GeneratedContentSanitizer.sanitizeProviderTextField("  NULL  "))
        assertEquals("value\nnull\nend", GeneratedContentSanitizer.sanitizeProviderTextField("value\nnull\nend"))
    }

    @Test
    fun sanitizeGeneratedDisplayTextKeepsNullLinesInRealContent() {
        assertEquals("", GeneratedContentSanitizer.sanitizeGeneratedDisplayText("null"))
        // 流式渲染保留前导空白(段落分隔信号),只去尾部空白:
        // 如果双边 trim,模型在段落开头吐出来的 `\n\n` 会被吃掉,markdown 段落就塌成一行。
        assertEquals(" value\nnull\nend", GeneratedContentSanitizer.sanitizeGeneratedDisplayText(" value\nnull\nend "))
    }

    @Test
    fun sanitizeGeneratedDisplayTextDoesNotEatLeadingNewlines() {
        // 真实流式场景:前面几个 chunk 是 ```\n```\n,trimEnd 不能吃掉这种段落分隔。
        assertEquals("\n\nHello", GeneratedContentSanitizer.sanitizeGeneratedDisplayText("\n\nHello\n\n"))
    }

    @Test
    fun splitReasoningFromAnswerExtractsClosedThinkBlock() {
        val split = GeneratedContentSanitizer.splitReasoningFromAnswer(
            "<think>check facts</think>\nFinal answer",
        )

        assertEquals("\nFinal answer", split.answerContent)
        assertEquals("check facts", split.reasoningContent)
    }

    @Test
    fun splitReasoningFromAnswerExtractsUnclosedThinkBlock() {
        val split = GeneratedContentSanitizer.splitReasoningFromAnswer(
            "Before\n<think>still thinking",
        )

        assertEquals("Before\n", split.answerContent)
        assertEquals("still thinking", split.reasoningContent)
    }

    @Test
    fun splitReasoningFromAnswerKeepsPlainContentWithNullLine() {
        val split = GeneratedContentSanitizer.splitReasoningFromAnswer(
            "```json\n{\"value\": null}\n```\nnull\nDone",
        )

        assertEquals("```json\n{\"value\": null}\n```\nnull\nDone", split.answerContent)
        assertEquals("", split.reasoningContent)
    }
}

package com.nuttavern.network

import com.nuttavern.data.model.EffortTier
import com.nuttavern.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考量三家映射的逐档对照。映射规则按官方文档(2026-05)固化,新增 Provider 或调整
 * 档位时这组用例先红,避免静默改写请求字段。
 */
class ThinkingLevelMappingTest {

    @Test
    fun openAI_offAndAuto() {
        assertEquals("none", ThinkingLevelMapping.toOpenAIEffort(ThinkingLevel.Off))
        assertNull(ThinkingLevelMapping.toOpenAIEffort(ThinkingLevel.Auto))
    }

    @Test
    fun openAI_effortTiersMapToOfficialValues() {
        assertEquals("minimal", ThinkingLevelMapping.toOpenAIEffort(ThinkingLevel.Effort(EffortTier.MINIMAL)))
        assertEquals("low", ThinkingLevelMapping.toOpenAIEffort(ThinkingLevel.Effort(EffortTier.LOW)))
        assertEquals("medium", ThinkingLevelMapping.toOpenAIEffort(ThinkingLevel.Effort(EffortTier.MEDIUM)))
        assertEquals("high", ThinkingLevelMapping.toOpenAIEffort(ThinkingLevel.Effort(EffortTier.HIGH)))
        assertEquals("xhigh", ThinkingLevelMapping.toOpenAIEffort(ThinkingLevel.Effort(EffortTier.MAX)))
    }

    @Test
    fun openAI_budgetFoldsToNearestTier() {
        assertEquals("minimal", ThinkingLevelMapping.toOpenAIEffort(ThinkingLevel.Budget(512)))
        assertEquals("low", ThinkingLevelMapping.toOpenAIEffort(ThinkingLevel.Budget(4096)))
        assertEquals("medium", ThinkingLevelMapping.toOpenAIEffort(ThinkingLevel.Budget(10000)))
        assertEquals("high", ThinkingLevelMapping.toOpenAIEffort(ThinkingLevel.Budget(24576)))
        assertEquals("xhigh", ThinkingLevelMapping.toOpenAIEffort(ThinkingLevel.Budget(40000)))
    }

    @Test
    fun gemini_offSendsZeroBudget() {
        val config = ThinkingLevelMapping.toGeminiThinkingConfig(ThinkingLevel.Off)
        assertEquals(0, config?.getInt("thinkingBudget"))
    }

    @Test
    fun gemini_autoSendsNothing() {
        assertNull(ThinkingLevelMapping.toGeminiThinkingConfig(ThinkingLevel.Auto))
    }

    @Test
    fun gemini_effortUsesThinkingLevelStringAndCapsAtHigh() {
        assertEquals(
            "minimal",
            ThinkingLevelMapping.toGeminiThinkingConfig(ThinkingLevel.Effort(EffortTier.MINIMAL))?.getString("thinkingLevel"),
        )
        assertEquals(
            "high",
            ThinkingLevelMapping.toGeminiThinkingConfig(ThinkingLevel.Effort(EffortTier.HIGH))?.getString("thinkingLevel"),
        )
        // 极高没有更高档,封顶到 high。
        assertEquals(
            "high",
            ThinkingLevelMapping.toGeminiThinkingConfig(ThinkingLevel.Effort(EffortTier.MAX))?.getString("thinkingLevel"),
        )
    }

    @Test
    fun gemini_budgetUsesIntegerBudget() {
        val config = ThinkingLevelMapping.toGeminiThinkingConfig(ThinkingLevel.Budget(2048))
        assertEquals(2048, config?.getInt("thinkingBudget"))
    }

    @Test
    fun claude_offAndAutoSendNothing() {
        assertNull(ThinkingLevelMapping.toClaudeThinking(ThinkingLevel.Off, 4096))
        assertNull(ThinkingLevelMapping.toClaudeThinking(ThinkingLevel.Auto, 4096))
    }

    @Test
    fun claude_effortMapsToBudgetClampedUnderMaxTokens() {
        val high = ThinkingLevelMapping.toClaudeThinking(ThinkingLevel.Effort(EffortTier.HIGH), 8192)
        assertEquals("enabled", high?.getString("type"))
        assertEquals(8191, high?.getInt("budget_tokens")) // 16384 钳到 maxTokens-1

        val medium = ThinkingLevelMapping.toClaudeThinking(ThinkingLevel.Effort(EffortTier.MEDIUM), 64000)
        assertEquals(8192, medium?.getInt("budget_tokens"))
    }

    @Test
    fun claude_budgetRespectsMinAndMax() {
        // 低于下限钳到 1024。
        val tooLow = ThinkingLevelMapping.toClaudeThinking(ThinkingLevel.Budget(100), 8192)
        assertEquals(ThinkingLevelMapping.CLAUDE_MIN_BUDGET, tooLow?.getInt("budget_tokens"))

        // 正常值原样保留。
        val normal = ThinkingLevelMapping.toClaudeThinking(ThinkingLevel.Budget(5000), 8192)
        assertEquals(5000, normal?.getInt("budget_tokens"))

        // budget 必须严格小于 max_tokens。
        val capped = ThinkingLevelMapping.toClaudeThinking(ThinkingLevel.Budget(10000), 4096)
        assertTrue((capped?.getInt("budget_tokens") ?: 0) < 4096)
    }

    @Test
    fun claude_nullMaxTokensUsesFallback() {
        val thinking = ThinkingLevelMapping.toClaudeThinking(ThinkingLevel.Budget(10000), null)
        // fallback 4096,budget 钳到 4095。
        assertEquals(4095, thinking?.getInt("budget_tokens"))
    }
}

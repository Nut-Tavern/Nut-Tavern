package com.nuttavern.network

import com.nuttavern.data.model.EffortTier
import com.nuttavern.data.model.ThinkingLevel
import org.json.JSONObject

/**
 * 把会话级 [ThinkingLevel] 映射成三家后端各自的 reasoning 请求字段。
 *
 * 这里只做"档位 → 协议字段"的纯转换,不读 [Model.abilities]:是否允许发送思考字段由
 * [com.nuttavern.network.ChatApiClient] 的 REASONING 门控决定,门控不通过时根本不会调到这里。
 *
 * 设计取舍:抽出独立文件而不是散在四个 build 函数里,是因为映射规则三家各异、还要单测逐档
 * 对照官方文档,集中一处便于维护和测试。
 *
 * # 各家关键约束(来源已核实)
 *
 * - **OpenAI** `reasoning_effort`(Chat)/ `reasoning.effort`(Responses):支持值
 *   `none / minimal / low / medium / high / xhigh`(模型相关,后端忽略不支持的值)。
 *   来源:已核对官方 "Reasoning models" 文档——原文 "Supported values are model-dependent
 *   and can include none, minimal, low, medium, high, and xhigh";Responses 用
 *   `reasoning:{effort}` 嵌套对象,Chat 用顶层 `reasoning_effort`。
 * - **Gemini** `thinkingConfig`(已核对官方 generateContent / Gemini 思考文档):
 *   - Gemini 3+ 用 `thinkingLevel`,四档 `minimal/low/medium/high` 全部受支持,无更高档;
 *   - 2.5 系列用 `thinkingBudget` 整数,`0`=关闭(仅 Flash / Flash-Lite 支持关闭,
 *     **2.5 Pro 不可关**,最小 128,发 0 会被拒)、`-1`=动态;
 *   - `thinkingLevel` 与 `thinkingBudget` 不可同时下发。
 *   这里 Effort 档发 `thinkingLevel` 字符串,关闭 / 自定义发 `thinkingBudget` 整数。
 * - **Claude** `thinking`(已核对官方 extended thinking 文档):
 *   手动 `{type:"enabled",budget_tokens:N}`,N 最小 **1024** 且必须 **< max_tokens**;
 *   关闭不发(`type:"disabled"` 在 Mythos 等模型不支持,不发最稳);自动不发。
 *   注意:Opus 4.8 / 4.7 不再接受手动 budget(返回 400),需 `type:"adaptive"`+effort,
 *   本期不适配 adaptive,对这两款模型手动 budget 会失败(见 docs 待办)。
 */
object ThinkingLevelMapping {

    /** Claude budget_tokens 最小值(官方硬下限)。 */
    const val CLAUDE_MIN_BUDGET = 1_024

    /** Claude 没有 max_tokens 时的兜底上限,与请求体 max_tokens fallback 保持一致。 */
    private const val CLAUDE_MAX_TOKENS_FALLBACK = 4_096

    /** Claude 各 Effort 档的 budget token 预算。 */
    private val CLAUDE_EFFORT_BUDGET = mapOf(
        EffortTier.MINIMAL to 1_024,
        EffortTier.LOW to 2_048,
        EffortTier.MEDIUM to 8_192,
        EffortTier.HIGH to 16_384,
        EffortTier.MAX to 32_000,
    )

    /**
     * OpenAI `reasoning_effort` / `reasoning.effort` 取值。null 表示不发送该字段。
     *
     * 自定义 token 按预算折算到最接近的官方档位(OpenAI 不接受裸 token 数)。
     */
    fun toOpenAIEffort(level: ThinkingLevel): String? = when (level) {
        ThinkingLevel.Off -> "none"
        ThinkingLevel.Auto -> null
        is ThinkingLevel.Effort -> when (level.tier) {
            EffortTier.MINIMAL -> "minimal"
            EffortTier.LOW -> "low"
            EffortTier.MEDIUM -> "medium"
            EffortTier.HIGH -> "high"
            EffortTier.MAX -> "xhigh"
        }
        is ThinkingLevel.Budget -> openAIEffortForBudget(level.tokens)
    }

    private fun openAIEffortForBudget(tokens: Int): String = when {
        tokens <= 1_024 -> "minimal"
        tokens <= 4_096 -> "low"
        tokens <= 12_288 -> "medium"
        tokens <= 24_576 -> "high"
        else -> "xhigh"
    }

    /**
     * Gemini `thinkingConfig` 对象。null 表示不发送该字段(= 自动)。
     *
     * - Off → `{thinkingBudget:0}`(2.5 系列停用思考;Gemini 3 不可关时由后端忽略)。
     * - Effort → `{thinkingLevel:"..."}`(Gemini 无更高档,MAX 封顶到 high)。
     * - Budget → `{thinkingBudget:N}`。
     */
    fun toGeminiThinkingConfig(level: ThinkingLevel): JSONObject? = when (level) {
        ThinkingLevel.Off -> JSONObject().put("thinkingBudget", 0)
        ThinkingLevel.Auto -> null
        is ThinkingLevel.Effort -> JSONObject().put("thinkingLevel", geminiThinkingLevel(level.tier))
        is ThinkingLevel.Budget -> JSONObject().put("thinkingBudget", level.tokens)
    }

    private fun geminiThinkingLevel(tier: EffortTier): String = when (tier) {
        EffortTier.MINIMAL -> "minimal"
        EffortTier.LOW -> "low"
        EffortTier.MEDIUM -> "medium"
        // Gemini 没有比 high 更高的档位,极高封顶到 high。
        EffortTier.HIGH, EffortTier.MAX -> "high"
    }

    /**
     * Claude `thinking` 对象。null 表示不发送该字段(= 关闭 / 自动)。
     *
     * [maxTokens] 来自请求体的 max_tokens(Claude 必填);budget_tokens 钳到
     * `[CLAUDE_MIN_BUDGET, maxTokens - 1]`,保证既满足官方下限又严格小于 max_tokens。
     */
    fun toClaudeThinking(level: ThinkingLevel, maxTokens: Int?): JSONObject? = when (level) {
        ThinkingLevel.Off, ThinkingLevel.Auto -> null
        is ThinkingLevel.Effort -> claudeThinking(CLAUDE_EFFORT_BUDGET.getValue(level.tier), maxTokens)
        is ThinkingLevel.Budget -> claudeThinking(level.tokens, maxTokens)
    }

    private fun claudeThinking(requestedBudget: Int, maxTokens: Int?): JSONObject {
        val upperExclusive = (maxTokens ?: CLAUDE_MAX_TOKENS_FALLBACK) - 1
        // max_tokens 可能小到放不下下限预算;此时取下限,让 Anthropic 自己拒并给出明确错误,
        // 而不是悄悄塞一个非法值。upperBound 至少为下限,避免 coerceIn 的 min>max 崩溃。
        val upperBound = maxOf(CLAUDE_MIN_BUDGET, upperExclusive)
        val budget = requestedBudget.coerceIn(CLAUDE_MIN_BUDGET, upperBound)
        return JSONObject()
            .put("type", "enabled")
            .put("budget_tokens", budget)
    }
}

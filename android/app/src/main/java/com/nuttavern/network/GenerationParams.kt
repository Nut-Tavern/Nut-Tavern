package com.nuttavern.network

/**
 * 一次发送请求的"生成参数"。
 *
 * 预设里所有与 chat completion API 直接挂钩的字段(temperature / max_tokens / top_p / 各类
 * penalty / seed / n / streamEnabled 等)走这里透传;[com.nuttavern.network.ChatApiClient.streamChat]
 * 拿到后按 Provider 协议选择能注入的字段写到请求体里(OpenAI 全字段,Claude / Gemini 子集)。
 *
 * 这里**不**承载:
 * - 系统消息文本、消息历史:走 [ChatMessage] / `systemPrompt` 单独参数;
 * - 模型 id / endpoint / API key:走 [com.nuttavern.data.model.Model] / [com.nuttavern.data.model.Provider];
 * - 角色名拼接策略([com.nuttavern.data.preset.NamesBehavior]):由 PromptComposer 在
 *   `messages` 拼接阶段消费,不到达这一层。
 *
 * # 字段来源(剥离后)
 *
 * - [temperature] / [maxTokens] / [topP] / [topK] / [topA] / [minP] / [frequencyPenalty] /
 *   [presencePenalty] / [repetitionPenalty] / [seed] / [n] / [streamEnabled] / [logitBias]
 *   → 来自 [com.nuttavern.data.preset.Preset];
 * - [verbosityRawValue] → 来自 [com.nuttavern.data.character.Character.verbosity],按角色绑定;
 * - [customPostProcessing] → 来自当前 [com.nuttavern.data.model.Provider] 的
 *   `customPromptPostProcessing` 字段;
 * - [thinkingLevelOverride] → 来自会话级 [ChatViewModel.currentThinkingLevel],对应 reasoning
 *   effort(我们用 ThinkingLevel 表达,内部映射到各 Provider,见 [ThinkingLevelMapping])。
 *
 * # 字段选择
 *
 * 所有字段都用 nullable / 默认值,允许"什么都不指定"。`null` / 空字符串表示不写到请求体里,
 * 让 Provider 用它自己的默认值。这与酒馆"未启用某参数 → 不发送"的行为一致。
 */
data class GenerationParams(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val topA: Double? = null,
    val minP: Double? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val repetitionPenalty: Double? = null,
    val seed: Int? = null,
    val n: Int? = null,
    val streamEnabled: Boolean = true,
    /**
     * 回复长度档位。空字符串 = 不发送字段。来源:[com.nuttavern.data.character.Character.verbosity]。
     * 取值参考酒馆 verbosity_levels(low / medium / high),允许任意自定义字符串透传给后端。
     */
    val verbosityRawValue: String = "",
    val logitBias: Map<String, Int> = emptyMap(),
    val stop: List<String> = emptyList(),
    /**
     * 自定义 OpenAI 中转站的请求消息后处理策略。空 = 不改写。来源:当前
     * [com.nuttavern.data.model.Provider.customPromptPostProcessing] 字段。
     */
    val customPostProcessing: String? = null,
    val thinkingLevelOverride: com.nuttavern.data.model.ThinkingLevel? = null,
) {
    companion object {
        /** 不指定任何参数,所有字段走 Provider 默认。用于"无预设"或测试场景。 */
        val Empty: GenerationParams = GenerationParams()
    }
}

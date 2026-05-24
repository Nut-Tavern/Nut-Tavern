package com.nuttavern.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Provider 级别的请求消息后处理策略。对齐酒馆 `custom_prompt_post_processing_types`。
 *
 * 把这个字段挂在 Provider(而不是 Preset)上,是因为它**与连接绑定**:不同的中转站对消息
 * 格式有不同的硬性要求(有的要求严格的 system → user → assistant 交替、有的要求合并 system、
 * 有的接 Claude 风格 prefill),用户在哪个 Provider 配什么后处理是固定的;而预设是"提示词
 * 怎么写、参数怎么调"的层面,与连接无关。
 *
 * 当前所有枚举值都先承担"标记 / 透传"语义 — Provider 实现读到非 [NONE] 时按对应策略改写
 * 请求消息。Nut Tavern 自己的后端按 Provider 路由,具体策略实现随 OpenAICustom Provider 上线
 * 时落地;在此之前默认 [NONE] 就够。
 */
@Serializable
enum class CustomPromptPostProcessing(val value: String) {
    @SerialName("") NONE(""),
    @SerialName("claude") CLAUDE("claude"),
    @SerialName("merge") MERGE("merge"),
    @SerialName("merge_tools") MERGE_TOOLS("merge_tools"),
    @SerialName("semi") SEMI("semi"),
    @SerialName("semi_tools") SEMI_TOOLS("semi_tools"),
    @SerialName("strict") STRICT("strict"),
    @SerialName("strict_tools") STRICT_TOOLS("strict_tools"),
    @SerialName("single") SINGLE("single"),
}

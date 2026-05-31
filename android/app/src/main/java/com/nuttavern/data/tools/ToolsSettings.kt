package com.nuttavern.data.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 全局工具与 tool 调用配置。
 *
 * 这里只承载"工具调用引擎自身的高级行为"。**单个工具的开关 / MCP 服务器列表**
 * 各自落在独立模块(内置工具仓库、MCP 仓库),不进这个数据袋,避免设置面板
 * 越来越大、字段语义越来越糊。
 *
 * # 字段来源
 *
 * 这两个字段**对应酒馆 default_settings**:
 * - [toolCallRecurseLimit] ↔ `oai_settings.tool_call_recurse_limit`
 * - [toolReasoningMode] ↔ `oai_settings.tool_reasoning_mode`
 *
 * 酒馆把它们放在"AI Response Configuration"全局面板,跨预设通用,Default.json 不写。
 * Nut Tavern 同语义但更明确分组在"工具 → 高级"下。
 *
 * # 不进这里的字段
 *
 * - `function_calling`(工具总开关)→ MCP 走"单工具开关"模型,不需要总开关。
 * - `enable_web_search` → Provider 模块按 Provider 提供能力(Gemini 已做)。
 * - `show_thoughts` → 客户端 UI 行为,默认永远展示,不暴露开关。
 * - `reasoning_effort` → 走 ChatViewModel.currentThinkingLevel 会话级控制。
 * - `verbosity` → 移入 [com.nuttavern.data.character.Character.verbosity],绑角色。
 * - `request_images` / `request_image_aspect_ratio` / `request_image_resolution` →
 *   Provider 模块图片生成功能上线时落地。
 */
@Serializable
data class ToolsSettings(
    /**
     * 模型一轮回复内最多连续触发工具调用的次数(防止死循环)。对齐酒馆默认 5。
     *
     * 含义:同一次用户消息 → 模型回复期间,工具被调起的总次数上限。达到后强制结束本轮,
     * 不再继续触发新的 tool 调用。
     */
    @SerialName("tool_call_recurse_limit") val toolCallRecurseLimit: Int = 5,

    /**
     * 多步工具调用时,如何把上一轮的 reasoning 转发给下一轮。对齐酒馆 tool_reasoning_modes。
     *
     * - [ToolReasoningMode.DISABLED]:不带 reasoning(默认,最省 token);
     * - [ToolReasoningMode.SINCE_LAST_USER]:只带最近一条用户消息之后的 reasoning;
     * - [ToolReasoningMode.ACTIVE_CHAIN]:带整个工具调用链的 reasoning(最贵)。
     */
    @SerialName("tool_reasoning_mode") val toolReasoningMode: ToolReasoningMode =
        ToolReasoningMode.DISABLED,
) {
    companion object {
        /** [toolCallRecurseLimit] 合理上限。超过这个值通常意味着用户配置错了或工具陷入死循环。 */
        const val MAX_RECURSE_LIMIT = 50

        /** [toolCallRecurseLimit] 合理下限。0 等于禁用工具调用,语义混淆,改用 MCP 单工具开关。 */
        const val MIN_RECURSE_LIMIT = 1
    }
}

/** 工具调用链中的 reasoning 转发模式。对齐酒馆 tool_reasoning_modes。 */
@Serializable
enum class ToolReasoningMode {
    @SerialName("disabled") DISABLED,
    @SerialName("since_last_user") SINCE_LAST_USER,
    @SerialName("active_chain") ACTIVE_CHAIN,
}

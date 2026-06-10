package com.nuttavern.network

/**
 * Represents a single chunk of streamed chat response.
 */
data class ChatStreamChunk(
    val content: String,
    val reasoningContent: String = "",
    val isDone: Boolean = false,
    val error: String? = null,
    /**
     * 本轮触发的内置工具调用名(本地 function calling)。非空表示模型调用了某个工具。
     * 在工具**开始执行前**发出,仅用于流式过程中的"正在调用 xxx"状态提示。
     */
    val toolActivity: String? = null,
    /**
     * 工具调用的完整记录,在工具**执行完成后**发出。
     * 上层据此把工具调用作为 ToolCall part 落进消息(名称 / 入参 / 结果 / 是否被拒)。
     * 与 [toolActivity] 区分:后者是执行前的瞬时提示,前者是执行后的可落库结果。
     */
    val toolCall: ToolCallRecord? = null,
)

/**
 * 一次本地工具调用的可落库记录。流式过程中工具执行完成后透传给上层,
 * 由 ViewModel 转成 [com.nuttavern.data.model.MessagePart.ToolCall] 落库与渲染。
 *
 * @property denied 是否被用户拒绝(需确认的工具)。被拒时 [result] 为拒绝说明。
 */
data class ToolCallRecord(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String,
    val denied: Boolean = false,
)


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
     * 仅用于流式过程中的状态提示,不写入最终落库的消息内容。
     */
    val toolActivity: String? = null,
)

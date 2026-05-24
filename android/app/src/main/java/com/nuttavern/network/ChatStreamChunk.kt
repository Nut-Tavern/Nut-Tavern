package com.nuttavern.network

/**
 * Represents a single chunk of streamed chat response.
 */
data class ChatStreamChunk(
    val content: String,
    val reasoningContent: String = "",
    val isDone: Boolean = false,
    val error: String? = null,
)

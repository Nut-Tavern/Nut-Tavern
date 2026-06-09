package com.nuttavern.network

import org.json.JSONArray

data class ChatMessage(
    val role: String,
    val content: String,
    /**
     * 随消息发送的图片(已编码为 base64)。空 = 纯文本消息。
     * 文件读取 + base64 编码在 ViewModel/仓库层完成,网络层只负责按各家格式拼块。
     */
    val images: List<ChatImage> = emptyList(),
    /**
     * 本地 function calling 回灌用字段,仅在工具调用循环内由网络层内部构造,不参与持久化。
     *
     * - [toolCalls]:assistant 轮请求模型调用工具时的原始 tool_calls 数组(OpenAI 格式);
     * - [toolCallId]:tool 角色消息对应的 tool_call_id,把工具结果关联回某次调用。
     */
    val toolCalls: JSONArray? = null,
    val toolCallId: String? = null,
)

/**
 * 一张已编码好的待发图片。[base64Data] 是不含 data URL 前缀的纯 base64,
 * 各家请求构造时按需拼成 data URL 或裸 base64。
 */
data class ChatImage(
    val base64Data: String,
    val mimeType: String,
) {
    /** OpenAI image_url / Gemini 等需要的 `data:{mime};base64,{data}` 形式。 */
    fun toDataUrl(): String = "data:$mimeType;base64,$base64Data"
}

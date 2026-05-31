package com.nuttavern.network

data class ChatMessage(
    val role: String,
    val content: String,
    /**
     * 随消息发送的图片(已编码为 base64)。空 = 纯文本消息。
     * 文件读取 + base64 编码在 ViewModel/仓库层完成,网络层只负责按各家格式拼块。
     */
    val images: List<ChatImage> = emptyList(),
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

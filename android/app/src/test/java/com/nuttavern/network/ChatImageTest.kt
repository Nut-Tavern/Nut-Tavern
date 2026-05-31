package com.nuttavern.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 图片附件编码格式测试。三家请求块的 JSON 拼装在 ChatApiClient 私有 builder 里(随集成路径
 * 验证),这里锁住跨三家共用的 data URL 拼装契约,避免改坏 OpenAI image_url / Gemini 前缀。
 */
class ChatImageTest {

    @Test
    fun toDataUrl_buildsStandardPrefix() {
        val image = ChatImage(base64Data = "QUJD", mimeType = "image/png")
        assertEquals("data:image/png;base64,QUJD", image.toDataUrl())
    }

    @Test
    fun toDataUrl_preservesMimeAndData() {
        val image = ChatImage(base64Data = "Zm9vYmFy", mimeType = "image/jpeg")
        assertEquals("data:image/jpeg;base64,Zm9vYmFy", image.toDataUrl())
    }

    @Test
    fun chatMessage_defaultsToNoImages() {
        val message = ChatMessage(role = "user", content = "hi")
        assertEquals(0, message.images.size)
    }
}

package com.nuttavern.prompt

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Token 计数器。封装 jtokkit 的 cl100k_base 编码，提供统一的 token 计数能力。
 *
 * 当前统一使用 cl100k_base（GPT-4 编码）估算所有模型的 token 数：
 * - OpenAI GPT-4 系列：精确
 * - OpenAI GPT-4o/o1/o3 系列：略有偏差（实际用 o200k_base），误差 ±5%
 * - Claude / DeepSeek：误差 ±5-15%
 * - Gemini：误差 ±10-20%
 *
 * 对世界书预算裁剪和上下文窗口管理来说，这个精度足够。
 *
 * 线程安全，可在任意线程调用。首次调用会加载词表（~4MB 堆内存），
 * 后续调用无额外开销。
 */
@Singleton
class TokenCounter @Inject constructor() {

    private val encoding: Encoding by lazy {
        val registry = Encodings.newDefaultEncodingRegistry()
        registry.getEncoding(EncodingType.CL100K_BASE)
    }

    /** 计算文本的 token 数。空文本返回 0。 */
    fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return encoding.countTokens(text)
    }
}

package com.nuttavern.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Claude 提示缓存 TTL。`FIVE_MINUTES` 在请求里不带 ttl 字段(默认值),`ONE_HOUR` 显式传 "1h"。
 */
@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h"),
}

/**
 * Provider 支持的三种协议。和 [Provider] sealed class 一一对应,但单独抽出来用于
 * UI 上"协议三选"的状态表达,以及 [Provider.withProtocol] 的入参。
 */
enum class ProviderProtocol(val typeKey: String, val displayLabel: String) {
    OPENAI("openai", "OpenAI"),
    GOOGLE("google", "Google"),
    CLAUDE("claude", "Claude"),
}

/**
 * 提供商配置。三种实现对应三种 API 协议,不允许"一个 Provider 同时挂多个协议",
 * 这是与老 ProviderConfig 的关键差异:协议变更 = 重新创建 Provider 或确认替换。
 *
 * 通用约束:
 * - [id] 全局唯一稳定字符串(创建时 UUID);
 * - [apiKey] 写入 DataStore 前会被抹空,真正的 key 走 ApiKeyStore 单独加密存储;
 * - [models] 仅承载这一个 Provider 下的模型,跨 Provider 共享要复制。
 */
@Serializable
sealed class Provider {
    abstract val id: String
    abstract val name: String
    abstract val enabled: Boolean
    abstract val models: List<Model>
    abstract val order: Int
    abstract val apiKey: String
    abstract val baseUrl: String

    /**
     * 用户手动指定的图标 key。空字符串 = "按名称自动推断"(走 ProviderIconBadge 的默认逻辑)。
     * 取值范围由 [com.nuttavern.ui.chat.ProviderIconCatalog] 统一维护,UI 侧只允许用户从
     * 内置库里选,不接受任意字符串。
     */
    abstract val iconKey: String

    /**
     * 请求消息后处理策略。对齐酒馆 `custom_prompt_post_processing`,详见
     * [CustomPromptPostProcessing] KDoc。当前所有 Provider 默认 [CustomPromptPostProcessing.NONE]
     * 不做改写;OpenAI 自定义中转站 Provider 上线时再读这里。
     */
    abstract val customPromptPostProcessing: CustomPromptPostProcessing

    /** 给 ApiKeyStore 做隔离用;不会持久化到 DataStore JSON。 */
    fun typeKey(): String = when (this) {
        is OpenAI -> "openai"
        is Google -> "google"
        is Claude -> "claude"
    }

    abstract fun withApiKey(apiKey: String): Provider
    abstract fun withModels(models: List<Model>): Provider
    abstract fun withEnabled(enabled: Boolean): Provider
    abstract fun withName(name: String): Provider
    abstract fun withOrder(order: Int): Provider
    abstract fun withIconKey(iconKey: String): Provider

    /**
     * 把当前 Provider 切换成另一种协议。保留 [id] / [name] / [enabled] / [order],
     * 重置 [baseUrl] / [models] 与协议特有字段(它们在新协议下没有意义,继续带过去会污染请求)。
     *
     * - [apiKey] 默认保留,用户切回原协议时不至于丢 key。如果上层产品决策要清空,
     *   仓库 `updateProvider` 之前显式调用 `withApiKey("")` 即可。
     * - 切换前 UI 必须先做"已有模型 / 已填字段会被丢弃"二次确认,这里不做。
     */
    fun withProtocol(target: ProviderProtocol): Provider {
        if (typeKey() == target.typeKey) return this
        return when (target) {
            ProviderProtocol.OPENAI -> OpenAI(
                id = id,
                name = name,
                enabled = enabled,
                order = order,
                apiKey = apiKey,
                iconKey = iconKey,
                customPromptPostProcessing = customPromptPostProcessing,
            )
            ProviderProtocol.GOOGLE -> Google(
                id = id,
                name = name,
                enabled = enabled,
                order = order,
                apiKey = apiKey,
                iconKey = iconKey,
                customPromptPostProcessing = customPromptPostProcessing,
            )
            ProviderProtocol.CLAUDE -> Claude(
                id = id,
                name = name,
                enabled = enabled,
                order = order,
                apiKey = apiKey,
                iconKey = iconKey,
                customPromptPostProcessing = customPromptPostProcessing,
            )
        }
    }

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override val id: String,
        override val name: String = "OpenAI",
        override val enabled: Boolean = false,
        override val models: List<Model> = emptyList(),
        override val order: Int = 0,
        override val apiKey: String = "",
        override val baseUrl: String = "https://api.openai.com/v1",
        override val iconKey: String = "",
        override val customPromptPostProcessing: CustomPromptPostProcessing =
            CustomPromptPostProcessing.NONE,
        val chatCompletionsPath: String = "/chat/completions",
        val useResponsesApi: Boolean = false,
    ) : Provider() {
        override fun withApiKey(apiKey: String): Provider = copy(apiKey = apiKey)
        override fun withModels(models: List<Model>): Provider = copy(models = models)
        override fun withEnabled(enabled: Boolean): Provider = copy(enabled = enabled)
        override fun withName(name: String): Provider = copy(name = name)
        override fun withOrder(order: Int): Provider = copy(order = order)
        override fun withIconKey(iconKey: String): Provider = copy(iconKey = iconKey)
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override val id: String,
        override val name: String = "Google",
        override val enabled: Boolean = false,
        override val models: List<Model> = emptyList(),
        override val order: Int = 0,
        override val apiKey: String = "",
        override val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        override val iconKey: String = "",
        override val customPromptPostProcessing: CustomPromptPostProcessing =
            CustomPromptPostProcessing.NONE,
    ) : Provider() {
        override fun withApiKey(apiKey: String): Provider = copy(apiKey = apiKey)
        override fun withModels(models: List<Model>): Provider = copy(models = models)
        override fun withEnabled(enabled: Boolean): Provider = copy(enabled = enabled)
        override fun withName(name: String): Provider = copy(name = name)
        override fun withOrder(order: Int): Provider = copy(order = order)
        override fun withIconKey(iconKey: String): Provider = copy(iconKey = iconKey)
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override val id: String,
        override val name: String = "Claude",
        override val enabled: Boolean = false,
        override val models: List<Model> = emptyList(),
        override val order: Int = 0,
        override val apiKey: String = "",
        override val baseUrl: String = "https://api.anthropic.com/v1",
        override val iconKey: String = "",
        override val customPromptPostProcessing: CustomPromptPostProcessing =
            CustomPromptPostProcessing.NONE,
        val promptCaching: Boolean = false,
        val promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
    ) : Provider() {
        override fun withApiKey(apiKey: String): Provider = copy(apiKey = apiKey)
        override fun withModels(models: List<Model>): Provider = copy(models = models)
        override fun withEnabled(enabled: Boolean): Provider = copy(enabled = enabled)
        override fun withName(name: String): Provider = copy(name = name)
        override fun withOrder(order: Int): Provider = copy(order = order)
        override fun withIconKey(iconKey: String): Provider = copy(iconKey = iconKey)
    }
}

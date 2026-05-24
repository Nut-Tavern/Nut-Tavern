package com.nuttavern.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 模型类型。当前只有聊天模型,枚举保留单值是为了反序列化兼容,以及未来可能再扩展。
 *
 * 历史值 IMAGE / EMBEDDING 已被砍掉(类酒馆产品线不需要)。如果旧数据里出现这两个值,
 * kotlinx.serialization 会抛 SerializationException;我们在仓库读入时统一兜底成 CHAT。
 */
@Serializable
enum class ModelType { CHAT }

/**
 * 输入 / 输出模态。当前只区分文本和图像;音频 / 视频后续轮按需追加。
 */
@Serializable
enum class Modality { TEXT, IMAGE }

/**
 * 模型能力声明。这两个开关直接驱动 ChatApiClient 的请求构造:
 * - [TOOL]:决定是否在请求里附带 `tools` 字段;
 * - [REASONING]:决定是否传思考量参数(OpenAI reasoning_effort / Google thinkingConfig / Claude thinking)。
 *
 * 字段值由用户在编辑模型 UI 显式勾选,默认值通过 [com.nuttavern.data.registry.ModelRegistry] 推断。
 */
@Serializable
enum class ModelAbility { TOOL, REASONING }

/**
 * 模型内置工具(由 Provider API 原生提供,不是 App 实现的)。
 * 当前 UI 只对 Gemini 生效,其他 Provider 字段保留但不发到请求里。
 */
@Serializable
sealed class BuiltInTool {
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTool()

    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTool()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTool()
}

/**
 * 自定义请求 Header。`name` 重复时,后写覆盖先写;空 [name] 整条丢弃。
 */
@Serializable
data class CustomHeader(
    val name: String = "",
    val value: String = "",
)

/**
 * 自定义 Body 字段。[jsonValue] 必须是合法 JSON(数字、字符串字面量、对象、数组);
 * 解析失败时整条丢弃,不影响主请求。
 */
@Serializable
data class CustomBody(
    val key: String = "",
    val jsonValue: String = "",
)

/**
 * 模型配置。隶属于某个 [Provider]。
 *
 * 关键字段:
 * - [modelId] 是真正下发到 API 的模型 id,用户保存后一般不再改;
 * - [displayName] 仅 UI 展示,允许中文 / emoji,空时回退到 [modelId];
 * - [abilities] / [inputModalities] / [outputModalities] 决定运行时请求构造;
 * - [providerOverride] 允许这一条模型走与父 Provider 不同的 baseUrl / key / 路径;
 *   置为 null 时完全继承父 Provider。本轮数据结构到位,UI 编辑入口下一轮再做。
 */
@Serializable
data class Model(
    val id: String,
    val modelId: String,
    val displayName: String = "",
    val type: ModelType = ModelType.CHAT,
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val builtInTools: Set<BuiltInTool> = emptySet(),
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val providerOverride: Provider? = null,
) {
    /** UI 显示用的最终名称,空 displayName 退回 modelId。 */
    val resolvedDisplayName: String
        get() = displayName.ifBlank { modelId }
}

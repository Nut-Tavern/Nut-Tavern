package com.nuttavern.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一条消息的有序内容块。一条 assistant 消息由 [MessagePart] 列表按到达顺序组成,
 * 渲染时正文 / 思考 / 工具调用按列表顺序穿插(顺序即语义,不重排)。
 *
 * # 为什么用 sealed class 多态序列化
 *
 * 一条消息可能是 `[Reasoning, ToolCall, Text, Reasoning, Text]` 这种交替结构,无法用并列字段表达。
 * 整个 `List<MessagePart>` 序列化成一个 JSON 串落 [com.nuttavern.data.local.entity.MessageEntity.partsJson]。
 * 多态判别字段统一用 `type`(见 [com.nuttavern.data.repository.ConversationRepository] 的 partsJsonCodec)。
 *
 * # 不含图片
 *
 * 图片附件走独立的 [Message.attachments] 链路(落 filesDir,不进 DB,不进 parts),
 * 这里只承载文本类内容块,避免把已稳定的多模态图片链路卷进来。
 */
@Serializable
sealed class MessagePart {

    /** 正文文本块。走 markdown 渲染。 */
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : MessagePart()

    /**
     * 思考(reasoning)块。
     *
     * @property durationMillis 思考耗时(毫秒)。沿用并取代旧字段 `reasoningDurationMillis` 的语义。
     */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val text: String,
        val durationMillis: Long = 0L,
    ) : MessagePart()

    /**
     * 工具调用块。
     *
     * 状态用字段组合表达,不用 enum:
     * - 执行进度:[result] 为空 = 执行中 / 未完成;非空 = 已完成。
     * - 审批结果:[denied] = true 表示被用户拒绝([result] 写拒绝原因)。
     *
     * 本仓库工具确认是"调用前拦截"(见 [com.nuttavern.data.tools.LocalToolsSettings.approvalRequiredToolIds]),
     * 确认在调用发起前由 UI 完成,落库时只剩"已执行"或"被拒"两种终态,无需 Pending/Approved 等中间态。
     *
     * @property arguments 工具入参,原始 JSON 字符串(与 [com.nuttavern.network.LocalToolCall.arguments] 对齐)。
     */
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        val result: String = "",
        val denied: Boolean = false,
    ) : MessagePart()
}

package com.nuttavern.ui.chat

import com.nuttavern.data.model.MessagePart

/**
 * 一条消息按渲染需要分组后的块。把有序 [MessagePart] 列表聚成:
 * - [Timeline]:连续的思考 / 工具调用,聚成一个时间线块(竖排紧凑卡片);
 * - [Body]:正文文本块,打断时间线、单独成块。
 *
 * 顺序严格跟随 parts 原始顺序,不重排。穿插能力来自"不重排"——
 * 同一条消息可以是 思考 → 工具 → 正文 → 思考 → 正文 这种交替结构。
 */
internal sealed interface MessagePartBlock {
    /** 连续的思考 / 工具调用,按到达顺序聚在一个时间线块里。[steps] 只含 Reasoning / ToolCall。 */
    data class Timeline(val steps: List<MessagePart>) : MessagePartBlock

    /** 正文文本块。 */
    data class Body(val text: MessagePart.Text) : MessagePartBlock
}

/**
 * 把有序 parts 分组成可渲染的块列表:连续的 Reasoning / ToolCall 聚成一个 [MessagePartBlock.Timeline],
 * 遇到 [MessagePart.Text] 就 flush 当前时间线、把正文作为 [MessagePartBlock.Body] 单独放入,末尾再 flush。
 *
 * 纯函数,无 Compose 依赖,可单测。
 */
internal fun List<MessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val blocks = mutableListOf<MessagePartBlock>()
    val pendingTimelineSteps = mutableListOf<MessagePart>()

    fun flushTimeline() {
        if (pendingTimelineSteps.isNotEmpty()) {
            blocks += MessagePartBlock.Timeline(pendingTimelineSteps.toList())
            pendingTimelineSteps.clear()
        }
    }

    for (part in this) {
        when (part) {
            is MessagePart.Reasoning, is MessagePart.ToolCall -> pendingTimelineSteps += part
            is MessagePart.Text -> {
                flushTimeline()
                blocks += MessagePartBlock.Body(part)
            }
        }
    }
    flushTimeline()
    return blocks
}

/**
 * 时间线块内的渲染分段。思考与工具调用合并策略不同,所以在时间线块内再细分:
 * - [TimelineSegment.Thinking]:单个思考项,各自独立成卡(思考卡自带展开 / 收起);
 * - [TimelineSegment.ToolGroup]:连续的工具调用聚成一组,渲染成一张合并折叠卡。
 *
 * 顺序严格跟随输入,不重排。
 */
internal sealed interface TimelineSegment {
    data class Thinking(val reasoning: MessagePart.Reasoning) : TimelineSegment

    data class ToolGroup(val toolCalls: List<MessagePart.ToolCall>) : TimelineSegment
}

/**
 * 把时间线块的 steps 细分成渲染分段:连续的 [MessagePart.ToolCall] 聚成一个 [TimelineSegment.ToolGroup],
 * 遇到 [MessagePart.Reasoning] 就 flush 当前工具组、把思考作为 [TimelineSegment.Thinking] 单独放入。
 *
 * [MessagePart.Text] 不应进入时间线块(已被 [groupMessageParts] 分到 Body),防御性忽略。
 *
 * 纯函数,无 Compose 依赖,可单测。
 */
internal fun List<MessagePart>.splitTimelineSegments(): List<TimelineSegment> {
    val segments = mutableListOf<TimelineSegment>()
    val pendingToolCalls = mutableListOf<MessagePart.ToolCall>()

    fun flushToolGroup() {
        if (pendingToolCalls.isNotEmpty()) {
            segments += TimelineSegment.ToolGroup(pendingToolCalls.toList())
            pendingToolCalls.clear()
        }
    }

    for (part in this) {
        when (part) {
            is MessagePart.ToolCall -> pendingToolCalls += part
            is MessagePart.Reasoning -> {
                flushToolGroup()
                segments += TimelineSegment.Thinking(part)
            }
            is MessagePart.Text -> Unit
        }
    }
    flushToolGroup()
    return segments
}

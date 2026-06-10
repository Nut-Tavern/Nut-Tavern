package com.nuttavern.ui.chat

import com.nuttavern.data.model.MessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePartGroupingTest {

    private val reasoning = MessagePart.Reasoning("想一下", 100L)
    private val tool = MessagePart.ToolCall("c1", "get_current_time", "{}", result = "ok")
    private val text = MessagePart.Text("正文")

    @Test
    fun emptyParts_producesNoBlocks() {
        assertTrue(emptyList<MessagePart>().groupMessageParts().isEmpty())
    }

    @Test
    fun singleText_producesOneBodyBlock() {
        val blocks = listOf(text).groupMessageParts()
        assertEquals(listOf(MessagePartBlock.Body(text)), blocks)
    }

    @Test
    fun reasoningThenText_producesTimelineThenBody() {
        val blocks = listOf(reasoning, text).groupMessageParts()
        assertEquals(
            listOf(
                MessagePartBlock.Timeline(listOf(reasoning)),
                MessagePartBlock.Body(text),
            ),
            blocks,
        )
    }

    @Test
    fun consecutiveReasoningAndTool_mergeIntoOneTimeline() {
        val blocks = listOf(reasoning, tool, text).groupMessageParts()
        assertEquals(
            listOf(
                MessagePartBlock.Timeline(listOf(reasoning, tool)),
                MessagePartBlock.Body(text),
            ),
            blocks,
        )
    }

    @Test
    fun interleavedParts_keepOrderAndSplitTimelines() {
        // 思考 → 工具 → 正文 → 工具 → 正文:两段时间线被正文打断,顺序严格保留。
        val tool2 = MessagePart.ToolCall("c2", "tool_b", "{}", result = "done")
        val text2 = MessagePart.Text("第二段正文")
        val blocks = listOf(reasoning, tool, text, tool2, text2).groupMessageParts()
        assertEquals(
            listOf(
                MessagePartBlock.Timeline(listOf(reasoning, tool)),
                MessagePartBlock.Body(text),
                MessagePartBlock.Timeline(listOf(tool2)),
                MessagePartBlock.Body(text2),
            ),
            blocks,
        )
    }

    @Test
    fun trailingTimeline_isFlushed() {
        // 正文后又跟思考 / 工具,末尾时间线必须被 flush 出来。
        val blocks = listOf(text, reasoning, tool).groupMessageParts()
        assertEquals(
            listOf(
                MessagePartBlock.Body(text),
                MessagePartBlock.Timeline(listOf(reasoning, tool)),
            ),
            blocks,
        )
    }

    @Test
    fun splitTimeline_consecutiveTools_mergeIntoOneToolGroup() {
        // 思考 → 工具 → 工具:思考独立成段,连续两个工具聚成一个工具组。
        val tool2 = MessagePart.ToolCall("c2", "tool_b", "{}", result = "done")
        val segments = listOf(reasoning, tool, tool2).splitTimelineSegments()
        assertEquals(
            listOf(
                TimelineSegment.Thinking(reasoning),
                TimelineSegment.ToolGroup(listOf(tool, tool2)),
            ),
            segments,
        )
    }

    @Test
    fun splitTimeline_reasoningBetweenTools_splitsToolGroups() {
        // 工具 → 思考 → 工具:思考打断工具组,前后各成一组,顺序严格保留。
        val tool2 = MessagePart.ToolCall("c2", "tool_b", "{}", result = "done")
        val segments = listOf(tool, reasoning, tool2).splitTimelineSegments()
        assertEquals(
            listOf(
                TimelineSegment.ToolGroup(listOf(tool)),
                TimelineSegment.Thinking(reasoning),
                TimelineSegment.ToolGroup(listOf(tool2)),
            ),
            segments,
        )
    }

    @Test
    fun splitTimeline_singleTool_producesOneToolGroup() {
        val segments = listOf(tool).splitTimelineSegments()
        assertEquals(listOf(TimelineSegment.ToolGroup(listOf(tool))), segments)
    }
}

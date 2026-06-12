package com.nuttavern.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSwipesTest {

    private fun assistantMessage(
        id: String = "m1",
        parts: List<MessagePart>,
        swipes: List<List<MessagePart>> = emptyList(),
        swipeIndex: Int = 0,
    ) = Message(id = id, role = "assistant", parts = parts, swipes = swipes, swipeIndex = swipeIndex)

    private fun text(value: String): List<MessagePart> = listOf(MessagePart.Text(value))

    @Test
    fun appendRegenerated_noExistingSwipes_seedsWithOldAndAppendsNew() {
        val message = assistantMessage(parts = text("旧回复"))

        val merged = MessageSwipes.appendRegeneratedCandidate(message, text("新回复"))

        // 旧回复作为第 0 个候选保留,新回复追加为第 1 个并选中。
        assertEquals(listOf(text("旧回复"), text("新回复")), merged.swipes)
        assertEquals(1, merged.swipeIndex)
        assertEquals(text("新回复"), merged.parts)
    }

    @Test
    fun appendRegenerated_withExistingSwipes_keepsAllAndAppends() {
        val message = assistantMessage(
            parts = text("候选B"),
            swipes = listOf(text("候选A"), text("候选B")),
            swipeIndex = 1,
        )

        val merged = MessageSwipes.appendRegeneratedCandidate(message, text("候选C"))

        assertEquals(listOf(text("候选A"), text("候选B"), text("候选C")), merged.swipes)
        assertEquals(2, merged.swipeIndex)
        assertEquals(text("候选C"), merged.parts)
    }

    @Test
    fun appendRegenerated_preservesToolCallParts() {
        val oldParts = listOf(
            MessagePart.ToolCall(toolCallId = "c1", toolName = "get_time", arguments = "{}", result = "20:00"),
            MessagePart.Text("现在 20:00"),
        )
        val message = assistantMessage(parts = oldParts)

        val merged = MessageSwipes.appendRegeneratedCandidate(message, text("直接说:晚上八点"))

        // 带工具调用的旧候选整条(含 ToolCall)保留为第 0 个候选。
        assertEquals(oldParts, merged.swipes[0])
        assertEquals(text("直接说:晚上八点"), merged.swipes[1])
        assertEquals(1, merged.swipeIndex)
    }

    @Test
    fun appendRegenerated_emptyNewParts_throws() {
        // 锁住调用契约:空候选并入会让用户切回时看到空白消息。空回复场景调用方应走"不落库"路径
        // (ChatViewModel 在 assistantParts.isEmpty() 时早返回),不应到达本函数。
        val message = assistantMessage(parts = text("旧回复"))

        assertThrows(IllegalArgumentException::class.java) {
            MessageSwipes.appendRegeneratedCandidate(message, emptyList())
        }
    }

    @Test
    fun selectCandidate_switchesPartsAndIndex() {
        val message = assistantMessage(
            parts = text("候选C"),
            swipes = listOf(text("候选A"), text("候选B"), text("候选C")),
            swipeIndex = 2,
        )

        val switched = MessageSwipes.selectCandidate(message, 0)

        assertEquals(0, switched.swipeIndex)
        assertEquals(text("候选A"), switched.parts)
        // swipes 列表本身不变,只换选中。
        assertEquals(message.swipes, switched.swipes)
    }

    @Test
    fun selectCandidate_sameIndex_returnsOriginal() {
        val message = assistantMessage(
            parts = text("候选B"),
            swipes = listOf(text("候选A"), text("候选B")),
            swipeIndex = 1,
        )

        val result = MessageSwipes.selectCandidate(message, 1)

        assertSame(message, result)
    }

    @Test
    fun selectCandidate_outOfBounds_returnsOriginal() {
        val message = assistantMessage(
            parts = text("候选B"),
            swipes = listOf(text("候选A"), text("候选B")),
            swipeIndex = 1,
        )

        assertSame(message, MessageSwipes.selectCandidate(message, -1))
        assertSame(message, MessageSwipes.selectCandidate(message, 2))
    }

    @Test
    fun selectCandidate_noMultipleSwipes_returnsOriginal() {
        val message = assistantMessage(parts = text("唯一回复"))

        assertSame(message, MessageSwipes.selectCandidate(message, 0))
        assertSame(message, MessageSwipes.selectCandidate(message, 1))
    }

    @Test
    fun hasMultipleSwipes_reflectsCandidateCount() {
        assertTrue(
            assistantMessage(
                parts = text("b"),
                swipes = listOf(text("a"), text("b")),
                swipeIndex = 1,
            ).hasMultipleSwipes,
        )
        assertEquals(false, assistantMessage(parts = text("x")).hasMultipleSwipes)
    }

    @Test
    fun removeCurrentCandidate_middleIndex_promotesNext() {
        // 删除中间索引,后一个顶上(索引不变,内容是原 swipes[2])。
        val message = assistantMessage(
            parts = text("B"),
            swipes = listOf(text("A"), text("B"), text("C")),
            swipeIndex = 1,
        )

        val result = MessageSwipes.removeCurrentCandidate(message)

        assertEquals(listOf(text("A"), text("C")), result.swipes)
        assertEquals(1, result.swipeIndex)
        assertEquals(text("C"), result.parts)
    }

    @Test
    fun removeCurrentCandidate_lastIndex_fallsBackToNewLast() {
        // 删除末位,索引回退到新末位。
        val message = assistantMessage(
            parts = text("C"),
            swipes = listOf(text("A"), text("B"), text("C")),
            swipeIndex = 2,
        )

        val result = MessageSwipes.removeCurrentCandidate(message)

        assertEquals(listOf(text("A"), text("B")), result.swipes)
        assertEquals(1, result.swipeIndex)
        assertEquals(text("B"), result.parts)
    }

    @Test
    fun removeCurrentCandidate_firstIndex_promotesNext() {
        // 删除首位,后一个顶上(索引保持 0,内容是原 swipes[1])。
        val message = assistantMessage(
            parts = text("A"),
            swipes = listOf(text("A"), text("B"), text("C")),
            swipeIndex = 0,
        )

        val result = MessageSwipes.removeCurrentCandidate(message)

        assertEquals(listOf(text("B"), text("C")), result.swipes)
        assertEquals(0, result.swipeIndex)
        assertEquals(text("B"), result.parts)
    }

    @Test
    fun removeCurrentCandidate_singleSwipe_returnsOriginal() {
        // 单候选不能删 swipe(调用方应走删整条),本函数返回原消息。
        val singleSwipe = assistantMessage(
            parts = text("X"),
            swipes = listOf(text("X")),
            swipeIndex = 0,
        )
        assertSame(singleSwipe, MessageSwipes.removeCurrentCandidate(singleSwipe))

        val noSwipes = assistantMessage(parts = text("X"))
        assertSame(noSwipes, MessageSwipes.removeCurrentCandidate(noSwipes))
    }

    @Test
    fun removeCurrentCandidate_outOfBoundsIndex_coercesToValid() {
        // swipeIndex 越界时按 coerce 到合法区间处理,避免脏数据让函数崩溃。
        val message = assistantMessage(
            parts = text("B"),
            swipes = listOf(text("A"), text("B")),
            swipeIndex = 5,
        )

        val result = MessageSwipes.removeCurrentCandidate(message)

        // 索引被 coerce 到 1(末位),删后退回 0。
        assertEquals(listOf(text("A")), result.swipes)
        assertEquals(0, result.swipeIndex)
        assertEquals(text("A"), result.parts)
    }
}

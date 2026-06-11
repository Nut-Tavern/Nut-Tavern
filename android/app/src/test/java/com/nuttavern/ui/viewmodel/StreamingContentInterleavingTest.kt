package com.nuttavern.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingContentInterleavingTest {

    @Test
    fun noTools_wholeContentIsSingleTailText() {
        val slices = interleaveContentWithTools("完整正文", emptyList())
        assertEquals(listOf(ContentSlice.Text("完整正文", isTail = true)), slices)
    }

    @Test
    fun noTools_emptyContent_producesNoSlice() {
        assertEquals(emptyList<ContentSlice>(), interleaveContentWithTools("", emptyList()))
    }

    @Test
    fun textBeforeAndAfterTool_interleavedInOrder() {
        // "前文" 长度 2 → 工具切点 2,工具后是 "后文"。
        val slices = interleaveContentWithTools("前文后文", listOf(2))
        assertEquals(
            listOf(
                ContentSlice.Text("前文", isTail = false),
                ContentSlice.Tool(0),
                ContentSlice.Text("后文", isTail = true),
            ),
            slices,
        )
    }

    @Test
    fun toolAtStart_noLeadingText() {
        // 切点 0:工具前无文字,只产出工具 + 尾段。
        val slices = interleaveContentWithTools("正文", listOf(0))
        assertEquals(
            listOf(
                ContentSlice.Tool(0),
                ContentSlice.Text("正文", isTail = true),
            ),
            slices,
        )
    }

    @Test
    fun toolAtEnd_noTrailingText() {
        // 切点等于全长:工具前是全部文字,工具后无尾段。
        val slices = interleaveContentWithTools("正文", listOf(2))
        assertEquals(
            listOf(
                ContentSlice.Text("正文", isTail = false),
                ContentSlice.Tool(0),
            ),
            slices,
        )
    }

    @Test
    fun multipleTools_eachSplitsContent() {
        // "A段BB段CCC" 切点 1(A后)、3(BB后):A → 工具0 → BB → 工具1 → 段CCC。
        val slices = interleaveContentWithTools("ABBCCC", listOf(1, 3))
        assertEquals(
            listOf(
                ContentSlice.Text("A", isTail = false),
                ContentSlice.Tool(0),
                ContentSlice.Text("BB", isTail = false),
                ContentSlice.Tool(1),
                ContentSlice.Text("CCC", isTail = true),
            ),
            slices,
        )
    }

    @Test
    fun adjacentToolsSameOffset_noEmptyTextBetween() {
        // 两个工具同切点 2:它们之间没有文字段。
        val slices = interleaveContentWithTools("前文后文", listOf(2, 2))
        assertEquals(
            listOf(
                ContentSlice.Text("前文", isTail = false),
                ContentSlice.Tool(0),
                ContentSlice.Tool(1),
                ContentSlice.Text("后文", isTail = true),
            ),
            slices,
        )
    }

    @Test
    fun offsetBeyondLength_clampedToContentEnd() {
        // 切点越界(超过全长)被 clamp 到末尾:工具前取全部文字,无尾段。
        val slices = interleaveContentWithTools("正文", listOf(999))
        assertEquals(
            listOf(
                ContentSlice.Text("正文", isTail = false),
                ContentSlice.Tool(0),
            ),
            slices,
        )
    }

    @Test
    fun toolsButEmptyContent_onlyToolSlices() {
        val slices = interleaveContentWithTools("", listOf(0, 0))
        assertEquals(
            listOf(ContentSlice.Tool(0), ContentSlice.Tool(1)),
            slices,
        )
    }
}

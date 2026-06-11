package com.nuttavern.data.lorebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LorebookEditHistoryTest {

    private val history = LorebookEditHistory()

    private fun snapshot(uid: Int): List<LorebookEntry> = listOf(LorebookEntry(uid = uid))

    @Test
    fun pushThenPop_restoresLatestSnapshotLifo() {
        history.push("book", snapshot(0))
        history.push("book", snapshot(1))
        assertEquals(2, history.depth("book"))
        assertEquals(1, history.pop("book")!!.first().uid)
        assertEquals(0, history.pop("book")!!.first().uid)
        assertNull(history.pop("book"))
    }

    @Test
    fun pop_emptyOrUnknownBook_returnsNull() {
        assertNull(history.pop("never-pushed"))
    }

    @Test
    fun push_overMaxHistory_dropsOldest() {
        repeat(LorebookEditHistory.MAX_HISTORY + 3) { index ->
            history.push("book", snapshot(index))
        }
        assertEquals(LorebookEditHistory.MAX_HISTORY, history.depth("book"))
        // 最新一次(index 最大)仍在栈顶。
        val newestUid = LorebookEditHistory.MAX_HISTORY + 2
        assertEquals(newestUid, history.pop("book")!!.first().uid)
    }

    @Test
    fun stacks_perLorebookIdAreIndependent() {
        history.push("a", snapshot(10))
        history.push("b", snapshot(20))
        assertEquals(1, history.depth("a"))
        assertEquals(1, history.depth("b"))
        assertEquals(10, history.pop("a")!!.first().uid)
        assertEquals(1, history.depth("b"))
    }
}

package com.nuttavern.network.tools

import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.network.ChatTool
import com.nuttavern.network.ToolContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LorebookReadToolsTest {

    private val readTools = LorebookReadTools()

    private fun toolByName(name: String): ChatTool =
        readTools.tools.first { it.name == name }

    private fun context(books: List<Lorebook>): ToolContext = object : ToolContext {
        override val conversationId: String? = "conv-1"
        override val sessionLorebooks: List<Lorebook> = books
    }

    private val eldoria = Lorebook(
        id = "eldoria",
        name = "Eldoria",
        entries = listOf(
            LorebookEntry(uid = 0, comment = "王都", key = listOf("王都", "首都"), content = "王都位于大陆中心，是王国的政治核心。"),
            LorebookEntry(uid = 1, comment = "森林", key = listOf("森林"), content = "古老的森林。", disable = true),
        ),
    )

    @Test
    fun listSessionLorebooks_noQuery_returnsAllBooksAndEntrySummaries() = runBlocking {
        val result = JSONObject(
            toolByName(LorebookReadTools.TOOL_LIST_SESSION_LOREBOOKS)
                .execute(JSONObject(), context(listOf(eldoria))),
        )
        val books = result.getJSONArray("lorebooks")
        assertEquals(1, books.length())
        val book = books.getJSONObject(0)
        assertEquals("eldoria", book.getString("id"))
        assertEquals(2, book.getJSONArray("entries").length())
        // 第二条 disabled → enabled=false。
        assertFalse(book.getJSONArray("entries").getJSONObject(1).getBoolean("enabled"))
        // 摘要不含完整正文字段,只有 content_preview。
        assertFalse(book.getJSONArray("entries").getJSONObject(0).has("content"))
        assertTrue(book.getJSONArray("entries").getJSONObject(0).has("content_preview"))
    }

    @Test
    fun listSessionLorebooks_queryMatchesEntryKeyword_filtersEntries() = runBlocking {
        val result = JSONObject(
            toolByName(LorebookReadTools.TOOL_LIST_SESSION_LOREBOOKS)
                .execute(JSONObject().put("query", "首都"), context(listOf(eldoria))),
        )
        val book = result.getJSONArray("lorebooks").getJSONObject(0)
        // 只命中"王都"条目(关键词含"首都")。
        assertEquals(1, book.getJSONArray("entries").length())
        assertEquals("王都", book.getJSONArray("entries").getJSONObject(0).getString("comment"))
    }

    @Test
    fun listSessionLorebooks_queryNoMatch_excludesBook() = runBlocking {
        val result = JSONObject(
            toolByName(LorebookReadTools.TOOL_LIST_SESSION_LOREBOOKS)
                .execute(JSONObject().put("query", "不存在的词"), context(listOf(eldoria))),
        )
        assertEquals(0, result.getJSONArray("lorebooks").length())
    }

    @Test
    fun readLorebookEntry_validUid_returnsFullContent() = runBlocking {
        val result = JSONObject(
            toolByName(LorebookReadTools.TOOL_READ_LOREBOOK_ENTRY)
                .execute(
                    JSONObject().put("lorebook_id", "eldoria").put("uid", 0),
                    context(listOf(eldoria)),
                ),
        )
        val entry = result.getJSONObject("entry")
        assertEquals(0, entry.getInt("uid"))
        assertEquals("王都位于大陆中心，是王国的政治核心。", entry.getString("content"))
    }

    @Test
    fun readLorebookEntry_lorebookNotInSession_rejected() = runBlocking {
        val result = JSONObject(
            toolByName(LorebookReadTools.TOOL_READ_LOREBOOK_ENTRY)
                .execute(
                    JSONObject().put("lorebook_id", "other-book").put("uid", 0),
                    context(listOf(eldoria)),
                ),
        )
        assertTrue(result.has("error"))
    }

    @Test
    fun readLorebookEntry_uidNotFound_rejected() = runBlocking {
        val result = JSONObject(
            toolByName(LorebookReadTools.TOOL_READ_LOREBOOK_ENTRY)
                .execute(
                    JSONObject().put("lorebook_id", "eldoria").put("uid", 999),
                    context(listOf(eldoria)),
                ),
        )
        assertTrue(result.has("error"))
    }

    @Test
    fun readLorebookEntry_missingArgs_rejected() = runBlocking {
        val result = JSONObject(
            toolByName(LorebookReadTools.TOOL_READ_LOREBOOK_ENTRY)
                .execute(JSONObject().put("lorebook_id", "eldoria"), context(listOf(eldoria))),
        )
        assertTrue(result.has("error"))
    }

    @Test
    fun readOnlyTools_doNotRequireApproval() {
        assertFalse(toolByName(LorebookReadTools.TOOL_LIST_SESSION_LOREBOOKS).needsApproval)
        assertFalse(toolByName(LorebookReadTools.TOOL_READ_LOREBOOK_ENTRY).needsApproval)
    }

    @Test
    fun readOnlyTools_useReadOnlyGroup() {
        assertEquals("lorebook_read", toolByName(LorebookReadTools.TOOL_LIST_SESSION_LOREBOOKS).group?.id)
        assertEquals("lorebook_read", toolByName(LorebookReadTools.TOOL_READ_LOREBOOK_ENTRY).group?.id)
    }

    @Test
    fun readOnlyTools_returnProtocolEnvelope() = runBlocking {
        val result = JSONObject(
            toolByName(LorebookReadTools.TOOL_LIST_SESSION_LOREBOOKS)
                .execute(JSONObject(), context(listOf(eldoria))),
        )
        assertTrue(result.getBoolean("ok"))
        assertEquals(LorebookReadTools.TOOL_LIST_SESSION_LOREBOOKS, result.getString("tool"))
        assertTrue(result.has("warnings"))
    }
}

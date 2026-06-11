package com.nuttavern.network.tools

import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEntry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书写工具核心计算 [planLorebookEdits] 的单测。
 *
 * 只测顶层纯函数,不拉起 LorebookRepository / Hilt:落库 / 范围校验 / 撤销栈压栈在
 * [LorebookWriteTools.applyLorebookEdits] 里编排,纯函数只负责"在给定整书上算出编辑结果"。
 */
class LorebookWriteToolsTest {

    private val book = Lorebook(
        id = "eldoria",
        name = "Eldoria",
        entries = listOf(
            LorebookEntry(uid = 0, comment = "王都", key = listOf("王都"), content = "王都正文", order = 100),
            LorebookEntry(uid = 1, comment = "森林", key = listOf("森林"), content = "森林正文", disable = true, order = 50),
        ),
    )

    private fun edits(vararg ops: JSONObject): JSONArray =
        JSONArray().apply { ops.forEach { put(it) } }

    @Test
    fun create_appendsEntryWithNextUid() {
        val plan = planLorebookEdits(
            book,
            edits(
                JSONObject().put("op", "create").put(
                    "entry",
                    JSONObject().put("comment", "海港").put("key", JSONArray().put("海港")).put("content", "海港正文"),
                ),
            ),
        )
        assertNull(plan.error)
        assertEquals(3, plan.resultEntries.size)
        val created = plan.resultEntries.last()
        assertEquals(2, created.uid) // nextEntryUid = max(0,1)+1
        assertEquals("海港", created.comment)
        assertEquals(listOf("海港"), created.key)
        assertFalse(created.disable)
    }

    @Test
    fun update_patchOnlyChangesProvidedFields() {
        val plan = planLorebookEdits(
            book,
            edits(
                JSONObject().put("op", "update").put("uid", 0).put(
                    "patch",
                    JSONObject().put("content", "新王都正文"),
                ),
            ),
        )
        assertNull(plan.error)
        val updated = plan.resultEntries.first { it.uid == 0 }
        assertEquals("新王都正文", updated.content)
        // patch 未传的字段保持原值。
        assertEquals("王都", updated.comment)
        assertEquals(listOf("王都"), updated.key)
        assertEquals(100, updated.order)
    }

    @Test
    fun setEnabled_togglesDisableFlag() {
        val plan = planLorebookEdits(
            book,
            edits(JSONObject().put("op", "set_enabled").put("uid", 1).put("enabled", true)),
        )
        assertNull(plan.error)
        val entry = plan.resultEntries.first { it.uid == 1 }
        assertFalse(entry.disable) // enabled=true → disable=false
    }

    @Test
    fun delete_removesEntryByUid() {
        val plan = planLorebookEdits(
            book,
            edits(JSONObject().put("op", "delete").put("uid", 0)),
        )
        assertNull(plan.error)
        assertEquals(1, plan.resultEntries.size)
        assertEquals(1, plan.resultEntries.first().uid)
    }

    @Test
    fun multipleOps_keepOriginalOrderAndAppendCreatedAtEnd() {
        val plan = planLorebookEdits(
            book,
            edits(
                JSONObject().put("op", "create").put(
                    "entry",
                    JSONObject().put("comment", "新条目"),
                ),
                JSONObject().put("op", "update").put("uid", 1).put(
                    "patch",
                    JSONObject().put("comment", "改后森林"),
                ),
            ),
        )
        assertNull(plan.error)
        // 顺序:原有 uid 0、1 在前,新建追加在末尾。
        assertEquals(listOf(0, 1, 2), plan.resultEntries.map { it.uid })
        assertEquals("改后森林", plan.resultEntries.first { it.uid == 1 }.comment)
    }

    @Test
    fun unknownOp_returnsErrorAndNoPartialApply() {
        val plan = planLorebookEdits(
            book,
            edits(
                JSONObject().put("op", "delete").put("uid", 0),
                JSONObject().put("op", "frobnicate").put("uid", 1),
            ),
        )
        assertNotNull(plan.error)
        assertTrue(plan.error!!.contains("unknown op"))
    }

    @Test
    fun update_missingUid_returnsError() {
        val plan = planLorebookEdits(
            book,
            edits(JSONObject().put("op", "update").put("patch", JSONObject().put("content", "x"))),
        )
        assertNotNull(plan.error)
    }

    @Test
    fun update_uidNotFound_returnsError() {
        val plan = planLorebookEdits(
            book,
            edits(JSONObject().put("op", "update").put("uid", 999).put("patch", JSONObject().put("content", "x"))),
        )
        assertNotNull(plan.error)
    }

    @Test
    fun create_missingEntry_returnsError() {
        val plan = planLorebookEdits(book, edits(JSONObject().put("op", "create")))
        assertNotNull(plan.error)
    }

    @Test
    fun beforeAfter_recordsCreateUpdateDeleteShape() {
        val plan = planLorebookEdits(
            book,
            edits(
                JSONObject().put("op", "delete").put("uid", 0),
            ),
        )
        assertNull(plan.error)
        val entry = plan.beforeAfterJson.getJSONObject(0)
        assertEquals(0, entry.getInt("uid"))
        assertNotNull(entry.get("before"))
        assertEquals(JSONObject.NULL, entry.get("after"))
    }

    @Test
    fun disabledEffectiveUids_flagsUpdatedEntryStillDisabled() {
        // 更新一个 disabled 条目(uid=1 在 book 里 disable=true)的正文,改成功但仍禁用。
        val plan = planLorebookEdits(
            book,
            edits(
                JSONObject().put("op", "update").put("uid", 1).put(
                    "patch",
                    JSONObject().put("content", "改后森林"),
                ),
            ),
        )
        assertNull(plan.error)
        assertEquals(listOf(1), disabledEffectiveUids(plan.resultEntries, plan.appliedJson))
    }

    @Test
    fun disabledEffectiveUids_ignoresEnabledAndSetEnabledOps() {
        // update enabled 条目(uid=0)+ set_enabled 启用 uid=1:无"改了不生效"的条目。
        val plan = planLorebookEdits(
            book,
            edits(
                JSONObject().put("op", "update").put("uid", 0).put("patch", JSONObject().put("content", "x")),
                JSONObject().put("op", "set_enabled").put("uid", 1).put("enabled", true),
            ),
        )
        assertNull(plan.error)
        assertEquals(emptyList<Int>(), disabledEffectiveUids(plan.resultEntries, plan.appliedJson))
    }

    @Test
    fun disabledEffectiveUids_flagsCreatedDisabledEntry() {
        // 新建一个 enabled=false 的条目:创建成功但不生效,应被标记。
        val plan = planLorebookEdits(
            book,
            edits(
                JSONObject().put("op", "create").put(
                    "entry",
                    JSONObject().put("comment", "草稿").put("enabled", false),
                ),
            ),
        )
        assertNull(plan.error)
        assertEquals(listOf(2), disabledEffectiveUids(plan.resultEntries, plan.appliedJson))
    }
}

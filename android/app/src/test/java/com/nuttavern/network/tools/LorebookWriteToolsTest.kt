package com.nuttavern.network.tools

import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.network.DiffLineKind
import com.nuttavern.network.ToolDiffType
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

    @Test
    fun buildLorebookEditDiffSections_mapsToCorrectToolDiffTypes() {
        val plan = planLorebookEdits(
            book,
            edits(
                JSONObject().put("op", "create").put(
                    "entry",
                    JSONObject().put("comment", "海港").put("key", JSONArray().put("海港")).put("content", "海港正文"),
                ),
                JSONObject().put("op", "update").put("uid", 0).put(
                    "patch",
                    JSONObject().put("content", "新王都正文"),
                ),
                JSONObject().put("op", "set_enabled").put("uid", 1).put("enabled", true),
                JSONObject().put("op", "delete").put("uid", 0),
            ),
        )
        assertNull(plan.error)

        val diffs = buildLorebookEditDiffSections(plan.beforeAfterJson)
        val types = diffs.map { it.type }
        assertTrue("应包含 ADDED", ToolDiffType.ADDED in types)
        assertTrue("应包含 MODIFIED", ToolDiffType.MODIFIED in types)
        assertTrue("应包含 STATUS", ToolDiffType.STATUS in types)
        assertTrue("应包含 DELETED", ToolDiffType.DELETED in types)
    }

    @Test
    fun buildLorebookEditDiffSections_includesAllWritableFieldsInPreview() {
        val plan = planLorebookEdits(
            book,
            edits(
                JSONObject().put("op", "update").put("uid", 0).put(
                    "patch",
                    JSONObject()
                        .put("keysecondary", JSONArray().put("王城"))
                        .put("order", 250),
                ),
            ),
        )
        assertNull(plan.error)

        val diff = buildLorebookEditDiffSections(plan.beforeAfterJson).single()
        val fieldNames = diff.fields.map { it.name }

        assertTrue("次要关键词应出现在确认预览", "次要关键词" in fieldNames)
        assertTrue("排序权重应出现在确认预览", "排序权重" in fieldNames)
    }

    @Test
    fun buildLorebookEditDiffSections_createShowsSecondaryKeywordsAndOrder() {
        val plan = planLorebookEdits(
            book,
            edits(
                JSONObject().put("op", "create").put(
                    "entry",
                    JSONObject()
                        .put("comment", "海港")
                        .put("key", JSONArray().put("海港"))
                        .put("keysecondary", JSONArray().put("码头"))
                        .put("order", 180)
                        .put("content", "海港正文"),
                ),
            ),
        )
        assertNull(plan.error)

        val diff = buildLorebookEditDiffSections(plan.beforeAfterJson).single()
        val fieldNames = diff.fields.map { it.name }

        assertTrue("新增预览应显示次要关键词", "次要关键词" in fieldNames)
        assertTrue("新增预览应显示排序权重", "排序权重" in fieldNames)
    }

    @Test
    fun diffLines_marksContextRemovedAddedInOrder() {
        val before = listOf("第一行不变", "中间旧内容", "第三行不变")
        val after = listOf("第一行不变", "中间新内容", "第三行不变")

        val ops = diffLines(before, after)

        // 不变行标记为上下文,旧行删除、新行新增,顺序为"删除在前、新增在后"。
        assertEquals(
            listOf(
                DiffLineKind.CONTEXT,
                DiffLineKind.REMOVED,
                DiffLineKind.ADDED,
                DiffLineKind.CONTEXT,
            ),
            ops.map { it.kind },
        )
        val removed = ops.first { it.kind == DiffLineKind.REMOVED }
        val added = ops.first { it.kind == DiffLineKind.ADDED }
        assertEquals("中间旧内容", removed.text)
        assertEquals("中间新内容", added.text)
    }

    @Test
    fun splitIntoHunks_splitsDiscontiguousChangesIntoSeparateHunks() {
        // 第 2 行和第 9 行各改一处,相隔远超 2*context,应切成两个独立 hunk。
        val before = (1..10).map { "line$it" }
        val after = before.toMutableList().apply {
            this[1] = "line2-改"
            this[8] = "line9-改"
        }

        val (hunks, _) = splitIntoHunks(diffLines(before, after), contextLines = 1)

        assertEquals("不连续的改动应切成两个 hunk", 2, hunks.size)
        // 第一个 hunk 之前没有省略行(改动靠近开头,上下文从第 1 行起),第二个 hunk 之前有省略行。
        assertEquals(false, hunks[0].precededByGap)
        assertEquals(true, hunks[1].precededByGap)
    }

    @Test
    fun splitIntoHunks_mergesNearbyChangesIntoOneHunk() {
        // 第 4 行和第 6 行都改,上下文(各 3 行)重叠,应合并成单个 hunk。
        val before = (1..10).map { "line$it" }
        val after = before.toMutableList().apply {
            this[3] = "line4-改"
            this[5] = "line6-改"
        }

        val (hunks, _) = splitIntoHunks(diffLines(before, after), contextLines = 3)

        assertEquals("邻近改动应合并为一个 hunk", 1, hunks.size)
    }

    @Test
    fun splitIntoHunks_reportsTrailingGapWhenLastChangeNotNearEnd() {
        val before = (1..10).map { "line$it" }
        val after = before.toMutableList().apply { this[1] = "line2-改" }

        val (hunks, trailingGap) = splitIntoHunks(diffLines(before, after), contextLines = 1)

        assertEquals(1, hunks.size)
        assertTrue("末尾还有未展示的行,应标记尾部省略", trailingGap)
    }

    @Test
    fun buildDiffField_wholeAddWhenBeforeNull() {
        val field = buildDiffField("正文", before = null, after = "第一行\n第二行")

        assertEquals(1, field.hunks.size)
        val kinds = field.hunks.first().lines.map { it.kind }
        assertEquals(listOf(DiffLineKind.ADDED, DiffLineKind.ADDED), kinds)
        // 整段新增没有上下文,不应有省略标记。
        assertFalse(field.hunks.first().precededByGap)
        assertFalse(field.hasTrailingGap)
    }

    @Test
    fun buildDiffField_wholeRemoveWhenAfterNull() {
        val field = buildDiffField("正文", before = "旧的一行", after = null)

        val kinds = field.hunks.flatMap { it.lines }.map { it.kind }
        assertEquals(listOf(DiffLineKind.REMOVED), kinds)
    }

    @Test
    fun buildDiffField_largeTwoSidedTextFallsBackToSummaryPreview() {
        val before = (1..250).joinToString("\n") { "旧内容$it" }
        val after = (1..250).joinToString("\n") { "新内容$it" }

        val field = buildDiffField("正文", before, after)

        val lines = field.hunks.flatMap { it.lines }
        assertEquals("摘要预览只保留旧内容前 8 行 + 新内容前 8 行", 16, lines.size)
        assertTrue("摘要预览应提示后续内容被省略", field.hasTrailingGap)
        assertEquals(DiffLineKind.REMOVED, lines.first().kind)
        assertEquals(DiffLineKind.ADDED, lines.last().kind)
    }

    @Test
    fun buildDiffField_largeOneSidedTextFallsBackToSummaryPreview() {
        val after = (1..40).joinToString("\n") { "新增内容$it" }

        val field = buildDiffField("正文", before = null, after = after)

        val lines = field.hunks.flatMap { it.lines }
        assertEquals(8, lines.size)
        assertTrue("单边摘要预览应提示后续内容被省略", field.hasTrailingGap)
        assertTrue(lines.all { it.kind == DiffLineKind.ADDED })
    }
}

package com.nuttavern.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolUnitTest {

    private val lorebookGroup = ToolGroup(id = "lorebook", displayName = "世界书", description = "读写世界书")

    private fun tool(id: String, group: ToolGroup? = null): ChatTool = ChatTool(
        id = id,
        name = id,
        displayName = id,
        description = id,
        parametersSchema = JSONObject(),
        group = group,
        execute = { _, _ -> "" },
    )

    @Test
    fun buildToolUnits_groupsToolsAndKeepsRegistrationOrderWhenNoOrder() {
        val tools = listOf(
            tool("time"),
            tool("list", lorebookGroup),
            tool("read", lorebookGroup),
        )
        val units = buildToolUnits(tools)
        // 同组合并成一个单元,组单元落在该组第一个工具的位置。
        assertEquals(listOf("tool:time", "group:lorebook"), units.map { it.orderKey })
        val group = units.last() as ToolUnit.Group
        assertEquals(setOf("list", "read"), group.toolIds)
    }

    @Test
    fun buildToolUnits_appliesExplicitOrder() {
        val tools = listOf(tool("time"), tool("list", lorebookGroup), tool("read", lorebookGroup))
        val units = buildToolUnits(tools, order = listOf("group:lorebook", "tool:time"))
        assertEquals(listOf("group:lorebook", "tool:time"), units.map { it.orderKey })
    }

    @Test
    fun buildToolUnits_unrankedUnitsKeepOriginalRelativeOrderAtEnd() {
        val tools = listOf(tool("a"), tool("b"), tool("c"))
        // order 只覆盖 c,a/b 未排过序 → 追加在末尾,保持原相对顺序 a 在 b 前。
        val units = buildToolUnits(tools, order = listOf("tool:c"))
        assertEquals(listOf("tool:c", "tool:a", "tool:b"), units.map { it.orderKey })
    }

    @Test
    fun buildToolUnits_staleOrderKeysIgnored() {
        val tools = listOf(tool("a"), tool("b"))
        // order 含已不存在的 key,不影响现存单元排序。
        val units = buildToolUnits(tools, order = listOf("tool:removed", "tool:b", "tool:a"))
        assertEquals(listOf("tool:b", "tool:a"), units.map { it.orderKey })
    }
}

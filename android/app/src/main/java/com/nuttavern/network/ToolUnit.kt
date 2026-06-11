package com.nuttavern.network

/**
 * 内置工具的"展示单元":设置页 / 右侧栏快选里把工具列成卡片的最小单位。
 *
 * 同一 [ToolGroup] 的工具合并成一个 [ToolUnit.Group],无分组工具各自是 [ToolUnit.SingleTool]。
 * 单工具页、右侧栏快选、单元计数共用这一份聚合 + 排序逻辑,保证三处口径一致。
 */
sealed interface ToolUnit {
    /** 稳定排序 key:单工具 `tool:{id}`,工具组 `group:{id}`。也用作 LazyColumn item key。 */
    val orderKey: String

    /** 这个展示单元覆盖的全部工具 id。组级开关把这些 id 一起增删。 */
    val toolIds: Set<String>

    data class SingleTool(val tool: ChatTool) : ToolUnit {
        override val orderKey: String get() = "tool:${tool.id}"
        override val toolIds: Set<String> get() = setOf(tool.id)
    }

    data class Group(val group: ToolGroup, val tools: List<ChatTool>) : ToolUnit {
        override val orderKey: String get() = "group:${group.id}"
        override val toolIds: Set<String> get() = tools.map { it.id }.toSet()
    }
}

/**
 * 把工具列表聚合成展示单元,并按 [order] 排序。
 *
 * - 聚合:同组工具合并成 [ToolUnit.Group](保持组内原始顺序),无分组工具各自成 [ToolUnit.SingleTool];
 * - 排序:先按 [order] 里出现的 [ToolUnit.orderKey] 排,[order] 没覆盖到的单元(新装工具 / 未排过序)
 *   追加到末尾,保持其在 [tools] 里的原始相对顺序。
 *
 * [order] 传空 = 完全按注册顺序。设置页拖动排序回写的 key 列表传进来即可让右侧栏跟随。
 */
fun buildToolUnits(tools: List<ChatTool>, order: List<String> = emptyList()): List<ToolUnit> {
    val units = mutableListOf<ToolUnit>()
    val seenGroupIds = mutableSetOf<String>()
    for (tool in tools) {
        val group = tool.group
        if (group == null) {
            units += ToolUnit.SingleTool(tool)
            continue
        }
        if (group.id in seenGroupIds) continue
        seenGroupIds += group.id
        units += ToolUnit.Group(group, tools.filter { it.group?.id == group.id })
    }
    if (order.isEmpty()) return units

    val rank = order.withIndex().associate { (index, key) -> key to index }
    return units.sortedWith(
        compareBy(
            { rank[it.orderKey] ?: Int.MAX_VALUE },
            { units.indexOf(it) },
        ),
    )
}

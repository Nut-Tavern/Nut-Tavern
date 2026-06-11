package com.nuttavern.data.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 客户端内置工具(本地 function calling)的全局配置。
 *
 * 与 [ToolsSettings](工具调用引擎自身的递归/推理行为)分开:这里只管"新会话默认是否启用工具、
 * 每个内置工具默认是否启用、每个工具调用前是否需要人工确认"。独立 DataStore 文件,爆炸半径自包含。
 *
 * 启用语义:
 * - [defaultEnabled]:决定新建会话占位态是否默认带上 [enabledToolIds],不动态影响已创建会话;
 * - [enabledToolIds]:新会话默认启用的工具 [com.nuttavern.network.ChatTool.id] 集合。空集表示
 *   "新会话默认不启用任何具体工具";
 * - [approvalRequiredToolIds]:调用前需要人工确认的工具 id 集合;
 * - [requireApproval]:旧版全局确认字段,仅为反序列化兼容保留,新逻辑不再消费。
 * - [toolOrder]:工具展示单元(单工具 `tool:{id}` / 工具组 `group:{id}`)的排序 key 列表。空 = 用注册
 *   顺序。设置页拖动排序后回写,内置工具页与右侧栏快选列表共用这一份顺序。
 *
 * 默认值:[enabledToolIds] 预置 [DEFAULT_ENABLED_TOOL_IDS](当前是无副作用的 get_current_time),
 * 让全新装机在"全局默认启用"下真实可用,而不是显示启用却下发空工具集。这里的 id 字面量必须与
 * [com.nuttavern.network.ChatToolRegistry] 注册的工具 id 保持一致(分层约束:data 层不反向依赖
 * network 层,故用文档约束而非引用常量)。
 */
@Serializable
data class LocalToolsSettings(
    @SerialName("default_enabled") val defaultEnabled: Boolean = true,
    @SerialName("require_approval") val requireApproval: Boolean = false,
    @SerialName("enabled_tool_ids") val enabledToolIds: Set<String> = DEFAULT_ENABLED_TOOL_IDS,
    @SerialName("approval_required_tool_ids") val approvalRequiredToolIds: Set<String> = emptySet(),
    @SerialName("tool_order") val toolOrder: List<String> = emptyList(),
) {
    fun isToolEnabledByDefault(toolId: String): Boolean = toolId in enabledToolIds

    fun isApprovalRequiredForTool(toolId: String): Boolean = toolId in approvalRequiredToolIds

    companion object {
        /** 全新装机默认启用的内置工具 id。只放无副作用、无需确认的安全工具。 */
        val DEFAULT_ENABLED_TOOL_IDS: Set<String> = setOf("get_current_time")
    }
}

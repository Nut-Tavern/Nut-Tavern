package com.nuttavern.data.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 客户端内置工具(本地 function calling)的全局配置。
 *
 * 与 [ToolsSettings](工具调用引擎自身的递归/推理行为)分开:这里只管"哪些内置工具被启用、
 * 新会话默认是否带工具、调用前是否需要人工确认"。独立 DataStore 文件,爆炸半径自包含。
 *
 * 启用语义:
 * - [defaultEnabled]:新会话 / toolMode=FOLLOW_GLOBAL 的会话默认是否携带工具;
 * - [enabledToolIds]:被勾选启用的工具 [com.nuttavern.network.ChatTool.id] 集合。未在集合里的
 *   工具即使全局开了也不下发。空集表示"一个都没启用";
 * - [requireApproval]:模型触发工具时是否弹窗人工确认。
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
) {
    companion object {
        /** 全新装机默认启用的内置工具 id。只放无副作用、无需确认的安全工具。 */
        val DEFAULT_ENABLED_TOOL_IDS: Set<String> = setOf("get_current_time")
    }
}

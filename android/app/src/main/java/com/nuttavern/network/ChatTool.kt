package com.nuttavern.network

import com.nuttavern.data.lorebook.Lorebook
import org.json.JSONObject

/**
 * 工具执行时的会话上下文。把"工具运行需要、但不该由模型给"的运行时信息透传给 [ChatTool.execute]。
 *
 * 无依赖工具(如 get_current_time)忽略它即可;需要会话信息的工具(如世界书编辑)按需读取。
 *
 * @property conversationId 当前会话 id;无会话(占位态发送)时为 null。
 * @property sessionLorebooks 当前会话**已启用**的世界书集合(global + 角色 + persona 三来源合并去重)。
 *   世界书工具的作用范围硬边界:只能在这个集合内操作条目,集合外一律拒绝。空集表示当前会话没启用任何世界书。
 */
interface ToolContext {
    val conversationId: String?
    val sessionLorebooks: List<Lorebook>

    companion object {
        /** 不携带任何会话信息的空上下文。用于无工具 / 无会话场景。 */
        val Empty: ToolContext = object : ToolContext {
            override val conversationId: String? = null
            override val sessionLorebooks: List<Lorebook> = emptyList()
        }
    }
}

/**
 * 客户端内置工具(本地 function calling)。
 *
 * 与 [com.nuttavern.data.model.BuiltInTool](Gemini 原生内置工具,由 Provider API 执行)不同:
 * 这里的工具由 App 自己执行,把结果回灌给模型,属于标准 OpenAI function calling 流程。
 *
 * 网络层([ChatApiClient])负责把 [parametersSchema] 注入请求体的 `tools` 字段、解析模型返回的
 * tool_calls、调用 [execute] 拿结果、再回灌发起下一轮请求。工具自身只关心"给参数、出结果"。
 *
 * @property id 工具稳定标识,用于持久化启用状态与设置页枚举。与 [name] 通常一致,但 [id] 永不变,
 *   [name] 是下发给模型的函数名。
 * @property name 下发给模型的函数名,用于匹配 tool_call。必须稳定、语义明确。
 * @property displayName 设置页 / 确认弹窗展示用的中文名。
 * @property description 工具用途说明,模型据此决定是否调用。
 * @property parametersSchema JSON Schema(object 类型)。无参数工具传 `{"type":"object","properties":{}}`。
 * @property needsApproval 该工具本次调用是否需要人工确认。注册表里的值只是默认建议;发送链路会按
 *   用户在内置工具页的"调用前确认"设置复制出本次实际值。
 * @property group 工具分组。同一 [ToolGroup] 下的工具在内置工具选择 UI 里合并成一张卡、用一个总开关
 *   一起启用 / 禁用(底层会话启用集仍按工具 id 存,组开关只是把组内 id 一起增删)。null = 不分组,
 *   单独成卡(如 get_current_time)。
 * @property approvalDetails 高风险工具在人工确认弹窗里展示的可读摘要。返回 null 时 UI 退回展示原始参数。
 * @property execute 执行体。入参是模型给出的实参(解析后的 JSON,无参数时为空对象)与会话上下文
 *   [ToolContext],返回纯文本结果。无依赖工具可忽略 context 参数。
 */
data class ChatTool(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val parametersSchema: JSONObject,
    val needsApproval: Boolean = false,
    val group: ToolGroup? = null,
    val approvalDetails: (suspend (arguments: JSONObject, context: ToolContext) -> ToolApprovalDetails?)? = null,
    val execute: suspend (arguments: JSONObject, context: ToolContext) -> String,
)

/**
 * 工具确认弹窗可读摘要。
 *
 * 用于替代直接展示大段 JSON 参数。工具仍会把原始 JSON 作为兜底展示,摘要只负责让用户确认时能看懂
 * "将做什么"。
 */
data class ToolApprovalDetails(
    val description: String? = null,
    val sections: List<ToolApprovalSection> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class ToolApprovalSection(
    val title: String,
    val lines: List<String>,
)

/**
 * 工具分组。用于内置工具选择 UI 把同类工具合并成一张卡、一个总开关管理。
 *
 * @property id 分组稳定标识。
 * @property displayName 分组展示名。
 * @property description 分组说明(展示在卡片副标题)。
 */
data class ToolGroup(
    val id: String,
    val displayName: String,
    val description: String,
)

/**
 * 工具调用人工确认回调。在执行工具前调用,返回 true 放行、false 拒绝。
 *
 * 由 ViewModel 提供:挂起弹出确认 UI,等用户点按后 resume。为空表示不需要确认(全自动执行)。
 *
 * @param displayName 工具中文名(展示用)
 * @param toolName 工具函数名
 * @param argumentsJson 模型给出的实参 JSON 字符串(兜底展示用)
 * @param details 工具提供的可读确认摘要;为空时 UI 退回显示 argumentsJson
 */
typealias ToolCallApprover = suspend (
    displayName: String,
    toolName: String,
    argumentsJson: String,
    details: ToolApprovalDetails?,
) -> Boolean

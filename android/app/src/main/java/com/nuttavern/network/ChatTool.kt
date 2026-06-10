package com.nuttavern.network

import org.json.JSONObject

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
 * @property execute 执行体。入参是模型给出的实参(解析后的 JSON,无参数时为空对象),返回纯文本结果。
 */
data class ChatTool(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val parametersSchema: JSONObject,
    val needsApproval: Boolean = false,
    val execute: suspend (arguments: JSONObject) -> String,
)

/**
 * 工具调用人工确认回调。在执行工具前调用,返回 true 放行、false 拒绝。
 *
 * 由 ViewModel 提供:挂起弹出确认 UI,等用户点按后 resume。为空表示不需要确认(全自动执行)。
 *
 * @param displayName 工具中文名(展示用)
 * @param toolName 工具函数名
 * @param argumentsJson 模型给出的实参 JSON 字符串(展示用)
 */
typealias ToolCallApprover = suspend (displayName: String, toolName: String, argumentsJson: String) -> Boolean

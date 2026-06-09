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
 * @property name 工具名,下发给模型并用于匹配 tool_call。必须稳定、语义明确。
 * @property description 工具用途说明,模型据此决定是否调用。
 * @property parametersSchema JSON Schema(object 类型)。无参数工具传 `{"type":"object","properties":{}}`。
 * @property execute 执行体。入参是模型给出的实参(解析后的 JSON,无参数时为空对象),返回纯文本结果。
 */
data class ChatTool(
    val name: String,
    val description: String,
    val parametersSchema: JSONObject,
    val execute: suspend (arguments: JSONObject) -> String,
)

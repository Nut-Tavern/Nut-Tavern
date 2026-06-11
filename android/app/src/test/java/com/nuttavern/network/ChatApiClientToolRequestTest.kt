package com.nuttavern.network

import com.nuttavern.data.model.ClaudePromptCacheTtl
import com.nuttavern.data.model.Model
import com.nuttavern.data.model.ThinkingLevel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatApiClientToolRequestTest {
    private val client = ChatApiClient()
    private val testTool = ChatTool(
        id = "get_current_time",
        name = "get_current_time",
        displayName = "获取当前时间",
        description = "获取设备当前的本地日期和时间",
        parametersSchema = JSONObject().put("type", "object").put("properties", JSONObject()),
        execute = { _, _ -> JSONObject().put("ok", true).toString() },
    )

    @Test
    fun openAiResponsesRequestIncludesLocalToolsAndFunctionOutput() {
        val toolCalls = JSONArray().put(
            JSONObject()
                .put("type", "function_call")
                .put("call_id", "call_1")
                .put("name", "get_current_time")
                .put("arguments", "{}"),
        )
        val body = JSONObject(
            buildOpenAIResponsesRequest(
                messages = listOf(
                    ChatMessage(role = "user", content = "现在几点"),
                    ChatMessage(role = "assistant", content = "", toolCalls = toolCalls),
                    ChatMessage(role = "tool", content = "{\"time\":\"10:00\"}", toolCallId = "call_1"),
                ),
                tools = listOf(testTool),
            ),
        )

        val tools = body.getJSONArray("tools")
        assertEquals("function", tools.getJSONObject(0).getString("type"))
        assertEquals("get_current_time", tools.getJSONObject(0).getString("name"))

        val input = body.getJSONArray("input")
        assertEquals("function_call", input.getJSONObject(1).getString("type"))
        assertEquals("function_call_output", input.getJSONObject(2).getString("type"))
        assertEquals("call_1", input.getJSONObject(2).getString("call_id"))
        assertEquals(true, body.getBoolean("stream"))
    }

    @Test
    fun claudeRequestIncludesLocalToolsAndToolResultBlocks() {
        val toolUseBlocks = JSONArray().put(
            JSONObject()
                .put("type", "tool_use")
                .put("id", "toolu_1")
                .put("name", "get_current_time")
                .put("input", JSONObject()),
        )
        val body = JSONObject(
            buildClaudeRequest(
                messages = listOf(
                    ChatMessage(role = "user", content = "现在几点"),
                    ChatMessage(role = "assistant", content = "", toolCalls = toolUseBlocks),
                    ChatMessage(role = "tool", content = "{\"time\":\"10:00\"}", toolCallId = "toolu_1"),
                ),
                tools = listOf(testTool),
            ),
        )

        val tools = body.getJSONArray("tools")
        assertEquals("get_current_time", tools.getJSONObject(0).getString("name"))
        assertTrue(tools.getJSONObject(0).has("input_schema"))

        val messages = body.getJSONArray("messages")
        assertEquals("tool_use", messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type"))
        val toolResult = messages.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", toolResult.getString("type"))
        assertEquals("toolu_1", toolResult.getString("tool_use_id"))
        assertEquals(true, body.getBoolean("stream"))
    }

    @Test
    fun claudeRequestCanGroupMultipleToolResultsInOneUserMessage() {
        val toolResults = JSONArray()
            .put(
                JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", "toolu_1")
                    .put("content", "{\"first\":true}"),
            )
            .put(
                JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", "toolu_2")
                    .put("content", "{\"second\":true}"),
            )

        val body = JSONObject(
            buildClaudeRequest(
                messages = listOf(ChatMessage(role = "tool", content = "", toolCalls = toolResults)),
                tools = listOf(testTool),
            ),
        )

        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals(2, messages.getJSONObject(0).getJSONArray("content").length())
    }

    @Test
    fun geminiRequestIncludesLocalToolsAndFunctionResponseParts() {
        val functionCallParts = JSONArray().put(
            JSONObject().put(
                "functionCall",
                JSONObject()
                    .put("name", "get_current_time")
                    .put("args", JSONObject()),
            ),
        )
        val functionResponseParts = JSONArray().put(
            JSONObject().put(
                "functionResponse",
                JSONObject()
                    .put("name", "get_current_time")
                    .put("response", JSONObject().put("time", "10:00")),
            ),
        )

        val body = JSONObject(
            buildGeminiRequest(
                messages = listOf(
                    ChatMessage(role = "user", content = "现在几点"),
                    ChatMessage(role = "assistant", content = "", toolCalls = functionCallParts),
                    ChatMessage(role = "tool", content = "", toolCalls = functionResponseParts),
                ),
                tools = listOf(testTool),
            ),
        )

        val tools = body.getJSONArray("tools")
        val functionDeclarations = tools.getJSONObject(0).getJSONArray("functionDeclarations")
        val declaration = functionDeclarations.getJSONObject(0)
        assertEquals("get_current_time", declaration.getString("name"))
        assertEquals("获取设备当前的本地日期和时间", declaration.getString("description"))
        assertEquals("object", declaration.getJSONObject("parameters").getString("type"))

        val contents = body.getJSONArray("contents")
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals("user", contents.getJSONObject(2).getString("role"))
        assertEquals("functionCall", contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).keys().next())
        val functionResponse = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("get_current_time", functionResponse.getString("name"))
        assertEquals("10:00", functionResponse.getJSONObject("response").getString("time"))
    }

    @Test
    fun geminiFunctionCallIdRoundTripsThroughProducedHistory() {
        val calls = parseGeminiFunctionCalls(
            JSONObject().put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "functionCall",
                                    JSONObject()
                                        .put("id", "call_123")
                                        .put("name", "get_current_time")
                                        .put("args", JSONObject()),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val assistantMessage = buildGeminiAssistantToolCallMessage(calls)
        val toolMessage = buildGeminiToolResultsMessage(calls.map { it to JSONObject().put("ok", true).toString() })
        val body = JSONObject(
            buildGeminiRequest(
                messages = listOf(assistantMessage, toolMessage),
                tools = listOf(testTool),
            ),
        )

        val contents = body.getJSONArray("contents")
        val functionCall = contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall")
        val functionResponse = contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("call_123", functionCall.getString("id"))
        assertEquals("call_123", functionResponse.getString("id"))
    }

    @Test
    fun geminiSyntheticFunctionCallIdIsNotSentBackToProvider() {
        val calls = parseGeminiFunctionCalls(
            JSONObject().put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "functionCall",
                                    JSONObject()
                                        .put("name", "get_current_time")
                                        .put("args", JSONObject()),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val assistantMessage = buildGeminiAssistantToolCallMessage(calls)
        val toolMessage = buildGeminiToolResultsMessage(calls.map { it to JSONObject().put("ok", true).toString() })
        val body = JSONObject(buildGeminiRequest(messages = listOf(assistantMessage, toolMessage), tools = listOf(testTool)))

        val contents = body.getJSONArray("contents")
        val functionCall = contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall")
        val functionResponse = contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertFalse(functionCall.has("id"))
        assertFalse(functionResponse.has("id"))
    }

    private fun buildOpenAIResponsesRequest(
        messages: List<ChatMessage>,
        tools: List<ChatTool>,
    ): String {
        val method = ChatApiClient::class.java.getDeclaredMethod(
            "buildOpenAIResponsesRequest",
            Model::class.java,
            List::class.java,
            String::class.java,
            ThinkingLevel::class.java,
            GenerationParams::class.java,
            List::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            client,
            Model(id = "model", modelId = "gpt-test"),
            messages,
            null,
            ThinkingLevel.Auto,
            GenerationParams(streamEnabled = false),
            tools,
        ) as String
    }

    private fun buildClaudeRequest(
        messages: List<ChatMessage>,
        tools: List<ChatTool>,
    ): String {
        val method = ChatApiClient::class.java.getDeclaredMethod(
            "buildClaudeRequest",
            Model::class.java,
            List::class.java,
            String::class.java,
            ThinkingLevel::class.java,
            Boolean::class.javaPrimitiveType,
            ClaudePromptCacheTtl::class.java,
            GenerationParams::class.java,
            List::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            client,
            Model(id = "model", modelId = "claude-test"),
            messages,
            null,
            ThinkingLevel.Auto,
            false,
            ClaudePromptCacheTtl.FIVE_MINUTES,
            GenerationParams(streamEnabled = false),
            tools,
        ) as String
    }

    private fun buildGeminiRequest(
        messages: List<ChatMessage>,
        tools: List<ChatTool>,
    ): String {
        val method = ChatApiClient::class.java.getDeclaredMethod(
            "buildGeminiRequest",
            Model::class.java,
            List::class.java,
            String::class.java,
            ThinkingLevel::class.java,
            GenerationParams::class.java,
            List::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            client,
            Model(id = "model", modelId = "gemini-test"),
            messages,
            null,
            ThinkingLevel.Auto,
            GenerationParams(streamEnabled = false),
            tools,
        ) as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseGeminiFunctionCalls(json: JSONObject): List<Any> {
        val method = ChatApiClient::class.java.getDeclaredMethod("parseGeminiFunctionCalls", JSONObject::class.java)
        method.isAccessible = true
        return method.invoke(client, json) as List<Any>
    }

    private fun buildGeminiAssistantToolCallMessage(calls: List<Any>): ChatMessage {
        val method = ChatApiClient::class.java.getDeclaredMethod("geminiAssistantToolCallMessage", List::class.java)
        method.isAccessible = true
        return method.invoke(client, calls) as ChatMessage
    }

    private fun buildGeminiToolResultsMessage(results: List<Pair<Any, String>>): ChatMessage {
        val method = ChatApiClient::class.java.getDeclaredMethod("geminiToolResultsMessage", List::class.java)
        method.isAccessible = true
        return method.invoke(client, results) as ChatMessage
    }
}

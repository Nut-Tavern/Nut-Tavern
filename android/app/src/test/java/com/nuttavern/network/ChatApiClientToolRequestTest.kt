package com.nuttavern.network

import com.nuttavern.data.model.ClaudePromptCacheTtl
import com.nuttavern.data.model.Model
import com.nuttavern.data.model.ThinkingLevel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
        execute = { JSONObject().put("ok", true).toString() },
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
}

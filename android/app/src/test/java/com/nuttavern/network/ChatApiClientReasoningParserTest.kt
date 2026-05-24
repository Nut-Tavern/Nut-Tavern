package com.nuttavern.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatApiClientReasoningParserTest {
    private val client = ChatApiClient()

    @Test
    fun openAiReasoningParserIgnoresJsonNullFields() {
        val delta = JSONObject("""{"content":null,"reasoning_content":null}""")

        assertEquals("", optCleanString(delta, "content"))
        assertEquals("", parseOpenAIReasoningDelta(delta))
    }

    @Test
    fun openAiReasoningParserCollectsKnownReasoningFields() {
        val delta = JSONObject(
            """
            {
              "reasoning_content": "first ",
              "reasoning": "second ",
              "reasoningContent": "third ",
              "thinking": "fourth ",
              "thinking_content": "fifth"
            }
            """.trimIndent(),
        )

        assertEquals("first second third fourth fifth", parseOpenAIReasoningDelta(delta))
    }

    @Test
    fun claudeReasoningParserReadsThinkingDeltaOnly() {
        val thinkingDelta = JSONObject("""{"type":"thinking_delta","thinking":"think"}""")
        val textDelta = JSONObject("""{"type":"text_delta","text":"answer"}""")

        assertEquals("think", parseClaudeReasoningDelta(thinkingDelta))
        assertEquals("", parseClaudeReasoningDelta(textDelta))
    }

    @Test
    fun geminiParserSplitsThoughtPartsFromAnswerParts() {
        val json = JSONObject(
            """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {"text": "reason ", "thought": true},
                      {"text": "answer"}
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val chunk = parseGeminiChunk(json)

        assertEquals("answer", chunk.content)
        assertEquals("reason ", chunk.reasoningContent)
    }

    @Test
    fun geminiParserKeepsNullLinesInsideRealText() {
        val json = JSONObject(
            """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {"text": "value\nnull\nend"}
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("value\nnull\nend", parseGeminiChunk(json).content)
    }

    private fun parseOpenAIReasoningDelta(delta: JSONObject): String {
        val method = ChatApiClient::class.java.getDeclaredMethod(
            "parseOpenAIReasoningDelta",
            JSONObject::class.java,
        )
        method.isAccessible = true
        return method.invoke(client, delta) as String
    }

    private fun parseClaudeReasoningDelta(delta: JSONObject): String {
        val method = ChatApiClient::class.java.getDeclaredMethod(
            "parseClaudeReasoningDelta",
            JSONObject::class.java,
        )
        method.isAccessible = true
        return method.invoke(client, delta) as String
    }

    private fun parseGeminiChunk(json: JSONObject): ChatStreamChunk {
        val method = ChatApiClient::class.java.getDeclaredMethod(
            "parseGeminiChunk",
            JSONObject::class.java,
        )
        method.isAccessible = true
        return method.invoke(client, json) as ChatStreamChunk
    }

    private fun optCleanString(json: JSONObject, fieldName: String): String {
        val method = ChatApiClient::class.java.getDeclaredMethod(
            "optCleanString",
            JSONObject::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(client, json, fieldName) as String
    }
}

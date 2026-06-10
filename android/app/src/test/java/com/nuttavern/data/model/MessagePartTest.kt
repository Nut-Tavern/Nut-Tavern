package com.nuttavern.data.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePartTest {

    // 与 ConversationRepository.partsJsonCodec 保持一致的配置:多态判别字段用 type,默认值写盘。
    private val codec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    @Test
    fun parts_polymorphicRoundTrip() {
        val parts: List<MessagePart> = listOf(
            MessagePart.Reasoning(text = "先想一下", durationMillis = 1500L),
            MessagePart.ToolCall(
                toolCallId = "call_1",
                toolName = "get_current_time",
                arguments = """{"timezone":"Asia/Shanghai"}""",
                result = "2026-06-10T20:00:00+08:00",
            ),
            MessagePart.Text(text = "现在是晚上八点。"),
        )

        val json = codec.encodeToString(ListSerializer(MessagePart.serializer()), parts)
        val decoded = codec.decodeFromString(ListSerializer(MessagePart.serializer()), json)

        assertEquals(parts, decoded)
    }

    @Test
    fun toolCall_defaultsArePersisted() {
        val running = MessagePart.ToolCall(
            toolCallId = "call_2",
            toolName = "list_session_lorebooks",
            arguments = "{}",
        )

        val json = codec.encodeToString(MessagePart.serializer(), running)

        // 执行中默认值(result 空 / denied false)必须写盘,保证 round-trip 稳定。
        assertTrue(json.contains("\"result\":\"\""))
        assertTrue(json.contains("\"denied\":false"))
        assertEquals(running, codec.decodeFromString(MessagePart.serializer(), json))
    }

    @Test
    fun toolCall_statusDerivedFromFields() {
        val running = MessagePart.ToolCall("c", "t", "{}")
        val done = running.copy(result = "ok")
        val denied = running.copy(result = "用户拒绝", denied = true)

        assertTrue(running.result.isEmpty())
        assertTrue(done.result.isNotEmpty())
        assertTrue(denied.denied)
    }

    @Test
    fun unknownPartType_throwsSoCallerCanFallBack() {
        // ignoreUnknownKeys 只忽略未知字段(key),不忽略未知多态判别值(type)。
        // 遇到未来新增、当前版本不认识的 part type,解码会抛 SerializationException,
        // 由调用方(ConversationRepository.decodeParts)兜底退化为空 parts。此测试锁住该现状行为。
        val json = """[{"type":"future_part","payload":"x"}]"""
        assertThrows(SerializationException::class.java) {
            codec.decodeFromString(ListSerializer(MessagePart.serializer()), json)
        }
    }

    @Test
    fun malformedJson_throwsSoCallerCanFallBack() {
        // 结构损坏(非法 JSON)同样抛异常,由 decodeParts 的 runCatching 兜底退化为空 parts。
        val brokenJson = """[{"type":"text","text":"""  // 截断的 JSON
        assertThrows(SerializationException::class.java) {
            codec.decodeFromString(ListSerializer(MessagePart.serializer()), brokenJson)
        }
    }
}

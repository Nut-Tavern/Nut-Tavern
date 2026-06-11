package com.nuttavern.prompt

import com.nuttavern.data.character.Character
import com.nuttavern.data.persona.PersonaPosition
import com.nuttavern.data.persona.PersonaRole
import com.nuttavern.data.persona.UserPersona
import com.nuttavern.data.preset.Preset
import com.nuttavern.network.ChatMessage
import com.nuttavern.regex.RegexEngine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PromptComposer 节点行为单测。
 *
 * 不接 Hilt / Room,直接构造 [PromptComposer] + [PlaceholderResolver] 验证拼接结果。
 *
 * 节点覆盖矩阵参见 docs/modules/prompt-composer.md。
 *
 * 所有用例都使用 [emptyPreset] —— 完全空骨架(无 prompts / 无 promptOrder),
 * 让测试聚焦于"角色 / 用户身份 / 历史消息 / 占位符 / PHI"等节点本身。
 * 预设字段(prompt_order / marker / RELATIVE / ABSOLUTE 注入等)的覆盖另起一组用例,
 * 显式构造预设,与本组隔离。
 */
class PromptComposerTest {

    private val placeholderResolver = PlaceholderResolver()
    private val composer = PromptComposer(
        placeholderResolver = placeholderResolver,
        regexEngine = RegexEngine(placeholderResolver),
    )

    private fun emptyPreset(): Preset = Preset(prompts = emptyList(), promptOrder = emptyList())

    @Test
    fun emptyInputProducesEmptySystemAndNoMessages() {
        val output = composer.compose(
            PromptComposerInput(
                userMessage = null,
                history = emptyList(),
                character = null,
                userPersona = null,
                preset = emptyPreset(),
            )
        )
        assertNull(output.systemPrompt)
        assertTrue(output.messages.isEmpty())
    }

    @Test
    fun characterFieldsAppearInSystemPromptInOrder() {
        val character = Character(
            id = "alice",
            name = "Alice",
            description = "wise tavern keeper",
            personality = "calm",
            scenario = "rainy night at the tavern",
            systemPrompt = "stay in character",
        )
        val output = composer.compose(
            PromptComposerInput(
                userMessage = "hi",
                history = emptyList(),
                character = character,
                userPersona = null,
                preset = Preset.default(),
            )
        )
        val system = output.systemPrompt!!
        // 默认预设的 prompt_order 顺序里:
        //   main(被 character.systemPrompt 覆盖) → personaDescription → charDescription
        //     → charPersonality → scenario → ...
        // 所以拼出来的 system 段按这个顺序累积。
        val expectedOrder = listOf(
            "stay in character",
            "wise tavern keeper",
            "calm",
            "rainy night at the tavern",
        )
        var lastIndex = -1
        for (segment in expectedOrder) {
            val idx = system.indexOf(segment)
            assertTrue("missing segment '$segment' in system prompt:\n$system", idx >= 0)
            assertTrue("segment '$segment' out of order in:\n$system", idx > lastIndex)
            lastIndex = idx
        }
    }

    @Test
    fun userMessageBecomesLastMessage() {
        val output = composer.compose(
            PromptComposerInput(
                userMessage = "hello",
                history = listOf(
                    HistoryMessage("user", "earlier"),
                    HistoryMessage("assistant", "previous reply"),
                ),
                character = null,
                userPersona = null,
                preset = emptyPreset(),
            )
        )
        assertEquals(3, output.messages.size)
        val last = output.messages.last()
        assertEquals("user", last.role)
        assertEquals("hello", last.content)
    }

    @Test
    fun nullUserMessageKeepsHistoryAsIs() {
        val output = composer.compose(
            PromptComposerInput(
                userMessage = null,
                history = listOf(
                    HistoryMessage("user", "u1"),
                    HistoryMessage("assistant", "a1"),
                    HistoryMessage("user", "u2"),
                ),
                character = null,
                userPersona = null,
                preset = emptyPreset(),
            )
        )
        assertEquals(3, output.messages.size)
        assertEquals("u2", output.messages.last().content)
    }

    @Test
    fun postHistoryInstructionsAppendToLastUserMessage() {
        val character = Character(
            name = "Alice",
            postHistoryInstructions = "respond in haiku",
        )
        val output = composer.compose(
            PromptComposerInput(
                userMessage = "tell me a story",
                history = emptyList(),
                character = character,
                userPersona = null,
                preset = emptyPreset(),
            )
        )
        val lastUserContent = output.messages.last { it.role == "user" }.content
        assertTrue(
            "PHI not appended to last user message: $lastUserContent",
            lastUserContent.contains("tell me a story") &&
                lastUserContent.contains("respond in haiku"),
        )
    }

    @Test
    fun postHistoryInstructionsBecomesSystemMessageWhenNoUserMessage() {
        val character = Character(
            name = "Alice",
            postHistoryInstructions = "respond in haiku",
        )
        val output = composer.compose(
            PromptComposerInput(
                userMessage = null,
                history = listOf(HistoryMessage("assistant", "greeting")),
                character = character,
                userPersona = null,
                preset = emptyPreset(),
            )
        )
        val tail = output.messages.last()
        assertEquals("system", tail.role)
        assertEquals("respond in haiku", tail.content)
    }

    @Test
    fun mesExampleIsSplitByStartIntoSystemMessages() {
        // mes_example 解析依赖预设里的 dialogueExamples marker — 用 [Preset.default] 让它启用。
        val character = Character(
            name = "Alice",
            messageExample = """
                <START>
                Alice: hello
                <START>
                Alice: another example
            """.trimIndent(),
        )
        val output = composer.compose(
            PromptComposerInput(
                userMessage = null,
                history = emptyList(),
                character = character,
                userPersona = null,
                preset = Preset.default(),
            )
        )
        val examples = output.messages.filter {
            it.role == "system" && it.content.contains("Alice:")
        }
        assertEquals(2, examples.size)
        assertTrue(examples[0].content.contains("Alice: hello"))
        assertTrue(examples[1].content.contains("Alice: another example"))
    }

    @Test
    fun personaInPromptModeAppendsToSystemPrompt() {
        val persona = UserPersona(
            id = "p1",
            name = "Bob",
            description = "curious traveler",
            position = PersonaPosition.IN_PROMPT,
        )
        val output = composer.compose(
            PromptComposerInput(
                userMessage = "hi",
                history = emptyList(),
                character = null,
                userPersona = persona,
                preset = Preset.default(),
            )
        )
        assertTrue(
            "persona description missing in system prompt: ${output.systemPrompt}",
            output.systemPrompt!!.contains("curious traveler"),
        )
    }

    @Test
    fun personaAtDepthInsertsBeforeRecentHistory() {
        val persona = UserPersona(
            id = "p1",
            name = "Bob",
            description = "curious traveler",
            position = PersonaPosition.AT_DEPTH,
            depth = 1,
            role = PersonaRole.SYSTEM,
        )
        val history = listOf(
            HistoryMessage("user", "u1"),
            HistoryMessage("assistant", "a1"),
            HistoryMessage("user", "u2"),
        )
        val output = composer.compose(
            PromptComposerInput(
                userMessage = "hi",
                history = history,
                character = null,
                userPersona = persona,
                preset = emptyPreset(),
            )
        )
        // depth = 1: 在最末尾消息前面插入(末尾是新追加的 user "hi",所以 persona 应在 "hi" 之前)。
        // 索引序列预期: u1 / a1 / u2 / [persona] / hi
        val targetIndex = output.messages.indexOfFirst {
            it.role == "system" && it.content.contains("curious traveler")
        }
        assertTrue("persona block not found in messages", targetIndex >= 0)
        val lastIndex = output.messages.lastIndex
        assertEquals(lastIndex - 1, targetIndex)
    }

    @Test
    fun nonePersonaIsCompletelySkipped() {
        val output = composer.compose(
            PromptComposerInput(
                userMessage = "hi",
                history = emptyList(),
                character = null,
                userPersona = UserPersona.None,
                preset = emptyPreset(),
            )
        )
        assertNull(output.systemPrompt)
        // 只剩用户消息一条,不应有 persona 注入。
        assertEquals(1, output.messages.size)
        assertEquals("user", output.messages.first().role)
    }

    @Test
    fun placeholdersInSystemAndMessagesAreResolved() {
        val character = Character(
            name = "Alice",
            description = "I am {{char}}, talking to {{user}}",
        )
        val persona = UserPersona(
            id = "p1",
            name = "Bob",
            description = "curious traveler",
        )
        val output = composer.compose(
            PromptComposerInput(
                userMessage = "Hello {{char}}",
                history = emptyList(),
                character = character,
                userPersona = persona,
                preset = Preset.default(),
            )
        )
        assertTrue(
            "system did not resolve placeholders: ${output.systemPrompt}",
            output.systemPrompt!!.contains("I am Alice, talking to Bob"),
        )
        assertEquals("Hello Alice", output.messages.last().content)
    }

    @Test
    fun charDepthPromptPlaceholderReadsCharacterExtension() {
        val character = Character(
            name = "Alice",
            description = "Depth: {{charDepthPrompt}}",
            extensions = buildJsonObject {
                put("depth_prompt", buildJsonObject {
                    put("prompt", "hidden depth prompt")
                })
            },
        )

        val output = composer.compose(
            PromptComposerInput(
                userMessage = null,
                history = emptyList(),
                character = character,
                userPersona = null,
                preset = Preset.default(),
            )
        )

        assertTrue(
            "charDepthPrompt was not resolved from extensions: ${output.systemPrompt}",
            output.systemPrompt!!.contains("Depth: hidden depth prompt"),
        )
    }

    @Test
    fun unknownPlaceholdersAreLeftIntact() {
        val output = composer.compose(
            PromptComposerInput(
                userMessage = "Hello {{user}}",
                history = emptyList(),
                character = null,
                userPersona = null,
                preset = emptyPreset(),
            )
        )
        // 没传 user,占位符按 PlaceholderResolver 偏差登记保持原样。
        val last = output.messages.last()
        assertEquals("Hello {{user}}", last.content)
    }

    @Test
    fun diagnosticsRemainEmptyOnHappyPath() {
        val output = composer.compose(
            PromptComposerInput(
                userMessage = "hi",
                history = emptyList(),
                character = Character(name = "Alice", description = "wise"),
                userPersona = null,
                preset = Preset.default(),
            )
        )
        assertTrue(
            "happy path produced diagnostics: ${output.diagnostics}",
            output.diagnostics.isEmpty(),
        )
        assertNotNull(output.systemPrompt)
    }

    @Test
    fun characterNameOnlyPersonaUsesIAmFallback() {
        val persona = UserPersona(id = "p1", name = "Bob")
        val output = composer.compose(
            PromptComposerInput(
                userMessage = null,
                history = emptyList(),
                character = null,
                userPersona = persona,
                preset = Preset.default(),
            )
        )
        assertTrue(
            "I am fallback missing in system: ${output.systemPrompt}",
            output.systemPrompt!!.contains("I am Bob."),
        )
    }

    @Test
    fun personaSystemFallbackAppliesWhenPersonaDescriptionMarkerMissing() {
        // 预设 prompt_order 里没有 personaDescription marker(或被禁用),system 类 persona
        // 仍应由 PromptComposer 兜底拼到 system 段。对齐 personaSystemBlock KDoc:
        // "Nut Tavern 的 persona 模块独立于预设,默认应该工作,预设禁用了 marker 也不能让 persona 消失"。
        val persona = UserPersona(
            id = "p1",
            name = "Bob",
            description = "curious traveler",
            position = PersonaPosition.IN_PROMPT,
        )
        val output = composer.compose(
            PromptComposerInput(
                userMessage = "hi",
                history = emptyList(),
                character = null,
                userPersona = persona,
                preset = emptyPreset(), // 空预设,无任何 marker
            )
        )
        assertTrue(
            "persona description missing when marker absent: ${output.systemPrompt}",
            output.systemPrompt!!.contains("curious traveler"),
        )
    }
}

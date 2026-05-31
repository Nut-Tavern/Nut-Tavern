package com.nuttavern.prompt

import com.nuttavern.data.character.Character
import com.nuttavern.data.preset.PresetSillyTavernCodec
import com.nuttavern.regex.RegexEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端到端验证:**导入酒馆预设 → PromptComposer 拼接 → 上下文符合酒馆 chat completion 行为**。
 *
 * 与 [PromptComposerTest](聚焦单节点)和 [com.nuttavern.data.preset.PresetSillyTavernCodecTest]
 * (聚焦字段映射)互补:这里把"导入 codec + 拼接管线"串起来,证明导入的真实酒馆预设能正常拼进
 * 上下文,且顺序 / marker / 参数与酒馆一致。
 *
 * 对齐参考:酒馆 `default/content/presets/openai/Default.json`(prompt_order 100001 活跃顺序)
 * + `PromptManager.js` 拼接策略。
 */
class PresetImportPipelineTest {

    private val placeholderResolver = PlaceholderResolver()
    private val composer = PromptComposer(
        placeholderResolver = placeholderResolver,
        regexEngine = RegexEngine(placeholderResolver),
    )

    /**
     * 一份贴近真实的酒馆导出预设:裸数字枚举 + 100000(legacy)/100001(active) 双槽 +
     * 连接字段 + 与酒馆 Default 同构的 prompt_order(含 personaDescription marker)。
     */
    private val sillyTavernPreset = """
        {
            "temperature": 0.85,
            "top_p": 0.95,
            "openai_max_tokens": 400,
            "names_behavior": 0,
            "continue_postfix": " ",
            "chat_completion_source": "openai",
            "openai_model": "gpt-4o",
            "reverse_proxy": "https://proxy.example.com",
            "prompts": [
                { "identifier": "main", "name": "Main Prompt", "role": "system",
                  "content": "Write {{char}}'s next reply.", "system_prompt": true },
                { "identifier": "worldInfoBefore", "name": "World Info (before)", "system_prompt": true, "marker": true },
                { "identifier": "personaDescription", "name": "Persona Description", "system_prompt": true, "marker": true },
                { "identifier": "charDescription", "name": "Char Description", "system_prompt": true, "marker": true },
                { "identifier": "charPersonality", "name": "Char Personality", "system_prompt": true, "marker": true },
                { "identifier": "scenario", "name": "Scenario", "system_prompt": true, "marker": true },
                { "identifier": "chatHistory", "name": "Chat History", "system_prompt": true, "marker": true },
                { "identifier": "jailbreak", "name": "Post-History Instructions", "role": "system",
                  "content": "", "system_prompt": true }
            ],
            "prompt_order": [
                {
                    "character_id": 100000,
                    "order": [ { "identifier": "main", "enabled": true } ]
                },
                {
                    "character_id": 100001,
                    "order": [
                        { "identifier": "main", "enabled": true },
                        { "identifier": "charDescription", "enabled": true },
                        { "identifier": "charPersonality", "enabled": true },
                        { "identifier": "scenario", "enabled": true },
                        { "identifier": "chatHistory", "enabled": true }
                    ]
                }
            ]
        }
    """.trimIndent()

    private val character = Character(
        id = "char-1",
        name = "Alice",
        description = "a wise tavern keeper",
        personality = "calm and witty",
        scenario = "a rainy night at the tavern",
    )

    @Test
    fun importedPresetComposesSystemSegmentInActiveOrder() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(sillyTavernPreset, "Imported")

        val output = composer.compose(
            PromptComposerInput(
                userMessage = "hello",
                history = emptyList(),
                character = character,
                userPersona = null,
                preset = preset,
            )
        )

        val system = output.systemPrompt
            ?: error("imported preset produced no system prompt")

        // 酒馆 chat completion 用 100001(active)顺序:
        //   main → charDescription → charPersonality → scenario(chatHistory 是 marker,不进 system 文本)
        val expectedOrder = listOf(
            "Write Alice's next reply.", // main,占位符已解析
            "a wise tavern keeper",       // charDescription
            "calm and witty",             // charPersonality
            "a rainy night at the tavern", // scenario
        )
        var lastIndex = -1
        for (segment in expectedOrder) {
            val idx = system.indexOf(segment)
            assertTrue("缺段 '$segment':\n$system", idx >= 0)
            assertTrue("段 '$segment' 顺序错乱:\n$system", idx > lastIndex)
            lastIndex = idx
        }
    }

    @Test
    fun importedPresetPlacesUserMessageLastAndHistoryBefore() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(sillyTavernPreset, "Imported")

        val output = composer.compose(
            PromptComposerInput(
                userMessage = "what's on the menu?",
                history = listOf(
                    HistoryMessage("user", "good evening"),
                    HistoryMessage("assistant", "welcome, traveler"),
                ),
                character = character,
                userPersona = null,
                preset = preset,
            )
        )

        // 系统段单独在 output.systemPrompt;output.messages 是 history + 用户当前消息,
        // 用户当前消息在末尾(对齐酒馆 messages 组装:system 段由 ChatApiClient 作为首条 system 注入)。
        assertTrue("系统段缺失", !output.systemPrompt.isNullOrBlank())
        val last = output.messages.last()
        assertEquals("user", last.role)
        assertEquals("what's on the menu?", last.content)
        assertTrue(
            "history 未拼入:\n${output.messages}",
            output.messages.any { it.content == "welcome, traveler" },
        )
        // history 在用户当前消息之前
        val historyIdx = output.messages.indexOfFirst { it.content == "welcome, traveler" }
        assertTrue("history 应在用户当前消息之前", historyIdx < output.messages.lastIndex)
    }

    @Test
    fun importedPresetMapsGenerationParams() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(sillyTavernPreset, "Imported")

        val output = composer.compose(
            PromptComposerInput(
                userMessage = "hi",
                history = emptyList(),
                character = character,
                userPersona = null,
                preset = preset,
            )
        )

        val params = output.generationParams
        // 采样参数从导入的预设映射到 GenerationParams,直接透传到请求体(与酒馆一致)。
        assertEquals(0.85, params.temperature ?: 0.0, 0.0001)
        assertEquals(0.95, params.topP ?: 0.0, 0.0001)
        assertEquals(400, params.maxTokens)
    }

    @Test
    fun importedPresetHasCleanDiagnostics() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(sillyTavernPreset, "Imported")

        val output = composer.compose(
            PromptComposerInput(
                userMessage = "hi",
                history = emptyList(),
                character = character,
                userPersona = null,
                preset = preset,
            )
        )

        // 导入的预设拼接不应产生任何节点失败/跳过诊断。
        assertTrue("导入预设拼接产生诊断: ${output.diagnostics}", output.diagnostics.isEmpty())
    }

    @Test
    fun characterOverridesMainPromptOnImportedPreset() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(sillyTavernPreset, "Imported")
        val overriding = character.copy(systemPrompt = "Always stay in character.")

        val output = composer.compose(
            PromptComposerInput(
                userMessage = "hi",
                history = emptyList(),
                character = overriding,
                userPersona = null,
                preset = preset,
            )
        )

        val system = output.systemPrompt!!
        // 角色卡 system_prompt 覆盖 main 条目内容(对齐酒馆 forbid_overrides 缺省可覆盖行为)。
        assertTrue("main 未被角色 system_prompt 覆盖:\n$system", system.contains("Always stay in character."))
    }
}

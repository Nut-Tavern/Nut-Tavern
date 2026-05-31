package com.nuttavern.data.preset

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetSillyTavernCodecTest {

    /**
     * 真实 ST 预设:裸数字枚举 + prompt_order 100000(legacy,2 项) / 100001(active,2 项不同启停)
     * + 一条角色定制(character_id=5) + 连接字段。
     *
     * 酒馆 chat completion 实际消费 100001(active),100000 是 legacy 不读。两条故意做成不同启停,
     * 用于断言 codec 取的是 active(100001)那条。
     */
    private val sillyTavernPreset = """
        {
            "temperature": 0.9,
            "frequency_penalty": 0.2,
            "openai_max_tokens": 512,
            "names_behavior": 2,
            "continue_postfix": " ",
            "chat_completion_source": "openai",
            "openai_model": "gpt-4o",
            "reverse_proxy": "https://example.com",
            "reasoning_effort": "high",
            "prompts": [
                {
                    "identifier": "main",
                    "name": "Main Prompt",
                    "role": "system",
                    "content": "Write {{char}}'s reply.",
                    "system_prompt": true,
                    "injection_position": 1,
                    "injection_depth": 3
                },
                {
                    "identifier": "jailbreak",
                    "name": "Post-History",
                    "role": "system",
                    "content": "Stay in character.",
                    "system_prompt": true
                }
            ],
            "prompt_order": [
                {
                    "character_id": 100000,
                    "order": [
                        { "identifier": "main", "enabled": false }
                    ]
                },
                {
                    "character_id": 100001,
                    "order": [
                        { "identifier": "main", "enabled": true },
                        { "identifier": "jailbreak", "enabled": false }
                    ]
                },
                {
                    "character_id": 5,
                    "order": [
                        { "identifier": "main", "enabled": true }
                    ]
                }
            ]
        }
    """.trimIndent()

    @Test
    fun importParsesNumericEnumsAndGenerationParams() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(sillyTavernPreset, "My Preset")

        assertEquals("My Preset", preset.name)
        assertEquals(0.9, preset.temperature, 0.0001)
        assertEquals(0.2, preset.frequencyPenalty, 0.0001)
        assertEquals(512, preset.openaiMaxTokens)
        assertEquals(NamesBehavior.CONTENT, preset.namesBehavior)
        assertEquals(ContinuePostfix.SPACE, preset.continuePostfix)
    }

    @Test
    fun importDropsConnectionAndGlobalBehaviorFields() {
        // chat_completion_source / openai_model / reverse_proxy / reasoning_effort 经 ignoreUnknownKeys
        // 静默丢弃,不进 Preset。这里通过 round-trip 导出确认它们没被保留。
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(sillyTavernPreset, "p")
        val exported = Json.parseToJsonElement(PresetSillyTavernCodec.encodeToSillyTavern(preset)) as JsonObject

        assertNull(exported["chat_completion_source"])
        assertNull(exported["openai_model"])
        assertNull(exported["reverse_proxy"])
        assertNull(exported["reasoning_effort"])
    }

    @Test
    fun importParsesPromptInjectionPosition() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(sillyTavernPreset, "p")
        val main = preset.prompts.first { it.identifier == "main" }
        val jailbreak = preset.prompts.first { it.identifier == "jailbreak" }

        assertEquals(InjectionPosition.ABSOLUTE, main.injectionPosition)
        assertEquals(3, main.injectionDepth)
        // 缺 injection_position 的条目走默认 RELATIVE
        assertEquals(InjectionPosition.RELATIVE, jailbreak.injectionPosition)
    }

    @Test
    fun importTakesActiveOrder100001FillsBothGlobalAndGroupDropsCharacterCustom() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(sillyTavernPreset, "p")

        val ids = preset.promptOrder.map { it.characterId }
        // 全局 + 群聊样板两槽都填,角色定制(=5)丢弃
        assertEquals(2, preset.promptOrder.size)
        assertTrue(PromptOrderForCharacter.GLOBAL_CHARACTER_ID in ids)
        assertTrue(PromptOrderForCharacter.GROUP_CHARACTER_ID in ids)

        // __global__ 取的是酒馆 active(100001)那条:2 项 main(启)+jailbreak(停),
        // 而非 legacy(100000)的 1 项 main(停)。
        val global = preset.promptOrder.first { it.characterId == PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
        assertEquals(2, global.order.size)
        assertEquals("main", global.order[0].identifier)
        assertTrue(global.order[0].enabled)
        assertEquals("jailbreak", global.order[1].identifier)
        assertFalse(global.order[1].enabled)

        // group 样板与 global 一致(对齐 Preset.default 结构)
        val group = preset.promptOrder.first { it.characterId == PromptOrderForCharacter.GROUP_CHARACTER_ID }
        assertEquals(global.order, group.order)
    }

    /** 缺 100001、只有 100000(legacy)时回退取 100000。 */
    @Test
    fun importFallsBackToLegacy100000WhenActiveMissing() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(
            """
            {
                "prompt_order": [
                    { "character_id": 100000, "order": [ { "identifier": "main", "enabled": true } ] }
                ]
            }
            """.trimIndent(),
            "p",
        )

        val global = preset.promptOrder.first { it.characterId == PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
        assertEquals(1, global.order.size)
        assertEquals("main", global.order[0].identifier)
    }

    @Test
    fun importAssignsFreshIdAndTimestamps() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(sillyTavernPreset, "p")

        assertTrue(preset.id.isNotBlank())
        assertFalse(preset.isBuiltInDefault)
        assertTrue(preset.createdAt > 0)
        assertTrue(preset.updatedAt > 0)
    }

    @Test
    fun exportWritesBareNumericEnums() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(sillyTavernPreset, "p")
        val exported = Json.parseToJsonElement(PresetSillyTavernCodec.encodeToSillyTavern(preset)) as JsonObject

        // names_behavior 裸数字 2
        assertEquals(2, (exported["names_behavior"] as JsonPrimitive).intOrNull)
        assertFalse((exported["names_behavior"] as JsonPrimitive).isString)

        // prompts[].injection_position 裸数字 1
        val prompts = exported["prompts"] as JsonArray
        val main = prompts.map { it as JsonObject }.first {
            (it["identifier"] as JsonPrimitive).content == "main"
        }
        assertEquals(1, (main["injection_position"] as JsonPrimitive).intOrNull)
        assertFalse((main["injection_position"] as JsonPrimitive).isString)
    }

    @Test
    fun exportWritesNumericCharacterIdAndStripsInternalKeys() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(sillyTavernPreset, "p")
        val exported = Json.parseToJsonElement(PresetSillyTavernCodec.encodeToSillyTavern(preset)) as JsonObject

        val order = exported["prompt_order"] as JsonArray
        val charIds = order.map { (it as JsonObject)["character_id"] as JsonPrimitive }.mapNotNull { it.intOrNull }
        // __global__ 同时写 100000 + 100001,保证酒馆 chat completion(读 100001)拿到正确顺序
        assertEquals(listOf(100000, 100001), charIds)

        // 两条内容一致
        val orders = order.map { (it as JsonObject)["order"] }
        assertEquals(orders[0], orders[1])

        // 内部专用键不写进酒馆 JSON
        assertNull(exported["id"])
        assertNull(exported["name"])
        assertNull(exported["createdAt"])
        assertNull(exported["updatedAt"])
    }

    @Test
    fun roundTripPreservesPromptContentAndOrder() {
        val imported = PresetSillyTavernCodec.decodeFromSillyTavern(sillyTavernPreset, "p")
        val exportedText = PresetSillyTavernCodec.encodeToSillyTavern(imported)
        val reimported = PresetSillyTavernCodec.decodeFromSillyTavern(exportedText, "p2")

        assertEquals(imported.temperature, reimported.temperature, 0.0001)
        assertEquals(imported.namesBehavior, reimported.namesBehavior)
        assertEquals(imported.prompts.map { it.identifier }, reimported.prompts.map { it.identifier })
        assertEquals(
            imported.prompts.first { it.identifier == "main" }.injectionPosition,
            reimported.prompts.first { it.identifier == "main" }.injectionPosition,
        )
        assertEquals(
            imported.promptOrder.map { it.characterId }.toSet(),
            reimported.promptOrder.map { it.characterId }.toSet(),
        )
        val globalBefore = imported.promptOrder.first { it.characterId == PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
        val globalAfter = reimported.promptOrder.first { it.characterId == PromptOrderForCharacter.GLOBAL_CHARACTER_ID }
        assertEquals(globalBefore.order, globalAfter.order)
    }

    @Test
    fun importHandlesMinimalPresetWithoutCrash() {
        val minimal = PresetSillyTavernCodec.decodeFromSillyTavern(
            """{ "temperature": 1.0 }""",
            "minimal",
        )

        assertEquals(1.0, minimal.temperature, 0.0001)
        assertEquals(emptyList<PromptEntry>(), minimal.prompts)
        assertEquals(emptyList<PromptOrderForCharacter>(), minimal.promptOrder)
        assertNotNull(minimal.id)
    }

    @Test
    fun importRejectsNonObjectJson() {
        val result = runCatching {
            PresetSillyTavernCodec.decodeFromSillyTavern("""[1, 2, 3]""", "bad")
        }
        assertTrue(result.isFailure)
    }

    /** NamesBehavior.NONE 是 -1 负值,裸数字 → 枚举 → 裸数字往返正确。 */
    @Test
    fun namesBehaviorNegativeValueRoundTrips() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(
            """{ "names_behavior": -1 }""",
            "p",
        )
        assertEquals(NamesBehavior.NONE, preset.namesBehavior)

        val exported = Json.parseToJsonElement(PresetSillyTavernCodec.encodeToSillyTavern(preset)) as JsonObject
        assertEquals(-1, (exported["names_behavior"] as JsonPrimitive).intOrNull)
    }

    /** ContinuePostfix 换行值是字符串,codec 不转换,往返保持。 */
    @Test
    fun continuePostfixNewlineRoundTrips() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(
            "{ \"continue_postfix\": \"\\n\" }",
            "p",
        )
        assertEquals(ContinuePostfix.NEWLINE, preset.continuePostfix)

        val exported = Json.parseToJsonElement(PresetSillyTavernCodec.encodeToSillyTavern(preset)) as JsonObject
        assertEquals("\n", (exported["continue_postfix"] as JsonPrimitive).content)
    }

    /** 空 prompt_order 导入退化为空列表,不崩、不留悬空槽。 */
    @Test
    fun importHandlesEmptyPromptOrder() {
        val preset = PresetSillyTavernCodec.decodeFromSillyTavern(
            """{ "prompt_order": [] }""",
            "p",
        )
        assertEquals(emptyList<PromptOrderForCharacter>(), preset.promptOrder)
    }
}

package com.nuttavern.lorebook

import com.nuttavern.data.character.CharacterBookEntry
import com.nuttavern.data.character.toLorebookEntry
import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.WiPosition
import com.nuttavern.data.lorebook.WiRole
import com.nuttavern.prompt.HistoryMessage
import com.nuttavern.prompt.PlaceholderResolver
import com.nuttavern.prompt.PromptComposer
import com.nuttavern.prompt.PromptComposerInput
import com.nuttavern.prompt.TokenCounter
import com.nuttavern.regex.RegexEngine
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端到端验证:**V3 角色内嵌世界书(character_book)→ toLorebookEntry 转换 → LorebookEngine 激活
 * → PromptComposer 拼接 → 上下文符合酒馆行为**。
 *
 * 覆盖 character_book 导入提取链路:角色卡导入时 character_book 条目经 toLorebookEntry 提取成
 * 独立世界书条目(角色世界书重构后运行时不再直接消费 character_book,但导入提取仍走这条转换)。
 * 重点证明 extensions 序列化(高级字段在 extensions 子对象)转换不丢:position / depth / role
 * 等从 extensions 读出后,激活与注入位置与酒馆一致。
 *
 * 与 [LorebookEngineTest](聚焦激活算法本身,用内部 LorebookEntry)互补:这里从酒馆 JSON 结构出发,
 * 串起"导入转换 + 激活 + 拼接"全链路。
 */
class CharacterBookActivationPipelineTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val engine = LorebookEngine(TokenCounter())
    private val placeholderResolver = PlaceholderResolver()
    private val composer = PromptComposer(
        placeholderResolver = placeholderResolver,
        regexEngine = RegexEngine(placeholderResolver),
    )

    /** 把一组 character_book 条目转成运行时 Lorebook,模拟导入提取的转换路径。 */
    private fun bookToLorebook(vararg entriesJson: String): Lorebook {
        val entries = entriesJson.mapIndexed { index, raw ->
            json.decodeFromString<CharacterBookEntry>(raw).toLorebookEntry(fallbackUid = index)
        }
        return Lorebook(id = "__character_book__", name = "角色内嵌世界书", entries = entries)
    }

    private fun activate(book: Lorebook, vararg messages: String): LorebookEngine.ActivationResult {
        return engine.activate(
            messages = messages.toList(),
            lorebooks = listOf(TaggedLorebook(book = book, isCharacterSource = true, sourceKey = book.id)),
            messageCount = messages.size,
        )
    }

    @Test
    fun beforeCharEntryActivatesIntoWorldInfoBefore() {
        // position 整数在 extensions 里(BEFORE=0),顶层 position 是 V2 字符串。
        val book = bookToLorebook(
            """
            {
                "keys": ["dragon"],
                "content": "The dragon guards the northern pass.",
                "enabled": true,
                "position": "before_char",
                "extensions": { "position": ${WiPosition.BEFORE} }
            }
            """.trimIndent(),
        )

        val result = activate(book, "tell me about the dragon")

        assertTrue(
            "before_char 条目未进 worldInfoBefore: ${result.worldInfoBefore}",
            result.worldInfoBefore.contains("The dragon guards the northern pass."),
        )
        assertTrue("不应进 worldInfoAfter", result.worldInfoAfter.isBlank())
    }

    @Test
    fun afterCharEntryActivatesIntoWorldInfoAfter() {
        val book = bookToLorebook(
            """
            {
                "keys": ["potion"],
                "content": "Potions restore health.",
                "enabled": true,
                "position": "after_char",
                "extensions": { "position": ${WiPosition.AFTER} }
            }
            """.trimIndent(),
        )

        val result = activate(book, "where's the potion?")

        assertTrue(
            "after_char 条目未进 worldInfoAfter: ${result.worldInfoAfter}",
            result.worldInfoAfter.contains("Potions restore health."),
        )
        assertTrue("不应进 worldInfoBefore", result.worldInfoBefore.isBlank())
    }

    @Test
    fun atDepthEntryBecomesDepthInjectionWithRoleAndDepth() {
        // 高级字段全在 extensions:position=AT_DEPTH(4) / depth=2 / role=USER(1)。
        val book = bookToLorebook(
            """
            {
                "keys": ["secret"],
                "content": "The secret password is opensesame.",
                "enabled": true,
                "extensions": {
                    "position": ${WiPosition.AT_DEPTH},
                    "depth": 2,
                    "role": ${WiRole.USER}
                }
            }
            """.trimIndent(),
        )

        val result = activate(book, "what's the secret?")

        assertEquals("应有一条 depth 注入", 1, result.depthEntries.size)
        val depthEntry = result.depthEntries.first()
        assertEquals(2, depthEntry.depth)
        assertEquals(WiRole.USER, depthEntry.role)
        assertTrue(depthEntry.content.contains("opensesame"))
        // at_depth 不进 before/after
        assertTrue(result.worldInfoBefore.isBlank())
        assertTrue(result.worldInfoAfter.isBlank())
    }

    @Test
    fun disabledEntryDoesNotActivate() {
        val book = bookToLorebook(
            """
            {
                "keys": ["hidden"],
                "content": "This should never appear.",
                "enabled": false,
                "position": "before_char",
                "extensions": { "position": ${WiPosition.BEFORE} }
            }
            """.trimIndent(),
        )

        val result = activate(book, "hidden keyword here")

        assertTrue(result.worldInfoBefore.isBlank())
        assertEquals(emptyList<Any>(), result.activatedEntries)
    }

    @Test
    fun constantEntryActivatesWithoutKeyword() {
        // constant(蓝灯)在 extensions 之外是顶层字段;无关键词也应激活。
        val book = bookToLorebook(
            """
            {
                "keys": [],
                "content": "Always-on world rule.",
                "enabled": true,
                "constant": true,
                "position": "before_char",
                "extensions": { "position": ${WiPosition.BEFORE} }
            }
            """.trimIndent(),
        )

        val result = activate(book, "unrelated chatter")

        assertTrue(
            "constant 条目未无条件激活: ${result.worldInfoBefore}",
            result.worldInfoBefore.contains("Always-on world rule."),
        )
    }

    @Test
    fun activatedCharacterBookFlowsIntoComposedSystemPrompt() {
        // 全链路:character_book 激活结果 → PromptComposer worldInfoBefore marker → system 段。
        val book = bookToLorebook(
            """
            {
                "keys": ["tavern"],
                "content": "The tavern is warm and crowded.",
                "enabled": true,
                "position": "before_char",
                "extensions": { "position": ${WiPosition.BEFORE} }
            }
            """.trimIndent(),
        )
        val result = activate(book, "describe the tavern")

        val output = composer.compose(
            PromptComposerInput(
                userMessage = "describe the tavern",
                history = emptyList(),
                character = com.nuttavern.data.character.Character(id = "c1", name = "Alice"),
                userPersona = null,
                preset = com.nuttavern.data.preset.Preset.default(),
                lorebookResult = result,
            )
        )

        assertTrue(
            "激活的世界书内容未拼进 system 段: ${output.systemPrompt}",
            output.systemPrompt?.contains("The tavern is warm and crowded.") == true,
        )
    }

    @Test
    fun atDepthCharacterBookFlowsIntoComposedDepthInjection() {
        val book = bookToLorebook(
            """
            {
                "keys": ["reminder"],
                "content": "Remember to speak formally.",
                "enabled": true,
                "extensions": {
                    "position": ${WiPosition.AT_DEPTH},
                    "depth": 1,
                    "role": ${WiRole.SYSTEM}
                }
            }
            """.trimIndent(),
        )
        val result = activate(book, "reminder please")

        val output = composer.compose(
            PromptComposerInput(
                userMessage = "reminder please",
                history = listOf(
                    HistoryMessage("user", "earlier message"),
                    HistoryMessage("assistant", "earlier reply"),
                ),
                character = com.nuttavern.data.character.Character(id = "c1", name = "Alice"),
                userPersona = null,
                preset = com.nuttavern.data.preset.Preset.default(),
                lorebookResult = result,
            )
        )

        // depth=1 注入应出现在 messages 里(倒数第 1 条之前)。
        assertTrue(
            "at_depth 世界书未注入 messages: ${output.messages}",
            output.messages.any { it.content.contains("Remember to speak formally.") },
        )
    }
}

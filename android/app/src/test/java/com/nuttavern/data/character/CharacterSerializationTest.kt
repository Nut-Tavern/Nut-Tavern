package com.nuttavern.data.character

import com.nuttavern.data.lorebook.SelectiveLogic
import com.nuttavern.data.lorebook.WiPosition
import com.nuttavern.data.lorebook.WiRole
import com.nuttavern.data.regex.RegexScript
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterSerializationTest {

    @Test
    fun characterRoundTripKeepsV3FieldNamesAndNestedData() {
        val character = Character(
            id = "character-1",
            name = "Alice",
            firstMessage = "Hello",
            messageExample = "<START>\nAlice: hi",
            systemPrompt = "Stay in character.",
            postHistoryInstructions = "End note.",
            alternateGreetings = listOf("Hi", "Hey"),
            tags = listOf("test", "v3"),
            extensions = buildJsonObject { put("vendor", "nut-tavern") },
            characterBook = CharacterBook(
                name = "Alice book",
                entries = listOf(
                    CharacterBookEntry(
                        keys = listOf("tea"),
                        content = "Alice likes tea.",
                        secondaryKeys = listOf("cup"),
                    )
                ),
            ),
            regexScripts = listOf(
                RegexScript(
                    id = "regex-1",
                    scriptName = "Trim brackets",
                    findRegex = "\\[(.*?)\\]",
                    replaceString = "$1",
                    placement = listOf(1, 2),
                )
            ),
            characterLorebookId = "lorebook-primary",
            lorebookIds = listOf("lorebook-aux-1", "lorebook-aux-2"),
            createdAt = 10L,
            updatedAt = 20L,
        )

        val encodedCharacter = json.encodeToString(character)
        val decodedCharacter = json.decodeFromString<Character>(encodedCharacter)

        assertEquals(character, decodedCharacter)
        assertNotNull(decodedCharacter.characterBook)
        assertEquals("Hello", decodedCharacter.firstMessage)
        assertEquals(listOf("Hi", "Hey"), decodedCharacter.alternateGreetings)
        assertEquals("Alice likes tea.", decodedCharacter.characterBook?.entries?.first()?.content)
        assertEquals("Trim brackets", decodedCharacter.regexScripts.first().scriptName)
        assertEquals("lorebook-primary", decodedCharacter.characterLorebookId)
        assertEquals(listOf("lorebook-aux-1", "lorebook-aux-2"), decodedCharacter.lorebookIds)
    }

    @Test
    fun characterDefaultsAllowMinimalCardData() {
        val decodedCharacter = json.decodeFromString<Character>("""
            {
                "name": "Minimal",
                "first_mes": "Hi"
            }
        """.trimIndent())

        assertEquals("Minimal", decodedCharacter.name)
        assertEquals("Hi", decodedCharacter.firstMessage)
        assertEquals(emptyList<String>(), decodedCharacter.alternateGreetings)
        assertEquals(Character.EMPTY_JSON_OBJECT, decodedCharacter.extensions)
        assertEquals(null, decodedCharacter.characterBook)
        assertEquals(emptyList<RegexScript>(), decodedCharacter.regexScripts)
        assertEquals(null, decodedCharacter.characterLorebookId)
        assertEquals(emptyList<String>(), decodedCharacter.lorebookIds)
    }

    /**
     * 真实 V3 卡片把高级字段放在 entries[].extensions 子对象里。
     * toLorebookEntry 必须从 extensions 读出来,而不是从顶层(顶层不存在 → 旧实现全丢成默认)。
     */
    @Test
    fun toLorebookEntryReadsAdvancedFieldsFromExtensions() {
        val entry = json.decodeFromString<CharacterBookEntry>("""
            {
                "keys": ["dragon"],
                "content": "A fearsome dragon.",
                "enabled": true,
                "insertion_order": 50,
                "position": "after_char",
                "extensions": {
                    "position": 4,
                    "role": 2,
                    "depth": 7,
                    "selectiveLogic": 3,
                    "probability": 80,
                    "useProbability": false,
                    "exclude_recursion": true,
                    "prevent_recursion": true,
                    "delay_until_recursion": 3,
                    "group": "monsters",
                    "group_override": true,
                    "group_weight": 200,
                    "scan_depth": 5,
                    "case_sensitive": true,
                    "match_whole_words": false,
                    "use_group_scoring": true,
                    "automation_id": "auto-1",
                    "vectorized": true,
                    "sticky": 10,
                    "cooldown": 20,
                    "delay": 30,
                    "outlet_name": "outlet-x",
                    "match_persona_description": true,
                    "match_scenario": true,
                    "triggers": ["t1", "t2"],
                    "ignore_budget": true
                }
            }
        """.trimIndent())

        val lore = entry.toLorebookEntry()

        assertEquals(WiPosition.AT_DEPTH, lore.position)
        assertEquals(WiRole.ASSISTANT, lore.role)
        assertEquals(7, lore.depth)
        assertEquals(SelectiveLogic.AND_ALL, lore.selectiveLogic)
        assertEquals(80, lore.probability)
        assertFalse(lore.useProbability)
        assertTrue(lore.excludeRecursion)
        assertTrue(lore.preventRecursion)
        assertEquals(3, lore.delayUntilRecursion)
        assertEquals("monsters", lore.group)
        assertTrue(lore.groupOverride)
        assertEquals(200, lore.groupWeight)
        assertEquals(5, lore.entryScanDepth)
        assertEquals(true, lore.entryCaseSensitive)
        assertEquals(false, lore.entryMatchWholeWords)
        assertEquals(true, lore.entryUseGroupScoring)
        assertEquals("auto-1", lore.automationId)
        assertTrue(lore.vectorized)
        assertEquals(10, lore.sticky)
        assertEquals(20, lore.cooldown)
        assertEquals(30, lore.delay)
        assertEquals("outlet-x", lore.outletName)
        assertTrue(lore.matchPersonaDescription)
        assertTrue(lore.matchScenario)
        assertEquals(listOf("t1", "t2"), lore.triggers)
        assertTrue(lore.ignoreBudget)
    }

    /**
     * 缺 extensions 的极简条目走酒馆同款默认值(convertCharacterBook 的 ?? 兜底)。
     * position 回退到顶层 V2 字符串。
     */
    @Test
    fun toLorebookEntryUsesSillyTavernDefaultsWhenExtensionsMissing() {
        val before = json.decodeFromString<CharacterBookEntry>("""
            { "keys": ["x"], "content": "c", "position": "before_char" }
        """.trimIndent())
        val after = json.decodeFromString<CharacterBookEntry>("""
            { "keys": ["y"], "content": "c", "position": "after_char" }
        """.trimIndent())

        val beforeLore = before.toLorebookEntry()
        val afterLore = after.toLorebookEntry()

        assertEquals(WiPosition.BEFORE, beforeLore.position)
        assertEquals(WiPosition.AFTER, afterLore.position)
        assertEquals(WiRole.SYSTEM, beforeLore.role)
        assertEquals(100, beforeLore.probability)
        assertTrue(beforeLore.useProbability)
        assertEquals(SelectiveLogic.AND_ANY, beforeLore.selectiveLogic)
        assertEquals(0, beforeLore.delayUntilRecursion)
    }

    /**
     * delay_until_recursion 酒馆历史为 Boolean(true→1/false→0),新版可为 Int 深度,两种都要接受。
     */
    @Test
    fun toLorebookEntryAcceptsBooleanAndNumberDelayUntilRecursion() {
        fun delay(value: String): Int = json.decodeFromString<CharacterBookEntry>("""
            { "keys": ["k"], "content": "c", "extensions": { "delay_until_recursion": $value } }
        """.trimIndent()).toLorebookEntry().delayUntilRecursion

        assertEquals(1, delay("true"))
        assertEquals(0, delay("false"))
        assertEquals(2, delay("2"))
    }

    /** 6 个 match_* 扫描范围扩展字段全部从 extensions 读出。 */
    @Test
    fun toLorebookEntryReadsAllMatchScopeFlags() {
        val lore = json.decodeFromString<CharacterBookEntry>("""
            {
                "keys": ["k"], "content": "c",
                "extensions": {
                    "match_persona_description": true,
                    "match_character_description": true,
                    "match_character_personality": true,
                    "match_character_depth_prompt": true,
                    "match_scenario": true,
                    "match_creator_notes": true
                }
            }
        """.trimIndent()).toLorebookEntry()

        assertTrue(lore.matchPersonaDescription)
        assertTrue(lore.matchCharacterDescription)
        assertTrue(lore.matchCharacterPersonality)
        assertTrue(lore.matchCharacterDepthPrompt)
        assertTrue(lore.matchScenario)
        assertTrue(lore.matchCreatorNotes)
    }

    /**
     * 导出:高级字段写进 extensions(整数 position/role),顶层 position 写 V2 字符串,use_regex 恒 true。
     */
    @Test
    fun toCharacterBookEntryWritesAdvancedFieldsIntoExtensions() {
        val original = CharacterBookEntry(keys = listOf("k"), content = "c")
        val lore = original.toLorebookEntry().copy(
            position = WiPosition.AT_DEPTH,
            role = WiRole.USER,
            depth = 9,
            selectiveLogic = SelectiveLogic.NOT_ALL,
            group = "g",
            groupWeight = 150,
            excludeRecursion = true,
            delayUntilRecursion = 4,
        )

        val exported = lore.toCharacterBookEntry(original)
        val ext = exported.extensions

        assertEquals("after_char", exported.position)
        assertEquals(true, exported.useRegex)
        assertEquals(WiPosition.AT_DEPTH, ext.int("position"))
        assertEquals(WiRole.USER, ext.int("role"))
        assertEquals(9, ext.int("depth"))
        assertEquals(SelectiveLogic.NOT_ALL, ext.int("selectiveLogic"))
        assertEquals("g", ext.string("group"))
        assertEquals(150, ext.int("group_weight"))
        assertEquals(true, ext.bool("exclude_recursion"))
        // delayUntilRecursion>0 写精确整数
        assertEquals(4, ext.int("delay_until_recursion"))
    }

    /** delayUntilRecursion==0 时导出写布尔 false(对齐酒馆 off 形态)。 */
    @Test
    fun toCharacterBookEntryWritesFalseDelayWhenZero() {
        val original = CharacterBookEntry(keys = listOf("k"), content = "c")
        val lore = original.toLorebookEntry().copy(delayUntilRecursion = 0)

        val ext = lore.toCharacterBookEntry(original).extensions

        assertEquals(false, ext.bool("delay_until_recursion"))
    }

    /** delayUntilRecursion>0 时导出写精确整数深度(非布尔)。 */
    @Test
    fun toCharacterBookEntryWritesIntDelayWhenPositive() {
        val original = CharacterBookEntry(keys = listOf("k"), content = "c")
        val lore = original.toLorebookEntry().copy(delayUntilRecursion = 5)

        val ext = lore.toCharacterBookEntry(original).extensions

        assertEquals(5, ext.int("delay_until_recursion"))
        assertNull(ext.bool("delay_until_recursion"))
    }

    /** entryUseGroupScoring 为 null 时导出归一为 false(对齐酒馆 use_group_scoring ?? false)。 */
    @Test
    fun toCharacterBookEntryNormalizesNullUseGroupScoringToFalse() {
        val original = CharacterBookEntry(keys = listOf("k"), content = "c")
        val lore = original.toLorebookEntry().copy(entryUseGroupScoring = null)

        val ext = lore.toCharacterBookEntry(original).extensions

        assertEquals(false, ext.bool("use_group_scoring"))
    }

    /** 导出保留 original.extensions 里本仓库不建模的键(如 display_index),不丢失。 */
    @Test
    fun toCharacterBookEntryPreservesUnknownExtensionKeys() {
        val original = CharacterBookEntry(
            keys = listOf("k"),
            content = "c",
            extensions = buildJsonObject {
                put("display_index", 7)
                put("vendor_field", "keep-me")
            },
        )
        val lore = original.toLorebookEntry()

        val ext = lore.toCharacterBookEntry(original).extensions

        assertEquals(7, ext.int("display_index"))
        assertEquals("keep-me", ext.string("vendor_field"))
    }

    /** character_filter(本仓库扩展,存 extensions)往返不丢:导入读出 → 导出写回。 */
    @Test
    fun characterFilterSurvivesRoundTripThroughExtensions() {
        val source = json.decodeFromString<CharacterBookEntry>("""
            {
                "keys": ["k"], "content": "c",
                "extensions": {
                    "character_filter": { "isExclude": true, "names": ["uuid-1", "uuid-2"], "tags": ["t1"] }
                }
            }
        """.trimIndent())

        val lore = source.toLorebookEntry()
        assertNotNull(lore.characterFilter)
        assertEquals(true, lore.characterFilter?.isExclude)
        assertEquals(listOf("uuid-1", "uuid-2"), lore.characterFilter?.names)

        val exported = lore.toCharacterBookEntry(source)
        val reread = exported.toLorebookEntry()
        assertEquals(lore.characterFilter, reread.characterFilter)
    }

    /** character_filter 缺失时导入降级 null,导出不写该键(不污染酒馆导入)。 */
    @Test
    fun characterFilterAbsentStaysNullAndNotWritten() {
        val original = CharacterBookEntry(keys = listOf("k"), content = "c")
        val lore = original.toLorebookEntry()

        assertNull(lore.characterFilter)
        val ext = lore.toCharacterBookEntry(original).extensions
        assertNull(ext["character_filter"])
    }

    /** character_filter 结构非法时导入降级 null,不让坏卡崩溃。 */
    @Test
    fun malformedCharacterFilterDowngradesToNull() {
        val entry = json.decodeFromString<CharacterBookEntry>("""
            { "keys": ["k"], "content": "c", "extensions": { "character_filter": "not-an-object" } }
        """.trimIndent())

        assertNull(entry.toLorebookEntry().characterFilter)
    }

    /**
     * 完整 round-trip:真实 V3 卡 → toLorebookEntry → toCharacterBookEntry,
     * 高级字段语义不丢(经 extensions 往返)。
     */
    @Test
    fun advancedFieldsSurviveImportEditExportRoundTrip() {
        val source = json.decodeFromString<CharacterBookEntry>("""
            {
                "keys": ["dragon"],
                "content": "c",
                "extensions": {
                    "position": 4, "role": 2, "depth": 7, "selectiveLogic": 3,
                    "probability": 80, "useProbability": false,
                    "group": "monsters", "group_weight": 200,
                    "exclude_recursion": true, "delay_until_recursion": 3
                }
            }
        """.trimIndent())

        val roundTripped = source.toLorebookEntry().toCharacterBookEntry(source)
        val lore = roundTripped.toLorebookEntry()

        assertEquals(WiPosition.AT_DEPTH, lore.position)
        assertEquals(WiRole.ASSISTANT, lore.role)
        assertEquals(7, lore.depth)
        assertEquals(SelectiveLogic.AND_ALL, lore.selectiveLogic)
        assertEquals(80, lore.probability)
        assertFalse(lore.useProbability)
        assertEquals("monsters", lore.group)
        assertEquals(200, lore.groupWeight)
        assertTrue(lore.excludeRecursion)
        assertEquals(3, lore.delayUntilRecursion)
    }

    /**
     * 整本 CharacterBook → Lorebook → CharacterBook round-trip(角色卡导入提取 + 导出回填)。
     * 校验书名 / 顶层设置 / 条目按 uid 匹配 original 保留 extensions round-trip。
     */
    @Test
    fun characterBookToLorebookAndBackPreservesCoreFields() {
        val original = CharacterBook(
            name = "Eldoria",
            description = "lore",
            scanDepth = 3,
            tokenBudget = 512,
            recursiveScanning = true,
            entries = listOf(
                CharacterBookEntry(
                    keys = listOf("dragon"),
                    content = "A dragon.",
                    id = 0,
                    insertionOrder = 10,
                ),
            ),
        )

        val lorebook = original.toLorebook(lorebookId = "lb-1", characterName = "Hero")
        assertEquals("Eldoria", lorebook.name)
        assertEquals(3, lorebook.scanDepth)
        assertEquals(512, lorebook.tokenBudget)
        assertTrue(lorebook.recursiveScanning)
        assertEquals(1, lorebook.entries.size)
        assertEquals(listOf("dragon"), lorebook.entries[0].key)

        val reExported = lorebook.toCharacterBook(original)
        assertEquals("Eldoria", reExported.name)
        assertEquals(3, reExported.scanDepth)
        assertEquals(512, reExported.tokenBudget)
        assertEquals(1, reExported.entries.size)
        assertEquals(listOf("dragon"), reExported.entries[0].keys)
        assertEquals("A dragon.", reExported.entries[0].content)
    }

    @Test
    fun characterBookToLorebookUsesCharacterNameFallbackWhenNoBookName() {
        val book = CharacterBook(
            name = null,
            entries = listOf(CharacterBookEntry(keys = listOf("k"), content = "c")),
        )
        val lorebook = book.toLorebook(lorebookId = "lb-2", characterName = "Seraphina")
        assertEquals("Seraphina的世界书", lorebook.name)
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
        fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }
    }
}

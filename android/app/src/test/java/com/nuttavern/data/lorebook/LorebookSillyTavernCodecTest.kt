package com.nuttavern.data.lorebook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LorebookSillyTavernCodecTest {

    /**
     * 真实酒馆世界书文件:entries 是 uid 键 map,无顶层 name,条目用 characterFilter(camelCase),
     * displayIndex 与 uid 故意错位(显示顺序 ≠ map 键顺序 ≠ 注入顺序 order)。
     */
    private val sillyTavernWorld = """
        {
            "entries": {
                "0": {
                    "uid": 0,
                    "key": ["dragon"],
                    "content": "Dragon lore.",
                    "order": 50,
                    "displayIndex": 2,
                    "disable": false
                },
                "1": {
                    "uid": 1,
                    "key": ["potion"],
                    "content": "Potion lore.",
                    "order": 90,
                    "displayIndex": 0,
                    "disable": false,
                    "characterFilter": { "isExclude": true, "names": ["alice"], "tags": [] }
                },
                "2": {
                    "uid": 2,
                    "key": ["tavern"],
                    "content": "Tavern lore.",
                    "order": 70,
                    "displayIndex": 1,
                    "constant": true
                }
            }
        }
    """.trimIndent()

    @Test
    fun importParsesEntriesMapIntoList() {
        val book = LorebookSillyTavernCodec.decodeFromSillyTavern(sillyTavernWorld, "My World")

        assertEquals("My World", book.name)
        assertEquals(3, book.entries.size)
        assertTrue(book.id.isNotBlank())
    }

    @Test
    fun importOrdersListByDisplayIndex() {
        val book = LorebookSillyTavernCodec.decodeFromSillyTavern(sillyTavernWorld, "w")

        // displayIndex: potion=0, tavern=1, dragon=2 → list 顺序应为 potion, tavern, dragon
        assertEquals(listOf("Potion lore.", "Tavern lore.", "Dragon lore."), book.entries.map { it.content })
    }

    @Test
    fun importPreservesInjectionOrderField() {
        val book = LorebookSillyTavernCodec.decodeFromSillyTavern(sillyTavernWorld, "w")

        // order 字段(注入顺序)与显示顺序独立,原样搬运
        val byContent = book.entries.associateBy { it.content }
        assertEquals(50, byContent["Dragon lore."]?.order)
        assertEquals(90, byContent["Potion lore."]?.order)
        assertEquals(70, byContent["Tavern lore."]?.order)
    }

    @Test
    fun importRenamesCharacterFilterToInternalForm() {
        val book = LorebookSillyTavernCodec.decodeFromSillyTavern(sillyTavernWorld, "w")

        val potion = book.entries.first { it.content == "Potion lore." }
        assertNotNull("characterFilter 未解析", potion.characterFilter)
        assertEquals(true, potion.characterFilter?.isExclude)
        assertEquals(listOf("alice"), potion.characterFilter?.names)
    }

    @Test
    fun importMapsCommonFields() {
        val book = LorebookSillyTavernCodec.decodeFromSillyTavern(sillyTavernWorld, "w")

        val dragon = book.entries.first { it.content == "Dragon lore." }
        assertEquals(0, dragon.uid)
        assertEquals(listOf("dragon"), dragon.key)
        assertEquals(false, dragon.disable)

        val tavern = book.entries.first { it.content == "Tavern lore." }
        assertEquals(true, tavern.constant)
    }

    @Test
    fun exportWritesEntriesAsUidKeyedMap() {
        val book = LorebookSillyTavernCodec.decodeFromSillyTavern(sillyTavernWorld, "w")
        val exported = Json.parseToJsonElement(LorebookSillyTavernCodec.encodeToSillyTavern(book)) as JsonObject

        val entries = exported["entries"] as JsonObject
        // uid 作为 map 键
        assertNotNull(entries["0"])
        assertNotNull(entries["1"])
        assertNotNull(entries["2"])
    }

    @Test
    fun exportRewritesDisplayIndexByListPosition() {
        val book = LorebookSillyTavernCodec.decodeFromSillyTavern(sillyTavernWorld, "w")
        val exported = Json.parseToJsonElement(LorebookSillyTavernCodec.encodeToSillyTavern(book)) as JsonObject
        val entries = exported["entries"] as JsonObject

        // list 顺序 potion(uid1), tavern(uid2), dragon(uid0) → displayIndex 0,1,2
        fun displayIndex(uid: String): Int? =
            (entries[uid]!!.jsonObject["displayIndex"] as? JsonPrimitive)?.intOrNull
        assertEquals(0, displayIndex("1")) // potion 排第一
        assertEquals(1, displayIndex("2")) // tavern 第二
        assertEquals(2, displayIndex("0")) // dragon 第三
    }

    @Test
    fun exportRenamesCharacterFilterBackToCamelCase() {
        val book = LorebookSillyTavernCodec.decodeFromSillyTavern(sillyTavernWorld, "w")
        val exported = Json.parseToJsonElement(LorebookSillyTavernCodec.encodeToSillyTavern(book)) as JsonObject
        val entries = exported["entries"] as JsonObject

        val potion = entries["1"]!!.jsonObject
        assertNotNull("应写回 characterFilter(camelCase)", potion["characterFilter"])
        assertNull("不应残留 character_filter(snake)", potion["character_filter"])
    }

    @Test
    fun exportDoesNotWriteTopLevelName() {
        val book = LorebookSillyTavernCodec.decodeFromSillyTavern(sillyTavernWorld, "My World")
        val exported = Json.parseToJsonElement(LorebookSillyTavernCodec.encodeToSillyTavern(book)) as JsonObject

        // 酒馆独立世界书文件 JSON 本体不写 name(书名取自文件名)
        assertNull(exported["name"])
    }

    @Test
    fun roundTripPreservesDisplayAndInjectionOrders() {
        val imported = LorebookSillyTavernCodec.decodeFromSillyTavern(sillyTavernWorld, "w")
        val exportedText = LorebookSillyTavernCodec.encodeToSillyTavern(imported)
        val reimported = LorebookSillyTavernCodec.decodeFromSillyTavern(exportedText, "w2")

        // 显示顺序(list)与注入顺序(order)往返都不丢
        assertEquals(imported.entries.map { it.content }, reimported.entries.map { it.content })
        assertEquals(imported.entries.map { it.order }, reimported.entries.map { it.order })
        assertEquals(
            imported.entries.first { it.content == "Potion lore." }.characterFilter,
            reimported.entries.first { it.content == "Potion lore." }.characterFilter,
        )
    }

    @Test
    fun importFallsBackToMapOrderWhenDisplayIndexMissing() {
        val book = LorebookSillyTavernCodec.decodeFromSillyTavern(
            """
            {
                "entries": {
                    "0": { "uid": 0, "key": ["a"], "content": "first" },
                    "1": { "uid": 1, "key": ["b"], "content": "second" }
                }
            }
            """.trimIndent(),
            "w",
        )
        // 无 displayIndex,回退 uid 升序
        assertEquals(listOf("first", "second"), book.entries.map { it.content })
    }

    /** 缺 displayIndex 时按 uid 排序(对齐酒馆 ?? uid),而非 map 文本顺序。 */
    @Test
    fun importFallbackUsesUidNotMapTextOrder() {
        val book = LorebookSillyTavernCodec.decodeFromSillyTavern(
            """
            {
                "entries": {
                    "7": { "uid": 7, "key": ["c"], "content": "uid7" },
                    "1": { "uid": 1, "key": ["a"], "content": "uid1" },
                    "3": { "uid": 3, "key": ["b"], "content": "uid3" }
                }
            }
            """.trimIndent(),
            "w",
        )
        // map 文本顺序是 7,1,3;按 uid 排序应为 1,3,7
        assertEquals(listOf("uid1", "uid3", "uid7"), book.entries.map { it.content })
    }

    /** 条目缺 uid 字段时用 map 键兜底,导出不碰撞、不丢条目。 */
    @Test
    fun importUsesMapKeyWhenEntryUidFieldMissing() {
        val book = LorebookSillyTavernCodec.decodeFromSillyTavern(
            """
            {
                "entries": {
                    "0": { "key": ["a"], "content": "first" },
                    "1": { "key": ["b"], "content": "second" },
                    "2": { "key": ["c"], "content": "third" }
                }
            }
            """.trimIndent(),
            "w",
        )
        assertEquals(3, book.entries.size)
        // uid 从 map 键取
        assertEquals(setOf(0, 1, 2), book.entries.map { it.uid }.toSet())

        // 导出 map 键不碰撞,三条都在
        val exported = Json.parseToJsonElement(LorebookSillyTavernCodec.encodeToSillyTavern(book)) as JsonObject
        val entries = exported["entries"] as JsonObject
        assertEquals(3, entries.size)
    }

    /** 满字段条目 round-trip 后逐字段不丢(锁 @SerialName 漂移)。 */
    @Test
    fun fullFieldRoundTripPreservesAllFields() {
        val original = Lorebook(
            id = "src",
            name = "full",
            entries = listOf(
                LorebookEntry(
                    uid = 5,
                    key = listOf("k1", "k2"),
                    keysecondary = listOf("s1"),
                    comment = "memo",
                    content = "body",
                    constant = true,
                    selective = false,
                    selectiveLogic = SelectiveLogic.NOT_ANY,
                    order = 42,
                    position = WiPosition.AT_DEPTH,
                    disable = true,
                    depth = 9,
                    role = WiRole.USER,
                    group = "g",
                    groupOverride = true,
                    groupWeight = 77,
                    entryScanDepth = 3,
                    entryCaseSensitive = true,
                    entryMatchWholeWords = false,
                    entryUseGroupScoring = true,
                    probability = 88,
                    useProbability = false,
                    sticky = 4,
                    cooldown = 5,
                    delay = 6,
                    excludeRecursion = true,
                    preventRecursion = true,
                    delayUntilRecursion = 2,
                    ignoreBudget = true,
                    addMemo = true,
                    outletName = "out",
                    triggers = listOf("normal"),
                    matchPersonaDescription = true,
                    matchScenario = true,
                    characterFilter = CharacterFilter(isExclude = true, names = listOf("x")),
                    vectorized = true,
                    automationId = "auto",
                ),
            ),
        )

        val exportedText = LorebookSillyTavernCodec.encodeToSillyTavern(original)
        val reimported = LorebookSillyTavernCodec.decodeFromSillyTavern(exportedText, "full")

        // id 是导入新生成的,排除;其余条目字段逐一比对
        assertEquals(original.entries.single(), reimported.entries.single())
    }

    @Test
    fun importHandlesEmptyEntries() {
        val book = LorebookSillyTavernCodec.decodeFromSillyTavern("""{ "entries": {} }""", "empty")
        assertEquals(emptyList<LorebookEntry>(), book.entries)
        assertEquals("empty", book.name)
    }

    @Test
    fun importRejectsNonObjectJson() {
        assertTrue(runCatching {
            LorebookSillyTavernCodec.decodeFromSillyTavern("[1,2,3]", "bad")
        }.isFailure)
    }

    @Test
    fun importRejectsMissingEntries() {
        assertTrue(runCatching {
            LorebookSillyTavernCodec.decodeFromSillyTavern("""{ "name": "x" }""", "bad")
        }.isFailure)
    }

    /**
     * 真实酒馆导出的条目:`role` 为 null(非 atDepth 时不指定角色),
     * `delayUntilRecursion` 为 Boolean(旧格式)。内部模型这两字段是非空 Int,
     * 不规整会让反序列化直接抛异常导致整本书导入失败(对齐 worlds/Eldoria.json 实际结构)。
     */
    @Test
    fun importHandlesNullRoleAndBooleanDelayUntilRecursion() {
        val world = """
            {
                "entries": {
                    "0": {
                        "uid": 0,
                        "key": ["eldoria"],
                        "content": "Eldoria lore.",
                        "order": 100,
                        "position": 0,
                        "role": null,
                        "delayUntilRecursion": false
                    },
                    "1": {
                        "uid": 1,
                        "key": ["recurse"],
                        "content": "Recurse lore.",
                        "delayUntilRecursion": true
                    }
                }
            }
        """.trimIndent()

        val book = LorebookSillyTavernCodec.decodeFromSillyTavern(world, "Eldoria")

        assertEquals(2, book.entries.size)
        val first = book.entries.first { it.uid == 0 }
        assertEquals(WiRole.SYSTEM, first.role)
        assertEquals(0, first.delayUntilRecursion)
        val second = book.entries.first { it.uid == 1 }
        assertEquals(1, second.delayUntilRecursion)
    }

    /**
     * AN_TOP / AN_BOTTOM position 在导入导出 round-trip 中必须无损保留。
     *
     * 本仓库 author's note 模块未落地,LorebookEngine 运行时直接忽略这两档(见 AGENTS.md
     * "Author's Note 模块未落地"待办),但 codec 必须保留 position 整数值,保证:
     * 1. 用户从酒馆导入的世界书,带 AN_TOP/AN_BOTTOM 条目的 position 字段不会被改写或丢失;
     * 2. 从本仓库导出回酒馆时,这些条目的 position 仍是原始值,酒馆侧仍能正确按 AN 槽注入。
     *
     * 这是"运行时忽略 ≠ 数据层降级"的边界保证。
     */
    @Test
    fun roundTripPreservesAnTopAndAnBottomPosition() {
        val world = """
            {
                "entries": {
                    "0": {
                        "uid": 0,
                        "key": ["alpha"],
                        "content": "an-top entry",
                        "position": 2
                    },
                    "1": {
                        "uid": 1,
                        "key": ["beta"],
                        "content": "an-bottom entry",
                        "position": 3
                    }
                }
            }
        """.trimIndent()

        val book = LorebookSillyTavernCodec.decodeFromSillyTavern(world, "AN Round Trip")

        val anTop = book.entries.first { it.uid == 0 }
        val anBottom = book.entries.first { it.uid == 1 }
        assertEquals(WiPosition.AN_TOP, anTop.position)
        assertEquals(WiPosition.AN_BOTTOM, anBottom.position)

        val exported = LorebookSillyTavernCodec.encodeToSillyTavern(book)
        val reparsed = Json.parseToJsonElement(exported).jsonObject
        val entries = reparsed["entries"]!!.jsonObject
        assertEquals(
            WiPosition.AN_TOP,
            (entries["0"]!!.jsonObject["position"] as JsonPrimitive).intOrNull,
        )
        assertEquals(
            WiPosition.AN_BOTTOM,
            (entries["1"]!!.jsonObject["position"] as JsonPrimitive).intOrNull,
        )
    }
}

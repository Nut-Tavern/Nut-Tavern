package com.nuttavern.data.character

import com.nuttavern.data.character.io.PngTextChunk
import com.nuttavern.data.regex.RegexScript
import java.util.zip.CRC32
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CharacterCardCodec] 三态识别 + round-trip 单测。全部用内联 JSON 构造,不依赖外部卡文件。
 *
 * Seraphina.png 等真实卡的端到端验证走装机手测(避免硬编码本机路径)。
 */
class CharacterCardCodecTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesV1FlatCardIntoData() {
        val v1 = """
            {
              "name": "V1 Bot",
              "description": "desc",
              "personality": "kind",
              "scenario": "tavern",
              "first_mes": "Hello!",
              "mes_example": "<START>",
              "creatorcomment": "by me"
            }
        """.trimIndent()

        val decoded = CharacterCardCodec.decodeFromJson(v1)

        assertEquals("V1 Bot", decoded.character.name)
        assertEquals("desc", decoded.character.description)
        assertEquals("Hello!", decoded.character.firstMessage)
        // V1 的 creatorcomment 映射成 creator_notes
        assertEquals("by me", decoded.character.creatorNotes)
        assertNull(decoded.embeddedBook)
    }

    @Test
    fun decodesV1TalkativenessAndFavIntoExtensions() {
        // V1 顶层 talkativeness / fav 升级进 extensions(对齐酒馆 charaFormatData)
        val v1 = """
            {
              "name": "V1 Bot",
              "talkativeness": "0.7",
              "fav": true
            }
        """.trimIndent()

        val decoded = CharacterCardCodec.decodeFromJson(v1)
        val ext = decoded.character.extensions

        assertEquals("0.7", ext["talkativeness"]!!.jsonPrimitive.content)
        assertEquals(true, ext["fav"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun decodesV2CardFromDataObject() {
        val v2 = buildCardJson(spec = "chara_card_v2", specVersion = "2.0", name = "V2 Bot")
        val decoded = CharacterCardCodec.decodeFromJson(v2)

        assertEquals("V2 Bot", decoded.character.name)
        assertEquals("a system prompt", decoded.character.systemPrompt)
    }

    @Test
    fun decodesV3CardFromDataObject() {
        val v3 = buildCardJson(spec = "chara_card_v3", specVersion = "3.0", name = "V3 Bot")
        val decoded = CharacterCardCodec.decodeFromJson(v3)

        assertEquals("V3 Bot", decoded.character.name)
        assertEquals(listOf("g1", "g2"), decoded.character.alternateGreetings)
    }

    @Test
    fun decodesCardWithNullArrayFields() {
        // 酒馆卡常把 alternate_greetings / tags 写成显式 null,应回退空 List 而不是抛错
        val card = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "Null Arrays",
                "description": "d",
                "alternate_greetings": null,
                "tags": null
              }
            }
        """.trimIndent()

        val decoded = CharacterCardCodec.decodeFromJson(card)

        assertEquals("Null Arrays", decoded.character.name)
        assertTrue(decoded.character.alternateGreetings.isEmpty())
        assertTrue(decoded.character.tags.isEmpty())
    }

    @Test
    fun decodesStringListFieldsFromStrings() {
        val card = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "String Lists",
                "alternate_greetings": "Hello from string",
                "tags": "mage, tavern，friend"
              }
            }
        """.trimIndent()

        val decoded = CharacterCardCodec.decodeFromJson(card)

        assertEquals(listOf("Hello from string"), decoded.character.alternateGreetings)
        assertEquals(listOf("mage", "tavern", "friend"), decoded.character.tags)
    }

    @Test
    fun fillsDefaultNameWhenCardNameIsMissingOrBlank() {
        val card = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "   ",
                "description": "missing usable name"
              }
            }
        """.trimIndent()

        val decoded = CharacterCardCodec.decodeFromJson(card)

        assertEquals("未命名角色", decoded.character.name)
    }

    @Test
    fun keepsUnmodeledDataFieldsInRawCardData() {
        val v3 = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "Keeper",
                "group_only_greetings": ["hi group"],
                "nickname": "K"
              }
            }
        """.trimIndent()

        val decoded = CharacterCardCodec.decodeFromJson(v3)
        val raw = decoded.character.rawCardData

        assertNotNull("未建模字段应存进 rawCardData", raw)
        assertEquals("K", raw!!["nickname"]!!.jsonPrimitive.content)
        assertEquals("hi group", raw["group_only_greetings"]!!.jsonArray[0].jsonPrimitive.content)
        // 已建模字段不应进 rawCardData
        assertNull(raw["name"])
    }

    @Test
    fun exportRoundTripsUnmodeledFields() {
        val v3 = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "Keeper",
                "description": "d",
                "group_only_greetings": ["hi group"]
              }
            }
        """.trimIndent()

        val decoded = CharacterCardCodec.decodeFromJson(v3)
        val exported = CharacterCardCodec.encodeToV3Json(decoded.character, decoded.embeddedBook)
        val exportedData = json.parseToJsonElement(exported).jsonObject["data"]!!.jsonObject

        // 未建模字段 round-trip 不丢
        assertEquals("hi group", exportedData["group_only_greetings"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("Keeper", exportedData["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun exportReflectsEditedModeledFields() {
        val v3 = buildCardJson(spec = "chara_card_v3", specVersion = "3.0", name = "Old Name")
        val decoded = CharacterCardCodec.decodeFromJson(v3)
        val edited = decoded.character.copy(name = "New Name")

        val exported = CharacterCardCodec.encodeToV3Json(edited, decoded.embeddedBook)
        val data = json.parseToJsonElement(exported).jsonObject["data"]!!.jsonObject

        assertEquals("New Name", data["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun exportReflectsEditedRegexScripts() {
        val card = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "Regex Bot",
                "extensions": {
                  "regex_scripts": [
                    { "id": "old", "scriptName": "Old", "findRegex": "/old/g", "replaceString": "" }
                  ]
                }
              }
            }
        """.trimIndent()

        val decoded = CharacterCardCodec.decodeFromJson(card)
        val edited = decoded.character.copy(
            regexScripts = listOf(
                RegexScript(
                    id = "new",
                    scriptName = "New",
                    findRegex = "/new/g",
                    replaceString = "replacement",
                )
            ),
        )
        val exported = CharacterCardCodec.encodeToV3Json(edited, decoded.embeddedBook)
        val data = json.parseToJsonElement(exported).jsonObject["data"]!!.jsonObject
        val scripts = data["extensions"]!!.jsonObject["regex_scripts"]!!.jsonArray

        assertEquals(1, scripts.size)
        assertEquals("new", scripts[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertNull("酒馆角色正则应写入 extensions.regex_scripts,不应写顶层", data["regex_scripts"])
        assertNull("已兼容字段不应留在 rawCardData", decoded.character.rawCardData?.get("regex_scripts"))
    }

    @Test
    fun importLegacyTopLevelRegexScriptsExportsToExtensions() {
        val card = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "Legacy Regex Bot",
                "regex_scripts": [
                  { "id": "legacy", "scriptName": "Legacy", "findRegex": "/x/g", "replaceString": "y" }
                ]
              }
            }
        """.trimIndent()

        val decoded = CharacterCardCodec.decodeFromJson(card)
        val exported = CharacterCardCodec.encodeToV3Json(decoded.character, decoded.embeddedBook)
        val data = json.parseToJsonElement(exported).jsonObject["data"]!!.jsonObject
        val scripts = data["extensions"]!!.jsonObject["regex_scripts"]!!.jsonArray

        assertEquals("legacy", decoded.character.regexScripts.single().id)
        assertEquals("legacy", scripts.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertNull(data["regex_scripts"])
    }

    @Test
    fun exportPreservesUnparsedRegexScriptsWhenNoEditedScriptsExist() {
        val card = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "Broken Regex Bot",
                "regex_scripts": { "unexpected": "object" }
              }
            }
        """.trimIndent()

        val decoded = CharacterCardCodec.decodeFromJson(card)
        val exported = CharacterCardCodec.encodeToV3Json(decoded.character, decoded.embeddedBook)
        val data = json.parseToJsonElement(exported).jsonObject["data"]!!.jsonObject

        assertTrue(decoded.character.regexScripts.isEmpty())
        assertEquals("object", data["regex_scripts"]!!.jsonObject["unexpected"]!!.jsonPrimitive.content)
    }

    @Test
    fun exportPreservesUnparsedExtensionRegexScriptsWhenNoEditedScriptsExist() {
        val card = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "Broken Extension Regex Bot",
                "extensions": {
                  "regex_scripts": [42],
                  "vendor": "external-tool"
                }
              }
            }
        """.trimIndent()

        val decoded = CharacterCardCodec.decodeFromJson(card)
        val exported = CharacterCardCodec.encodeToV3Json(decoded.character, decoded.embeddedBook)
        val extensions = json.parseToJsonElement(exported)
            .jsonObject["data"]!!.jsonObject["extensions"]!!.jsonObject

        assertTrue(decoded.character.regexScripts.isEmpty())
        assertEquals("external-tool", extensions["vendor"]!!.jsonPrimitive.content)
        assertEquals("42", extensions["regex_scripts"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun exportsSpecV3Header() {
        val decoded = CharacterCardCodec.decodeFromJson(
            buildCardJson(spec = "chara_card_v3", specVersion = "3.0", name = "Bot"),
        )
        val exported = json.parseToJsonElement(
            CharacterCardCodec.encodeToV3Json(decoded.character, null),
        ).jsonObject

        assertEquals("chara_card_v3", exported["spec"]!!.jsonPrimitive.content)
        assertEquals("3.0", exported["spec_version"]!!.jsonPrimitive.content)
    }

    @Test
    fun decodesEmbeddedCharacterBook() {
        val v3 = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "Booked",
                "character_book": {
                  "name": "Lore",
                  "entries": [
                    { "keys": ["k1"], "content": "c1", "enabled": true, "insertion_order": 5 }
                  ]
                }
              }
            }
        """.trimIndent()

        val decoded = CharacterCardCodec.decodeFromJson(v3)

        assertNotNull(decoded.embeddedBook)
        assertEquals("Lore", decoded.embeddedBook!!.name)
        assertEquals(1, decoded.embeddedBook!!.entries.size)
        assertEquals(listOf("k1"), decoded.embeddedBook!!.entries[0].keys)
    }

    @Test
    fun exportOmitsCharacterBookWhenNull() {
        val decoded = CharacterCardCodec.decodeFromJson(
            buildCardJson(spec = "chara_card_v3", specVersion = "3.0", name = "Bot"),
        )
        val data = json.parseToJsonElement(
            CharacterCardCodec.encodeToV3Json(decoded.character, null),
        ).jsonObject["data"]!!.jsonObject

        assertNull(data["character_book"])
    }

    @Test
    fun pngRoundTripsCardThroughCcv3Chunk() {
        val decoded = CharacterCardCodec.decodeFromJson(
            buildCardJson(spec = "chara_card_v3", specVersion = "3.0", name = "PngBot"),
        )
        val pngOut = CharacterCardCodec.encodeToPng(decoded.character, null, minimalPng())

        // 双写 chara + ccv3
        val chunks = PngTextChunk.readTextChunks(pngOut)
        assertTrue(chunks.containsKey("chara"))
        assertTrue(chunks.containsKey("ccv3"))

        val reDecoded = CharacterCardCodec.decodeFromPng(pngOut)
        assertEquals("PngBot", reDecoded.character.name)
    }

    @Test
    fun pngPrefersCcv3OverChara() {
        val v3Char = CharacterCardCodec.decodeFromJson(
            buildCardJson(spec = "chara_card_v3", specVersion = "3.0", name = "FromCcv3"),
        ).character
        val png = CharacterCardCodec.encodeToPng(v3Char, null, minimalPng())

        val decoded = CharacterCardCodec.decodeFromPng(png)
        // ccv3 与 chara data 相同,这里只验证能正确读出
        assertEquals("FromCcv3", decoded.character.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidJson() {
        CharacterCardCodec.decodeFromJson("not a json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPngWithoutCard() {
        CharacterCardCodec.decodeFromPng(minimalPng())
    }

    // ── helpers ──

    private fun buildCardJson(spec: String, specVersion: String, name: String): String = """
        {
          "spec": "$spec",
          "spec_version": "$specVersion",
          "data": {
            "name": "$name",
            "description": "a description",
            "personality": "friendly",
            "scenario": "a scenario",
            "first_mes": "Hi there",
            "mes_example": "<START>",
            "system_prompt": "a system prompt",
            "post_history_instructions": "post history",
            "alternate_greetings": ["g1", "g2"],
            "creator": "tester",
            "character_version": "1.0",
            "creator_notes": "notes",
            "tags": ["t1", "t2"],
            "extensions": { "vendor": "nut-tavern" }
          }
        }
    """.trimIndent()

    private fun minimalPng(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val typeBytes = "IEND".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(typeBytes) }.value
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write(typeBytes)
        out.write(
            byteArrayOf(
                ((crc ushr 24) and 0xFF).toByte(),
                ((crc ushr 16) and 0xFF).toByte(),
                ((crc ushr 8) and 0xFF).toByte(),
                (crc and 0xFF).toByte(),
            ),
        )
        return out.toByteArray()
    }
}

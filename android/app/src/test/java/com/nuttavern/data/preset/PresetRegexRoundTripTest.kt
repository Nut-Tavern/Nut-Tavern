package com.nuttavern.data.preset

import com.nuttavern.data.regex.RegexPlacement
import com.nuttavern.data.regex.RegexScript
import com.nuttavern.data.regex.SubstituteRegex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设内嵌正则(PRESET 作用域)读写 round-trip。
 *
 * 验证 [Preset.presetRegexScripts] / [Preset.withPresetRegexScripts]:
 * - 写入后能原样读回(字段全集 round-trip);
 * - 写回**不破坏** extensions 里的其他键;
 * - 空列表移除 `regex_scripts` 键(不留空数组脏数据);
 * - 经过 [Preset] 整体序列化(对齐酒馆 JSON)后脚本不丢。
 */
class PresetRegexRoundTripTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun sampleScript(name: String) = RegexScript(
        id = "fixed-id-$name",
        scriptName = name,
        findRegex = "/<think>(.*?)</think>/gs",
        replaceString = "$1",
        trimStrings = listOf("  ", "\n"),
        placement = listOf(RegexPlacement.AI_OUTPUT.value),
        disabled = false,
        markdownOnly = true,
        promptOnly = false,
        runOnEdit = true,
        substituteRegex = SubstituteRegex.ESCAPED.value,
        minDepth = 1,
        maxDepth = 4,
    )

    @Test
    fun writeThenReadKeepsAllFields() {
        val scripts = listOf(sampleScript("trim-think"), sampleScript("strip-tags").copy(disabled = true))
        val preset = Preset(name = "RP").withPresetRegexScripts(scripts)

        assertEquals(scripts, preset.presetRegexScripts())
    }

    @Test
    fun writePreservesOtherExtensionKeys() {
        val preset = Preset(
            name = "RP",
            extensions = JsonObject(mapOf("vendor" to JsonPrimitive("nut-tavern"))),
        ).withPresetRegexScripts(listOf(sampleScript("a")))

        assertEquals(JsonPrimitive("nut-tavern"), preset.extensions["vendor"])
        assertEquals(1, preset.presetRegexScripts().size)
    }

    @Test
    fun emptyListRemovesRegexScriptsKey() {
        val withScripts = Preset(name = "RP").withPresetRegexScripts(listOf(sampleScript("a")))
        assertTrue(withScripts.extensions.containsKey("regex_scripts"))

        val cleared = withScripts.withPresetRegexScripts(emptyList())
        assertFalse(cleared.extensions.containsKey("regex_scripts"))
        assertTrue(cleared.presetRegexScripts().isEmpty())
    }

    @Test
    fun missingNodeReturnsEmpty() {
        assertTrue(Preset(name = "RP").presetRegexScripts().isEmpty())
    }

    @Test
    fun survivesFullPresetSerialization() {
        val scripts = listOf(sampleScript("trim-think"))
        val preset = Preset(name = "RP").withPresetRegexScripts(scripts)

        val encoded = json.encodeToString(Preset.serializer(), preset)
        val decoded = json.decodeFromString(Preset.serializer(), encoded)

        assertEquals(scripts, decoded.presetRegexScripts())
    }

    @Test
    fun parsesSillyTavernPresetExtensionsRegexScripts() {
        // 酒馆 preset.extensions.regex_scripts 直接吃 @SerialName 对齐的脚本数组。
        val raw = """
            {
                "name": "ST",
                "extensions": {
                    "regex_scripts": [
                        {
                            "id": "st-1",
                            "scriptName": "Strip",
                            "findRegex": "/foo/g",
                            "replaceString": "bar",
                            "placement": [2],
                            "disabled": false,
                            "markdownOnly": false,
                            "promptOnly": false,
                            "runOnEdit": true,
                            "substituteRegex": 0
                        }
                    ]
                }
            }
        """.trimIndent()

        val preset = json.decodeFromString(Preset.serializer(), raw)
        val scripts = preset.presetRegexScripts()

        assertEquals(1, scripts.size)
        assertEquals("Strip", scripts.first().scriptName)
        assertEquals(listOf(2), scripts.first().placement)
        assertNull(scripts.first().minDepth)
    }
}

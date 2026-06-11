package com.nuttavern.data.character

import com.nuttavern.data.character.io.PngTextChunk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 角色卡 JSON / PNG 编解码。**对齐酒馆 `src/endpoints/characters.js` + `character-card-parser.js`**。
 *
 * # 三态识别(对齐 getCharaCardV2, characters.js:450)
 * - 无 `spec` 字段 → V1 卡:顶层平铺字段升级进 `data`(对齐 convertToV2 / charaFormatData:565);
 * - `spec = chara_card_v2` / `chara_card_v3` → 读 `data`(V2 / V3 结构同构,本仓库 [Character] 已对齐 V3 data)。
 *
 * # round-trip
 * 导入时未建模的 data 顶层字段存进 [Character.rawCardData];导出时以已建模字段为准,
 * 再合并 rawCardData 里的未建模键,保证字段不丢(对齐"V3 完整对齐 + 兼容酒馆 JSON"铁律)。
 *
 * # PNG
 * - 读:优先 `ccv3` chunk(V3),回退 `chara` chunk(V2),base64(UTF-8 JSON);
 * - 写:双写 `chara`(V2) + `ccv3`(V3),最大兼容老客户端(对齐 character-card-parser.js write)。
 */
object CharacterCardCodec {

    /** 解码结果:角色本体 + 内嵌世界书(V3 `character_book`,由导入链路提取成独立世界书)。 */
    data class DecodedCard(
        val character: Character,
        val embeddedBook: CharacterBook?,
    )

    private const val SPEC_V3 = "chara_card_v3"
    private const val SPEC_VERSION_V3 = "3.0"
    private const val SPEC_V2 = "chara_card_v2"
    private const val SPEC_VERSION_V2 = "2.0"

    private const val PNG_CHUNK_V3 = "ccv3"
    private const val PNG_CHUNK_V2 = "chara"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // 酒馆卡常把 alternate_greetings / tags 等数组字段写成显式 null。默认行为下 null 喂给
        // 非空 List 会抛 "Expected JsonArray but had JsonNull"。coerceInputValues 让"显式 null +
        // 非空且有默认值"的字段回退默认值(emptyList);explicitNulls=false 让导出侧 null 可空字段
        // 不写出,符合 V3 卡习惯。
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * 解析角色卡 JSON 文本。
     *
     * @throws IllegalArgumentException JSON 非法 / 结构不是对象 / 缺少必要字段时抛出
     */
    fun decodeFromJson(jsonText: String): DecodedCard {
        val root = runCatching { json.parseToJsonElement(jsonText) }
            .getOrNull() as? JsonObject
            ?: throw IllegalArgumentException("角色卡不是合法的 JSON 对象")

        val data = CharacterCardDataConverter.resolveDataObject(root)
        val decoded = CharacterCardDataConverter.decodeDataObject(data, json)
        return DecodedCard(decoded.character, decoded.embeddedBook)
    }

    /**
     * 从 PNG 字节解析角色卡。优先 ccv3(V3) chunk,回退 chara(V2) chunk。
     *
     * @throws IllegalArgumentException 不是合法 PNG / 不含角色卡 chunk / 内容解码失败时抛出
     */
    fun decodeFromPng(image: ByteArray): DecodedCard {
        val chunks = PngTextChunk.readTextChunks(image)
        val base64 = chunks[PNG_CHUNK_V3] ?: chunks[PNG_CHUNK_V2]
            ?: throw IllegalArgumentException("PNG 不包含角色卡数据(缺少 ccv3 / chara chunk)")
        val jsonText = runCatching {
            String(java.util.Base64.getDecoder().decode(base64.trim()), Charsets.UTF_8)
        }.getOrElse {
            throw IllegalArgumentException("角色卡 chunk 不是合法的 base64 数据", it)
        }
        return decodeFromJson(jsonText)
    }

    /**
     * 把角色编码成 V3 卡 JSON 文本(含 spec / spec_version 包裹)。
     *
     * @param embeddedBook 内嵌世界书。导出时回填进 `data.character_book`(对齐酒馆把角色世界书写回卡)。
     *                     传 null 则不写 character_book(角色没有内嵌世界书)。
     */
    fun encodeToV3Json(character: Character, embeddedBook: CharacterBook?): String {
        val data = CharacterCardDataConverter.buildDataObject(character, embeddedBook, json)
        val card = buildJsonObject {
            put(CharacterCardDataConverter.KEY_SPEC, JsonPrimitive(SPEC_V3))
            put(CharacterCardDataConverter.KEY_SPEC_VERSION, JsonPrimitive(SPEC_VERSION_V3))
            put(CharacterCardDataConverter.KEY_DATA, data)
        }
        return json.encodeToString(JsonObject.serializer(), card)
    }

    /** 把角色编码成 PNG 字节,基于 [baseImage] 双写 chara(V2) + ccv3(V3) chunk。 */
    fun encodeToPng(character: Character, embeddedBook: CharacterBook?, baseImage: ByteArray): ByteArray {
        val data = CharacterCardDataConverter.buildDataObject(character, embeddedBook, json)
        val v3Card = buildJsonObject {
            put(CharacterCardDataConverter.KEY_SPEC, JsonPrimitive(SPEC_V3))
            put(CharacterCardDataConverter.KEY_SPEC_VERSION, JsonPrimitive(SPEC_VERSION_V3))
            put(CharacterCardDataConverter.KEY_DATA, data)
        }
        val v2Card = buildJsonObject {
            put(CharacterCardDataConverter.KEY_SPEC, JsonPrimitive(SPEC_V2))
            put(CharacterCardDataConverter.KEY_SPEC_VERSION, JsonPrimitive(SPEC_VERSION_V2))
            put(CharacterCardDataConverter.KEY_DATA, data)
        }
        val encoder = java.util.Base64.getEncoder()
        val v3Base64 = encoder.encodeToString(
            json.encodeToString(JsonObject.serializer(), v3Card).toByteArray(Charsets.UTF_8),
        )
        val v2Base64 = encoder.encodeToString(
            json.encodeToString(JsonObject.serializer(), v2Card).toByteArray(Charsets.UTF_8),
        )
        // LinkedHashMap 保插入序:chara 在前、ccv3 在后,对齐酒馆 write 顺序。
        return PngTextChunk.writeTextChunks(
            baseImage,
            linkedMapOf(PNG_CHUNK_V2 to v2Base64, PNG_CHUNK_V3 to v3Base64),
        )
    }

}

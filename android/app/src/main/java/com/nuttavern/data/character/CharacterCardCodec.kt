package com.nuttavern.data.character

import com.nuttavern.data.character.io.PngTextChunk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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

    private const val KEY_SPEC = "spec"
    private const val KEY_SPEC_VERSION = "spec_version"
    private const val KEY_DATA = "data"
    private const val KEY_CHARACTER_BOOK = "character_book"

    private const val PNG_CHUNK_V3 = "ccv3"
    private const val PNG_CHUNK_V2 = "chara"

    /** [Character] 已建模的 V3 data 顶层键。导出时这些键由角色当前字段覆盖,其余从 rawCardData 回填。 */
    private val MODELED_DATA_KEYS = setOf(
        "name", "description", "personality", "scenario",
        "first_mes", "mes_example", "system_prompt", "post_history_instructions",
        "alternate_greetings", "creator", "character_version", "creator_notes",
        "tags", "extensions", "character_book",
    )

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

        val data = resolveDataObject(root)
        return decodeFromDataObject(data)
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
        val data = buildDataObject(character, embeddedBook)
        val card = buildJsonObject {
            put(KEY_SPEC, JsonPrimitive(SPEC_V3))
            put(KEY_SPEC_VERSION, JsonPrimitive(SPEC_VERSION_V3))
            put(KEY_DATA, data)
        }
        return json.encodeToString(JsonObject.serializer(), card)
    }

    /** 把角色编码成 PNG 字节,基于 [baseImage] 双写 chara(V2) + ccv3(V3) chunk。 */
    fun encodeToPng(character: Character, embeddedBook: CharacterBook?, baseImage: ByteArray): ByteArray {
        val data = buildDataObject(character, embeddedBook)
        val v3Card = buildJsonObject {
            put(KEY_SPEC, JsonPrimitive(SPEC_V3))
            put(KEY_SPEC_VERSION, JsonPrimitive(SPEC_VERSION_V3))
            put(KEY_DATA, data)
        }
        val v2Card = buildJsonObject {
            put(KEY_SPEC, JsonPrimitive(SPEC_V2))
            put(KEY_SPEC_VERSION, JsonPrimitive(SPEC_VERSION_V2))
            put(KEY_DATA, data)
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

    /**
     * 三态识别:返回用于解析的 V3 data 对象。
     * - 无 spec → V1 卡,顶层字段升级成 data;
     * - 有 spec → 取 root.data(V2 / V3 同构)。
     */
    private fun resolveDataObject(root: JsonObject): JsonObject {
        val spec = root[KEY_SPEC]?.jsonPrimitive?.contentOrNull
        if (spec == null) {
            return upgradeV1ToData(root)
        }
        return root[KEY_DATA] as? JsonObject
            ?: throw IllegalArgumentException("角色卡 spec=$spec 但缺少 data 对象")
    }

    /**
     * V1 卡升级:顶层平铺字段重组成 V3 data 结构(对齐 charaFormatData:565)。
     * V1 的 `creatorcomment` 映射成 `creator_notes`;`talkativeness` / `fav` 进 `extensions` 子对象
     * (对齐酒馆 `data.extensions.talkativeness` / `data.extensions.fav`)。V1 没有的字段交给
     * [decodeFromDataObject] 走默认值。
     */
    private fun upgradeV1ToData(root: JsonObject): JsonObject = buildJsonObject {
        copyStringField(root, this, from = "name", to = "name")
        copyStringField(root, this, from = "description", to = "description")
        copyStringField(root, this, from = "personality", to = "personality")
        copyStringField(root, this, from = "scenario", to = "scenario")
        copyStringField(root, this, from = "first_mes", to = "first_mes")
        copyStringField(root, this, from = "mes_example", to = "mes_example")
        copyStringField(root, this, from = "creatorcomment", to = "creator_notes")
        root["tags"]?.let { put("tags", it) }
        val extensions = buildV1Extensions(root)
        if (extensions.isNotEmpty()) put("extensions", JsonObject(extensions))
    }

    /** V1 顶层 talkativeness / fav 升级进 extensions(对齐 charaFormatData:615-616)。 */
    private fun buildV1Extensions(root: JsonObject): Map<String, JsonElement> {
        val extensions = LinkedHashMap<String, JsonElement>()
        (root["talkativeness"] as? JsonPrimitive)?.let { extensions["talkativeness"] = it }
        (root["fav"] as? JsonPrimitive)?.let { extensions["fav"] = it }
        return extensions
    }

    private fun copyStringField(source: JsonObject, target: JsonObjectBuilder, from: String, to: String) {
        val value = source[from]?.jsonPrimitive?.contentOrNull ?: return
        target.put(to, JsonPrimitive(value))
    }

    private fun decodeFromDataObject(data: JsonObject): DecodedCard {
        val character = json.decodeFromJsonElement(Character.serializer(), data)
        val unmodeled = data.filterKeys { it !in MODELED_DATA_KEYS }
        val rawCardData = if (unmodeled.isEmpty()) null else JsonObject(unmodeled)
        return DecodedCard(
            character = character.copy(rawCardData = rawCardData),
            embeddedBook = decodeEmbeddedBook(data),
        )
    }

    private fun decodeEmbeddedBook(data: JsonObject): CharacterBook? {
        val bookElement = data[KEY_CHARACTER_BOOK] as? JsonObject ?: return null
        return runCatching {
            json.decodeFromJsonElement(CharacterBook.serializer(), bookElement)
        }.getOrNull()
    }

    /**
     * 构建导出用的 V3 data 对象:rawCardData 里的未建模键打底,再用角色当前已建模字段覆盖。
     * 这样未建模字段(group_only_greetings 等)不丢,已建模字段始终是角色最新值。
     */
    private fun buildDataObject(character: Character, embeddedBook: CharacterBook?): JsonObject {
        val modeled = json.encodeToJsonElement(Character.serializer(), character) as JsonObject
        val merged = LinkedHashMap<String, JsonElement>()
        // 1) 未建模键打底(只取 rawCardData 中不属于已建模集合的键,防止过期已建模值覆盖)
        character.rawCardData?.forEach { (key, value) ->
            if (key !in MODELED_DATA_KEYS) merged[key] = value
        }
        // 2) 已建模字段覆盖(排除 Character 的内部 / 非 V3 字段)
        modeled.forEach { (key, value) ->
            if (key in MODELED_DATA_KEYS) merged[key] = value
        }
        // 3) character_book 用导出链路提供的内嵌世界书;无则移除该键
        if (embeddedBook != null) {
            merged[KEY_CHARACTER_BOOK] = json.encodeToJsonElement(CharacterBook.serializer(), embeddedBook)
        } else {
            merged.remove(KEY_CHARACTER_BOOK)
        }
        return JsonObject(merged)
    }
}

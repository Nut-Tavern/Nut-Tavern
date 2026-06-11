package com.nuttavern.data.character

import com.nuttavern.data.regex.RegexScript
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class CharacterCardDecodedData(
    val character: Character,
    val embeddedBook: CharacterBook?,
)

private data class DecodedRegexScripts(
    val scripts: List<RegexScript>,
    val keepTopLevelRaw: Boolean,
)

private data class RegexScriptElementDecode(
    val scripts: List<RegexScript>,
    val succeeded: Boolean,
)

/**
 * 角色卡 `data` 转换层。codec 只负责外层 JSON / PNG 包装,这里统一处理字段兼容与 round-trip。
 *
 * 原则:已兼容且成功解析的字段进入 [Character] 并由运行时消费;未兼容字段 / 当前无法解析的兼容字段
 * 原样存入 [Character.rawCardData],不消费、不解释。未来兼容时可从已存 raw 数据迁出;当前重新导出
 * 分发也不会丢这些字段。
 */
internal object CharacterCardDataConverter {
    const val KEY_SPEC = "spec"
    const val KEY_SPEC_VERSION = "spec_version"
    const val KEY_DATA = "data"
    const val KEY_CHARACTER_BOOK = "character_book"

    private const val DEFAULT_IMPORTED_NAME = "未命名角色"
    private const val KEY_ALTERNATE_GREETINGS = "alternate_greetings"
    private const val KEY_TAGS = "tags"
    private const val KEY_EXTENSIONS = "extensions"
    private const val KEY_REGEX_SCRIPTS = "regex_scripts"

    /** [Character] 已兼容的 V3 data 顶层键。导出时这些键由当前字段覆盖,其余从 rawCardData 回填。 */
    private val modeledDataKeys = setOf(
        "name", "description", "personality", "scenario",
        "first_mes", "mes_example", "system_prompt", "post_history_instructions",
        KEY_ALTERNATE_GREETINGS, "creator", "character_version", "creator_notes",
        KEY_TAGS, KEY_EXTENSIONS, KEY_CHARACTER_BOOK,
    )

    private val stringDataKeys = setOf(
        "name", "description", "personality", "scenario",
        "first_mes", "mes_example", "system_prompt", "post_history_instructions",
        "creator", "character_version", "creator_notes",
    )

    /**
     * 三态识别:返回用于解析的 V3 data 对象。
     * - 无 spec → V1 卡,顶层字段升级成 data;
     * - 有 spec → 取 root.data(V2 / V3 同构)。
     */
    fun resolveDataObject(root: JsonObject): JsonObject {
        val spec = root[KEY_SPEC]?.jsonPrimitive?.contentOrNull
        if (spec == null) return upgradeV1ToData(root)
        return root[KEY_DATA] as? JsonObject
            ?: throw IllegalArgumentException("角色卡 spec=$spec 但缺少 data 对象")
    }

    fun decodeDataObject(data: JsonObject, json: Json): CharacterCardDecodedData {
        val normalizedData = normalizeDataForImport(data)
        val embeddedBook = decodeEmbeddedBook(normalizedData, json)
        val regexScripts = decodeRegexScripts(data, json)
        val characterData = withoutNestedModeledFields(normalizedData)
        val character = json.decodeFromJsonElement(Character.serializer(), characterData)
        val rawRoundTripData = collectRawRoundTripData(data, embeddedBook, regexScripts)
        val rawCardData = if (rawRoundTripData.isEmpty()) null else JsonObject(rawRoundTripData)
        return CharacterCardDecodedData(
            character = character.copy(
                characterBook = embeddedBook,
                regexScripts = regexScripts.scripts,
                rawCardData = rawCardData,
            ),
            embeddedBook = embeddedBook,
        )
    }

    /**
     * 构建导出用的 V3 data 对象:rawCardData 未兼容 / 未能解析键打底,再用当前已兼容字段覆盖。
     *
     * 这样已兼容字段反映用户最新编辑;未兼容字段继续原样带回导出卡,不因本仓库暂未消费而丢失。
     */
    fun buildDataObject(character: Character, embeddedBook: CharacterBook?, json: Json): JsonObject {
        val modeled = json.encodeToJsonElement(Character.serializer(), character) as JsonObject
        val merged = LinkedHashMap<String, JsonElement>()

        character.rawCardData?.forEach { (key, value) ->
            if (shouldKeepRawDataOnExport(key, character, embeddedBook)) merged[key] = value
        }

        modeled.forEach { (key, value) ->
            if (key == KEY_CHARACTER_BOOK) return@forEach
            if (key == KEY_EXTENSIONS) {
                merged[key] = buildExtensionsForExport(character, json)
                return@forEach
            }
            if (key in modeledDataKeys) merged[key] = value
        }

        if (embeddedBook != null) {
            merged[KEY_CHARACTER_BOOK] = json.encodeToJsonElement(CharacterBook.serializer(), embeddedBook)
        }
        return JsonObject(merged)
    }

    private fun shouldKeepRawDataOnExport(
        key: String,
        character: Character,
        embeddedBook: CharacterBook?,
    ): Boolean {
        if (key !in modeledDataKeys) return true
        if (key == KEY_CHARACTER_BOOK && embeddedBook == null) return true
        return false
    }

    private fun buildExtensionsForExport(character: Character, json: Json): JsonObject {
        val extensions = LinkedHashMap<String, JsonElement>()
        character.extensions.forEach { (key, value) -> extensions[key] = value }

        if (character.regexScripts.isNotEmpty()) {
            extensions[KEY_REGEX_SCRIPTS] = json.encodeToJsonElement(
                ListSerializer(RegexScript.serializer()),
                character.regexScripts,
            )
        } else if (isDecodableRegexScriptsArray(extensions[KEY_REGEX_SCRIPTS], json)) {
            extensions.remove(KEY_REGEX_SCRIPTS)
        }
        return JsonObject(extensions)
    }

    private fun isDecodableRegexScriptsArray(element: JsonElement?, json: Json): Boolean {
        if (element !is JsonArray) return false
        return runCatching {
            json.decodeFromJsonElement(ListSerializer(RegexScript.serializer()), element)
        }.isSuccess
    }

    /**
     * V1 卡升级:顶层平铺字段重组成 V3 data 结构(对齐 charaFormatData:565)。
     * V1 的 `creatorcomment` 映射成 `creator_notes`;`talkativeness` / `fav` 进 `extensions` 子对象。
     */
    private fun upgradeV1ToData(root: JsonObject): JsonObject = buildJsonObject {
        copyStringField(root, this, from = "name", to = "name")
        copyStringField(root, this, from = "description", to = "description")
        copyStringField(root, this, from = "personality", to = "personality")
        copyStringField(root, this, from = "scenario", to = "scenario")
        copyStringField(root, this, from = "first_mes", to = "first_mes")
        copyStringField(root, this, from = "mes_example", to = "mes_example")
        copyStringField(root, this, from = "creatorcomment", to = "creator_notes")
        root[KEY_TAGS]?.let { put(KEY_TAGS, it) }
        val extensions = buildV1Extensions(root)
        if (extensions.isNotEmpty()) put(KEY_EXTENSIONS, JsonObject(extensions))
    }

    /** V1 顶层 talkativeness / fav 升级进 extensions(对齐 charaFormatData:615-616)。 */
    private fun buildV1Extensions(root: JsonObject): Map<String, JsonElement> {
        val extensions = LinkedHashMap<String, JsonElement>()
        (root["talkativeness"] as? JsonPrimitive)?.let { extensions["talkativeness"] = it }
        (root["fav"] as? JsonPrimitive)?.let { extensions["fav"] = it }
        return extensions
    }

    private fun copyStringField(source: JsonObject, target: JsonObjectBuilder, from: String, to: String) {
        val value = source[from]?.asStringOrNull() ?: return
        target.put(to, JsonPrimitive(value))
    }

    private fun normalizeDataForImport(data: JsonObject): JsonObject {
        val normalized = LinkedHashMap<String, JsonElement>()
        data.forEach { (key, value) ->
            when (key) {
                in stringDataKeys -> value.asStringOrNull()?.let { normalized[key] = JsonPrimitive(it) }
                KEY_ALTERNATE_GREETINGS -> normalized[key] = normalizeStringList(value, splitCommaString = false)
                KEY_TAGS -> normalized[key] = normalizeStringList(value, splitCommaString = true)
                KEY_EXTENSIONS -> normalized[key] = value as? JsonObject ?: Character.EMPTY_JSON_OBJECT
                KEY_CHARACTER_BOOK -> if (value is JsonObject) normalized[key] = value
                else -> normalized[key] = value
            }
        }

        val importedName = (normalized["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (importedName.isBlank()) normalized["name"] = JsonPrimitive(DEFAULT_IMPORTED_NAME)
        if (KEY_EXTENSIONS !in normalized) normalized[KEY_EXTENSIONS] = Character.EMPTY_JSON_OBJECT
        return JsonObject(normalized)
    }

    private fun normalizeStringList(value: JsonElement, splitCommaString: Boolean): JsonArray {
        val values = when (value) {
            is JsonArray -> value.mapNotNull { it.asStringOrNull() }
            is JsonPrimitive -> {
                val content = value.asStringOrNull()?.trim().orEmpty()
                when {
                    content.isBlank() -> emptyList()
                    splitCommaString -> content.split(',', '，').map { it.trim() }.filter { it.isNotBlank() }
                    else -> listOf(content)
                }
            }
            else -> emptyList()
        }
        return JsonArray(values.map(::JsonPrimitive))
    }

    private fun JsonElement.asStringOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun decodeEmbeddedBook(data: JsonObject, json: Json): CharacterBook? {
        val bookElement = data[KEY_CHARACTER_BOOK] as? JsonObject ?: return null
        return runCatching {
            json.decodeFromJsonElement(CharacterBook.serializer(), bookElement)
        }.getOrNull()
    }

    private fun decodeRegexScripts(data: JsonObject, json: Json): DecodedRegexScripts {
        val extensions = data[KEY_EXTENSIONS] as? JsonObject
        decodeRegexScriptsElement(extensions?.get(KEY_REGEX_SCRIPTS), json)?.let { extensionDecode ->
            if (extensionDecode.succeeded) {
                return DecodedRegexScripts(extensionDecode.scripts, keepTopLevelRaw = false)
            }
        }

        val topLevelElement = data[KEY_REGEX_SCRIPTS]
        decodeRegexScriptsElement(topLevelElement, json)?.let { topLevelDecode ->
            if (topLevelDecode.succeeded) {
                return DecodedRegexScripts(topLevelDecode.scripts, keepTopLevelRaw = false)
            }
        }

        return DecodedRegexScripts(emptyList(), keepTopLevelRaw = topLevelElement != null)
    }

    private fun decodeRegexScriptsElement(element: JsonElement?, json: Json): RegexScriptElementDecode? {
        val scriptsElement = element ?: return null
        if (scriptsElement !is JsonArray) return RegexScriptElementDecode(emptyList(), succeeded = false)
        return runCatching {
            json.decodeFromJsonElement(ListSerializer(RegexScript.serializer()), scriptsElement)
        }.fold(
            onSuccess = { RegexScriptElementDecode(it, succeeded = true) },
            onFailure = { RegexScriptElementDecode(emptyList(), succeeded = false) },
        )
    }

    private fun withoutNestedModeledFields(data: JsonObject): JsonObject {
        return JsonObject(data.filterKeys { key -> key != KEY_CHARACTER_BOOK && key != KEY_REGEX_SCRIPTS })
    }

    private fun collectRawRoundTripData(
        data: JsonObject,
        embeddedBook: CharacterBook?,
        regexScripts: DecodedRegexScripts,
    ): Map<String, JsonElement> {
        val rawFields = LinkedHashMap<String, JsonElement>()
        data.forEach { (key, value) ->
            when {
                key == KEY_REGEX_SCRIPTS && regexScripts.keepTopLevelRaw -> rawFields[key] = value
                key == KEY_REGEX_SCRIPTS -> Unit
                key !in modeledDataKeys -> rawFields[key] = value
                key == KEY_CHARACTER_BOOK && embeddedBook == null -> rawFields[key] = value
            }
        }
        return rawFields
    }
}

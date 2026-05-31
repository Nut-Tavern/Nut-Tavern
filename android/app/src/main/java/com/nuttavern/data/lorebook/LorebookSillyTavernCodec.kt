package com.nuttavern.data.lorebook

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

/**
 * 独立世界书文件的酒馆 JSON ↔ 内部 [Lorebook] 编解码器。
 *
 * 只在 JsonElement 层做格式适配,不改内部 [LorebookEntry] 模型与 DataStore 存储格式。
 * 隔离酒馆独立世界书文件的两处与内部表达的差异:
 *
 * 1. **entries map ↔ list**:酒馆文件 `entries` 是以 uid 字符串为键的对象
 *    (`{ "0": {...}, "1": {...} }`,world-info.js:2542 `JSON.stringify({ entries })`),
 *    本仓库用 `List<LorebookEntry>`。导入取 map 的 values、按 `displayIndex` 排序后转 list
 *    (显示顺序进 list);导出按 list 下标重写 `displayIndex` 再以 uid 为键组装回 map。
 * 2. **character_filter ↔ characterFilter 命名**:独立世界书文件里条目用 camelCase
 *    `characterFilter`(world-info.js:2126 / 4704 运行时结构),而内部 [LorebookEntry] 标的是
 *    `@SerialName("character_filter")`(对齐 V3 角色内嵌 character_book 的 snake_case 形态)。
 *    导入把 `characterFilter` 重命名为 `character_filter`,导出反向。
 *
 * 顺序的两套机制(已核实 world-info.js:88/110 + LorebookEngine:110 + LorebookDetailScreen):
 * - **显示顺序**:酒馆用 `displayIndex`,本仓库用 list 下标。导入按 displayIndex 排序写入 list,
 *   导出按 list 下标重写 displayIndex,顺序无损,不引入 displayIndex 字段。
 * - **注入顺序**:酒馆与本仓库都用 `order`(insertion_order)字段,[LorebookEntry.order] 原样搬运,
 *   运行时 [com.nuttavern.lorebook.LorebookEngine] 按 order 降序注入,与显示顺序独立。
 *
 * 书名:酒馆独立世界书文件 JSON 本体不写 name,书名取自文件名(world-info.js:5777 / 2543)。
 * 导入时 [Lorebook.name] 取文件名;导出不写顶层 name(严格对齐酒馆 round-trip)。
 *
 * 第三方扩展键(条目 extensions 子对象里的未知键)不保留:[LorebookEntry] 无 extensions 透传位,
 * 经 `ignoreUnknownKeys` 丢弃。这是 [LorebookEntry] 的既有设计,本 codec 不额外处理。
 */
object LorebookSillyTavernCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private const val KEY_ENTRIES = "entries"
    private const val KEY_UID = "uid"
    private const val KEY_DISPLAY_INDEX = "displayIndex"
    private const val KEY_ROLE = "role"
    private const val KEY_DELAY_UNTIL_RECURSION = "delayUntilRecursion"
    private const val ST_CHARACTER_FILTER = "characterFilter"
    private const val INTERNAL_CHARACTER_FILTER = "character_filter"

    /**
     * 把酒馆独立世界书文件 JSON 解析为内部 [Lorebook]。
     *
     * @param jsonText 酒馆导出的世界书 JSON 文本
     * @param bookName 书名(取自文件名,JSON 本体不含)
     * @throws IllegalArgumentException JSON 结构非法(顶层非对象 / 无 entries)时抛出,由调用方兜底
     */
    fun decodeFromSillyTavern(jsonText: String, bookName: String): Lorebook {
        val root = json.parseToJsonElement(jsonText) as? JsonObject
            ?: throw IllegalArgumentException("世界书 JSON 顶层不是对象")
        val entriesMap = root[KEY_ENTRIES] as? JsonObject
            ?: throw IllegalArgumentException("世界书 JSON 缺少 entries 对象")

        // 酒馆用 map 的键作条目身份(world-info.js:2360 `Object.keys(data.entries).map(uid => ...)`),
        // entry 内的 uid 字段只是冗余。这里以 map 键为准:键能解析成 Int 就作 uid,条目缺 uid 字段时
        // 用键兜底,避免"缺 uid 字段 → 全部默认 0 → 导出 map 键碰撞 → 条目丢失"。
        val keyedEntries = entriesMap.entries
            .mapNotNull { (key, value) ->
                val entryObject = value as? JsonObject ?: return@mapNotNull null
                val keyUid = key.toIntOrNull()
                val uid = entryObject.intField(KEY_UID) ?: keyUid ?: return@mapNotNull null
                EntryWithUid(uid = uid, entryObject = withUid(entryObject, uid))
            }

        // 显示顺序:按 displayIndex 升序,缺失回退到 uid(对齐酒馆 world-info.js:2365 `?? entry.uid`)。
        val orderedEntries = keyedEntries
            .sortedBy { it.entryObject.intField(KEY_DISPLAY_INDEX) ?: it.uid }
            .map { it.entryObject }

        val entries = orderedEntries.map { entryObject ->
            json.decodeFromJsonElement(
                LorebookEntry.serializer(),
                sanitizeEntryForImport(renameFilterForImport(entryObject)),
            )
        }

        return Lorebook(
            id = UUID.randomUUID().toString(),
            name = bookName,
            entries = entries,
        )
    }

    /** 条目对象 + 解析定的 uid(map 键优先,字段兜底)。 */
    private data class EntryWithUid(val uid: Int, val entryObject: JsonObject)

    /** 把确定的 uid 写回条目对象,保证 decode 出的 [LorebookEntry.uid] 与 map 键一致。 */
    private fun withUid(entryObject: JsonObject, uid: Int): JsonObject {
        if (entryObject.intField(KEY_UID) == uid) return entryObject
        return buildJsonObject {
            entryObject.forEach { (key, value) -> put(key, value) }
            put(KEY_UID, JsonPrimitive(uid))
        }
    }

    /**
     * 把内部 [Lorebook] 编码为酒馆独立世界书文件 JSON 文本。
     *
     * entries 以 uid 字符串为键组装成 map;displayIndex 按 list 下标重写(显示顺序还原);
     * character_filter 重命名回 characterFilter;不写顶层 name。
     */
    fun encodeToSillyTavern(lorebook: Lorebook): String {
        val entriesMap = buildJsonObject {
            lorebook.entries.forEachIndexed { displayIndex, entry ->
                val encoded = json.encodeToJsonElement(LorebookEntry.serializer(), entry) as JsonObject
                val withDisplayIndex = buildJsonObject {
                    renameFilterForExport(encoded).forEach { (key, value) -> put(key, value) }
                    put(KEY_DISPLAY_INDEX, JsonPrimitive(displayIndex))
                }
                put(entry.uid.toString(), withDisplayIndex)
            }
        }
        val root = buildJsonObject { put(KEY_ENTRIES, entriesMap) }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /** 导入:characterFilter(camelCase) → character_filter(内部 SerialName)。 */
    private fun renameFilterForImport(entry: JsonObject): JsonObject {
        if (ST_CHARACTER_FILTER !in entry) return entry
        return buildJsonObject {
            entry.forEach { (key, value) ->
                if (key == ST_CHARACTER_FILTER) put(INTERNAL_CHARACTER_FILTER, value) else put(key, value)
            }
        }
    }

    /** 导出:character_filter(内部 SerialName) → characterFilter(酒馆运行时结构)。 */
    private fun renameFilterForExport(entry: JsonObject): JsonObject {
        if (INTERNAL_CHARACTER_FILTER !in entry) return entry
        return buildJsonObject {
            entry.forEach { (key, value) ->
                if (key == INTERNAL_CHARACTER_FILTER) put(ST_CHARACTER_FILTER, value) else put(key, value)
            }
        }
    }

    /**
     * 把酒馆条目里与内部 [LorebookEntry] 类型不兼容的字段规整,避免反序列化抛异常:
     *
     * - `role`:酒馆默认 `null`(非 atDepth 时不指定角色,world-info.js:4037/5124 `entry.role ?? SYSTEM`),
     *   内部模型是非空 `Int`。遇 null 移除该键,让默认值 [WiRole.SYSTEM] 生效。
     * - `delayUntilRecursion`:酒馆历史上是 Boolean(false/true),内部模型是 `Int`。
     *   Boolean 转成 0/1(对齐 [com.nuttavern.data.character.CharacterBook] 的双容口径)。
     */
    private fun sanitizeEntryForImport(entry: JsonObject): JsonObject {
        val roleIsNull = entry[KEY_ROLE] is JsonNull
        val delayBool = (entry[KEY_DELAY_UNTIL_RECURSION] as? JsonPrimitive)?.booleanOrNull
        if (!roleIsNull && delayBool == null) return entry

        return buildJsonObject {
            entry.forEach { (key, value) ->
                when {
                    key == KEY_ROLE && roleIsNull -> Unit // 移除 null role,用默认 SYSTEM
                    key == KEY_DELAY_UNTIL_RECURSION && delayBool != null ->
                        put(key, JsonPrimitive(if (delayBool) 1 else 0))
                    else -> put(key, value)
                }
            }
        }
    }

    private fun JsonObject.intField(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull
}

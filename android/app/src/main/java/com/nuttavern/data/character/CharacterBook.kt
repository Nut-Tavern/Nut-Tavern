package com.nuttavern.data.character

import com.nuttavern.data.lorebook.CharacterFilter
import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.data.lorebook.SelectiveLogic
import com.nuttavern.data.lorebook.WiPosition
import com.nuttavern.data.lorebook.WiRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * V3 角色内嵌世界书。当前只做存盘,运行时由 LorebookEngine 消费(经 [CharacterBookEntry.toLorebookEntry])。
 */
@Serializable
data class CharacterBook(
    val name: String? = null,
    val description: String? = null,
    @SerialName("scan_depth") val scanDepth: Int? = null,
    @SerialName("token_budget") val tokenBudget: Int? = null,
    @SerialName("recursive_scanning") val recursiveScanning: Boolean? = null,
    val extensions: JsonObject = Character.EMPTY_JSON_OBJECT,
    val entries: List<CharacterBookEntry> = emptyList(),
)

/**
 * V3 世界书条目。只保留 V3 spec 顶层字段;酒馆把全部高级字段放在 [extensions] 子对象里
 * (position/role 是整数,exclude_recursion 等是 snake_case,useProbability/selectiveLogic 是 camelCase)。
 *
 * 高级字段不再平铺到顶层 @SerialName——那样读真实 V3 卡片会全丢成 null。
 * [extensions] 原样存盘保证 JSON round-trip 无损(含 display_index / outlet_name / 第三方键);
 * 字段映射统一在 [toLorebookEntry] / [toCharacterBookEntry] 完成,对齐酒馆
 * convertCharacterBook(world-info.js:5498) 与 convertWorldInfoToCharacterBook(characters.js:663)。
 */
@Serializable
data class CharacterBookEntry(
    val keys: List<String> = emptyList(),
    val content: String = "",
    val extensions: JsonObject = Character.EMPTY_JSON_OBJECT,
    // V3 spec 把 enabled 列为必填,合规卡总会带此字段。缺失时本仓库按"启用"兜底(默认 true),
    // 与酒馆 `disable: !entry.enabled`(undefined→禁用)相反。只在粗制滥造/残缺第三方卡触发,
    // 取"用户写了条目内容大概率想用"的直觉,有意保留此差异。
    val enabled: Boolean = true,
    @SerialName("insertion_order") val insertionOrder: Int = 100,
    @SerialName("case_sensitive") val caseSensitive: Boolean? = null,
    val name: String? = null,
    val priority: Int? = null,
    val id: Int? = null,
    val comment: String? = null,
    val selective: Boolean? = null,
    @SerialName("secondary_keys") val secondaryKeys: List<String> = emptyList(),
    @SerialName("constant") val isConstant: Boolean? = null,
    val position: String? = null,
    @SerialName("use_regex") val useRegex: Boolean? = null,
)

// ── CharacterBookEntry ↔ LorebookEntry 转换 ──

/**
 * 将 V3 CharacterBookEntry 转为内部 LorebookEntry。
 *
 * **运行时已无调用方**:角色世界书重构后,对话激活只读独立世界书(characterLorebookId / lorebookIds),
 * 不再把 character_book 转成临时 Lorebook 激活。保留本函数给角色卡导入用——导入带 character_book
 * 的 V3 卡时,把内嵌条目提取成独立世界书条目(对齐酒馆 convertCharacterBook)。
 *
 * 严格对齐酒馆 convertCharacterBook(world-info.js:5498):高级字段全部从 [extensions] 读,
 * 缺失时取酒馆同款默认值;position 优先 extensions 整数,回退顶层 V2 字符串。
 *
 * @param fallbackUid 条目缺 id 时的兜底 uid(酒馆按数组下标兜底,见 world-info.js:5503)。
 */
fun CharacterBookEntry.toLorebookEntry(fallbackUid: Int = 0): LorebookEntry {
    val ext = extensions
    return LorebookEntry(
        uid = id ?: fallbackUid,
        key = keys,
        keysecondary = secondaryKeys,
        comment = comment ?: "",
        content = content,
        constant = isConstant ?: false,
        selective = selective ?: false,
        order = insertionOrder,
        position = ext.intField(BookEntryExt.POSITION) ?: parsePositionString(position),
        disable = !enabled,
        addMemo = !comment.isNullOrEmpty(),
        probability = ext.intField(BookEntryExt.PROBABILITY) ?: 100,
        useProbability = ext.boolField(BookEntryExt.USE_PROBABILITY) ?: true,
        depth = ext.intField(BookEntryExt.DEPTH) ?: LorebookEntry.DEFAULT_DEPTH,
        selectiveLogic = ext.intField(BookEntryExt.SELECTIVE_LOGIC) ?: SelectiveLogic.AND_ANY,
        outletName = ext.stringField(BookEntryExt.OUTLET_NAME) ?: "",
        group = ext.stringField(BookEntryExt.GROUP) ?: "",
        groupOverride = ext.boolField(BookEntryExt.GROUP_OVERRIDE) ?: false,
        groupWeight = ext.intField(BookEntryExt.GROUP_WEIGHT) ?: LorebookEntry.DEFAULT_WEIGHT,
        entryScanDepth = ext.intField(BookEntryExt.SCAN_DEPTH),
        entryCaseSensitive = ext.boolField(BookEntryExt.CASE_SENSITIVE),
        entryMatchWholeWords = ext.boolField(BookEntryExt.MATCH_WHOLE_WORDS),
        entryUseGroupScoring = ext.boolField(BookEntryExt.USE_GROUP_SCORING),
        automationId = ext.stringField(BookEntryExt.AUTOMATION_ID) ?: "",
        role = ext.intField(BookEntryExt.ROLE) ?: WiRole.SYSTEM,
        vectorized = ext.boolField(BookEntryExt.VECTORIZED) ?: false,
        sticky = ext.intField(BookEntryExt.STICKY),
        cooldown = ext.intField(BookEntryExt.COOLDOWN),
        delay = ext.intField(BookEntryExt.DELAY),
        excludeRecursion = ext.boolField(BookEntryExt.EXCLUDE_RECURSION) ?: false,
        preventRecursion = ext.boolField(BookEntryExt.PREVENT_RECURSION) ?: false,
        delayUntilRecursion = ext.delayUntilRecursionField() ?: 0,
        ignoreBudget = ext.boolField(BookEntryExt.IGNORE_BUDGET) ?: false,
        triggers = ext.stringListField(BookEntryExt.TRIGGERS),
        matchPersonaDescription = ext.boolField(BookEntryExt.MATCH_PERSONA_DESCRIPTION) ?: false,
        matchCharacterDescription = ext.boolField(BookEntryExt.MATCH_CHARACTER_DESCRIPTION) ?: false,
        matchCharacterPersonality = ext.boolField(BookEntryExt.MATCH_CHARACTER_PERSONALITY) ?: false,
        matchCharacterDepthPrompt = ext.boolField(BookEntryExt.MATCH_CHARACTER_DEPTH_PROMPT) ?: false,
        matchScenario = ext.boolField(BookEntryExt.MATCH_SCENARIO) ?: false,
        matchCreatorNotes = ext.boolField(BookEntryExt.MATCH_CREATOR_NOTES) ?: false,
        characterFilter = ext.characterFilterField(),
    )
}

/**
 * 将编辑后的 LorebookEntry 转回 CharacterBookEntry 用于存盘。
 *
 * **当前无运行时调用方**:角色世界书重构后,角色卡只引用独立世界书(characterLorebookId / lorebookIds),
 * 不再内嵌编辑 character_book。保留本函数是给角色卡导出用——导出 V3 卡时把角色世界书条目回填成
 * character_book 字段(对齐酒馆 convertWorldInfoToCharacterBook)。已有 round-trip 单测覆盖,先行存档。
 *
 * 严格对齐酒馆 convertWorldInfoToCharacterBook(characters.js:663):
 * - 顶层只写 V3 spec 字段;position 写 V2 兼容字符串(before_char/after_char),use_regex 恒 true;
 * - 高级字段写进 [extensions],先铺开 [original] 的 extensions 再覆盖已知键,
 *   保留 display_index 等本仓库不建模但需 round-trip 的字段;
 * - name / priority / case_sensitive 顶层字段经 copy 从 [original] 保留。
 *
 * character_filter 不在酒馆 character_book 格式内,作为本仓库扩展存入 extensions,酒馆导入会忽略。
 */
fun LorebookEntry.toCharacterBookEntry(original: CharacterBookEntry): CharacterBookEntry {
    val mergedExtensions = buildJsonObject {
        original.extensions.forEach { (key, value) -> put(key, value) }
        put(BookEntryExt.POSITION, position)
        put(BookEntryExt.EXCLUDE_RECURSION, excludeRecursion)
        put(BookEntryExt.PREVENT_RECURSION, preventRecursion)
        // off 写布尔 false;启用时写精确深度整数。对齐酒馆 delay_until_recursion 的 boolean|number 双形态。
        put(BookEntryExt.DELAY_UNTIL_RECURSION, if (delayUntilRecursion > 0) JsonPrimitive(delayUntilRecursion) else JsonPrimitive(false))
        putNullableInt(BookEntryExt.PROBABILITY, probability)
        put(BookEntryExt.USE_PROBABILITY, useProbability)
        put(BookEntryExt.DEPTH, depth)
        put(BookEntryExt.SELECTIVE_LOGIC, selectiveLogic)
        put(BookEntryExt.OUTLET_NAME, outletName)
        put(BookEntryExt.GROUP, group)
        put(BookEntryExt.GROUP_OVERRIDE, groupOverride)
        putNullableInt(BookEntryExt.GROUP_WEIGHT, groupWeight)
        putNullableInt(BookEntryExt.SCAN_DEPTH, entryScanDepth)
        putNullableBool(BookEntryExt.CASE_SENSITIVE, entryCaseSensitive)
        putNullableBool(BookEntryExt.MATCH_WHOLE_WORDS, entryMatchWholeWords)
        put(BookEntryExt.USE_GROUP_SCORING, entryUseGroupScoring ?: false)
        put(BookEntryExt.AUTOMATION_ID, automationId)
        put(BookEntryExt.ROLE, role)
        put(BookEntryExt.VECTORIZED, vectorized)
        putNullableInt(BookEntryExt.STICKY, sticky)
        putNullableInt(BookEntryExt.COOLDOWN, cooldown)
        putNullableInt(BookEntryExt.DELAY, delay)
        put(BookEntryExt.MATCH_PERSONA_DESCRIPTION, matchPersonaDescription)
        put(BookEntryExt.MATCH_CHARACTER_DESCRIPTION, matchCharacterDescription)
        put(BookEntryExt.MATCH_CHARACTER_PERSONALITY, matchCharacterPersonality)
        put(BookEntryExt.MATCH_CHARACTER_DEPTH_PROMPT, matchCharacterDepthPrompt)
        put(BookEntryExt.MATCH_SCENARIO, matchScenario)
        put(BookEntryExt.MATCH_CREATOR_NOTES, matchCreatorNotes)
        put(BookEntryExt.TRIGGERS, JsonArray(triggers.map(::JsonPrimitive)))
        put(BookEntryExt.IGNORE_BUDGET, ignoreBudget)
        putCharacterFilter(characterFilter)
    }
    return original.copy(
        id = uid,
        keys = key,
        secondaryKeys = keysecondary,
        comment = comment.ifBlank { null },
        content = content,
        enabled = !disable,
        insertionOrder = order,
        isConstant = constant,
        selective = selective,
        // 顶层 case_sensitive 是 V3 spec 字段,与 extensions.case_sensitive 同步,避免编辑后顶层留陈旧值
        caseSensitive = entryCaseSensitive,
        position = formatPositionString(position),
        useRegex = true,
        extensions = mergedExtensions,
    )
}

private fun parsePositionString(position: String?): Int =
    if (position == "before_char") WiPosition.BEFORE else WiPosition.AFTER

private fun formatPositionString(position: Int): String =
    if (position == WiPosition.BEFORE) "before_char" else "after_char"

// ── CharacterBook ↔ Lorebook 整本转换(角色卡导入提取 / 导出回填) ──

/**
 * 把内嵌 [CharacterBook] 提取成独立 [Lorebook]。
 *
 * 角色卡导入链路用:带 character_book 的 V3 卡导入时,把内嵌世界书提取成独立世界书写进
 * LorebookRepository,再设 [Character.characterLorebookId] 指向它(对齐酒馆 importEmbeddedWorldInfo,
 * world-info.js:5612 + convertCharacterBook:5498)。
 *
 * @param lorebookId 新世界书 id(调用方生成 UUID 传入)
 * @param characterName 角色名,用于书名兜底(对齐酒馆 `${name}'s Lorebook`)
 */
fun CharacterBook.toLorebook(lorebookId: String, characterName: String): Lorebook {
    val resolvedName = name?.takeIf { it.isNotBlank() }
        ?: characterName.takeIf { it.isNotBlank() }?.let { "${it}的世界书" }
        ?: "世界书"
    return Lorebook(
        id = lorebookId,
        name = resolvedName,
        description = description ?: "",
        scanDepth = scanDepth ?: Lorebook().scanDepth,
        tokenBudget = tokenBudget ?: Lorebook().tokenBudget,
        recursiveScanning = recursiveScanning ?: false,
        entries = entries.mapIndexed { index, entry -> entry.toLorebookEntry(fallbackUid = index) },
    )
}

/**
 * 把独立 [Lorebook] 转回内嵌 [CharacterBook],用于角色卡导出回填 `character_book`。
 *
 * 导出时用户的角色世界书是独立世界书([Character.characterLorebookId] 指向),可能在世界书模块
 * 编辑过,导出应反映最新内容(对齐"所见即所得")。每个条目按 uid 匹配 [original](导入时存的
 * 原始 character_book)对应条目,复用其 extensions 做 round-trip 基底(保留 display_index 等
 * 本仓库不建模的键);匹配不到(新增条目)用空 [CharacterBookEntry] 兜底。
 *
 * @param original 导入时携带的原始 character_book,提供 extensions round-trip 基底;null 则全用空基底
 */
fun Lorebook.toCharacterBook(original: CharacterBook?): CharacterBook {
    val originalEntriesByUid = original?.entries
        ?.mapNotNull { entry -> entry.id?.let { it to entry } }
        ?.toMap()
        ?: emptyMap()
    return CharacterBook(
        name = name.ifBlank { original?.name },
        description = description.ifBlank { null } ?: original?.description,
        scanDepth = scanDepth,
        tokenBudget = tokenBudget,
        recursiveScanning = recursiveScanning,
        extensions = original?.extensions ?: Character.EMPTY_JSON_OBJECT,
        entries = entries.map { entry ->
            val base = originalEntriesByUid[entry.uid] ?: CharacterBookEntry()
            entry.toCharacterBookEntry(base)
        },
    )
}

// ── extensions 读写工具 ──

private val characterBookJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.intField(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.boolField(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

private fun JsonObject.stringListField(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { element ->
        (element as? JsonPrimitive)?.let { if (it.isString) it.content else null }
    } ?: emptyList()

/**
 * 酒馆 `delay_until_recursion` 历史上是 Boolean(true→1 / false→0),新版可为 Int 深度。两种都接受。
 */
private fun JsonObject.delayUntilRecursionField(): Int? {
    val primitive = this[BookEntryExt.DELAY_UNTIL_RECURSION] as? JsonPrimitive ?: return null
    primitive.intOrNull?.let { return it }
    return when (primitive.booleanOrNull) {
        true -> 1
        false -> 0
        null -> primitive.content.toIntOrNull()
    }
}

/**
 * character_filter 来自外部卡片,可能结构非法;解析失败降级为 null,不让坏卡片崩溃导入。
 */
private fun JsonObject.characterFilterField(): CharacterFilter? {
    val filterObject = this["character_filter"] as? JsonObject ?: return null
    return runCatching {
        characterBookJson.decodeFromJsonElement(CharacterFilter.serializer(), filterObject)
    }.getOrNull()
}

private fun JsonObjectBuilder.putNullableInt(key: String, value: Int?) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObjectBuilder.putNullableBool(key: String, value: Boolean?) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObjectBuilder.putCharacterFilter(filter: CharacterFilter?) {
    if (filter == null) return
    put("character_filter", characterBookJson.encodeToJsonElement(CharacterFilter.serializer(), filter))
}

/**
 * 酒馆 character_book 条目 extensions 子对象的字段名(world-info.js:5498 / characters.js:663)。
 * 下划线与驼峰是酒馆原始混用,必须逐字对齐,否则导入导出错位。
 */
private object BookEntryExt {
    const val POSITION = "position"
    const val EXCLUDE_RECURSION = "exclude_recursion"
    const val PREVENT_RECURSION = "prevent_recursion"
    const val DELAY_UNTIL_RECURSION = "delay_until_recursion"
    const val PROBABILITY = "probability"
    const val USE_PROBABILITY = "useProbability"
    const val DEPTH = "depth"
    const val SELECTIVE_LOGIC = "selectiveLogic"
    const val OUTLET_NAME = "outlet_name"
    const val GROUP = "group"
    const val GROUP_OVERRIDE = "group_override"
    const val GROUP_WEIGHT = "group_weight"
    const val SCAN_DEPTH = "scan_depth"
    const val CASE_SENSITIVE = "case_sensitive"
    const val MATCH_WHOLE_WORDS = "match_whole_words"
    const val USE_GROUP_SCORING = "use_group_scoring"
    const val AUTOMATION_ID = "automation_id"
    const val ROLE = "role"
    const val VECTORIZED = "vectorized"
    const val STICKY = "sticky"
    const val COOLDOWN = "cooldown"
    const val DELAY = "delay"
    const val MATCH_PERSONA_DESCRIPTION = "match_persona_description"
    const val MATCH_CHARACTER_DESCRIPTION = "match_character_description"
    const val MATCH_CHARACTER_PERSONALITY = "match_character_personality"
    const val MATCH_CHARACTER_DEPTH_PROMPT = "match_character_depth_prompt"
    const val MATCH_SCENARIO = "match_scenario"
    const val MATCH_CREATOR_NOTES = "match_creator_notes"
    const val TRIGGERS = "triggers"
    const val IGNORE_BUDGET = "ignore_budget"
}

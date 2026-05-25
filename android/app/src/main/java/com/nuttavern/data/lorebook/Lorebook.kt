package com.nuttavern.data.lorebook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 世界书条目。字段全集对齐酒馆 `newWorldInfoEntryDefinition`(world-info.js:4002-4045)。
 *
 * 命名规则:Kotlin camelCase + @SerialName 对齐酒馆 JSON key,导入导出零成本。
 */
@Serializable
data class LorebookEntry(
    val uid: Int = 0,
    val key: List<String> = emptyList(),
    val keysecondary: List<String> = emptyList(),
    val comment: String = "",
    val content: String = "",
    val constant: Boolean = false,
    val selective: Boolean = true,
    @SerialName("selectiveLogic") val selectiveLogic: Int = SelectiveLogic.AND_ANY,
    val order: Int = 100,
    val position: Int = WiPosition.BEFORE,
    val disable: Boolean = false,
    val depth: Int = DEFAULT_DEPTH,
    val role: Int = WiRole.SYSTEM,
    val group: String = "",
    @SerialName("groupOverride") val groupOverride: Boolean = false,
    @SerialName("groupWeight") val groupWeight: Int = DEFAULT_WEIGHT,
    @SerialName("scanDepth") val entryScanDepth: Int? = null,
    @SerialName("caseSensitive") val entryCaseSensitive: Boolean? = null,
    @SerialName("matchWholeWords") val entryMatchWholeWords: Boolean? = null,
    @SerialName("useGroupScoring") val entryUseGroupScoring: Boolean? = null,
    val probability: Int = 100,
    @SerialName("useProbability") val useProbability: Boolean = true,
    val sticky: Int? = null,
    val cooldown: Int? = null,
    val delay: Int? = null,
    @SerialName("excludeRecursion") val excludeRecursion: Boolean = false,
    @SerialName("preventRecursion") val preventRecursion: Boolean = false,
    @SerialName("delayUntilRecursion") val delayUntilRecursion: Int = 0,
    @SerialName("ignoreBudget") val ignoreBudget: Boolean = false,
    @SerialName("addMemo") val addMemo: Boolean = false,
    @SerialName("outletName") val outletName: String = "",
    val triggers: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_DEPTH = 4
        const val DEFAULT_WEIGHT = 100
    }
}

/**
 * 世界书(一本书 = 一组条目)。
 *
 * 对齐酒馆世界书 JSON 文件结构:顶层有 name + entries map。
 * 本仓库用 List 而非 Map(uid 作为 list index 的 key),简化 DataStore 序列化。
 */
@Serializable
data class Lorebook(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val scanDepth: Int = 2,
    val tokenBudget: Int = 25,
    val recursiveScanning: Boolean = false,
    val caseSensitive: Boolean = false,
    val matchWholeWords: Boolean = false,
    val maxRecursionSteps: Int = 0,
    val entries: List<LorebookEntry> = emptyList(),
) {
    /** 下一个可用的条目 uid(书内自增)。 */
    fun nextEntryUid(): Int = (entries.maxOfOrNull { it.uid } ?: -1) + 1
}

/** 条目注入位置。对齐酒馆 `world_info_position`。 */
object WiPosition {
    const val BEFORE = 0
    const val AFTER = 1
    const val AN_TOP = 2
    const val AN_BOTTOM = 3
    const val AT_DEPTH = 4
    const val EM_TOP = 5
    const val EM_BOTTOM = 6
    // outlet = 7,本仓库不实现
}

/** 条目注入角色(仅 atDepth 时生效)。对齐酒馆 `extension_prompt_roles`。 */
object WiRole {
    const val SYSTEM = 0
    const val USER = 1
    const val ASSISTANT = 2
}

/** 次要关键词逻辑。对齐酒馆 `world_info_logic`。 */
object SelectiveLogic {
    const val AND_ANY = 0
    const val NOT_ALL = 1
    const val NOT_ANY = 2
    const val AND_ALL = 3
}

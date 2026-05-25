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
    // ── 扫描范围扩展(match* 系列) ──
    @SerialName("matchPersonaDescription") val matchPersonaDescription: Boolean = false,
    @SerialName("matchCharacterDescription") val matchCharacterDescription: Boolean = false,
    @SerialName("matchCharacterPersonality") val matchCharacterPersonality: Boolean = false,
    @SerialName("matchCharacterDepthPrompt") val matchCharacterDepthPrompt: Boolean = false,
    @SerialName("matchScenario") val matchScenario: Boolean = false,
    @SerialName("matchCreatorNotes") val matchCreatorNotes: Boolean = false,
    // ── 角色过滤器 ──
    @SerialName("character_filter") val characterFilter: CharacterFilter? = null,
    // ── 兼容性存盘(运行时不消费) ──
    val vectorized: Boolean = false,
    @SerialName("automationId") val automationId: String = "",
) {
    companion object {
        const val DEFAULT_DEPTH = 4
        const val DEFAULT_WEIGHT = 100
    }
}

/**
 * 条目角色过滤器。null 表示"对所有角色生效"(不过滤)。
 *
 * 对齐酒馆 `entry.character_filter`(world-info.js:4703-4731)。
 *
 * - [isExclude] = false → 白名单:只对 [names] 中的角色生效
 * - [isExclude] = true → 黑名单:排除 [names] 中的角色
 * - [names] 存 character.id(UUID)。酒馆存 avatar 文件名去后缀,本仓库用 UUID 标识角色。
 * - [tags] 存 tag id。当前本仓库无 tag 系统,字段保留但引擎不消费。
 */
@Serializable
data class CharacterFilter(
    val isExclude: Boolean = false,
    val names: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

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
    // ── 新增:预算与激活控制 ──
    val budgetCap: Int = 0,
    val minActivations: Int = 0,
    val minActivationsDepthMax: Int = 0,
    // ── 新增:扫描行为 ──
    val includeNames: Boolean = true,
    val overflowAlert: Boolean = false,
    // ── 新增:互斥组评分 ──
    val useGroupScoring: Boolean = false,
    // ── 新增:角色书/全局书合并策略 ──
    val characterStrategy: Int = WiCharacterStrategy.CHARACTER_FIRST,
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

/** 角色书与全局书合并策略。对齐酒馆 `world_info_insertion_strategy`。 */
object WiCharacterStrategy {
    /** 均匀混合:全局和角色条目统一按 order 排序 */
    const val EVENLY = 0
    /** 角色优先:角色条目排前面,预算不够时角色条目优先注入 */
    const val CHARACTER_FIRST = 1
    /** 全局优先:全局条目排前面 */
    const val GLOBAL_FIRST = 2
}

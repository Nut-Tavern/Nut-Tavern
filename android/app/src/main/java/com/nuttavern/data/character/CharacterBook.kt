package com.nuttavern.data.character

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * V3 角色内嵌世界书。当前只做存盘,运行时由后续 LorebookEngine 消费。
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
 * V3 世界书条目字段。命名保留业务语义,序列化名对齐卡片 schema。
 */
@Serializable
data class CharacterBookEntry(
    val keys: List<String> = emptyList(),
    val content: String = "",
    val extensions: JsonObject = Character.EMPTY_JSON_OBJECT,
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
    @SerialName("selectiveLogic") val selectiveLogic: Int? = null,
    @SerialName("addMemo") val addMemo: Boolean? = null,
    @SerialName("excludeRecursion") val excludeRecursion: Boolean? = null,
    @SerialName("preventRecursion") val preventRecursion: Boolean? = null,
    @SerialName("delayUntilRecursion") val delayUntilRecursion: Boolean? = null,
    val probability: Int? = null,
    @SerialName("useProbability") val useProbability: Boolean? = null,
    val depth: Int? = null,
    val group: String? = null,
    @SerialName("groupOverride") val groupOverride: Boolean? = null,
    @SerialName("groupWeight") val groupWeight: Int? = null,
    @SerialName("scanDepth") val entryScanDepth: Int? = null,
    @SerialName("caseSensitive") val entryCaseSensitive: Boolean? = null,
    @SerialName("matchWholeWords") val matchWholeWords: Boolean? = null,
    @SerialName("useGroupScoring") val useGroupScoring: Boolean? = null,
    @SerialName("automationId") val automationId: String? = null,
    @SerialName("role") val role: String? = null,
    @SerialName("sticky") val sticky: Int? = null,
    @SerialName("cooldown") val cooldown: Int? = null,
    @SerialName("delay") val delay: Int? = null,
)

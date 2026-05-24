package com.nuttavern.data.regex

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 正则组。由一组 [RegexScript] 组成,组本身是启用单位。
 *
 * - 组内规则完全私有,两个组里字面相同的规则是两条独立记录,互不影响。
 * - [enabled] 是用户级默认开关:新会话创建时把当前启用的组 id 快照到会话上;
 *   设置页改 [enabled] 不影响已存在的会话。
 * - 组内规则**没有**逐条启用开关 — 组开了全跑,组关了全跳过。
 * - 执行顺序:顶层列表顺序决定组与散规则的执行先后;组内按 [scripts] 列表顺序串行。
 */
@Serializable
data class RegexGroup(
    val id: String = UUID.randomUUID().toString(),
    @SerialName("name") val name: String = "",
    /** 用户级默认启用开关。true = 新会话默认引用此组。 */
    @SerialName("enabled") val enabled: Boolean = true,
    /** 组内规则列表,按执行顺序排列。 */
    @SerialName("scripts") val scripts: List<RegexScript> = emptyList(),
)

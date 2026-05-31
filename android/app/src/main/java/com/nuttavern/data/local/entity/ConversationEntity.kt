package com.nuttavern.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val lastMessageTime: Long,
    val assistantId: String,
    val groupLabel: String?,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    /**
     * 当前会话绑定的角色卡 id。null 表示未绑定(用默认助手聊天,不走 PromptComposer 的角色字段节点)。
     *
     * 角色被删除时**不**自动清空这个字段:让 ChatViewModel 在加载会话时按 id 查角色,
     * 查不到就回退到"无角色"语义,避免 cascade delete 静默改写历史会话。
     */
    @ColumnInfo(defaultValue = "NULL") val characterId: String? = null,
    /**
     * 当前会话锁定的用户身份 id。会话创建那一刻取**当前生效的默认身份**写入,后续抽屉切身份
     * 直接覆盖这个字段。
     *
     * null 表示"无身份"("无"伪卡 / 老数据迁移上来的会话):拼接管线完全跳过用户身份块。
     *
     * 默认身份变化**不**反向修改老会话:每个会话的身份在创建时锁定,与 [characterId] 同语义。
     * 身份被删除时**不**自动清空这个字段:加载时按 id 查不到就退化到"无身份",避免 cascade
     * delete 静默改写历史会话。
     */
    @ColumnInfo(defaultValue = "NULL") val personaId: String? = null,
    /**
     * 当前会话锁定的预设 id。会话创建那一刻取当前默认预设写入,后续抽屉切预设直接覆盖。
     *
     * null 表示"未锁定预设"(老会话迁移上来的兜底):拼接管线退化为使用全局默认预设。
     *
     * 默认预设变化**不**反向修改老会话;预设被删除时**不**自动清空,加载时按 id 查不到则
     * 退化为全局默认预设,与 [characterId] / [personaId] 同处理。
     */
    @ColumnInfo(defaultValue = "NULL") val presetId: String? = null,
    /**
     * 当前会话引用的正则组 id 列表(JSON 数组字符串)。
     *
     * 会话创建时从用户级当前启用的组 id 快照写入。设置页改用户级启用不影响此字段。
     * 会话内可临时改(切换当前聊天的正则引用),只动本字段,不动用户级默认。
     *
     * 组被删除时,仓库层(`RegexScriptRepository.deleteGroup`)会同步扫所有会话清理悬空 id;
     * 运行时遇到未清理的悬空 id 仍兜底忽略,不报错。
     */
    @ColumnInfo(defaultValue = "NULL") val enabledRegexGroupIds: String? = null,
    /**
     * 当前会话引用的散规则 id 列表(JSON 数组字符串)。
     *
     * 语义与 [enabledRegexGroupIds] 完全一致,只是对应散规则而非组。
     */
    @ColumnInfo(defaultValue = "NULL") val enabledOrphanRegexIds: String? = null,
    @ColumnInfo(defaultValue = "'{}'") val lorebookTimedEffectsJson: String = "{}",
    /**
     * 当前会话的思考量(reasoning effort)。会话级,切会话保留各自档位。
     *
     * 存 [com.nuttavern.data.model.ThinkingLevel] 的序列化字符串(off / auto / effort:LOW /
     * budget:4096 等)。null = 老会话迁移上来的兜底,加载时退化为"自动"。
     */
    @ColumnInfo(defaultValue = "NULL") val thinkingLevel: String? = null,
)

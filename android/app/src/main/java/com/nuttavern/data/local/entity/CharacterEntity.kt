package com.nuttavern.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMessage: String,
    val messageExample: String,
    val systemPrompt: String,
    val postHistoryInstructions: String,
    val alternateGreetings: List<String>,
    val creator: String,
    val characterVersion: String,
    val creatorNotes: String,
    val tags: List<String>,
    val extensionsJson: String,
    val characterBookJson: String?,
    val regexScriptsJson: String,
    val avatarPath: String?,
    @ColumnInfo(defaultValue = "") val verbosity: String,
    @ColumnInfo(defaultValue = "0") val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long,
    /** JSON 序列化的 List<String>,绑定的全局世界书 id。 */
    @ColumnInfo(defaultValue = "[]") val lorebookIdsJson: String,
    /**
     * 用户拖动排序后的位置。新卡追加到末尾(取当前 max + 步长),
     * 已有卡迁移时按 createdAt 兜底。reorder 时按 100 步长重写一遍,
     * 便于后续在两条之间插入。
     */
    @ColumnInfo(defaultValue = "0") val displayOrder: Long,
)

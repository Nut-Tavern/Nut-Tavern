package com.nuttavern.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    @ColumnInfo(defaultValue = "''") val reasoningContent: String = "",
    @ColumnInfo(defaultValue = "0") val reasoningDurationMillis: Long = 0L,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis()
)

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
    /**
     * 消息内容块列表(JSON 数组字符串),序列化自 `List<com.nuttavern.data.model.MessagePart>`。
     * 取代旧的 content / reasoningContent / reasoningDurationMillis 三个并列字段:
     * 正文 / 思考 / 工具调用按到达顺序作为有序 parts 存在同一个字段里,渲染时穿插。
     * 空数组 = 空消息。编解码见 [com.nuttavern.data.repository.ConversationRepository] 的 partsJsonCodec。
     */
    @ColumnInfo(defaultValue = "'[]'") val partsJson: String,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    /**
     * 图片附件列表(JSON 数组字符串)。空数组 = 纯文本消息。
     * 只存附件元数据(id / path / mime),图片二进制落 filesDir,不进 DB。
     */
    @ColumnInfo(defaultValue = "'[]'") val attachmentsJson: String = "[]",
)

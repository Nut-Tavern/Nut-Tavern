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
    /**
     * swipe 候选列表(JSON 数组字符串),序列化自 `List<List<com.nuttavern.data.model.MessagePart>>`:
     * 每个元素是一个完整候选的 parts 列表(含正文 / 思考 / 工具调用)。对齐酒馆 `swipes`。
     *
     * 空数组 `'[]'` = 这条消息只有一个版本,不展示 swipe 切换。非空时 [swipeIndex] 处的候选
     * 必须与 [partsJson] 内容一致([partsJson] 永远是"当前显示候选",保证现有渲染 / 历史 /
     * 正则链路零改动)。重新生成最后一条 assistant 消息时把旧 parts 并入,新回复追加为新候选。
     */
    @ColumnInfo(defaultValue = "'[]'") val swipesJson: String = "[]",
    /** 当前选中的 swipe 候选索引。[swipesJson] 为空时无意义,固定 0。 */
    @ColumnInfo(defaultValue = "0") val swipeIndex: Int = 0,
)

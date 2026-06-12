package com.nuttavern.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nuttavern.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun getByConversationId(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentByConversationId(conversationId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    /**
     * 一次性取某会话的全部消息(按时间升序)。
     *
     * 与 [getByConversationId] 的 Flow 版区分:Flow 版给 UI 订阅,这里是删除链路
     * 取出待删消息列表用,不需要订阅。范围与 [deleteByConversationId] 一致。
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getAllByConversationId(conversationId: String): List<MessageEntity>

    /**
     * 取某会话内 [messageId] 之后(不含)的所有消息。
     *
     * 用于 [deleteAfter] 前先取出待删列表,清掉这些消息引用的附件文件。
     * SQL 条件与 [deleteAfter] 严格一致,保证"取的就是要删的"。
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        AND createdAt > (
            SELECT createdAt FROM messages WHERE id = :messageId
        )
        ORDER BY createdAt ASC
        """,
    )
    suspend fun getAfter(conversationId: String, messageId: String): List<MessageEntity>

    /**
     * 取某会话内 [messageId] 及之后的所有消息(含 [messageId] 自身)。
     *
     * 用于 [deleteFrom] 前先取出待删列表。SQL 条件与 [deleteFrom] 严格一致。
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        AND createdAt >= (
            SELECT createdAt FROM messages WHERE id = :messageId
        )
        ORDER BY createdAt ASC
        """,
    )
    suspend fun getFrom(conversationId: String, messageId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query(
        """
        UPDATE messages
        SET partsJson = :partsJson
        WHERE id = :messageId
        """,
    )
    suspend fun updatePartsById(
        messageId: String,
        partsJson: String,
    )

    @Query(
        """
        UPDATE messages
        SET partsJson = :partsJson, swipesJson = :swipesJson, swipeIndex = :swipeIndex
        WHERE id = :messageId
        """,
    )
    suspend fun updateSwipesById(
        messageId: String,
        partsJson: String,
        swipesJson: String,
        swipeIndex: Int,
    )

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query(
        """
        DELETE FROM messages
        WHERE conversationId = :conversationId
        AND createdAt > (
            SELECT createdAt FROM messages WHERE id = :messageId
        )
        """,
    )
    suspend fun deleteAfter(conversationId: String, messageId: String)

    @Query(
        """
        DELETE FROM messages
        WHERE conversationId = :conversationId
        AND createdAt >= (
            SELECT createdAt FROM messages WHERE id = :messageId
        )
        """,
    )
    suspend fun deleteFrom(conversationId: String, messageId: String)
}

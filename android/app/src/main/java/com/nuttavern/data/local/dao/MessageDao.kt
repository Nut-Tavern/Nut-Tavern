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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query(
        """
        UPDATE messages
        SET content = :content,
            reasoningContent = :reasoningContent,
            reasoningDurationMillis = :reasoningDurationMillis
        WHERE id = :messageId
        """,
    )
    suspend fun updateContentById(
        messageId: String,
        content: String,
        reasoningContent: String,
        reasoningDurationMillis: Long,
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

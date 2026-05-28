package com.nuttavern.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nuttavern.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY pinned DESC, lastMessageTime DESC")
    fun getAllNonArchived(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY pinned DESC, lastMessageTime DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id = :id AND archived = 0)")
    suspend fun existsNonArchivedById(id: String): Boolean

    @Query("SELECT * FROM conversations WHERE assistantId = :assistantId AND archived = 0 ORDER BY pinned DESC, lastMessageTime DESC")
    fun getByAssistantId(assistantId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE archived = 1 ORDER BY lastMessageTime DESC")
    fun getAllArchived(): Flow<List<ConversationEntity>>

    /**
     * 批量取出会话中正则字段非空的所有行。
     * 给"删除组 / 散规则后清理会话引用"用,只挑可能含目标 id 的行。
     */
    @Query("SELECT * FROM conversations WHERE enabledRegexGroupIds IS NOT NULL OR enabledOrphanRegexIds IS NOT NULL")
    suspend fun getAllWithRegexReferences(): List<ConversationEntity>

    /**
     * 批量取出绑定指定预设的会话。给"删除预设后清理会话引用"用。
     */
    @Query("SELECT * FROM conversations WHERE presetId = :presetId")
    suspend fun getByPresetId(presetId: String): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("UPDATE conversations SET lorebookTimedEffectsJson = :timedEffectsJson WHERE id = :conversationId")
    suspend fun updateLorebookTimedEffects(conversationId: String, timedEffectsJson: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)
}

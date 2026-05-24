package com.nuttavern.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.nuttavern.data.local.entity.CharacterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {

    @Query("SELECT * FROM characters ORDER BY displayOrder ASC, createdAt ASC, name COLLATE NOCASE ASC")
    fun observeCharacters(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE id = :id")
    fun observeCharacterById(id: String): Flow<CharacterEntity?>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun getCharacterById(id: String): CharacterEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM characters WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    /**
     * 当前最大的 displayOrder。空表返回 null,新卡 Repository 兜底成 0。
     */
    @Query("SELECT MAX(displayOrder) FROM characters")
    suspend fun maxDisplayOrder(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(character: CharacterEntity)

    @Query("UPDATE characters SET displayOrder = :displayOrder WHERE id = :id")
    suspend fun updateDisplayOrder(id: String, displayOrder: Long)

    /**
     * 按入参 id 顺序整段重写 displayOrder,步长 [REORDER_STEP],
     * 便于后续在中间插入而不必立刻重排所有行。未在入参中出现的角色保持原值。
     */
    @Transaction
    suspend fun reorder(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id ->
            updateDisplayOrder(id, (index + 1) * REORDER_STEP)
        }
    }

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun deleteById(id: String)

    companion object {
        /** displayOrder 重排步长。预留给后续单条插入,不必 O(n) 重排整段。 */
        const val REORDER_STEP = 100L
    }
}

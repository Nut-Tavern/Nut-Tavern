package com.nuttavern.data.lorebook

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界书仓库。管理用户级世界书的 CRUD + 全局选中 + 排序。
 *
 * 角色世界书 / 辅助世界书都是独立世界书(存本仓库),角色卡只存引用 id
 * (characterLorebookId / lorebookIds);运行时由 ChatViewModel 取出后交给 LorebookEngine。
 * 角色内嵌 character_book 仅作 V3 导入导出携带位,运行时不消费(见 docs/modules/lorebook.md)。
 */
@Singleton
class LorebookRepository @Inject constructor(
    private val dataStore: LorebookDataStore,
) {
    /** 所有世界书(按 order 排好)。 */
    val lorebooks: Flow<List<Lorebook>> = dataStore.snapshot.map { snap ->
        val byId = snap.lorebooks.associateBy { it.id }
        val ordered = snap.order.mapNotNull { byId[it] }
        val unordered = snap.lorebooks.filter { it.id !in snap.order.toSet() }
        ordered + unordered
    }

    /** 全局选中的世界书 id 列表。 */
    val globalSelectedIds: Flow<List<String>> = dataStore.snapshot.map { it.globalSelectedIds }

    fun findById(id: String): Flow<Lorebook?> = dataStore.snapshot.map { snap ->
        snap.lorebooks.find { it.id == id }
    }

    suspend fun upsert(lorebook: Lorebook) {
        val snap = dataStore.snapshot.first()
        val existing = snap.lorebooks.indexOfFirst { it.id == lorebook.id }
        val updatedBooks = if (existing >= 0) {
            snap.lorebooks.toMutableList().apply { this[existing] = lorebook }
        } else {
            snap.lorebooks + lorebook
        }
        val updatedOrder = if (existing < 0) snap.order + lorebook.id else snap.order
        dataStore.save(snap.copy(lorebooks = updatedBooks, order = updatedOrder))
    }

    suspend fun delete(id: String) {
        val snap = dataStore.snapshot.first()
        dataStore.save(
            snap.copy(
                lorebooks = snap.lorebooks.filter { it.id != id },
                order = snap.order.filter { it != id },
                globalSelectedIds = snap.globalSelectedIds.filter { it != id },
            ),
        )
    }

    suspend fun duplicate(id: String) {
        val snap = dataStore.snapshot.first()
        val original = snap.lorebooks.find { it.id == id } ?: return
        val newId = UUID.randomUUID().toString()
        val duplicated = original.copy(id = newId, name = "${original.name}*")
        val insertIndex = snap.order.indexOf(id)
        val newOrder = snap.order.toMutableList().apply {
            add(if (insertIndex >= 0) insertIndex + 1 else size, newId)
        }
        dataStore.save(
            snap.copy(
                lorebooks = snap.lorebooks + duplicated,
                order = newOrder,
            ),
        )
    }

    suspend fun reorder(orderedIds: List<String>) {
        val snap = dataStore.snapshot.first()
        dataStore.save(snap.copy(order = orderedIds))
    }

    suspend fun setGlobalSelected(ids: List<String>) {
        val snap = dataStore.snapshot.first()
        dataStore.save(snap.copy(globalSelectedIds = ids))
    }

    suspend fun toggleGlobalSelected(id: String, selected: Boolean) {
        val snap = dataStore.snapshot.first()
        val updated = if (selected) {
            if (id in snap.globalSelectedIds) snap.globalSelectedIds else snap.globalSelectedIds + id
        } else {
            snap.globalSelectedIds.filter { it != id }
        }
        dataStore.save(snap.copy(globalSelectedIds = updated))
    }

    /** 条目级操作:更新某本书的条目列表。 */
    suspend fun updateEntries(lorebookId: String, entries: List<LorebookEntry>) {
        val snap = dataStore.snapshot.first()
        val updatedBooks = snap.lorebooks.map { book ->
            if (book.id == lorebookId) book.copy(entries = entries) else book
        }
        dataStore.save(snap.copy(lorebooks = updatedBooks))
    }

    fun newLorebook(): Lorebook = Lorebook(
        id = UUID.randomUUID().toString(),
        name = "",
    )

    fun newEntry(lorebook: Lorebook): LorebookEntry = LorebookEntry(
        uid = lorebook.nextEntryUid(),
    )
}

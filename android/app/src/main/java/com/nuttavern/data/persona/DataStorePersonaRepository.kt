package com.nuttavern.data.persona

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 持久化版本的 [PersonaRepository]。
 *
 * 数据落盘走 [PersonaDataStore](独立 DataStore 文件,kotlinx.serialization JSON)。
 * 列表顺序通过单独的 `personaOrder` 数组维护 — 不靠 `personas` 列表本身的顺序,
 * 因为顺序变更与内容变更职责分离,便于以后单独迁移。
 *
 * # 行为契约
 *
 * - "无"伪卡永远在 [personas] 流头部,且不入库;
 * - [defaultPersonaId] 永不为 `null`,空仓库 / 默认未设时回退到 [UserPersona.NONE_PERSONA_ID];
 * - upsert / delete / reorder 都拒绝 [UserPersona.NONE_PERSONA_ID];
 * - 删默认时回退到"无"伪卡;
 * - **所有写入走 [PersonaDataStore.mutate],单次 edit 原子提交**,中途崩溃不会留下
 *   "personas 写了但顺序没写"等不一致中间态。
 *
 * # 不做的事
 *
 * - 不做并发控制(单用户单 app 场景下 [PersonaDataStore.mutate] 内部已经走 DataStore 串行 actor);
 * - 不做"默认变化时改写老会话身份":每个会话的身份在创建时锁定到 `conversations.personaId`,
 *   后续抽屉切身份直接覆盖会话字段。改默认身份只影响**新建**会话的初值,与角色卡处理一致。
 */
@Singleton
class DataStorePersonaRepository @Inject constructor(
    private val dataStore: PersonaDataStore,
    @param:ApplicationContext private val context: Context,
) : PersonaRepository {

    override val personas: Flow<List<UserPersona>> = combine(
        dataStore.personasFlow,
        dataStore.orderFlow,
    ) { stored, order ->
        listOf(UserPersona.None) + applyOrder(stored, order)
    }.distinctUntilChanged()

    override val defaultPersonaId: Flow<String> = dataStore.defaultPersonaIdFlow
        .map { it ?: UserPersona.NONE_PERSONA_ID }
        .distinctUntilChanged()

    override suspend fun upsert(persona: UserPersona) {
        require(!persona.isNonePersona) { "禁止 upsert \"无\" 伪卡" }

        dataStore.mutate { snapshot ->
            val existingIndex = snapshot.personas.indexOfFirst { it.id == persona.id }
            val nextPersonas = if (existingIndex >= 0) {
                snapshot.personas.toMutableList().apply { set(existingIndex, persona) }
            } else {
                snapshot.personas + persona
            }
            val nextOrder = if (existingIndex < 0 && persona.id !in snapshot.orderedIds) {
                snapshot.orderedIds + persona.id
            } else {
                snapshot.orderedIds
            }
            snapshot.copy(personas = nextPersonas, orderedIds = nextOrder)
        }
    }

    override suspend fun delete(id: String) {
        require(id != UserPersona.NONE_PERSONA_ID) { "禁止删除 \"无\" 伪卡" }

        dataStore.mutate { snapshot ->
            val nextDefault = if (snapshot.defaultPersonaId == id) {
                UserPersona.NONE_PERSONA_ID
            } else {
                snapshot.defaultPersonaId
            }
            snapshot.copy(
                personas = snapshot.personas.filterNot { it.id == id },
                orderedIds = snapshot.orderedIds.filterNot { it == id },
                defaultPersonaId = nextDefault,
            )
        }
        deleteAvatarFiles(id)
    }

    override suspend fun setDefault(id: String) {
        dataStore.mutate { snapshot ->
            val isNoneCard = id == UserPersona.NONE_PERSONA_ID
            val existsInRealList = snapshot.personas.any { it.id == id }
            require(isNoneCard || existsInRealList) { "未知 persona id: $id" }
            snapshot.copy(defaultPersonaId = id)
        }
    }

    override suspend fun reorder(orderedIds: List<String>) {
        require(UserPersona.NONE_PERSONA_ID !in orderedIds) {
            "禁止把 \"无\" 伪卡纳入真实身份排序"
        }
        dataStore.mutate { snapshot ->
            // 入参里没出现的真实身份保留在末尾,避免静默丢失。
            val knownIds = snapshot.personas.map { it.id }.toSet()
            val cleanedHead = orderedIds.filter { it in knownIds }
            val tail = (knownIds - cleanedHead.toSet())
            snapshot.copy(orderedIds = cleanedHead + tail)
        }
    }

    override suspend fun clearLorebookBinding(lorebookId: String) {
        dataStore.mutate { snapshot ->
            val updated = snapshot.personas.map { persona ->
                if (persona.lorebookId == lorebookId) persona.copy(lorebookId = null)
                else persona
            }
            if (updated == snapshot.personas) snapshot
            else snapshot.copy(personas = updated)
        }
    }

    override suspend fun clearCharacterConnection(characterId: String) {
        dataStore.mutate { snapshot ->
            val updated = snapshot.personas.map { persona ->
                if (characterId in persona.characterConnections) {
                    persona.copy(characterConnections = persona.characterConnections - characterId)
                } else {
                    persona
                }
            }
            if (updated == snapshot.personas) snapshot
            else snapshot.copy(personas = updated)
        }
    }

    override fun saveAvatarBytes(personaId: String, bytes: ByteArray, extension: String): String {
        val target = avatarFileFor(personaId, extension)
        target.parentFile?.mkdirs()
        deleteAvatarFilesExcept(personaId, target)
        target.writeBytes(bytes)
        return target.absolutePath
    }

    private fun avatarFileFor(personaId: String, extension: String): File {
        require(personaId.isNotBlank()) { "Persona id must not be blank." }
        // 头像文件名直接用 personaId 拼,必须拒绝路径分隔符 / 上跳,防止写到私有目录外。
        require(personaId.none { it == '/' || it == '\\' } && !personaId.contains("..")) {
            "Persona id must not contain path separators."
        }
        val safeExtension = extension.trim().trimStart('.').lowercase()
        require(safeExtension in SUPPORTED_AVATAR_EXTENSIONS) {
            "Unsupported persona avatar extension: $extension"
        }
        return File(personaAvatarDirectory(), "$personaId.$safeExtension")
    }

    private fun personaAvatarDirectory(): File = File(context.filesDir, PERSONA_AVATAR_DIRECTORY)

    private fun deleteAvatarFilesExcept(personaId: String, keep: File) {
        SUPPORTED_AVATAR_EXTENSIONS.forEach { ext ->
            val file = File(personaAvatarDirectory(), "$personaId.$ext")
            if (file != keep && file.exists()) file.delete()
        }
    }

    /** 删除身份时清理它的全部头像文件(各扩展名),避免私有目录残留孤儿文件。 */
    private fun deleteAvatarFiles(personaId: String) {
        if (personaId.any { it == '/' || it == '\\' } || personaId.contains("..")) return
        SUPPORTED_AVATAR_EXTENSIONS.forEach { ext ->
            val file = File(personaAvatarDirectory(), "$personaId.$ext")
            if (file.exists()) file.delete()
        }
    }

    /**
     * 按 [order] 给 [personas] 排序;[order] 里没出现的真实身份按它们在 [personas] 里的原顺序追到末尾。
     */
    private fun applyOrder(personas: List<UserPersona>, order: List<String>): List<UserPersona> {
        val byId = personas.associateBy { it.id }
        val ordered = order.mapNotNull(byId::get)
        val tail = personas.filter { it.id !in order }
        return ordered + tail
    }

    private companion object {
        const val PERSONA_AVATAR_DIRECTORY = "personas"
        val SUPPORTED_AVATAR_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}

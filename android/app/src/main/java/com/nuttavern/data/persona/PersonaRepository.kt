package com.nuttavern.data.persona

import kotlinx.coroutines.flow.Flow

/**
 * 用户身份仓库。生产实现走 [DataStorePersonaRepository](独立 DataStore 文件,
 * kotlinx.serialization JSON)。测试如需替换,在测试 module 里覆盖
 * [com.nuttavern.di.PersonaModule.bindPersonaRepository] 即可。
 *
 * # 契约
 *
 * - [personas] 始终把 [UserPersona.None] 伪卡放在列表第 0 位,后接真实身份。
 *   伪卡不入库,任何持久化实现都不应允许 upsert / delete 它。
 * - [defaultPersonaId] 是用户标记为默认的身份。空仓库 / 默认未设时回退到
 *   [UserPersona.NONE_PERSONA_ID](伪卡 id),**永不返回 null**。
 * - [upsert] 按 id 创建或覆盖真实身份。空字符串 / 全空白名字也允许保存;
 *   是否允许"空 persona"由 UI 校验,仓库不拦。传入 [UserPersona.None] 会抛 [IllegalArgumentException]。
 * - [delete] 删除真实身份。删默认时回退到"无"伪卡。删 [UserPersona.NONE_PERSONA_ID] 抛异常。
 * - [setDefault] 接受任意已知 id(包括 [UserPersona.NONE_PERSONA_ID]);未知 id 抛 [IllegalArgumentException]。
 */
interface PersonaRepository {
    val personas: Flow<List<UserPersona>>
    val defaultPersonaId: Flow<String>

    suspend fun upsert(persona: UserPersona)
    suspend fun delete(id: String)
    suspend fun setDefault(id: String)

    /**
     * 按指定 id 顺序重排真实身份列表。
     *
     * - [orderedIds] 是除"无"伪卡之外的真实身份 id 顺序。包含未知 id 时会被忽略。
     * - 实现层不应允许 [UserPersona.NONE_PERSONA_ID] 出现在入参里。
     */
    suspend fun reorder(orderedIds: List<String>)

    /**
     * 清除所有身份中对指定世界书的绑定引用。
     *
     * 当世界书被删除时调用,避免悬空引用。
     */
    suspend fun clearLorebookBinding(lorebookId: String)

    /**
     * 清除所有身份中对指定角色的绑定引用。
     *
     * 当角色被删除时调用,避免悬空引用。
     */
    suspend fun clearCharacterConnection(characterId: String)

    /**
     * 把头像字节写入 `filesDir/personas/{id}.{ext}`,返回可存进 [UserPersona.avatarPath] 的绝对路径。
     * 同一身份换头像时先清掉其它扩展名的旧文件,避免残留。
     *
     * @throws IllegalArgumentException id 含路径分隔符 / 扩展名不支持时抛出
     */
    fun saveAvatarBytes(personaId: String, bytes: ByteArray, extension: String): String
}

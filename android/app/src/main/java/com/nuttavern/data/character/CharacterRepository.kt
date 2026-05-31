package com.nuttavern.data.character

import android.content.Context
import com.nuttavern.data.local.dao.CharacterDao
import com.nuttavern.data.local.entity.CharacterEntity
import com.nuttavern.data.regex.RegexScript
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Singleton
class CharacterRepository @Inject constructor(
    private val characterDao: CharacterDao,
    @param:ApplicationContext private val context: Context,
) {
    val characters: Flow<List<Character>> = characterDao.observeCharacters().map { entities ->
        entities.map { it.toCharacter() }
    }

    fun observeCharacterById(id: String): Flow<Character?> {
        return characterDao.observeCharacterById(id).map { it?.toCharacter() }
    }

    suspend fun getCharacterById(id: String): Character? {
        return characterDao.getCharacterById(id)?.toCharacter()
    }

    suspend fun characterExists(id: String): Boolean {
        return characterDao.existsById(id)
    }

    /**
     * 创建或更新角色卡。
     *
     * - **新建**:displayOrder 取当前最大值 + [CharacterDao.REORDER_STEP],
     *   始终追加到列表末尾。
     * - **更新**:保留原 displayOrder,不影响列表中的位置。
     *
     * 名字为空抛 [IllegalArgumentException],由 UI 层提前校验。
     */
    suspend fun upsert(character: Character) {
        require(character.name.isNotBlank()) { "Character name must not be blank." }
        val now = System.currentTimeMillis()
        val existing = characterDao.getCharacterById(character.id)
        val displayOrder = existing?.displayOrder
            ?: ((characterDao.maxDisplayOrder() ?: 0L) + CharacterDao.REORDER_STEP)
        val normalizedCharacter = if (character.createdAt <= 0L) {
            character.copy(createdAt = now, updatedAt = now)
        } else {
            character.copy(updatedAt = now)
        }
        characterDao.upsert(normalizedCharacter.toEntity(displayOrder))
    }

    suspend fun delete(id: String) {
        characterDao.deleteById(id)
        deleteAvatarFiles(id)
    }

    /**
     * 按指定 id 顺序重排角色列表。未在 [orderedIds] 中出现的角色保持原 displayOrder。
     */
    suspend fun reorder(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        characterDao.reorder(orderedIds)
    }

    fun avatarFileFor(characterId: String, extension: String = DEFAULT_AVATAR_EXTENSION): File {
        require(characterId.isNotBlank()) { "Character id must not be blank." }
        // 头像文件名直接用 characterId 拼,必须拒绝路径分隔符 / 上跳,防止写到私有目录外。
        require(characterId.none { it == '/' || it == '\\' } && !characterId.contains("..")) {
            "Character id must not contain path separators."
        }
        val safeExtension = extension.trim().trimStart('.').lowercase()
        require(safeExtension in SUPPORTED_AVATAR_EXTENSIONS) {
            "Unsupported character avatar extension: $extension"
        }
        return File(characterAvatarDirectory(), "$characterId.$safeExtension")
    }

    fun characterAvatarDirectory(): File {
        return File(context.filesDir, CHARACTER_AVATAR_DIRECTORY)
    }

    /**
     * 把头像字节写入 [filesDir]/characters/{id}.{ext},返回可存进 [Character.avatarPath] 的绝对路径。
     *
     * 角色卡导入用:V3 PNG 卡的头像就是卡本身的图像字节,需落盘后导出 PNG 才有底图。
     * 同一角色换头像时,先清掉其它扩展名的旧文件,避免残留(导入 png 后又换 jpg 等)。
     *
     * @throws IllegalArgumentException 扩展名不在 [SUPPORTED_AVATAR_EXTENSIONS] 时抛出
     */
    fun saveAvatarBytes(characterId: String, bytes: ByteArray, extension: String): String {
        val target = avatarFileFor(characterId, extension)
        target.parentFile?.mkdirs()
        deleteAvatarFilesExcept(characterId, target)
        target.writeBytes(bytes)
        return target.absolutePath
    }

    /** 读出角色头像字节;文件不存在返回 null(导出 PNG 时用作底图)。 */
    fun readAvatarBytes(avatarPath: String?): ByteArray? {
        val path = avatarPath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        return if (file.exists()) file.readBytes() else null
    }

    private fun deleteAvatarFilesExcept(characterId: String, keep: File) {
        SUPPORTED_AVATAR_EXTENSIONS.forEach { ext ->
            val file = File(characterAvatarDirectory(), "$characterId.$ext")
            if (file != keep && file.exists()) file.delete()
        }
    }

    /** 删除角色时清理它的全部头像文件(各扩展名),避免私有目录残留孤儿文件。 */
    private fun deleteAvatarFiles(characterId: String) {
        if (characterId.any { it == '/' || it == '\\' } || characterId.contains("..")) return
        SUPPORTED_AVATAR_EXTENSIONS.forEach { ext ->
            val file = File(characterAvatarDirectory(), "$characterId.$ext")
            if (file.exists()) file.delete()
        }
    }

    private fun CharacterEntity.toCharacter(): Character {
        return Character(
            id = id,
            name = name,
            description = description,
            personality = personality,
            scenario = scenario,
            firstMessage = firstMessage,
            messageExample = messageExample,
            systemPrompt = systemPrompt,
            postHistoryInstructions = postHistoryInstructions,
            alternateGreetings = alternateGreetings,
            creator = creator,
            characterVersion = characterVersion,
            creatorNotes = creatorNotes,
            tags = tags,
            extensions = extensionsJson.takeIf { it.isNotBlank() }?.let { json.decodeFromString(it) }
                ?: Character.EMPTY_JSON_OBJECT,
            characterBook = characterBookJson?.takeIf { it.isNotBlank() }?.let { json.decodeFromString<CharacterBook>(it) },
            regexScripts = regexScriptsJson.takeIf { it.isNotBlank() }?.let { json.decodeFromString<List<RegexScript>>(it) }
                ?: emptyList(),
            avatarPath = avatarPath,
            lorebookIds = lorebookIdsJson.takeIf { it.isNotBlank() }?.let { json.decodeFromString<List<String>>(it) }
                ?: emptyList(),
            characterLorebookId = characterLorebookId,
            verbosity = verbosity,
            rawCardData = rawCardDataJson?.takeIf { it.isNotBlank() }?.let { json.decodeFromString<JsonObject>(it) },
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun Character.toEntity(displayOrder: Long): CharacterEntity {
        return CharacterEntity(
            id = id,
            name = name.trim(),
            description = description,
            personality = personality,
            scenario = scenario,
            firstMessage = firstMessage,
            messageExample = messageExample,
            systemPrompt = systemPrompt,
            postHistoryInstructions = postHistoryInstructions,
            alternateGreetings = alternateGreetings,
            creator = creator,
            characterVersion = characterVersion,
            creatorNotes = creatorNotes,
            tags = tags,
            extensionsJson = json.encodeToString(extensions),
            characterBookJson = characterBook?.let { json.encodeToString(it) },
            regexScriptsJson = json.encodeToString(regexScripts),
            avatarPath = avatarPath,
            lorebookIdsJson = json.encodeToString(lorebookIds),
            characterLorebookId = characterLorebookId,
            verbosity = verbosity,
            rawCardDataJson = rawCardData?.let { json.encodeToString(JsonObject.serializer(), it) },
            createdAt = createdAt,
            updatedAt = updatedAt,
            displayOrder = displayOrder,
        )
    }

    private companion object {
        const val CHARACTER_AVATAR_DIRECTORY = "characters"
        const val DEFAULT_AVATAR_EXTENSION = "png"

        val SUPPORTED_AVATAR_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

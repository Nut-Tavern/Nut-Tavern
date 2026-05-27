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
        val safeExtension = extension.trim().trimStart('.').lowercase()
        require(safeExtension in SUPPORTED_AVATAR_EXTENSIONS) {
            "Unsupported character avatar extension: $extension"
        }
        return File(characterAvatarDirectory(), "$characterId.$safeExtension")
    }

    fun characterAvatarDirectory(): File {
        return File(context.filesDir, CHARACTER_AVATAR_DIRECTORY)
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
            verbosity = verbosity,
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
            verbosity = verbosity,
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

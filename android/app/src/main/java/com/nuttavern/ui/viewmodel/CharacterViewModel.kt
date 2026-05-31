package com.nuttavern.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuttavern.data.character.Character
import com.nuttavern.data.character.CharacterBook
import com.nuttavern.data.character.CharacterCardCodec
import com.nuttavern.data.character.CharacterRepository
import com.nuttavern.data.character.toLorebook
import com.nuttavern.data.character.toCharacterBook
import com.nuttavern.data.lorebook.LorebookRepository
import com.nuttavern.data.persona.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 角色卡 ViewModel。承载列表页和编辑页的数据 + 角色卡导入导出编排。
 *
 * - 列表页订阅 [characters]。
 * - 编辑页通过 [findById] 取角色快照(普通 Flow,不挂 stateIn,避免 viewModelScope 累积订阅);
 *   编辑过程的本地草稿由 Screen 自己保留,保存时整体 [upsert] 写回。
 * - 导入导出由 Screen 用 SAF 读写字节,本 VM 负责 codec 解析 + 内嵌世界书提取 + 头像落盘编排。
 */
@HiltViewModel
class CharacterViewModel @Inject constructor(
    private val repository: CharacterRepository,
    private val lorebookRepository: LorebookRepository,
    private val personaRepository: PersonaRepository,
) : ViewModel() {

    val characters: StateFlow<List<Character>> = repository.characters.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun findById(id: String): Flow<Character?> {
        return repository.observeCharacterById(id)
    }

    fun newCharacter(): Character = Character()

    fun upsert(character: Character) {
        viewModelScope.launch { repository.upsert(character) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            personaRepository.clearCharacterConnection(id)
        }
    }

    fun reorderCharacters(orderedIds: List<String>) {
        viewModelScope.launch { repository.reorder(orderedIds) }
    }

    /**
     * 编辑页手动选图:把相册选中的图片字节落盘为该角色头像,回调返回 avatarPath 供草稿更新。
     * 落盘失败回调 null。落盘用草稿 id(新角色 [Character] 已生成 UUID),保存时随草稿一起写库。
     */
    fun persistAvatar(characterId: String, bytes: ByteArray, extension: String, onSaved: (String?) -> Unit) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching { repository.saveAvatarBytes(characterId, bytes, extension) }.getOrNull()
            }
            onSaved(path)
        }
    }

    /**
     * 导入 V3 / V2 / V1 角色卡 PNG。卡里的图像字节就是角色头像,落盘后设 avatarPath。
     *
     * 流程对齐酒馆 importFromPng + importEmbeddedWorldInfo:
     * 1. codec 三态识别解析出角色 + 内嵌 character_book;
     * 2. 内嵌世界书提取成独立世界书写进 [lorebookRepository],设 characterLorebookId;
     * 3. PNG 字节落盘为头像,设 avatarPath;
     * 4. upsert 角色。
     *
     * 解析(含字节遍历 / base64 / JSON)与文件落盘都在 IO 线程执行,回调切回主线程。
     * 解析失败回调 [onError],不写入任何仓库。
     */
    fun importFromPng(
        imageBytes: ByteArray,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                runCatching { CharacterCardCodec.decodeFromPng(imageBytes) }
            }.getOrElse { error ->
                onError(importErrorMessage(error))
                return@launch
            }
            val characterId = UUID.randomUUID().toString()
            val lorebookId = extractEmbeddedLorebook(decoded.embeddedBook, decoded.character.name)
            val avatarPath = withContext(Dispatchers.IO) {
                runCatching { repository.saveAvatarBytes(characterId, imageBytes, AVATAR_EXTENSION_PNG) }.getOrNull()
            }
            val character = decoded.character.copy(
                id = characterId,
                createdAt = 0L,
                characterLorebookId = lorebookId ?: decoded.character.characterLorebookId,
                avatarPath = avatarPath,
            )
            repository.upsert(character)
            onSuccess(character.name)
        }
    }

    /**
     * 导入角色卡 JSON 文本(无头像)。流程同 [importFromPng] 但不落盘头像。解析在 IO 线程。
     */
    fun importFromJson(
        jsonText: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                runCatching { CharacterCardCodec.decodeFromJson(jsonText) }
            }.getOrElse { error ->
                onError(importErrorMessage(error))
                return@launch
            }
            val characterId = UUID.randomUUID().toString()
            val lorebookId = extractEmbeddedLorebook(decoded.embeddedBook, decoded.character.name)
            val character = decoded.character.copy(
                id = characterId,
                createdAt = 0L,
                characterLorebookId = lorebookId ?: decoded.character.characterLorebookId,
            )
            repository.upsert(character)
            onSuccess(character.name)
        }
    }

    /**
     * 导出角色为 V3 卡 JSON 文本。角色不存在则不回调。
     */
    fun exportToJson(characterId: String, onReady: (fileName: String, jsonText: String) -> Unit) {
        viewModelScope.launch {
            val character = repository.getCharacterById(characterId) ?: return@launch
            val book = resolveExportBook(character)
            val jsonText = withContext(Dispatchers.IO) {
                CharacterCardCodec.encodeToV3Json(character, book)
            }
            onReady(character.name.ifBlank { "character" }, jsonText)
        }
    }

    /** 导出编辑页当前草稿为 V3 卡 JSON 文本(所见即所得,不依赖库中已保存版本)。 */
    fun exportDraftToJson(character: Character, onReady: (fileName: String, jsonText: String) -> Unit) {
        viewModelScope.launch {
            val book = resolveExportBook(character)
            val jsonText = withContext(Dispatchers.IO) {
                CharacterCardCodec.encodeToV3Json(character, book)
            }
            onReady(character.name.ifBlank { "character" }, jsonText)
        }
    }

    /**
     * 导出角色为 V3 卡 PNG 字节(双写 chara + ccv3 chunk)。
     *
     * - 无头像底图:回调 [onNoAvatar](PNG 卡必须以头像图为底,改导 JSON);
     * - 底图非 PNG / 编码失败:回调 [onError](区别于"无头像",给准确文案)。
     *
     * 角色不存在则不回调。
     */
    fun exportToPng(
        characterId: String,
        onReady: (fileName: String, imageBytes: ByteArray) -> Unit,
        onNoAvatar: () -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val character = repository.getCharacterById(characterId) ?: return@launch
            encodePngOrNotify(character, onReady, onNoAvatar, onError)
        }
    }

    /** 导出编辑页当前草稿为 V3 卡 PNG(所见即所得)。无头像回调 [onNoAvatar],编码失败回调 [onError]。 */
    fun exportDraftToPng(
        character: Character,
        onReady: (fileName: String, imageBytes: ByteArray) -> Unit,
        onNoAvatar: () -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch { encodePngOrNotify(character, onReady, onNoAvatar, onError) }
    }

    private suspend fun encodePngOrNotify(
        character: Character,
        onReady: (fileName: String, imageBytes: ByteArray) -> Unit,
        onNoAvatar: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val book = resolveExportBook(character)
        val encoded = withContext(Dispatchers.IO) {
            val baseImage = repository.readAvatarBytes(character.avatarPath)
                ?: return@withContext PngEncodeResult.NoAvatar
            runCatching { CharacterCardCodec.encodeToPng(character, book, baseImage) }
                .fold(
                    onSuccess = { PngEncodeResult.Success(it) },
                    onFailure = { PngEncodeResult.Failed(it.message ?: "图片编码失败") },
                )
        }
        when (encoded) {
            is PngEncodeResult.NoAvatar -> onNoAvatar()
            is PngEncodeResult.Failed -> onError("当前头像不是 PNG 图片,无法导出为图片,可改导出 JSON")
            is PngEncodeResult.Success -> onReady(character.name.ifBlank { "character" }, encoded.bytes)
        }
    }

    /**
     * 算出导出回填用的 `character_book`。
     *
     * 角色世界书是独立世界书(characterLorebookId 指向),可能在世界书模块编辑过,导出反映最新内容
     * (对齐所见即所得):读独立世界书转回 CharacterBook,以导入携带位(character.characterBook)做
     * extensions round-trip 基底。无 characterLorebookId / 找不到独立世界书时退回携带位。
     */
    private suspend fun resolveExportBook(character: Character): CharacterBook? {
        val lorebookId = character.characterLorebookId ?: return character.characterBook
        val lorebook = lorebookRepository.findById(lorebookId).first() ?: return character.characterBook
        return lorebook.toCharacterBook(character.characterBook)
    }

    fun duplicate(characterId: String) {
        viewModelScope.launch {
            val original = repository.getCharacterById(characterId) ?: return@launch
            val copy = original.copy(
                id = UUID.randomUUID().toString(),
                name = "${original.name}*",
                avatarPath = null,
                createdAt = 0L,
            )
            repository.upsert(copy)
        }
    }

    /**
     * 提取内嵌世界书成独立世界书写进仓库,返回新世界书 id;无内嵌或空条目返回 null。
     */
    private suspend fun extractEmbeddedLorebook(
        embeddedBook: com.nuttavern.data.character.CharacterBook?,
        characterName: String,
    ): String? {
        val book = embeddedBook?.takeIf { it.entries.isNotEmpty() } ?: return null
        val lorebookId = UUID.randomUUID().toString()
        val lorebook = book.toLorebook(lorebookId, characterName)
        lorebookRepository.upsert(lorebook)
        return lorebookId
    }

    /** 导入失败给用户固定友好文案,不把底层序列化异常 message 直接抛到 UI(可能含文件内容片段)。 */
    private fun importErrorMessage(error: Throwable): String {
        return when (error) {
            is IllegalArgumentException -> error.message ?: DEFAULT_IMPORT_ERROR
            else -> DEFAULT_IMPORT_ERROR
        }
    }

    private sealed interface PngEncodeResult {
        data class Success(val bytes: ByteArray) : PngEncodeResult
        data class Failed(val reason: String) : PngEncodeResult
        data object NoAvatar : PngEncodeResult
    }

    private companion object {
        const val AVATAR_EXTENSION_PNG = "png"
        const val DEFAULT_IMPORT_ERROR = "角色卡解析失败,请确认文件格式"
    }
}

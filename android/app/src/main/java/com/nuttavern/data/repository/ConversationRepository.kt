package com.nuttavern.data.repository

import android.content.Context
import com.nuttavern.data.local.dao.ConversationDao
import com.nuttavern.data.local.dao.MessageDao
import com.nuttavern.data.local.entity.ConversationEntity
import com.nuttavern.data.local.entity.MessageEntity
import com.nuttavern.data.model.ConversationSummary
import com.nuttavern.data.tools.ConversationToolMode
import com.nuttavern.data.model.ImageAttachment
import com.nuttavern.data.model.Message
import com.nuttavern.data.model.MessagePart
import com.nuttavern.data.model.ThinkingLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    @param:ApplicationContext private val context: Context,
) {
    val nonArchivedConversations: Flow<List<ConversationSummary>> =
        conversationDao.getAllNonArchived().map { conversations ->
            conversations.map { it.toSummary() }
        }

    val conversations: Flow<List<ConversationSummary>> =
        conversationDao.getAll().map { conversations ->
            conversations.map { it.toSummary() }
        }

    fun observeMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getByConversationId(conversationId).map { messages ->
            messages.map { it.toMessage() }
        }
    }

    suspend fun createConversation(conversation: ConversationSummary, createdAt: Long) {
        conversationDao.insert(conversation.toEntity(createdAt))
    }

    suspend fun appendMessage(conversationId: String, message: Message, createdAt: Long) {
        messageDao.insert(message.toEntity(conversationId, createdAt))
    }

    suspend fun updateMessageParts(
        messageId: String,
        parts: List<MessagePart>,
    ) {
        messageDao.updatePartsById(messageId, encodeParts(parts))
    }

    suspend fun deleteMessage(messageId: String) {
        messageDao.deleteById(messageId)
    }

    /**
     * 把图片字节写入 `filesDir/chat-images/{attachmentId}.{ext}`,返回可存进
     * [ImageAttachment.path] 的绝对路径。设计对齐角色头像落盘
     * ([com.nuttavern.data.character.CharacterRepository.saveAvatarBytes])。
     *
     * @throws IllegalArgumentException attachmentId 含路径分隔符 / 扩展名不支持时抛出
     */
    fun saveImageBytes(attachmentId: String, bytes: ByteArray, extension: String): String {
        val target = imageFileFor(attachmentId, extension)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return target.absolutePath
    }

    /** 读出图片附件字节;文件不存在返回 null。 */
    fun readImageBytes(path: String?): ByteArray? {
        val file = path?.takeIf { it.isNotBlank() }?.let { File(it) } ?: return null
        return if (file.exists()) file.readBytes() else null
    }

    private fun imageFileFor(attachmentId: String, extension: String): File {
        require(attachmentId.isNotBlank()) { "Attachment id must not be blank." }
        // 文件名直接用 attachmentId 拼,必须拒绝路径分隔符 / 上跳,防止写到私有目录外。
        require(attachmentId.none { it == '/' || it == '\\' } && !attachmentId.contains("..")) {
            "Attachment id must not contain path separators."
        }
        val safeExtension = extension.trim().trimStart('.').lowercase()
        require(safeExtension in SUPPORTED_IMAGE_EXTENSIONS) {
            "Unsupported image extension: $extension"
        }
        return File(File(context.filesDir, CHAT_IMAGE_DIRECTORY), "$attachmentId.$safeExtension")
    }

    suspend fun deleteMessagesAfter(conversationId: String, messageId: String) {
        messageDao.deleteAfter(conversationId, messageId)
    }

    suspend fun deleteMessagesFrom(conversationId: String, messageId: String) {
        messageDao.deleteFrom(conversationId, messageId)
    }

    suspend fun renameConversation(conversation: ConversationSummary, title: String) {
        conversationDao.update(conversation.copy(title = title).toEntity())
    }

    suspend fun updateConversation(conversation: ConversationSummary) {
        conversationDao.update(conversation.toEntity())
    }

    suspend fun updateConversation(conversation: ConversationSummary, lastMessageTime: Long) {
        conversationDao.update(conversation.toEntity(lastMessageTime))
    }

    suspend fun updateLorebookTimedEffects(conversationId: String, timedEffectsJson: String) {
        conversationDao.updateLorebookTimedEffects(conversationId, timedEffectsJson)
    }

    suspend fun deleteConversation(id: String) {
        conversationDao.deleteById(id)
    }

    suspend fun conversationExists(id: String): Boolean {
        return conversationDao.existsById(id)
    }

    suspend fun nonArchivedConversationExists(id: String): Boolean {
        return conversationDao.existsNonArchivedById(id)
    }

    /**
     * 清理所有会话引用快照中已删除的正则组 id。
     *
     * 由 [com.nuttavern.data.regex.RegexScriptRepository.deleteGroup] 在删除完成后调用。
     * 扫表只读取 `enabledRegexGroupIds` 非空的行,从 JSON 列表里移除被删 id。
     */
    suspend fun removeRegexGroupIdFromAllConversations(groupId: String) {
        val rows = conversationDao.getAllWithRegexReferences()
        rows.forEach { entity ->
            val cleaned = removeIdFromJsonList(entity.enabledRegexGroupIds, groupId)
            if (cleaned != entity.enabledRegexGroupIds) {
                conversationDao.update(entity.copy(enabledRegexGroupIds = cleaned))
            }
        }
    }

    /**
     * 清理所有会话引用快照中已删除的散规则 id。
     *
     * 由 [com.nuttavern.data.regex.RegexScriptRepository.deleteOrphan] 在删除完成后调用。
     */
    suspend fun removeOrphanRegexIdFromAllConversations(scriptId: String) {
        val rows = conversationDao.getAllWithRegexReferences()
        rows.forEach { entity ->
            val cleaned = removeIdFromJsonList(entity.enabledOrphanRegexIds, scriptId)
            if (cleaned != entity.enabledOrphanRegexIds) {
                conversationDao.update(entity.copy(enabledOrphanRegexIds = cleaned))
            }
        }
    }

    /**
     * 清理所有会话绑定的指定预设 id(置空,让 ChatViewModel 退化为全局默认预设)。
     *
     * 由 [com.nuttavern.data.preset.PresetRepository.delete] 在删除完成后调用。
     */
    suspend fun clearPresetIdFromAllConversations(presetId: String) {
        val rows = conversationDao.getByPresetId(presetId)
        rows.forEach { entity ->
            conversationDao.update(entity.copy(presetId = null))
        }
    }

    /**
     * JSON 数组字符串移除指定 id 后重新序列化。
     * 输入 null / 不含 id / 解析失败时返回原值。
     */
    private fun removeIdFromJsonList(json: String?, targetId: String): String? {
        if (json.isNullOrBlank()) return json
        val list = runCatching {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(json)
        }.getOrNull() ?: return json
        if (targetId !in list) return json
        val cleaned: List<String> = list.filter { it != targetId }
        // 与 ChatViewModel.encodeStringListToJson 一致的 buildJsonArray 写法,
        // 避免 reified encodeToString 在多模块编译路径上的类型推断不稳定。
        val array = kotlinx.serialization.json.buildJsonArray {
            cleaned.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        return array.toString()
    }

    private fun ConversationEntity.toSummary(): ConversationSummary {
        return ConversationSummary(
            id = id,
            title = title,
            lastMessageTime = formatRelativeTime(lastMessageTime),
            assistantId = assistantId,
            groupLabel = groupLabel ?: groupLabelFor(lastMessageTime),
            pinned = pinned,
            archived = archived,
            characterId = characterId,
            personaId = personaId,
            presetId = presetId,
            enabledRegexGroupIds = enabledRegexGroupIds,
            enabledOrphanRegexIds = enabledOrphanRegexIds,
            lorebookTimedEffectsJson = lorebookTimedEffectsJson,
            enabledToolIdsJson = enabledToolIdsJson,
            thinkingLevel = ThinkingLevel.parse(thinkingLevel),
            toolMode = ConversationToolMode.fromStorage(toolMode),
        )
    }

    private fun ConversationSummary.toEntity(lastMessageTimeMillis: Long = parseRelativeTime(lastMessageTime)): ConversationEntity {
        return ConversationEntity(
            id = id,
            title = title,
            lastMessageTime = lastMessageTimeMillis,
            assistantId = assistantId,
            groupLabel = groupLabel,
            pinned = pinned,
            archived = archived,
            characterId = characterId,
            personaId = personaId,
            presetId = presetId,
            enabledRegexGroupIds = enabledRegexGroupIds,
            enabledOrphanRegexIds = enabledOrphanRegexIds,
            lorebookTimedEffectsJson = lorebookTimedEffectsJson,
            enabledToolIdsJson = enabledToolIdsJson,
            thinkingLevel = ThinkingLevel.serialize(thinkingLevel),
            toolMode = toolMode.storageValue,
        )
    }

    private fun MessageEntity.toMessage(): Message {
        return Message(
            id = id,
            role = role,
            parts = decodeParts(partsJson),
            attachments = decodeAttachments(attachmentsJson),
        )
    }

    private fun Message.toEntity(conversationId: String, createdAt: Long): MessageEntity {
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            role = role,
            partsJson = encodeParts(parts),
            createdAt = createdAt,
            attachmentsJson = encodeAttachments(attachments),
        )
    }

    private fun decodeParts(json: String): List<MessagePart> {
        if (json.isBlank() || json == "[]") return emptyList()
        // 损坏 / 不可识别(如未来新增 part type)的 JSON 不应让整个会话加载崩:退化为空 parts,
        // 只丢这一条消息的内容。但不静默 — 记一条 warning 便于排查(对齐 PresetDataStore 等的 Log.w 口径)。
        return runCatching {
            partsJsonCodec.decodeFromString(ListSerializer(MessagePart.serializer()), json)
        }.getOrElse { error ->
            android.util.Log.w("ConversationRepository", "decodeParts failed, dropping message content", error)
            emptyList()
        }
    }

    private fun encodeParts(parts: List<MessagePart>): String {
        if (parts.isEmpty()) return "[]"
        return partsJsonCodec.encodeToString(ListSerializer(MessagePart.serializer()), parts)
    }

    private fun decodeAttachments(json: String): List<ImageAttachment> {
        if (json.isBlank() || json == "[]") return emptyList()
        // 损坏 JSON 不应让整个会话加载崩;退化为无附件,只丢这一条消息的图片引用。
        return runCatching {
            attachmentsJsonCodec.decodeFromString<List<ImageAttachment>>(json)
        }.getOrDefault(emptyList())
    }

    private fun encodeAttachments(attachments: List<ImageAttachment>): String {
        if (attachments.isEmpty()) return "[]"
        return attachmentsJsonCodec.encodeToString(attachments)
    }

    private fun formatRelativeTime(timestamp: Long): String {
        val elapsedMillis = System.currentTimeMillis() - timestamp
        return when {
            elapsedMillis < ONE_MINUTE_MILLIS -> "刚刚"
            elapsedMillis < ONE_HOUR_MILLIS -> "${elapsedMillis / ONE_MINUTE_MILLIS} 分钟前"
            elapsedMillis < ONE_DAY_MILLIS -> "${elapsedMillis / ONE_HOUR_MILLIS} 小时前"
            elapsedMillis < TWO_DAYS_MILLIS -> "昨天"
            else -> "${elapsedMillis / ONE_DAY_MILLIS} 天前"
        }
    }

    private fun groupLabelFor(timestamp: Long): String {
        val elapsedMillis = System.currentTimeMillis() - timestamp
        return when {
            elapsedMillis < ONE_DAY_MILLIS -> "今天"
            elapsedMillis < TWO_DAYS_MILLIS -> "昨天"
            else -> "更早"
        }
    }

    private fun parseRelativeTime(value: String): Long {
        val now = System.currentTimeMillis()
        return when {
            value == "刚刚" -> now
            value.endsWith(" 分钟前") -> now - (value.removeSuffix(" 分钟前").toLongOrNull() ?: 0L) * ONE_MINUTE_MILLIS
            value.endsWith(" 小时前") -> now - (value.removeSuffix(" 小时前").toLongOrNull() ?: 0L) * ONE_HOUR_MILLIS
            value == "昨天" -> now - ONE_DAY_MILLIS
            value.endsWith(" 天前") -> now - (value.removeSuffix(" 天前").toLongOrNull() ?: 0L) * ONE_DAY_MILLIS
            else -> now
        }
    }

    private companion object {
        const val ONE_MINUTE_MILLIS = 60_000L
        const val ONE_HOUR_MILLIS = 60 * ONE_MINUTE_MILLIS
        const val ONE_DAY_MILLIS = 24 * ONE_HOUR_MILLIS
        const val TWO_DAYS_MILLIS = 2 * ONE_DAY_MILLIS

        const val CHAT_IMAGE_DIRECTORY = "chat-images"
        val SUPPORTED_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")

        val attachmentsJsonCodec = Json { ignoreUnknownKeys = true }

        // MessagePart 多态序列化:判别字段用 type,默认值(result/denied)写盘保证 round-trip 稳定。
        val partsJsonCodec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }
    }
}

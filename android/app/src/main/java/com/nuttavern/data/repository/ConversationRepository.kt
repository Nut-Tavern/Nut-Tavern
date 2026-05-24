package com.nuttavern.data.repository

import com.nuttavern.data.local.dao.ConversationDao
import com.nuttavern.data.local.dao.MessageDao
import com.nuttavern.data.local.entity.ConversationEntity
import com.nuttavern.data.local.entity.MessageEntity
import com.nuttavern.data.model.ConversationSummary
import com.nuttavern.data.model.Message
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
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

    suspend fun updateMessageContent(
        messageId: String,
        content: String,
        reasoningContent: String,
        reasoningDurationMillis: Long,
    ) {
        messageDao.updateContentById(messageId, content, reasoningContent, reasoningDurationMillis)
    }

    suspend fun deleteMessage(messageId: String) {
        messageDao.deleteById(messageId)
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
        )
    }

    private fun MessageEntity.toMessage(): Message {
        return Message(
            id = id,
            role = role,
            content = content,
            reasoningContent = reasoningContent,
            reasoningDurationMillis = reasoningDurationMillis,
        )
    }

    private fun Message.toEntity(conversationId: String, createdAt: Long): MessageEntity {
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            role = role,
            content = content,
            reasoningContent = reasoningContent,
            reasoningDurationMillis = reasoningDurationMillis,
            createdAt = createdAt,
        )
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
    }
}

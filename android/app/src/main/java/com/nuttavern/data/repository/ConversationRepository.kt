package com.nuttavern.data.repository

import android.content.Context
import com.nuttavern.data.local.dao.ConversationDao
import com.nuttavern.data.local.dao.MessageDao
import com.nuttavern.data.local.entity.ConversationEntity
import com.nuttavern.data.local.entity.MessageEntity
import com.nuttavern.data.files.SupportedTextFileTypes
import com.nuttavern.data.model.ConversationSummary
import com.nuttavern.data.tools.ConversationToolMode
import com.nuttavern.data.model.FileAttachment
import com.nuttavern.data.model.ImageAttachment
import com.nuttavern.data.model.Message
import com.nuttavern.data.model.MessagePart
import com.nuttavern.data.model.ThinkingLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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

    /**
     * 按主键直读单条消息。供"生成新候选"流水线在落库前重查 target 用,避免使用上游捕获的
     * 旧快照(流式期间用户若编辑同一条消息,parts/swipes 会被 editMessage 同步落库)。
     */
    suspend fun getMessageById(messageId: String): Message? {
        return messageDao.getById(messageId)?.toMessage()
    }

    suspend fun updateMessageParts(
        messageId: String,
        parts: List<MessagePart>,
    ) {
        messageDao.updatePartsById(messageId, encodeParts(parts))
    }

    /**
     * 更新某条消息的 swipe 候选与当前选中索引,同时把 [partsJson] 同步为选中候选的内容
     * (保证"当前显示候选"与 swipeIndex 一致)。
     *
     * @param selectedParts 当前选中候选的 parts,写入 partsJson 供现有渲染 / 历史链路消费。
     * @param swipes 全部候选;空列表表示退化为无 swipe(swipesJson 写 '[]')。
     */
    suspend fun updateMessageSwipes(
        messageId: String,
        selectedParts: List<MessagePart>,
        swipes: List<List<MessagePart>>,
        swipeIndex: Int,
    ) {
        messageDao.updateSwipesById(
            messageId = messageId,
            partsJson = encodeParts(selectedParts),
            swipesJson = encodeSwipes(swipes),
            swipeIndex = swipeIndex,
        )
    }

    /**
     * 清理一批消息引用的附件文件(图片 + 文本文件)。
     *
     * 调用方:[deleteMessage] / [deleteMessagesAfter] / [deleteMessagesFrom] /
     * [deleteConversation]。**调用顺序固定为"先清文件、后删 DB 行"**——反过来 DB 行删了
     * `attachment.path` 就拿不到了,文件就成了孤儿。
     *
     * **IO 调度**:本函数体显式 [withContext] 切到 [Dispatchers.IO],覆盖 [java.io.File.delete]
     * 这条阻塞系统调用,避免删大会话时(N 条消息 × M 个附件)拖慢 ViewModel 主线程。
     * 与项目内 `CharacterViewModel` / `UserPersonaViewModel` 的落盘 IO 调度风格一致。
     *
     * **不在本函数 IO 范围内**:调用方在 purge 之前的 `messageDao.getXxx(...).map { it.toMessage() }`
     * 仍跑在调用方协程上下文(通常是 `viewModelScope` 默认 Main)。`toMessage` 内会顺带解码
     * `partsJson` / `swipesJson` 等并非 purge 真正需要的字段,这是已知冗余。删除链路调用频次
     * 低、单次解码量级可接受,本期不为它单独优化(详见 [decodeFileAttachments] 上方"宽容降级"
     * 的同款权衡口径)。如未来发现删大会话有掉帧,优先级是把整段"取列表 + map + purge"统一切 IO,
     * 而不是引入新的"只解附件 JSON 列"分叉。
     *
     * **失败语义**:单个文件删除失败(权限 / 文件已被外部清掉等)只 [android.util.Log.w]
     * 留痕,不抛异常、不阻断后续 DB 删除。文件系统错误本身罕见,且即便残留也只是孤儿
     * 文件,后续靠"附件 GC 待办"对账清理(见 `AGENTS.md` 待办)。这与 [decodeFileAttachments]
     * 的"宽容降级 + Log.w 留痕"一致。
     *
     * **不进 swipes**:附件挂在 [Message.attachments] / [Message.fileAttachments] 顶层
     * (user 消息携带),swipes 是 assistant 候选 parts,本身不携带附件,无需遍历。
     */
    private suspend fun purgeAttachmentFiles(messages: List<Message>) {
        if (messages.isEmpty()) return
        withContext(Dispatchers.IO) {
            for (message in messages) {
                for (image in message.attachments) {
                    deleteAttachmentFile(image.path, "image", message.id)
                }
                for (file in message.fileAttachments) {
                    deleteAttachmentFile(file.path, "file", message.id)
                }
            }
        }
    }

    /**
     * 删单个附件文件。
     *
     * **路径越界防御**:与 [saveImageBytes] / [saveFileBytes] 的 attachmentId 字符黑名单
     * 形成对称——save 侧拒绝可疑 id,delete 侧再次校验文件实际落点必须在 [filesDir]
     * canonical 路径之内。这是纵深防御:正常落库的 path 一定在 filesDir 下;若未来出现
     * 数据库导入旧备份时 path 字段被篡改 / 历史 schema 漂移等异常情况,这里能挡住把
     * 任意可写文件被 [java.io.File.delete] 删掉的风险。
     */
    private fun deleteAttachmentFile(path: String, kind: String, messageId: String) {
        if (path.isBlank()) return
        val file = File(path)
        val canonical = try {
            file.canonicalPath
        } catch (error: java.io.IOException) {
            android.util.Log.w(
                "ConversationRepository",
                "purge $kind attachment failed (canonicalPath) messageId=$messageId path=$path",
                error,
            )
            return
        }
        val filesRoot = context.filesDir.canonicalPath
        // 必须严格落在 filesDir 子路径下:`startsWith(filesRoot + File.separator)` 也会拒绝
        // path 恰为 filesDir 自身(无子路径)的情况。这是有意的保守策略——save 侧落盘必落到
        // chat-images/{id} / chat-files/{id},正常 attachment.path 不可能等于 filesDir 自身;
        // 真出现,说明 path 已被异常源污染,直接拒绝更安全。
        if (!canonical.startsWith(filesRoot + File.separator)) {
            android.util.Log.w(
                "ConversationRepository",
                "purge $kind attachment refused (out of filesDir) messageId=$messageId path=$path",
            )
            return
        }
        if (!file.exists()) return
        val deleted = try {
            file.delete()
        } catch (error: SecurityException) {
            android.util.Log.w(
                "ConversationRepository",
                "purge $kind attachment failed (security) messageId=$messageId path=$path",
                error,
            )
            return
        }
        if (!deleted) {
            android.util.Log.w(
                "ConversationRepository",
                "purge $kind attachment returned false messageId=$messageId path=$path",
            )
        }
    }

    suspend fun deleteMessage(messageId: String) {
        // 先取消息,再清附件文件,最后删 DB 行。
        // 顺序不能反:DB 行删了之后 attachment.path 就拿不到了,文件就成了孤儿。
        // 单条文件删除失败不阻断 DB 删除,Log.w 留痕便于排查;
        // 残留文件由后续"附件 GC 待办"对账清理(见 AGENTS.md 待办)。
        val target = messageDao.getById(messageId)?.toMessage()
        if (target != null) {
            purgeAttachmentFiles(listOf(target))
        }
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

    /**
     * 把文本文件字节写入 `filesDir/chat-files/{attachmentId}.{ext}`,返回可存进
     * [FileAttachment.path] 的绝对路径。设计与 [saveImageBytes] 完全对齐(只是目录与扩展名校验不同)。
     *
     * **不限大小**:用户决策"超了不怪我"。极端大文件读盘 OOM 由
     * [com.nuttavern.data.files.FileAttachmentPromptBuilder] 兜底转
     * `FileAttachmentReadException`,不让 app 崩。
     *
     * @throws IllegalArgumentException attachmentId 含路径分隔符 / 扩展名不在
     *   [SupportedTextFileTypes.EXTENSION_WHITELIST] 时抛出
     */
    fun saveFileBytes(attachmentId: String, bytes: ByteArray, extension: String): String {
        val target = fileAttachmentFor(attachmentId, extension)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return target.absolutePath
    }

    /** 读出文件附件字节;文件不存在返回 null。 */
    fun readFileBytes(path: String?): ByteArray? {
        val file = path?.takeIf { it.isNotBlank() }?.let { File(it) } ?: return null
        return if (file.exists()) file.readBytes() else null
    }

    private fun fileAttachmentFor(attachmentId: String, extension: String): File {
        require(attachmentId.isNotBlank()) { "Attachment id must not be blank." }
        // 与 imageFileFor 同口径:拒绝路径分隔符 / 上跳,防止写到私有目录外。
        require(attachmentId.none { it == '/' || it == '\\' } && !attachmentId.contains("..")) {
            "Attachment id must not contain path separators."
        }
        val safeExtension = extension.trim().trimStart('.').lowercase()
        require(safeExtension in SupportedTextFileTypes.EXTENSION_WHITELIST) {
            "Unsupported text file extension: $extension"
        }
        return File(File(context.filesDir, CHAT_FILE_DIRECTORY), "$attachmentId.$safeExtension")
    }

    suspend fun deleteMessagesAfter(conversationId: String, messageId: String) {
        val targets = messageDao.getAfter(conversationId, messageId).map { it.toMessage() }
        purgeAttachmentFiles(targets)
        messageDao.deleteAfter(conversationId, messageId)
    }

    suspend fun deleteMessagesFrom(conversationId: String, messageId: String) {
        val targets = messageDao.getFrom(conversationId, messageId).map { it.toMessage() }
        purgeAttachmentFiles(targets)
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
        // 同 deleteMessage 链路:先取整个会话的消息列表清附件,再删会话 DB 行
        // (Room 外键 cascade 会一并删 messages 行)。顺序不能反。
        val targets = messageDao.getAllByConversationId(id).map { it.toMessage() }
        purgeAttachmentFiles(targets)
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
            enabledLorebookIdsJson = enabledLorebookIdsJson,
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
            enabledLorebookIdsJson = enabledLorebookIdsJson,
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
            fileAttachments = decodeFileAttachments(fileAttachmentsJson),
            swipes = decodeSwipes(swipesJson),
            swipeIndex = swipeIndex,
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
            fileAttachmentsJson = encodeFileAttachments(fileAttachments),
            swipesJson = encodeSwipes(swipes),
            swipeIndex = swipeIndex,
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

    private fun decodeSwipes(json: String): List<List<MessagePart>> {
        if (json.isBlank() || json == "[]") return emptyList()
        // 损坏 / 不可识别的 swipe JSON 不应让会话加载崩:退化为无候选(只剩 partsJson 那一个版本)。
        // 与 decodeParts 同口径记 warning 便于排查。
        return runCatching {
            swipesJsonCodec.decodeFromString(swipesSerializer, json)
        }.getOrElse { error ->
            android.util.Log.w("ConversationRepository", "decodeSwipes failed, dropping swipe candidates", error)
            emptyList()
        }
    }

    private fun encodeSwipes(swipes: List<List<MessagePart>>): String {
        if (swipes.isEmpty()) return "[]"
        return swipesJsonCodec.encodeToString(swipesSerializer, swipes)
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

    private fun decodeFileAttachments(json: String): List<FileAttachment> {
        if (json.isBlank() || json == "[]") return emptyList()
        // 损坏 JSON 不让整个会话加载崩,退化为无文件附件。与 decodeParts / decodeSwipes 同口径
        // 记 warning 便于排查(对齐 PresetDataStore 等 Log.w 风格,**不**沿用 decodeAttachments
        // 的静默吞错 — 那处口径漂移已在 AGENTS.md 代码审计待办登记)。
        return runCatching {
            attachmentsJsonCodec.decodeFromString<List<FileAttachment>>(json)
        }.getOrElse { error ->
            android.util.Log.w("ConversationRepository", "decodeFileAttachments failed, dropping file attachments", error)
            emptyList()
        }
    }

    private fun encodeFileAttachments(attachments: List<FileAttachment>): String {
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

        const val CHAT_FILE_DIRECTORY = "chat-files"

        val attachmentsJsonCodec = Json { ignoreUnknownKeys = true }

        // MessagePart 多态序列化:判别字段用 type,默认值(result/denied)写盘保证 round-trip 稳定。
        val partsJsonCodec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }

        // swipe 候选编解码复用 partsJsonCodec 的多态配置:每个候选是一个 List<MessagePart>,
        // 整体是 List<List<MessagePart>>。单独建 codec 只为复用 serializer,配置必须与 parts 一致。
        val swipesJsonCodec = partsJsonCodec
        val swipesSerializer = ListSerializer(ListSerializer(MessagePart.serializer()))
    }
}

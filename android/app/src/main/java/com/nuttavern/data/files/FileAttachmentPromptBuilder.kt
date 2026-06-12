package com.nuttavern.data.files

import com.nuttavern.data.model.FileAttachment
import com.nuttavern.data.repository.ConversationRepository
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 把文件附件读盘 → XML 标签包装 → 拼到 user 消息文本前。
 *
 * 模板(单个附件):
 * ```
 * <file name="example.txt" mime="text/plain">
 * <内容>
 * </file>
 * ```
 *
 * 多个附件之间用单空行隔开;最终结果再用单空行与 user 输入文本隔开。
 *
 * **为什么 XML 标签而不是 Markdown 标题**:rikkahub 用 `## user sent a file: <name>` Markdown
 * 标题在 user 消息里会被部分模型当成话题分隔解释,影响后续指令理解。XML 风格标签
 * 对 Claude / GPT 系列都更稳定。
 *
 * **错误处理**:任一附件读取失败抛 [FileAttachmentReadException],调用方阻断发送 + 吐司。
 * **不**沿用 rikkahub 把 `[ERROR, ...]` 占位塞进 prompt 的做法 — 那会让模型基于"看似真实
 * 的错误描述"回答,污染对话。
 */
object FileAttachmentPromptBuilder {

    /**
     * 读盘 + 包装 + 拼接,返回最终要前置到 user 消息文本前的字符串。
     *
     * @param attachments 待拼接的文件附件列表;空列表返回空串
     * @param repository 用来按 [FileAttachment.path] 读盘
     *
     * @throws FileAttachmentReadException 文件不存在 / IO 失败 / 解码非法 UTF-8 / 读到 OOM
     *   时抛出,cause 透传原始异常,message 字段供 UI 直接吐司。
     */
    fun buildPrependedText(
        attachments: List<FileAttachment>,
        repository: ConversationRepository,
    ): String {
        if (attachments.isEmpty()) return ""
        val blocks = attachments.map { attachment -> buildSingleBlock(attachment, repository) }
        return blocks.joinToString(separator = "\n\n")
    }

    private fun buildSingleBlock(
        attachment: FileAttachment,
        repository: ConversationRepository,
    ): String {
        val text = readAsText(attachment, repository)
        val safeName = escapeXmlAttribute(attachment.fileName)
        val safeMime = escapeXmlAttribute(attachment.mimeType)
        return buildString {
            append("<file name=\"")
            append(safeName)
            append("\" mime=\"")
            append(safeMime)
            append("\">\n")
            append(text)
            // 内容若不以换行结尾,补一个,保证 </file> 单独成行。
            if (!text.endsWith('\n')) append('\n')
            append("</file>")
        }
    }

    private fun readAsText(
        attachment: FileAttachment,
        repository: ConversationRepository,
    ): String {
        val bytes = try {
            repository.readFileBytes(attachment.path)
        } catch (oom: OutOfMemoryError) {
            // 不限大小 → 极端大文件读字节就 OOM,显式兜底转可读异常,不让 app 崩。
            throw FileAttachmentReadException(
                attachment = attachment,
                message = "文件过大无法读取:${attachment.fileName}",
                cause = oom,
            )
        } catch (io: Exception) {
            // 只接 Exception(不接 Error / Throwable):IOException / SecurityException
            // 等业务失败转可读异常;StackOverflowError / VirtualMachineError 等 JVM 致命
            // 错误继续向上抛,不该被业务路径吞掉。
            throw FileAttachmentReadException(
                attachment = attachment,
                message = "附件读取失败:${attachment.fileName}",
                cause = io,
            )
        }
        if (bytes == null) {
            // 文件被外部清掉(卸载、清缓存、用户手动删 filesDir):历史重发场景常见。
            throw FileAttachmentReadException(
                attachment = attachment,
                message = "附件已丢失:${attachment.fileName}",
                cause = null,
            )
        }
        return decodeUtf8(bytes, attachment)
    }

    /**
     * 字节 → UTF-8 字符串。规则:
     * - 跳过 UTF-8 BOM(`EF BB BF`,3 字节)
     * - 严格解码非法 UTF-8 序列(报错而非替换字符),让 ViewModel 拒收时给到明确提示
     *
     * UTF-16 / UTF-32 BOM 已在 [com.nuttavern.ui.viewmodel.ChatViewModel.addFileAttachment]
     * 拦截,不会到这一步。
     */
    private fun decodeUtf8(bytes: ByteArray, attachment: FileAttachment): String {
        val withoutBom = if (
            bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        // CharsetDecoder 严格模式:遇非法序列直接抛 MalformedInputException,
        // 不像 String(bytes, UTF_8) 默认替换为 U+FFFD 后无声成功。
        val decoder = StandardCharsets.UTF_8.newDecoder().apply {
            onMalformedInput(CodingErrorAction.REPORT)
            onUnmappableCharacter(CodingErrorAction.REPORT)
        }
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(withoutBom)).toString()
        } catch (e: CharacterCodingException) {
            throw FileAttachmentReadException(
                attachment = attachment,
                message = "文件不是有效的 UTF-8 文本:${attachment.fileName}",
                cause = e,
            )
        } catch (oom: OutOfMemoryError) {
            // 字节读出来了,但解码出来的 String 太大也会 OOM(罕见但可能)
            throw FileAttachmentReadException(
                attachment = attachment,
                message = "文件过大无法读取:${attachment.fileName}",
                cause = oom,
            )
        }
    }

    /**
     * 转义 XML 属性值里的 5 个特殊字符。文件名和 MIME 都走属性值,不能含未转义的 `"` `<` `>` `&` `'`。
     * Android 文件名理论上不会含这些字符(`<` `>` 是非法字符),但 MIME 在某些奇特设备上可能含 `"`,
     * 防御性转义不增加多少成本。
     */
    private fun escapeXmlAttribute(value: String): String {
        return buildString(value.length) {
            value.forEach { c ->
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(c)
                }
            }
        }
    }

    /**
     * 文件附件读盘失败的统一异常。[message] 字段已写好可直接吐司给用户。
     */
    class FileAttachmentReadException(
        val attachment: FileAttachment,
        override val message: String,
        cause: Throwable?,
    ) : RuntimeException(message, cause)
}

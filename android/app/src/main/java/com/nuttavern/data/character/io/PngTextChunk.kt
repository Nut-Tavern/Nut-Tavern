package com.nuttavern.data.character.io

import java.util.zip.CRC32

/**
 * PNG tEXt chunk 读写。**对齐酒馆 `character-card-parser.js`** 的 read / write,
 * 纯字节操作,不依赖第三方 PNG 库。
 *
 * 角色卡把角色 JSON(base64)藏在 PNG 的 tEXt chunk 里:
 * - `ccv3`:V3 数据(优先读);
 * - `chara`:V2 数据(回退读)。
 *
 * 导出时按酒馆口径**双写** `chara` + `ccv3`,最大兼容老客户端(character-card-parser.js write)。
 *
 * PNG 结构:8 字节签名 + 若干 chunk(每个 = 4 字节长度 + 4 字节类型 + data + 4 字节 CRC32)。
 * tEXt chunk 的 data 形态:`keyword\u0000text`(keyword 用 Latin-1,text 这里固定是 base64 ASCII)。
 */
object PngTextChunk {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private const val TYPE_TEXT = "tEXt"
    private const val TYPE_IEND = "IEND"

    /** chunk 头:4 字节 length + 4 字节 type。 */
    private const val CHUNK_HEADER_SIZE = 8

    /** chunk 尾:4 字节 CRC32。 */
    private const val CRC_SIZE = 4

    /**
     * 读出 PNG 里所有 tEXt chunk,返回 keyword(小写)→ text。
     *
     * 对畸形输入(负 length / 超长 length / offset 不前进)做防护:任一异常立即停止遍历,
     * 避免死循环。损坏或恶意 PNG 不应让调用方卡死。
     *
     * @throws IllegalArgumentException 不是合法 PNG(签名不符)时抛出
     */
    fun readTextChunks(image: ByteArray): Map<String, String> {
        requirePngSignature(image)

        val chunks = mutableMapOf<String, String>()
        var offset = PNG_SIGNATURE.size
        while (offset + CHUNK_HEADER_SIZE <= image.size) {
            val length = readUInt32(image, offset)
            val type = readType(image, offset + 4)
            val dataStart = offset + CHUNK_HEADER_SIZE

            // length 用无符号 Long 读出,校验落在剩余字节内(同时拦截负值伪装的超大值)。
            if (length !in 0L..(image.size - dataStart).toLong()) break
            val dataLength = length.toInt()
            if (type == TYPE_TEXT) {
                decodeTextChunk(image, dataStart, dataLength)?.let { (keyword, text) ->
                    chunks[keyword.lowercase()] = text
                }
            }
            if (type == TYPE_IEND) break
            // 跳到下一个 chunk:length + type(4) + data(length) + crc(4)。
            // 严格递增校验:防止畸形 length 让 offset 原地踏步形成死循环。
            val next = dataStart + dataLength + CRC_SIZE
            if (next <= offset) break
            offset = next
        }
        return chunks
    }

    /**
     * 在 IEND 前插入 / 替换 tEXt chunk。同名 keyword(忽略大小写)的旧 tEXt 先移除,再追加新值。
     *
     * @param image 原始 PNG 字节(作为底图,图像数据不动)
     * @param textChunks keyword → text(text 应已是 ASCII,如 base64)
     * @throws IllegalArgumentException 原始不是合法 PNG / 结构损坏(无法定位 IEND)时抛出
     */
    fun writeTextChunks(image: ByteArray, textChunks: Map<String, String>): ByteArray {
        requirePngSignature(image)

        val removedKeywords = textChunks.keys.map { it.lowercase() }.toSet()
        val output = java.io.ByteArrayOutputStream(image.size + estimatedSize(textChunks))
        output.write(PNG_SIGNATURE)

        var offset = PNG_SIGNATURE.size
        var insertedBeforeIend = false
        while (offset + CHUNK_HEADER_SIZE <= image.size) {
            val length = readUInt32(image, offset)
            val type = readType(image, offset + 4)
            val dataStart = offset + CHUNK_HEADER_SIZE
            if (length !in 0L..(image.size - dataStart - CRC_SIZE).toLong()) break
            val dataLength = length.toInt()
            val chunkEnd = dataStart + dataLength + CRC_SIZE

            if (type == TYPE_IEND) {
                // IEND 之前插入全部新 tEXt
                for ((keyword, text) in textChunks) {
                    output.write(buildTextChunk(keyword, text))
                }
                output.write(image, offset, chunkEnd - offset)
                insertedBeforeIend = true
                break
            }

            val isReplacedText = type == TYPE_TEXT &&
                decodeTextChunk(image, dataStart, dataLength)?.first?.lowercase() in removedKeywords
            if (!isReplacedText) {
                output.write(image, offset, chunkEnd - offset)
            }
            if (chunkEnd <= offset) break
            offset = chunkEnd
        }
        require(insertedBeforeIend) { "PNG 结构损坏:未找到 IEND,无法写入角色卡数据" }
        return output.toByteArray()
    }

    private fun decodeTextChunk(image: ByteArray, dataStart: Int, length: Int): Pair<String, String>? {
        val data = image.copyOfRange(dataStart, dataStart + length)
        val nullIndex = data.indexOf(0)
        if (nullIndex < 0) return null
        val keyword = String(data, 0, nullIndex, Charsets.ISO_8859_1)
        val text = String(data, nullIndex + 1, data.size - nullIndex - 1, Charsets.ISO_8859_1)
        return keyword to text
    }

    private fun buildTextChunk(keyword: String, text: String): ByteArray {
        val keywordBytes = keyword.toByteArray(Charsets.ISO_8859_1)
        val textBytes = text.toByteArray(Charsets.ISO_8859_1)
        val data = ByteArray(keywordBytes.size + 1 + textBytes.size)
        keywordBytes.copyInto(data, 0)
        data[keywordBytes.size] = 0
        textBytes.copyInto(data, keywordBytes.size + 1)

        val typeBytes = TYPE_TEXT.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value

        return java.io.ByteArrayOutputStream(12 + data.size).apply {
            write(uint32ToBytes(data.size))
            write(typeBytes)
            write(data)
            write(uint32ToBytes(crc))
        }.toByteArray()
    }

    private fun requirePngSignature(image: ByteArray) {
        require(image.size >= PNG_SIGNATURE.size) { "不是合法的 PNG 文件(太短)" }
        for (i in PNG_SIGNATURE.indices) {
            require(image[i] == PNG_SIGNATURE[i]) { "不是合法的 PNG 文件(签名不符)" }
        }
    }

    /** 按大端读 4 字节为无符号 32 位整数(用 Long 承载,避免最高位为 1 时变成负数)。 */
    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun readType(bytes: ByteArray, offset: Int): String {
        return String(bytes, offset, 4, Charsets.US_ASCII)
    }

    private fun uint32ToBytes(value: Long): ByteArray = uint32ToBytes(value.toInt())

    private fun uint32ToBytes(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun estimatedSize(textChunks: Map<String, String>): Int =
        textChunks.entries.sumOf { 12 + it.key.length + 1 + it.value.length }
}

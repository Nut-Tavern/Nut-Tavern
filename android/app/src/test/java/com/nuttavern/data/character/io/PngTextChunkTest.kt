package com.nuttavern.data.character.io

import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PngTextChunk] 读写单测。用最小合法 PNG(签名 + IEND)做底,验证 tEXt chunk 的写入 / 读取 /
 * 替换 / CRC 正确性,不依赖任何外部图片文件。
 */
class PngTextChunkTest {

    @Test
    fun writeThenReadRoundTripsTextChunk() {
        val png = minimalPng()
        val written = PngTextChunk.writeTextChunks(png, linkedMapOf("chara" to "aGVsbG8="))

        val chunks = PngTextChunk.readTextChunks(written)
        assertEquals("aGVsbG8=", chunks["chara"])
    }

    @Test
    fun writeDualChunksKeepsBoth() {
        val png = minimalPng()
        val written = PngTextChunk.writeTextChunks(
            png,
            linkedMapOf("chara" to "djI=", "ccv3" to "djM="),
        )

        val chunks = PngTextChunk.readTextChunks(written)
        assertEquals("djI=", chunks["chara"])
        assertEquals("djM=", chunks["ccv3"])
    }

    @Test
    fun writeReplacesExistingSameKeyword() {
        val png = minimalPng()
        val first = PngTextChunk.writeTextChunks(png, linkedMapOf("chara" to "b2xk"))
        val second = PngTextChunk.writeTextChunks(first, linkedMapOf("chara" to "bmV3"))

        val chunks = PngTextChunk.readTextChunks(second)
        assertEquals("bmV3", chunks["chara"])
        // 不应残留旧 chunk(同 keyword 只剩一个)
        assertEquals(1, countTextChunks(second))
    }

    @Test
    fun readKeywordIsCaseInsensitiveLowercased() {
        val png = minimalPng()
        val written = PngTextChunk.writeTextChunks(png, linkedMapOf("Chara" to "eA=="))

        val chunks = PngTextChunk.readTextChunks(written)
        assertEquals("eA==", chunks["chara"])
    }

    @Test
    fun writtenChunkHasValidCrc() {
        val png = minimalPng()
        val written = PngTextChunk.writeTextChunks(png, linkedMapOf("chara" to "eHk="))

        assertTrue("写出的 PNG 所有 chunk CRC 必须自洽", allChunkCrcsValid(written))
    }

    @Test
    fun readReturnsEmptyWhenNoTextChunks() {
        val chunks = PngTextChunk.readTextChunks(minimalPng())
        assertTrue(chunks.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun readRejectsNonPng() {
        PngTextChunk.readTextChunks(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
    }

    @Test(timeout = 2000)
    fun readDoesNotHangOnNegativeChunkLength() {
        // 构造 length 字段 = 0xFFFFFFF4(有符号 Int 下 = -12)的畸形 chunk。
        // 旧实现会让遍历 offset 原地踏步形成死循环;修复后应安全 break。
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // length = 0xFFFFFFF4
        out.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xF4.toByte()))
        // type:任意非 tEXt / IEND 的 4 字节
        out.write("bKGD".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0, 0, 0)) // 占位 CRC
        val malformed = out.toByteArray()

        val chunks = PngTextChunk.readTextChunks(malformed)
        // 不应抛异常、不应死循环,畸形 chunk 被跳过 → 空结果
        assertTrue(chunks.isEmpty())
    }

    @Test(timeout = 2000)
    fun readDoesNotHangOnOversizedChunkLength() {
        // length 远超剩余字节,应安全 break 而非越界 / 死循环
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(byteArrayOf(0x7F.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())) // 巨大正 length
        out.write("tEXt".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0, 0, 0))
        val malformed = out.toByteArray()

        val chunks = PngTextChunk.readTextChunks(malformed)
        assertTrue(chunks.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeRejectsPngWithoutIend() {
        // 无 IEND 的 PNG 无法定位插入点,应抛异常而不是静默产出残缺文件
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // 一个普通 chunk,但没有 IEND
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write("IHDR".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0, 0, 0))
        PngTextChunk.writeTextChunks(out.toByteArray(), linkedMapOf("chara" to "eA=="))
    }

    @Test
    fun roundTripsUnicodeTextThroughBase64() {
        // 角色卡 JSON 含中文,base64 编码后是 ASCII,tEXt 用 ISO-8859-1 存取应无损
        val original = "{\"name\":\"中文角色\"}"
        val base64 = java.util.Base64.getEncoder().encodeToString(original.toByteArray(Charsets.UTF_8))
        val written = PngTextChunk.writeTextChunks(minimalPng(), linkedMapOf("ccv3" to base64))

        val readBack = PngTextChunk.readTextChunks(written)["ccv3"]
        assertEquals(base64, readBack)
        val decoded = String(java.util.Base64.getDecoder().decode(readBack), Charsets.UTF_8)
        assertEquals(original, decoded)
    }

    // ── helpers ──

    /** 最小合法 PNG:8 字节签名 + 一个 IEND chunk(长度 0)。 */
    private fun minimalPng(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value
        return java.io.ByteArrayOutputStream().apply {
            write(uint32(data.size))
            write(typeBytes)
            write(data)
            write(uint32(crc.toInt()))
        }.toByteArray()
    }

    private fun uint32(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun countTextChunks(image: ByteArray): Int {
        var count = 0
        var offset = 8
        while (offset + 8 <= image.size) {
            val length = readUInt32(image, offset)
            val type = String(image, offset + 4, 4, Charsets.US_ASCII)
            if (type == "tEXt") count++
            if (type == "IEND") break
            offset += 12 + length
        }
        return count
    }

    private fun allChunkCrcsValid(image: ByteArray): Boolean {
        var offset = 8
        while (offset + 8 <= image.size) {
            val length = readUInt32(image, offset)
            val typeStart = offset + 4
            val dataStart = offset + 8
            val crcStart = dataStart + length
            if (crcStart + 4 > image.size) return false
            val expected = readUInt32(image, crcStart).toLong() and 0xFFFFFFFFL
            val actual = CRC32().apply {
                update(image, typeStart, 4 + length)
            }.value
            if (expected != actual) return false
            val type = String(image, typeStart, 4, Charsets.US_ASCII)
            if (type == "IEND") break
            offset = crcStart + 4
        }
        return true
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}

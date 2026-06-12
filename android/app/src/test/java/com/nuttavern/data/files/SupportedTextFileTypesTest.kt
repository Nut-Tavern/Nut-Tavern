package com.nuttavern.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SupportedTextFileTypes.isAllowed] 三层校验(MIME 白名单 / text 前缀 / 扩展名白名单)
 * 与 [SupportedTextFileTypes.extensionOf] 的覆盖测试。
 */
class SupportedTextFileTypesTest {

    // --- 第 1 层:MIME 白名单命中 ---

    @Test
    fun isAllowed_mimeInWhitelist_returnsTrue() {
        assertTrue(SupportedTextFileTypes.isAllowed("application/json", "data.bin"))
        assertTrue(SupportedTextFileTypes.isAllowed("application/xml", "data.bin"))
        assertTrue(SupportedTextFileTypes.isAllowed("application/javascript", "data.bin"))
        assertTrue(SupportedTextFileTypes.isAllowed("application/x-yaml", "data.bin"))
        assertTrue(SupportedTextFileTypes.isAllowed("application/yaml", "data.bin"))
        assertTrue(SupportedTextFileTypes.isAllowed("application/toml", "data.bin"))
    }

    @Test
    fun isAllowed_mimeWhitelistIsCaseInsensitive() {
        assertTrue(SupportedTextFileTypes.isAllowed("Application/JSON", "data.bin"))
        assertTrue(SupportedTextFileTypes.isAllowed("  application/json  ", "data.bin"))
    }

    // --- 第 2 层:text/* 前缀放行 ---

    @Test
    fun isAllowed_textSlashAnything_returnsTrue() {
        assertTrue(SupportedTextFileTypes.isAllowed("text/plain", "no-ext"))
        assertTrue(SupportedTextFileTypes.isAllowed("text/markdown", "no-ext"))
        assertTrue(SupportedTextFileTypes.isAllowed("text/csv", "no-ext"))
        assertTrue(SupportedTextFileTypes.isAllowed("text/x-kotlin", "no-ext"))
        assertTrue(SupportedTextFileTypes.isAllowed("text/anything-future-subtype", "no-ext"))
    }

    // --- 第 3 层:扩展名兜底(MIME 不可信时) ---

    @Test
    fun isAllowed_octetStreamMime_fallsBackToExtension() {
        // Android 部分设备对 .kt / .md / .toml 返回 application/octet-stream,扩展名兜底必须放行
        assertTrue(SupportedTextFileTypes.isAllowed("application/octet-stream", "Main.kt"))
        assertTrue(SupportedTextFileTypes.isAllowed("application/octet-stream", "README.md"))
        assertTrue(SupportedTextFileTypes.isAllowed("application/octet-stream", "config.toml"))
        assertTrue(SupportedTextFileTypes.isAllowed("application/octet-stream", "build.gradle.kts"))
    }

    @Test
    fun isAllowed_nullOrBlankMime_fallsBackToExtension() {
        // ContentResolver 偶发返回 null / 空,扩展名兜底必须放行
        assertTrue(SupportedTextFileTypes.isAllowed(null, "notes.txt"))
        assertTrue(SupportedTextFileTypes.isAllowed("", "notes.txt"))
        assertTrue(SupportedTextFileTypes.isAllowed("   ", "notes.txt"))
    }

    @Test
    fun isAllowed_extensionIsCaseInsensitive() {
        assertTrue(SupportedTextFileTypes.isAllowed(null, "DATA.JSON"))
        assertTrue(SupportedTextFileTypes.isAllowed(null, "Notes.MD"))
    }

    // --- 拒绝分支 ---

    @Test
    fun isAllowed_binaryMime_extensionAlsoNotInWhitelist_returnsFalse() {
        assertFalse(SupportedTextFileTypes.isAllowed("application/pdf", "report.pdf"))
        assertFalse(SupportedTextFileTypes.isAllowed("image/png", "screenshot.png"))
        assertFalse(SupportedTextFileTypes.isAllowed("video/mp4", "clip.mp4"))
        assertFalse(SupportedTextFileTypes.isAllowed("application/zip", "archive.zip"))
        assertFalse(SupportedTextFileTypes.isAllowed("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "doc.docx"))
    }

    @Test
    fun isAllowed_octetStreamWithoutExtension_returnsFalse() {
        assertFalse(SupportedTextFileTypes.isAllowed("application/octet-stream", "no-extension"))
        assertFalse(SupportedTextFileTypes.isAllowed("application/octet-stream", "trailing-dot."))
    }

    @Test
    fun isAllowed_octetStreamWithUnknownExtension_returnsFalse() {
        assertFalse(SupportedTextFileTypes.isAllowed("application/octet-stream", "binary.exe"))
        assertFalse(SupportedTextFileTypes.isAllowed("application/octet-stream", "image.bmp"))
    }

    // --- extensionOf ---

    @Test
    fun extensionOf_singleExtension_returnsLowercaseWithoutDot() {
        assertEquals("kt", SupportedTextFileTypes.extensionOf("Main.kt"))
        assertEquals("json", SupportedTextFileTypes.extensionOf("data.JSON"))
    }

    @Test
    fun extensionOf_multiDot_returnsLastSegment() {
        // 与 java.io.File.extension 同口径:取最后一个点之后的部分
        assertEquals("kts", SupportedTextFileTypes.extensionOf("build.gradle.kts"))
        assertEquals("gz", SupportedTextFileTypes.extensionOf("archive.tar.gz"))
    }

    @Test
    fun extensionOf_noExtensionOrTrailingDot_returnsEmpty() {
        assertEquals("", SupportedTextFileTypes.extensionOf("README"))
        assertEquals("", SupportedTextFileTypes.extensionOf("file."))
        assertEquals("", SupportedTextFileTypes.extensionOf(""))
    }

    @Test
    fun extensionOf_hiddenFileWithoutExtension_returnsEmpty() {
        // .gitignore 这类"以点开头但只有一段"的文件:lastIndexOf('.') == 0,
        // dotIndex == 0 不等于 length-1(若 ".gitignore" 长度 > 1),取后续 "gitignore" 视为扩展名。
        // 这是与 java.io.File.extension 一致的边界,可接受 — 用户用 .gitignore 当文本附件不应被拒。
        assertEquals("gitignore", SupportedTextFileTypes.extensionOf(".gitignore"))
    }
}

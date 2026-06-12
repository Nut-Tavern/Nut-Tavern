package com.nuttavern.data.files

/**
 * 文本文件附件的 MIME / 扩展名白名单。
 *
 * 选文件链路:[com.nuttavern.ui.viewmodel.ChatViewModel.addFileAttachment] 在拿到 Uri
 * 后用 [isAllowed] 双层校验(MIME 白名单 / text 前缀 / 扩展名白名单,任一命中即放行)。
 *
 * 落盘链路:[com.nuttavern.data.repository.ConversationRepository.fileAttachmentFor] 用
 * [EXTENSION_WHITELIST] 校验扩展名,防止 attachmentId.ext 写到非白名单后缀。
 *
 * **不对齐酒馆**(酒馆 chat completion 没有通用文件块);白名单参考 rikkahub
 * (`E:\Code\rikkahub-master\app\src\main\java\me\rerere\rikkahub\ui\components\ai\ChatInput.kt:312-366`)
 * 的同款思路,但常量集中管理,不像 rikkahub 把 36 个 endsWith 写死在 Composable 里。
 */
object SupportedTextFileTypes {
    /**
     * MIME 白名单。命中即放行。
     *
     * 不含 `text/` 前缀的项(如 `application/json`):很多文本类格式 IANA 注册在 application 命名空间下,
     * 必须显式列出;text/ 前缀的整体放行由 [isAllowed] 单独处理。
     */
    val MIME_WHITELIST: Set<String> = setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/x-yaml",
        "application/yaml",
        "application/toml",
    )

    /**
     * 扩展名白名单(小写,不含点)。命中即放行。
     *
     * 覆盖常见纯文本格式:文档 / 配置 / 主流编程语言源码 / shell 脚本 / 日志。
     * 二进制格式(PDF / DOCX / PPTX / EPUB / 图片 / 音视频)不进白名单——
     * 即便用户通过 ContentResolver 强行绕过 MIME 选择器选中,扩展名也会在这一道拦下。
     */
    val EXTENSION_WHITELIST: Set<String> = setOf(
        // 文档 / 数据
        "txt", "md", "markdown", "mdx",
        "csv", "tsv",
        "json", "jsonc", "json5",
        "xml", "yaml", "yml", "toml",
        // 网页 / 样式
        "html", "htm", "css",
        // JS / TS 系
        "js", "mjs", "cjs", "ts", "tsx", "jsx",
        // 编程语言
        "py", "java", "kt", "kts", "go", "rs",
        "c", "cpp", "cc", "cxx", "h", "hpp", "hxx",
        "rb", "php", "swift", "scala",
        "sql", "r",
        // shell / 配置
        "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
        "ini", "conf", "cfg", "properties", "env",
        // 日志 / 其他纯文本
        "log",
    )

    /**
     * 是否允许作为文本文件附件。
     *
     * 三层任一命中即放行:
     * 1. MIME 命中 [MIME_WHITELIST]
     * 2. MIME 以 `text/` 开头(覆盖 `text/plain` / `text/markdown` / `text/x-kotlin` 等任意 text 子类型)
     * 3. 文件名扩展名命中 [EXTENSION_WHITELIST]
     *
     * **第 3 层兜底关键**:Android 在某些设备上对 .kt / .md / .toml 等返回
     * `application/octet-stream` 或干脆返回 null,只靠 MIME 会漏放;扩展名兜底拦回来。
     *
     * @param mime ContentResolver 返回的 MIME(可能为 null / 空 / `application/octet-stream`)
     * @param fileName 原始文件名(含扩展名),不区分大小写
     */
    fun isAllowed(mime: String?, fileName: String): Boolean {
        val normalizedMime = mime?.trim()?.lowercase().orEmpty()
        if (normalizedMime in MIME_WHITELIST) return true
        if (normalizedMime.startsWith("text/")) return true
        return extensionOf(fileName) in EXTENSION_WHITELIST
    }

    /**
     * 提取文件名扩展名(小写,不含点)。无扩展名返回空串。
     *
     * 多点文件名(如 `archive.tar.gz`)只取最后一段(`gz`),与 [java.io.File.extension] 行为一致。
     */
    fun extensionOf(fileName: String): String {
        val name = fileName.trim()
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex < 0 || dotIndex == name.length - 1) return ""
        return name.substring(dotIndex + 1).lowercase()
    }
}

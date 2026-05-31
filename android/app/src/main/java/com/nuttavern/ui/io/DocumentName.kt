package com.nuttavern.ui.io

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * 取 SAF(Storage Access Framework)文档 Uri 的真实显示文件名(去 .json 后缀)。
 *
 * `OpenDocument` 返回的 Uri,其 [Uri.getLastPathSegment] 是 document id(如 `msf:1234` /
 * `primary:Download/x.json`),**不是**用户看到的文件名。正确来源是 ContentResolver 查
 * [OpenableColumns.DISPLAY_NAME]。查询失败(provider 不支持该列)时回退到 lastPathSegment
 * 的末段,最后兜底 [fallback]。
 *
 * @return 去掉 `.json` 后缀的文件名;全部取不到时返回 [fallback]。
 */
fun Uri.resolveImportFileName(context: Context, fallback: String): String {
    val displayName = queryDisplayName(context)
        ?: lastPathSegment?.substringAfterLast('/')
    return displayName?.removeSuffix(".json")?.takeIf { it.isNotBlank() } ?: fallback
}

private fun Uri.queryDisplayName(context: Context): String? {
    return runCatching {
        context.contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
    }.getOrNull()
}

package com.nuttavern.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 三态明暗模式。`System` 跟随系统(默认),`Light` / `Dark` 强制覆盖。
 *
 * 跨进程持久化用 [name] 字符串,反序列化失败兜底到 [System],避免 DataStore 损坏导致主题崩溃。
 */
enum class ThemeMode {
    System,
    Light,
    Dark,
}

/**
 * 主题描述。
 *
 * - [id] 持久化标识,跨版本必须稳定。新增主题必须新增 id,不要复用旧 id。
 * - [seedColor] 种子色,会被 `ColorSchemeFactory` 展开成完整 ColorScheme。
 *   动态主题(系统壁纸取色)的 [seedColor] 仅作降级兜底,真实色由 Android 12+ API 提供。
 * - [supportsDynamic] 是否依赖系统动态色(壁纸取色)。当前仅 [ThemePresets.Dynamic] 为 true。
 */
data class ThemeSpec(
    val id: String,
    val displayName: String,
    val seedColor: Color,
    val supportsDynamic: Boolean = false,
)

/**
 * 内置主题预设清单。新增 / 删除主题改这里,别散落到 UI 层。
 *
 * 当前阶段:
 * 1. [Default] —— 中性灰种子色,种子来自历史的 LightAccent (#4D5358),保证升级到种子方案后视觉接近原状。
 * 2. [Dynamic] —— 依赖 Android 12+ 系统壁纸取色;低版本必须由 ThemeRepository 兜底到 [Default]。
 *
 * 后续可在此追加 Warm / Cool / Forest 等预设;只要新增 ThemeSpec 即可,无需改其他文件。
 */
object ThemePresets {
    val Default = ThemeSpec(
        id = "default",
        displayName = "默认",
        seedColor = Color(0xFF4D5358),
    )

    val Dynamic = ThemeSpec(
        id = "dynamic",
        displayName = "动态色(系统壁纸)",
        seedColor = Color(0xFF4D5358), // 仅作低版本 / 取色失败兜底
        supportsDynamic = true,
    )

    val all: List<ThemeSpec> = listOf(Default, Dynamic)

    fun findById(id: String?): ThemeSpec {
        if (id.isNullOrBlank()) return Default
        return all.firstOrNull { it.id == id } ?: Default
    }
}

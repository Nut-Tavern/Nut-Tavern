package com.nuttavern.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 让 Composable 拿到 [ThemeRepository] 而不引入额外 ViewModel。
 * Theme 必须在 Activity 装饰阶段就消费偏好,引 ViewModel 反而过重。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ThemeRepositoryEntryPoint {
    fun themeRepository(): ThemeRepository
}

@Composable
fun NutTavernTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) {
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                ThemeRepositoryEntryPoint::class.java,
            ).themeRepository()
        }.getOrNull()
    }

    // 取不到 repository(例如 Compose Preview 没有 Hilt 容器)时,退化到默认主题 + 跟随系统。
    val themeIdState = repository?.themeId?.collectAsState(initial = null)
        ?: remember { MutableStateFlow<String?>(null) }.collectAsState()
    val themeModeState = repository?.themeMode?.collectAsState(initial = ThemeMode.System)
        ?: remember { MutableStateFlow(ThemeMode.System) }.collectAsState()

    val themeSpec = ThemePresets.findById(themeIdState.value)
    val isDark = when (themeModeState.value) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val colorScheme = resolveColorScheme(themeSpec, isDark)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NutTavernTypography,
        content = content,
    )
}

@Composable
private fun resolveColorScheme(themeSpec: ThemeSpec, isDark: Boolean): ColorScheme {
    val context = LocalContext.current

    // Android 12+ 才有 dynamicLight/DarkColorScheme,低版本动态主题降级到种子色。
    if (themeSpec.supportsDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return if (isDark) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }
    }

    return if (isDark) {
        ColorSchemeFactory.darkFromSeed(themeSpec.seedColor)
    } else {
        ColorSchemeFactory.lightFromSeed(themeSpec.seedColor)
    }
}

/**
 * 给设置页 / 测试用的内部辅助:在不读取 DataStore 的前提下得到一个 Theme 的 ColorScheme。
 * 当前阶段未用,保留接口供后续主题预览卡片等场景复用,避免再写一份。
 */
@Suppress("unused")
internal fun previewColorScheme(themeSpec: ThemeSpec, isDark: Boolean): ColorScheme {
    if (themeSpec.supportsDynamic) {
        // 动态主题预览只能用降级种子色,真实壁纸取色需要 Activity 上下文,预览场景不适用。
        return if (isDark) {
            ColorSchemeFactory.darkFromSeed(themeSpec.seedColor)
        } else {
            ColorSchemeFactory.lightFromSeed(themeSpec.seedColor)
        }
    }
    return if (isDark) {
        ColorSchemeFactory.darkFromSeed(themeSpec.seedColor)
    } else {
        ColorSchemeFactory.lightFromSeed(themeSpec.seedColor)
    }
}

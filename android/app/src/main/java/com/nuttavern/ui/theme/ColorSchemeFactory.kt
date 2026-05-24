package com.nuttavern.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeTonalSpot

/**
 * 把单个种子色展开成 Material 3 完整 ColorScheme。
 *
 * 使用 Google Material Color Utilities 官方实现(`material-color-utilities`)。
 * 当前固定走 [SchemeTonalSpot](M3 默认风格,饱和度适中、对比度自然)。后续若要支持
 * "鲜艳 / 内容忠诚 / 低饱和"等变体,在这里加分支即可,业务层不感知。
 *
 * `contrastLevel` 取 0.0(标准对比度)。预留参数以便未来接入"高对比度模式"。
 */
internal object ColorSchemeFactory {

    fun lightFromSeed(seed: Color): ColorScheme {
        return buildScheme(seed, isDark = false)
    }

    fun darkFromSeed(seed: Color): ColorScheme {
        return buildScheme(seed, isDark = true)
    }

    private fun buildScheme(seed: Color, isDark: Boolean): ColorScheme {
        val sourceColorHct = Hct.fromInt(seed.toArgb())
        val dynamicScheme = SchemeTonalSpot(sourceColorHct, isDark, 0.0)
        return dynamicScheme.toComposeColorScheme(isDark = isDark)
    }
}

private fun Color.toArgb(): Int {
    val a = (alpha * 255f + 0.5f).toInt() and 0xFF
    val r = (red * 255f + 0.5f).toInt() and 0xFF
    val g = (green * 255f + 0.5f).toInt() and 0xFF
    val b = (blue * 255f + 0.5f).toInt() and 0xFF
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * 把 utilities 库的 [DynamicScheme] 转成 Compose [ColorScheme]。
 *
 * 通过 [MaterialDynamicColors] 取每个语义角色的颜色,确保 light / dark 两套都覆盖到 M3
 * 标准的全部 token(包括 surfaceContainerLowest…surfaceContainerHighest)。
 */
private fun DynamicScheme.toComposeColorScheme(isDark: Boolean): ColorScheme {
    val tokens = MaterialDynamicColors()
    fun argb(extractor: MaterialDynamicColors.() -> com.google.android.material.color.utilities.DynamicColor): Color {
        return Color(tokens.extractor().getArgb(this))
    }

    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = argb { primary() },
        onPrimary = argb { onPrimary() },
        primaryContainer = argb { primaryContainer() },
        onPrimaryContainer = argb { onPrimaryContainer() },
        inversePrimary = argb { inversePrimary() },
        secondary = argb { secondary() },
        onSecondary = argb { onSecondary() },
        secondaryContainer = argb { secondaryContainer() },
        onSecondaryContainer = argb { onSecondaryContainer() },
        tertiary = argb { tertiary() },
        onTertiary = argb { onTertiary() },
        tertiaryContainer = argb { tertiaryContainer() },
        onTertiaryContainer = argb { onTertiaryContainer() },
        background = argb { background() },
        onBackground = argb { onBackground() },
        surface = argb { surface() },
        onSurface = argb { onSurface() },
        surfaceVariant = argb { surfaceVariant() },
        onSurfaceVariant = argb { onSurfaceVariant() },
        surfaceTint = argb { surfaceTint() },
        inverseSurface = argb { inverseSurface() },
        inverseOnSurface = argb { inverseOnSurface() },
        error = argb { error() },
        onError = argb { onError() },
        errorContainer = argb { errorContainer() },
        onErrorContainer = argb { onErrorContainer() },
        outline = argb { outline() },
        outlineVariant = argb { outlineVariant() },
        scrim = argb { scrim() },
        surfaceBright = argb { surfaceBright() },
        surfaceDim = argb { surfaceDim() },
        surfaceContainer = argb { surfaceContainer() },
        surfaceContainerHigh = argb { surfaceContainerHigh() },
        surfaceContainerHighest = argb { surfaceContainerHighest() },
        surfaceContainerLow = argb { surfaceContainerLow() },
        surfaceContainerLowest = argb { surfaceContainerLowest() },
    )
}

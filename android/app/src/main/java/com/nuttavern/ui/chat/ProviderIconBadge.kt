package com.nuttavern.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.css
import com.nuttavern.data.model.Provider

/**
 * Provider / 模型图标。
 *
 * 设计取舍:
 * - **不带底色**。早期设计是圆形 surfaceContainerHigh 圆底,但与项目其他位置的图标
 *   呈现不一致(模型卡片要求"无图标底色"),全局统一成"纯图标"。
 * - **默认 32dp**,内部 svg `fillMaxSize()` 跟随。调用方需要其他尺寸时通过
 *   `Modifier.size(...)` 覆盖即可。这条默认值给未显式传 size 的旧调用点兜底,避免
 *   `ProviderIconBadge(provider = ...)` 在 Row 里因为 fillMaxSize 而把容器撑爆。
 * - 没有 svg 资源时降级显示首字母。
 */
@Composable
fun ProviderIconBadge(
    provider: Provider?,
    modifier: Modifier = Modifier.size(32.dp),
    modelName: String = "",
) {
    val iconAsset = providerIconAssetName(provider, modelName)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (iconAsset != null) {
            ProviderSvgIcon(iconAsset)
        } else {
            Text(
                text = providerBadgeLabel(provider, modelName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProviderSvgIcon(iconAsset: ProviderIconAsset) {
    val context = LocalContext.current
    val contentColor = LocalContentColor.current
    val tintCss = remember(contentColor) { contentColor.toCssHex() }
    val imageRequest = remember(context, iconAsset, tintCss) {
        val builder = ImageRequest.Builder(context)
            .data("file:///android_asset/icons/${iconAsset.fileName}")

        if (iconAsset.tintWithContentColor) {
            builder.css(
                """
                svg, path, circle, rect, polygon {
                  fill: $tintCss;
                }
                line, polyline {
                  stroke: $tintCss;
                }
                """.trimIndent(),
            )
        }

        builder.build()
    }

    AsyncImage(
        model = imageRequest,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
    )
}

fun providerIconAssetName(
    provider: Provider?,
    modelName: String = "",
): ProviderIconAsset? {
    if (provider == null && modelName.isBlank()) return null

    // 有具体模型名时,优先按模型名匹配品牌图标(如 "gpt-4o" → OpenAI 图标)。
    // 这样即使 Provider 设了自定义图标,模型列表里每个模型仍能显示自己的品牌图标。
    if (modelName.isNotBlank()) {
        val brand = matchProviderBrand(provider, modelName)
        if (brand != null) return brand.icon
    }

    // 用户在详情页手动指定了图标,在"显示 Provider 本身"或"模型名无法匹配品牌"时生效。
    if (provider != null && provider.iconKey.isNotBlank()) {
        ProviderIconCatalog.find(provider.iconKey)?.let { return it.icon }
    }

    // 模型名非空但上面没匹配到品牌,走协议兜底。
    if (modelName.isNotBlank()) {
        return protocolFallbackIcon(provider)
    }

    // 没有模型名(显示 Provider 本身),走品牌关键字 → 协议兜底。
    val brand = matchProviderBrand(provider, modelName)
    return brand?.icon ?: protocolFallbackIcon(provider)
}

fun providerBadgeLabel(
    provider: Provider?,
    modelName: String = "",
): String {
    if (provider == null && modelName.isBlank()) return "?"

    val brand = matchProviderBrand(provider, modelName)
    if (brand?.label != null) return brand.label

    return when (provider) {
        is Provider.Google -> "G"
        is Provider.Claude -> "C"
        is Provider.OpenAI -> "AI"
        null -> firstUppercaseLetter(provider, modelName)
    }
}

data class ProviderIconAsset(
    val fileName: String,
    val tintWithContentColor: Boolean = false,
)

/**
 * 把图标和缩写绑在同一个表里,避免两份关键字映射各自漂移。`label = null` 表示
 * 该品牌没有特定缩写,会走协议兜底或首字母兜底。
 */
private data class ProviderBrand(
    val keywords: List<String>,
    val icon: ProviderIconAsset,
    val label: String?,
)

private val providerBrands: List<ProviderBrand> = listOf(
    ProviderBrand(
        keywords = listOf("deepseek"),
        icon = ProviderIconAsset("deepseek-color.svg"),
        label = "D",
    ),
    ProviderBrand(
        keywords = listOf("openrouter"),
        icon = ProviderIconAsset("openrouter.svg", tintWithContentColor = true),
        label = null,
    ),
    ProviderBrand(
        keywords = listOf("openai", "chatgpt", "gpt-"),
        icon = ProviderIconAsset("openai.svg", tintWithContentColor = true),
        label = "AI",
    ),
    ProviderBrand(
        keywords = listOf("gemini"),
        icon = ProviderIconAsset("gemini-color.svg"),
        label = "G",
    ),
    ProviderBrand(
        keywords = listOf("google"),
        icon = ProviderIconAsset("google-color.svg"),
        label = "G",
    ),
    ProviderBrand(
        keywords = listOf("claude"),
        icon = ProviderIconAsset("claude-color.svg"),
        label = "C",
    ),
    ProviderBrand(
        keywords = listOf("anthropic"),
        icon = ProviderIconAsset("anthropic.svg", tintWithContentColor = true),
        label = "C",
    ),
    ProviderBrand(
        keywords = listOf("ollama"),
        icon = ProviderIconAsset("ollama.svg", tintWithContentColor = true),
        label = null,
    ),
    ProviderBrand(
        keywords = listOf("qwen", "通义", "tongyi"),
        icon = ProviderIconAsset("qwen-color.svg"),
        label = "Q",
    ),
    ProviderBrand(
        keywords = listOf("kimi"),
        icon = ProviderIconAsset("kimi-color.svg"),
        label = "K",
    ),
    ProviderBrand(
        keywords = listOf("moonshot"),
        icon = ProviderIconAsset("moonshot.svg", tintWithContentColor = true),
        label = "K",
    ),
    ProviderBrand(
        keywords = listOf("grok"),
        icon = ProviderIconAsset("grok.svg", tintWithContentColor = true),
        label = null,
    ),
    ProviderBrand(
        keywords = listOf("xai", "x.ai"),
        icon = ProviderIconAsset("xai.svg", tintWithContentColor = true),
        label = null,
    ),
    ProviderBrand(
        keywords = listOf("mistral"),
        icon = ProviderIconAsset("mistral-color.svg"),
        label = null,
    ),
    ProviderBrand(
        keywords = listOf("perplexity"),
        icon = ProviderIconAsset("perplexity-color.svg"),
        label = null,
    ),
    ProviderBrand(
        keywords = listOf("siliconcloud", "siliconflow", "硅基"),
        icon = ProviderIconAsset("siliconcloud-color.svg"),
        label = null,
    ),
    ProviderBrand(
        keywords = listOf("doubao", "豆包"),
        icon = ProviderIconAsset("doubao-color.svg"),
        label = null,
    ),
    ProviderBrand(
        keywords = listOf("hunyuan", "混元"),
        icon = ProviderIconAsset("hunyuan-color.svg"),
        label = null,
    ),
    ProviderBrand(
        keywords = listOf("zhipu", "智谱", "glm"),
        icon = ProviderIconAsset("zhipu-color.svg"),
        label = null,
    ),
    ProviderBrand(
        keywords = listOf("minimax"),
        icon = ProviderIconAsset("minimax-color.svg"),
        label = null,
    ),
)

private fun matchProviderBrand(provider: Provider?, modelName: String): ProviderBrand? {
    val normalizedText = "${provider?.name.orEmpty()} $modelName".lowercase()
    if (normalizedText.isBlank()) return null
    return providerBrands.firstOrNull { brand ->
        brand.keywords.any(normalizedText::contains)
    }
}

/**
 * 给"提供商手动选图标"功能用的图标目录。每条 [Entry] 给:
 * - [key] 写到 [Provider.iconKey] 的稳定字符串(英文小写,无空格);
 * - [displayName] 选择器面板里显示的中文名;
 * - [icon] 真实 svg 资源。
 *
 * 设计取舍:不复用 [providerBrands] 的 keywords / label 字段。那张表是"自动推断"用的,
 * 关心如何从 Provider 名 / 模型名匹配出图标;这张表是"用户主动选"用的,只关心稳定 key 和
 * 展示名。两者数据形态不同,合并反而互相约束。
 */
object ProviderIconCatalog {
    data class Entry(
        val key: String,
        val displayName: String,
        val icon: ProviderIconAsset,
    )

    val entries: List<Entry> = listOf(
        Entry("openai", "OpenAI", ProviderIconAsset("openai.svg", tintWithContentColor = true)),
        Entry("anthropic", "Anthropic", ProviderIconAsset("anthropic.svg", tintWithContentColor = true)),
        Entry("claude", "Claude", ProviderIconAsset("claude-color.svg")),
        Entry("gemini", "Gemini", ProviderIconAsset("gemini-color.svg")),
        Entry("google", "Google", ProviderIconAsset("google-color.svg")),
        Entry("deepseek", "DeepSeek", ProviderIconAsset("deepseek-color.svg")),
        Entry("qwen", "通义千问", ProviderIconAsset("qwen-color.svg")),
        Entry("kimi", "Kimi", ProviderIconAsset("kimi-color.svg")),
        Entry("moonshot", "Moonshot", ProviderIconAsset("moonshot.svg", tintWithContentColor = true)),
        Entry("grok", "Grok", ProviderIconAsset("grok.svg", tintWithContentColor = true)),
        Entry("xai", "xAI", ProviderIconAsset("xai.svg", tintWithContentColor = true)),
        Entry("mistral", "Mistral", ProviderIconAsset("mistral-color.svg")),
        Entry("perplexity", "Perplexity", ProviderIconAsset("perplexity-color.svg")),
        Entry("siliconcloud", "硅基流动", ProviderIconAsset("siliconcloud-color.svg")),
        Entry("doubao", "豆包", ProviderIconAsset("doubao-color.svg")),
        Entry("hunyuan", "混元", ProviderIconAsset("hunyuan-color.svg")),
        Entry("zhipu", "智谱 / GLM", ProviderIconAsset("zhipu-color.svg")),
        Entry("minimax", "MiniMax", ProviderIconAsset("minimax-color.svg")),
        Entry("openrouter", "OpenRouter", ProviderIconAsset("openrouter.svg", tintWithContentColor = true)),
        Entry("ollama", "Ollama", ProviderIconAsset("ollama.svg", tintWithContentColor = true)),
    )

    fun find(key: String): Entry? = entries.firstOrNull { it.key == key }
}

private fun protocolFallbackIcon(provider: Provider?): ProviderIconAsset? {
    return when (provider) {
        is Provider.OpenAI -> ProviderIconAsset("openai.svg", tintWithContentColor = true)
        is Provider.Google -> ProviderIconAsset("gemini-color.svg")
        is Provider.Claude -> ProviderIconAsset("claude-color.svg")
        null -> null
    }
}

private fun firstUppercaseLetter(provider: Provider?, modelName: String): String {
    val providerName = provider?.name.orEmpty()
    if (providerName.isNotBlank()) return providerName.first().uppercase()
    if (modelName.isNotBlank()) return modelName.first().uppercase()
    return "?"
}

private fun Color.toCssHex(): String {
    val colorInt = toArgb()
    val red = (colorInt shr 16) and 0xFF
    val green = (colorInt shr 8) and 0xFF
    val blue = colorInt and 0xFF
    return "#%02X%02X%02X".format(red, green, blue)
}

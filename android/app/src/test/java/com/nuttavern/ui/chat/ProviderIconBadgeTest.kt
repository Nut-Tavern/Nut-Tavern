package com.nuttavern.ui.chat

import com.nuttavern.data.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderIconBadgeTest {

    @Test
    fun providerIconAssetName_mapsKnownProviderNames() {
        assertEquals("openai.svg", providerIconAssetName(openAi("OpenAI"))?.fileName)
        assertEquals("gemini-color.svg", providerIconAssetName(google("Google Gemini"))?.fileName)
        assertEquals("claude-color.svg", providerIconAssetName(claude("Anthropic Claude"))?.fileName)
        assertEquals("deepseek-color.svg", providerIconAssetName(openAi("DeepSeek"))?.fileName)
    }

    @Test
    fun providerIconAssetName_prefersModelNameBeforeProtocolFallback() {
        val claudeCompatibleProvider = claude("Custom Claude Proxy")

        assertEquals(
            "deepseek-color.svg",
            providerIconAssetName(claudeCompatibleProvider, modelName = "deepseek-chat")?.fileName,
        )
    }

    @Test
    fun providerIconAssetName_fallsBackToProtocolIconWhenNameIsUnknown() {
        assertEquals("openai.svg", providerIconAssetName(openAi("Nut API"))?.fileName)
        assertEquals("gemini-color.svg", providerIconAssetName(google("Nut Generic"))?.fileName)
        assertEquals("claude-color.svg", providerIconAssetName(claude("Nut Generic"))?.fileName)
    }

    @Test
    fun providerIconAssetName_handlesMissingProvider() {
        assertNull(providerIconAssetName(null))
        assertEquals("deepseek-color.svg", providerIconAssetName(null, modelName = "deepseek-reasoner")?.fileName)
    }

    @Test
    fun providerBadgeLabel_keepsReadableFallback() {
        assertEquals("D", providerBadgeLabel(openAi("DeepSeek")))
        assertEquals("AI", providerBadgeLabel(openAi("OpenAI")))
        assertEquals("?", providerBadgeLabel(null))
    }

    private fun openAi(name: String): Provider = Provider.OpenAI(
        id = name.lowercase().replace(" ", "-"),
        name = name,
    )

    private fun google(name: String): Provider = Provider.Google(
        id = name.lowercase().replace(" ", "-"),
        name = name,
    )

    private fun claude(name: String): Provider = Provider.Claude(
        id = name.lowercase().replace(" ", "-"),
        name = name,
    )
}

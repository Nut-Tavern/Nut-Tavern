package com.nuttavern.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatApiClientUrlTest {
    private val client = ChatApiClient()

    @Test
    fun openAiChatUrlIgnoresEndpointAlreadyPresentInBaseUrl() {
        val url = buildVersionedEndpointUrl(
            baseUrl = "https://api.openai.com/v1/chat/completions",
            apiVersion = "v1",
            endpointPath = "/v1/chat/completions",
        )

        assertEquals("https://api.openai.com/v1/chat/completions", url)
    }

    @Test
    fun openAiModelsUrlUsesVersionRootWhenBaseUrlContainsChatEndpoint() {
        val url = buildVersionedEndpointUrl(
            baseUrl = "https://api.openai.com/v1/chat/completions",
            apiVersion = "v1",
            endpointPath = "models",
        )

        assertEquals("https://api.openai.com/v1/models", url)
    }

    @Test
    fun geminiStreamUrlIgnoresModelsEndpointAlreadyPresentInBaseUrl() {
        val url = buildGeminiStreamUrl(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/models",
            model = "models/gemini-2.0-flash",
        )

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:streamGenerateContent?alt=sse",
            url,
        )
    }

    private fun buildVersionedEndpointUrl(
        baseUrl: String,
        apiVersion: String,
        endpointPath: String,
    ): String {
        val method = ChatApiClient::class.java.getDeclaredMethod(
            "buildVersionedEndpointUrl",
            String::class.java,
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(client, baseUrl, apiVersion, endpointPath) as String
    }

    private fun buildGeminiStreamUrl(
        baseUrl: String,
        model: String,
    ): String {
        val method = ChatApiClient::class.java.getDeclaredMethod(
            "buildGeminiStreamUrl",
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(client, baseUrl, model) as String
    }
}

package com.shenyu.chat.net

import com.shenyu.chat.data.ProviderConfig
import com.shenyu.chat.data.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderEndpointsTest {

    private fun provider(baseUrl: String, protocol: ProviderProtocol) = ProviderConfig(
        id = "test",
        name = "测试",
        baseUrl = baseUrl,
        protocol = protocol,
    )

    @Test
    fun deepSeekOfficialKeepsAnthropicPath() {
        val p = provider("https://api.deepseek.com", ProviderProtocol.DEEPSEEK)
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            ProviderEndpoints.messagesUrl(p),
        )
        assertEquals("https://api.deepseek.com/v1/models", ProviderEndpoints.modelsUrl(p))
        assertEquals("https://api.deepseek.com/anthropic/v1/files", ProviderEndpoints.filesUrl(p))
        assertEquals("https://api.deepseek.com", ProviderEndpoints.serviceOrigin(p))
    }

    @Test
    fun fullMessageUrlIsUsedAsIs() {
        val p = provider("https://api.deepseek.com/anthropic/v1/messages", ProviderProtocol.DEEPSEEK)
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            ProviderEndpoints.messagesUrl(p),
        )
        assertEquals("https://api.deepseek.com/v1/models", ProviderEndpoints.modelsUrl(p))
    }

    @Test
    fun anthropicProtocolUsesV1Messages() {
        val p = provider("https://api.anthropic.com", ProviderProtocol.ANTHROPIC)
        assertEquals("https://api.anthropic.com/v1/messages", ProviderEndpoints.messagesUrl(p))
        assertEquals("https://api.anthropic.com/v1/models", ProviderEndpoints.modelsUrl(p))
    }

    @Test
    fun anthropicProtocolAcceptsV1Base() {
        val p = provider("https://gateway.example.com/api/v1", ProviderProtocol.ANTHROPIC)
        assertEquals("https://gateway.example.com/api/v1/messages", ProviderEndpoints.messagesUrl(p))
        assertEquals("https://gateway.example.com/api/v1/models", ProviderEndpoints.modelsUrl(p))
    }

    @Test
    fun openAiProtocolUsesChatCompletions() {
        val p = provider("https://api.openai.com/v1", ProviderProtocol.OPENAI)
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ProviderEndpoints.messagesUrl(p),
        )
        assertEquals("https://api.openai.com/v1/models", ProviderEndpoints.modelsUrl(p))
    }

    @Test
    fun openAiProtocolAcceptsFullEndpoint() {
        val p = provider("https://relay.example.com/v1/chat/completions", ProviderProtocol.OPENAI)
        assertEquals(
            "https://relay.example.com/v1/chat/completions",
            ProviderEndpoints.messagesUrl(p),
        )
        assertEquals("https://relay.example.com/v1/models", ProviderEndpoints.modelsUrl(p))
    }

    @Test
    fun missingSchemeGetsHttps() {
        val p = provider("api.example.com/v1", ProviderProtocol.ANTHROPIC)
        assertEquals("https://api.example.com/v1/messages", ProviderEndpoints.messagesUrl(p))
        assertNotNull(ProviderEndpoints.shortLabel(p))
    }

    @Test
    fun validationRejectsMissingHostAndQuery() {
        assertNotNull(ProviderEndpoints.validationError(""))
        assertNotNull(ProviderEndpoints.validationError("https://"))
        assertNotNull(ProviderEndpoints.validationError("https://api.example.com/v1?key=1"))
        assertNull(ProviderEndpoints.validationError("api.example.com/v1"))
        assertNull(ProviderEndpoints.validationError("http://127.0.0.1:8080/anthropic/v1/messages"))
    }

    @Test
    fun deepSeekRelayV1BaseUsesV1MessagesAndTools() {
        val p = provider("https://api.jiulingssh.xyz/v1", ProviderProtocol.DEEPSEEK)
        assertEquals("https://api.jiulingssh.xyz/v1/messages", ProviderEndpoints.messagesUrl(p))
        assertEquals("https://api.jiulingssh.xyz/v1/models", ProviderEndpoints.modelsUrl(p))
        assertEquals("https://api.jiulingssh.xyz/v1/files", ProviderEndpoints.filesUrl(p))
        assertEquals("https://api.jiulingssh.xyz", ProviderEndpoints.serviceOrigin(p))
    }

    @Test
    fun serviceOriginFoldsFullMessageUrl() {
        val p = provider("https://mirror.example.com/deepseek/anthropic/v1/messages", ProviderProtocol.DEEPSEEK)
        assertEquals("https://mirror.example.com/deepseek", ProviderEndpoints.serviceOrigin(p))
    }
}

package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's extra provider headers (#648): who wins, what is dropped, `{session}`. */
class AiClientHeadersTest {

    private fun config(vararg headers: Pair<String, String>, session: String = "s-1") =
        AiClient.Config(
            AiProvider.OPENAI_COMPATIBLE, "k", "m", "https://x/v1",
            headers = headers.map { McpHeader(it.first, it.second) },
            sessionId = session,
        )

    @Test
    fun `no extra headers leaves the provider's own untouched`() {
        val base = mapOf("Authorization" to "Bearer k")
        assertEquals(base, AiClient.requestHeaders(config(), base))
    }

    @Test
    fun `a user header replaces a built-in one of the same name in any case`() {
        val sent = AiClient.requestHeaders(
            config("authorization" to "Bearer gateway", "User-Agent" to "opencode/1"),
            mapOf("Authorization" to "Bearer k"),
        )
        assertEquals(mapOf("authorization" to "Bearer gateway", "User-Agent" to "opencode/1"), sent)
    }

    @Test
    fun `session placeholder becomes the config's session id`() {
        val sent = AiClient.requestHeaders(config("x-opencode-session" to "{session}", session = "abc"), emptyMap())
        assertEquals("abc", sent["x-opencode-session"])
    }

    @Test
    fun `invalid names and values with line breaks are dropped`() {
        val sent = AiClient.requestHeaders(
            config("bad name" to "v", "X-Ok" to "fine", "X-Split" to "a\r\nInjected: 1"),
            emptyMap(),
        )
        assertEquals(mapOf("X-Ok" to "fine"), sent)
    }

    @Test
    fun `config picks the headers of the chosen provider only`() {
        val settings = AiSettings(
            provider = AiProvider.OPENAI,
            openAiKey = "k",
            providerHeaders = mapOf(
                AiProvider.OPENAI.name to listOf(McpHeader("X-A", "1")),
                AiProvider.ANTHROPIC.name to listOf(McpHeader("X-B", "2")),
            ),
        )
        assertEquals(listOf(McpHeader("X-A", "1")), AiClient.config(settings).headers)
        assertTrue(AiClient.config(settings.copy(provider = AiProvider.GEMINI, geminiKey = "g")).headers.isEmpty())
    }

    @Test
    fun `each config gets its own session id`() {
        val settings = AiSettings(provider = AiProvider.OPENAI, openAiKey = "k")
        assertFalse(AiClient.config(settings).sessionId == AiClient.config(settings).sessionId)
    }
}

package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.tools.AiClient.ChatRole
import com.wasimaster.wmkeyboard.core.tools.AiClient.ChatTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The chat screen sends whole conversations; each provider spells the message
 * array differently. These pin the request bodies, and that the single-turn
 * wrapper still produces exactly what the keyboard's AI actions always sent.
 */
class AiClientChatBodyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun config(provider: AiProvider) = AiClient.Config(provider, "k", "test-model", "")

    private val chat = listOf(
        ChatTurn(ChatRole.USER, "What is a monad?"),
        ChatTurn(ChatRole.ASSISTANT, "A monoid in the category of endofunctors."),
        ChatTurn(ChatRole.USER, "In plain words?"),
    )

    @Test
    fun `anthropic sends user and assistant roles with system on top`() {
        val body = json.parseToJsonElement(
            AiClient.anthropicBody(config(AiProvider.ANTHROPIC), "sys", chat, 1000),
        ).jsonObject
        assertEquals("sys", body["system"]?.jsonPrimitive?.content)
        val roles = body["messages"]!!.jsonArray.map {
            it.jsonObject["role"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("user", "assistant", "user"), roles)
    }

    @Test
    fun `openai puts the system message first in the array`() {
        val body = json.parseToJsonElement(
            AiClient.openAiCompatibleBody(config(AiProvider.OPENAI), "sys", chat, null),
        ).jsonObject
        val messages = body["messages"]!!.jsonArray
        val roles = messages.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("system", "user", "assistant", "user"), roles)
        assertEquals("sys", messages.first().jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `gemini calls the assistant role model`() {
        val body = json.parseToJsonElement(
            AiClient.geminiBody("sys", chat, null),
        ).jsonObject
        val roles = body["contents"]!!.jsonArray.map {
            it.jsonObject["role"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("user", "model", "user"), roles)
    }

    @Test
    fun `ollama keeps the system message in the array`() {
        val body = json.parseToJsonElement(
            AiClient.ollamaBody(config(AiProvider.OLLAMA), "sys", chat, null),
        ).jsonObject
        val roles = body["messages"]!!.jsonArray.map {
            it.jsonObject["role"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("system", "user", "assistant", "user"), roles)
    }

    @Test
    fun `a single-turn conversation builds the same bodies as before`() {
        val single = listOf(ChatTurn(ChatRole.USER, "hello"))
        val anthropic = json.parseToJsonElement(
            AiClient.anthropicBody(config(AiProvider.ANTHROPIC), "sys", single, 500),
        ).jsonObject
        assertEquals(1, anthropic["messages"]!!.jsonArray.size)
        assertEquals("sys", anthropic["system"]?.jsonPrimitive?.content)
        assertEquals(500, anthropic["max_tokens"]?.jsonPrimitive?.content?.toInt())

        val openAi = json.parseToJsonElement(
            AiClient.openAiCompatibleBody(config(AiProvider.OPENAI), "sys", single, null),
        ).jsonObject
        assertEquals(2, openAi["messages"]!!.jsonArray.size)
        assertNull(openAi["max_tokens"])

        val gemini = json.parseToJsonElement(
            AiClient.geminiBody("sys", single, null),
        ).jsonObject
        assertEquals(1, gemini["contents"]!!.jsonArray.size)
        assertNull(gemini["generationConfig"])
    }

    @Test
    fun `normalization merges adjacent same-role turns after a dropped failure`() {
        val turns = listOf(
            ChatTurn(ChatRole.USER, "first question"),
            // The failed assistant turn was dropped by the caller, leaving
            // two user turns in a row.
            ChatTurn(ChatRole.USER, "second question"),
        )
        val normalized = AiClient.normalizedTurns(turns)
        assertEquals(1, normalized.size)
        assertEquals("first question\n\nsecond question", normalized.single().text)
    }

    @Test
    fun `normalization drops a leading assistant turn and a trailing one`() {
        val turns = listOf(
            ChatTurn(ChatRole.ASSISTANT, "greeting from a trimmed history"),
            ChatTurn(ChatRole.USER, "question"),
            ChatTurn(ChatRole.ASSISTANT, "answer"),
        )
        val normalized = AiClient.normalizedTurns(turns)
        assertEquals(listOf(ChatRole.USER), normalized.map { it.role })
    }

    @Test
    fun `normalization skips blank messages`() {
        val turns = listOf(
            ChatTurn(ChatRole.USER, "question"),
            ChatTurn(ChatRole.ASSISTANT, "   "),
            ChatTurn(ChatRole.USER, "again"),
        )
        val normalized = AiClient.normalizedTurns(turns)
        assertEquals(1, normalized.size)
        assertEquals("question\n\nagain", normalized.single().text)
    }

    @Test
    fun `the reasoning switch reaches each provider's own field`() {
        fun parse(text: String) = json.parseToJsonElement(text).jsonObject
        val claudeOn = parse(AiClient.anthropicBody(config(AiProvider.ANTHROPIC).copy(reasoning = true), "s", chat, 1000))
        assertEquals("adaptive", claudeOn["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val claudeOff = parse(AiClient.anthropicBody(config(AiProvider.ANTHROPIC).copy(reasoning = false), "s", chat, 1000))
        assertEquals("disabled", claudeOff["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(parse(AiClient.anthropicBody(config(AiProvider.ANTHROPIC), "s", chat, 1000))["thinking"])
        val oldClaude = AiClient.Config(AiProvider.ANTHROPIC, "k", "claude-3-5-haiku-latest", "", reasoning = true)
        assertNull(parse(AiClient.anthropicBody(oldClaude, "s", chat, 1000))["thinking"])

        val flashOff = parse(AiClient.geminiBody("s", chat, null, reasoning = false, model = "gemini-2.5-flash"))
        assertEquals(
            0,
            flashOff["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content.toInt(),
        )
        val threeOff = parse(AiClient.geminiBody("s", chat, null, reasoning = false, model = "gemini-3.5-flash"))
        assertEquals(
            "minimal",
            threeOff["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content,
        )
        assertNull(parse(AiClient.geminiBody("s", chat, null))["generationConfig"])

        val ollamaOff = parse(AiClient.ollamaBody(config(AiProvider.OLLAMA).copy(reasoning = false), "s", chat, null))
        assertEquals("false", ollamaOff["think"]!!.jsonPrimitive.content)
        assertNull(parse(AiClient.ollamaBody(config(AiProvider.OLLAMA).copy(reasoning = true), "s", chat, null))["think"])

        val gptOff = AiClient.Config(AiProvider.OPENAI, "k", "gpt-5.6-luna", "", reasoning = false)
        assertEquals("low", parse(AiClient.openAiCompatibleBody(gptOff, "s", chat, null))["reasoning_effort"]!!.jsonPrimitive.content)
        val plainOff = config(AiProvider.OPENAI).copy(reasoning = false)
        assertNull(parse(AiClient.openAiCompatibleBody(plainOff, "s", chat, null))["reasoning_effort"])
    }
}

package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.AiProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The assistant turn of a tool exchange goes back the way the provider wrote
 * it: Gemini 3 refuses a functionCall without its thoughtSignature, and
 * DeepSeek's thinking mode a tool round without its reasoning_content.
 */
class AiToolReplayTest {

    private val user = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "weather"))
    private val tools = listOf(AiTools.webSearch)

    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject

    /** The conversation a tool loop sends next, after [completion]'s round. */
    private fun next(completion: AiClient.Completion): List<AiClient.ChatTurn> {
        val call = completion.toolCalls.single()
        return user + listOf(
            AiClient.ChatTurn(
                AiClient.ChatRole.ASSISTANT, completion.text,
                toolCalls = completion.toolCalls, replay = completion.replay,
            ),
            AiClient.ChatTurn(AiClient.ChatRole.TOOL, "sunny", toolResult = AiToolResult(call, "sunny")),
        )
    }

    @Test
    fun `gemini plays the thought signature back on its functionCall part`() {
        val buffer = AiClient.StreamBuffer()
        AiClient.applyGeminiEvent(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"id":"f1","name":"web_search",""" +
                """"args":{"query":"weather"}},"thoughtSignature":"SIG"}]}}]}""",
            buffer,
        )
        val contents = body(AiClient.geminiBody("s", next(buffer.finish()), 100, tools))["contents"]!!
            .jsonArray.map { it.jsonObject }
        val part = contents[1]["parts"]!!.jsonArray.single().jsonObject
        assertEquals("SIG", part["thoughtSignature"]?.jsonPrimitive?.content)
        val response = contents[2]["parts"]!!.jsonArray.single().jsonObject["functionResponse"]!!.jsonObject
        assertEquals("f1", response["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `gemini keeps a signature that arrives on a trailing empty text part`() {
        val buffer = AiClient.StreamBuffer()
        AiClient.applyGeminiEvent(
            """{"candidates":[{"content":{"parts":[{"text":"Let me "}]}}]}""", buffer,
        )
        AiClient.applyGeminiEvent(
            """{"candidates":[{"content":{"parts":[{"text":"check."},""" +
                """{"functionCall":{"name":"web_search","args":{}}}]}}]}""",
            buffer,
        )
        AiClient.applyGeminiEvent(
            """{"candidates":[{"content":{"parts":[{"text":"","thoughtSignature":"END"}]}}]}""", buffer,
        )
        val parts = buffer.finish().replay!![AiClient.REPLAY_GEMINI]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, parts.size)
        assertEquals("Let me check.", parts[0]["text"]?.jsonPrimitive?.content)
        assertEquals("END", parts[2]["thoughtSignature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a rebuilt gemini call carries the documented stand-in signature`() {
        val call = AiToolCall("", AiTools.WEB_SEARCH, "{}")
        val turns = user + AiClient.ChatTurn(AiClient.ChatRole.ASSISTANT, "", toolCalls = listOf(call))
        val part = body(AiClient.geminiBody("s", turns, 100, tools))["contents"]!!
            .jsonArray[1].jsonObject["parts"]!!.jsonArray.single().jsonObject
        assertEquals("skip_thought_signature_validator", part["thoughtSignature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `deepseek gets its reasoning_content back and no think tags in content`() {
        val buffer = AiClient.StreamBuffer()
        AiClient.applyOpenAiEvent("""{"choices":[{"delta":{"reasoning_content":"need data"}}]}""", buffer)
        AiClient.applyOpenAiEvent(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1",""" +
                """"function":{"name":"web_search","arguments":"{}"}}]}}]}""",
            buffer,
        )
        val config = AiClient.Config(AiProvider.DEEPSEEK, "k", "m", "")
        val assistant = body(AiClient.openAiCompatibleBody(config, "s", next(buffer.finish()), 100, tools))[
            "messages"
        ]!!.jsonArray[2].jsonObject
        assertEquals("need data", assistant["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals("", assistant["content"]?.jsonPrimitive?.content)
        assertEquals("c1", assistant["tool_calls"]!!.jsonArray.single().jsonObject["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `gemini behind an openai endpoint keeps its extra_content`() {
        val buffer = AiClient.StreamBuffer()
        AiClient.applyOpenAiEvent(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1",""" +
                """"function":{"name":"web_search","arguments":"{}"},""" +
                """"extra_content":{"google":{"thought_signature":"SIG"}}}]}}]}""",
            buffer,
        )
        val config = AiClient.Config(AiProvider.OPENAI_COMPATIBLE, "k", "m", "https://x.test")
        val call = body(AiClient.openAiCompatibleBody(config, "s", next(buffer.finish()), 100, tools))[
            "messages"
        ]!!.jsonArray[2].jsonObject["tool_calls"]!!.jsonArray.single().jsonObject
        val google = (call["extra_content"] as JsonObject)["google"]!!.jsonObject
        assertEquals("SIG", google["thought_signature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `plain openai sends no reasoning field it never received`() {
        val buffer = AiClient.StreamBuffer()
        AiClient.applyOpenAiEvent(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1",""" +
                """"function":{"name":"web_search","arguments":"{}"}}]}}]}""",
            buffer,
        )
        val config = AiClient.Config(AiProvider.OPENAI, "k", "m", "")
        val assistant = body(AiClient.openAiCompatibleBody(config, "s", next(buffer.finish()), 100, tools))[
            "messages"
        ]!!.jsonArray[2].jsonObject
        assertNull(assistant["reasoning_content"])
        assertNull(assistant["reasoning"])
        assertNull(assistant["tool_calls"]!!.jsonArray.single().jsonObject["extra_content"])
    }

    @Test
    fun `anthropic plays a thinking block back with its signature`() {
        val buffer = AiClient.StreamBuffer()
        listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIG"}}""",
            """{"type":"content_block_start","index":1,"content_block":""" +
                """{"type":"tool_use","id":"t1","name":"web_search","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":""" +
                """{"type":"input_json_delta","partial_json":"{\"query\":\"x\"}"}}""",
        ).forEach { AiClient.applyAnthropicEvent(it, buffer) }
        val config = AiClient.Config(AiProvider.ANTHROPIC, "k", "m", "")
        val content = body(AiClient.anthropicBody(config, "s", next(buffer.finish()), 100, tools))["messages"]!!
            .jsonArray[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("thinking", content[0]["type"]?.jsonPrimitive?.content)
        assertEquals("hmm", content[0]["thinking"]?.jsonPrimitive?.content)
        assertEquals("SIG", content[0]["signature"]?.jsonPrimitive?.content)
        assertEquals("t1", content[1]["id"]?.jsonPrimitive?.content)
        assertEquals("x", content[1]["input"]?.jsonObject?.get("query")?.jsonPrimitive?.content)
    }

    @Test
    fun `ollama gets its thinking in its own field`() {
        val buffer = AiClient.StreamBuffer()
        AiClient.applyOllamaEvent(
            """{"message":{"thinking":"plan","tool_calls":[{"function":{"name":"web_search","arguments":{}}}]}}""",
            buffer,
        )
        val config = AiClient.Config(AiProvider.OLLAMA, "", "m", "http://x.test")
        val assistant = body(AiClient.ollamaBody(config, "s", next(buffer.finish()), 100, tools))["messages"]!!
            .jsonArray[2].jsonObject
        assertEquals("plan", assistant["thinking"]?.jsonPrimitive?.content)
        assertEquals("", assistant["content"]?.jsonPrimitive?.content)
    }
}

package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** One thing Assist acted on: an area, a device, an entity. */
@Serializable
data class HaAssistTarget(val name: String, val type: String, val id: String? = null)

/** What Assist said back, and what it touched. */
data class HaAssistReply(
    /** `action_done`, `query_answer` or `error`. */
    val type: String,
    val speech: String,
    val success: List<HaAssistTarget> = emptyList(),
    val failed: List<HaAssistTarget> = emptyList(),
    val conversationId: String? = null,
    val continueConversation: Boolean = false,
    val errorCode: String? = null,
) {
    val isError: Boolean get() = type == "error"
    val successEntityIds: List<String> get() = success.filter { it.type == "entity" }.mapNotNull { it.id }
    val failedEntityIds: List<String> get() = failed.filter { it.type == "entity" }.mapNotNull { it.id }

    companion object {
        const val ACTION_DONE = "action_done"
        const val QUERY_ANSWER = "query_answer"
        const val ERROR = "error"
    }
}

object HaAssist {

    /** The body of `conversation/process`, REST and socket alike. */
    fun request(text: String, language: String?, agentId: String?, conversationId: String?): JsonObject = buildJsonObject {
        put("text", text)
        language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
        agentId?.takeIf { it.isNotBlank() }?.let { put("agent_id", it) }
        conversationId?.takeIf { it.isNotBlank() }?.let { put("conversation_id", it) }
    }

    /** The REST response, or the socket's `result`: both carry `response` and `conversation_id`. */
    fun parse(o: JsonObject): HaAssistReply {
        val response = o.obj("response") ?: o
        val speech = response.obj("speech")?.let { s -> s.obj("plain")?.str("speech") ?: s.obj("ssml")?.str("speech") }.orEmpty()
        val data = response.obj("data")
        return HaAssistReply(
            type = response.str("response_type") ?: HaAssistReply.QUERY_ANSWER,
            speech = speech.trim(),
            success = data?.arr("success")?.mapNotNull { target(it.jsonObject) }.orEmpty(),
            failed = data?.arr("failed")?.mapNotNull { target(it.jsonObject) }.orEmpty(),
            conversationId = o.str("conversation_id"),
            continueConversation = o.bool("continue_conversation") == true,
            errorCode = data?.str("code"),
        )
    }

    private fun target(o: JsonObject): HaAssistTarget? {
        val name = o.str("name") ?: return null
        return HaAssistTarget(name, o.str("type") ?: "entity", o.str("id"))
    }
}

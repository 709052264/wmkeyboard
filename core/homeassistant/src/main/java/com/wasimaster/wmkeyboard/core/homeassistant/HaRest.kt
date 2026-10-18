package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/**
 * The REST half: what works without a socket, and what the socket cannot do
 * (a camera picture). Blocking; call it off the main thread. [url] and [token]
 * are read per call so a token refresh or an address switch needs no new
 * object.
 */
class HaRest(
    private val http: HaHttp,
    private val url: () -> HaUrl?,
    private val token: () -> String?,
) {

    private fun base(): HaUrl = url() ?: throw IOException("no server address")

    private fun headers(): Map<String, String> {
        val t = token() ?: throw HaHttpException(HaHttpException.UNAUTHORIZED, "")
        return mapOf("Authorization" to "Bearer $t", "Accept" to "application/json")
    }

    private fun json(response: HaHttpResponse): JsonElement {
        if (!response.ok) throw HaHttpException(response.status, response.body)
        return HaJson.parseToJsonElement(response.body)
    }

    /** `GET /api/`: 200 means the token works, 401 means it does not. */
    fun ping(timeoutMs: Int = HaHttp.DEFAULT_TIMEOUT_MS): String {
        val r = http.get(base().api(""), headers(), timeoutMs)
        val o = json(r) as? JsonObject
        return o?.str("message") ?: "ok"
    }

    fun config(): HaInstance = HaInstance.parse(json(http.get(base().api("config"), headers())).jsonObject)

    fun states(): List<HaEntity> =
        (json(http.get(base().api("states"), headers())) as? JsonArray)?.mapNotNull { HaEntity.fromRest(it.jsonObject) }.orEmpty()

    fun state(entityId: String): HaEntity? =
        HaEntity.fromRest(json(http.get(base().api("states/$entityId"), headers())).jsonObject)

    /** Runs [call]; returns the states that changed while it ran. */
    fun callService(call: HaServiceCall): List<HaEntity> {
        val r = http.postJson(
            base().api("services/${call.domain}/${call.service}"),
            HaJson.encodeToString(JsonObject.serializer(), call.restBody()),
            headers(),
            SERVICE_TIMEOUT_MS,
        )
        return (json(r) as? JsonArray)?.mapNotNull { HaEntity.fromRest(it.jsonObject) }.orEmpty()
    }

    /** Assist: one sentence in, the reply out. */
    fun conversation(text: String, language: String? = null, agentId: String? = null, conversationId: String? = null): HaAssistReply {
        val r = http.postJson(
            base().api("conversation/process"),
            HaJson.encodeToString(JsonObject.serializer(), HaAssist.request(text, language, agentId, conversationId)),
            headers(),
            ASSIST_TIMEOUT_MS,
        )
        return HaAssist.parse(json(r).jsonObject)
    }

    /** A still from a camera, as the bytes of the image HA serves (JPEG or PNG). */
    fun cameraSnapshot(entityId: String): HaBytesResponse {
        val r = http.getBytes(base().api("camera_proxy/$entityId"), headers(), SNAPSHOT_TIMEOUT_MS)
        if (r.status !in OK) throw HaHttpException(r.status, "")
        return r
    }

    companion object {
        private const val SERVICE_TIMEOUT_MS = 20_000
        private const val ASSIST_TIMEOUT_MS = 30_000
        private const val SNAPSHOT_TIMEOUT_MS = 15_000
        private val OK = 200..299
    }
}

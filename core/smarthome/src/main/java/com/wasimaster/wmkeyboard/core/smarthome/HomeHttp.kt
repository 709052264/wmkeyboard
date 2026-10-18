package com.wasimaster.wmkeyboard.core.smarthome

import kotlinx.coroutines.runInterruptible
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A status the caller did not expect. 401 and 403 mean the key or token was refused. */
class HomeHttpException(val status: Int, val body: String) : IOException("HTTP $status") {
    val tokenRejected: Boolean get() = status == UNAUTHORIZED || status == FORBIDDEN

    companion object {
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
    }
}

data class HomeReply(val status: Int, val body: String) {
    val ok: Boolean get() = status in OK_RANGE

    /** The body, or a [HomeHttpException] for a status outside 2xx. */
    fun require(): String {
        if (!ok) throw HomeHttpException(status, body)
        return body
    }

    private companion object {
        val OK_RANGE = 200..299
    }
}

/**
 * The blocking requests every adapter makes, over the OkHttp client the owner
 * hands in (the keyboard's carries its network log and internet gate). Calls
 * suspend by running off the caller's thread; a status outside 2xx is handed
 * back, not thrown, so each API decides what it means.
 */
class HomeHttp(val client: OkHttpClient) {

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = DEFAULT_TIMEOUT_MS): HomeReply =
        exchange(Request.Builder().url(url).get(), headers, timeoutMs)

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = DEFAULT_TIMEOUT_MS): HomeReply =
        exchange(Request.Builder().url(url).post(json.toRequestBody(JSON)), headers, timeoutMs)

    suspend fun putJson(url: String, json: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = DEFAULT_TIMEOUT_MS): HomeReply =
        exchange(Request.Builder().url(url).put(json.toRequestBody(JSON)), headers, timeoutMs)

    suspend fun postText(url: String, text: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = DEFAULT_TIMEOUT_MS): HomeReply =
        exchange(Request.Builder().url(url).post(text.toRequestBody(TEXT)), headers, timeoutMs)

    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap(), timeoutMs: Long = DEFAULT_TIMEOUT_MS): HomeReply {
        val body = okhttp3.FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return exchange(Request.Builder().url(url).post(body), headers, timeoutMs)
    }

    private suspend fun exchange(builder: Request.Builder, headers: Map<String, String>, timeoutMs: Long): HomeReply = runInterruptible {
        headers.forEach { (k, v) -> builder.header(k, v) }
        val timed = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        timed.newCall(builder.build()).execute().use { response ->
            HomeReply(response.code, response.body?.string().orEmpty())
        }
    }

    /**
     * Reads a server-sent event stream until the server closes it or the
     * caller is cancelled. Blocking inside; cancellation closes the socket.
     * Throws [HomeHttpException] when the stream is refused.
     */
    suspend fun listen(url: String, headers: Map<String, String>, onEvent: (SseEvent) -> Unit) = runInterruptible {
        val builder = Request.Builder().url(url).get().header("Accept", "text/event-stream").header("Cache-Control", "no-cache")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val streaming = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
        val call = streaming.newCall(builder.build())
        call.execute().use { response ->
            if (!response.isSuccessful) throw HomeHttpException(response.code, response.body?.string().orEmpty())
            val source = response.body?.source() ?: return@use
            var event = ""
            var data = StringBuilder()
            var id: String? = null
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) onEvent(SseEvent(event.ifEmpty { "message" }, data.toString().removeSuffix("\n"), id))
                        event = ""
                        data = StringBuilder()
                    }
                    line.startsWith(":") -> Unit
                    else -> {
                        val colon = line.indexOf(':')
                        val field = if (colon < 0) line else line.substring(0, colon)
                        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
                        when (field) {
                            "event" -> event = value
                            "data" -> data.append(value).append('\n')
                            "id" -> id = value
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val TEXT = "text/plain; charset=utf-8".toMediaType()

        fun bearer(token: String?): Map<String, String> = if (token.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")

        fun headersOf(vararg pairs: Pair<String, String>): Headers = Headers.headersOf(*pairs.flatMap { listOf(it.first, it.second) }.toTypedArray())
    }
}

/** One server-sent event. */
data class SseEvent(val event: String, val data: String, val id: String? = null)

package com.wasimaster.wmkeyboard.core.homeassistant

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The blocking HTTP the REST side needs. An interface so the keyboard can
 * route it through its metered client (network log, internet gate) and the
 * tests through a plain one. Implementations never throw on an HTTP status;
 * they hand it back.
 */
interface HaHttp {
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = DEFAULT_TIMEOUT_MS): HaHttpResponse

    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = DEFAULT_TIMEOUT_MS): HaHttpResponse

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap(), timeoutMs: Int = DEFAULT_TIMEOUT_MS): HaHttpResponse

    fun getBytes(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = DEFAULT_TIMEOUT_MS): HaBytesResponse

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000

        fun formEncode(form: Map<String, String>): String =
            form.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
    }
}

data class HaHttpResponse(val status: Int, val body: String) {
    val ok: Boolean get() = status in OK_RANGE

    companion object {
        private val OK_RANGE = 200..299
    }
}

data class HaBytesResponse(val status: Int, val bytes: ByteArray, val contentType: String?)

/** A status the caller did not expect. [status] 401 and 403 mean the token was refused. */
class HaHttpException(val status: Int, val body: String) : IOException("HTTP $status") {
    val tokenRejected: Boolean get() = status == UNAUTHORIZED || status == FORBIDDEN

    companion object {
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
    }
}

/** `HttpURLConnection`, for tests and for a host without its own client. */
class JvmHaHttp : HaHttp {

    override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HaHttpResponse =
        exchange(url, "GET", null, null, headers, timeoutMs).let { HaHttpResponse(it.status, String(it.bytes, Charsets.UTF_8)) }

    override fun postJson(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): HaHttpResponse =
        exchange(url, "POST", body.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", headers, timeoutMs)
            .let { HaHttpResponse(it.status, String(it.bytes, Charsets.UTF_8)) }

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>, timeoutMs: Int): HaHttpResponse =
        exchange(url, "POST", HaHttp.formEncode(form).toByteArray(Charsets.UTF_8), "application/x-www-form-urlencoded", headers, timeoutMs)
            .let { HaHttpResponse(it.status, String(it.bytes, Charsets.UTF_8)) }

    override fun getBytes(url: String, headers: Map<String, String>, timeoutMs: Int): HaBytesResponse =
        exchange(url, "GET", null, null, headers, timeoutMs)

    private fun exchange(
        url: String,
        method: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): HaBytesResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) conn.errorStream else conn.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HaBytesResponse(status, bytes, conn.contentType)
        } finally {
            conn.disconnect()
        }
    }
}

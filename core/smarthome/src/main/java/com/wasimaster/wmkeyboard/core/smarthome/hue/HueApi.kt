package com.wasimaster.wmkeyboard.core.smarthome.hue

import com.wasimaster.wmkeyboard.core.homeassistant.HaCommandException
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttp
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttpException
import com.wasimaster.wmkeyboard.core.smarthome.arr
import com.wasimaster.wmkeyboard.core.smarthome.obj
import com.wasimaster.wmkeyboard.core.smarthome.objects
import com.wasimaster.wmkeyboard.core.smarthome.parseJson
import com.wasimaster.wmkeyboard.core.smarthome.str
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** What a bridge says about itself on its open config endpoint. */
data class HueBridgeInfo(val id: String, val name: String, val apiVersion: String?, val host: String) {
    companion object {
        /** `GET /api/0/config`: no key needed. */
        fun parse(body: String, host: String): HueBridgeInfo? {
            val o = parseJson(body).obj() ?: return null
            val id = o.str("bridgeid") ?: return null
            return HueBridgeInfo(id.lowercase(), o.str("name") ?: "Hue bridge", o.str("apiversion"), host)
        }
    }
}

/**
 * The bridge's address as a base URL. A bare host is HTTPS, which every
 * bridge since 2020 answers on; `http://` is accepted for a test server.
 */
object HueUrl {
    fun base(address: String): String? {
        val s = address.trim().trimEnd('/')
        if (s.isEmpty()) return null
        return when {
            s.startsWith("https://", ignoreCase = true) || s.startsWith("http://", ignoreCase = true) -> s
            s.contains("://") -> null
            else -> "https://$s"
        }
    }

    fun host(address: String): String = base(address)?.substringAfter("://")?.substringBefore('/')?.substringBefore(':').orEmpty()
}

/**
 * The bridge's TLS: a certificate of its own, signed by Signify's private
 * root and named after the bridge id, so no public store knows it and no
 * hostname matches it. The key pairing gives comes with a pin: the
 * certificate's SHA-256 is kept with the credential and every later
 * connection must show the same one. Before pairing, the first certificate
 * seen is the one pinned.
 */
object HueTls {

    /** [pinnedSha256] blank trusts the first certificate and reports it through [onSeen]. */
    fun client(base: OkHttpClient, pinnedSha256: String?, onSeen: (String) -> Unit = {}): OkHttpClient {
        val pin = pinnedSha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
                val seen = sha256(leaf.encoded)
                if (pin != null && seen != pin) throw CertificateException("bridge certificate changed")
                onSeen(seen)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** The bridge's CLIP v2 API over one pinned client. */
class HueApi(private val http: HomeHttp, private val baseUrl: () -> String, private val appKey: () -> String) {

    private val base: String get() = baseUrl()

    private fun headers(): Map<String, String> = mapOf("hue-application-key" to appKey(), "Accept" to "application/json")

    /** `GET /clip/v2/resource/{type}`: the `data` array, or a thrown status. The bridge's `errors` ride in the same reply. */
    suspend fun resources(type: String): List<JsonObject> {
        val body = http.get("$base/clip/v2/resource/$type", headers()).require()
        val o = parseJson(body).obj() ?: throw IllegalStateException("not a CLIP reply")
        return o.objects("data")
    }

    suspend fun put(type: String, id: String, json: String) {
        val reply = http.putJson("$base/clip/v2/resource/$type/$id", json, headers())
        if (!reply.ok) throw HomeHttpException(reply.status, reply.body)
        val errors = parseJson(reply.body).obj()?.arr("errors")
        if (errors != null && errors.isNotEmpty()) {
            throw HaCommandException("bridge", errors.firstOrNull()?.obj()?.str("description") ?: "refused")
        }
    }

    suspend fun bridge(): JsonObject? = resources("bridge").firstOrNull()

    /** The event stream, until the bridge closes it. */
    suspend fun listen(onEvent: (JsonObject) -> Unit) {
        http.listen("$base/eventstream/clip/v2", mapOf("hue-application-key" to appKey())) { ev ->
            parseJson(ev.data).arr()?.forEach { e -> e.obj()?.let(onEvent) }
        }
    }

    companion object {
        /** The open config endpoint, for discovery and before pairing. */
        suspend fun info(http: HomeHttp, base: String): HueBridgeInfo? {
            val body = http.get("$base/api/0/config", mapOf("Accept" to "application/json")).require()
            return HueBridgeInfo.parse(body, HueUrl.host(base))
        }

        /**
         * Pairing: with the bridge's button pressed in the last thirty
         * seconds, the bridge mints an application key for [deviceType].
         * Returns null while the button has not been pressed; throws for
         * anything else.
         */
        suspend fun pair(http: HomeHttp, base: String, deviceType: String = DEVICE_TYPE): String? {
            val body = http.postJson("$base/api", """{"devicetype":"$deviceType","generateclientkey":true}""", mapOf("Accept" to "application/json")).require()
            val first = parseJson(body).arr()?.firstOrNull().obj() ?: throw IllegalStateException("not a pairing reply")
            first.obj("success")?.str("username")?.let { return it }
            val error = first.obj("error")
            if (error?.str("type") == LINK_BUTTON_NOT_PRESSED) return null
            throw HomeHttpException(HomeHttpException.FORBIDDEN, error?.str("description") ?: body)
        }

        const val DEVICE_TYPE = "wmkeyboard#phone"
        private const val LINK_BUTTON_NOT_PRESSED = "101"
    }
}

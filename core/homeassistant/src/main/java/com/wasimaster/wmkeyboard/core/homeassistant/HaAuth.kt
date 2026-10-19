package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * The tokens a signed-in keyboard holds. A browser sign-in gives a short
 * access token and a refresh token; a pasted long-lived token is its own
 * access token for ten years and never refreshes.
 */
@Serializable
data class HaTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    /** When [accessToken] stops working, epoch millis; [Long.MAX_VALUE] for a long-lived one. */
    val expiresAtMs: Long = Long.MAX_VALUE,
    val longLived: Boolean = false,
) {
    /** Whether a refresh is due before using the token: less than [marginMs] of life left. */
    fun expiresWithin(nowMs: Long, marginMs: Long = REFRESH_MARGIN_MS): Boolean =
        !longLived && refreshToken != null && expiresAtMs - nowMs < marginMs

    val canRefresh: Boolean get() = refreshToken != null

    companion object {
        const val REFRESH_MARGIN_MS = 60_000L

        fun longLived(token: String): HaTokens = HaTokens(token.trim(), null, Long.MAX_VALUE, longLived = true)
    }
}

/**
 * Home Assistant's OAuth, IndieAuth style: the client id is a web page we
 * host, and that page names `wmkeyboard://ha-auth` as an allowed redirect
 * with a `<link rel="redirect_uri">`. The browser opens HA's own login, HA
 * redirects to the keyboard with a code, and the code is exchanged here.
 */
object HaAuth {

    const val CLIENT_ID = "https://wmkeyboard.pages.dev/ha/"
    const val REDIRECT_URI = "wmkeyboard://ha-auth"
    const val CALLBACK_HOST = "ha-auth"
    private const val STATE_SEPARATOR = '|'
    private const val STATE_PARTS = 3
    private const val NONCE_BYTES = 16
    private const val MS_PER_SECOND = 1000L

    /**
     * A fresh `state`: a nonce, the credential (the home's row) the sign-in is
     * for, and the server it was started against, so the callback knows which
     * home to finish and which address to trade the code at.
     */
    fun newState(base: HaUrl, credentialId: String, random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val nonce = bytes.joinToString("") { "%02x".format(it) }
        return nonce + STATE_SEPARATOR + credentialId + STATE_SEPARATOR + base.base
    }

    fun nonceOf(state: String): String = state.substringBefore(STATE_SEPARATOR)

    /** The credential id the sign-in was started for, blank for a state of another shape. */
    fun credentialOf(state: String): String = state.split(STATE_SEPARATOR, limit = STATE_PARTS).getOrNull(1).orEmpty()

    fun baseOf(state: String): HaUrl? = state.split(STATE_SEPARATOR, limit = STATE_PARTS).getOrNull(2)?.takeIf { it.isNotEmpty() }?.let(HaUrl::parse)

    fun authorizeUrl(base: HaUrl, state: String): String =
        base.auth("authorize") +
            "?client_id=" + encode(CLIENT_ID) +
            "&redirect_uri=" + encode(REDIRECT_URI) +
            "&state=" + encode(state)

    data class Callback(val code: String, val state: String)

    /** The `wmkeyboard://ha-auth?code=…&state=…` link the browser hands back, or null for any other link. */
    fun parseCallback(link: String?): Callback? {
        val l = link?.trim() ?: return null
        if (!l.startsWith("$REDIRECT_URI?") && !l.startsWith("$REDIRECT_URI/?")) return null
        val query = l.substringAfter('?')
        val params = query.split('&').mapNotNull { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            if (k.isEmpty()) null else decode(k) to decode(v)
        }.toMap()
        val code = params["code"]?.takeIf { it.isNotEmpty() } ?: return null
        val state = params["state"] ?: return null
        return Callback(code, state)
    }

    /** Trades the authorization code for tokens. Blocking. */
    fun exchange(http: HaHttp, base: HaUrl, code: String, nowMs: Long = System.currentTimeMillis()): HaTokens {
        val r = http.postForm(
            base.auth("token"),
            mapOf("grant_type" to "authorization_code", "code" to code, "client_id" to CLIENT_ID),
        )
        return tokensOf(r, nowMs, previousRefresh = null)
    }

    /** A new access token from the refresh token. The refresh token stays the same. Blocking. */
    fun refresh(http: HaHttp, base: HaUrl, tokens: HaTokens, nowMs: Long = System.currentTimeMillis()): HaTokens {
        val refresh = tokens.refreshToken ?: throw HaHttpException(HaHttpException.UNAUTHORIZED, "no refresh token")
        val r = http.postForm(
            base.auth("token"),
            mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to CLIENT_ID),
        )
        return tokensOf(r, nowMs, previousRefresh = refresh)
    }

    /** Signing out: the refresh token is revoked on the server, so the pair is dead even if a backup kept it. */
    fun revoke(http: HaHttp, base: HaUrl, tokens: HaTokens): Boolean {
        val refresh = tokens.refreshToken ?: return false
        val r = http.postForm(base.auth("token"), mapOf("action" to "revoke", "token" to refresh))
        return r.ok
    }

    private fun tokensOf(r: HaHttpResponse, nowMs: Long, previousRefresh: String?): HaTokens {
        if (!r.ok) throw HaHttpException(r.status, r.body)
        val o = HaJson.parseToJsonElement(r.body) as? JsonObject ?: throw HaHttpException(r.status, r.body)
        val access = o.str("access_token") ?: throw HaHttpException(r.status, r.body)
        val expiresIn = o.num("expires_in")?.toLong() ?: DEFAULT_EXPIRES_SECONDS
        return HaTokens(
            accessToken = access,
            refreshToken = o.str("refresh_token") ?: previousRefresh,
            expiresAtMs = nowMs + expiresIn * MS_PER_SECOND,
            longLived = false,
        )
    }

    private const val DEFAULT_EXPIRES_SECONDS = 1800L

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}

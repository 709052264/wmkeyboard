package com.wasimaster.wmkeyboard.core.smarthome.smartthings

import com.wasimaster.wmkeyboard.core.homeassistant.HaTokens
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttp
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttpException
import com.wasimaster.wmkeyboard.core.smarthome.num
import com.wasimaster.wmkeyboard.core.smarthome.obj
import com.wasimaster.wmkeyboard.core.smarthome.parseJson
import com.wasimaster.wmkeyboard.core.smarthome.str
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * SmartThings' OAuth 2.0, authorization code grant. The app is an OAuth-In
 * SmartApp registered with the SmartThings CLI; its client id and secret
 * come with the build. Access tokens last a day and the refresh token about
 * a month, so a signed-in home refreshes on its own.
 *
 * The redirect is a page we host, `https://wmkeyboard.pages.dev/st/`, which
 * hands the code on to `wmkeyboard://st-auth` through the `/open/` doorway.
 */
object SmartThingsAuth {

    const val AUTHORIZE_URL = "https://api.smartthings.com/oauth/authorize"
    const val TOKEN_URL = "https://auth-global.api.smartthings.com/oauth/token"
    const val REDIRECT_PAGE = "https://wmkeyboard.pages.dev/st/"
    const val REDIRECT_URI = "wmkeyboard://st-auth"
    const val CALLBACK_HOST = "st-auth"
    val SCOPES = listOf("r:devices:*", "x:devices:*", "r:locations:*", "r:scenes:*", "x:scenes:*")

    private const val STATE_SEPARATOR = '|'
    private const val NONCE_BYTES = 16
    private const val MS_PER_SECOND = 1000L
    private const val DEFAULT_EXPIRES_SECONDS = 86_400L

    /** A fresh `state`: a nonce and the credential the sign-in is for. */
    fun newState(credentialId: String, random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) } + STATE_SEPARATOR + credentialId
    }

    fun nonceOf(state: String): String = state.substringBefore(STATE_SEPARATOR)

    fun credentialOf(state: String): String = state.substringAfter(STATE_SEPARATOR, "")

    fun authorizeUrl(clientId: String, state: String): String =
        AUTHORIZE_URL +
            "?client_id=" + encode(clientId) +
            "&response_type=code" +
            "&redirect_uri=" + encode(REDIRECT_PAGE) +
            "&scope=" + encode(SCOPES.joinToString(" ")) +
            "&state=" + encode(state)

    data class Callback(val code: String, val state: String)

    /** The `wmkeyboard://st-auth?code=…&state=…` link the doorway hands back, or null for any other link. */
    fun parseCallback(link: String?): Callback? {
        val l = link?.trim() ?: return null
        if (!l.startsWith("$REDIRECT_URI?") && !l.startsWith("$REDIRECT_URI/?")) return null
        val params = l.substringAfter('?').split('&').mapNotNull { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            if (k.isEmpty()) null else decode(k) to decode(v)
        }.toMap()
        val code = params["code"]?.takeIf { it.isNotEmpty() } ?: return null
        val state = params["state"] ?: return null
        return Callback(code, state)
    }

    /** Trades the code for tokens. */
    suspend fun exchange(http: HomeHttp, clientId: String, clientSecret: String, code: String, nowMs: Long = System.currentTimeMillis()): HaTokens {
        val reply = http.postForm(
            TOKEN_URL,
            mapOf("grant_type" to "authorization_code", "code" to code, "client_id" to clientId, "redirect_uri" to REDIRECT_PAGE),
            basic(clientId, clientSecret),
        )
        return tokensOf(reply.require(), nowMs, previousRefresh = null)
    }

    /** A new access token, and usually a new refresh token, from the refresh token. */
    suspend fun refresh(http: HomeHttp, clientId: String, clientSecret: String, tokens: HaTokens, nowMs: Long = System.currentTimeMillis()): HaTokens {
        val refresh = tokens.refreshToken ?: throw HomeHttpException(HomeHttpException.UNAUTHORIZED, "no refresh token")
        val reply = http.postForm(
            TOKEN_URL,
            mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId),
            basic(clientId, clientSecret),
        )
        return tokensOf(reply.require(), nowMs, previousRefresh = refresh)
    }

    private fun basic(clientId: String, clientSecret: String): Map<String, String> =
        mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8)))

    private fun tokensOf(body: String, nowMs: Long, previousRefresh: String?): HaTokens {
        val o = parseJson(body).obj() ?: throw HomeHttpException(HomeHttpException.UNAUTHORIZED, body)
        val access = o.str("access_token") ?: throw HomeHttpException(HomeHttpException.UNAUTHORIZED, body)
        val expiresIn = o.num("expires_in")?.toLong() ?: DEFAULT_EXPIRES_SECONDS
        return HaTokens(
            accessToken = access,
            refreshToken = o.str("refresh_token") ?: previousRefresh,
            expiresAtMs = nowMs + expiresIn * MS_PER_SECOND,
            longLived = false,
        )
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}

package com.wasimaster.wmkeyboard.core.send

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What one phone shows the other to receive a file with no network between
 * them: `wmkeyboard://nearby?s=&k=&h=&p=&i=&f=&t=&n=&b=`, the Wi-Fi the
 * receiver has just opened (SSID, passphrase, band), where the receiver
 * listens on it (address, TCP port), and who it is (KDE Connect device id,
 * certificate fingerprint, one-time pairing token, name).
 *
 * The sender joins the Wi-Fi, announces to the address, and pairs with the
 * token, so the whole exchange is one scan. The same `https://<docs>/open/#…`
 * wrapper the computer's pairing code uses lets a phone camera open it.
 */
data class SendDirectCode(
    val ssid: String,
    val passphrase: String,
    val host: String,
    val port: Int,
    val deviceId: String,
    val fingerprint: String,
    val token: String,
    val name: String,
    /** "2" or "5" (GHz), or "" when unknown. */
    val band: String = "",
) {
    /** The bare `wmkeyboard://` form. */
    fun encode(): String = buildString {
        append("wmkeyboard://nearby?")
        append("s=").append(enc(ssid))
        append("&k=").append(enc(passphrase))
        append("&h=").append(enc(host))
        append("&p=").append(port)
        append("&i=").append(enc(deviceId))
        append("&f=").append(enc(fingerprint))
        append("&t=").append(enc(token))
        append("&n=").append(enc(name))
        if (band.isNotEmpty()) append("&b=").append(enc(band))
    }

    /** The web form a camera app can open: `https://<docs>/open/#wmkeyboard://nearby?…`. */
    fun encodeWeb(docsBase: String): String = docsBase.trimEnd('/') + "/open/#" + encode()

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun parse(link: String): SendDirectCode? {
            val uri = runCatching { java.net.URI(link.trim()) }.getOrNull() ?: return null
            if (uri.scheme.equals("https", ignoreCase = true) && uri.rawPath.orEmpty().trimEnd('/') == "/open") {
                // Raw: the decoded form would hand the inner link its spaces back.
                val inner = uri.rawFragment?.trim().orEmpty()
                return if (inner.startsWith("wmkeyboard://nearby")) parse(inner) else null
            }
            if (uri.scheme != "wmkeyboard" || uri.host != "nearby") return null
            val query = uri.rawQuery ?: return null
            val q = query.split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null
                else {
                    val raw = part.substring(i + 1)
                    part.substring(0, i) to (runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: raw)
                }
            }.toMap()
            val ssid = q["s"]?.takeIf { it.isNotBlank() } ?: return null
            val host = q["h"]?.takeIf { it.isNotBlank() } ?: return null
            val port = q["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val id = q["i"]?.takeIf { it.isNotBlank() } ?: return null
            val token = q["t"]?.takeIf { it.length >= 16 } ?: return null
            return SendDirectCode(
                ssid = ssid,
                passphrase = q["k"].orEmpty(),
                host = host,
                port = port,
                deviceId = id,
                fingerprint = q["f"].orEmpty(),
                token = token,
                name = q["n"].orEmpty(),
                band = q["b"].orEmpty(),
            )
        }
    }
}

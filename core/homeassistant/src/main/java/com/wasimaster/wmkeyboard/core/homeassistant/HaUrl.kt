package com.wasimaster.wmkeyboard.core.homeassistant

/**
 * A Home Assistant base address, normalised from whatever the user typed:
 * `homeassistant.local`, `192.168.1.5:8123`, `https://x.ui.nabu.casa`, or a
 * page address pasted from the browser. A bare host without a scheme is plain
 * HTTP on 8123 when it looks like a LAN name or address, and HTTPS on 443 when
 * it looks like a public domain, which is what a Nabu Casa or reverse-proxied
 * instance answers on.
 */
data class HaUrl(val scheme: String, val host: String, val port: Int) {

    val secure: Boolean get() = scheme == "https"

    private val defaultPort: Boolean get() = if (secure) port == HTTPS_PORT else port == HTTP_PORT

    private val hostForUrl: String get() = if (host.contains(':')) "[$host]" else host

    /** `scheme://host[:port]`, no trailing slash. */
    val base: String get() = if (defaultPort) "$scheme://$hostForUrl" else "$scheme://$hostForUrl:$port"

    /** The WebSocket API address. */
    val websocket: String get() = (if (secure) "wss" else "ws") + base.removePrefix(scheme) + "/api/websocket"

    fun api(path: String): String = base + "/api/" + path.trimStart('/')

    fun auth(path: String): String = base + "/auth/" + path.trimStart('/')

    /** Whether [other] names the same server, whatever the user typed. */
    fun sameServer(other: HaUrl?): Boolean = other != null && other.host == host && other.port == port

    override fun toString(): String = base

    companion object {
        const val DEFAULT_PORT = 8123
        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443
        private const val MAX_PORT = 65535

        @Suppress("detekt.ReturnCount", "detekt.CyclomaticComplexMethod")
        fun parse(input: String): HaUrl? {
            var s = input.trim().trimEnd('/')
            if (s.isEmpty()) return null
            val scheme: String
            when {
                s.startsWith("https://", ignoreCase = true) -> {
                    scheme = "https"
                    s = s.drop("https://".length)
                }
                s.startsWith("http://", ignoreCase = true) -> {
                    scheme = "http"
                    s = s.drop("http://".length)
                }
                s.contains("://") -> return null
                else -> scheme = if (looksPublic(s)) "https" else "http"
            }
            // A pasted page address carries a path; the server is before it.
            s = s.substringBefore('/').substringBefore('?').substringBefore('#')
            if (s.isEmpty() || s.contains('@') || s.any { it.isWhitespace() }) return null
            val host: String
            val portText: String?
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) return null
                host = s.substring(1, end)
                val rest = s.substring(end + 1)
                if (rest.isNotEmpty() && !rest.startsWith(":")) return null
                portText = rest.removePrefix(":").ifEmpty { null }
            } else {
                val colon = s.lastIndexOf(':')
                if (colon >= 0) {
                    host = s.substring(0, colon)
                    portText = s.substring(colon + 1)
                } else {
                    host = s
                    portText = null
                }
            }
            if (host.isEmpty()) return null
            val port = when (portText) {
                null -> if (scheme == "https") HTTPS_PORT else DEFAULT_PORT
                else -> portText.toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null
            }
            return HaUrl(scheme, host.lowercase(), port)
        }

        /** A public domain, as opposed to a LAN name or an IP address. */
        private fun looksPublic(s: String): Boolean {
            val host = s.substringBefore('/').substringBefore(':').lowercase()
            if (host.startsWith("[") || isIpv4(host)) return false
            if (!host.contains('.')) return false
            return LAN_SUFFIXES.none { host.endsWith(it) }
        }

        private fun isIpv4(host: String): Boolean {
            val parts = host.split('.')
            return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
        }

        private val LAN_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa")
    }
}

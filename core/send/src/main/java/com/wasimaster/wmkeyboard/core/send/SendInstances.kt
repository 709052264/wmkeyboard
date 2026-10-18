package com.wasimaster.wmkeyboard.core.send

/**
 * A public Send instance as the community list describes it. The limits are
 * what the list said when this was written; the server's own `/config` is
 * asked before anything is uploaded, and that answer wins.
 */
data class SendInstance(
    val url: String,
    /** Largest upload, bytes; 0 when unknown. */
    val maxBytes: Long = 0,
    /** Longest expiry, days; 0 when unknown. */
    val maxDays: Int = 0,
    val maxDownloads: Int = 0,
    /** ISO 3166-1 alpha-2 of where it is hosted, or "". */
    val country: String = "",
    /** The maintainer of Send runs this one. */
    val reference: Boolean = false,
) {
    val host: String get() = SendInstances.displayHost(url)
}

/**
 * The curated list from `timvisee/send-instances` (read 2026-10-18), which
 * gives no warranty on any of them, and the grammar of an instance address.
 * The default is the one the maintainer of Send runs.
 */
object SendInstances {
    const val DEFAULT_URL = "https://send.vis.ee"

    private const val GIB = 1024L * 1024 * 1024
    private const val MIB = 1024L * 1024

    val catalogue: List<SendInstance> = listOf(
        SendInstance(DEFAULT_URL, maxBytes = 2560 * MIB, maxDays = 3, maxDownloads = 10, country = "NL", reference = true),
        SendInstance("https://send.mni.li", maxBytes = 8 * GIB, maxDays = 7, maxDownloads = 25, country = "NL"),
        SendInstance("https://send.monks.tools", maxBytes = 5 * GIB, maxDays = 7, maxDownloads = 50, country = "US"),
        SendInstance("https://send.aurorabilisim.com", maxBytes = 2560 * MIB, maxDays = 7, maxDownloads = 100, country = "TR"),
        SendInstance("https://send.artemislena.eu", maxBytes = 2560 * MIB, maxDays = 7, maxDownloads = 100, country = "DE"),
        SendInstance("https://fileupload.ggc-project.de", maxBytes = 2560 * MIB, maxDays = 7, maxDownloads = 100, country = "DE"),
        SendInstance("https://drop.chapril.org", maxBytes = 1 * GIB, maxDays = 5, maxDownloads = 100, country = "DE"),
        SendInstance("https://send.jeugdhulp.be", maxBytes = 50 * MIB, maxDays = 10, maxDownloads = 25, country = "FR"),
        SendInstance("https://send.cyberjake.xyz", maxBytes = 10 * GIB, maxDays = 30, maxDownloads = 100, country = "US"),
        SendInstance("https://send.adminforge.de", maxBytes = 8 * GIB, maxDays = 7, maxDownloads = 1000, country = "DE"),
        SendInstance("https://send.turingpoint.de", maxBytes = 10 * GIB, maxDays = 7, maxDownloads = 10, country = "DE"),
        SendInstance("https://send.aslaets.be", maxBytes = 512 * MIB, maxDays = 7, maxDownloads = 3, country = "DE"),
        SendInstance("https://send.codespace.cz", maxBytes = 10 * GIB, maxDays = 7, maxDownloads = 100, country = "CZ"),
        SendInstance("https://upload.nolog.cz", maxBytes = 5 * GIB, maxDays = 7, maxDownloads = 100, country = "CZ"),
        SendInstance("https://send.canine.tools", maxBytes = 1 * GIB, maxDays = 7, maxDownloads = 100, country = "US"),
        SendInstance("https://send.skylerszijjarto.com", maxBytes = 250 * GIB, maxDays = 7, maxDownloads = 10_000, country = "US"),
        SendInstance("https://send.hostnetwork.xyz", maxBytes = 2560 * MIB, maxDays = 8, maxDownloads = 999_999, country = "DE"),
        SendInstance("https://send.blablalinux.be", maxBytes = 512 * MIB, maxDays = 7, maxDownloads = 10, country = "BE"),
        SendInstance("https://dropnito.online", maxBytes = 150 * MIB, maxDays = 7, maxDownloads = 100, country = "CZ"),
    )

    fun catalogued(url: String): SendInstance? = normaliseBase(url)?.let { b -> catalogue.firstOrNull { it.url == b } }

    /**
     * What the user typed as an instance, made into a base URL: `send.vis.ee`,
     * `https://send.vis.ee/`, `send.vis.ee/download/abc/#k` all become
     * `https://send.vis.ee`. A path prefix is kept (`https://x.org/send`), a
     * share link's own path is not. Null for anything that is not a host.
     */
    fun normaliseBase(input: String): String? {
        var text = input.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) text = "https://$text"
        val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() && it.contains('.') || it == "localhost" } ?: return null
        var path = uri.rawPath.orEmpty().trimEnd('/')
        val download = path.indexOf("/download/")
        if (download >= 0) path = path.substring(0, download)
        if (path == "/") path = ""
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port$path"
    }

    /** `send.vis.ee` for display: no scheme, no trailing slash, a path prefix kept. */
    fun displayHost(url: String): String =
        url.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

    /** The instances with a cap at or above [bytes], largest-known first: what to suggest when a file is too big. */
    fun roomFor(bytes: Long): List<SendInstance> =
        catalogue.filter { it.maxBytes >= bytes }.sortedByDescending { it.maxBytes }
}

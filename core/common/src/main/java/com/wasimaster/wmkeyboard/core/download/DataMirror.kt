package com.wasimaster.wmkeyboard.core.download

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo

/**
 * A copy of the data repository's `models` and `natives` releases on
 * Cloudflare R2, which [FileDownloader] tries before GitHub. Same file names
 * (`<sha256>-<name>`) under `<tag>/`, so the pins built into the app check its
 * bytes exactly as they check GitHub's, and the mirror is trusted no more.
 *
 * GitHub holds one connection to 0.3 to 0.8 MB/s from Bangladesh; Cloudflare's
 * Dhaka edge gave 7.6 to 8.4 MB/s on one connection from the phone (measured
 * 2026-10-21). A file missing from the mirror, or a mirror that is down, falls
 * back to GitHub with whatever pieces already arrived.
 *
 * Filled by `scripts/mirror-releases-to-r2.sh`.
 */
object DataMirror {

    /** The mirror's address with no trailing slash, or null to fetch everything from GitHub. */
    @Volatile
    var base: String? = null

    /** The releases that are mirrored. Anything else in the repository is not. */
    val TAGS = setOf("models", "natives")

    /**
     * The mirror's address for [url], or null when [url] is not an asset of a
     * mirrored release, when there is no mirror, or when the data repository
     * has been pointed somewhere else (the F-Droid build's server settings):
     * a repository the user chose is the one they get.
     */
    fun of(url: String): String? {
        val base = base ?: return null
        val data = ServiceRepo.DATA.default
        if (ServiceEndpoints.repo(ServiceRepo.DATA) != data) return null
        return mirrored(url, "https://${data.host}/${data.owner}/${data.repo}/releases/download/", base)
    }

    /** [url] under [origin] (`…/releases/download/`) moved to [base], for a [TAGS] release. */
    internal fun mirrored(url: String, origin: String, base: String): String? {
        if (!url.startsWith(origin)) return null
        val rest = url.removePrefix(origin)
        val tag = rest.substringBefore('/', "")
        val asset = rest.substringAfter('/', "")
        if (tag !in TAGS || asset.isEmpty() || '/' in asset || '?' in asset) return null
        return "${base.trimEnd('/')}/$tag/$asset"
    }
}

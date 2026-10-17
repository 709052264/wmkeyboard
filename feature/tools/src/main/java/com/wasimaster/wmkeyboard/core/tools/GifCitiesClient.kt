package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * GifCities, the Internet Archive's search over the GIFs it saved from
 * GeoCities before that closed in 2009.
 *
 * No key and no account, so it works on every build. The corpus is what you
 * would expect of 1990s home pages: under-construction signs, spinning
 * globes, dancing babies, small animated clip art. It carries no content
 * rating, so the shared content filter has nothing to map onto; the setting
 * that turns it on says so.
 *
 * The search answers with an archive path rather than a file. The file itself
 * comes from the Wayback Machine, with `im_` after the timestamp so it serves
 * the raw image instead of wrapping it in its replay banner.
 */
object GifCitiesClient {

    /**
     * Results smaller than this on either side are dropped: GeoCities was full
     * of 10-pixel bullets and spacer GIFs, and blown up to a grid cell they
     * are a blur that nobody would send.
     */
    private const val MIN_SIDE = 32

    /**
     * GifCities has no trending feed and answers a blank query with `null`, so
     * the default view searches one of these, a different one each day.
     */
    internal val BROWSE_TERMS = listOf(
        "welcome", "under construction", "dancing", "cat", "fire", "email",
        "rainbow", "globe", "star", "heart", "computer", "flowers", "dog", "new",
    )

    /** Blocking; call on an IO dispatcher. Throws on failure. */
    fun search(query: String, limit: Int = 24, dayIndex: Long = System.currentTimeMillis() / DAY_MS): List<GifItem> {
        val terms = query.trim().ifEmpty { BROWSE_TERMS[Math.floorMod(dayIndex, BROWSE_TERMS.size.toLong()).toInt()] }
        val url = "${ServiceEndpoints.base(ServiceEndpoint.GIFCITIES)}$SEARCH_PATH?q=${ToolHttp.encode(terms)}"
        return parse(
            ToolHttp.get(url, source = NetSource.GIF, route = SEARCH_PATH),
            ServiceEndpoints.base(ServiceEndpoint.WAYBACK_MACHINE),
        ).take(limit.coerceAtLeast(1))
    }

    internal fun parse(body: String, wayback: String = ServiceEndpoint.WAYBACK_MACHINE.default): List<GifItem> {
        val results = runCatching { Json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return results.mapNotNull { element ->
            val result = element as? JsonObject ?: return@mapNotNull null
            val path = result.text("gif") ?: return@mapNotNull null
            val file = fileUrl(wayback, path) ?: return@mapNotNull null
            val width = result.int("width")
            val height = result.int("height")
            if (width in 1 until MIN_SIDE || height in 1 until MIN_SIDE) return@mapNotNull null
            GifItem(
                id = "gifcities_" + (result.text("checksum") ?: path),
                // One file serves both: GeoCities GIFs are a few kilobytes,
                // and there is no smaller rendition to ask for.
                previewUrl = file,
                fullUrl = file,
                mime = "image/gif",
                aspectRatio = if (width > 0 && height > 0) width.toFloat() / height else 1f,
                source = GifSource.GIFCITIES,
                title = result.text("url_text").orEmpty(),
            )
        }
    }

    /**
     * `20091020083540/http://geocities.com/…/cat.gif` ->
     * `https://web.archive.org/web/20091020083540im_/http://geocities.com/…/cat.gif`.
     * Null when the path does not start with a timestamp.
     */
    internal fun fileUrl(wayback: String, path: String): String? {
        val slash = path.indexOf('/')
        if (slash <= 0) return null
        val timestamp = path.take(slash)
        if (timestamp.any { !it.isDigit() }) return null
        return "${wayback.trimEnd('/')}/web/${timestamp}im_/${path.substring(slash + 1)}"
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private const val SEARCH_PATH = "/api/v1/gifsearch"
    private const val DAY_MS = 24L * 60 * 60 * 1000
}

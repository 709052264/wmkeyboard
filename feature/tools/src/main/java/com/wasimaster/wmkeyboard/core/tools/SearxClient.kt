package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.tools.feature.R
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Web and image search against a SearXNG instance the user names.
 *
 * The F-Droid build's search, and the reason it can be one: SearXNG is AGPL and
 * self-hostable, so this is a configuration choice rather than a dependency on
 * anyone's product. The other channels keep [BraveSearchClient]; nothing here
 * changes for them.
 *
 * **There is no default instance, on purpose.** SearXNG ships with
 * `search.formats: [html]`, and an instance that has not added `json` answers
 * `format=json` with 403 — so most public instances refuse this API outright.
 * A baked-in default would therefore fail for most users, at a moment when they
 * have no way to see why. Instead the tool stays blocked until an instance is
 * named (see `ToolBlocker.NEEDS_SEARCH_INSTANCE`), and a 403 from one that was
 * named says what to switch on rather than "Forbidden".
 */
object SearxClient {

    /**
     * Blocking; call on an IO dispatcher. Throws on failure.
     *
     * [source] is what the network log files the request under. It is the
     * search tool unless the AI tool ran the search itself (#470).
     */
    fun webSearch(
        query: String,
        instance: String,
        count: Int,
        safe: Boolean,
        source: NetSource = NetSource.WEB_SEARCH,
        page: Int = 0,
    ): WebSearchPage {
        val body = get(searchUrl(instance, query, categories = "general", safe = safe, page = page), source)
        return WebSearchPage(parseWeb(body).take(count.coerceIn(1, 50)), parseAnswer(body))
    }

    /**
     * The instance's `answers`, the first that says anything: plain strings on
     * older SearXNG, `{"answer": …}` objects on newer.
     */
    internal fun parseAnswer(body: String): String? {
        val answers = Json.parseToJsonElement(body).jsonObject["answers"] as? JsonArray ?: return null
        return answers.firstNotNullOfOrNull { element ->
            val text = when (element) {
                is JsonPrimitive -> element.takeIf { it.isString }?.content
                is JsonObject -> (element["answer"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                else -> null
            }
            text?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /** Blocking; call on an IO dispatcher. Throws on failure. [page] counts from 0. */
    fun imageSearch(query: String, instance: String, count: Int, safe: Boolean, page: Int = 0): List<ImageResult> =
        parseImages(get(searchUrl(instance, query, categories = "images", safe = safe, page = page), NetSource.IMAGE_SEARCH))
            .take(count.coerceIn(1, 50))

    /**
     * A 403 here almost always means one thing, and the generic wording for it
     * sends the user looking in the wrong place. Their instance is up and
     * reachable — it just has not enabled the JSON format, which is one line in
     * `settings.yml`. Say that instead.
     *
     * A 429 is the bot limiter public instances run, not a quota, so it gets
     * its own wording too.
     *
     * A web page where JSON was asked for is a check the request did not pass:
     * the limiter's redirect to the home page, or a proxy's challenge page
     * (Anubis and the like) in front of the instance. Parsing it would only
     * surface a JSON error with the page's first bytes in it (#592).
     */
    private fun get(url: String, source: NetSource): String {
        val body = try {
            ToolHttp.get(url, headers = requestHeaders(), source = source, route = NetLog.pathOf(url))
        } catch (e: ToolHttpException) {
            when (e.status) {
                HTTP_FORBIDDEN -> throw ToolHttpException(R.string.ftools_search_error_json_disabled)
                HTTP_TOO_MANY_REQUESTS -> throw ToolHttpException(R.string.ftools_search_error_searx_limited)
            }
            throw e
        }
        if (isWebPage(body)) throw ToolHttpException(R.string.ftools_search_error_searx_web_page)
        return body
    }

    internal fun isWebPage(body: String): Boolean = body.trimStart().startsWith('<')

    /**
     * The headers a browser sends, which is what SearXNG's limiter (on by
     * default on public instances) checks before anything else (#592). It
     * answers 429 to a request whose `Accept` lacks `text/html` or which has
     * no `Accept-Language` at all, whatever the rate, so `Accept:
     * application/json` alone was refused by every instance that runs it.
     * JSON still comes first; the format is asked for in the URL anyway.
     *
     * `Sec-Fetch-*` because [ToolHttp.USER_AGENT] reads as Chrome, and the
     * limiter's `http_sec_fetch` check sends any Chrome 80+ that lacks them
     * back to the home page with a 302, which HttpURLConnection follows into
     * a 200 HTML page. These are the values Chrome sends for an address
     * typed into its bar.
     * `Accept-Encoding` is left to HttpURLConnection, which offers gzip and
     * unpacks it only while the header is its own.
     */
    internal fun requestHeaders(locale: Locale = Locale.getDefault()): Map<String, String> {
        val tag = locale.toLanguageTag().takeIf { it.isNotEmpty() && it != "und" }
        val language = when {
            tag == null -> "en;q=0.9"
            locale.language == "en" -> "$tag,en;q=0.9"
            else -> "$tag,${locale.language};q=0.9,en;q=0.8"
        }
        return mapOf(
            "Accept" to "application/json,text/html;q=0.9,*/*;q=0.8",
            "Accept-Language" to language,
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-User" to "?1",
        )
    }

    /**
     * SearXNG takes no result-count parameter — it answers with a page and the
     * caller keeps what it wants — so [webSearch] and [imageSearch] trim rather
     * than ask. `safesearch` is 0/1/2 for off/moderate/strict, and images get
     * the strict end for the same reason the Brave path does. [page] is the
     * instance's own page, which `pageno` counts from 1 (#879).
     */
    internal fun searchUrl(
        instance: String,
        query: String,
        categories: String,
        safe: Boolean,
        page: Int = 0,
    ): String {
        val safesearch = when {
            !safe -> 0
            categories == "images" -> 2
            else -> 1
        }
        return buildString {
            append(searchBase(instance))
            append("?q=${ToolHttp.encode(query.trim())}")
            append("&format=json")
            append("&categories=$categories")
            append("&safesearch=$safesearch")
            if (page > 0) append("&pageno=${page + 1}")
        }
    }

    /**
     * The `/search` endpoint from whatever the user pasted: a bare host, a host
     * with a scheme, a trailing slash, or the full path. All are things people
     * copy out of an instance's front page, and a 404 for the wrong one looks
     * like the instance being broken rather than the field being picky.
     */
    internal fun searchBase(instance: String): String {
        val raw = instance.trim()
        require(raw.isNotEmpty()) { "no SearXNG instance configured" }
        val withScheme = when {
            raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("https://", ignoreCase = true) -> raw
            !raw.contains("://") -> "https://$raw"
            else -> throw IllegalArgumentException("unsupported scheme in SearXNG URL")
        }
        val trimmed = withScheme.trimEnd('/')
        return if (trimmed.endsWith("/search")) trimmed else "$trimmed/search"
    }

    internal fun parseWeb(body: String): List<WebResult> {
        val results = resultsOf(body)
        return results.mapNotNull { element ->
            val item = element.jsonObject
            val url = item["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = item["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            WebResult(
                title = title,
                snippet = item["content"]?.jsonPrimitive?.content.orEmpty(),
                url = url,
                displayUrl = hostOf(url),
            )
        }
    }

    internal fun parseImages(body: String): List<ImageResult> {
        val results = resultsOf(body)
        return results.mapNotNull { element ->
            val item = element.jsonObject
            // Without a full image there is nothing to insert, so an entry that
            // carries only a thumbnail is dropped rather than shown.
            val full = item["img_src"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            // Engines disagree about the name, and some send neither.
            val thumb = item["thumbnail_src"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: item["thumbnail"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: full
            ImageResult(
                title = item["title"]?.jsonPrimitive?.content.orEmpty(),
                thumbUrl = thumb,
                imageUrl = full,
                mime = mimeOf(item["img_format"]?.jsonPrimitive?.content, full),
                contextUrl = item["url"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }

    /** An instance that found nothing answers with an empty list, not an error. */
    private fun resultsOf(body: String) =
        Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty()

    /**
     * SearXNG reports `img_format` for some engines ("jpeg", "png", "gif",
     * sometimes "Image/jpeg"), and nothing for others. Take it when it names a
     * type we know and fall back to the extension, the way the Brave path does.
     */
    internal fun mimeOf(format: String?, url: String): String {
        val named = format?.lowercase()?.substringAfterLast('/')?.trim()
        return when (named) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            else -> BraveSearchClient.mimeFromUrl(url)
        }
    }

    /** "https://en.wikipedia.org/wiki/X" -> "en.wikipedia.org". */
    internal fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/')

    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_TOO_MANY_REQUESTS = 429
}

package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The keyboard's own documentation, searched for a model that was asked about
 * the keyboard.
 *
 * A model answering "how do I import the Sogou dictionary?" from training data
 * describes some other keyboard, or this one as it was a year ago. The docs
 * site answers `GET /api/docs/search?q=` with the sections that match, each
 * with its link (docs/functions/api/docs/search.ts), so the answer can quote
 * the page that covers it and the user can tap through to check.
 *
 * Only the query leaves the phone, and only to the docs site.
 */
object WmDocs {

    /** The docs site. Same host as `DOCS_URL` in the app's About screen. */
    const val BASE_URL = "https://wmkeyboard.pages.dev"

    /** One documentation section. */
    data class Hit(val title: String, val heading: String, val url: String, val text: String) {
        /** "Chinese, Japanese & Korean › Your own word lists", or the title alone. */
        val label: String get() = if (heading.isEmpty()) title else "$title › $heading"
    }

    /**
     * Sections handed to the model per search. Each runs up to ~1,800
     * characters and is replayed into every later round of the run, so a
     * small on-device model gets fewer.
     */
    const val DEFAULT_RESULTS = 4

    /** Blocking; call on an IO dispatcher. Throws [ToolHttpException] or an IOException when the site can't be reached. */
    fun search(query: String, limit: Int = DEFAULT_RESULTS, source: NetSource = NetSource.AI_TOOLS): List<Hit> {
        val url = "$BASE_URL/api/docs/search?q=${URLEncoder.encode(query.take(MAX_QUERY), "UTF-8")}&limit=$limit"
        return parse(ToolHttp.get(url, timeoutMs = TIMEOUT_MS, source = source, route = "docs_search"))
    }

    /** The search API's answer as hits; anything malformed is left out rather than thrown. */
    fun parse(body: String): List<Hit> {
        val results = runCatching { Json.parseToJsonElement(body).jsonObject["results"] as? JsonArray }
            .getOrNull() ?: return emptyList()
        return results.mapNotNull { element ->
            val hit = element as? JsonObject ?: return@mapNotNull null
            fun field(name: String) = (hit[name] as? JsonPrimitive)?.content?.trim().orEmpty()
            val url = field("url")
            val text = field("text")
            if (url.isEmpty() || text.isEmpty()) null else Hit(field("title"), field("heading"), url, text)
        }
    }

    /**
     * Hits as the model reads them: each section under its title and link,
     * and a reminder to cite, because an answer with the link is one the
     * user can check.
     */
    fun format(query: String, hits: List<Hit>): String {
        if (hits.isEmpty()) {
            return "The WM Keyboard documentation has nothing matching \"$query\". " +
                "Try other words, or say the docs don't cover it rather than guess."
        }
        return buildString {
            append("From the WM Keyboard documentation. Answer from these sections and give the user the link of the one you used.\n")
            hits.forEachIndexed { index, hit ->
                append("\n").append(index + 1).append(". ").append(hit.label).append('\n')
                append(hit.url).append("\n\n")
                append(hit.text).append('\n')
            }
        }.trim()
    }

    private const val MAX_QUERY = 300
    private const val TIMEOUT_MS = 10_000
}

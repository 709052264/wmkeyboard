package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.providers.ProviderRunner
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.resolveProviders

/**
 * The web and image search tools' providers: SearXNG instances, Tavily,
 * Brave, in the user's order. The first that answers is the answer; one that
 * fails hands the query to the next. Shared by the panels and the AI's
 * search tools, so they never disagree about where a query goes.
 *
 * Blocking; call on an IO dispatcher. Throws the last provider's failure.
 */
object SearchProviders {

    /** [pick] is the panel's choice in pick mode; null, the AI's tools, walks the whole list. */
    fun rows(settings: KeyboardSettings, pick: String? = null): List<ResolvedProvider> =
        settings.resolveProviders(ProviderTool.SEARCH, pick = pick).filter { backend(it) != null }

    fun backend(row: ResolvedProvider): SearchBackend? = when (row.typeId) {
        ProviderTypes.SEARXNG.id -> SearchBackend.SEARXNG
        ProviderTypes.TAVILY.id -> SearchBackend.TAVILY
        ProviderTypes.BRAVE.id -> SearchBackend.BRAVE
        else -> null
    }

    fun web(
        settings: KeyboardSettings,
        query: String,
        count: Int,
        safe: Boolean,
        answer: Boolean,
        source: NetSource = NetSource.WEB_SEARCH,
        pick: String? = null,
    ): WebSearchPage = ask(settings, pick) { row, backend ->
        when (backend) {
            SearchBackend.SEARXNG -> SearxClient.webSearch(query, row.url, count, safe, source = source)
            SearchBackend.TAVILY -> TavilySearchClient.webSearch(
                query,
                row.apiKey,
                count,
                safe,
                advanced = settings.webSearch.tavilyAdvanced,
                answer = answer,
                source = source,
            )
            SearchBackend.BRAVE -> WebSearchPage(BraveSearchClient.webSearch(query, row.apiKey, count, safe, source = source))
        }
    }

    fun images(
        settings: KeyboardSettings,
        query: String,
        count: Int,
        safe: Boolean,
        pick: String? = null,
    ): List<ImageResult> =
        ask(settings, pick) { row, backend ->
            when (backend) {
                SearchBackend.SEARXNG -> SearxClient.imageSearch(query, row.url, count, safe)
                SearchBackend.TAVILY -> TavilySearchClient.imageSearch(query, row.apiKey, count, safe)
                SearchBackend.BRAVE -> BraveSearchClient.imageSearch(query, row.apiKey, count, safe)
            }
        }

    private inline fun <T> ask(settings: KeyboardSettings, pick: String?, call: (ResolvedProvider, SearchBackend) -> T): T {
        val rows = rows(settings, pick)
        if (rows.isEmpty()) throw NoSearchProviderException()
        val outcome = ProviderRunner.fallback(rows, key = { it.key }) { row -> call(row, requireNotNull(backend(row))) }
        return when (outcome) {
            is ProviderRunner.Outcome.Found -> outcome.value
            ProviderRunner.Outcome.NotFound -> throw NoSearchProviderException()
            is ProviderRunner.Outcome.Failed -> throw outcome.error ?: NoSearchProviderException()
        }
    }

    /** The search list has nothing on that can be asked. */
    class NoSearchProviderException : IllegalStateException("No search provider is on")
}

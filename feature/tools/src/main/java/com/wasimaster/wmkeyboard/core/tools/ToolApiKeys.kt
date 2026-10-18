package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.hasSearchKey
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.providerCredential

/**
 * Resolves the effective API key for each network tool: a key the user
 * pasted into the tool's settings wins; otherwise the key baked into the
 * build (from local.properties / environment variables — see
 * app/build.gradle.kts) applies; blank means the tool shows its
 * "needs an API key" state (translate instead falls back to the free
 * endpoint, which needs none).
 */
object ToolApiKeys {

    fun klipy(settings: KeyboardSettings): String = key(settings, ProviderTypes.KLIPY)

    fun giphy(settings: KeyboardSettings): String = key(settings, ProviderTypes.GIPHY)

    /**
     * The key in force for a single-instance provider: the one the user set
     * up (shared by every tool that lists it), else the one this build carries.
     */
    fun key(settings: KeyboardSettings, type: ProviderType): String =
        settings.providerCredential(type.id)?.apiKey?.trim().orEmpty().ifBlank { type.builtInKey }

    /** Which GIF providers can serve requests, in the user's order; see [MediaProviders]. */
    fun gifSources(settings: KeyboardSettings): List<GifSource> =
        MediaProviders.targets(settings, sticker = false).map { it.source }.distinct()

    /** The same for the sticker tool. */
    fun stickerSources(settings: KeyboardSettings): List<GifSource> =
        MediaProviders.targets(settings, sticker = true).map { it.source }.distinct()

    fun brave(settings: KeyboardSettings): String =
        settings.webSearch.braveApiKey.ifBlank { BuildConfig.BRAVE_API_KEY }

    /** The user's Tavily key; there is no built-in one (#439). */
    fun tavily(settings: KeyboardSettings): String = settings.webSearch.tavilyApiKey

    /**
     * Which service the web and image search tools ask, or null when none is
     * set up. A named SearXNG instance wins, then a Tavily key, then Brave:
     * the more deliberate the setup, the earlier it comes. Tavily goes before
     * Brave because only the user can have put its key there, while a Brave
     * key may be the one this build ships.
     */
    fun searchBackend(settings: KeyboardSettings): SearchBackend? = when {
        settings.selfHosted.searxUrl.isNotBlank() -> SearchBackend.SEARXNG
        tavily(settings).isNotBlank() -> SearchBackend.TAVILY
        brave(settings).isNotBlank() -> SearchBackend.BRAVE
        else -> null
    }

    /**
     * Whether the web/image search tools have a usable Brave key. Delegates to
     * `hasSearchKey`, which is where the toolbar and the settings screens ask
     * the same question — one answer, so a tool can never be on the bar while
     * its panel says the key is missing.
     */
    fun hasSearchProvider(settings: KeyboardSettings): Boolean = hasSearchKey(settings)

    fun translate(settings: KeyboardSettings): String =
        settings.translateApiKey.ifBlank { BuildConfig.TRANSLATE_API_KEY }

    fun unsplash(settings: KeyboardSettings): String =
        settings.photoBackground.unsplashApiKey.ifBlank { BuildConfig.UNSPLASH_API_KEY }

    fun pexels(settings: KeyboardSettings): String =
        settings.photoBackground.pexelsApiKey.ifBlank { BuildConfig.PEXELS_API_KEY }

    /** Which background-photo providers can actually serve requests. */
    fun photoSources(settings: KeyboardSettings): List<PhotoSource> = buildList {
        if (unsplash(settings).isNotBlank()) add(PhotoSource.UNSPLASH)
        if (pexels(settings).isNotBlank()) add(PhotoSource.PEXELS)
        // Alongside the keyed two, not instead of them — see [gifSources].
        if (BuildConfig.ENABLE_FDROID) add(PhotoSource.COMMONS)
    }

    /** Resolves each provider's key for the photo client's dispatch. */
    fun photoKeys(settings: KeyboardSettings): PhotoSearchClient.Keys =
        PhotoSearchClient.Keys { source ->
            when (source) {
                PhotoSource.UNSPLASH -> unsplash(settings)
                PhotoSource.PEXELS -> pexels(settings)
                // Commons is anonymous, so this slot carries the endpoint
                // instead of a key: it is the per-source string the dispatch
                // already threads through, and a second parallel channel for
                // one provider would be worse than one documented reuse.
                PhotoSource.COMMONS -> settings.selfHosted.commonsUrl
            }
        }

    /** For the settings screens: whether the build ships its own key. */
    val builtInKlipy: Boolean get() = BuildConfig.KLIPY_API_KEY.isNotBlank()
    val builtInGiphy: Boolean get() = BuildConfig.GIPHY_API_KEY.isNotBlank()
    val builtInBrave: Boolean get() = BuildConfig.BRAVE_API_KEY.isNotBlank()
    val builtInTranslate: Boolean get() = BuildConfig.TRANSLATE_API_KEY.isNotBlank()
    val builtInUnsplash: Boolean get() = BuildConfig.UNSPLASH_API_KEY.isNotBlank()
    val builtInPexels: Boolean get() = BuildConfig.PEXELS_API_KEY.isNotBlank()
}

/** The services behind the web and image search tools; see [ToolApiKeys.searchBackend]. */
enum class SearchBackend { SEARXNG, TAVILY, BRAVE }

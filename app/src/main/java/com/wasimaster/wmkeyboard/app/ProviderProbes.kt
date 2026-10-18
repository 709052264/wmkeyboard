package com.wasimaster.wmkeyboard.app

import android.content.Context
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.GifContentFilter
import com.wasimaster.wmkeyboard.core.tools.BraveSearchClient
import com.wasimaster.wmkeyboard.core.tools.CommonsClient
import com.wasimaster.wmkeyboard.core.tools.PhotoQuery
import com.wasimaster.wmkeyboard.core.tools.PhotoSearchClient
import com.wasimaster.wmkeyboard.core.tools.PhotoSource
import com.wasimaster.wmkeyboard.core.tools.SearxClient
import com.wasimaster.wmkeyboard.core.tools.TavilySearchClient
import com.wasimaster.wmkeyboard.core.tools.DeepLClient
import com.wasimaster.wmkeyboard.core.tools.LibreTranslateClient
import com.wasimaster.wmkeyboard.core.tools.LingvaClient
import com.wasimaster.wmkeyboard.core.tools.MozhiClient
import com.wasimaster.wmkeyboard.core.tools.RensClient
import com.wasimaster.wmkeyboard.core.tools.SimplyTranslateClient
import com.wasimaster.wmkeyboard.core.tools.TransliteClient
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.tools.TranslateServerClient
import com.wasimaster.wmkeyboard.core.tools.GiphyClient
import com.wasimaster.wmkeyboard.core.tools.KlipyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Test button of the provider editor: the cheapest request each kind of
 * provider answers, made with what is in the editor now rather than what is
 * saved. Null for a kind with nothing worth testing (files on the phone, a
 * service that needs no setup).
 */
internal object ProviderProbes {

    @Suppress("UNUSED_PARAMETER")
    fun probe(
        context: Context,
        tool: ProviderTool,
        type: ProviderType,
        credential: ProviderCredential,
    ): (suspend () -> Unit)? {
        // The key the provider would be asked with: the typed one, else the build's.
        val key = credential.apiKey.ifBlank { type.builtInKey }
        val sticker = tool == ProviderTool.STICKER
        val call: (() -> Unit)? = when (type.id) {
            ProviderTypes.KLIPY.id -> {
                { KlipyClient.search(PROBE_WORD, key, sticker, GifContentFilter.HIGH, limit = 1) }
            }
            ProviderTypes.GIPHY.id -> {
                { GiphyClient.search(PROBE_WORD, key, sticker, GifContentFilter.HIGH, limit = 1) }
            }
            ProviderTypes.COMMONS.id -> {
                { CommonsClient.searchGifs(PROBE_WORD, limit = 1, endpoint = credential.url) }
            }
            ProviderTypes.GOOGLE_TRANSLATE.id -> {
                { TranslateClient.translate(text = PROBE_WORD, targetLang = PROBE_TARGET, apiKey = key, sourceLang = PROBE_SOURCE) }
            }
            ProviderTypes.DEEPL.id -> {
                {
                    DeepLClient.translate(
                        text = PROBE_WORD, target = PROBE_TARGET, apiKey = key, endpoint = credential.url, source = PROBE_SOURCE,
                    )
                }
            }
            ProviderTypes.LIBRETRANSLATE.id -> {
                {
                    LibreTranslateClient.translate(
                        text = PROBE_WORD, target = PROBE_TARGET, endpoint = credential.url, apiKey = key, source = PROBE_SOURCE,
                    )
                }
            }
            ProviderTypes.MOZHI.id -> {
                {
                    MozhiClient.translate(
                        text = PROBE_WORD, target = PROBE_TARGET, endpoint = credential.url, engine = credential.model, source = PROBE_SOURCE,
                    )
                }
            }
            ProviderTypes.SIMPLYTRANSLATE.id -> {
                {
                    SimplyTranslateClient.translate(
                        text = PROBE_WORD, target = PROBE_TARGET, endpoint = credential.url, engine = credential.model, source = PROBE_SOURCE,
                    )
                }
            }
            ProviderTypes.LINGVA.id -> {
                { LingvaClient.translate(text = PROBE_WORD, target = PROBE_TARGET, endpoint = credential.url, source = PROBE_SOURCE) }
            }
            ProviderTypes.TRANSLITE.id -> {
                { TransliteClient.translate(text = PROBE_WORD, target = PROBE_TARGET, endpoint = credential.url, source = PROBE_SOURCE) }
            }
            // Rens has no request that costs nothing but a photo; its page having the form is the check.
            ProviderTypes.RENS.id -> {
                { RensClient.ping(credential.url) }
            }
            ProviderTypes.TRANSLATE_SERVER.id -> {
                {
                    TranslateServerClient.translate(
                        text = PROBE_WORD,
                        target = PROBE_TARGET,
                        url = credential.url,
                        model = credential.model,
                        apiKey = key,
                        source = PROBE_SOURCE,
                        isActive = { true },
                    )
                }
            }
            ProviderTypes.SEARXNG.id -> {
                { SearxClient.webSearch(PROBE_WORD, credential.url, 1, true) }
            }
            ProviderTypes.TAVILY.id -> {
                { TavilySearchClient.webSearch(PROBE_WORD, key, 1, true) }
            }
            ProviderTypes.BRAVE.id -> {
                { BraveSearchClient.webSearch(PROBE_WORD, key, 1, true) }
            }
            else -> null
        }
        if (call != null) return { withContext(Dispatchers.IO) { call() } }
        // The photo services page through their own client, which suspends.
        val photo = when (type.id) {
            ProviderTypes.UNSPLASH.id -> PhotoSource.UNSPLASH
            ProviderTypes.PEXELS.id -> PhotoSource.PEXELS
            else -> return null
        }
        return { PhotoSearchClient.page(photo, PhotoQuery(text = PROBE_WORD, perPage = 1), key) }
    }

    /** A word every provider has something for. */
    private const val PROBE_WORD = "hello"
    private const val PROBE_SOURCE = "en"
    private const val PROBE_TARGET = "es"
}

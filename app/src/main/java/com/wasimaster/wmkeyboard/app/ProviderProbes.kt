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
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.tools.TranslateServerClient
import com.wasimaster.wmkeyboard.core.tools.GiphyClient
import com.wasimaster.wmkeyboard.core.tools.KlipyClient
import com.wasimaster.wmkeyboard.app.homeassistant.HaSignIn
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttp
import com.wasimaster.wmkeyboard.core.smarthome.hue.HueApi
import com.wasimaster.wmkeyboard.core.smarthome.hue.HueUrl
import com.wasimaster.wmkeyboard.core.smarthome.smartthings.SmartThingsBackend
import com.wasimaster.wmkeyboard.ime.homeassistant.HomeBackends
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
        homeProbe(context, type, credential)?.let { return it }
        // The photo services page through their own client, which suspends.
        val photo = when (type.id) {
            ProviderTypes.UNSPLASH.id -> PhotoSource.UNSPLASH
            ProviderTypes.PEXELS.id -> PhotoSource.PEXELS
            else -> return null
        }
        return { PhotoSearchClient.page(photo, PhotoQuery(text = PROBE_WORD, perPage = 1), key) }
    }

    private fun probe(block: suspend () -> Unit): suspend () -> Unit = block

    /** The cheapest request each home answers with the key it holds; null for a home with nothing to ask yet. */
    private fun homeProbe(context: Context, type: ProviderType, credential: ProviderCredential): (suspend () -> Unit)? = when (type.id) {
        ProviderTypes.HOME_ASSISTANT.id -> {
            if (credential.apiKey.isBlank() && credential.extra[HomeKeys.ACCESS_TOKEN].isNullOrBlank()) {
                null
            } else {
                probe { HaSignIn.test(context, credential).getOrThrow() }
            }
        }
        ProviderTypes.OPENHAB.id -> {
            val base = credential.url.trim().trimEnd('/').let { if (it.contains("://")) it else "http://$it" }
            probe { HomeBackends.http(NetSource.OPENHAB).get("$base/rest/", HomeHttp.bearer(credential.apiKey)).require() }
        }
        ProviderTypes.HUE.id -> {
            val base = HueUrl.base(credential.url)
            if (base == null || credential.apiKey.isBlank()) {
                null
            } else {
                probe { HueApi(HomeBackends.hueHttp(credential.extra[HomeKeys.CERT_SHA256]), { base }, { credential.apiKey }).bridge() ?: error("no bridge") }
            }
        }
        ProviderTypes.SMARTTHINGS.id -> {
            val token = credential.apiKey.ifBlank { credential.extra[HomeKeys.ACCESS_TOKEN].orEmpty() }
            if (token.isBlank()) {
                null
            } else {
                probe { HomeBackends.http(NetSource.SMARTTHINGS).get("${SmartThingsBackend.API}/locations", HomeHttp.bearer(token)).require() }
            }
        }
        else -> null
    }

    /** A word every provider has something for. */
    private const val PROBE_WORD = "hello"
    private const val PROBE_SOURCE = "en"
    private const val PROBE_TARGET = "es"
}

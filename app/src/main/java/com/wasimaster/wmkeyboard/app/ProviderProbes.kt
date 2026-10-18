package com.wasimaster.wmkeyboard.app

import android.content.Context
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.GifContentFilter
import com.wasimaster.wmkeyboard.core.tools.CommonsClient
import com.wasimaster.wmkeyboard.core.tools.DeepLClient
import com.wasimaster.wmkeyboard.core.tools.LibreTranslateClient
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
            else -> null
        }
        return call?.let { blocking -> { withContext(Dispatchers.IO) { blocking() } } }
    }

    /** A word every provider has something for. */
    private const val PROBE_WORD = "hello"
    private const val PROBE_SOURCE = "en"
    private const val PROBE_TARGET = "es"
}

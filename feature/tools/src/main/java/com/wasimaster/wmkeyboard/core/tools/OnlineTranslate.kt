package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import java.util.Collections

/**
 * The translate tool's online services in the order the user's setup asks for
 * them, shared by the tool's panel and the AI's `translate` tool (#698) so the
 * two never disagree about where a sentence goes.
 *
 * Blocking; call on an IO dispatcher. Throws whatever the service that
 * answered threw.
 */
object OnlineTranslate {

    /**
     * Language pairs DeepL turned down this process, as "source>target", so a
     * language it does not have costs one refused request rather than one per
     * keystroke. Forgotten with the process, since DeepL keeps adding languages.
     */
    private val deeplRefusedPairs: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun translate(
        settings: KeyboardSettings,
        text: String,
        target: String,
        source: String = TranslateClient.AUTO,
        isActive: () -> Boolean = { true },
    ): Translation {
        val from = source.ifBlank { TranslateClient.AUTO }
        // The user's own server outranks every service (#435). It has no
        // fallback: whatever it answers, error included, is the answer, since
        // the point of running it is that the text goes nowhere else.
        val server = settings.translate.server
        if (server.configured) {
            return TranslateServerClient.translate(
                text = text,
                target = target,
                url = server.url,
                model = server.model,
                apiKey = server.apiKey,
                source = from,
                isActive = isActive,
            )
        }
        // DeepL first while the user has set it up (#331). A 400 is DeepL not
        // having the language, so that pair goes to the usual service instead;
        // any other failure is the answer, since it is the service they chose.
        val deepl = settings.translate.deepl
        val pair = "$from>$target"
        if (deepl.translateActive && pair !in deeplRefusedPairs) {
            try {
                return DeepLClient.translate(
                    text = text,
                    target = target,
                    apiKey = deepl.apiKey,
                    endpoint = deepl.endpoint,
                    source = from,
                )
            } catch (failure: ToolHttpException) {
                if (failure.status != HTTP_BAD_REQUEST) throw failure
                deeplRefusedPairs += pair
            }
        }
        // Configured instance wins. Without one the F-Droid build still
        // translates through the keyless public endpoint, which is a working
        // feature and not worth removing.
        return if (settings.selfHosted.libreTranslateUrl.isNotBlank()) {
            LibreTranslateClient.translate(
                text = text,
                target = target,
                endpoint = settings.selfHosted.libreTranslateUrl,
                apiKey = settings.selfHosted.libreTranslateApiKey,
                source = from,
            )
        } else {
            TranslateClient.translate(
                text = text,
                targetLang = target,
                apiKey = ToolApiKeys.translate(settings),
                sourceLang = from,
            )
        }
    }

    private const val HTTP_BAD_REQUEST = 400
}

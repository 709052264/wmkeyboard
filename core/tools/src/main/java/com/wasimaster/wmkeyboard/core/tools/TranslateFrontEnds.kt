package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * Translation through a front end (#726): a free server that passes the text
 * on to Google, DeepL, Yandex and others and hands the answer back, so the
 * service behind it sees the instance and never the phone. Four are known:
 * Mozhi, SimplyTranslate, Lingva and Translite.
 *
 * None of them has a default instance, for the reason LibreTranslate has
 * none: public instances come and go, and a baked-in one fails for everybody
 * on the day it does. The user pastes the address of one they trust.
 *
 * Every client here is blocking; call it on an IO dispatcher. Each throws on
 * failure, an empty answer included, so the provider list asks the next row.
 */

/** Mozhi (codeberg.org/aryak/mozhi): one API in front of several engines. */
object MozhiClient {

    /** What a blank engine means: the one every instance has. */
    const val DEFAULT_ENGINE = "google"

    fun translate(
        text: String,
        target: String,
        endpoint: String,
        engine: String = DEFAULT_ENGINE,
        source: String = TranslateClient.AUTO,
    ): Translation {
        val base = FrontEndUrls.base(endpoint)
        val engineId = engine.ifBlank { DEFAULT_ENGINE }
        val to = FrontEndLanguages.require(target, languages(base, engineId, "target"))
        val from = FrontEndLanguages.source(source, languages(base, engineId, "source"))
        val body = ToolHttp.postForm(
            url = "$base/api/translate",
            form = mapOf(
                "engine" to engineId,
                "from" to from,
                "to" to to,
                "text" to text.take(TranslateClient.MAX_CHARS),
            ),
            source = NetSource.TRANSLATE,
            route = "/api/translate",
        )
        return FrontEndResult.checked(parse(body), source)
    }

    /**
     * One engine's answer is an object; "all" and a comma list answer with an
     * array. Only one engine is ever asked, but the first answer that has text
     * is read either way.
     */
    internal fun parse(body: String): Translation {
        val root = Json.parseToJsonElement(body)
        val answers = if (root is JsonArray) root.map { it.jsonObject } else listOf(root.jsonObject)
        val answer = answers.firstOrNull { it.string("translated-text").isNotBlank() } ?: answers.firstOrNull()
        return Translation(
            text = answer?.string("translated-text").orEmpty(),
            detectedSource = answer?.string("detected").orEmpty(),
        )
    }

    /** `[{"Name":"English","Id":"en"}, ...]`: the codes this engine takes, which differ engine to engine. */
    internal fun parseLanguages(body: String): Set<String> =
        (Json.parseToJsonElement(body) as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("Id")?.ifBlank { null } }
            .toSet()

    private fun languages(base: String, engine: String, kind: String): Set<String> =
        FrontEndLanguages.cached("mozhi|$base|$engine|$kind") {
            parseLanguages(
                ToolHttp.get(
                    "$base/api/${kind}_languages?engine=${ToolHttp.encode(engine)}",
                    source = NetSource.TRANSLATE,
                    route = "/api/${kind}_languages",
                ),
            )
        }
}

/** SimplyTranslate (codeberg.org/ManeraKai/simplytranslate), the Go version. */
object SimplyTranslateClient {

    const val DEFAULT_ENGINE = "google"

    fun translate(
        text: String,
        target: String,
        endpoint: String,
        engine: String = DEFAULT_ENGINE,
        source: String = TranslateClient.AUTO,
    ): Translation {
        val base = FrontEndUrls.base(endpoint)
        val engineId = engine.ifBlank { DEFAULT_ENGINE }
        val to = FrontEndLanguages.require(target, languages(base, engineId, "target"))
        val from = FrontEndLanguages.source(source, languages(base, engineId, "source"))
        // An instance that does not run the engine quietly uses Google instead.
        val body = ToolHttp.postForm(
            url = "$base/api/translate",
            form = mapOf(
                "engine" to engineId,
                "from" to from,
                "to" to to,
                "text" to text.take(TranslateClient.MAX_CHARS),
            ),
            source = NetSource.TRANSLATE,
            route = "/api/translate",
        )
        return FrontEndResult.checked(parse(body), source)
    }

    /** `{"source_language":"en","translated_text":"Hola", ...}`. A broken engine answers 200 with no text. */
    internal fun parse(body: String): Translation {
        val root = Json.parseToJsonElement(body).jsonObject
        return Translation(text = root.string("translated_text"), detectedSource = root.string("source_language"))
    }

    /** `{"en":"English", ...}`. */
    internal fun parseLanguages(body: String): Set<String> =
        (Json.parseToJsonElement(body) as? JsonObject)?.keys.orEmpty()

    private fun languages(base: String, engine: String, kind: String): Set<String> =
        FrontEndLanguages.cached("simplytranslate|$base|$engine|$kind") {
            parseLanguages(
                ToolHttp.get(
                    "$base/api/${kind}_languages?engine=${ToolHttp.encode(engine)}",
                    source = NetSource.TRANSLATE,
                    route = "/api/${kind}_languages",
                ),
            )
        }
}

/**
 * Lingva (github.com/thedaviddelta/lingva-translate), a front end to Google.
 *
 * Its API is GET only, with the text in the path, so unlike the other front
 * ends here the text is part of the request URL. The route logged is the
 * fixed part alone, never the text.
 */
object LingvaClient {

    fun translate(
        text: String,
        target: String,
        endpoint: String,
        source: String = TranslateClient.AUTO,
    ): Translation {
        val base = FrontEndUrls.base(endpoint)
        val to = FrontEndLanguages.require(target, languages(base, "target"))
        val from = FrontEndLanguages.source(source, languages(base, "source"))
        val body = ToolHttp.get(
            url = translateUrl(base, from, to, text.take(TranslateClient.MAX_CHARS)),
            source = NetSource.TRANSLATE,
            route = "/api/v1",
        )
        return FrontEndResult.checked(parse(body), source)
    }

    /** A path segment: `+` would stay a plus there, so a space is `%20`. */
    internal fun translateUrl(base: String, from: String, to: String, text: String): String =
        "$base/api/v1/${segment(from)}/${segment(to)}/${segment(text)}"

    private fun segment(value: String): String = ToolHttp.encode(value).replace("+", "%20")

    /** `{"translation":"Hola","info":{"detectedSource":"en", ...}}`; `info` is often missing. */
    internal fun parse(body: String): Translation {
        val root = Json.parseToJsonElement(body).jsonObject
        val info = root["info"] as? JsonObject
        return Translation(text = root.string("translation"), detectedSource = info?.string("detectedSource").orEmpty())
    }

    /** `{"languages":[{"code":"zh_HANT","name":"Chinese (Traditional)"}, ...]}`. */
    internal fun parseLanguages(body: String): Set<String> =
        ((Json.parseToJsonElement(body) as? JsonObject)?.get("languages") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("code")?.ifBlank { null } }
            .toSet()

    private fun languages(base: String, kind: String): Set<String> =
        FrontEndLanguages.cached("lingva|$base|$kind") {
            parseLanguages(
                ToolHttp.get("$base/api/v1/languages/$kind", source = NetSource.TRANSLATE, route = "/api/v1/languages"),
            )
        }
}

/** Translite (codeberg.org/gospodin/translite), a front end to Google that answers in plain text. */
object TransliteClient {

    fun translate(
        text: String,
        target: String,
        endpoint: String,
        source: String = TranslateClient.AUTO,
    ): Translation {
        val base = FrontEndUrls.base(endpoint)
        val lists = languages(base)
        val to = FrontEndLanguages.require(target, lists.second)
        val from = FrontEndLanguages.source(source, lists.first)
        // The API reads the form as well as the query, which keeps the text out of the URL.
        val body = ToolHttp.postForm(
            url = "$base/api/translate",
            form = mapOf("sl" to from, "tl" to to, "text" to text.take(TranslateClient.MAX_CHARS)),
            source = NetSource.TRANSLATE,
            route = "/api/translate",
        )
        // Translite does not say what it detected; a source the user named is all there is.
        return FrontEndResult.checked(Translation(text = body.trim(), detectedSource = ""), source)
    }

    /** `{"sl":{"auto":"Autodetect", ...},"tl":{"en":"English", ...}}`, the source codes first. */
    internal fun parseLanguages(body: String): Pair<Set<String>, Set<String>> {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return emptySet<String>() to emptySet()
        return (root["sl"] as? JsonObject)?.keys.orEmpty() to (root["tl"] as? JsonObject)?.keys.orEmpty()
    }

    // One call answers both lists; they are cached apart, so the pair is rebuilt from the two.
    private fun languages(base: String): Pair<Set<String>, Set<String>> {
        var both: Pair<Set<String>, Set<String>>? = null
        fun fetch(): Pair<Set<String>, Set<String>> = both ?: parseLanguages(
            ToolHttp.get("$base/api/get_languages", source = NetSource.TRANSLATE, route = "/api/get_languages"),
        ).also { both = it }
        val sources = FrontEndLanguages.cached("translite|$base|source") { fetch().first }
        val targets = FrontEndLanguages.cached("translite|$base|target") { fetch().second }
        return sources to targets
    }
}

/** A front end answered, but with nothing in it, or with a language it was not asked for. */
class FrontEndEmptyException : IllegalStateException("The translation came back empty")

/** The front end's engine has no code for this language, or cannot detect the source. */
class FrontEndLanguageException(code: String) : IllegalArgumentException("Not available here: $code")

internal object FrontEndResult {

    /** An empty answer is a failure, and a source the user named is what was detected. */
    fun checked(result: Translation, source: String): Translation {
        if (result.text.isBlank()) throw FrontEndEmptyException()
        val named = source.isNotBlank() && source != TranslateClient.AUTO
        return if (result.detectedSource.isBlank() && named) result.copy(detectedSource = source) else result
    }
}

internal object FrontEndUrls {

    /**
     * The instance's root from whatever was pasted: a bare host, a scheme, a
     * trailing slash, an API path, or the address bar of the web page, which
     * for Mozhi ends in `/#` and for the others carries the text in a query.
     * A scheme other than http or https is refused rather than guessed at.
     */
    fun base(endpoint: String): String {
        val raw = endpoint.trim().substringBefore('#').substringBefore('?').trim()
        require(raw.isNotEmpty()) { "no instance address" }
        val withScheme = when {
            raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("https://", ignoreCase = true) -> raw
            // A bare host is the common paste; assume TLS rather than cleartext.
            !raw.contains("://") -> "https://$raw"
            else -> throw IllegalArgumentException("unsupported scheme in instance address")
        }
        val root = withScheme.trimEnd('/')
        val api = root.indexOf("/api/", startIndex = root.indexOf("://") + 3, ignoreCase = true)
        val trimmed = if (api >= 0) root.substring(0, api) else root.removeSuffix("/api")
        return trimmed.trimEnd('/')
    }
}

/**
 * The keyboard's language codes are Google's (`zh-CN`, `he`, `pt`), and each
 * front end's engines want their own: DeepL `zh-hant`, Lingva `zh_HANT`,
 * Yandex `no`, Google `iw`. So each instance's lists are read once a process
 * and the code is matched against them. An instance that does not answer the
 * list request gets the code as it is.
 */
internal object FrontEndLanguages {

    private val lists = ConcurrentHashMap<String, Set<String>>()

    /** Codes that name the same language, best first. */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "he" to listOf("iw"),
        "iw" to listOf("he"),
        "nb" to listOf("no"),
        "no" to listOf("nb"),
        "fil" to listOf("tl"),
        "tl" to listOf("fil"),
        "jw" to listOf("jv"),
        "jv" to listOf("jw"),
        "zh-cn" to listOf("zh-hans", "zh", "zh_hans"),
        "zh-tw" to listOf("zh-hant", "zh_hant", "zh-hk"),
        "zh-hk" to listOf("zh-hant", "zh_hant", "zh-tw"),
        "pt" to listOf("pt-br", "pt-pt"),
        "en" to listOf("en-us", "en-gb"),
    )

    /** Traditional Chinese is never answered in Simplified, which is what `zh` alone means. */
    private val NO_BASE_FALLBACK = setOf("zh-tw", "zh-hk", "zh-hant")

    fun cached(key: String, fetch: () -> Set<String>): Set<String> {
        lists[key]?.let { return it }
        val fetched = try {
            fetch()
        } catch (failure: ToolHttpException) {
            // The instance answered, without a list: an older version. Use the codes as they are.
            emptySet()
        } catch (failure: IllegalArgumentException) {
            // Not JSON, or not the shape expected (a SerializationException is one of these).
            emptySet()
        }
        // Not reached at all (no connection) throws past here, and is asked again next time.
        lists[key] = fetched
        return fetched
    }

    /** [code] as the engine spells it, or null when the engine does not have it. Empty [available] means unknown. */
    fun match(code: String, available: Collection<String>): String? {
        if (available.isEmpty()) return code
        val wanted = code.lowercase(Locale.ROOT)
        fun find(candidate: String) = available.firstOrNull { it.lowercase(Locale.ROOT) == candidate }
        find(wanted)?.let { return it }
        ALIASES[wanted]?.firstNotNullOfOrNull(::find)?.let { return it }
        if (wanted in NO_BASE_FALLBACK) return null
        val base = wanted.substringBefore('-').substringBefore('_')
        find(base)?.let { return it }
        ALIASES[base]?.firstNotNullOfOrNull(::find)?.let { return it }
        return available.firstOrNull { it.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_') == base }
    }

    fun require(code: String, available: Collection<String>): String =
        match(code, available) ?: throw FrontEndLanguageException(code)

    /** "auto" when the engine detects the source, else the engine's code for the one the user named. */
    fun source(source: String, available: Collection<String>): String {
        if (source.isNotBlank() && source != TranslateClient.AUTO) return require(source, available)
        // DeepL and Reverso behind Mozhi cannot detect: hand the text to the next provider.
        if (available.isNotEmpty() && available.none { it.equals(TranslateClient.AUTO, ignoreCase = true) }) {
            throw FrontEndLanguageException(TranslateClient.AUTO)
        }
        return TranslateClient.AUTO
    }
}

private fun JsonObject.string(name: String): String =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

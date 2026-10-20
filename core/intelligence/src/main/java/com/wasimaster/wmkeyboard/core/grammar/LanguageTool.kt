package com.wasimaster.wmkeyboard.core.grammar

import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.GrammarDialect
import com.wasimaster.wmkeyboard.core.settings.GrammarLintKind
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ToolHttpException
import com.wasimaster.wmkeyboard.intelligence.R
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One LanguageTool server as a row of the grammar tool's list set it up.
 *
 * [url] blank is the public API, or the Premium one when [username] and
 * [apiKey] are both there: LanguageTool serves Premium accounts from a host
 * of its own. [picky] asks for the extra style rules (`level=picky`), and
 * [motherTongue] is the writer's first language, which turns on the
 * false-friend rules.
 */
data class LanguageToolServer(
    val url: String = "",
    val username: String = "",
    val apiKey: String = "",
    val picky: Boolean = false,
    val motherTongue: String = "",
) {
    val premium: Boolean get() = username.isNotBlank() && apiKey.isNotBlank()

    /** Where the requests go, without a trailing slash or the `/v2` part. */
    val base: String get() = LanguageToolClient.baseUrl(url, premium)

    /** The free public API, which publishes limits this client keeps to. */
    val publicApi: Boolean
        get() = !premium && runCatching { URL(base).host.lowercase(Locale.ROOT) }.getOrNull() == LanguageToolClient.PUBLIC_HOST

    /** The most text one request carries, in UTF-8 bytes. */
    val maxRequestBytes: Int
        get() = if (publicApi) LanguageToolClient.PUBLIC_MAX_BYTES else LanguageToolClient.OTHER_MAX_BYTES

    companion object {
        /** The [com.wasimaster.wmkeyboard.core.providers.ProviderCredential.extra] key for [picky]. */
        const val PICKY = "picky"

        /** The extra key for [motherTongue]. */
        const val MOTHER_TONGUE = "mother_tongue"
    }
}

/** What one check found, with the language code LanguageTool checked it as. */
data class LanguageToolResult(val lints: List<GrammarLint>, val language: String)

/**
 * LanguageTool's HTTP API: `POST /v2/check` and `GET /v2/languages`.
 *
 * Running LanguageTool on the phone is not an option (its rules are written
 * for `java.util.regex`, which Android's ICU regex does not match, and English
 * alone wants a few hundred megabytes of heap), so the grammar tool reaches it
 * as a server: the public one at `api.languagetool.org`, a Premium account, or
 * one the user runs (the server is LGPL and ships as a jar and a Docker image).
 *
 * Blocking; call on an IO dispatcher. [LanguageToolChecker] does that, and
 * splits and paces what it sends.
 */
object LanguageToolClient {

    const val PUBLIC_SERVER = "https://api.languagetool.org"
    const val PUBLIC_HOST = "api.languagetool.org"
    const val PREMIUM_SERVER = "https://api.languagetoolplus.com"

    /** Lets the server work out the language itself. */
    const val AUTO = "auto"

    /*
     * The public API's limits (dev.languagetool.org/public-http-api): 20
     * requests a minute, 75 KB of text a minute, 20 KB a request. A request is
     * kept under 18 KB, which stays under the limit whether it is counted in
     * bytes or in characters.
     */
    internal const val PUBLIC_MAX_BYTES = 18_000
    internal const val PUBLIC_REQUESTS_PER_MINUTE = 20
    internal const val PUBLIC_BYTES_PER_MINUTE = 75_000

    /** A request's text anywhere else: a long field in one go, and still a bounded request. */
    internal const val OTHER_MAX_BYTES = 60_000

    /** Fix chips a card shows at most; a spelling match can carry thirty. */
    private const val MAX_FIXES = 5

    private const val CHECK_PATH = "/v2/check"
    private const val LANGUAGES_PATH = "/v2/languages"
    private const val TIMEOUT_MS = 20_000

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * One request to `/v2/check`. Throws [ToolHttpException] on a failure,
     * worded for this service where the status says something particular.
     */
    fun check(server: LanguageToolServer, text: String, language: String): LanguageToolResult {
        val body = try {
            ToolHttp.postForm(
                url = server.base + CHECK_PATH,
                form = form(server, text, language),
                timeoutMs = TIMEOUT_MS,
                source = NetSource.LANGUAGETOOL,
                route = CHECK_PATH,
                headers = mapOf("Accept" to "application/json"),
                wantHeaders = setOf(RETRY_AFTER),
            )
        } catch (e: ToolHttpException) {
            throw reworded(e)
        }
        return parse(body, text)
    }

    /** The languages [server] checks, by their long codes (`en-US`, `de-DE`, `ar`). */
    fun languages(server: LanguageToolServer): List<String> {
        val body = ToolHttp.get(
            url = server.base + LANGUAGES_PATH,
            timeoutMs = TIMEOUT_MS,
            headers = mapOf("Accept" to "application/json"),
            source = NetSource.LANGUAGETOOL,
            route = LANGUAGES_PATH,
        )
        return parseLanguages(body)
    }

    /** The response header that says how long to wait after a 429. */
    const val RETRY_AFTER = "Retry-After"

    /** The fields of one check. A username and key go together or not at all. */
    internal fun form(server: LanguageToolServer, text: String, language: String): Map<String, String> = buildMap {
        put("text", text)
        put("language", language.ifBlank { AUTO })
        if (server.premium) {
            put("username", server.username.trim())
            put("apiKey", server.apiKey.trim())
        }
        if (server.picky) put("level", "picky")
        server.motherTongue.trim().takeIf { it.isNotEmpty() }?.let { put("motherTongue", it) }
    }

    /**
     * The root every path hangs off, from whatever the user pasted: a bare
     * host, a trailing slash, the `/v2` or a whole `/v2/check` path on the
     * end. Blank is the public API, or the Premium one for a Premium account.
     * A scheme other than http/https is refused rather than guessed at, the
     * way the translation clients refuse it.
     */
    fun baseUrl(url: String, premium: Boolean = false): String {
        val raw = url.trim()
        if (raw.isEmpty()) return if (premium) PREMIUM_SERVER else PUBLIC_SERVER
        val withScheme = when {
            raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("https://", ignoreCase = true) -> raw
            !raw.contains("://") -> "https://$raw"
            else -> throw IllegalArgumentException("unsupported scheme in LanguageTool URL")
        }
        var base = withScheme.trimEnd('/')
        for (suffix in listOf(CHECK_PATH, LANGUAGES_PATH, "/v2")) {
            if (base.endsWith(suffix, ignoreCase = true)) {
                base = base.dropLast(suffix.length)
                break
            }
        }
        return base.trimEnd('/')
    }

    /**
     * LanguageTool answers a refusal with a line of plain text ("Error: …"),
     * which [ToolHttp] cannot read as an API message, so the statuses that
     * mean something here get words of their own.
     */
    private fun reworded(e: ToolHttpException): ToolHttpException {
        val res = when (e.status) {
            400 -> R.string.core_intel_languagetool_error_request
            401, 403 -> R.string.core_intel_languagetool_error_account
            413 -> R.string.core_intel_languagetool_error_too_long
            429 -> R.string.core_intel_languagetool_error_rate_limit
            else -> return e
        }
        return ToolHttpException(res, e.status, headers = e.headers)
    }

    /**
     * The matches as the grammar panel's lints. Offsets are UTF-16 indices
     * into [text], which is how LanguageTool, a Java server, counts them (an
     * emoji before a mistake moves it by two). A match that does not fit the
     * text is dropped rather than trusted.
     */
    internal fun parse(body: String, text: String): LanguageToolResult {
        val root = json.parseToJsonElement(body).jsonObject
        val language = (root["language"] as? JsonObject)?.get("code")?.jsonPrimitive?.content.orEmpty()
        val matches = root["matches"] as? JsonArray ?: return LanguageToolResult(emptyList(), language)
        val lints = matches.mapNotNull { element ->
            val match = element as? JsonObject ?: return@mapNotNull null
            val start = match["offset"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val length = match["length"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val end = start + length
            if (start < 0 || length < 0 || end > text.length) return@mapNotNull null
            val rule = match["rule"] as? JsonObject
            val category = (rule?.get("category") as? JsonObject)?.get("id")?.jsonPrimitive?.content.orEmpty()
            val issueType = rule?.get("issueType")?.jsonPrimitive?.content.orEmpty()
            val ruleId = rule?.get("id")?.jsonPrimitive?.content.orEmpty()
            val fixes = (match["replacements"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.get("value")?.jsonPrimitive?.content }
                .distinct()
                .take(MAX_FIXES)
                // An empty replacement is LanguageTool's way to say "delete it".
                .map { if (it.isEmpty()) GrammarFix("remove") else GrammarFix("replace", it) }
            GrammarLint(
                start = start,
                end = end,
                original = text.substring(start, end),
                kind = kindFor(category, issueType, ruleId).name,
                message = match["message"]?.jsonPrimitive?.content.orEmpty(),
                suggestions = fixes,
                title = match["shortMessage"]?.jsonPrimitive?.content.orEmpty(),
                rule = ruleId,
            )
        }
        return LanguageToolResult(lints.distinct(), language)
    }

    /** `[{"name":"German (Germany)","code":"de","longCode":"de-DE"}, …]` → the long codes. */
    internal fun parseLanguages(body: String): List<String> =
        (json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.let { it["longCode"] ?: it["code"] }?.jsonPrimitive?.content
        }.distinct()

    /**
     * Which of the panel's kinds a match is, so the filter, the colours and
     * "Fix all" treat it like one of Harper's. LanguageTool's category says
     * most; its issue type (the Localization Quality Issue Type) fills in
     * where the category is a broad one. A spelling match is told from a typo
     * by its rule: the spellers are the Morfologik and Hunspell rules.
     */
    internal fun kindFor(category: String, issueType: String, ruleId: String): GrammarLintKind {
        val speller = ruleId.contains("MORFOLOGIK", ignoreCase = true) ||
            ruleId.contains("HUNSPELL", ignoreCase = true) ||
            ruleId.contains("SPELLER", ignoreCase = true) ||
            ruleId.contains("SPELLING", ignoreCase = true)
        return when (category.uppercase(Locale.ROOT)) {
            "TYPOS" -> if (speller) GrammarLintKind.SPELLING else GrammarLintKind.TYPO
            "CASING" -> GrammarLintKind.CAPITALIZATION
            "PUNCTUATION" -> GrammarLintKind.PUNCTUATION
            "TYPOGRAPHY" -> GrammarLintKind.FORMATTING
            "GRAMMAR" -> GrammarLintKind.GRAMMAR
            "CONFUSED_WORDS" -> GrammarLintKind.USAGE
            "COMPOUNDING" -> GrammarLintKind.BOUNDARY_ERROR
            "REDUNDANCY" -> GrammarLintKind.REDUNDANCY
            "REPETITIONS", "REPETITIONS_STYLE" -> GrammarLintKind.REPETITION
            "STYLE", "PLAIN_ENGLISH", "WIKIPEDIA" -> GrammarLintKind.STYLE
            "FALSE_FRIENDS", "GENDER_NEUTRALITY" -> GrammarLintKind.WORD_CHOICE
            "COLLOQUIALISMS", "REGIONALISMS" -> GrammarLintKind.REGIONALISM
            "NONSTANDARD_PHRASES" -> GrammarLintKind.NONSTANDARD
            "SEMANTICS" -> GrammarLintKind.READABILITY
            else -> when (issueType) {
                "misspelling" -> if (speller) GrammarLintKind.SPELLING else GrammarLintKind.TYPO
                "grammar" -> GrammarLintKind.GRAMMAR
                "typographical", "whitespace", "formatting" -> GrammarLintKind.FORMATTING
                "duplication" -> GrammarLintKind.REPETITION
                "style", "register" -> GrammarLintKind.STYLE
                "locale-violation" -> GrammarLintKind.REGIONALISM
                "terminology" -> GrammarLintKind.WORD_CHOICE
                else -> GrammarLintKind.MISCELLANEOUS
            }
        }
    }
}

/**
 * The keyboard's language as one of LanguageTool's codes.
 *
 * A bare code is not always the useful one: plain `de`, `en` and `pt` check
 * grammar but no spelling, so a language with variants gets its main one.
 * English follows the grammar tool's dialect, the same choice Harper reads.
 * A language LanguageTool does not have goes as `auto`, and the server works
 * out what the text is.
 */
object LanguageToolLanguages {

    /** What `GET /v2/languages` on the public API listed on 2026-10-20. */
    val KNOWN: Set<String> = setOf(
        "ar", "ast-ES", "be-BY", "br-FR", "ca-ES", "ca-ES-balear", "ca-ES-valencia", "crh-UA", "da-DK",
        "de", "de-AT", "de-CH", "de-DE", "de-LU", "el-GR", "en", "en-AU", "en-CA", "en-GB", "en-NZ",
        "en-US", "en-ZA", "eo", "es", "es-AR", "es-ES", "fa", "fa-IR", "fr", "fr-BE", "fr-CA", "fr-CH",
        "fr-FR", "ga-IE", "gl-ES", "it", "it-IT", "ja-JP", "km-KH", "nb", "nl", "nl-BE", "nl-NL", "no",
        "pl-PL", "pt", "pt-AO", "pt-BR", "pt-MZ", "pt-PT", "ro-RO", "ru-RU", "sk-SK", "sl-SI", "sv",
        "sv-SE", "ta-IN", "tl-PH", "uk-UA", "zh-CN",
    )

    /** The variant a bare code goes as, where the bare code would skip spelling. */
    private val MAIN_VARIANT = mapOf(
        "de" to "de-DE",
        "pt" to "pt-PT",
        "ca" to "ca-ES",
        "nl" to "nl-NL",
        "zh" to "zh-CN",
        // Filipino: LanguageTool names it by Tagalog.
        "fil" to "tl-PH",
    )

    private val byLowerCase: Map<String, String> = KNOWN.associateBy { it.lowercase(Locale.ROOT) }

    fun codeFor(localeTag: String, dialect: GrammarDialect = GrammarDialect.AMERICAN): String {
        val parts = localeTag.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
        val language = parts.firstOrNull()?.lowercase(Locale.ROOT) ?: return LanguageToolClient.AUTO
        if (language == "en") return englishFor(dialect)
        val region = parts.drop(1).firstOrNull { it.length == 2 && it.all(Char::isLetter) }?.uppercase(Locale.ROOT)
        if (region != null) byLowerCase["$language-$region".lowercase(Locale.ROOT)]?.let { return it }
        MAIN_VARIANT[language]?.let { return it }
        byLowerCase[language]?.let { return it }
        return KNOWN.sorted().firstOrNull { it.startsWith("$language-", ignoreCase = true) } ?: LanguageToolClient.AUTO
    }

    private fun englishFor(dialect: GrammarDialect): String = when (dialect) {
        GrammarDialect.AMERICAN -> "en-US"
        GrammarDialect.BRITISH -> "en-GB"
        GrammarDialect.CANADIAN -> "en-CA"
        GrammarDialect.AUSTRALIAN -> "en-AU"
    }
}

/**
 * Where a long text is cut into requests: whole paragraphs packed up to the
 * limit, a paragraph too long for one request at its last sentence end, then
 * at a space, and only past all of that mid-word (never inside a surrogate
 * pair). Each piece keeps its offset, so a match comes back to the right place.
 */
internal object LanguageToolChunks {

    data class Chunk(val start: Int, val text: String)

    private val SENTENCE_ENDS = setOf('.', '!', '?', '…', '。', '！', '？', '۔', '।')

    fun split(text: String, maxBytes: Int): List<Chunk> {
        require(maxBytes >= 4) { "a chunk must hold at least one character" }
        val chunks = ArrayList<Chunk>()
        var from = 0
        while (from < text.length) {
            val end = cut(text, from, maxBytes)
            chunks += Chunk(from, text.substring(from, end))
            from = end
        }
        return chunks
    }

    /** UTF-8 bytes of [c]; a surrogate counts half of its pair's four. */
    private fun bytes(c: Char): Int = when {
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        c.isSurrogate() -> 2
        else -> 3
    }

    /** The end of the piece that starts at [from]. */
    private fun cut(text: String, from: Int, maxBytes: Int): Int {
        var size = 0
        var limit = from
        while (limit < text.length) {
            val next = bytes(text[limit])
            if (size + next > maxBytes) break
            size += next
            limit++
        }
        if (limit >= text.length) return text.length
        // Never between the halves of an emoji.
        if (limit > from && text[limit - 1].isHighSurrogate()) limit--
        if (limit <= from) return (from + if (text[from].isHighSurrogate()) 2 else 1).coerceAtMost(text.length)
        lastBreak(from, limit) { text[it] == '\n' }?.let { return it }
        lastBreak(from, limit) { i -> text[i].isWhitespace() && text[i - 1] in SENTENCE_ENDS }?.let { return it }
        lastBreak(from, limit) { text[it].isWhitespace() }?.let { return it }
        return limit
    }

    /** One past the last index in `(from, limit)` that [at] accepts, or null. */
    private inline fun lastBreak(from: Int, limit: Int, at: (Int) -> Boolean): Int? {
        var i = limit - 1
        while (i > from) {
            if (at(i)) return i + 1
            i--
        }
        return null
    }
}

/**
 * A sliding minute of what one server has been sent, so the next request
 * waits instead of being refused: the public API counts requests and bytes
 * per minute and answers 429 past either.
 */
internal class RateWindow(
    private val requests: Int,
    private val bytes: Long,
    private val windowMs: Long = 60_000,
) {
    private val sent = ArrayDeque<Pair<Long, Int>>()

    /** How long to wait before sending [size] more bytes at [now]; 0 to send at once. */
    @Synchronized
    fun waitMs(now: Long, size: Int): Long {
        while (sent.isNotEmpty() && now - sent.first().first >= windowMs) sent.removeFirst()
        var count = sent.size
        var total = sent.sumOf { it.second.toLong() }
        if (count < requests && total + size <= bytes) return 0
        for ((at, b) in sent) {
            count--
            total -= b
            if (count < requests && total + size <= bytes) return (at + windowMs - now).coerceAtLeast(1)
        }
        return windowMs
    }

    @Synchronized
    fun record(now: Long, size: Int) {
        sent.addLast(now to size)
    }
}

/**
 * Checks a field's text against a LanguageTool server: cut into requests the
 * server takes ([LanguageToolChunks]), paced to the public API's minute
 * ([RateWindow]), retried after a 429, and each piece's answer remembered, so
 * an edit to one paragraph of a long note sends that paragraph's piece again
 * and not the rest.
 *
 * Suspends while it waits; a newer check cancels it, which is what keeps a
 * burst of typing to one request.
 */
object LanguageToolChecker {

    /** The longest this waits for the public API's minute before saying it is busy. */
    internal const val MAX_WAIT_MS = 15_000L

    /** Waits after a 429 with no `Retry-After`, then gives up. */
    private val BACKOFF_MS = longArrayOf(2_000, 5_000)

    /** Pieces remembered, across every server and language. */
    private const val CACHE_SIZE = 48

    /** Monotonic milliseconds; a test sets its own. */
    @Volatile
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** The request itself; a test puts a recorded answer here. */
    @Volatile
    internal var transport: (LanguageToolServer, String, String) -> LanguageToolResult = LanguageToolClient::check

    /** How a wait is spent; a test makes it instant. */
    @Volatile
    internal var sleep: suspend (Long) -> Unit = { delay(it) }

    private val windows = HashMap<String, RateWindow>()

    private val cache = object : LinkedHashMap<String, LanguageToolResult>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LanguageToolResult>?) = size > CACHE_SIZE
    }

    /** Forgets every remembered answer and every minute: after the user edits a server. */
    fun reset() {
        synchronized(cache) { cache.clear() }
        synchronized(windows) { windows.clear() }
    }

    suspend fun check(text: String, language: String, server: LanguageToolServer): LanguageToolResult {
        if (text.isBlank()) return LanguageToolResult(emptyList(), language)
        val lints = ArrayList<GrammarLint>()
        var checkedAs = ""
        for (chunk in LanguageToolChunks.split(text, server.maxRequestBytes)) {
            if (chunk.text.isBlank()) continue
            val answer = piece(chunk.text, language, server)
            if (checkedAs.isEmpty()) checkedAs = answer.language
            answer.lints.mapTo(lints) { it.copy(start = it.start + chunk.start, end = it.end + chunk.start) }
        }
        return LanguageToolResult(lints, checkedAs.ifEmpty { language })
    }

    private suspend fun piece(text: String, language: String, server: LanguageToolServer): LanguageToolResult {
        val key = listOf(server.base, server.premium, server.picky, server.motherTongue, language, text).joinToString("\u0000")
        synchronized(cache) { cache[key] }?.let { return it }
        val size = text.toByteArray(Charsets.UTF_8).size
        var attempt = 0
        while (true) {
            pace(server, size)
            try {
                val answer = withContext(Dispatchers.IO) { transport(server, text, language) }
                synchronized(cache) { cache[key] = answer }
                return answer
            } catch (e: CancellationException) {
                throw e
            } catch (e: ToolHttpException) {
                if (e.status != HTTP_TOO_MANY_REQUESTS || attempt >= BACKOFF_MS.size) throw e
                val wait = retryAfterMs(e.headers[LanguageToolClient.RETRY_AFTER]) ?: BACKOFF_MS[attempt]
                if (wait > MAX_WAIT_MS) throw e
                attempt++
                sleep(wait)
            }
        }
    }

    /** Waits for room in the public API's minute; other servers set no limit to keep to. */
    private suspend fun pace(server: LanguageToolServer, size: Int) {
        if (!server.publicApi) return
        val window = synchronized(windows) {
            windows.getOrPut(server.base) {
                RateWindow(LanguageToolClient.PUBLIC_REQUESTS_PER_MINUTE, LanguageToolClient.PUBLIC_BYTES_PER_MINUTE.toLong())
            }
        }
        val wait = window.waitMs(clock(), size)
        if (wait > MAX_WAIT_MS) {
            throw ToolHttpException(R.string.core_intel_languagetool_error_rate_limit, HTTP_TOO_MANY_REQUESTS)
        }
        if (wait > 0) sleep(wait)
        window.record(clock(), size)
    }

    /** `Retry-After` in seconds (the only form LanguageTool's proxy sends), as milliseconds. */
    internal fun retryAfterMs(header: String?): Long? =
        header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1_000)

    private const val HTTP_TOO_MANY_REQUESTS = 429
}

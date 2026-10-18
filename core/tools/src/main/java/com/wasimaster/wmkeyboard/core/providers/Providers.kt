package com.wasimaster.wmkeyboard.core.providers

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.tools.R
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A tool that can ask more than one service for the same thing, and so keeps
 * a list of providers the user orders, switches on and off, adds to and
 * removes from. The backup tool's locations are the model: a provider is set
 * up once, and the tool asks the ones that are on, in order.
 *
 * [id] is what the settings store: never store [name]. [modes] are the ways
 * the tool may use its list; the first is the default.
 */
enum class ProviderTool(val id: String, val modes: List<ChainMode>, @StringRes val labelRes: Int) {
    GIF("gif", listOf(ChainMode.TABS, ChainMode.MIX, ChainMode.FALLBACK), R.string.core_tools_provider_tool_gif),
    STICKER("sticker", listOf(ChainMode.TABS, ChainMode.MIX, ChainMode.FALLBACK), R.string.core_tools_provider_tool_sticker),
    TRANSLATE("translate", listOf(ChainMode.FALLBACK), R.string.core_tools_provider_tool_translate),
    DICTIONARY("dictionary", listOf(ChainMode.FALLBACK), R.string.core_tools_provider_tool_dictionary),
    SYNONYMS("synonyms", listOf(ChainMode.FALLBACK), R.string.core_tools_provider_tool_synonyms),
    VOCAB_AUTOFILL("vocab_autofill", listOf(ChainMode.FALLBACK), R.string.core_tools_provider_tool_vocab),

    /** The web and the image search tools: today one service answers both. */
    SEARCH("search", listOf(ChainMode.FALLBACK), R.string.core_tools_provider_tool_search),
    /** Always mixed: the photo grid pages through every provider at once and keeps what answered. */
    PHOTOS("photos", listOf(ChainMode.MIX), R.string.core_tools_provider_tool_photos),
    CURRENCY_FIAT("currency_fiat", listOf(ChainMode.FALLBACK), R.string.core_tools_provider_tool_currency_fiat),
    CURRENCY_CRYPTO("currency_crypto", listOf(ChainMode.FALLBACK), R.string.core_tools_provider_tool_currency_crypto),

    /** The smart home panel: every home on the list is connected at once, in list order. */
    SMART_HOME("smart_home", listOf(ChainMode.MIX), R.string.core_tools_provider_tool_smart_home),
    ;

    companion object {
        fun of(id: String): ProviderTool? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How a tool uses its list.
 *
 * [FALLBACK] asks the first provider that is on, and the next one only when
 * that one fails or knows nothing. [MIX] asks all of them at once and
 * interleaves the answers, keeping what came back when some fail. [TABS] gives
 * each provider a chip of its own, and a fetch asks the one whose chip is on.
 */
@Serializable
enum class ChainMode(@StringRes val labelRes: Int, @StringRes val descRes: Int) {
    FALLBACK(R.string.core_tools_provider_mode_fallback, R.string.core_tools_provider_mode_fallback_desc),
    MIX(R.string.core_tools_provider_mode_mix, R.string.core_tools_provider_mode_mix_desc),
    TABS(R.string.core_tools_provider_mode_tabs, R.string.core_tools_provider_mode_tabs_desc),
}

/** Whether a provider's form asks for a field, and whether it may stay blank. */
enum class Need { NONE, OPTIONAL, REQUIRED }

/**
 * What setting a provider up asks for. A [key] that is [Need.REQUIRED] can
 * still be blank when the build carries its own ([ProviderType.builtInKey]).
 */
data class CredentialSpec(
    val key: Need = Need.NONE,
    val url: Need = Need.NONE,
    val model: Boolean = false,
    /**
     * The services a front end can pass the text on to, the default first.
     * The pick is stored in [ProviderCredential.model]: like a model, it says
     * what the server should use, and a blank one means the default.
     */
    val engines: List<FrontEndEngine> = emptyList(),
) {
    val none: Boolean get() = key == Need.NONE && url == Need.NONE && !model && engines.isEmpty()

    /** The engine [stored] names, else the default; null for a kind with no engines. */
    fun engine(stored: String): FrontEndEngine? =
        engines.firstOrNull { it.id == stored.trim() } ?: engines.firstOrNull()
}

/** One service behind a translation front end. [id] is what the front end's API takes; [label] is a brand name. */
data class FrontEndEngine(val id: String, val label: String)

/**
 * One kind of provider: KLIPY, a LibreTranslate server, the vocabulary packs.
 *
 * [id] is stored, so never rename one. Ids that an older list already stored
 * (`DictionarySource.id`, `SynonymSource.id`, a `CurrencyClient.Provider`
 * name in lower case, a `GifSource` name in lower case) are kept as they were,
 * which is what lets an existing list read straight across.
 *
 * [multiInstance] types can be added more than once, each with its own
 * address: two SearXNG instances, a public LibreTranslate behind a private
 * one. The rest exist once, and their one set of credentials is shared by
 * every tool that lists them, so a KLIPY key typed under GIFs is the key
 * stickers use too.
 */
class ProviderType(
    val id: String,
    val serves: Set<ProviderTool>,
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int,
    val credential: CredentialSpec = CredentialSpec(),
    val multiInstance: Boolean = false,
    /** Works with no network: files or a model on the phone. */
    val onDevice: Boolean = false,
    /** Free software anyone can run, or open data: what the F-Droid build suggests first. */
    val freeSoftware: Boolean = false,
    /** A server the user runs themself rather than a public service. */
    val selfHosted: Boolean = false,
    /** The key this build carries, or blank. Read from [BuildConfig], never stored. */
    val builtInKey: String = "",
    /** Never interleaved with another provider's results: GIPHY's API terms, section 1. */
    val ownGrid: Boolean = false,
    /** A tool whose description differs from [descRes]: Wiktionary means one thing to the dictionary, another to synonyms. */
    private val toolDescRes: Map<ProviderTool, Int> = emptyMap(),
) {
    @StringRes
    fun descriptionRes(tool: ProviderTool): Int = toolDescRes[tool] ?: descRes

    val hasBuiltInKey: Boolean get() = builtInKey.isNotBlank()

    /** Needs nothing typed in to work: no key, no address, or a key the build has. */
    val readyOutOfTheBox: Boolean
        get() = credential.url != Need.REQUIRED && (credential.key != Need.REQUIRED || hasBuiltInKey)

    override fun toString(): String = id
}

/** Every provider type this build knows, in the order the add sheet lists them. */
object ProviderTypes {

    private val MEDIA = setOf(ProviderTool.GIF, ProviderTool.STICKER)

    val KLIPY = ProviderType(
        "klipy", MEDIA, R.string.core_tools_gif_source_klipy, R.string.core_tools_provider_klipy_desc,
        CredentialSpec(key = Need.REQUIRED), builtInKey = BuildConfig.KLIPY_API_KEY,
    )
    val GIPHY = ProviderType(
        "giphy", MEDIA, R.string.core_tools_gif_source_giphy, R.string.core_tools_provider_giphy_desc,
        CredentialSpec(key = Need.REQUIRED), builtInKey = BuildConfig.GIPHY_API_KEY, ownGrid = true,
    )

    /**
     * Wikimedia Commons, or another MediaWiki. A blank address is Commons
     * itself, which is why the one entry needs no credential at all.
     */
    val COMMONS = ProviderType(
        "commons", setOf(ProviderTool.GIF, ProviderTool.PHOTOS),
        R.string.core_tools_provider_commons, R.string.core_tools_provider_commons_desc,
        CredentialSpec(url = Need.OPTIONAL), multiInstance = true, freeSoftware = true,
    )
    val GIFCITIES = ProviderType(
        "gifcities", setOf(ProviderTool.GIF),
        R.string.core_tools_gif_source_gifcities, R.string.core_tools_provider_gifcities_desc,
    )
    val OFFLINE_GIFS = ProviderType(
        "offline_gif_packs", setOf(ProviderTool.GIF),
        R.string.core_tools_provider_offline_gifs, R.string.core_tools_provider_offline_gifs_desc,
        onDevice = true,
    )
    val STICKER_PACKS = ProviderType(
        "sticker_packs", setOf(ProviderTool.STICKER),
        R.string.core_tools_gif_source_local, R.string.core_tools_provider_sticker_packs_desc,
        onDevice = true,
    )

    val ML_KIT = ProviderType(
        "mlkit", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_mlkit, R.string.core_tools_provider_mlkit_desc,
        onDevice = true,
    )
    val TRANSLATE_SERVER = ProviderType(
        "translate_server", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_translate_server, R.string.core_tools_provider_translate_server_desc,
        CredentialSpec(key = Need.OPTIONAL, url = Need.REQUIRED, model = true),
        multiInstance = true, selfHosted = true,
    )

    /** A key, a proxy address, or both; [ProviderResolution] asks for at least one. */
    val DEEPL = ProviderType(
        "deepl", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_deepl, R.string.core_tools_provider_deepl_desc,
        CredentialSpec(key = Need.OPTIONAL, url = Need.OPTIONAL),
    )
    val LIBRETRANSLATE = ProviderType(
        "libretranslate", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_libretranslate, R.string.core_tools_provider_libretranslate_desc,
        CredentialSpec(key = Need.OPTIONAL, url = Need.REQUIRED),
        multiInstance = true, freeSoftware = true, selfHosted = true,
    )

    /*
     * Front ends (#726): free servers that pass the text on to Google, DeepL
     * and others, so those see the instance rather than the phone. Each is
     * added by the address of an instance, as often as the user likes; none
     * has a default one. Not [selfHosted]: most people use a public instance.
     */
    val MOZHI = ProviderType(
        "mozhi", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_mozhi, R.string.core_tools_provider_mozhi_desc,
        CredentialSpec(
            url = Need.REQUIRED,
            engines = listOf(
                FrontEndEngine("google", "Google"),
                FrontEndEngine("deepl", "DeepL"),
                FrontEndEngine("duckduckgo", "DuckDuckGo"),
                FrontEndEngine("yandex", "Yandex"),
                FrontEndEngine("reverso", "Reverso"),
            ),
        ),
        multiInstance = true, freeSoftware = true,
    )
    val SIMPLYTRANSLATE = ProviderType(
        "simplytranslate", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_simplytranslate, R.string.core_tools_provider_simplytranslate_desc,
        CredentialSpec(
            url = Need.REQUIRED,
            engines = listOf(
                FrontEndEngine("google", "Google"),
                FrontEndEngine("reverso", "Reverso"),
                FrontEndEngine("iciba", "iCIBA"),
            ),
        ),
        multiInstance = true, freeSoftware = true,
    )
    val LINGVA = ProviderType(
        "lingva", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_lingva, R.string.core_tools_provider_lingva_desc,
        CredentialSpec(url = Need.REQUIRED), multiInstance = true, freeSoftware = true,
    )
    val TRANSLITE = ProviderType(
        "translite", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_translite, R.string.core_tools_provider_translite_desc,
        CredentialSpec(url = Need.REQUIRED), multiInstance = true, freeSoftware = true,
    )

    /** Google's keyless endpoint, or Cloud Translation v2 once a key is set. */
    val GOOGLE_TRANSLATE = ProviderType(
        "google_translate", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_google_translate, R.string.core_tools_provider_google_translate_desc,
        CredentialSpec(key = Need.OPTIONAL), builtInKey = BuildConfig.TRANSLATE_API_KEY,
    )

    private val WORDS = setOf(ProviderTool.DICTIONARY, ProviderTool.SYNONYMS)

    val VOCAB_PACKS = ProviderType(
        "vocab_packs", WORDS,
        R.string.core_tools_dictionary_source_vocab_packs, R.string.core_tools_dictionary_source_vocab_packs_desc,
        onDevice = true,
        toolDescRes = mapOf(ProviderTool.SYNONYMS to R.string.core_tools_synonym_source_vocab_packs_desc),
    )

    /** kaikki.org's copy of Wiktionary. */
    val WIKTIONARY = ProviderType(
        "wiktionary", WORDS + ProviderTool.VOCAB_AUTOFILL,
        R.string.core_tools_dictionary_source_wiktionary, R.string.core_tools_dictionary_source_wiktionary_desc,
        freeSoftware = true,
        toolDescRes = mapOf(ProviderTool.SYNONYMS to R.string.core_tools_synonym_source_wiktionary_desc),
    )
    val WIKTIONARY_REST = ProviderType(
        "wiktionary_rest", setOf(ProviderTool.DICTIONARY, ProviderTool.VOCAB_AUTOFILL),
        R.string.core_tools_dictionary_source_wiktionary_rest, R.string.core_tools_dictionary_source_wiktionary_rest_desc,
        freeSoftware = true,
    )
    val DICTIONARY_API = ProviderType(
        "dictionary_api", WORDS + ProviderTool.VOCAB_AUTOFILL,
        R.string.core_tools_dictionary_source_dictionary_api, R.string.core_tools_dictionary_source_dictionary_api_desc,
        freeSoftware = true,
        toolDescRes = mapOf(ProviderTool.SYNONYMS to R.string.core_tools_synonym_source_dictionary_api_desc),
    )

    /** The offline dictionaries (#665): the ids are their `DictionarySource` ids. */
    val OFFLINE_WIKTIONARY = ProviderType(
        "offline_wiktionary", setOf(ProviderTool.DICTIONARY),
        R.string.core_tools_dictionary_source_offline_wiktionary, R.string.core_tools_dictionary_source_offline_wiktionary_desc,
        onDevice = true, freeSoftware = true,
    )
    val OFFLINE_WORDNET = ProviderType(
        "offline_wordnet", setOf(ProviderTool.DICTIONARY),
        R.string.core_tools_dictionary_source_offline_wordnet, R.string.core_tools_dictionary_source_offline_wordnet_desc,
        onDevice = true, freeSoftware = true,
    )
    val DATAMUSE = ProviderType(
        "datamuse", setOf(ProviderTool.SYNONYMS),
        R.string.core_tools_synonym_source_datamuse, R.string.core_tools_synonym_source_datamuse_desc,
    )
    val SIMILAR_WORDS = ProviderType(
        "similar_words", setOf(ProviderTool.SYNONYMS),
        R.string.core_tools_synonym_source_similar_words, R.string.core_tools_synonym_source_similar_words_desc,
    )

    val SEARXNG = ProviderType(
        "searxng", setOf(ProviderTool.SEARCH),
        R.string.core_tools_provider_searxng, R.string.core_tools_provider_searxng_desc,
        CredentialSpec(url = Need.REQUIRED), multiInstance = true, freeSoftware = true, selfHosted = true,
    )
    val TAVILY = ProviderType(
        "tavily", setOf(ProviderTool.SEARCH),
        R.string.core_tools_provider_tavily, R.string.core_tools_provider_tavily_desc,
        CredentialSpec(key = Need.REQUIRED),
    )
    val BRAVE = ProviderType(
        "brave", setOf(ProviderTool.SEARCH),
        R.string.core_tools_provider_brave, R.string.core_tools_provider_brave_desc,
        CredentialSpec(key = Need.REQUIRED), builtInKey = BuildConfig.BRAVE_API_KEY,
    )

    val UNSPLASH = ProviderType(
        "unsplash", setOf(ProviderTool.PHOTOS),
        R.string.core_tools_photo_source_unsplash, R.string.core_tools_provider_unsplash_desc,
        CredentialSpec(key = Need.REQUIRED), builtInKey = BuildConfig.UNSPLASH_API_KEY,
    )
    val PEXELS = ProviderType(
        "pexels", setOf(ProviderTool.PHOTOS),
        R.string.core_tools_photo_source_pexels, R.string.core_tools_provider_pexels_desc,
        CredentialSpec(key = Need.REQUIRED), builtInKey = BuildConfig.PEXELS_API_KEY,
    )

    private val FIAT = setOf(ProviderTool.CURRENCY_FIAT)
    private val COINS = setOf(ProviderTool.CURRENCY_CRYPTO)

    val ER_API = ProviderType(
        "er_api", FIAT, R.string.core_tools_provider_er_api, R.string.core_tools_provider_er_api_desc,
    )
    val FRANKFURTER = ProviderType(
        "frankfurter", FIAT, R.string.core_tools_provider_frankfurter, R.string.core_tools_provider_frankfurter_desc,
        freeSoftware = true,
    )
    val COINBASE = ProviderType(
        "coinbase", FIAT + COINS, R.string.core_tools_provider_coinbase, R.string.core_tools_provider_coinbase_desc,
    )
    val CURRENCY_API = ProviderType(
        "currency_api", FIAT + COINS, R.string.core_tools_provider_currency_api, R.string.core_tools_provider_currency_api_desc,
        freeSoftware = true,
    )
    val COINGECKO = ProviderType(
        "coingecko", COINS, R.string.core_tools_provider_coingecko, R.string.core_tools_provider_coingecko_desc,
    )

    // ---- Smart home. The ids are `HomeKind.typeId` in :core:homeassistant; keep them in step.

    private val HOMES = setOf(ProviderTool.SMART_HOME)

    /**
     * The address is the home one; the remote address, the sign-in tokens and
     * what the server said about itself live in the credential's `extra`. A
     * pasted long-lived token is the key.
     */
    val HOME_ASSISTANT = ProviderType(
        "home_assistant", HOMES, R.string.core_tools_provider_home_assistant, R.string.core_tools_provider_home_assistant_desc,
        CredentialSpec(url = Need.REQUIRED, key = Need.OPTIONAL), multiInstance = true, freeSoftware = true, selfHosted = true,
    )

    /** Google's Home APIs through Play services; the grant lives in `extra`. Only where the build carries the OAuth client. */
    val GOOGLE_HOME = ProviderType(
        "google_home", HOMES, R.string.core_tools_provider_google_home, R.string.core_tools_provider_google_home_desc,
    )

    /** A browser sign-in fills `extra`; a pasted personal access token (24 hours) is the key. */
    val SMARTTHINGS = ProviderType(
        "smartthings", HOMES, R.string.core_tools_provider_smartthings, R.string.core_tools_provider_smartthings_desc,
        CredentialSpec(key = Need.OPTIONAL),
    )

    /** A Hue bridge on the LAN: the address, and the application key pairing gives. */
    val HUE = ProviderType(
        "hue", HOMES, R.string.core_tools_provider_hue, R.string.core_tools_provider_hue_desc,
        CredentialSpec(url = Need.REQUIRED, key = Need.REQUIRED), multiInstance = true, selfHosted = true,
    )

    val OPENHAB = ProviderType(
        "openhab", HOMES, R.string.core_tools_provider_openhab, R.string.core_tools_provider_openhab_desc,
        CredentialSpec(url = Need.REQUIRED, key = Need.OPTIONAL), multiInstance = true, freeSoftware = true, selfHosted = true,
    )

    val all: List<ProviderType> = listOf(
        KLIPY, GIPHY, COMMONS, GIFCITIES, OFFLINE_GIFS, STICKER_PACKS,
        ML_KIT, TRANSLATE_SERVER, DEEPL, LIBRETRANSLATE, MOZHI, SIMPLYTRANSLATE, LINGVA, TRANSLITE, GOOGLE_TRANSLATE,
        VOCAB_PACKS, WIKTIONARY, WIKTIONARY_REST, DICTIONARY_API, OFFLINE_WIKTIONARY, OFFLINE_WORDNET,
        DATAMUSE, SIMILAR_WORDS,
        SEARXNG, TAVILY, BRAVE,
        UNSPLASH, PEXELS,
        ER_API, FRANKFURTER, COINBASE, CURRENCY_API, COINGECKO,
        HOME_ASSISTANT, GOOGLE_HOME, SMARTTHINGS, HUE, OPENHAB,
    )

    private val byId: Map<String, ProviderType> = all.associateBy { it.id }

    fun of(id: String): ProviderType? = byId[id]

    fun serving(tool: ProviderTool): List<ProviderType> = all.filter { tool in it.serves }
}

/**
 * What one provider was set up with: a key, an address, a model. Shared by
 * every tool that lists the provider. A single-instance type's credential
 * has the type's own id, so there is only ever one; a [ProviderType.multiInstance]
 * one gets a fresh id per server.
 *
 * A blank [apiKey] on a type with a built-in key means "use the build's key".
 *
 * [extra] holds what the four fields cannot: a sign-in's refresh token and
 * expiry, a second address, a bridge id. Its keys are the type's business
 * (`HomeKeys` for the smart home kinds); a reader never sees a key it does
 * not know, and an older build reads a credential without the field as empty.
 */
@Serializable
data class ProviderCredential(
    val id: String,
    val typeId: String,
    val name: String = "",
    val apiKey: String = "",
    val url: String = "",
    val model: String = "",
    val extra: Map<String, String> = emptyMap(),
) {
    val type: ProviderType? get() = ProviderTypes.of(typeId)

    companion object {
        private val random = SecureRandom()

        /** A fresh id for one more server of a multi-instance type. */
        fun newId(typeId: String): String {
            val bytes = ByteArray(4).also(random::nextBytes)
            return typeId + ":" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * One row of a tool's list. [credentialId] is null for a type with nothing to
 * set up and for a single-instance type, whose credential is found by its
 * type id; [key] is what tells two rows of one list apart.
 */
@Serializable
data class ProviderEntry(
    val typeId: String,
    val credentialId: String? = null,
    val enabled: Boolean = true,
) {
    val key: String get() = credentialId ?: typeId
    val type: ProviderType? get() = ProviderTypes.of(typeId)

    /** The credential this row reads: its own, or its single-instance type's. */
    val credentialKey: String get() = credentialId ?: typeId
}

/** A tool's list: the providers in the order they are asked, and how they are used. */
@Serializable
data class ProviderChain(
    val entries: List<ProviderEntry> = emptyList(),
    val mode: ChainMode = ChainMode.FALLBACK,
    /**
     * The type ids this tool could list when the list was saved. A type a
     * later build adds is not among them, which is how [withNewTypes] tells
     * a source that is new from one the user removed.
     */
    val known: List<String> = emptyList(),
) {
    fun has(key: String): Boolean = entries.any { it.key == key }

    fun without(key: String): ProviderChain = copy(entries = entries.filterNot { it.key == key })

    fun plus(entry: ProviderEntry): ProviderChain =
        if (has(entry.key)) this else copy(entries = entries + entry)

    fun toggled(key: String, on: Boolean): ProviderChain =
        copy(entries = entries.map { if (it.key == key) it.copy(enabled = on) else it })

    /** Stamped on saving: every type [tool] can list in this build. */
    fun stamped(tool: ProviderTool): ProviderChain = copy(known = ProviderTypes.serving(tool).map { it.id })

    /**
     * The word sources (dictionary, synonyms, vocabulary) a later build added
     * join the end of a saved list, switched on, as they did before the lists
     * were provider lists: an update adds a backup rather than hiding it.
     * Only kinds that need nothing set up, and never one the user removed.
     */
    fun withNewTypes(tool: ProviderTool): ProviderChain {
        if (tool !in APPENDING || known.isEmpty()) return this
        val added = ProviderTypes.serving(tool)
            .filter { it.id !in known && it.credential.none && !it.multiInstance }
            .filter { type -> entries.none { it.typeId == type.id } }
        return if (added.isEmpty()) this else copy(entries = entries + added.map { ProviderEntry(it.id) })
    }

    // Not private: the serialization plugin hangs `serializer()` on the
    // companion, and ProviderCodec has to reach it.
    companion object {
        private val APPENDING = setOf(ProviderTool.DICTIONARY, ProviderTool.SYNONYMS, ProviderTool.VOCAB_AUTOFILL)
    }
}

/**
 * The two preference strings. A type id this build does not know is kept on
 * reading, not dropped: a downgrade and an upgrade again should not lose it.
 * Resolution skips it instead ([ProviderResolution]).
 */
object ProviderCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
    }

    private val chainsSerializer = MapSerializer(String.serializer(), ProviderChain.serializer())
    private val credentialsSerializer = ListSerializer(ProviderCredential.serializer())

    fun encodeCredentials(credentials: List<ProviderCredential>): String =
        json.encodeToString(credentialsSerializer, credentials)

    /** Null for nothing stored or a value that cannot be read, so the caller falls back to the old settings. */
    fun decodeCredentials(value: String?): List<ProviderCredential>? {
        if (value.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(credentialsSerializer, value) }.getOrNull()
    }

    fun encodeChains(chains: Map<ProviderTool, ProviderChain>): String =
        json.encodeToString(chainsSerializer, chains.mapKeys { it.key.id })

    /** A tool id this build does not know is dropped; there is nothing to show it in. */
    fun decodeChains(value: String?): Map<ProviderTool, ProviderChain> {
        if (value.isNullOrBlank()) return emptyMap()
        val raw = runCatching { json.decodeFromString(chainsSerializer, value) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<ProviderTool, ProviderChain>()
        for ((id, chain) in raw) {
            val tool = ProviderTool.of(id) ?: continue
            // A mode this tool does not offer (written by a later build) falls back to its default.
            out[tool] = if (chain.mode in tool.modes) chain else chain.copy(mode = tool.modes.first())
        }
        return out
    }
}

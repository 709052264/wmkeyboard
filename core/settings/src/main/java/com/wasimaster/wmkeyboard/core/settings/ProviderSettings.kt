package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.providers.ChainMode
import com.wasimaster.wmkeyboard.core.providers.Need
import com.wasimaster.wmkeyboard.core.providers.ProviderCatalog
import com.wasimaster.wmkeyboard.core.providers.ProviderChain
import com.wasimaster.wmkeyboard.core.providers.ProviderChannel
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderEntry
import com.wasimaster.wmkeyboard.core.providers.ProviderEnv
import com.wasimaster.wmkeyboard.core.providers.ProviderEnvironment
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.providers.SameKind
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.tools.CurrencyClient

/**
 * The provider lists the user has edited, and the credentials they set up.
 *
 * Nothing is stored until the user changes something. Until then a tool's
 * list is worked out from the settings that came before it ([LegacyProviders]),
 * which is what keeps every install, new or upgraded, doing exactly what it
 * did: the same providers, in the same order, with the same keys. Each tool's
 * list is stored on its own the first time it is edited, so editing the GIF
 * list leaves the translate list following the old settings.
 *
 * [credentials] is null until the first credential is saved; from then on it
 * is the whole truth and the old key fields are no longer read.
 */
data class ProviderSettings(
    val credentials: List<ProviderCredential>? = null,
    val chains: Map<ProviderTool, ProviderChain> = emptyMap(),
)

/** Why a provider in a list cannot be asked. */
enum class ProviderProblem {
    /** A key is needed and there is neither the user's nor the build's. */
    NEEDS_KEY,

    /** A server address is needed. */
    NEEDS_SERVER,

    /** A model name is needed: a vision server for the OCR tool. */
    NEEDS_MODEL,

    /** Offline GIF packs with no pack imported. */
    NEEDS_PACKS,

    /** A home that is set up but not signed in, paired or granted yet. */
    NEEDS_SIGN_IN,

    /** This build cannot run it: no internet permission, no ML Kit, no Google Home APIs. */
    UNAVAILABLE,

    /** Written by a newer build; this one does not know the type. */
    UNKNOWN,
}

/**
 * One row of a tool's list with everything a caller needs to ask it: the key
 * to send (the user's, else the build's), the address, the model.
 */
data class ResolvedProvider(
    val entry: ProviderEntry,
    val type: ProviderType?,
    val credential: ProviderCredential?,
    val apiKey: String,
    val url: String,
    val model: String,
    val problem: ProviderProblem?,
    /**
     * The later rows of this kind, asked in order when this one fails, when
     * the list keeps two of a kind together ([SameKind.FALLBACK]). Only a
     * grouped resolution fills it ([resolveProviders] with `grouped`).
     */
    val backups: List<ResolvedProvider> = emptyList(),
) {
    val key: String get() = entry.key
    val typeId: String get() = entry.typeId
    val usable: Boolean get() = problem == null && type != null

    /** The key in use is the one this build carries, not one the user typed. */
    val usesBuiltInKey: Boolean get() = credential?.apiKey.isNullOrBlank() && type?.hasBuiltInKey == true && entry.primary

    /** This row, then its [backups]: the order a fetch walks. */
    val withBackups: List<ResolvedProvider> get() = if (backups.isEmpty()) listOf(this) else listOf(copy(backups = emptyList())) + backups

    /** What the list shows: the name the user gave the server, else the type's own. */
    val customName: String get() = credential?.name?.trim().orEmpty()
}

/** Every credential in force: the stored list, or the one the old settings make. */
fun KeyboardSettings.providerCredentials(): List<ProviderCredential> =
    providers.credentials ?: LegacyProviders.credentials(this)

/** The credential a single-instance type, or one row, reads. */
fun KeyboardSettings.providerCredential(id: String): ProviderCredential? =
    providerCredentials().firstOrNull { it.id == id }

/** [tool]'s list as it stands: the stored one, or the one the old settings make. */
fun KeyboardSettings.providerChain(
    tool: ProviderTool,
    env: ProviderEnv = ProviderEnvironment.current(),
): ProviderChain = providers.chains[tool]?.withNewTypes(tool) ?: LegacyProviders.chain(this, tool, env)

/**
 * [tool]'s list resolved: every row with its credential and, when it cannot
 * be asked, why. [all] keeps the rows that are off or cannot be asked, for
 * the settings list; without it only what a fetch should ask is left, in order.
 *
 * [pick] is the panel's choice, for a list in [ChainMode.PICK]: then only the
 * row with that key is left, or the first row when the choice is gone (or
 * was never made, ""), followed by the rows of its kind that back it up
 * ([SameKind.FALLBACK]). Null is a caller with no panel, the AI's tools,
 * which walk the whole list whatever the mode.
 *
 * [grouped] is for a caller that shows one entry per provider (the media
 * chips, the panel's chooser): with [SameKind.FALLBACK] and a mode other
 * than [ChainMode.FALLBACK], the rows of one kind become the first of them,
 * carrying the rest as its [ResolvedProvider.backups].
 */
fun KeyboardSettings.resolveProviders(
    tool: ProviderTool,
    env: ProviderEnv = ProviderEnvironment.current(),
    all: Boolean = false,
    pick: String? = null,
    grouped: Boolean = false,
): List<ResolvedProvider> {
    val credentials = providerCredentials()
    val chain = providerChain(tool, env)
    val resolved = chain.entries.map { ProviderResolution.resolve(it, credentials, env) }
    if (all) return resolved
    val asked = resolved.filter { it.entry.enabled && it.usable }
    if (pick != null && chain.mode == ChainMode.PICK) {
        val leads = groupSameKind(asked, chain)
        val chosen = leads.firstOrNull { it.key == pick } ?: leads.firstOrNull() ?: return emptyList()
        return chosen.withBackups
    }
    return if (grouped) groupSameKind(asked, chain) else asked
}

/** [rows] with each kind's later rows folded into its first, when [chain] says so; else as they are. */
private fun groupSameKind(rows: List<ResolvedProvider>, chain: ProviderChain): List<ResolvedProvider> {
    if (chain.mode == ChainMode.FALLBACK || chain.sameKindInForce != SameKind.FALLBACK) return rows
    val byKind = rows.groupBy { it.typeId }
    if (byKind.values.none { it.size > 1 }) return rows
    return byKind.values.map { kind -> kind.first().copy(backups = kind.drop(1)) }
}

/** The rows a panel in [ChainMode.PICK] offers, and which of them is chosen. */
data class ProviderChoices(val rows: List<ResolvedProvider>, val chosen: String) {
    /** Worth a chooser: the list is in pick mode and more than one row is on. */
    val shown: Boolean get() = rows.size > 1
}

/** [tool]'s chooser for a panel, or null when the list does not ask the panel to choose. */
fun KeyboardSettings.providerChoices(
    tool: ProviderTool,
    pick: String?,
    env: ProviderEnv = ProviderEnvironment.current(),
): ProviderChoices? {
    if (providerMode(tool, env) != ChainMode.PICK) return null
    val rows = resolveProviders(tool, env, grouped = true)
    if (rows.isEmpty()) return null
    val chosen = rows.firstOrNull { it.key == pick }?.key ?: rows.first().key
    return ProviderChoices(rows, chosen)
}

/**
 * DeepL as DeepL Write reads it: the switches from [DeepLSettings], the key
 * and the proxy from the shared DeepL credential the translate list sets up.
 */
val KeyboardSettings.deeplLive: DeepLSettings
    get() {
        val credential = providerCredential(ProviderTypes.DEEPL.id)
        return translate.deepl.copy(apiKey = credential?.apiKey.orEmpty(), endpoint = credential?.url.orEmpty())
    }

/**
 * The currency lists as `CurrencyClient.Provider` names, best first, the rows
 * that are off left out. Empty when the user switched every one off, which
 * the callers take as "fetch nothing" rather than the client's defaults.
 */
fun KeyboardSettings.fiatRateProviders(): List<String> =
    resolveProviders(ProviderTool.CURRENCY_FIAT).map { it.typeId.uppercase(java.util.Locale.ROOT) }

/** The same for coin prices. */
fun KeyboardSettings.cryptoRateProviders(): List<String> =
    resolveProviders(ProviderTool.CURRENCY_CRYPTO).map { it.typeId.uppercase(java.util.Locale.ROOT) }

/** How [tool] uses its list. */
fun KeyboardSettings.providerMode(tool: ProviderTool, env: ProviderEnv = ProviderEnvironment.current()): ChainMode =
    providerChain(tool, env).mode

object ProviderResolution {

    fun resolve(entry: ProviderEntry, credentials: List<ProviderCredential>, env: ProviderEnv): ResolvedProvider {
        val type = entry.type
        val credential = credentials.firstOrNull { it.id == entry.credentialKey }
        // The build's key stands in for the type's own credential only: a second one is there to bring its own.
        val apiKey = credential?.apiKey?.trim().orEmpty().ifBlank { if (entry.primary) type?.builtInKey.orEmpty() else "" }
        val url = credential?.url?.trim().orEmpty()
        val model = credential?.model?.trim().orEmpty()
        val problem = when {
            type == null -> ProviderProblem.UNKNOWN
            !ProviderCatalog.runnable(type, env) -> ProviderProblem.UNAVAILABLE
            type === ProviderTypes.OFFLINE_GIFS && !env.offlineGifPacks -> ProviderProblem.NEEDS_PACKS
            // DeepL works with a key, a proxy that adds one, or both.
            type === ProviderTypes.DEEPL && apiKey.isBlank() && url.isBlank() -> ProviderProblem.NEEDS_KEY
            type.credential.url == Need.REQUIRED && url.isBlank() -> ProviderProblem.NEEDS_SERVER
            // A home's key is a pairing or a sign-in, which the row says in its own words.
            HomeKind.of(type.id) != null -> homeProblem(entry, type, credential)
            type.credential.modelRequired && model.isBlank() -> ProviderProblem.NEEDS_MODEL
            type.credential.key == Need.REQUIRED && apiKey.isBlank() -> ProviderProblem.NEEDS_KEY
            else -> null
        }
        return ResolvedProvider(entry, type, credential, apiKey, url, model, problem)
    }

    private fun homeProblem(entry: ProviderEntry, type: ProviderType, credential: ProviderCredential?): ProviderProblem? {
        val kind = HomeKind.of(type.id) ?: return ProviderProblem.UNKNOWN
        val account = HomeAccount(entry.key, kind, url = credential?.url?.trim().orEmpty(), apiKey = credential?.apiKey?.trim().orEmpty(), extra = credential?.extra.orEmpty())
        return when {
            !account.hasServer -> ProviderProblem.NEEDS_SERVER
            !account.signedIn -> ProviderProblem.NEEDS_SIGN_IN
            else -> null
        }
    }
}

/**
 * [tool] is [ProviderTool.SMART_HOME]: every home the panel should connect,
 * in list order. [all] keeps the ones that are off or not signed in, for a
 * settings page that shows them.
 */
fun KeyboardSettings.homes(env: ProviderEnv = ProviderEnvironment.current(), all: Boolean = false): List<HomeAccount> =
    resolveProviders(ProviderTool.SMART_HOME, env, all).mapNotNull { it.toHomeAccount() }

/** The home as the backends read it, or null for a type this build does not know. */
fun ResolvedProvider.toHomeAccount(): HomeAccount? {
    val kind = HomeKind.of(typeId) ?: return null
    return HomeAccount(
        key = key,
        kind = kind,
        name = customName,
        url = url,
        apiKey = apiKey,
        extra = credential?.extra.orEmpty(),
    )
}

/** The first Home Assistant on the list: the one Assist and the AI's "control your home" talk to. */
fun KeyboardSettings.assistHome(env: ProviderEnv = ProviderEnvironment.current()): HomeAccount? =
    homes(env).firstOrNull { it.kind == HomeKind.HOME_ASSISTANT }

/**
 * The lists and credentials the settings before provider lists amount to.
 *
 * Each list here is what the tool asked before, in the order it asked: the
 * GIF list is the providers that had a key, then Commons on F-Droid, then
 * GifCities when its switch was on, then the offline packs. A fresh install
 * reads its defaults through the same path, so new and upgraded installs
 * cannot drift apart.
 */
object LegacyProviders {

    fun credentials(s: KeyboardSettings): List<ProviderCredential> = buildList {
        fun key(type: ProviderType, value: String) {
            if (value.isNotBlank()) add(ProviderCredential(id = type.id, typeId = type.id, apiKey = value.trim()))
        }
        key(ProviderTypes.KLIPY, s.gif.klipyApiKey)
        key(ProviderTypes.GIPHY, s.gif.giphyApiKey)
        key(ProviderTypes.GOOGLE_TRANSLATE, s.translateApiKey)
        key(ProviderTypes.TAVILY, s.webSearch.tavilyApiKey)
        key(ProviderTypes.BRAVE, s.webSearch.braveApiKey)
        key(ProviderTypes.UNSPLASH, s.photoBackground.unsplashApiKey)
        key(ProviderTypes.PEXELS, s.photoBackground.pexelsApiKey)
        val deepl = s.translate.deepl
        if (deepl.configured) {
            add(
                ProviderCredential(
                    id = ProviderTypes.DEEPL.id,
                    typeId = ProviderTypes.DEEPL.id,
                    apiKey = deepl.apiKey.trim(),
                    url = deepl.endpoint.trim(),
                ),
            )
        }
        val server = s.translate.server
        if (server.configured) {
            add(
                ProviderCredential(
                    id = ProviderTypes.TRANSLATE_SERVER.id,
                    typeId = ProviderTypes.TRANSLATE_SERVER.id,
                    apiKey = server.apiKey.trim(),
                    url = server.url.trim(),
                    model = server.model.trim(),
                ),
            )
        }
        val hosted = s.selfHosted
        if (hosted.libreTranslateUrl.isNotBlank()) {
            add(
                ProviderCredential(
                    id = ProviderTypes.LIBRETRANSLATE.id,
                    typeId = ProviderTypes.LIBRETRANSLATE.id,
                    apiKey = hosted.libreTranslateApiKey.trim(),
                    url = hosted.libreTranslateUrl.trim(),
                ),
            )
        }
        if (hosted.searxUrl.isNotBlank()) {
            add(ProviderCredential(id = ProviderTypes.SEARXNG.id, typeId = ProviderTypes.SEARXNG.id, url = hosted.searxUrl.trim()))
        }
        if (hosted.commonsUrl.isNotBlank()) {
            add(ProviderCredential(id = ProviderTypes.COMMONS.id, typeId = ProviderTypes.COMMONS.id, url = hosted.commonsUrl.trim()))
        }
        // The OCR tool's one online reader, from before it kept a list (#469).
        val scanner = s.scanner
        if (scanner.ocrOnlineUrl.isNotBlank()) {
            add(
                ProviderCredential(
                    id = ProviderTypes.VISION_OCR.id,
                    typeId = ProviderTypes.VISION_OCR.id,
                    apiKey = scanner.ocrOnlineKey.trim(),
                    url = scanner.ocrOnlineUrl.trim(),
                    model = scanner.ocrOnlineModel.trim(),
                ),
            )
        }
    }

    fun chain(s: KeyboardSettings, tool: ProviderTool, env: ProviderEnv): ProviderChain {
        val credentials = s.providerCredentials()
        fun hasKey(type: ProviderType): Boolean =
            credentials.any { it.id == type.id && it.apiKey.isNotBlank() } || type.hasBuiltInKey
        fun has(type: ProviderType): Boolean = credentials.any { it.id == type.id }
        fun entry(type: ProviderType, on: Boolean = true) = ProviderEntry(type.id, enabled = on)
        val fdroid = env.channel == ProviderChannel.FDROID
        val mediaMode = when (s.gif.sourceMode) {
            GifSourceMode.TABS -> ChainMode.TABS
            GifSourceMode.MIX -> ChainMode.MIX
        }
        return when (tool) {
            ProviderTool.GIF -> ProviderChain(
                buildList {
                    if (hasKey(ProviderTypes.KLIPY)) add(entry(ProviderTypes.KLIPY))
                    if (hasKey(ProviderTypes.GIPHY)) add(entry(ProviderTypes.GIPHY))
                    if (fdroid) add(entry(ProviderTypes.COMMONS))
                    if (s.gif.gifCities) add(entry(ProviderTypes.GIFCITIES))
                    add(entry(ProviderTypes.OFFLINE_GIFS))
                },
                mediaMode,
            )
            // Commons used to join the sticker list on F-Droid too, and never
            // had a sticker to show; it is left out here.
            ProviderTool.STICKER -> ProviderChain(
                buildList {
                    if (hasKey(ProviderTypes.KLIPY)) add(entry(ProviderTypes.KLIPY))
                    if (hasKey(ProviderTypes.GIPHY)) add(entry(ProviderTypes.GIPHY))
                    add(entry(ProviderTypes.STICKER_PACKS))
                },
                mediaMode,
            )
            ProviderTool.TRANSLATE -> translateChain(s, env, ::has)
            ProviderTool.DICTIONARY -> ProviderChain(
                s.dictionarySources.map { ProviderEntry(it.source.id, enabled = it.enabled) },
            )
            ProviderTool.SYNONYMS -> ProviderChain(
                s.suggestionStrip.synonymSources.map { ProviderEntry(it.source.id, enabled = it.enabled) },
            )
            ProviderTool.VOCAB_AUTOFILL -> ProviderChain(
                listOf(entry(ProviderTypes.WIKTIONARY), entry(ProviderTypes.WIKTIONARY_REST), entry(ProviderTypes.DICTIONARY_API)),
            )
            // The more deliberate the setup, the earlier: a named SearXNG
            // instance, then a Tavily key only the user can have put there,
            // then Brave, whose key may be the build's.
            ProviderTool.SEARCH -> ProviderChain(
                buildList {
                    if (has(ProviderTypes.SEARXNG)) add(entry(ProviderTypes.SEARXNG))
                    if (hasKey(ProviderTypes.TAVILY)) add(entry(ProviderTypes.TAVILY))
                    if (hasKey(ProviderTypes.BRAVE)) add(entry(ProviderTypes.BRAVE))
                },
            )
            ProviderTool.PHOTOS -> ProviderChain(
                buildList {
                    if (hasKey(ProviderTypes.UNSPLASH)) add(entry(ProviderTypes.UNSPLASH))
                    if (hasKey(ProviderTypes.PEXELS)) add(entry(ProviderTypes.PEXELS))
                    if (fdroid) add(entry(ProviderTypes.COMMONS))
                },
                ChainMode.MIX,
            )
            ProviderTool.CURRENCY_FIAT ->
                currencyChain(s.rateSources.fiatProviders, tool, CurrencyClient.Provider.fiatDefaults(fdroid))
            ProviderTool.CURRENCY_CRYPTO ->
                currencyChain(s.rateSources.cryptoProviders, tool, CurrencyClient.Provider.cryptoDefaults(fdroid))
            // Nothing came before the list: a home is only ever added by the user.
            ProviderTool.SMART_HOME -> ProviderChain(emptyList(), ChainMode.MIX)
            // Only the reader the user set up: nothing reads a photo online
            // that the user did not name.
            ProviderTool.OCR -> ProviderChain(
                buildList { if (has(ProviderTypes.VISION_OCR)) add(entry(ProviderTypes.VISION_OCR)) },
            )
            // New with #727: empty until a Rens server is added.
            ProviderTool.PHOTO_TRANSLATE -> ProviderChain()
        }
    }

    /**
     * Before: behind Online a fixed order. The user's own server, when set,
     * answered alone; then DeepL, then LibreTranslate, then Google. On the
     * device is the list's first row; the engine switch (On device, Online,
     * Automatic) still decides which part of the list is asked, as it always
     * has, so it stays out of the list itself.
     *
     * The server answering alone is kept by switching off what comes after
     * it: falling through to Google would send text the user meant for their
     * own machine to someone else's.
     */
    private fun translateChain(s: KeyboardSettings, env: ProviderEnv, has: (ProviderType) -> Boolean): ProviderChain {
        val online = buildList {
            val server = has(ProviderTypes.TRANSLATE_SERVER)
            if (server) add(ProviderEntry(ProviderTypes.TRANSLATE_SERVER.id))
            if (has(ProviderTypes.DEEPL) && s.translate.deepl.translate) add(ProviderEntry(ProviderTypes.DEEPL.id, enabled = !server))
            if (has(ProviderTypes.LIBRETRANSLATE)) add(ProviderEntry(ProviderTypes.LIBRETRANSLATE.id, enabled = !server))
            add(ProviderEntry(ProviderTypes.GOOGLE_TRANSLATE.id, enabled = !server))
        }
        if (!env.onDeviceTranslate) return ProviderChain(online)
        return ProviderChain(listOf(ProviderEntry(ProviderTypes.ML_KIT.id)) + online)
    }

    /**
     * The old lists stored `CurrencyClient.Provider` names; a type id is the
     * same name in lower case. A list with nothing usable in it fell back to
     * the defaults, and still does.
     */
    private fun currencyChain(names: List<String>, tool: ProviderTool, defaults: List<String>): ProviderChain {
        fun ids(from: List<String>) = from.map { it.lowercase() }
            .distinct()
            .filter { id -> ProviderTypes.of(id)?.serves?.contains(tool) == true }
        return ProviderChain(ids(names).ifEmpty { ids(defaults) }.map { ProviderEntry(it) })
    }
}

/**
 * Edits the settings screens make, as pure functions of the current settings:
 * each returns the whole new list and the whole new credential list, which
 * [SettingsRepository.saveProviders] writes in one transaction.
 */
object ProviderEdits {

    data class Change(
        val tool: ProviderTool?,
        val chain: ProviderChain?,
        val credentials: List<ProviderCredential>?,
    )

    /** Saves [credential] (new or changed) and, when [tool] is given, adds a row for it there. */
    fun saveCredential(
        s: KeyboardSettings,
        credential: ProviderCredential,
        tool: ProviderTool?,
        env: ProviderEnv = ProviderEnvironment.current(),
    ): Change {
        val current = s.providerCredentials()
        val next = if (current.any { it.id == credential.id }) {
            current.map { if (it.id == credential.id) credential else it }
        } else {
            current + credential
        }
        if (tool == null) return Change(null, null, next)
        val entry = ProviderEntry(
            typeId = credential.typeId,
            credentialId = credential.id.takeIf { it != credential.typeId },
        )
        val chain = s.providerChain(tool, env)
        if (chain.has(entry.key)) return Change(tool, chain, next)
        return Change(tool, withNewRow(tool, chain, entry), next)
    }

    /**
     * A new row goes to the end, except the user's own translation server:
     * the point of running one is that the text goes nowhere else, so it goes
     * first and the other online rows are switched off, as they were when the
     * server was a single address field. The user can switch them back on.
     */
    private fun withNewRow(tool: ProviderTool, chain: ProviderChain, entry: ProviderEntry): ProviderChain {
        if (tool != ProviderTool.TRANSLATE || entry.typeId != ProviderTypes.TRANSLATE_SERVER.id) return chain.plus(entry)
        val (device, online) = chain.entries.partition { it.type?.onDevice == true }
        return chain.copy(entries = device + entry + online.map { it.copy(enabled = false) })
    }

    /** Adds a row that needs nothing set up, or reuses a credential that exists. */
    fun addEntry(
        s: KeyboardSettings,
        tool: ProviderTool,
        entry: ProviderEntry,
        env: ProviderEnv = ProviderEnvironment.current(),
    ): Change {
        val chain = s.providerChain(tool, env)
        return Change(tool, if (chain.has(entry.key)) chain else withNewRow(tool, chain, entry), null)
    }

    fun setChain(tool: ProviderTool, chain: ProviderChain): Change = Change(tool, chain, null)

    /**
     * Takes the row out of [tool]'s list. Its credential goes too when no
     * other tool's list still reads it, so a removed server does not linger
     * with its password in the backups.
     */
    fun removeEntry(
        s: KeyboardSettings,
        tool: ProviderTool,
        key: String,
        env: ProviderEnv = ProviderEnvironment.current(),
    ): Change {
        val chain = s.providerChain(tool, env)
        val entry = chain.entries.firstOrNull { it.key == key } ?: return Change(null, null, null)
        val next = chain.without(key)
        val stillUsed = ProviderTool.entries.filter { it != tool }
            .any { other -> s.providerChain(other, env).entries.any { it.credentialKey == entry.credentialKey } }
        val credentials = s.providerCredentials()
        val dropped = if (stillUsed || credentials.none { it.id == entry.credentialKey }) {
            null
        } else {
            credentials.filterNot { it.id == entry.credentialKey }
        }
        return Change(tool, next, dropped)
    }

    /** The other tools whose lists read [credentialId], for "Also used by". */
    fun usedBy(
        s: KeyboardSettings,
        credentialId: String,
        except: ProviderTool?,
        env: ProviderEnv = ProviderEnvironment.current(),
    ): List<ProviderTool> = ProviderTool.entries.filter { it != except }
        .filter { tool -> s.providerChain(tool, env).entries.any { it.credentialKey == credentialId } }
}

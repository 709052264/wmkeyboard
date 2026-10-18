package com.wasimaster.wmkeyboard.core.providers

import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.settings.DeepLSettings
import com.wasimaster.wmkeyboard.core.settings.GifSettings
import com.wasimaster.wmkeyboard.core.settings.GifSourceMode
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ProviderEdits
import com.wasimaster.wmkeyboard.core.settings.ProviderProblem
import com.wasimaster.wmkeyboard.core.settings.ProviderSettings
import com.wasimaster.wmkeyboard.core.settings.RateSourceSettings
import com.wasimaster.wmkeyboard.core.settings.ScannerSettings
import com.wasimaster.wmkeyboard.core.settings.SelfHostedSettings
import com.wasimaster.wmkeyboard.core.settings.TranslateEngine
import com.wasimaster.wmkeyboard.core.settings.TranslateServerSettings
import com.wasimaster.wmkeyboard.core.settings.TranslateSettings
import com.wasimaster.wmkeyboard.core.settings.WebSearchSettings
import com.wasimaster.wmkeyboard.core.settings.assistHome
import com.wasimaster.wmkeyboard.core.settings.homes
import com.wasimaster.wmkeyboard.core.settings.providerChain
import com.wasimaster.wmkeyboard.core.settings.providerChoices
import com.wasimaster.wmkeyboard.core.settings.providerCredentials
import com.wasimaster.wmkeyboard.core.settings.resolveProviders
import com.wasimaster.wmkeyboard.core.tools.DictionarySource
import com.wasimaster.wmkeyboard.core.tools.DictionarySourceChoice
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFrameworkTest {

    private val play = ProviderEnv(ProviderChannel.PLAY, offline = false, onDeviceTranslate = true, offlineGifPacks = false)
    private val github = play.copy(channel = ProviderChannel.GITHUB)
    private val fdroid = play.copy(channel = ProviderChannel.FDROID, onDeviceTranslate = false)
    private val offline = github.copy(offline = true)

    @After
    fun tearDown() {
        ProviderRunner.reset()
        ProviderRunner.clock = { System.nanoTime() / 1_000_000 }
    }

    private fun ids(chain: ProviderChain) = chain.entries.map { it.typeId }
    private fun on(chain: ProviderChain) = chain.entries.filter { it.enabled }.map { it.typeId }

    // ---- codec ----

    @Test
    fun `credentials and lists survive the store`() {
        val credentials = listOf(
            ProviderCredential("klipy", "klipy", apiKey = "k"),
            ProviderCredential("libretranslate:0a0b", "libretranslate", name = "Home", url = "https://lt", apiKey = "x"),
        )
        assertEquals(credentials, ProviderCodec.decodeCredentials(ProviderCodec.encodeCredentials(credentials)))
        val chains = mapOf(
            ProviderTool.GIF to ProviderChain(listOf(ProviderEntry("klipy"), ProviderEntry("gifcities", enabled = false)), ChainMode.MIX),
            ProviderTool.TRANSLATE to ProviderChain(listOf(ProviderEntry("libretranslate", "libretranslate:0a0b"))),
        )
        assertEquals(chains, ProviderCodec.decodeChains(ProviderCodec.encodeChains(chains)))
    }

    @Test
    fun `nothing stored reads as null so the old settings answer`() {
        assertNull(ProviderCodec.decodeCredentials(null))
        assertNull(ProviderCodec.decodeCredentials("not json"))
        assertTrue(ProviderCodec.decodeChains("").isEmpty())
    }

    @Test
    fun `an unknown tool is dropped, an unknown type is kept, a foreign mode falls back`() {
        val raw = """{"gif":{"entries":[{"typeId":"future_gifs"},{"typeId":"klipy"}]},"teleport":{},""" +
            """"translate":{"entries":[],"mode":"TABS"}}"""
        val chains = ProviderCodec.decodeChains(raw)
        assertEquals(setOf(ProviderTool.GIF, ProviderTool.TRANSLATE), chains.keys)
        assertEquals(listOf("future_gifs", "klipy"), ids(chains.getValue(ProviderTool.GIF)))
        assertEquals(ChainMode.FALLBACK, chains.getValue(ProviderTool.TRANSLATE).mode)
    }

    // ---- runner ----

    @Test
    fun `fallback walks past a failure and an empty answer`() = runBlocking {
        val outcome = ProviderRunner.fallback(
            listOf("a", "b", "c"),
            key = { it },
            isEmpty = { it: List<Int> -> it.isEmpty() },
        ) { name ->
            when (name) {
                "a" -> throw IOException("down")
                "b" -> emptyList()
                else -> listOf(1)
            }
        }
        assertEquals(ProviderRunner.Outcome.Found(listOf(1), "c"), outcome)
    }

    @Test
    fun `not found and failed stay apart`() = runBlocking {
        val none = ProviderRunner.fallback(listOf("a"), key = { it }, isEmpty = { it: String -> it.isEmpty() }) { "" }
        assertEquals(ProviderRunner.Outcome.NotFound, none)
        val failed = ProviderRunner.fallback<String, String>(listOf("a"), key = { it }) { throw IOException("x") }
        assertTrue(failed is ProviderRunner.Outcome.Failed)
    }

    @Test
    fun `an unreachable provider cools down while another can answer`() = runBlocking {
        var now = 0L
        ProviderRunner.clock = { now }
        val asked = mutableListOf<String>()
        suspend fun ask() = ProviderRunner.fallback(listOf("a", "b"), key = { it }) { name ->
            asked += name
            if (name == "a") throw UnknownHostException("a") else name
        }
        ask()
        ask()
        assertEquals(listOf("a", "b", "b"), asked)
        now += ProviderRunner.COOLDOWN_MS
        ask()
        assertEquals(listOf("a", "b", "b", "a", "b"), asked)
    }

    @Test
    fun `when every provider is cooling down they are asked anyway`() = runBlocking {
        ProviderRunner.clock = { 0L }
        var calls = 0
        repeat(2) {
            ProviderRunner.fallback<String, String>(listOf("a"), key = { it }) {
                calls++
                throw UnknownHostException("a")
            }
        }
        assertEquals(2, calls)
    }

    @Test
    fun `a wrong request does not cool a provider down`() = runBlocking {
        ProviderRunner.clock = { 0L }
        ProviderRunner.fallback(listOf("a", "b"), key = { it }) { if (it == "a") throw IllegalStateException() else it }
        assertFalse(ProviderRunner.coolingDown("a", 0L))
    }

    @Test
    fun `interleave takes one from each in turn`() {
        assertEquals(listOf(1, 10, 2, 20, 3), ProviderRunner.interleave(listOf(listOf(1, 2, 3), listOf(10, 20))))
    }

    // ---- today's lists, read from the old settings ----

    @Test
    fun `the gif list is the keyed providers, then Commons on F-Droid, then GifCities, then the packs`() {
        val s = KeyboardSettings(gif = GifSettings(klipyApiKey = "k", gifCities = true, sourceMode = GifSourceMode.MIX))
        val chain = s.providerChain(ProviderTool.GIF, fdroid)
        assertEquals("klipy", chain.entries.first().typeId)
        val tail = ids(chain).dropWhile { it == "klipy" || it == "giphy" }
        assertEquals(listOf("commons", "gifcities", "offline_gif_packs"), tail)
        assertEquals(ChainMode.MIX, chain.mode)
        assertFalse("commons" in ids(s.providerChain(ProviderTool.GIF, play)))
    }

    @Test
    fun `GifCities stays out until its switch was on`() {
        assertFalse("gifcities" in ids(KeyboardSettings().providerChain(ProviderTool.GIF, play)))
    }

    @Test
    fun `stickers no longer carry an always empty Commons`() {
        val chain = KeyboardSettings().providerChain(ProviderTool.STICKER, fdroid)
        assertFalse("commons" in ids(chain))
        assertEquals("sticker_packs", chain.entries.last().typeId)
    }

    @Test
    fun `translate lists the device first, then the online services`() {
        val s = KeyboardSettings(
            translate = TranslateSettings(engine = TranslateEngine.AUTO, deepl = DeepLSettings(apiKey = "d")),
            selfHosted = SelfHostedSettings(libreTranslateUrl = "https://lt"),
        )
        assertEquals(
            listOf("mlkit", "deepl", "libretranslate", "google_translate"),
            on(s.providerChain(ProviderTool.TRANSLATE, play)),
        )
    }

    @Test
    fun `the engine switch stays out of the list`() {
        val online = KeyboardSettings(translate = TranslateSettings(engine = TranslateEngine.ONLINE))
        val device = KeyboardSettings(translate = TranslateSettings(engine = TranslateEngine.ON_DEVICE))
        assertEquals(online.providerChain(ProviderTool.TRANSLATE, play), device.providerChain(ProviderTool.TRANSLATE, play))
        assertEquals(listOf("mlkit", "google_translate"), ids(online.providerChain(ProviderTool.TRANSLATE, github)))
    }

    @Test
    fun `a build with no ML Kit has no on device row`() {
        val s = KeyboardSettings(translate = TranslateSettings(engine = TranslateEngine.ON_DEVICE))
        assertEquals(listOf("google_translate"), ids(s.providerChain(ProviderTool.TRANSLATE, fdroid)))
    }

    @Test
    fun `the user's own translation server still answers alone`() {
        val s = KeyboardSettings(
            translate = TranslateSettings(engine = TranslateEngine.ONLINE, server = TranslateServerSettings(url = "http://box:8080")),
        )
        assertEquals(listOf("translate_server"), on(s.providerChain(ProviderTool.TRANSLATE, fdroid)))
    }

    @Test
    fun `DeepL with its translate switch off stays out`() {
        val s = KeyboardSettings(translate = TranslateSettings(deepl = DeepLSettings(apiKey = "d", translate = false)))
        assertFalse("deepl" in ids(s.providerChain(ProviderTool.TRANSLATE, fdroid)))
    }

    @Test
    fun `search keeps its old order`() {
        val s = KeyboardSettings(
            webSearch = WebSearchSettings(braveApiKey = "b", tavilyApiKey = "t"),
            selfHosted = SelfHostedSettings(searxUrl = "https://sx"),
        )
        assertEquals(listOf("searxng", "tavily", "brave"), ids(s.providerChain(ProviderTool.SEARCH, play)))
    }

    @Test
    fun `the dictionary list reads straight across`() {
        val s = KeyboardSettings(
            dictionarySources = listOf(
                DictionarySourceChoice(DictionarySource.DICTIONARY_API),
                DictionarySourceChoice(DictionarySource.VOCAB_PACKS, enabled = false),
            ),
        )
        val chain = s.providerChain(ProviderTool.DICTIONARY, play)
        assertEquals(listOf("dictionary_api", "vocab_packs"), ids(chain))
        assertEquals(listOf("dictionary_api"), on(chain))
    }

    @Test
    fun `currency reads the stored names, and an empty list falls back to the defaults`() {
        val s = KeyboardSettings(rateSources = RateSourceSettings(fiatProviders = listOf("FRANKFURTER", "COINGECKO")))
        // CoinGecko serves no fiat, so it never was in the fiat chain.
        assertEquals(listOf("frankfurter"), ids(s.providerChain(ProviderTool.CURRENCY_FIAT, play)))
        val empty = KeyboardSettings(rateSources = RateSourceSettings(fiatProviders = emptyList()))
        assertEquals(listOf("currency_api", "frankfurter"), ids(empty.providerChain(ProviderTool.CURRENCY_FIAT, fdroid)))
        assertEquals(listOf("er_api", "frankfurter"), ids(empty.providerChain(ProviderTool.CURRENCY_FIAT, play)))
    }

    @Test
    fun `extra survives the codec, and a credential without it reads as empty`() {
        val home = ProviderCredential(
            "home_assistant:01", "home_assistant", name = "Flat", url = "ha.local",
            extra = mapOf(HomeKeys.ACCESS_TOKEN to "t", HomeKeys.EXTERNAL_URL to "https://x.ui.nabu.casa"),
        )
        val back = ProviderCodec.decodeCredentials(ProviderCodec.encodeCredentials(listOf(home)))!!.single()
        assertEquals(home, back)
        val old = ProviderCodec.decodeCredentials("""[{"id":"klipy","typeId":"klipy","apiKey":"k"}]""")!!.single()
        assertTrue(old.extra.isEmpty())
        // Nothing to say: an empty map is left out of the JSON.
        assertFalse(ProviderCodec.encodeCredentials(listOf(old)).contains("extra"))
    }

    @Test
    fun `the smart home list starts empty, and a home says what it still needs`() {
        assertTrue(KeyboardSettings().providerChain(ProviderTool.SMART_HOME, play).entries.isEmpty())
        assertEquals(ChainMode.MIX, KeyboardSettings().providerChain(ProviderTool.SMART_HOME, play).mode)

        val withHomes = KeyboardSettings(
            providers = ProviderSettings(
                credentials = listOf(
                    ProviderCredential("home_assistant:01", "home_assistant", url = "ha.local"),
                    ProviderCredential("home_assistant:02", "home_assistant", url = "ha2.local", apiKey = "LONG"),
                    ProviderCredential("smartthings", "smartthings"),
                    ProviderCredential("hue:01", "hue", url = "192.168.1.9"),
                    ProviderCredential("openhab:01", "openhab", url = "http://oh:8080", apiKey = "tok"),
                    ProviderCredential("google_home", "google_home", extra = mapOf(HomeKeys.GRANTED to "1", HomeKeys.STRUCTURE_ID to "s")),
                ),
                chains = mapOf(
                    ProviderTool.SMART_HOME to ProviderChain(
                        listOf(
                            ProviderEntry("home_assistant", "home_assistant:01"),
                            ProviderEntry("home_assistant", "home_assistant:02"),
                            ProviderEntry("smartthings"),
                            ProviderEntry("hue", "hue:01"),
                            ProviderEntry("openhab", "openhab:01"),
                            ProviderEntry("google_home"),
                        ),
                        ChainMode.MIX,
                    ),
                ),
            ),
        )
        val rows = withHomes.resolveProviders(ProviderTool.SMART_HOME, play, all = true).associate { it.key to it.problem }
        assertEquals(ProviderProblem.NEEDS_SIGN_IN, rows["home_assistant:01"])
        assertNull(rows["home_assistant:02"])
        assertEquals(ProviderProblem.NEEDS_SIGN_IN, rows["smartthings"])
        assertEquals(ProviderProblem.NEEDS_SIGN_IN, rows["hue:01"])
        assertNull(rows["openhab:01"])
        // No Home APIs in this build: unavailable, not "sign in".
        assertEquals(ProviderProblem.UNAVAILABLE, rows["google_home"])
        assertNull(withHomes.resolveProviders(ProviderTool.SMART_HOME, play.copy(googleHome = true), all = true).first { it.key == "google_home" }.problem)

        val homes = withHomes.homes(play.copy(googleHome = true))
        assertEquals(listOf("home_assistant:02", "openhab:01", "google_home"), homes.map { it.key })
        assertEquals(HomeKind.OPENHAB, homes[1].kind)
        assertEquals("tok", homes[1].apiKey)
        assertEquals("home_assistant:02", withHomes.assistHome(play)!!.key)
        assertTrue(withHomes.homes(play).none { it.kind == HomeKind.GOOGLE_HOME })
    }

    @Test
    fun `a stored list wins over the old settings`() {
        val stored = ProviderChain(listOf(ProviderEntry("gifcities")))
        val s = KeyboardSettings(
            gif = GifSettings(klipyApiKey = "k"),
            providers = ProviderSettings(chains = mapOf(ProviderTool.GIF to stored)),
        )
        assertEquals(stored, s.providerChain(ProviderTool.GIF, play))
    }

    @Test
    fun `a word source a later build adds joins a saved list, a removed one stays out`() {
        val known = ProviderTypes.serving(ProviderTool.SYNONYMS).map { it.id } - "similar_words"
        val saved = ProviderChain(listOf(ProviderEntry("datamuse")), known = known)
        val s = KeyboardSettings(providers = ProviderSettings(chains = mapOf(ProviderTool.SYNONYMS to saved)))
        assertEquals(listOf("datamuse", "similar_words"), ids(s.providerChain(ProviderTool.SYNONYMS, play)))
        val stamped = saved.stamped(ProviderTool.SYNONYMS)
        assertEquals(listOf("datamuse"), ids(stamped.withNewTypes(ProviderTool.SYNONYMS)))
    }

    // ---- resolution ----

    @Test
    fun `the user's key wins, a missing one is named`() {
        val s = KeyboardSettings(
            providers = ProviderSettings(
                credentials = listOf(ProviderCredential("klipy", "klipy", apiKey = "mine")),
                chains = mapOf(ProviderTool.SEARCH to ProviderChain(listOf(ProviderEntry("tavily")))),
            ),
        )
        val tavily = s.resolveProviders(ProviderTool.SEARCH, play, all = true).single()
        assertEquals(ProviderProblem.NEEDS_KEY, tavily.problem)
        assertTrue(s.resolveProviders(ProviderTool.SEARCH, play).isEmpty())
        val klipy = s.resolveProviders(ProviderTool.GIF, play).first()
        assertEquals("mine", klipy.apiKey)
        assertFalse(klipy.usesBuiltInKey)
    }

    @Test
    fun `the no internet build asks only what is on the phone`() {
        val s = KeyboardSettings(gif = GifSettings(klipyApiKey = "k"))
        val rows = s.resolveProviders(ProviderTool.GIF, offline.copy(offlineGifPacks = true), all = true)
        assertEquals(ProviderProblem.UNAVAILABLE, rows.first { it.typeId == "klipy" }.problem)
        assertEquals(
            listOf("offline_gif_packs"),
            s.resolveProviders(ProviderTool.GIF, offline.copy(offlineGifPacks = true)).map { it.typeId },
        )
    }

    @Test
    fun `offline packs wait for a pack, DeepL takes a proxy alone`() {
        val s = KeyboardSettings(translate = TranslateSettings(deepl = DeepLSettings(endpoint = "https://proxy")))
        assertEquals(
            ProviderProblem.NEEDS_PACKS,
            s.resolveProviders(ProviderTool.GIF, play, all = true).first { it.typeId == "offline_gif_packs" }.problem,
        )
        assertTrue(s.resolveProviders(ProviderTool.TRANSLATE, fdroid).any { it.typeId == "deepl" })
    }

    // ---- pick mode (#727) ----

    @Test
    fun `pick mode asks only the chosen row, and the first when the choice is gone`() {
        val chain = ProviderChain(
            listOf(ProviderEntry("deepl"), ProviderEntry("google_translate"), ProviderEntry("mozhi", "mozhi:01", enabled = false)),
            ChainMode.PICK,
        )
        val s = KeyboardSettings(
            providers = ProviderSettings(
                listOf(
                    ProviderCredential("deepl", "deepl", apiKey = "d"),
                    ProviderCredential("mozhi:01", "mozhi", url = "https://mozhi"),
                ),
                mapOf(ProviderTool.TRANSLATE to chain),
            ),
        )
        val picked = { pick: String? -> s.resolveProviders(ProviderTool.TRANSLATE, play, pick = pick).map { it.key } }
        assertEquals(listOf("google_translate"), picked("google_translate"))
        assertEquals(listOf("deepl"), picked(""))
        // A row that is off cannot be chosen; the first that is on stands in.
        assertEquals(listOf("deepl"), picked("mozhi:01"))
        // No panel (the AI's tools): the whole list, in order.
        assertEquals(listOf("deepl", "google_translate"), picked(null))
        val choices = s.providerChoices(ProviderTool.TRANSLATE, "google_translate", play)!!
        assertEquals("google_translate", choices.chosen)
        assertTrue(choices.shown)
        // In fallback mode the panel has no chooser.
        val fallback = s.copy(providers = s.providers.copy(chains = mapOf(ProviderTool.TRANSLATE to chain.copy(mode = ChainMode.FALLBACK))))
        assertNull(fallback.providerChoices(ProviderTool.TRANSLATE, "", play))
        assertEquals(listOf("deepl", "google_translate"), fallback.resolveProviders(ProviderTool.TRANSLATE, play, pick = "google_translate").map { it.key })
    }

    @Test
    fun `a pick mode a tool does not offer reads as its default`() {
        val chains = ProviderCodec.decodeChains(
            ProviderCodec.encodeChains(mapOf(ProviderTool.CURRENCY_FIAT to ProviderChain(mode = ChainMode.PICK))),
        )
        assertEquals(ChainMode.FALLBACK, chains.getValue(ProviderTool.CURRENCY_FIAT).mode)
    }

    @Test
    fun `the OCR tool's old online reader becomes its list, and a vision reader needs a model`() {
        val s = KeyboardSettings(scanner = ScannerSettings(ocrOnlineUrl = "https://api", ocrOnlineModel = "", ocrOnlineKey = "k"))
        assertEquals(listOf("vision_ocr"), ids(s.providerChain(ProviderTool.OCR, play)))
        val row = s.resolveProviders(ProviderTool.OCR, play, all = true).single()
        assertEquals(ProviderProblem.NEEDS_MODEL, row.problem)
        assertEquals("k", row.apiKey)
        assertTrue(KeyboardSettings().providerChain(ProviderTool.PHOTO_TRANSLATE, play).entries.isEmpty())
    }

    // ---- edits ----

    @Test
    fun `a removed row takes its credential along unless another tool reads it`() {
        val s = KeyboardSettings(gif = GifSettings(klipyApiKey = "k"))
        val fromStickers = ProviderEdits.removeEntry(s, ProviderTool.STICKER, "klipy", play)
        assertNull(fromStickers.credentials)
        assertFalse(fromStickers.chain!!.has("klipy"))

        val server = ProviderCredential("libretranslate:01", "libretranslate", url = "https://lt")
        val added = ProviderEdits.saveCredential(s, server, ProviderTool.TRANSLATE, fdroid)
        val withServer = s.copy(
            providers = ProviderSettings(added.credentials, mapOf(ProviderTool.TRANSLATE to added.chain!!)),
        )
        assertTrue(withServer.providerChain(ProviderTool.TRANSLATE, fdroid).has("libretranslate:01"))
        val removed = ProviderEdits.removeEntry(withServer, ProviderTool.TRANSLATE, "libretranslate:01", fdroid)
        assertFalse(removed.credentials!!.any { it.id == "libretranslate:01" })
    }

    @Test
    fun `a new translation server answers alone until the others are switched back on`() {
        val s = KeyboardSettings(translate = TranslateSettings(deepl = DeepLSettings(apiKey = "d")))
        val server = ProviderCredential("translate_server:01", "translate_server", url = "http://box")
        val chain = ProviderEdits.saveCredential(s, server, ProviderTool.TRANSLATE, play).chain!!
        assertEquals(listOf("mlkit", "translate_server", "deepl", "google_translate"), ids(chain))
        assertEquals(listOf("mlkit", "translate_server"), on(chain))
    }

    @Test
    fun `saving the first credential keeps the old ones`() {
        val s = KeyboardSettings(webSearch = WebSearchSettings(braveApiKey = "b"))
        val change = ProviderEdits.saveCredential(s, ProviderCredential("klipy", "klipy", apiKey = "k"), null, play)
        assertEquals(setOf("brave", "klipy"), change.credentials!!.map { it.id }.toSet())
        assertEquals(setOf("brave"), s.providerCredentials().map { it.id }.toSet())
    }

    // ---- catalogue ----

    @Test
    fun `the no internet build offers only what runs on the phone`() {
        val groups = ProviderCatalog.addable(ProviderTool.GIF, ProviderChain(), offline)
        assertEquals(listOf("offline_gif_packs"), groups.flatMap { it.types }.map { it.id })
    }

    @Test
    fun `F-Droid suggests free software and lists proprietary keys after it`() {
        val groups = ProviderCatalog.addable(ProviderTool.GIF, ProviderChain(), fdroid)
        val suggested = groups.first { it.suggested }.types.map { it.id }
        assertTrue("commons" in suggested)
        assertFalse("klipy" in suggested)
        assertTrue(groups.first { !it.suggested }.types.any { it.id == "klipy" })
    }

    @Test
    fun `a single instance type already listed is not offered again, a server type is`() {
        val chain = ProviderChain(listOf(ProviderEntry("google_translate"), ProviderEntry("libretranslate", "libretranslate:01")))
        val offered = ProviderCatalog.addable(ProviderTool.TRANSLATE, chain, github).flatMap { it.types }.map { it.id }
        assertFalse("google_translate" in offered)
        assertTrue("libretranslate" in offered)
    }
}

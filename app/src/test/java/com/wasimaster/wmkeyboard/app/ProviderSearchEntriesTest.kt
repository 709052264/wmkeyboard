package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderEntry
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Providers as settings-search results, on the real index: every kind by
 * its own name, opening its own screen under the path of the list it is on,
 * and the rows the user added twice or named, built beside the index.
 */
class ProviderSearchEntriesTest {

    private val strings = XmlSearchStrings.forApp()
    private val index = settingsSearchIndex(strings)
    private val vocabulary = settingsSearchVocabulary(strings)
    private val home = strings.getString(com.wasimaster.wmkeyboard.common.R.string.common_settings)

    private fun kindEntry(tool: ProviderTool, type: ProviderType) =
        index.firstOrNull { it.route == providerRoute(tool, type) && it.weight == EntryWeight.TOOL }

    @Test
    fun `every kind is a result on every list it can join`() {
        // The on-device translators are indexed only where the build has them.
        val gated = setOf(ProviderTypes.ML_KIT.id, ProviderTypes.BERGAMOT.id, ProviderTypes.NLLB.id)
        val missing = ProviderTool.entries.flatMap { tool ->
            val host = providerHostTool(tool)
            if (host != null && !isSupportedTool(host)) return@flatMap emptyList()
            ProviderTypes.serving(tool)
                .filterNot { it.id in gated }
                .filter { kindEntry(tool, it) == null }
                .map { "${tool.id}/${it.id}" }
        }
        assertEquals("kinds with no result of their own", emptyList<String>(), missing)
    }

    @Test
    fun `a kind's result is its name, under its list's screen`() {
        val deepl = checkNotNull(kindEntry(ProviderTool.TRANSLATE, ProviderTypes.DEEPL))
        assertEquals(strings.getString(ProviderTypes.DEEPL.labelRes), deepl.title)
        assertEquals(strings.getString(ProviderTypes.DEEPL.descriptionRes(ProviderTool.TRANSLATE)), deepl.subtitle)
        assertEquals(
            listOf(
                strings.getString(com.wasimaster.wmkeyboard.R.string.home_tools_title),
                strings.getString(toolTitle(ToolbarTool.TRANSLATE)),
                deepl.title,
            ),
            deepl.screenPath,
        )
        assertEquals(ToolbarTool.TRANSLATE, deepl.tool)
        val whisper = checkNotNull(kindEntry(ProviderTool.VOICE, ProviderTypes.WHISPER))
        assertEquals(listOf(strings.getString(com.wasimaster.wmkeyboard.R.string.home_voice_title), whisper.title), whisper.screenPath)
    }

    @Test
    fun `a kind described differently on another list says so there`() {
        val synonyms = checkNotNull(kindEntry(ProviderTool.SYNONYMS, ProviderTypes.WIKTIONARY))
        assertEquals(strings.getString(ProviderTypes.WIKTIONARY.descriptionRes(ProviderTool.SYNONYMS)), synonyms.subtitle)
    }

    @Test
    fun `opening a kind seeds the steps above its screen, and they open`() {
        val deepl = checkNotNull(kindEntry(ProviderTool.TRANSLATE, ProviderTypes.DEEPL))
        val steps = settingsCrumbSeed(deepl, index, home)
        assertEquals(deepl.screenPath.dropLast(1), steps.map { it.title })
        assertEquals("tool/TRANSLATE", steps.last().route)
    }

    @Test
    fun `a row on a provider's screen has the provider as a step`() {
        val write = index.first { it.key.endsWith("#tooldetail_deepl_write_title") }
        assertEquals(providerRoute(ProviderTool.TRANSLATE, ProviderTypes.DEEPL), write.route)
        // The provider is the screen being opened, so it is not seeded; the tool above it is, and opens.
        assertEquals(listOf("tool/TRANSLATE"), settingsCrumbSeed(write, index, home).drop(1).map { it.route })
    }

    // ---- the user's own rows

    private fun row(type: ProviderType, key: String, name: String = "", url: String = "") = ResolvedProvider(
        entry = ProviderEntry(type.id, key.takeIf { it != type.id }),
        type = type,
        credential = ProviderCredential(key, type.id, name = name, url = url),
        apiKey = "",
        url = url,
        model = "",
        problem = null,
    )

    private val ownRows = mapOf(
        ProviderTool.TRANSLATE to listOf(
            // The kind's own row: its kind's result already opens it.
            row(ProviderTypes.DEEPL, ProviderTypes.DEEPL.id),
            row(ProviderTypes.LIBRETRANSLATE, "libretranslate:ab12", name = "Home server", url = "https://tr.home.lan"),
            row(ProviderTypes.LIBRETRANSLATE, "libretranslate:cd34", url = "https://libretranslate.com"),
        ),
    )

    private val instances = strings.providerInstanceRows(
        rows = { ownRows[it].orEmpty() },
        subtitle = { _, row -> hostOf(row.url) },
    )

    @Test
    fun `a second server and a named row are results of their own`() {
        assertEquals(
            listOf("Home server", strings.getString(ProviderTypes.LIBRETRANSLATE.labelRes)),
            instances.map { it.title },
        )
        val named = instances.first()
        assertEquals(providerRoute(ProviderTool.TRANSLATE, "libretranslate:ab12"), named.route)
        assertEquals("tr.home.lan", named.subtitle)
        assertEquals("Home server", named.screenPath.last())
        assertEquals(EntryWeight.TOOL, named.weight)
        assertEquals(instances.size, instances.distinctBy { it.key }.size)
        assertTrue(instances.none { it.key in index.map(SettingsSearchEntry::key) })
    }

    @Test
    fun `a row's nickname finds it`() {
        val all = searchableProviderEntries(index, instances, runnable = { _, _ -> true }, listed = { _, _ -> false })
        val first = rankSettings("home server", all, vocabulary).all.first()
        assertEquals(providerRoute(ProviderTool.TRANSLATE, "libretranslate:ab12"), first.route)
    }

    @Test
    fun `a kind the phone cannot run leaves the results unless a list has it`() {
        val whisper = providerRoute(ProviderTool.VOICE, ProviderTypes.WHISPER)
        val noWhisper = { _: ProviderTool, type: ProviderType -> type !== ProviderTypes.WHISPER }
        val dropped = searchableProviderEntries(index, emptyList(), noWhisper, listed = { _, _ -> false })
        assertTrue(dropped.none { it.route == whisper })
        // Rows on its screen go with it.
        assertTrue(index.count { it.route == whisper } > 1)
        assertTrue(dropped.size < index.size)
        val kept = searchableProviderEntries(
            index,
            emptyList(),
            noWhisper,
            listed = { tool, id -> tool == ProviderTool.VOICE && id == ProviderTypes.WHISPER.id },
        )
        assertEquals(index.count { it.route == whisper }, kept.count { it.route == whisper })
    }

    @Test
    fun `a provider route reads back as its list and row`() {
        val route = providerRoute(ProviderTool.TRANSLATE, "libretranslate:ab12")
        assertEquals(ProviderTool.TRANSLATE to "libretranslate:ab12", providerOfRoute(route))
        assertEquals(null, providerOfRoute("tool/TRANSLATE"))
        assertEquals(null, providerOfRoute("provider/nothing/deepl"))
    }
}

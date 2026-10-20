package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hidden keyword table, checked against the real index.
 *
 * A keyword is a promise that someone typing this word means this row. The
 * failure mode is not a missing keyword — it is a careless one, which takes a
 * query away from the row that deserved it. These are the rules that keep the
 * table honest; `SettingsSearchRankingTest` is what catches a keyword that
 * actually moves a query it should not have.
 */
class SettingsSearchKeywordsTest {

    private val strings = XmlSearchStrings.forApp()
    private val index = settingsSearchIndex(strings)

    private fun keywordsOf(titleRes: Int) = strings.getString(SettingsSearchKeywords.getValue(titleRes))

    @Test
    fun `every keyword is made of plain lower-case words`() {
        // Matched against text normalizeForSearch has already been through, so
        // a capital, an accent or a comma is a word no query can ever match.
        val malformed = SettingsSearchKeywords.keys.flatMap { title ->
            keywordsOf(title).split(' ').filter { it.isNotEmpty() }
                .filterNot { it == normalizeForSearch(it) }
        }
        assertEquals("keyword words no query can match", emptyList<String>(), malformed)
    }

    @Test
    fun `every keyword belongs to a row the index actually carries`() {
        val indexed = index.mapTo(mutableSetOf()) { it.titleRes }
        val orphans = SettingsSearchKeywords.keys.filterNot { it in indexed || it in ENGINE_GATED }
            .map { strings.resourceName(it) }
        assertEquals("keywords for rows no longer in the index", emptyList<String>(), orphans)
    }

    @Test
    fun `no keyword repeats a word the row already draws`() {
        // The matcher reads the title and the subtitle anyway, and it reads
        // them at a higher weight. A keyword that repeats one buys nothing and
        // hides the fact that the row was already findable by it.
        val redundant = mutableListOf<String>()
        for (entry in index) {
            val res = SettingsSearchKeywords[entry.titleRes] ?: continue
            val own = normalizeForSearch("${entry.title} ${entry.subtitle}")
                .split(' ').filterTo(mutableSetOf()) { it.isNotEmpty() }
            for (word in normalizeForSearch(strings.getString(res)).split(' ')) {
                if (word.isNotEmpty() && word in own) {
                    redundant += "${strings.resourceName(entry.titleRes)}: $word"
                }
            }
        }
        assertEquals("keywords the row's own words already cover", emptyList<String>(), redundant)
    }

    @Test
    fun `a keyword never outranks the row actually named by the query`() {
        // The property the whole table rests on: keywords can only fill in for
        // queries that find nothing or the wrong thing, never take one away
        // from the row whose name it is. MatchField.KEYWORDS sits below TITLE
        // so this holds by construction — this is the pin on that construction.
        val vocabulary = settingsSearchVocabulary(strings)
        for (entry in index) {
            val res = SettingsSearchKeywords[entry.titleRes] ?: continue
            for (word in strings.getString(res).split(' ')) {
                if (word.length < 3) continue
                val top = rankSettings(word, index, vocabulary).all.firstOrNull() ?: continue
                val named = normalizeForSearch(top.title) == normalizeForSearch(word)
                val keyworded = normalizeForSearch(entry.title) == normalizeForSearch(word)
                if (named && !keyworded) {
                    // Something is called exactly this. It must be first.
                    assertTrue(
                        "'$word' is the name of '${top.title}' and must lead it",
                        normalizeForSearch(top.title) == normalizeForSearch(word),
                    )
                }
            }
        }
    }

    @Test
    fun `the table is not padded out`() {
        // Keywords are for features known by another name, not a second
        // description of every row. If this ever approaches the size of the
        // index, the table has stopped being a list of names.
        assertTrue(
            "keyword table has ${SettingsSearchKeywords.size} rows against ${index.size} indexed",
            SettingsSearchKeywords.size < index.size / 3,
        )
    }

    private companion object {
        // Drawn only where their engine runs, and neither runs on this JVM:
        // Bergamot needs API 28, NLLB a 64-bit Android process.
        val ENGINE_GATED = setOf(
            R.string.tooldetail_translate_bergamot_group,
            R.string.tooldetail_translate_nllb_group,
        )
    }
}

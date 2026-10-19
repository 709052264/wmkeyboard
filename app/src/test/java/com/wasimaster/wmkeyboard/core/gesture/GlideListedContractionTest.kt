package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A contraction whose bare spelling is a word of its own (issue #784).
 *
 * `ill` is in the list, so the walk used to read the stroke at `ill`'s own
 * count, a small one, and a commoner word the same stroke passed won. The
 * stroke is drawn for *I'll* far more often, and commits it either way, so
 * it now stands at *I'll*'s count.
 */
class GlideListedContractionTest {

    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 90f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    /** Straight lines through the word's key centres, densely sampled. */
    private fun gestureFor(word: String): List<GesturePoint> {
        val anchors = word.toCharArray().toList()
            .fold(ArrayList<Char>()) { acc, c -> acc.also { if (acc.lastOrNull() != c) acc.add(c) } }
            .mapNotNull { centers[it.code] }
        val points = ArrayList<GesturePoint>()
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            for (step in 0..10) {
                val t = step / 10f
                points.add(GesturePoint(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)))
            }
        }
        return points
    }

    private fun engine(fixes: Boolean): SuggestionEngine {
        val list = Trie().apply {
            insert("ill", 5)
            insert("i'll", 5000)
            insert("oil", 400)
            insert("ilk", 300)
        }
        return SuggestionEngine(list, BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            englishSources = true
            primaryLanguageId = "en"
            apostropheFixes = fixes
        }
    }

    @Test
    fun `a stroke over i-l reads ill at the count of I'll`() {
        val words = engine(fixes = true).glide(gestureFor("ill"), grid, keyWidth, limit = 4).map { it.word }
        assertEquals("$words", "ill", words.first())
    }
}

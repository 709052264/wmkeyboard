package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A word the user saved with an apostrophe glides without one (#854), the way
 * the contractions of #451 and #784 do, wherever the bare spelling is no word.
 */
class GlideSavedApostropheWordTest {

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

    private fun engine(vararg saved: String): SuggestionEngine {
        val list = Trie().apply {
            insert("jenny", 300)
            insert("penny", 400)
            insert("its", 900)
            insert("it's", 800)
        }
        val lexicon = UserLexicon(null).apply { saved.forEach { addWord(it) } }
        return SuggestionEngine(list, BengaliPhoneticIndex(emptyList()), lexicon)
    }

    @Test
    fun `a saved possessive glides without the apostrophe`() {
        val words = engine("Jenny's").glide(gestureFor("jennys"), grid, keyWidth, limit = 4).map { it.word }
        assertEquals("$words", "jenny's", words.first().lowercase())
    }

    @Test
    fun `a bare spelling that is a word stays itself`() {
        val words = engine("it's").glide(gestureFor("its"), grid, keyWidth, limit = 4).map { it.word }
        assertTrue("$words", "its" in words)
    }
}

package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled English list is on one frequency scale (#792).
 *
 * It used to be three: a seed head numbered 10000 down to 1 by position (its
 * last few hundred words in alphabetical order), hand additions slotted into
 * that numbering, and an OpenSubtitles corpus band mapped 2600..1 by log
 * count. So `meet` (300) sat under `met` (1757), `food` (68) under `fed`
 * (984), and `wait`, `send` and `probably` were within ten of the floor,
 * whatever a decoder made of the stroke. `scripts/rescale_en_dictionary.py`
 * now puts every word on the corpus band's line; these pairs are the ones the
 * report named, plus the shape the engine relies on.
 */
class EnglishFrequencyScaleTest {

    private val entries: Map<String, Int> = listOf(
        File("dictionaries-src/en.txt"),
        File("app/dictionaries-src/en.txt"),
    ).first { it.isFile }
        .readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .associate { line ->
            val cut = line.lastIndexOf(' ')
            line.substring(0, cut).trim() to line.substring(cut + 1).toInt()
        }

    private fun f(word: String) = entries[word] ?: error("$word is not in en.txt")

    @Test
    fun `a commoner word outranks a rarer one it is often confused with`() {
        for ((common, rarer) in listOf(
            "meet" to "met", "met" to "net", "food" to "fed", "wait" to "wit",
            "probably" to "proudly", "send" to "sand", "well" to "we'll",
        )) {
            assertTrue("$common ${f(common)} vs $rarer ${f(rarer)}", f(common) > f(rarer))
        }
    }

    @Test
    fun `everyday words are nowhere near the floor`() {
        for (word in listOf("wait", "send", "probably", "meet", "food", "market", "build", "expect")) {
            assertTrue("$word is ${f(word)}", f(word) > 2_500)
        }
    }

    @Test
    fun `the seed head's alphabetical tail no longer outranks common words`() {
        // `zoo` was 120 and `wait` 8, because the head's tail ran a-z.
        assertTrue(f("wait") > f("zoo"))
        assertTrue(f("send") > f("yard"))
    }

    @Test
    fun `the commonest word is the engine's reference top, and nothing is below 1`() {
        // SuggestionEngine.REFERENCE_TOP_FREQUENCY: every weight was tuned on it.
        assertEquals(10_000, entries.values.max())
        assertTrue(entries.values.min() >= 1)
    }

    @Test
    fun `contractions keep their order among themselves`() {
        assertTrue(f("it's") > f("don't"))
        assertTrue(f("don't") > f("i'm"))
        assertTrue(f("i'm") > f("that's"))
        assertTrue(f("i'd") > f("id"))
    }
}

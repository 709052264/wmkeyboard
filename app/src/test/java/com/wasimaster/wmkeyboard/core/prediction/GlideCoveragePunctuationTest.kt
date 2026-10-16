package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.gesture.GlideCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Elisions and compounds are no grid's to draw, so they must not count against
 * one (#707). A French layout imported from Unexpected Keyboard, with é è ê à ù
 * ç on its arms and no â î ô, measured 0.897 against the AOSP list because one
 * word in eleven of the sample was `c'est`, `d'un` or `lui-même`, and lost
 * glide typing altogether.
 */
class GlideCoveragePunctuationTest {

    private val azerty: Set<Int> = ("abcdefghijklmnopqrstuvwxyz" + "éèêàùç").map { it.code }.toSet()

    private fun walkers(words: List<String>) =
        CompositeWordSource.ofLanguage(null, PackedTrie.of(words.mapIndexed { i, w -> w to 1000 - i }))
            .walkers()

    @Test fun elisionsAndCompoundsAreLeftOut() {
        val french = listOf("c'est", "d'une", "qu'il", "lui-même", "pour", "dans", "été", "très")
        assertEquals(1f, GlideCoverage.measure(walkers(french), azerty))
    }

    @Test fun aMissingLetterStillCounts() {
        // château needs â, which this grid has nowhere: one word in four fails.
        val french = listOf("c'est", "pour", "dans", "château", "très")
        assertEquals(0.75f, GlideCoverage.measure(walkers(french), azerty))
    }

    @Test fun punctuationTheGridCarriesIsAsked() {
        // Klingon: the apostrophe is a letter there and the alphabet holds it,
        // so its words are measured like any other.
        val klingon = setOf('\''.code) + "abcdeghjlmnopqrstuvwy".map { it.code }
        assertTrue(GlideCoverage.asksTheLetters("qa'pla'", klingon))
        assertFalse(GlideCoverage.asksTheLetters("c'est", azerty))
        assertFalse(GlideCoverage.asksTheLetters("lui-même", azerty))
        // Letters the grid lacks are a question about the grid, not skipped.
        assertTrue(GlideCoverage.asksTheLetters("château", azerty))
        // Asked, and drawn; `xuz` has no x or z on the grid and fails.
        assertEquals(0.5f, GlideCoverage.measure(walkers(listOf("qa'pla'", "xuz")), klingon))
    }
}

package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search over a neural glide model's emissions. The emissions here are
 * written by hand — one clear class per step — so each case reads as the
 * spelling the model "saw".
 */
class CtcGlideBeamTest {

    private val beam = CtcGlideBeam()

    private fun sources(vararg entries: Pair<String, Int>): List<FuzzyBeamSearch.WalkSource> {
        val trie = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        return trie.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) }
    }

    /** One step per character of [steps]: a..z is that letter, '_' the blank. */
    private fun emissions(steps: String): SwipeEmissions {
        val out = FloatArray(steps.length * SwipeEmissions.CLASSES) { -9f }
        steps.forEachIndexed { t, c ->
            val cls = if (c == '_') SwipeEmissions.BLANK else c - 'a'
            out[t * SwipeEmissions.CLASSES + cls] = -0.05f
        }
        return SwipeEmissions(steps.length, out)
    }

    private fun words(steps: String, vararg entries: Pair<String, Int>) =
        beam.decode(emissions(steps), sources(*entries), limit = 4).map { it.word }

    @Test
    fun spellsTheWordTheStepsSpell() {
        val read = words("__hh_ee_ll_oo__", "hello" to 500, "help" to 900, "hell" to 400, "hero" to 300)
        assertEquals("hello", read.first())
    }

    @Test
    fun aDoubledLetterNeedsNoBlankBetweenItsHalves() {
        // The stroke shows ll as one key visited once; the decoder model
        // emits it as one run, and the search reads "hello" off it.
        val read = words("_h_e_llll_o_", "hello" to 500, "helo" to 400)
        assertEquals("hello", read.first())
    }

    @Test
    fun neverSpellsWhatNoTrieHolds() {
        val read = words("_q_x_z_", "the" to 900, "and" to 800)
        assertTrue(read.none { it == "qxz" })
    }

    @Test
    fun stepsOverAHyphenTheStrokeNeverDraws() {
        val read = words("_e_m_a_i_l_", "e-mail" to 500, "mail" to 400)
        assertEquals("e-mail", read.first())
    }

    @Test
    fun keepsTheListsCapitals() {
        val read = words("_p_a_r_i_s_", "Paris" to 500, "pairs" to 300)
        assertEquals("Paris", read.first())
    }

    @Test
    fun frequencySettlesWhatTheStrokeLeavesOpen() {
        // Equal evidence for both: the commoner word leads.
        val out = emissions("_t_h_e_n_").logProbs.copyOf()
        val n = 7 * SwipeEmissions.CLASSES
        out[n + ('n' - 'a')] = -0.7f
        out[n + ('m' - 'a')] = -0.7f
        val read = beam.decode(
            SwipeEmissions(9, out),
            sources("then" to 50, "them" to 5_000),
            limit = 2,
        ).map { it.word }
        assertEquals(listOf("them", "then"), read)
    }

    @Test
    fun aSingleLetterIsNeverARead() {
        assertTrue(words("__a__", "a" to 900, "at" to 10).none { it == "a" })
    }
}

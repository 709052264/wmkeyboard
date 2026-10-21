package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.gesture.eval.GlideGrid
import com.wasimaster.wmkeyboard.core.gesture.eval.SwipeCorpus
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- what the stroke says beside the emissions ----

    private val grid = GlideGrid.of(BuiltInLayouts.QWERTY)
    private val keys = GlideKeyMap.of(grid.keyCenters(SwipeCorpus.KEY_WIDTH), SwipeCorpus.KEY_WIDTH)

    private fun stroke(
        word: String,
        loops: Boolean = false,
        noise: SwipeCorpus.Noise = SwipeCorpus.Noise.CLEAN,
    ): CtcGlideBeam.Stroke {
        val profile = noise.profile.copy(loopOnDoubles = if (loops) 1f else 0f)
        val path = requireNotNull(SwipeCorpus(7L, grid = grid).swipe(word, profile))
        return CtcGlideBeam.Stroke(path, keys, SwipeCorpus.KEY_WIDTH, GlideBeam.Tuning.DEFAULT, null)
    }

    @Test
    fun aStrokeLiftedPartWayOffersTheWordItWasSpelling() {
        // One reading kept, so "dictionary" is not among the readings: read
        // whole, it would pay for four letters the steps never showed.
        val lexicon = sources("dictionary" to 900, "dictate" to 100, "dict" to 50)
        val read = beam.decode(emissions("_d_i_c_t_i_"), lexicon, limit = 1, stroke = stroke("dicti"), lookAhead = 2)
        val guess = read.first { it.word == "dictionary" }
        assertEquals(5, guess.ahead)
        assertTrue(beam.decode(emissions("_d_i_c_t_i_"), lexicon, limit = 1).none { it.ahead > 0 })
    }

    @Test
    fun aLoopOnTheKeyChoosesTheDoubledSpelling() {
        // Steps both spellings read alike: "god" stays on its o, "good"
        // takes its second. The commoner spelling leads an unmarked stroke.
        val lexicon = sources("god" to 9_000, "good" to 50)
        val plain = beam.decode(emissions("_g_oo_d_"), lexicon, limit = 2, stroke = stroke("good"))
        assertEquals("god", plain.first().word)
        val looped = beam.decode(emissions("_g_oo_d_"), lexicon, limit = 2, stroke = stroke("good", loops = true))
        assertEquals("good", looped.first().word)
    }

    @Test
    fun aLoopMovesNoWordWithoutADoubledRival() {
        val lexicon = sources("god" to 900, "gad" to 800)
        val plain = beam.decode(emissions("_g_o_d_"), lexicon, limit = 2)
        val looped = beam.decode(emissions("_g_o_d_"), lexicon, limit = 2, stroke = stroke("good", loops = true))
        assertEquals(plain.map { it.word to it.score }, looped.map { it.word to it.score })
    }

    @Test
    fun aWordDrawnTheWayTheUserDrewItBeforeGains() {
        // A noisy hand: a clean stroke is the ideal path, with nothing to gain.
        val word = "then"
        val drawn = stroke(word, noise = SwipeCorpus.Noise.TYPICAL)
        val shape = requireNotNull(GlideBeam().sampleShape(drawn.path, SwipeCorpus.KEY_WIDTH, GlideWorkspace()))
        val layout = GlideKeyMap.fingerprint(grid.keyCenters(SwipeCorpus.KEY_WIDTH), SwipeCorpus.KEY_WIDTH)
        val store = GlideShapeStore(null).apply { learn(GlideShapeSample(layout, shape), word) }
        val lexicon = sources("then" to 500, "than" to 500)
        val without = beam.decode(emissions("_t_h_e_n_"), lexicon, limit = 2, stroke = drawn)
        val with = beam.decode(
            emissions("_t_h_e_n_"), lexicon, limit = 2,
            stroke = CtcGlideBeam.Stroke(drawn.path, keys, SwipeCorpus.KEY_WIDTH, GlideBeam.Tuning.DEFAULT, store.forLayout(layout)),
        )
        val gained = with.first { it.word == word }.score - without.first { it.word == word }.score
        assertTrue("gain $gained", gained > 0.0)
        assertEquals(
            with.first { it.word == "than" }.score.toRawBits(),
            without.first { it.word == "than" }.score.toRawBits(),
        )
        assertFalse(gained > CtcGlideBeam.Scoring().shapeWeight * GlideBeam.Tuning.DEFAULT.learnedShapeGain + 1e-9)
    }
}

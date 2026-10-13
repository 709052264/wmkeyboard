// The harness runs on the JVM only and stdout IS its report channel — the
// device-invisibility rationale behind the println ban does not apply here.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideShapeSample
import com.wasimaster.wmkeyboard.core.gesture.GlideShapeSource
import com.wasimaster.wmkeyboard.core.gesture.GlideShapeStore
import com.wasimaster.wmkeyboard.core.gesture.GlideWorkspace
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What learning how the user draws each word is worth (issue #52),
 * measured the way it is used: a word the user has swiped and kept once,
 * swiped again by the same hand.
 *
 * The corpus draws every stroke fresh, so on its own it has no notion of one
 * user drawing one word the same way twice. [SwipeCorpus.Signature] gives it
 * one: a fixed wobble of every anchor and a fixed scaling of every corner,
 * drawn per word, so the second draw of a word shares its motor habits with
 * the first while its tremor, bias and shrink are fresh. Like the corpus's
 * dwell, that is a claim about hands rather than a measurement, so the gain
 * here is an upper bound until a device says otherwise.
 *
 * Two floors. The words the user has drawn before must decode better with
 * their shapes than without; and the words they have not — decoded against
 * the full store — must not decode worse, or the shapes are buying their
 * words' accuracy with everyone else's.
 *
 * Measured 2026-09-08 at seed 42, 400 typical words drawn twice: top-1 on
 * the second draw .9150 without the shapes, .9375 with them; 280 words the
 * hand never drew, decoded against the full store, .9000 either way.
 * Re-measured 2026-10-13 after the store moved to key-width shapes and the
 * shape channel learned to normalise them before comparing: .9175 to .9375
 * on the second draw, .8964 either way on the words never drawn. The
 * decode itself costs the same with a thousand-word store as without one
 * (`GestureLatencyBench`).
 */
class GlideShapeLearningTest {

    private companion object {
        const val SEED = 42L
        const val SIGNATURE = 7L
        const val CASES = 400
        const val CONTROL_CASES = 300
        const val RANK_DEPTH = 8

        /** What the learned shapes must be worth on the words they belong to, in top-1 points. */
        const val MIN_GAIN = 0.02

        /** What they may cost the words they do not: ~1 binomial σ at 300 strokes. */
        const val MAX_LOSS = 0.015
    }

    private fun entries(): List<Pair<String, Int>> {
        val file = listOf(File("dictionaries-src/en.txt"), File("app/dictionaries-src/en.txt"))
            .firstOrNull { it.exists() } ?: error("en.txt not found (cwd=${File(".").absolutePath})")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    @Test
    fun learnedShapesPayOnTheirWordsAndCostNothingElsewhere() {
        val entries = entries()
        val trie = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        val sources = trie.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        }
        val grid = GlideGrid.of(BuiltInLayouts.QWERTY)
        val centers = grid.keyCenters(SwipeCorpus.KEY_WIDTH)
        val keys = GlideKeyMap.of(centers, SwipeCorpus.KEY_WIDTH)
        val layout = GlideKeyMap.fingerprint(centers, SwipeCorpus.KEY_WIDTH)
        val signature = SwipeCorpus.Signature(SIGNATURE)
        val profile = SwipeCorpus.Noise.TYPICAL.profile

        // The first draws: what the user kept, and what the store learns from.
        val first = SwipeCorpus(SEED, grid = grid, signature = signature)
            .generate(entries, SwipeCorpus.Noise.TYPICAL, CASES)
        // The second draws of the same words by the same hand, fresh noise.
        val again = SwipeCorpus(SEED + 1, grid = grid, signature = signature)
        val second = first.map { case -> case.intended to (again.swipe(case.intended, profile) ?: error(case.intended)) }
        // Words the user never drew, by the same hand, for the control.
        val other = SwipeCorpus(SEED + 2, grid = grid, signature = signature)
            .generate(entries, SwipeCorpus.Noise.TYPICAL, CONTROL_CASES)
            .filter { case -> first.none { it.intended == case.intended } }

        val beam = GlideBeam()
        val workspace = GlideWorkspace()
        val store = GlideShapeStore(null)
        for (case in first) {
            val shape = beam.sampleShape(case.path, SwipeCorpus.KEY_WIDTH, workspace) ?: continue
            store.learn(GlideShapeSample(layout, shape), case.intended)
        }
        val learned = store.forLayout(layout) ?: error("nothing learned")

        val cold = top1(second, beam, keys, sources, workspace, null)
        val emptyStore = top1(second, beam, keys, sources, workspace, GlideShapeStore(null).forLayout(layout))
        val warm = top1(second, beam, keys, sources, workspace, learned)
        val controlCold = top1(other.map { it.intended to it.path }, beam, keys, sources, workspace, null)
        val controlWarm = top1(other.map { it.intended to it.path }, beam, keys, sources, workspace, learned)

        println("=== glide shape learning (seed=$SEED, signature=$SIGNATURE, ${second.size} words drawn twice, ${other.size} control) ===")
        println(String.format(Locale.ROOT, "drawn before: cold=%.4f warm=%.4f", cold, warm))
        println(String.format(Locale.ROOT, "never drawn:  cold=%.4f warm=%.4f", controlCold, controlWarm))

        assertEquals("an empty store must change nothing", cold, emptyStore, 0.0)
        assertTrue("learned shapes gained only ${warm - cold} on their own words", warm - cold >= MIN_GAIN)
        assertTrue("learned shapes cost ${controlCold - controlWarm} on other words", controlCold - controlWarm <= MAX_LOSS)
    }

    @Suppress("LongParameterList")
    private fun top1(
        cases: List<Pair<String, List<com.wasimaster.wmkeyboard.core.gesture.GesturePoint>>>,
        beam: GlideBeam,
        keys: GlideKeyMap,
        sources: List<FuzzyBeamSearch.WalkSource>,
        workspace: GlideWorkspace,
        shapes: GlideShapeSource?,
    ): Double {
        var hits = 0
        for ((intended, path) in cases) {
            val decoded = beam.decode(path, keys, SwipeCorpus.KEY_WIDTH, sources, workspace, RANK_DEPTH, shapes)
            if (decoded.firstOrNull()?.word.equals(intended, ignoreCase = true)) hits++
        }
        return hits / cases.size.toDouble()
    }
}

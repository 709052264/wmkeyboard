// The harness runs on the JVM only and stdout IS its report channel.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.engine.NativeEngine
import com.wasimaster.wmkeyboard.core.gesture.eval.GlideGrid
import com.wasimaster.wmkeyboard.core.gesture.eval.SwipeCorpus
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.Trie
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The Rust glide decoder behind [GlideBeam] reads every stroke the way the
 * Kotlin one does (native/RUST_NEON_PLAN.md step 4).
 *
 * The gesture eval's corpus, every noise level plus one that dwells and
 * loops, decoded against the bundled English list (as a plain [Trie], which
 * gets a Rust copy, and as a [PackedTrie]) with a user tier of hyphenated,
 * dotted and apostrophe words. Each stroke runs through the shipped weights,
 * wiggles on, the vocabulary cap, wide anchor radii, look-ahead, the user's
 * learned shapes and the shape seeding; every candidate's word, score, shape
 * cost, tier and look-ahead must be bit-identical, and so must the
 * alignment of the intended word and the stored shape of the stroke.
 */
class NativeGlideParityTest {

    private companion object {
        const val SEED = 42L
        const val CASES = 150
        const val LIMIT = 8
        val KEY_WIDTH = SwipeCorpus.KEY_WIDTH
    }

    @Before
    fun native() {
        assumeTrue("libwm_engine_jni is not built for this host", NativeEngine.loaded)
    }

    private fun entries(): List<Pair<String, Int>> {
        val file = listOf(File("dictionaries-src/en.txt"), File("app/dictionaries-src/en.txt"))
            .firstOrNull { it.exists() } ?: error("en.txt not found (cwd=${File(".").absolutePath})")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    private var decodes = 0
    private var candidates = 0
    private var alignments = 0

    private fun same(expected: List<GlideBeam.Candidate>, actual: List<GlideBeam.Candidate>, what: String) {
        assertEquals("$what: words", expected.map { it.word }, actual.map { it.word })
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            assertEquals("$what: score of ${e.word}", e.score.toRawBits(), a.score.toRawBits())
            assertEquals("$what: shape cost of ${e.word}", e.shapeCost.toRawBits(), a.shapeCost.toRawBits())
            assertEquals("$what: tier of ${e.word}", e.tier, a.tier)
            assertEquals("$what: look-ahead of ${e.word}", e.ahead, a.ahead)
        }
        decodes++
        candidates += expected.size
    }

    private fun <T> kotlinSide(block: () -> T): T {
        val before = NativeEngine.forceKotlin
        NativeEngine.forceKotlin = true
        try {
            return block()
        } finally {
            NativeEngine.forceKotlin = before
        }
    }

    @Suppress("LongParameterList")
    private fun checkDecode(
        beam: GlideBeam,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        sources: List<FuzzyBeamSearch.WalkSource>,
        shapes: GlideShapeSource?,
        lookAhead: Int,
        what: String,
    ) {
        val ws = GlideWorkspace()
        val expected = kotlinSide { beam.decode(path, keys, KEY_WIDTH, sources, ws, LIMIT, shapes, lookAhead) }
        val runs = GlideBeam.nativeRuns.get()
        val actual = beam.decode(path, keys, KEY_WIDTH, sources, ws, LIMIT, shapes, lookAhead)
        assertEquals("$what: the decode did not run in Rust", runs + 1, GlideBeam.nativeRuns.get())
        same(expected, actual, what)
    }

    private fun checkAlign(beam: GlideBeam, word: String, path: List<GesturePoint>, keys: GlideKeyMap, what: String) {
        val ws = GlideWorkspace()
        val expected = kotlinSide { beam.align(word, path, keys, KEY_WIDTH, ws) }
        val actual = beam.align(word, path, keys, KEY_WIDTH, ws)
        assertEquals("$what: align $word", expected == null, actual == null)
        if (expected == null || actual == null) return
        assertArrayEquals("$what: keys", expected.keys, actual.keys)
        assertArrayEquals("$what: samples", expected.samples, actual.samples)
        assertArrayEquals("$what: chars", expected.chars, actual.chars)
        assertEquals("$what: sample count", expected.sampleCount, actual.sampleCount)
        assertEquals("$what: x", expected.x.map { it.toRawBits() }, actual.x.map { it.toRawBits() })
        assertEquals("$what: y", expected.y.map { it.toRawBits() }, actual.y.map { it.toRawBits() })
        alignments++
    }

    @Test
    fun everyStrokeDecodesTheSame() {
        val entries = entries()
        val trie = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        val packed = PackedTrie.of(entries)
        val user = Trie().apply {
            for ((i, word) in listOf("e-mail", "re-read", "jenny's", "a.m.", "rock&roll", "c++", "we're", "glide").withIndex()) {
                insert(word, 20 + i)
            }
        }
        val grid = GlideGrid.of(BuiltInLayouts.QWERTY)
        val centers = grid.keyCenters(KEY_WIDTH)
        val keys = GlideKeyMap.of(centers, KEY_WIDTH)
        val layout = GlideKeyMap.fingerprint(centers, KEY_WIDTH)

        fun sources(dictionary: com.wasimaster.wmkeyboard.core.prediction.WordSource) =
            dictionary.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) } +
                user.walkers().map { FuzzyBeamSearch.WalkSource(it, 1.5, FuzzyBeamSearch.Tier.USER) }

        val profiles = SwipeCorpus.Noise.entries.map { it.name to it.profile } +
            ("DWELL_LOOP" to SwipeCorpus.Noise.TYPICAL.profile.copy(letterDwell = 0.3f, loopOnDoubles = 1f))
        val corpus = SwipeCorpus(SEED, grid = grid)
        val cases = profiles.flatMap { (name, profile) ->
            corpus.generate(entries, profile, CASES).map { name to it }
        }

        // The user's learned shapes: the first draw of every tenth word.
        val store = GlideShapeStore(null)
        val shaping = GlideBeam()
        for ((_, case) in cases.filterIndexed { i, _ -> i % 10 == 0 }) {
            val shape = kotlinSide { shaping.sampleShape(case.path, KEY_WIDTH, GlideWorkspace()) } ?: continue
            store.learn(GlideShapeSample(layout, shape), case.intended)
        }
        val learned = store.forLayout(layout) ?: error("nothing learned")

        val default = GlideBeam.Tuning()
        val tunings = listOf(
            "shipped" to default,
            "wiggles" to GlideBeam.Tuning.WIGGLES_ON,
            "capped" to default.copy(vocabularyRank = 20_000),
            "wide anchors" to default.copy(startRadius = default.startRadius + 0.6f, endRadius = default.endRadius + 0.6f),
            "seeding" to default.copy(shapeSeeding = true),
        )
        for ((tuningName, tuning) in tunings) {
            val beam = GlideBeam(tuning)
            for ((index, named) in cases.withIndex()) {
                val (noise, case) = named
                val what = "$tuningName/$noise #$index '${case.intended}'"
                val dictionary = if (index % 2 == 0) trie else packed
                val all = sources(dictionary)
                checkDecode(beam, case.path, keys, all, null, 0, "$what plain")
                checkDecode(beam, case.path, keys, all, null, 3, "$what look-ahead")
                checkDecode(beam, case.path, keys, all, learned, if (index % 3 == 0) 3 else 0, "$what shapes")
                if (tuningName == "shipped" || tuningName == "wide anchors") {
                    checkAlign(beam, case.intended, case.path, keys, what)
                    val shape = kotlinSide { beam.sampleShape(case.path, KEY_WIDTH, GlideWorkspace()) }
                    assertArrayEquals("$what: sampled shape", shape, beam.sampleShape(case.path, KEY_WIDTH, GlideWorkspace()))
                }
            }
        }
        println("glide parity: $decodes decodes, $candidates candidates, $alignments alignments identical")
    }
}

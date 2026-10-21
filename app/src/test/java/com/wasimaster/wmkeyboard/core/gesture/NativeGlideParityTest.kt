// The harness runs on the JVM only and stdout IS its report channel.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.gesture.eval.DuetCorpus
import com.wasimaster.wmkeyboard.core.gesture.eval.GlideGrid
import com.wasimaster.wmkeyboard.core.gesture.eval.SwipeCorpus
import com.wasimaster.wmkeyboard.core.gesture.reference.KotlinCtcGlideBeam
import com.wasimaster.wmkeyboard.core.gesture.reference.KotlinDuetBeam
import com.wasimaster.wmkeyboard.core.gesture.reference.KotlinDuetWorkspace
import com.wasimaster.wmkeyboard.core.gesture.reference.KotlinGlideBeam
import com.wasimaster.wmkeyboard.core.gesture.reference.KotlinGlideWorkspace
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.Trie
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Rust glide decoders behind [GlideBeam], [DuetBeam] and [CtcGlideBeam]
 * read every stroke the way the Kotlin ones they replaced did
 * ([KotlinGlideBeam], [KotlinDuetBeam], [KotlinCtcGlideBeam], kept as the
 * references; native/RUST_NEON_PLAN.md step 4).
 *
 * The gesture eval's corpus, every noise level plus one that dwells and
 * loops, decoded against the bundled English list (as a plain [Trie], which
 * gets a Rust copy, and as a [PackedTrie]) with a user tier of hyphenated,
 * dotted and apostrophe words. Each stroke runs through the shipped weights,
 * wiggles on, the vocabulary cap, wide anchor radii, look-ahead, the user's
 * learned shapes and the shape seeding; every candidate's word, score, shape
 * cost, tier and look-ahead must be bit-identical, and so must the
 * alignment of the intended word and the stored shape of the stroke.
 *
 * [DuetBeam] gets the two-thumb eval's corpus, with and without its clock,
 * whole words and prefixes; [CtcGlideBeam] gets emissions peaked along
 * sampled words, with noise, at both beam widths and under the cap, and
 * then with the stroke they stand for: its loops, look-ahead and the
 * learned shapes.
 */
class NativeGlideParityTest {

    private companion object {
        const val SEED = 42L
        const val CASES = 150
        const val LIMIT = 8
        val KEY_WIDTH = SwipeCorpus.KEY_WIDTH
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

    @Suppress("LongParameterList")
    private fun checkDecode(
        beams: Pair<GlideBeam, KotlinGlideBeam>,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        sources: List<FuzzyBeamSearch.WalkSource>,
        shapes: GlideShapeSource?,
        lookAhead: Int,
        what: String,
    ) {
        val expected = beams.second.decode(path, keys, KEY_WIDTH, sources, KotlinGlideWorkspace(), LIMIT, shapes, lookAhead)
        val actual = beams.first.decode(path, keys, KEY_WIDTH, sources, GlideWorkspace(), LIMIT, shapes, lookAhead)
        same(expected, actual, what)
    }

    private fun checkAlign(beams: Pair<GlideBeam, KotlinGlideBeam>, word: String, path: List<GesturePoint>, keys: GlideKeyMap, what: String) {
        val expected = beams.second.align(word, path, keys, KEY_WIDTH, KotlinGlideWorkspace())
        val actual = beams.first.align(word, path, keys, KEY_WIDTH, GlideWorkspace())
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
        val shaping = KotlinGlideBeam()
        for ((_, case) in cases.filterIndexed { i, _ -> i % 10 == 0 }) {
            val shape = shaping.sampleShape(case.path, KEY_WIDTH, KotlinGlideWorkspace()) ?: continue
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
            val beam = GlideBeam(tuning) to KotlinGlideBeam(tuning)
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
                    val shape = beam.second.sampleShape(case.path, KEY_WIDTH, KotlinGlideWorkspace())
                    assertArrayEquals("$what: sampled shape", shape, beam.first.sampleShape(case.path, KEY_WIDTH, GlideWorkspace()))
                }
            }
        }
        println("glide parity: $decodes decodes, $candidates candidates, $alignments alignments identical")
    }

    private fun dictionaries(): Triple<List<Pair<String, Int>>, Trie, PackedTrie> {
        val entries = entries()
        val trie = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        return Triple(entries, trie, PackedTrie.of(entries))
    }

    private fun userTrie() = Trie().apply {
        for ((i, word) in listOf("e-mail", "re-read", "jenny's", "a.m.", "glide", "duet").withIndex()) insert(word, 20 + i)
    }

    private fun withUser(dictionary: com.wasimaster.wmkeyboard.core.prediction.WordSource, user: Trie) =
        dictionary.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) } +
            user.walkers().map { FuzzyBeamSearch.WalkSource(it, 1.5, FuzzyBeamSearch.Tier.USER) }

    @Test
    fun everyTwoThumbWordDecodesTheSame() {
        val (entries, trie, packed) = dictionaries()
        val user = userTrie()
        val grid = GlideGrid.of(BuiltInLayouts.QWERTY)
        val keys = GlideKeyMap.of(grid.keyCenters(KEY_WIDTH), KEY_WIDTH)
        val corpus = DuetCorpus(SEED, grid = grid)
        val looping = SwipeCorpus.Noise.TYPICAL.profile.copy(loopOnDoubles = 1f)
        val cases = SwipeCorpus.Noise.entries.flatMap { corpus.generate(entries, it, CASES) } +
            corpus.generate(entries, SwipeCorpus.Noise.TYPICAL, CASES, looping)
        val default = GlideBeam.Tuning()
        val beams = listOf(
            "shipped" to default,
            "capped" to default.copy(vocabularyRank = 20_000),
            "wide anchors" to default.copy(startRadius = default.startRadius + 0.6f, endRadius = default.endRadius + 0.6f),
        ).map { (name, tuning) -> name to (DuetBeam(tuning) to KotlinDuetBeam(tuning)) }
        var count = 0
        for ((name, beam) in beams) {
            for ((index, case) in cases.withIndex()) {
                val sources = withUser(if (index % 2 == 0) trie else packed, user)
                val clockless = case.tokens.map { token ->
                    when (token) {
                        is GlideToken.Tap -> GlideToken.Tap(token.x, token.y, 0L)
                        is GlideToken.Swipe -> GlideToken.Swipe(token.points.map { it.copy(t = 0L) })
                    }
                }
                for ((tokens, clock) in listOf(case.tokens to "clocked", clockless to "clockless")) {
                    for (partial in listOf(false, true)) {
                        val what = "$name #$index '${case.intended}' $clock partial=$partial"
                        val expected = beam.second.decode(tokens, keys, KEY_WIDTH, sources, KotlinDuetWorkspace(), LIMIT, partial)
                        val actual = beam.first.decode(tokens, keys, KEY_WIDTH, sources, DuetWorkspace(), LIMIT, partial)
                        same(expected, actual, what)
                        count++
                    }
                }
            }
        }
        println("duet parity: $count decodes, $candidates candidates identical")
    }

    /** Emissions peaked along [word]'s letters, a few steps each with blanks between, plus noise. */
    private fun emissionsFor(word: String, random: Random, noise: Float): SwipeEmissions {
        val classes = SwipeEmissions.CLASSES
        val steps = ArrayList<Int>()
        for (ch in word.lowercase()) {
            val cls = ch - 'a'
            if (cls !in 0 until 26) continue
            repeat(1 + random.nextInt(3)) { steps.add(cls) }
            if (random.nextFloat() < 0.4f) steps.add(SwipeEmissions.BLANK)
        }
        val out = FloatArray(steps.size * classes)
        for ((t, cls) in steps.withIndex()) {
            var sum = 0.0
            val raw = DoubleArray(classes) { c ->
                val logit = (if (c == cls) 6.0 else 0.0) + noise * random.nextDouble(-3.0, 3.0)
                exp(logit).also { sum += it }
            }
            for (c in 0 until classes) out[t * classes + c] = ln(raw[c] / sum).toFloat()
        }
        return SwipeEmissions(steps.size, out)
    }

    @Test
    fun everyNeuralReadingDecodesTheSame() {
        val (entries, trie, packed) = dictionaries()
        val user = userTrie()
        val random = Random(SEED)
        val words = entries.filter { (w, _) -> w.length >= 2 }.shuffled(random).take(CASES * 3).map { it.first } +
            listOf("email", "reread", "jennys", "duet")
        var count = 0
        val scorings = listOf(CtcGlideBeam.Scoring(), CtcGlideBeam.Scoring(lengthNorm = 0.4f, pruneBonus = 0.8f, freqWeight = 0.3f))
        for (beam in scorings.map { CtcGlideBeam(it) to KotlinCtcGlideBeam(it) }) {
            for ((index, word) in words.withIndex()) {
                val emissions = emissionsFor(word, random, if (index % 3 == 0) 1.5f else 0.4f)
                if (emissions.steps == 0) continue
                val sources = withUser(if (index % 2 == 0) trie else packed, user)
                for ((width, rank) in listOf(100 to 0, 300 to 0, 100 to 20_000)) {
                    val what = "#$index '$word' width=$width rank=$rank"
                    val expected = beam.second.decode(emissions, sources, LIMIT, width, rank)
                    val actual = beam.first.decode(emissions, sources, LIMIT, width, rank)
                    same(expected, actual, what)
                    count++
                }
            }
        }
        println("ctc parity: $count decodes, $candidates candidates identical")
    }

    /**
     * [CtcGlideBeam] with the stroke beside its emissions: the gesture
     * corpus, a looping profile among it, each case's emissions peaked along
     * its word (whole, or its first two thirds for a lifted finger), decoded
     * with look-ahead, the loops and the learned shapes against
     * [KotlinCtcGlideBeam] fed [KotlinGlideBeam]'s marks and learned gains.
     */
    @Test
    fun everyNeuralReadingWithItsStrokeDecodesTheSame() {
        val (entries, trie, packed) = dictionaries()
        val user = userTrie()
        val grid = GlideGrid.of(BuiltInLayouts.QWERTY)
        val centers = grid.keyCenters(KEY_WIDTH)
        val keys = GlideKeyMap.of(centers, KEY_WIDTH)
        val layout = GlideKeyMap.fingerprint(centers, KEY_WIDTH)
        val random = Random(SEED)
        val corpus = SwipeCorpus(SEED, grid = grid)
        val profiles = listOf(
            SwipeCorpus.Noise.TYPICAL.profile,
            SwipeCorpus.Noise.SLOPPY.profile,
            SwipeCorpus.Noise.TYPICAL.profile.copy(letterDwell = 0.3f, loopOnDoubles = 1f),
        )
        val cases = profiles.flatMap { corpus.generate(entries, it, CASES) }
        val store = GlideShapeStore(null)
        val shaping = KotlinGlideBeam()
        for (case in cases.filterIndexed { i, _ -> i % 4 == 0 }) {
            val shape = shaping.sampleShape(case.path, KEY_WIDTH, KotlinGlideWorkspace()) ?: continue
            store.learn(GlideShapeSample(layout, shape), case.intended)
        }
        val learned = store.forLayout(layout) ?: error("nothing learned")
        var count = 0
        val tunings = listOf(GlideBeam.Tuning(), GlideBeam.Tuning.WIGGLES_ON)
        val scorings = listOf(
            CtcGlideBeam.Scoring(),
            CtcGlideBeam.Scoring(lookAheadCost = 0.2f, loopWeight = 0.7f, shapeWeight = 25f),
        )
        for (tuning in tunings) {
            for (scoring in scorings) {
                val beam = CtcGlideBeam(scoring) to KotlinCtcGlideBeam(scoring)
                val glide = KotlinGlideBeam(tuning)
                for ((index, case) in cases.withIndex()) {
                    val word = if (index % 3 == 1) case.intended.take((case.intended.length * 2 + 2) / 3) else case.intended
                    val emissions = emissionsFor(word, random, if (index % 2 == 0) 1.0f else 0.4f)
                    if (emissions.steps == 0) continue
                    val sources = withUser(if (index % 2 == 0) trie else packed, user)
                    val shapes = if (index % 2 == 0) learned else null
                    val lookAhead = if (index % 3 == 2) 0 else 3
                    val marks = glide.marks(case.path, keys, KEY_WIDTH, KotlinGlideWorkspace())
                    val shapeWs = KotlinGlideWorkspace()
                    val prepared = shapes != null && glide.prepareShape(case.path, KEY_WIDTH, shapeWs)
                    val gain: ((String) -> Double)? = shapes?.let { s ->
                        { w: String -> if (prepared) glide.learnedGain(w, keys, s, shapeWs) else 0.0 }
                    }
                    val what = "#$index '${case.intended}' as '$word' look-ahead=$lookAhead shapes=${shapes != null}"
                    val expected = beam.second.decode(
                        emissions, sources, LIMIT, 100, 0,
                        lookAhead = lookAhead, marks = marks, keys = keys.takeIf { marks != null }, gain = gain,
                    )
                    val actual = beam.first.decode(
                        emissions, sources, LIMIT, 100, 0,
                        stroke = CtcGlideBeam.Stroke(case.path, keys, KEY_WIDTH, tuning, shapes),
                        lookAhead = lookAhead,
                    )
                    same(expected, actual, what)
                    count++
                }
            }
        }
        println("ctc stroke parity: $count decodes, $candidates candidates identical")
    }
}

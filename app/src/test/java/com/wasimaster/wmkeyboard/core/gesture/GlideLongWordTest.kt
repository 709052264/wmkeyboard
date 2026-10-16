package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Long words on a big word list (issue #714).
 *
 * A German compound — Computertomographie, Qualitätsmanagement — came back as
 * a short word that fit a third of the stroke. The walk's bound used to be a
 * prefix's best alignment so far and nothing about the stroke still ahead of
 * it, so on a long stroke every short prefix in the list looked as good as
 * the word being drawn and the state pool filled with them before the word
 * was reached. The graded eval corpus never draws a word past fourteen
 * letters, and the 17k English list is too small to flood the walk, which is
 * why neither caught it.
 *
 * This builds the shape of list that does: a few thousand stems and 150,000
 * two-stem compounds of them, the way German grows its tail, and asks for
 * three-stem compounds of thirteen letters and more drawn with corner cutting
 * and tremor. Top-1 was .45 before the remainder bound and the bigger state
 * pool, .95 after. On the real 205k-word German list, strokes of eighteen
 * letters and more went from 2 in 150 to 95.
 */
class GlideLongWordTest {

    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 120f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    @Test
    fun longCompoundsOnALargeListAreFound() {
        val random = Random(SEED)
        val stems = HashSet<String>()
        while (stems.size < STEMS) stems.add(stem(random))
        val stemList = stems.toList()
        val entries = HashMap<String, Int>()
        stemList.forEachIndexed { i, s -> entries[s] = 200 - i * 150 / STEMS }
        repeat(COMPOUNDS) {
            val word = stemList[random.nextInt(COMMON_STEMS)] + stemList[random.nextInt(STEMS)]
            entries.putIfAbsent(word, 1 + random.nextInt(60))
        }
        val targets = ArrayList<String>()
        while (targets.size < TARGETS) {
            val word = stemList[random.nextInt(COMMON_STEMS)] + stemList[random.nextInt(STEMS)] +
                stemList[random.nextInt(STEMS)]
            if (word.length >= MIN_TARGET_LENGTH && word !in entries) {
                entries[word] = TARGET_FREQUENCY
                targets.add(word)
            }
        }
        val trie = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        val sources = trie.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        }

        val beam = GlideBeam()
        val workspace = GlideWorkspace()
        var hits = 0
        var saturated = 0
        val misses = ArrayList<String>()
        for (word in targets) {
            val decoded = beam.decode(stroke(word, random), grid, keyWidth, sources, workspace, limit = 8)
            if (workspace.saturated) saturated++
            val top = decoded.firstOrNull()?.word
            if (top == word) hits++ else misses.add("$word -> $top")
        }
        val top1 = hits / targets.size.toDouble()
        assertTrue("long-word top-1 $top1, misses $misses", top1 >= MIN_TOP1)
        assertFalse("state pool saturated on $saturated of ${targets.size} strokes", saturated > MAX_SATURATED)
    }

    /** A pronounceable stem of two or three consonant-vowel syllables, sometimes closed. */
    private fun stem(random: Random): String = buildString {
        repeat(2 + random.nextInt(2)) {
            append(CONSONANTS[random.nextInt(CONSONANTS.length)])
            append(VOWELS[random.nextInt(VOWELS.length)])
        }
        if (random.nextBoolean()) append(CONSONANTS[random.nextInt(CONSONANTS.length)])
    }

    /** [word] drawn through its key centres with every corner cut and a wandering hand. */
    private fun stroke(word: String, random: Random): List<GesturePoint> {
        val anchors = ArrayList<Pair<Float, Float>>()
        var previous = -1
        for (ch in word) {
            if (ch.code == previous) continue
            val key = centers.getValue(ch.code)
            anchors.add(key.x / keyWidth to key.y / keyWidth)
            previous = ch.code
        }
        val dense = ArrayList<Pair<Float, Float>>()
        dense.add(anchors[0])
        for (i in 1 until anchors.size - 1) {
            val radius = minOf(
                CORNER,
                0.45f * distance(anchors[i], anchors[i - 1]),
                0.45f * distance(anchors[i], anchors[i + 1]),
            )
            val enter = toward(anchors[i], anchors[i - 1], radius)
            val exit = toward(anchors[i], anchors[i + 1], radius)
            line(dense, enter)
            for (s in 1..CURVE_STEPS) {
                val f = s / CURVE_STEPS.toFloat()
                val g = 1f - f
                dense.add(
                    (g * g * enter.first + 2 * g * f * anchors[i].first + f * f * exit.first) to
                        (g * g * enter.second + 2 * g * f * anchors[i].second + f * f * exit.second),
                )
            }
        }
        line(dense, anchors.last())

        val out = ArrayList<GesturePoint>()
        var tx = 0f
        var ty = 0f
        var travelled = 0f
        out.add(GesturePoint(dense[0].first * keyWidth, dense[0].second * keyWidth))
        for (i in 1 until dense.size) {
            travelled += distance(dense[i - 1], dense[i])
            if (travelled < SAMPLE_SPACING) continue
            travelled = 0f
            tx = RHO * tx + MIX * JITTER * gaussian(random)
            ty = RHO * ty + MIX * JITTER * gaussian(random)
            out.add(GesturePoint((dense[i].first + tx) * keyWidth, (dense[i].second + ty) * keyWidth))
        }
        out.add(GesturePoint(dense.last().first * keyWidth, dense.last().second * keyWidth))
        return out
    }

    private fun line(out: MutableList<Pair<Float, Float>>, to: Pair<Float, Float>) {
        val from = out.last()
        val steps = maxOf(1, (distance(from, to) / LINE_STEP).toInt())
        for (s in 1..steps) {
            val f = s / steps.toFloat()
            out.add((from.first + (to.first - from.first) * f) to (from.second + (to.second - from.second) * f))
        }
    }

    private fun toward(from: Pair<Float, Float>, to: Pair<Float, Float>, by: Float): Pair<Float, Float> {
        val length = distance(from, to)
        if (length <= 0f) return from
        return (from.first + (to.first - from.first) / length * by) to
            (from.second + (to.second - from.second) / length * by)
    }

    private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
        hypot(b.first - a.first, b.second - a.second)

    private fun gaussian(random: Random): Float {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
    }

    private companion object {
        const val SEED = 714L
        const val CONSONANTS = "bdfghklmnprstvwz"
        const val VOWELS = "aeiou"
        const val STEMS = 6000
        const val COMMON_STEMS = 1500
        const val COMPOUNDS = 150_000
        const val TARGETS = 40
        const val MIN_TARGET_LENGTH = 13
        const val TARGET_FREQUENCY = 40

        /** How far inside each corner the finger cuts, in key widths. */
        const val CORNER = 0.4f
        const val CURVE_STEPS = 12
        const val LINE_STEP = 0.02f
        const val SAMPLE_SPACING = 0.1f

        /** Tremor as an AR(1) walk, like `SwipeCorpus`: σ in key widths, ~10-sample memory. */
        const val JITTER = 0.08f
        const val RHO = 0.9f
        const val MIX = 0.4359f

        const val MIN_TOP1 = 0.9
        const val MAX_SATURATED = 2
    }
}

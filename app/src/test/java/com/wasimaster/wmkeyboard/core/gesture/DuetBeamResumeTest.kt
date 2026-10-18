package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One thumb's swipe rests on a key while the other thumb taps, then carries on
 * from that same key: s→e, rest on e while the other thumb taps l, then
 * e→c→t is "select". Lifting after "se" and swiping "ect" afresh already
 * works; the continuous swipe has to as well.
 */
class DuetBeamResumeTest {

    // The grid of DuetBeamTest and GlideBeamTest: 60 px keys, offset rows.
    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 90f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)
    private val beam = DuetBeam()
    private val workspace = DuetWorkspace()

    /** [word] is [before] + [tapped] + [after], and [after] starts on [before]'s last key. */
    private data class Case(val word: String, val before: String, val tapped: Char, val after: String)

    private val cases = listOf(
        Case("select", "se", 'l', "ect"),
        Case("delete", "de", 'l', "ete"),
        Case("release", "re", 'l', "ease"),
        Case("repeat", "re", 'p', "eat"),
        Case("energy", "e", 'n', "ergy"),
    )

    private fun sources(entries: List<Pair<String, Int>>): List<FuzzyBeamSearch.WalkSource> {
        val trie = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        return trie.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) }
    }

    private val english: List<FuzzyBeamSearch.WalkSource> by lazy {
        val file = listOf(File("dictionaries-src/en.txt"), File("app/dictionaries-src/en.txt"))
            .first { it.exists() }
        sources(file.inputStream().use { DictionaryLoader.loadEntries(it) })
    }

    private fun decode(dictionary: List<FuzzyBeamSearch.WalkSource>, tokens: List<GlideToken>): List<String> =
        beam.decode(tokens, grid, keyWidth, dictionary, workspace, limit = 8).map { it.word }

    private fun center(letter: Char): KeyCenter = centers.getValue(letter.code)

    private fun tap(letter: Char, t: Long) = GlideToken.Tap(center(letter).x, center(letter).y, t)

    /** Straight from [from] towards [to] over [LEG_MS], without the end point. */
    private fun MutableList<GesturePoint>.leg(from: Char, to: Char, t0: Long) {
        val a = center(from)
        val b = center(to)
        for (step in 0 until STEPS) {
            val f = step / STEPS.toFloat()
            add(GesturePoint(a.x + f * (b.x - a.x), a.y + f * (b.y - a.y), t0 + step * LEG_MS / STEPS))
        }
    }

    private fun stroke(letters: String, t0: Long): GlideToken.Swipe {
        val points = ArrayList<GesturePoint>()
        var t = t0
        for (i in 0 until letters.length - 1) {
            points.leg(letters[i], letters[i + 1], t)
            t += LEG_MS
        }
        points.add(GesturePoint(center(letters.last()).x, center(letters.last()).y, t))
        return GlideToken.Swipe(points)
    }

    /** One swipe: [Case.before], [restMs] resting on [Case.after]'s first key (shaking by [shakePx]), then [Case.after]. */
    private fun restingSwipe(case: Case, t0: Long, restMs: Long, shakePx: Float): GlideToken.Swipe {
        val points = ArrayList<GesturePoint>()
        var t = t0
        for (i in 0 until case.before.length - 1) {
            points.leg(case.before[i], case.before[i + 1], t)
            t += LEG_MS
        }
        val rest = center(case.after[0])
        var frame = 0
        while (frame * FRAME_MS < restMs) {
            val dx = if (frame % 2 == 0) shakePx else -shakePx
            points.add(GesturePoint(rest.x + dx, rest.y, t + frame * FRAME_MS))
            frame++
        }
        t += restMs
        val tail = stroke(case.after, t)
        points.addAll(tail.points)
        return GlideToken.Swipe(points)
    }

    /** Each case at two rests, still and shaking, the other thumb's tap a third of the way into the rest. */
    private fun misses(dictionary: (Case) -> List<FuzzyBeamSearch.WalkSource>, first: Boolean): List<String> {
        val misses = ArrayList<String>()
        for (case in cases) {
            for (restMs in longArrayOf(150L, 300L)) {
                for (shakePx in floatArrayOf(0f, 3f)) {
                    val arrive = T0 + (case.before.length - 1) * LEG_MS
                    val tokens = listOf(restingSwipe(case, T0, restMs, shakePx), tap(case.tapped, arrive + restMs / 3))
                    val words = decode(dictionary(case), tokens)
                    val hit = if (first) words.firstOrNull() == case.word else case.word in words
                    if (!hit) misses.add("${case.word}, rest $restMs ms, shake $shakePx px: $words")
                }
            }
        }
        return misses
    }

    @Test
    fun `a swipe resting on a key gives that key again after the other thumb's tap`() {
        // A dictionary of the one word: no hit means the word cannot be spelled at all.
        assertEquals(emptyList<String>(), misses({ sources(listOf(it.word to 500)) }, first = false))
    }

    @Test
    fun `on the English list the resumed word comes first`() {
        assertEquals(emptyList<String>(), misses({ english }, first = true))
    }

    @Test
    fun `lifting and swiping again already spells the word`() {
        val misses = ArrayList<String>()
        for (case in cases) {
            val arrive = T0 + (case.before.length - 1) * LEG_MS
            val tokens = listOf(stroke(case.before, T0), tap(case.tapped, arrive + 100L), stroke(case.after, arrive + 200L))
            val words = decode(english, tokens)
            if (words.firstOrNull() != case.word) misses.add("${case.word}: $words")
        }
        assertEquals(emptyList<String>(), misses)
    }

    @Test
    fun `a tap on the key the swipe rests on is still the doubled letter`() {
        // h→e→l, resting on l while the other thumb taps l, then l→o: the tap is the second l.
        val hello = Case("hello", "hel", 'l', "lo")
        val words = decode(english, listOf(restingSwipe(hello, T0, 300L, 0f), tap('l', T0 + 300L + 100L)))
        assertEquals("hello", words.firstOrNull())
    }

    private companion object {
        const val T0 = 1_000L
        const val LEG_MS = 150L
        const val STEPS = 10
        const val FRAME_MS = 16L
    }
}
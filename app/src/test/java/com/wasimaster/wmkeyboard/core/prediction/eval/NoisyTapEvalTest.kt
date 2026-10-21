// The harness runs on the JVM only and stdout IS its report channel — the
// device-invisibility rationale behind the println ban does not apply here.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.prediction.eval

import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.KeyTouchModel
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.SlipFixes
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.TouchPoint
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Words typed by a Gaussian finger, read under each [SlipFixes] level: the
 * whole engine, strip and autocorrect, over the real English list.
 *
 * [TypoCorpus] injects exactly one error per word and hands the engine no
 * taps, so it cannot see what slips are for: a fast typist misses two or
 * three keys of a word by a little, and every miss leaves a tap behind. Here
 * each letter gets a tap scattered around its key, the nearest key is what
 * got typed, and the engine reads the word with those taps. Three scatters,
 * careful to sloppy, each scored with slips off (every off-key tap an edit,
 * the old reading) and on.
 *
 * The bar is relative, not a floor: [SlipFixes.CAREFUL] must not lose top-1,
 * autocorrect recall or precision against [SlipFixes.OFF] at any scatter,
 * must not correct a known word typed right, and may rewrite at most a point
 * more new words (held out of the list, typed right) than off does
 * ([NEW_WORD_TOLERANCE]). [SlipFixes.EAGER]
 * is reported, not held to that last bar: it trades it away on purpose.
 * The table is printed and written to
 * `build/reports/predeval/noisy_taps.json`. `wm-engine`'s `noisy_tap_eval`
 * is the walk-only twin this was tuned against.
 */
class NoisyTapEvalTest {

    private companion object {
        const val SEED = 42L
        const val CASES = 1500
        const val CLEAN_CASES = 1000

        /** Slips may cost at most this much of any metric at any scatter. */
        const val TOLERANCE = 0.005

        /**
         * How many more new words Careful may rewrite than Off does. Not 0: a
         * name typed right and a list word typed wide can leave the same taps
         * (`cyrus` and `chris`, every tap inside its own key), and no price
         * tells them apart without costing the wide-tap fixes. Measured
         * 2026-10-21: +0.6 and +0.7 points over Off's .26 at the fast and
         * sloppy scatters, with recall .21 -> .65 and .11 -> .52. An uncapped
         * slip price cut the overlap a little and lost 3-4 points of sloppy
         * top-1, recall and precision, so it was left out.
         */
        const val NEW_WORD_TOLERANCE = 0.01

        /** How many new words, typed right, per scatter. */
        const val NEW_WORD_CASES = 1000

        /** Tap scatter in key widths, x then y: careful, fast, sloppy. */
        val SCATTERS = listOf(0.25 to 0.3, 0.35 to 0.45, 0.45 to 0.55)
    }

    private class Result(
        val top1: Double,
        val top3: Double,
        val precision: Double,
        val recall: Double,
        val fcr: Double,
        val newWords: Double,
    )

    private class Case(val intended: String, val typed: String, val taps: List<TouchPoint>)

    private fun realEntries(): List<Pair<String, Int>> {
        val file = listOf(File("dictionaries-src/en.txt"), File("app/dictionaries-src/en.txt"))
            .firstOrNull { it.exists() }
            ?: error("en.txt not found (cwd=${File(".").absolutePath})")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    private val centers = QwertyGeometry.keys.associate { it.char to TouchPoint(it.x, it.y) }

    private fun engine(trie: PackedTrie, mode: SlipFixes) =
        SuggestionEngine(trie, BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            slipFixes = mode
            touchModel = KeyTouchModel(
                centers,
                slipRadius = if (mode == SlipFixes.OFF) 0.0 else KeyTouchModel.SLIP_SIGMAS * KeyTouchModel.SIGMA,
            )
        }

    /**
     * One word in seven between ranks 3,000 and 60,000, four letters or more,
     * held out of the list: they stand in for slang, names and anything newer
     * than the list.
     */
    private fun heldOut(entries: List<Pair<String, Int>>): Set<String> =
        entries.sortedByDescending { it.second }.withIndex()
            .filter { (rank, e) ->
                rank in 3_000 until 60_000 && rank % 7 == 0 &&
                    e.first.length >= 4 && e.first.all { it in 'a'..'z' }
            }
            .map { it.value.first }
            .toSet()

    /** Frequency-weighted words of three letters or more, typed with [sx], [sy] of scatter. */
    @Suppress("LongParameterList")
    private fun cases(
        entries: List<Pair<String, Int>>,
        sx: Double,
        sy: Double,
        count: Int,
        mistyped: Boolean,
    ): List<Case> {
        val random = Random(SEED + (sx * 1000).toLong())
        val usable = entries.filter { (w, _) -> w.length >= 3 && w.all { it in 'a'..'z' } }
        val cumulative = LongArray(usable.size)
        var total = 0L
        for (i in usable.indices) {
            total += usable[i].second.coerceAtLeast(1)
            cumulative[i] = total
        }
        fun gaussian(): Double {
            val u1 = random.nextDouble().coerceAtLeast(1e-12)
            return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * random.nextDouble())
        }
        val out = ArrayList<Case>(count)
        while (out.size < count) {
            val target = random.nextLong(total)
            var lo = 0
            var hi = cumulative.size - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (cumulative[mid] <= target) lo = mid + 1 else hi = mid
            }
            val word = usable[lo].first
            val taps = word.map { ch ->
                val key = QwertyGeometry.keyFor(ch)!!
                TouchPoint((key.x + gaussian() * sx).toFloat(), (key.y + gaussian() * sy).toFloat())
            }
            val typed = taps.map { QwertyGeometry.nearestKey(it.x, it.y) }.joinToString("")
            if ((typed != word) == mistyped) out.add(Case(word, typed, taps))
        }
        return out
    }

    private fun score(
        engine: SuggestionEngine,
        heldOutEngine: SuggestionEngine,
        typos: List<Case>,
        clean: List<Case>,
        newWords: List<Case>,
    ): Result {
        var top1 = 0
        var top3 = 0
        var fired = 0
        var right = 0
        for (case in typos) {
            val strip = engine.suggest(case.typed, previousWord = null, touch = case.taps)
            if (strip.firstOrNull() == case.intended) top1++
            if (strip.take(3).contains(case.intended)) top3++
            val fix = engine.shouldAutocorrect(case.typed, touch = case.taps) ?: continue
            fired++
            if (fix == case.intended) right++
        }
        val cleanFired = clean.count { engine.shouldAutocorrect(it.typed, touch = it.taps) != null }
        val rewritten = newWords.count { heldOutEngine.shouldAutocorrect(it.typed, touch = it.taps) != null }
        val n = typos.size.toDouble()
        return Result(
            top1 = top1 / n,
            top3 = top3 / n,
            precision = if (fired == 0) 1.0 else right / fired.toDouble(),
            recall = right / n,
            fcr = cleanFired / clean.size.toDouble(),
            newWords = rewritten / newWords.size.toDouble(),
        )
    }

    @Test
    fun slipsReadSloppyTapsWithoutLosingCarefulOnes() {
        val entries = realEntries()
        val held = heldOut(entries)
        val trie = PackedTrie.of(entries)
        val without = PackedTrie.of(entries.filter { it.first !in held })
        val modes = SlipFixes.entries
        val engines = modes.associateWith { engine(trie, it) to engine(without, it) }
        fun f(v: Double) = String.format(Locale.ROOT, "%.4f", v)
        val json = StringBuilder("{\n  \"seed\": $SEED,\n  \"cases\": $CASES,\n  \"scatters\": [\n")
        println(
            "=== noisy-tap eval (seed=$SEED, $CASES mistyped + $CLEAN_CASES clean + $NEW_WORD_CASES new words " +
                "per scatter, ${held.size} held out) ===",
        )
        val failures = ArrayList<String>()
        for ((index, scatter) in SCATTERS.withIndex()) {
            val (sx, sy) = scatter
            val typos = cases(entries, sx, sy, CASES, mistyped = true)
            val clean = cases(entries, sx, sy, CLEAN_CASES, mistyped = false)
            val newWords = cases(entries.filter { it.first in held }, sx, sy, NEW_WORD_CASES, mistyped = false)
            val results = modes.associateWith { mode ->
                val (full, holed) = engines.getValue(mode)
                score(full, holed, typos, clean, newWords)
            }
            json.append("    {\"sx\": $sx, \"sy\": $sy")
            for ((mode, r) in results) {
                println(
                    "scatter ($sx, $sy) $mode: top1=${f(r.top1)} top3=${f(r.top3)} " +
                        "autocorrect precision=${f(r.precision)} recall=${f(r.recall)} " +
                        "falseCorrectionRate=${f(r.fcr)} newWordsRewritten=${f(r.newWords)}",
                )
                json.append(
                    ", \"${mode.name.lowercase()}\": {\"top1\": ${f(r.top1)}, \"top3\": ${f(r.top3)}, " +
                        "\"precision\": ${f(r.precision)}, \"recall\": ${f(r.recall)}, \"fcr\": ${f(r.fcr)}, " +
                        "\"newWords\": ${f(r.newWords)}}",
                )
            }
            json.append(if (index < SCATTERS.lastIndex) "},\n" else "}\n")
            val before = results.getValue(SlipFixes.OFF)
            val after = results.getValue(SlipFixes.CAREFUL)
            val at = "scatter ($sx, $sy)"
            if (after.top1 < before.top1 - TOLERANCE) failures += "$at top1 ${f(after.top1)} < ${f(before.top1)}"
            if (after.recall < before.recall - TOLERANCE) failures += "$at recall ${f(after.recall)} < ${f(before.recall)}"
            if (after.precision < before.precision - TOLERANCE) {
                failures += "$at precision ${f(after.precision)} < ${f(before.precision)}"
            }
            if (after.fcr > before.fcr) failures += "$at false corrections ${f(after.fcr)} > ${f(before.fcr)}"
            if (after.newWords > before.newWords + NEW_WORD_TOLERANCE) {
                failures += "$at new words rewritten ${f(after.newWords)} > ${f(before.newWords)}"
            }
        }
        json.append("  ]\n}\n")
        val out = File("build/reports/predeval/noisy_taps.json")
        out.parentFile?.mkdirs()
        out.writeText(json.toString())
        println("metrics written to ${out.path}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}

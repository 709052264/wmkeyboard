// The harness runs on the JVM only and stdout IS its report channel — the
// device-invisibility rationale behind the println ban does not apply here.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.prediction.eval

import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.KeyTouchModel
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
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
 * Words typed by a Gaussian finger, read with and without slips
 * ([KeyTouchModel.slipRadius]): the whole engine, strip and autocorrect, over
 * the real English list.
 *
 * [TypoCorpus] injects exactly one error per word and hands the engine no
 * taps, so it cannot see what slips are for: a fast typist misses two or
 * three keys of a word by a little, and every miss leaves a tap behind. Here
 * each letter gets a tap scattered around its key, the nearest key is what
 * got typed, and the engine reads the word with those taps. Three scatters,
 * careful to sloppy, each scored with slips off (every off-key tap an edit,
 * the old reading) and on.
 *
 * The bar is relative, not a floor: slips must not lose top-1, autocorrect
 * recall or precision against the old reading at any scatter, and must not
 * correct a word typed right. The table is printed and written to
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

        /** Tap scatter in key widths, x then y: careful, fast, sloppy. */
        val SCATTERS = listOf(0.25 to 0.3, 0.35 to 0.45, 0.45 to 0.55)
    }

    private class Result(val top1: Double, val top3: Double, val precision: Double, val recall: Double, val fcr: Double)

    private class Case(val intended: String, val typed: String, val taps: List<TouchPoint>)

    private fun realEntries(): List<Pair<String, Int>> {
        val file = listOf(File("dictionaries-src/en.txt"), File("app/dictionaries-src/en.txt"))
            .firstOrNull { it.exists() }
            ?: error("en.txt not found (cwd=${File(".").absolutePath})")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    private val centers = QwertyGeometry.keys.associate { it.char to TouchPoint(it.x, it.y) }

    private fun engine(trie: PackedTrie, slips: Boolean) =
        SuggestionEngine(trie, BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            touchModel = KeyTouchModel(
                centers,
                slipRadius = if (slips) KeyTouchModel.SLIP_SIGMAS * KeyTouchModel.SIGMA else 0.0,
            )
        }

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

    private fun score(engine: SuggestionEngine, typos: List<Case>, clean: List<Case>): Result {
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
        val n = typos.size.toDouble()
        return Result(
            top1 = top1 / n,
            top3 = top3 / n,
            precision = if (fired == 0) 1.0 else right / fired.toDouble(),
            recall = right / n,
            fcr = cleanFired / clean.size.toDouble(),
        )
    }

    @Test
    fun slipsReadSloppyTapsWithoutLosingCarefulOnes() {
        val entries = realEntries()
        val trie = PackedTrie.of(entries)
        val off = engine(trie, slips = false)
        val on = engine(trie, slips = true)
        fun f(v: Double) = String.format(Locale.ROOT, "%.4f", v)
        val json = StringBuilder("{\n  \"seed\": $SEED,\n  \"cases\": $CASES,\n  \"scatters\": [\n")
        println("=== noisy-tap eval (seed=$SEED, $CASES mistyped + $CLEAN_CASES clean words per scatter) ===")
        val failures = ArrayList<String>()
        for ((index, scatter) in SCATTERS.withIndex()) {
            val (sx, sy) = scatter
            val typos = cases(entries, sx, sy, CASES, mistyped = true)
            val clean = cases(entries, sx, sy, CLEAN_CASES, mistyped = false)
            val before = score(off, typos, clean)
            val after = score(on, typos, clean)
            for ((name, r) in listOf("slips off" to before, "slips on" to after)) {
                println(
                    "scatter ($sx, $sy) $name: top1=${f(r.top1)} top3=${f(r.top3)} " +
                        "autocorrect precision=${f(r.precision)} recall=${f(r.recall)} falseCorrectionRate=${f(r.fcr)}",
                )
            }
            json.append("    {\"sx\": $sx, \"sy\": $sy")
            for ((name, r) in listOf("off" to before, "on" to after)) {
                json.append(
                    ", \"$name\": {\"top1\": ${f(r.top1)}, \"top3\": ${f(r.top3)}, \"precision\": ${f(r.precision)}, " +
                        "\"recall\": ${f(r.recall)}, \"fcr\": ${f(r.fcr)}}",
                )
            }
            json.append(if (index < SCATTERS.lastIndex) "},\n" else "}\n")
            val at = "scatter ($sx, $sy)"
            if (after.top1 < before.top1 - TOLERANCE) failures += "$at top1 ${f(after.top1)} < ${f(before.top1)}"
            if (after.recall < before.recall - TOLERANCE) failures += "$at recall ${f(after.recall)} < ${f(before.recall)}"
            if (after.precision < before.precision - TOLERANCE) {
                failures += "$at precision ${f(after.precision)} < ${f(before.precision)}"
            }
            if (after.fcr > before.fcr) failures += "$at false corrections ${f(after.fcr)} > ${f(before.fcr)}"
        }
        json.append("  ]\n}\n")
        val out = File("build/reports/predeval/noisy_taps.json")
        out.parentFile?.mkdirs()
        out.writeText(json.toString())
        println("metrics written to ${out.path}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}

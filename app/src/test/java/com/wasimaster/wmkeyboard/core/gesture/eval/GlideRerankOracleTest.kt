// The harness runs on the JVM only and stdout IS its report channel — the
// device-invisibility rationale behind the println ban does not apply here.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.prediction.CandidateReranker
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.NgramReranker
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.SeedBigrams
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.prediction.eval.EvalMetrics
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import java.io.File
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gesture-side counterpart of `RerankOracleTest`, and the gate on ever
 * building a model-backed [CandidateReranker] for glide.
 *
 * Two numbers, and the decision lives in the gap between them:
 *
 *  - **Ceiling.** An oracle that always moves the intended word to the front
 *    (when the decoder emitted it at all) bounds what ANY reranker could gain
 *    over the shape ranking. Measured per noise level, because a clean stroke
 *    and a sloppy one are different problems: a clean stroke that still
 *    decodes wrong is ambiguous in the shape, which is precisely the case
 *    only context can fix.
 *  - **Claimed.** What the shipped [NgramReranker] already takes out of that
 *    ceiling, cold (bundled seed pairs only) and warm (the user has typed the
 *    phrase before).
 *
 * Headroom for a bigger model is `ceiling - claimed`, never `ceiling - base`.
 * Quoting the ceiling alone would credit an LM with wins the n-gram rescorer
 * makes today for free.
 */
class GlideRerankOracleTest {

    private companion object {
        const val SEED = 42L
        const val CASES_PER_LEVEL = 300
        const val CONTEXT_CASES = 600

        /** What the strip shows; the rerank pool behind it is deeper. */
        const val SHOWN = 4
    }

    private fun realEntries(): List<Pair<String, Int>> {
        val candidates = listOf(
            File("dictionaries-src/en.txt"),
            File("app/dictionaries-src/en.txt"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("en.txt not found (cwd=${File(".").absolutePath})")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    private fun seedFile(): File {
        val candidates = listOf(
            File("src/main/assets/dictionaries/en_bigrams.txt"),
            File("app/src/main/assets/dictionaries/en_bigrams.txt"),
        )
        return candidates.firstOrNull { it.exists() } ?: error("en_bigrams.txt not found")
    }

    private fun realSeeds(): SeedBigrams = seedFile().inputStream().use { SeedBigrams.load(it) }

    /** Every (prev, next) the seed list knows, lowercased. */
    private fun seedPairs(): List<Pair<String, String>> = realSeeds().wordPairs

    private class Setup(entries: List<Pair<String, Int>>, private val seeds: SeedBigrams) {
        val lexicon = UserLexicon(null)
        val dictionary = PackedTrie.of(entries)
        val engine = SuggestionEngine(
            dictionary, BengaliPhoneticIndex(emptyList()), lexicon, seedBigrams = seeds,
        )

        fun withNgram() = apply {
            engine.reranker = NgramReranker(
                lexicon, seeds, dictionaryFrequency = { dictionary.frequencyOf(it) },
            )
        }

        fun withNone() = apply { engine.reranker = CandidateReranker.NONE }
    }

    // ---- ceiling ----

    @Test
    fun oracleCeilingIsMeasuredPerNoiseLevel() {
        val entries = realEntries()
        val setup = Setup(entries, realSeeds())
        val keys = GlideKeyMap.of(SwipeCorpus.keyCenters(), SwipeCorpus.KEY_WIDTH)

        var baseHits = 0
        var oracleHits = 0
        var total = 0
        for (noise in SwipeCorpus.Noise.entries) {
            // From the pinned list when one is set (#837); see EvalSampling.
            val cases = SwipeCorpus(SEED).generate(EvalSampling.entries(entries), noise, CASES_PER_LEVEL)

            setup.withNone()
            val baseRanks = cases.map { case -> rankOf(setup, keys, case.path, case.intended, null) }

            // The oracle needs the intended word of the case it is scoring, so
            // it is installed once and reads a var the loop below sets — the
            // same trick RerankOracleTest uses, and the reason this is a
            // measurement rather than something that could ever ship.
            var intended = ""
            setup.engine.reranker = CandidateReranker { _, candidates ->
                val hit = candidates.firstOrNull { it.equals(intended, ignoreCase = true) }
                hit?.let { h -> listOf(h) + candidates.filterNot { it == h } }
            }
            val oracleRanks = cases.map { case ->
                intended = case.intended
                rankOf(setup, keys, case.path, case.intended, null)
            }

            val base = EvalMetrics.suggest(baseRanks)
            val oracle = EvalMetrics.suggest(oracleRanks)
            println(
                String.format(
                    Locale.ROOT,
                    "glide oracle %-8s base top1=%.4f top3=%.4f | oracle top1=%.4f | ceiling=+%.4f",
                    noise.name, base.top1, base.top3, oracle.top1, oracle.top1 - base.top1,
                )
            )
            baseHits += baseRanks.count { it == 1 }
            oracleHits += oracleRanks.count { it == 1 }
            total += cases.size
        }

        val base = baseHits / total.toDouble()
        val oracle = oracleHits / total.toDouble()
        println(
            String.format(
                Locale.ROOT,
                "glide oracle OVERALL: baseTop1=%.4f oracleTop1=%.4f ceiling=+%.4f n=%d",
                base, oracle, oracle - base, total,
            )
        )
        // The seam itself must work: an oracle can only help, never hurt.
        assertTrue("glide oracle regressed top1", oracleHits >= baseHits)
        // And the pool is deep enough to hold real headroom. If this ever
        // fails, GLIDE_RERANK_POOL or the shape ranking changed materially.
        assertTrue("glide oracle found no headroom at all", oracleHits > baseHits)
    }

    // ---- what the n-gram already claims ----

    @Test
    fun ngramGainIsMeasuredColdAndWarm() {
        val entries = realEntries()
        val seeds = realSeeds()
        val dictWords = entries.toMap()
        val keys = GlideKeyMap.of(SwipeCorpus.keyCenters(), SwipeCorpus.KEY_WIDTH)
        val random = Random(7L)

        // Contextful cases, the only kind that can show a context model doing
        // anything: the intended word is a known follower of the word before
        // it. SwipeCorpus's own `previous` is a random draw by design, so a
        // corpus built from it measures the reranker declining to speak.
        val corpus = SwipeCorpus(7L)
        val grid = corpus.grid
        val pairs = seedPairs().filter { (_, next) ->
            next.length >= 3 && dictWords.containsKey(next) && grid.canSpell(next)
        }
        check(pairs.isNotEmpty()) { "no swipeable seed pairs" }

        class Case(val previous: String, val intended: String, val path: List<GesturePoint>)

        val profile = SwipeCorpus.Noise.TYPICAL.profile
        val cases = ArrayList<Case>(CONTEXT_CASES)
        while (cases.size < CONTEXT_CASES) {
            val (prev, next) = pairs[random.nextInt(pairs.size)]
            val path = corpus.swipe(next, profile) ?: continue
            cases.add(Case(prev, next, path))
        }

        fun top1(setup: Setup): Double {
            val ranks = cases.map { case ->
                rankOf(setup, keys, case.path, case.intended, case.previous)
            }
            return EvalMetrics.suggest(ranks).top1
        }

        // Cold user: only the bundled seed counts speak for context.
        val cold = Setup(entries, seeds)
        val coldBase = top1(cold.withNone())
        val coldRerank = top1(cold.withNgram())

        // Warm user: the phrase has been swiped or typed before.
        val warm = Setup(entries, seeds)
        for ((prev, next) in pairs) repeat(3) { warm.lexicon.learnBigram(prev, next) }
        val warmBase = top1(warm.withNone())
        val warmRerank = top1(warm.withNgram())

        // The ceiling on THESE cases, so the headroom subtraction below is a
        // subtraction of two numbers measured on the same corpus. The
        // per-noise ceiling from the other test is not interchangeable: these
        // cases are seed-pair followers, which are commoner words than a
        // frequency-sampled draw and therefore start from a higher base.
        var intended = ""
        val oracleSetup = Setup(entries, seeds)
        oracleSetup.engine.reranker = CandidateReranker { _, candidates ->
            val hit = candidates.firstOrNull { it.equals(intended, ignoreCase = true) }
            hit?.let { h -> listOf(h) + candidates.filterNot { it == h } }
        }
        val oracle = EvalMetrics.suggest(
            cases.map { case ->
                intended = case.intended
                rankOf(oracleSetup, keys, case.path, case.intended, case.previous)
            }
        ).top1

        fun pct(v: Double) = String.format(Locale.ROOT, "%.4f", v)
        println(
            "glide ngram gain: cold ${pct(coldBase)} -> ${pct(coldRerank)} " +
                "(+${pct(coldRerank - coldBase)}), warm ${pct(warmBase)} -> ${pct(warmRerank)} " +
                "(+${pct(warmRerank - warmBase)}), n=${cases.size}"
        )
        println(
            "glide contextful ceiling: base=${pct(coldBase)} oracle=${pct(oracle)} " +
                "(+${pct(oracle - coldBase)}); headroom over warm n-gram = " +
                "${pct(oracle - warmRerank)}"
        )
        assertTrue("cold glide rerank regressed", coldRerank >= coldBase)
        assertTrue("warm glide rerank regressed", warmRerank >= warmBase)
        // Same gate as the typing harness: every case is a bundled seed pair,
        // so a flat cold gain means the seed term is being consulted and
        // ignored rather than being absent.
        assertTrue("no measurable cold glide gain", coldRerank > coldBase)
    }

    /** 1-based rank of [intended] in what the strip would show, or null. */
    private fun rankOf(
        setup: Setup,
        keys: GlideKeyMap,
        path: List<GesturePoint>,
        intended: String,
        previous: String?,
    ): Int? {
        val out = setup.engine.glide(
            path = path,
            keys = keys,
            keyWidth = SwipeCorpus.KEY_WIDTH,
            limit = SHOWN,
            previousWord = previous,
        )
        val at = out.indexOfFirst { it.word.equals(intended, ignoreCase = true) }
        return if (at >= 0) at + 1 else null
    }
}

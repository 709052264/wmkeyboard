package com.wasimaster.wmkeyboard.core.input.composer

import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * How often Pinyin converts real sentences right, measured on clauses the
 * word pairs were not counted from (`cjk/eval/pinyin_eval.tsv`: Tatoeba's
 * conversational Mandarin and a Leipzig corpus the packs never read). The
 * question every ranking change has to answer, and the one the 万象 model
 * reports itself against (#580 follow-up).
 *
 * Two numbers per input style, full pinyin and initials only:
 *  - **sentence**: the strip's first candidate is the whole clause, exactly;
 *  - **character**: what pressing space until the buffer is empty commits,
 *    scored as 1 − edit distance / length, the way the Rime schema
 *    comparisons score it.
 *
 * Measured on 2026-10-13, word pairs v1, stupid backoff: full pinyin 54.2%
 * of clauses exact and 83.7% of characters right, initials alone 5.4% and
 * 27.0%. Interpolation (λ 0.3 to 0.85) scored below backoff at every
 * weight, and the 万象-folded pairs (`pinyin_ngrams_v2.tsv`) gained 0.4
 * points on full pinyin but lost 3 on initials, so neither ships.
 *
 * Host-only data, so this is skipped wherever the data repo is not checked
 * out beside the app. `-Dpinyin.ngrams=<file>` scores another pair pack,
 * `-Dpinyin.lambdas=0,0.3,0.5` compares interpolation weights against stupid
 * backoff (0), `-Dpinyin.limit=N` scores the first N clauses.
 */
class PinyinBenchmarkTest {

    private val data = File(System.getProperty("user.home"), "Work/wmkeyboard-data/cjk")
    private val evalFile = File(data, "eval/pinyin_eval.tsv")
    private val packFile = File(data, "pinyin_v2.tsv")
    private val pairsFile = System.getProperty("pinyin.ngrams")?.let(::File) ?: File(data, "pinyin_ngrams.tsv")

    private class Row(val pinyin: String, val initials: String, val hanzi: String, val source: String)

    private lateinit var rows: List<Row>

    @Before
    fun load() {
        assumeTrue("eval set not on this machine", evalFile.isFile && packFile.isFile)
        val asset = File("src/main/assets/dictionaries/pinyin_syllables.txt")
        assumeTrue(asset.isFile)
        PinyinSyllables.valid = PinyinSyllables.parse(asset.readText().lineSequence())
        Jianpin.index = Jianpin.build(PinyinSyllables.valid)
        CjkDictionaries.pinyin = packFile.bufferedReader().useLines(ConversionDictionary::parse)
        CjkDictionaries.pinyinNgrams = if (pairsFile.isFile) pairsFile.bufferedReader().useLines(CjkNgrams::parse) else CjkNgrams.EMPTY
        File("src/main/assets/dictionaries/han_levels.txt").takeIf { it.isFile }?.let { levels ->
            HanLevels.table = HanLevels.parse(levels.readText().lineSequence())
        }
        CjkLearning.store = null
        CjkContext.before = ""
        CjkConfig.jianpin = true
        CjkConfig.fuzzyPinyin = false
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
        CjkConfig.traditionalOutput = false
        val limit = System.getProperty("pinyin.limit")?.toIntOrNull() ?: Int.MAX_VALUE
        rows = evalFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it.size >= 3 }
            .map { Row(it[0], it[1], it[2], it.getOrElse(3) { "" }) }
            .take(limit)
    }

    @After
    fun reset() {
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        CjkDictionaries.pinyinNgrams = CjkNgrams.EMPTY
        HanLevels.table = HanLevels.EMPTY
        PinyinSyllables.valid = emptySet()
        Jianpin.index = Jianpin.EMPTY
        CjkNgrams.bigramWeight = 0.0
    }

    private class Score(var sentences: Int = 0, var exact: Int = 0, var chars: Double = 0.0) {
        val sentence: Double get() = exact.toDouble() / sentences.coerceAtLeast(1)
        val character: Double get() = chars / sentences.coerceAtLeast(1)
        override fun toString() = "sentence %.3f  character %.3f  (n=%d)".format(sentence, character, sentences)
    }

    /** What pressing space until nothing is left commits for [buffer]. */
    private fun flush(buffer: String): String {
        val out = StringBuilder()
        var rest = buffer
        var guard = 0
        while (rest.isNotEmpty() && guard++ < 64) {
            val top = PinyinComposer.candidates(rest).firstOrNull()
            if (top == null) {
                out.append(rest)
                break
            }
            out.append(top)
            rest = rest.substring(PinyinComposer.consumedForIndex(rest, 0).coerceIn(1, rest.length))
        }
        return out.toString()
    }

    private fun editDistance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    private fun score(buffers: (Row) -> String, filter: (Row) -> Boolean = { true }): Score {
        val s = Score()
        for (row in rows) {
            if (!filter(row)) continue
            val buffer = buffers(row)
            s.sentences++
            if (PinyinComposer.candidates(buffer).firstOrNull() == row.hanzi) s.exact++
            val got = flush(buffer)
            s.chars += (1.0 - editDistance(got, row.hanzi).toDouble() / row.hanzi.length).coerceAtLeast(0.0)
        }
        return s
    }

    @Test
    fun `held-out sentences convert`() {
        val lambdas = System.getProperty("pinyin.lambdas")?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
            ?: listOf(CjkNgrams.bigramWeight)
        println("pinyin benchmark: ${rows.size} clauses, pairs ${pairsFile.name}")
        var full = Score()
        for (lambda in lambdas) {
            CjkNgrams.bigramWeight = lambda
            CjkDictionaries.invalidate()
            val t0 = System.nanoTime()
            full = score({ it.pinyin })
            val initials = score({ it.initials })
            val ms = (System.nanoTime() - t0) / 1e6 / (rows.size * 2).coerceAtLeast(1)
            println("  λ=%.2f full     %s".format(lambda, full))
            println("  λ=%.2f initials %s".format(lambda, initials))
            for (source in rows.map { it.source }.distinct()) {
                println("  λ=%.2f full[%s] %s".format(lambda, source, score({ it.pinyin }, { it.source == source })))
            }
            println("  %.1f ms a clause".format(ms))
        }
        // A floor well under today's numbers: the test is the measurement,
        // this only catches a decoder that has stopped working.
        assertTrue(full.toString(), full.character > 0.5)
    }
}

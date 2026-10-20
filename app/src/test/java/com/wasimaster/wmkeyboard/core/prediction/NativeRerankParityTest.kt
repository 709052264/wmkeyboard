package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.prediction.eval.SyntheticNgramPack
import com.wasimaster.wmkeyboard.core.prediction.reference.KotlinNgramReranker
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Rust context reranker behind [NgramReranker] orders every strip the
 * way the Kotlin one did ([KotlinNgramReranker], kept as the reference),
 * reading the same lexicon, pack and seed pairs (native/RUST_NEON_PLAN.md
 * step 3).
 *
 * The lexicon learns a few thousand sentences drawn from the bundled English
 * list by frequency; contexts and candidate lists come from the same draw,
 * with capitals, unknown previous words (the skip-gram backoff), recent-word
 * bags and repeated candidates mixed in.
 */
class NativeRerankParityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entries(): List<Pair<String, Int>> {
        val file = listOf(File("dictionaries-src/en.txt"), File("app/dictionaries-src/en.txt"))
            .first { it.exists() }
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    private fun seeds(): SeedBigrams {
        val file = listOf(
            File("src/main/assets/dictionaries/en_bigrams.txt"),
            File("app/src/main/assets/dictionaries/en_bigrams.txt"),
        ).first { it.exists() }
        return file.inputStream().use { SeedBigrams.load(it) }
    }

    @Test
    fun everyStripIsOrderedTheSame() {
        val en = entries()
        val dictionary = PackedTrie.of(en)
        val pack = SyntheticNgramPack.of(en, tmp.newFolder("pack"), bigrams = 40_000, trigrams = 20_000)
        val lexicon = UserLexicon(null)
        val random = Random(11)
        val common = en.sortedByDescending { it.second }.take(1500).map { it.first }
        fun word() = common[(random.nextDouble().let { it * it } * common.size).toInt()]
        repeat(3000) {
            val sentence = List(random.nextInt(3, 9)) { word() }
            for ((i, w) in sentence.withIndex()) {
                lexicon.learnWord(w)
                if (i >= 1) lexicon.learnBigram(sentence[i - 1], w)
                if (i >= 2) {
                    lexicon.learnTrigram(sentence[i - 2], sentence[i - 1], w)
                    lexicon.learnSkip1gram(sentence[i - 2], w)
                }
                if (i >= 3) lexicon.learnSkip2gram(sentence[i - 3], w)
            }
        }
        val seeds = seeds()
        val frequency = { w: String -> dictionary.frequencyOf(w) }
        val rust = NgramReranker(lexicon, seeds, frequency) { pack }
        val kotlin = KotlinNgramReranker(lexicon, seeds, frequency) { pack }
        var reordered = 0
        var strips = 0
        repeat(8000) {
            val prev = when (random.nextInt(10)) {
                0 -> "Zorblax" // unknown: the skip backoff
                1 -> word().replaceFirstChar { it.uppercase() }
                else -> word()
            }
            val prev2 = if (random.nextInt(4) == 0) null else word()
            val prev3 = if (random.nextInt(3) == 0) null else word()
            val recent = List(random.nextInt(0, 6)) { word() }
            val candidates = List(random.nextInt(1, 9)) {
                if (random.nextInt(12) == 0) word().uppercase() else word()
            }
            val context = RerankContext("", prev, recent, prev2, prev3)
            val expected = kotlin.rerank(context, candidates)
            val got = rust.rerank(context, candidates)
            assertEquals("prev=$prev prev2=$prev2 prev3=$prev3 recent=$recent: $candidates", expected, got)
            strips++
            if (expected != null) reordered++
        }
        assertTrue("the corpus should give evidence somewhere", reordered > 500)
        // The sentence start and empty lists answer null on both sides.
        assertEquals(null, rust.rerank(RerankContext("", WordContext.SENTENCE_START, emptyList()), listOf("the")))
        assertEquals(null, rust.rerank(RerankContext("", "the", emptyList()), emptyList()))
        println("rerank: $strips strips, $reordered reordered, every order identical")
    }
}

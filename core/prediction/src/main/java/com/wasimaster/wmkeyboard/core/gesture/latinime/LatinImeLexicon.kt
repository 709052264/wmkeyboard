package com.wasimaster.wmkeyboard.core.gesture.latinime

import com.wasimaster.wmkeyboard.core.gesture.GlideJoiners
import com.wasimaster.wmkeyboard.core.prediction.ChildBuffer
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.TrieWalker
import kotlin.math.ln
import kotlin.math.roundToInt

/** One word for the library's dictionary, with its AOSP 0..255 probability. */
class LatinImeWord(val word: String, val probability: Int)

/**
 * The words Google's gesture library searches, taken from the same tries the
 * rest of the keyboard uses, so the library reads a stroke against this
 * user's lists and not some dictionary of its own.
 *
 * The library keeps an AOSP version 4 dictionary, which it writes itself
 * (`LatinImeGlide` fills one through its own JNI). All this decides is which
 * words go in and how likely each is: every [FuzzyBeamSearch.Tier.DICTIONARY]
 * source, its commonest [MAX_WORDS] words, a word in several lists kept at
 * its best. A probability is AOSP's log scale, [STEPS_PER_DOUBLING] steps for
 * each doubling of frequency, the commonest word at [MAX_PROBABILITY]: the
 * inverse of how `AospScores` reads an AOSP list, so a list that came from
 * AOSP goes back in close to how it came out.
 */
object LatinImeLexicon {

    /** AOSP's word length limit (`MAX_WORD_LENGTH`), in code points. */
    const val MAX_WORD_LENGTH = 48

    /**
     * The most words one dictionary takes. AOSP caps a version 4 dictionary
     * at 8 MB and refuses words past it; this keeps the build well inside,
     * and a list's commonest 120,000 words are nearly every word swiped.
     */
    const val MAX_WORDS = 120_000

    const val MAX_PROBABILITY = 255

    /** AOSP's scale: 8.59 steps per doubling (see `AospScores`). */
    const val STEPS_PER_DOUBLING = 8.59

    /**
     * A word the user taught the keyboard: as likely as a common word, so a
     * stroke can find it, without outranking the language's commonest.
     */
    const val PERSONAL_PROBABILITY = 200

    /** Characters a word may run to in UTF-16, for the walk's path buffer. */
    private const val MAX_CHARS = MAX_WORD_LENGTH * 2

    /**
     * Every dictionary word of [sources] the library can hold, most probable
     * first, at most [limit].
     */
    fun dictionaryWords(sources: List<FuzzyBeamSearch.WalkSource>, limit: Int = MAX_WORDS): List<LatinImeWord> {
        val best = HashMap<String, Double>()
        for (source in sources) {
            if (source.tier != FuzzyBeamSearch.Tier.DICTIONARY) continue
            val walker = source.walker
            val cutoff = walker.frequencyAtRank(limit).coerceAtLeast(1)
            forEachWord(walker, cutoff) { word, frequency ->
                if (!fits(word) || GlideJoiners.isNonWord(word)) return@forEachWord
                val score = ln(frequency.toDouble()) + source.logWeight
                val old = best[word]
                if (old == null || score > old) best[word] = score
            }
        }
        if (best.isEmpty()) return emptyList()
        val top = best.values.max()
        return best.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { LatinImeWord(it.key, probability(it.value, top)) }
    }

    /** The user's own words in [sources]: what goes into the library's dictionary in memory, beside the lists. */
    fun personalWords(sources: List<FuzzyBeamSearch.WalkSource>): Set<String> {
        val words = LinkedHashSet<String>()
        for (source in sources) {
            if (source.tier != FuzzyBeamSearch.Tier.USER) continue
            forEachWord(source.walker, 1) { word, _ -> if (fits(word)) words += word }
        }
        return words
    }

    /**
     * A word of natural-log score [score] when the commonest scores [top], on
     * AOSP's scale: [MAX_PROBABILITY] for the commonest, never below 1 (zero
     * reads as no word at all in some AOSP paths).
     */
    fun probability(score: Double, top: Double): Int {
        val steps = (score - top) / ln(2.0) * STEPS_PER_DOUBLING
        return (MAX_PROBABILITY + steps).roundToInt().coerceIn(1, MAX_PROBABILITY)
    }

    /**
     * A name for exactly this word set, so a dictionary built once is
     * reused at the next keyboard start and rebuilt when a list changes.
     */
    fun fingerprint(language: String, words: List<LatinImeWord>): String {
        var hash = FNV_OFFSET
        for (ch in language) hash = (hash xor ch.code.toLong()) * FNV_PRIME
        for (w in words) {
            for (ch in w.word) hash = (hash xor ch.code.toLong()) * FNV_PRIME
            hash = (hash xor (SEPARATOR + w.probability).toLong()) * FNV_PRIME
        }
        return java.lang.Long.toHexString(hash)
    }

    /** Whether [word] is a single word the library can store. */
    private fun fits(word: String): Boolean {
        if (word.isEmpty() || word.any { it.isWhitespace() }) return false
        return word.codePointCount(0, word.length) <= MAX_WORD_LENGTH
    }

    /**
     * Calls [block] with every word in [walker] of frequency [cutoff] or
     * more, skipping whole subtrees whose best word is below it.
     */
    private fun forEachWord(walker: TrieWalker, cutoff: Int, block: (String, Int) -> Unit) {
        val buffer = ChildBuffer()
        var nodes = IntArray(INITIAL_STACK)
        var depths = IntArray(INITIAL_STACK)
        var labels = CharArray(INITIAL_STACK)
        val path = CharArray(MAX_CHARS)
        var top = 0
        nodes[top] = walker.root
        depths[top] = 0
        top++
        while (top > 0) {
            top--
            val node = nodes[top]
            val depth = depths[top]
            if (depth > 0) path[depth - 1] = labels[top]
            if (walker.maxSubtree(node) < cutoff) continue
            if (depth > 0 && walker.isWord(node)) {
                val frequency = walker.frequency(node)
                if (frequency >= cutoff) block(String(path, 0, depth), frequency)
            }
            if (depth >= MAX_CHARS) continue
            val count = walker.childrenInto(node, buffer)
            if (top + count > nodes.size) {
                val size = maxOf(nodes.size * 2, top + count)
                nodes = nodes.copyOf(size)
                depths = depths.copyOf(size)
                labels = labels.copyOf(size)
            }
            for (i in 0 until count) {
                nodes[top] = buffer.nodes[i]
                depths[top] = depth + 1
                labels[top] = buffer.labels[i]
                top++
            }
        }
    }

    private const val INITIAL_STACK = 256
    private const val SEPARATOR = 0x10000
    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
}

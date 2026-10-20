package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.engine.NativeHandles
import java.io.ByteArrayOutputStream
import java.util.WeakHashMap

/**
 * [FuzzyBeamSearch.search] in Rust (`wm-engine`'s `fuzzy::search_units`),
 * when every trie it walks has a Rust twin: a [MappedTrie] is one already,
 * a [PackedTrie] gets a copy of its bytes the first time it is walked, and
 * an empty trie (a user lexicon with nothing learned yet) is skipped, since
 * it can contribute nothing. Any other trie, which today means a non-empty
 * [Trie], keeps the whole search in Kotlin.
 *
 * One crossing per search; the proximity and habit models cross once, by
 * handle ([KeyProximity.nativeHandle], [EditHabits.nativeHandle]).
 */
internal object NativeFuzzySearch {

    /** [dictHandle]'s answer for a trie with nothing in it. */
    private const val SKIP = -1L

    private val packedTwins = WeakHashMap<PackedTrie, Long>()

    /**
     * Runs the search natively, or returns null when a source has no twin
     * and the caller should walk in Kotlin.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun search(
        sources: List<FuzzyBeamSearch.WalkSource>,
        typed: String,
        proximity: KeyProximity,
        limit: Int,
        maxEdits: Int,
        touch: FuzzyBeamSearch.TouchScoring?,
        habits: EditHabits,
        keys: KeySets?,
    ): List<FuzzyBeamSearch.ScoredCandidate>? {
        val handles = LongArray(sources.size)
        val weights = DoubleArray(sources.size)
        val tiers = IntArray(sources.size)
        var count = 0
        val scratch = ChildBuffer()
        for (src in sources) {
            val handle = dictHandle(src.walker, scratch)
            if (handle == SKIP) continue
            if (handle == 0L) return null
            handles[count] = handle
            weights[count] = src.logWeight
            tiers[count] = if (src.tier == FuzzyBeamSearch.Tier.USER) 1 else 0
            count++
        }
        val k = maxOf(limit * 2, FuzzyBeamSearch.AUTOCORRECT_K)
        val doubles = DoubleArray(k * 4)
        val ints = IntArray(k * 4)
        val model = touch?.model
        val points = touch?.points?.let { taps ->
            FloatArray(taps.size * 2) { i ->
                val tap = taps[i / 2]
                when {
                    tap == null -> Float.NaN
                    i % 2 == 0 -> tap.x
                    else -> tap.y
                }
            }
        }
        val words = PredictionNative.fuzzySearch(
            handles.copyOf(count), weights.copyOf(count), tiers.copyOf(count),
            typed, proximity.nativeHandle, limit, maxEdits, habits.nativeHandle,
            model?.keyOrder, model?.centerPairs, model?.sigmaValue ?: KeyTouchModel.SIGMA, points,
            keys?.rawSets, keys?.rawSpellings, doubles, ints,
        )
        return List(words.size) { i ->
            FuzzyBeamSearch.ScoredCandidate(
                word = words[i],
                score = doubles[i * 4],
                editCost = doubles[i * 4 + 1],
                edits = ints[i * 4],
                completedChars = ints[i * 4 + 1],
                tier = if (ints[i * 4 + 2] == 1) FuzzyBeamSearch.Tier.USER else FuzzyBeamSearch.Tier.DICTIONARY,
                dictScore = doubles[i * 4 + 2],
                userScore = doubles[i * 4 + 3],
                accents = ints[i * 4 + 3],
            )
        }
    }

    /** The Rust dictionary [walker] reads, 0 when it has none, [SKIP] when it is empty. */
    private fun dictHandle(walker: TrieWalker, scratch: ChildBuffer): Long = when (walker) {
        is MappedTrie -> walker.nativeHandle
        is PackedTrie -> packedTwin(walker)
        else -> if (walker.childrenInto(walker.root, scratch) == 0 && !walker.isWord(walker.root)) SKIP else 0L
    }

    /** A Rust copy of [trie], written once by the Kotlin codec and released with the trie. */
    private fun packedTwin(trie: PackedTrie): Long = synchronized(packedTwins) {
        packedTwins.getOrPut(trie) {
            val bytes = ByteArrayOutputStream().also { PackedTrieCodec.write(trie, it) }.toByteArray()
            PredictionNative.dictFromBytes(bytes).also {
                NativeHandles.track(trie, it, PredictionNative::dictClose)
            }
        }
    }
}

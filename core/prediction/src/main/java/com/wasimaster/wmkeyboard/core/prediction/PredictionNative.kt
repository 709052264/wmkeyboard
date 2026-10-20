package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.engine.NativeEngine

/**
 * JNI surface of prediction's Rust half, in libwm_engine_jni
 * (`native/wm-engine-jni/src/dict.rs` and the modules after it). Call it only
 * once [NativeEngine.loaded] is true; the classes that do ([MappedTrie],
 * [MappedNgramPack]) hold the handles and wrap them in the types the rest of
 * prediction uses.
 *
 * Handles are `long`s, 0 for "not opened". A call with a closed handle
 * throws [IllegalStateException]; a panic in Rust throws [RuntimeException].
 * Strings cross as UTF-16, unpaired surrogates included.
 */
internal object PredictionNative {
    init {
        NativeEngine.requireLoaded()
    }

    // .wmdict: a mapped dictionary.
    external fun dictOpen(path: String): Long
    external fun dictClose(handle: Long)
    external fun dictFrequencyOf(handle: Long, word: String): Int

    /** Completions best first; each one's frequency lands in [frequencies] at its index. */
    external fun dictComplete(handle: Long, prefix: String, limit: Int, frequencies: IntArray): Array<String>

    /** `[Array<String>, IntArray]`: every word and its frequency, depth first. */
    external fun dictEntries(handle: Long): Array<Any>

    /** A handle on a copy of `.wmdict` bytes written in memory ([PackedTrieCodec]), or 0. */
    external fun dictFromBytes(bytes: ByteArray): Long

    // .wmng: a mapped n-gram pack.
    external fun ngramOpen(path: String): Long
    external fun ngramClose(handle: Long)
    external fun ngramBigramCount(handle: Long, first: String, second: String): Int
    external fun ngramTrigramCount(handle: Long, first: String, second: String, third: String): Int
    external fun ngramNextWords(handle: Long, first: String, limit: Int): Array<String>
    external fun ngramNextWordsAfter(handle: Long, first: String, second: String, limit: Int): Array<String>
    external fun ngramWord(handle: Long, id: Int): String?
    external fun ngramIdOf(handle: Long, word: String): Int

    // The fuzzy correction walk (FuzzyBeamSearch) and the models it reads.
    external fun proximityOpen(rows: Array<String>): Long
    external fun proximityClose(handle: Long)

    @Suppress("LongParameterList")
    external fun habitsOpen(
        subKeys: IntArray,
        subValues: DoubleArray,
        insertKeys: CharArray,
        insertValues: DoubleArray,
        deleteKeys: CharArray,
        deleteValues: DoubleArray,
    ): Long

    external fun habitsClose(handle: Long)

    /**
     * One walk over the dictionaries behind [dicts]. Tiers are 0 (dictionary)
     * or 1 (user); [habits] is 0 for none. The touch frame is absent when
     * [touchKeys] is null: [touchCenters] holds an x, y per key and
     * [touchPoints] an x, y per typed character, NaN where unknown.
     * For result `i`, [doublesOut] holds score, editCost, dictScore and
     * userScore at `4i`, and [intsOut] edits, completedChars, tier and
     * accents; both must hold `4 * max(limit * 2, 8)`.
     */
    @Suppress("LongParameterList")
    external fun fuzzySearch(
        dicts: LongArray,
        weights: DoubleArray,
        tiers: IntArray,
        typed: String,
        proximity: Long,
        limit: Int,
        maxEdits: Int,
        habits: Long,
        touchKeys: CharArray?,
        touchCenters: FloatArray?,
        sigma: Double,
        touchPoints: FloatArray?,
        keySets: Array<String>?,
        keySpellings: Array<Array<String>?>?,
        doublesOut: DoubleArray,
        intsOut: IntArray,
    ): Array<String>
}

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

    // .wmng: a mapped n-gram pack.
    external fun ngramOpen(path: String): Long
    external fun ngramClose(handle: Long)
    external fun ngramBigramCount(handle: Long, first: String, second: String): Int
    external fun ngramTrigramCount(handle: Long, first: String, second: String, third: String): Int
    external fun ngramNextWords(handle: Long, first: String, limit: Int): Array<String>
    external fun ngramNextWordsAfter(handle: Long, first: String, second: String, limit: Int): Array<String>
    external fun ngramWord(handle: Long, id: Int): String?
    external fun ngramIdOf(handle: Long, word: String): Int
}

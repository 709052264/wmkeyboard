package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.engine.NativeHandles
import java.io.File

/**
 * A `.wmng` pack (see [NgramPackCodec] for the layout), mapped and read in
 * place by the Rust reader, `wm-engine`'s `wmng::Pack`, which WMK Desktop
 * runs too. This class holds its handle ([PredictionNative]).
 *
 * Nothing is parsed at open and nothing lands on the Java heap: every lookup
 * reads mapped pages, so resident memory is page cache the kernel can evict
 * and the parts of the corpus a user never types are never read.
 *
 * The reader leans throughout on the format's one structural promise, that
 * word ids are ranks in UTF-8 byte order. A follower run is stored sorted by
 * id, which therefore also means sorted alphabetically, so
 * `bigramCount(prev, next)` binary-searches the run by string and never
 * touches the vocabulary index. Strings are compared as Kotlin's
 * `toByteArray(UTF_8)` encodes them, a lone surrogate as `?`.
 *
 * Safe to share across threads. The mapping is released once this object is
 * unreachable ([NativeHandles]).
 */
class MappedNgramPack private constructor(private val handle: Long) {

    /** The Rust pack, for the Rust code that reads it ([NgramReranker]). */
    internal val nativeHandle: Long get() = handle

    fun bigramCount(first: String, second: String): Int =
        PredictionNative.ngramBigramCount(handle, first, second)

    fun trigramCount(first: String, second: String, third: String): Int =
        PredictionNative.ngramTrigramCount(handle, first, second, third)

    /** The [limit] likeliest words after [first], best first, ties alphabetical (UTF-8). */
    fun nextWords(first: String, limit: Int): List<String> =
        PredictionNative.ngramNextWords(handle, first, limit).asList()

    fun nextWordsAfter(first: String, second: String, limit: Int): List<String> =
        PredictionNative.ngramNextWordsAfter(handle, first, second, limit).asList()

    /** The word [id] stands for, or null when a corrupt file names a stranger. */
    fun word(id: Int): String? = PredictionNative.ngramWord(handle, id)

    /** Rank of [word] in the pack's vocabulary, or -1. */
    fun idOf(word: String): Int = PredictionNative.ngramIdOf(handle, word)

    companion object {

        /**
         * Maps [file] read-only and validates it. Returns null, so the caller
         * falls back to an empty pack, when the file is missing, truncated,
         * not a version-1 `.wmng`, or internally inconsistent: every section
         * present in full, and both run tables closing on their counts.
         */
        fun open(file: File): MappedNgramPack? {
            if (!file.isFile) return null
            val handle = PredictionNative.ngramOpen(file.absolutePath)
            if (handle == 0L) return null
            return MappedNgramPack(handle).also {
                NativeHandles.track(it, handle, PredictionNative::ngramClose)
            }
        }
    }
}

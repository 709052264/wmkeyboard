package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * JNI surface of Hunspell (libwmhunspell.so, built from native/hunspell-jni).
 * Optional at runtime: GitHub builds fetch it the first time it is asked for,
 * Play builds carry it in the on-demand `:feature:spell` split, and a build
 * without it reads [available] as false and spells with its word lists alone.
 *
 * Words cross as bytes in the dictionary's own encoding ([nativeEncoding]),
 * and a handle must not be used from two threads at once: [HunspellChecker]
 * does both.
 */
internal object HunspellNative : HunspellBackend {
    const val LIBRARY = "wmhunspell"

    private val library = GatedLibrary({ FeatureModules.spell }) {
        runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    /** Loads the library the first time it is read with the module present. */
    val available: Boolean get() = library.available

    /** Whether this build has the library, counting a module not fetched yet. Loads nothing then. */
    val shipped: Boolean get() = library.shipped

    override fun open(aff: String, dic: String): Long = nativeOpen(aff, dic)

    override fun close(handle: Long) = nativeClose(handle)

    override fun encoding(handle: Long): String = nativeEncoding(handle)

    override fun spell(handle: Long, word: ByteArray): Boolean = nativeSpell(handle, word)

    override fun suggest(handle: Long, word: ByteArray, limit: Int): Array<ByteArray>? =
        nativeSuggest(handle, word, limit)

    /** A Hunspell object over the two files, or 0. */
    private external fun nativeOpen(aff: String, dic: String): Long

    private external fun nativeClose(handle: Long)

    /** The dictionary's `SET`, `ISO8859-1` when it names none. */
    private external fun nativeEncoding(handle: Long): String

    private external fun nativeSpell(handle: Long, word: ByteArray): Boolean

    private external fun nativeSuggest(handle: Long, word: ByteArray, limit: Int): Array<ByteArray>?
}

/**
 * What [HunspellChecker] needs of the library. Public, not internal, so the
 * tests in `:app` can stand a fake in for the library and test the checker's
 * own logic (encoding, locking, caching, closing) without it.
 */
interface HunspellBackend {
    fun open(aff: String, dic: String): Long

    fun close(handle: Long)

    fun encoding(handle: Long): String

    fun spell(handle: Long, word: ByteArray): Boolean

    fun suggest(handle: Long, word: ByteArray, limit: Int): Array<ByteArray>?
}

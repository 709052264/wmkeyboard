package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * JNI surface of divvunspell (libwmdivvun.so, built from
 * native/divvunspell-jni), the engine GiellaLT's spellers run on. It rides
 * the same `spell` gate as Hunspell: GitHub builds fetch both libraries the
 * first time either is asked for, Play builds carry both in the on-demand
 * `:feature:spell` split, and a build without them reads [available] as false.
 *
 * Words cross as Java strings: a speller archive is always UTF-8. A handle
 * may be read from several threads at once, and only [close] must wait for
 * them; [DivvunChecker] sees to both.
 */
internal object DivvunNative : DivvunBackend {
    const val LIBRARY = "wmdivvun"

    private val library = GatedLibrary({ FeatureModules.spell }) {
        runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    /** Loads the library the first time it is read with the module present. */
    val available: Boolean get() = library.available

    /** Whether this build has the library, counting a module not fetched yet. Loads nothing then. */
    val shipped: Boolean get() = library.shipped

    override fun open(path: String, tempDir: String): Long = nativeOpen(path, tempDir)

    override fun close(handle: Long) = nativeClose(handle)

    override fun isCorrect(handle: Long, word: String): Boolean = nativeIsCorrect(handle, word)

    override fun suggest(handle: Long, word: String, limit: Int): Array<String>? =
        nativeSuggest(handle, word, limit)

    override fun unpack(source: String, member: String, target: String): String? =
        nativeUnpack(source, member, target)

    /**
     * A checker over the `.bhfst` (or `.zhfst`) at [path], or 0. [tempDir] is
     * where a `.zhfst` with compressed members unpacks them, since Android
     * gives an app no writable default.
     */
    private external fun nativeOpen(path: String, tempDir: String): Long

    private external fun nativeClose(handle: Long)

    private external fun nativeIsCorrect(handle: Long, word: String): Boolean

    private external fun nativeSuggest(handle: Long, word: String, limit: Int): Array<String>?

    /** Unpacks [member] (or the package's speller, when empty) of a `.pkt.tar.zst`. Null on success. */
    private external fun nativeUnpack(source: String, member: String, target: String): String?
}

/**
 * What [DivvunChecker] and the speller downloads need of the library. Public,
 * not internal, so the tests in `:app` can stand a fake in for it, the same
 * reason as [HunspellBackend].
 */
interface DivvunBackend {
    fun open(path: String, tempDir: String): Long

    fun close(handle: Long)

    fun isCorrect(handle: Long, word: String): Boolean

    fun suggest(handle: Long, word: String, limit: Int): Array<String>?

    fun unpack(source: String, member: String, target: String): String?
}

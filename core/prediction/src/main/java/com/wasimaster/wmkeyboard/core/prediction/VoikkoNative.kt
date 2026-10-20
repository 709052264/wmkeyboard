package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * JNI surface of Voikko (libwmvoikko.so, built from native/voikko-jni), the
 * Finnish spelling engine LibreOffice and Firefox use. It rides the same
 * `spell` gate as Hunspell and divvunspell, but no build packages it in an
 * APK: GitHub and F-Droid builds fetch it with the other two, Play builds
 * carry it in the on-demand `:feature:spell` split, and the offline build has
 * none, so there [available] reads false and Finnish spells as before.
 *
 * Words cross as UTF-8 bytes, which libvoikko's C-string calls read; JNI's
 * modified UTF-8 would differ outside the BMP. A handle keeps a speller cache
 * between calls and must not be used from two threads at once:
 * [VoikkoChecker] holds a lock around every call.
 */
internal object VoikkoNative : VoikkoBackend {
    const val LIBRARY = "wmvoikko"

    private val library = GatedLibrary({ FeatureModules.spell }) {
        runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    /** Loads the library the first time it is read with the module present. */
    val available: Boolean get() = library.available

    /** Whether this build has the library, counting a module not fetched yet. Loads nothing then. */
    val shipped: Boolean get() = library.shipped

    override fun open(language: String, dictionaryDir: String): Long = nativeOpen(language, dictionaryDir)

    override fun close(handle: Long) = nativeClose(handle)

    override fun spell(handle: Long, word: ByteArray): Boolean = nativeSpell(handle, word)

    override fun suggest(handle: Long, word: ByteArray, limit: Int): Array<ByteArray>? =
        nativeSuggest(handle, word, limit)

    override fun hyphenate(handle: Long, word: ByteArray): String? = nativeHyphenate(handle, word)

    /**
     * A Voikko instance for [language] (a BCP 47 tag, `fi`) over the
     * dictionaries under [dictionaryDir] (`5/mor-<variant>/`), or 0.
     */
    private external fun nativeOpen(language: String, dictionaryDir: String): Long

    private external fun nativeClose(handle: Long)

    private external fun nativeSpell(handle: Long, word: ByteArray): Boolean

    private external fun nativeSuggest(handle: Long, word: ByteArray, limit: Int): Array<ByteArray>?

    /** One character per code point of the word: ' ' none, '-' a break before it, '=' a break replacing it. */
    private external fun nativeHyphenate(handle: Long, word: ByteArray): String?
}

/**
 * What [VoikkoChecker] and the dictionary downloads need of the library.
 * Public, not internal, so the tests in `:app` can stand a fake in for it,
 * the same reason as [HunspellBackend].
 */
interface VoikkoBackend {
    fun open(language: String, dictionaryDir: String): Long

    fun close(handle: Long)

    fun spell(handle: Long, word: ByteArray): Boolean

    fun suggest(handle: Long, word: ByteArray, limit: Int): Array<ByteArray>?

    fun hyphenate(handle: Long, word: ByteArray): String?
}

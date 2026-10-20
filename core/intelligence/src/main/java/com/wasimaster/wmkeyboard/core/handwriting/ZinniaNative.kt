package com.wasimaster.wmkeyboard.core.handwriting

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * JNI surface of zinnia (libwmzinnia.so, built from native/zinnia-jni), the
 * offline recogniser behind Chinese and Japanese handwriting where ML Kit's
 * is not in the build or not picked. Optional at runtime: GitHub and F-Droid
 * builds fetch it the first time a model is downloaded, Play builds carry it
 * in the on-demand `:feature:zinnia` split, and until then [available] is
 * false and the panel offers the download.
 *
 * Labels come back as UTF-8 bytes; see [ZinniaRecognizer] for the decoding
 * and the locking around [nativeClose].
 */
internal object ZinniaNative {
    const val LIBRARY = "wmzinnia"

    private val library = GatedLibrary({ FeatureModules.zinnia }) {
        runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    /** Loads the library the first time it is read with the module present. */
    val available: Boolean get() = library.available

    /** A recogniser over the model file at [path], or 0 when it will not open. */
    external fun nativeOpen(path: String): Long

    external fun nativeClose(handle: Long)

    /** How many characters the model knows. */
    external fun nativeSize(handle: Long): Int

    /**
     * The [nbest] best labels for one character in a [size]-unit square box,
     * best first, with their scores in [scores] when it is not null. Null on
     * malformed input. See wm_zinnia_jni.cpp for the layout of [points].
     */
    external fun nativeClassify(
        handle: Long,
        size: Int,
        points: IntArray,
        strokeEnds: IntArray,
        nbest: Int,
        scores: FloatArray?,
    ): Array<ByteArray>?
}

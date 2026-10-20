package com.wasimaster.wmkeyboard.core.xlit

import com.wasimaster.wmkeyboard.core.modules.FeatureModules

/**
 * JNI surface of libwmxlit (`native/indicxlit-jni`): IndicXlit's beam search
 * over its two ONNX graphs, on Microsoft's ONNX Runtime. The library sits
 * beside libwmsherpa in `:core:voice`'s `src/onnx/jniLibs` and rides the same
 * `onnx` gate; ONNX Runtime is loaded first, and libwmxlit's dependency on it
 * binds to that copy. Handles are longs, 0 on failure.
 */
internal object XlitNative {

    @Volatile
    private var loaded: Boolean? = null

    /** Loads both libraries once. False when either is missing, which callers take as "no readings". */
    @Synchronized
    fun load(): Boolean {
        loaded?.let { return it }
        val ok = try {
            FeatureModules.loadLibrary("onnxruntime")
            FeatureModules.loadLibrary("wmxlit")
            true
        } catch (_: Throwable) {
            false
        }
        // A failure is not remembered: on Play the split may arrive later in
        // this process's life.
        if (ok) loaded = true
        return ok
    }

    @JvmStatic
    external fun nativeOpen(encoder: String, decoder: String, threads: Int): Long

    @JvmStatic
    external fun nativeClose(handle: Long)

    /**
     * Up to [beam] readings of [src], packed: each is its token count, the
     * bits of its score as a float, then its target ids. Null when the run failed.
     */
    @JvmStatic
    external fun nativeTransliterate(handle: Long, src: IntArray, allowed: BooleanArray, beam: Int, maxLen: Int): IntArray?
}

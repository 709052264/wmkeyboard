package com.wasimaster.wmkeyboard.core.voice

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * RNNoise in front of the on-device dictation engines: a small recurrent
 * network that takes steady noise (a fan, traffic, a café) out of the
 * microphone's audio before any model hears it. `native/rnnoise-jni`, about
 * 130 KB, rides the speech libraries (`:feature:speech` on Play, the
 * `speech` gate elsewhere), so an engine that already needs those costs
 * nothing more, and the others fetch that one part.
 *
 * Off by default: a quiet room gains nothing, and a voice already clean can
 * lose a little of its edge.
 */
object VoiceDenoise {

    private val library = GatedLibrary({ FeatureModules.speech }) {
        runCatching { FeatureModules.loadLibrary("wmdenoise") }.isSuccess
    }

    /** Whether the library is loaded here and now. */
    val available: Boolean get() = library.available

    /** Whether this build has it, counting a module not fetched yet. */
    val shipped: Boolean get() = library.shipped

    /** A fresh denoiser for one clip, or null when the library is not here. */
    fun newStream(): Stream? {
        if (!available) return null
        val handle = runCatching { nativeCreate() }.getOrDefault(0L)
        return if (handle == 0L) null else Stream(handle)
    }

    /**
     * One clip's denoiser. Works in 10 ms blocks, so [process] hands back
     * whole blocks and keeps the rest for the next call: the output trails
     * the input by under 10 ms. Not thread-safe; [close] once.
     */
    class Stream internal constructor(private var handle: Long) : AutoCloseable {

        /** [count] samples of [chunk] (16 kHz, in [-1, 1]) in; what is ready out, denoised. */
        fun process(chunk: FloatArray, count: Int = chunk.size): FloatArray {
            val h = handle
            if (h == 0L || count <= 0) return FloatArray(0)
            return nativeProcess(h, chunk, count)
        }

        override fun close() {
            val h = handle
            handle = 0L
            if (h != 0L) nativeFree(h)
        }
    }

    @JvmStatic private external fun nativeCreate(): Long

    @JvmStatic private external fun nativeFree(handle: Long)

    @JvmStatic private external fun nativeProcess(handle: Long, samples: FloatArray, count: Int): FloatArray
}

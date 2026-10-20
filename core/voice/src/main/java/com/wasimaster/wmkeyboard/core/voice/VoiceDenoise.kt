package com.wasimaster.wmkeyboard.core.voice

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import java.io.File

/**
 * Noise reduction in front of the on-device dictation engines, at one of two
 * strengths.
 *
 * [LIGHT] is RNNoise: a small recurrent network that takes steady noise (a
 * fan, traffic, a café) out of the microphone's audio before any model hears
 * it. `native/rnnoise-jni`, about 130 KB, rides the speech libraries
 * (`:feature:speech` on Play, the `speech` gate elsewhere), so an engine that
 * already needs those costs nothing more, and the others fetch that one part.
 *
 * [STRONG] is DPDFNet, a DeepFilterNet model, on ONNX Runtime
 * ([StrongDenoise]): a download of its own and about five times the work, but
 * it is the one that brings recognition back on noisy audio rather than
 * costing words. Without its model or the runtime it falls back to [LIGHT].
 *
 * Off by default: a quiet room gains nothing, and a voice already clean can
 * lose a little of its edge.
 */
object VoiceDenoise {

    /** Stored values of the setting (`WhisperSettings.denoise`). */
    const val OFF = "off"
    const val LIGHT = "light"
    const val STRONG = "strong"
    val LEVELS = listOf(OFF, LIGHT, STRONG)

    private val library = GatedLibrary({ FeatureModules.speech }) {
        runCatching { FeatureModules.loadLibrary("wmdenoise") }.isSuccess
    }

    /** Whether RNNoise's library is loaded here and now. */
    val available: Boolean get() = library.available

    /** Whether this build has RNNoise, counting a module not fetched yet. */
    val shipped: Boolean get() = library.shipped

    /**
     * A fresh denoiser for one clip at [level], or null for [OFF] and when
     * nothing that level can fall back to is here. [STRONG] without its model
     * or runtime hands back a [LIGHT] one.
     */
    fun newStream(level: String, filesDir: File): Stream? = when (level) {
        STRONG -> StrongDenoise.newStream(filesDir) ?: light()
        LIGHT -> light()
        else -> null
    }

    /** A fresh RNNoise denoiser for one clip, or null when the library is not here. */
    fun light(): Stream? {
        if (!available) return null
        val handle = runCatching { nativeCreate() }.getOrDefault(0L)
        return if (handle == 0L) null else Rnnoise(handle)
    }

    /**
     * One clip's denoiser. [process] hands back what is ready, which trails
     * the input; [finish] hands back the rest once the clip is over. Not
     * thread-safe: one capture thread uses it, and [close]s it once.
     */
    interface Stream : AutoCloseable {
        /** [count] samples of [chunk] (16 kHz, in [-1, 1]) in; what is ready out, denoised. */
        fun process(chunk: FloatArray, count: Int = chunk.size): FloatArray

        /** The clip is over: whatever is still inside, denoised. */
        fun finish(): FloatArray = FloatArray(0)
    }

    /**
     * RNNoise works in 10 ms blocks, so [process] hands back whole blocks and
     * keeps the rest for the next call: the output trails the input by under
     * 10 ms, and the last part-block of a clip is let go.
     */
    private class Rnnoise(private var handle: Long) : Stream {

        override fun process(chunk: FloatArray, count: Int): FloatArray {
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

package com.wasimaster.wmkeyboard.core.voice

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperMel

/**
 * Compresses [WhisperRecorder]'s samples into an Ogg Opus file before a clip
 * is uploaded to a transcription server: a twelfth of the [WavEncoder] file
 * at the same length. Measured through a Whisper small.en server on 73
 * LibriSpeech clips, that cost one word in 1,150 (`native/opus-jni/README.md`).
 *
 * `native/opus-jni` (libopus and a small Ogg page writer, about 300 KB) is a
 * gate of its own, `opus` (`:feature:opus` on Play): only server dictation
 * uses it, so riding the speech gate would make a phone that never runs an
 * on-device engine fetch whisper.cpp to get it. Until it is here, [encode]
 * answers null and the clip goes as WAV, which is also what a server that
 * cannot read Ogg gets (`TranscriptionClient`).
 */
object OpusEncoder {

    /**
     * Bits per second, VBR, voice-tuned. 16 kbit/s cost ten words in the same
     * test; 24 kbit/s gained nothing over this.
     */
    const val BITRATE = 20_000

    const val FILE_NAME = "speech.ogg"
    const val MIME_TYPE = "audio/ogg"

    private val library = GatedLibrary({ FeatureModules.opus }) {
        runCatching { FeatureModules.loadLibrary("wmopus") }.isSuccess
    }

    /** Whether the library is loaded here and now. */
    val available: Boolean get() = library.available

    /** Whether this build has the library, counting a module not fetched yet. */
    val shipped: Boolean get() = library.shipped

    /** Asks for the gate, so that the clips after this one go out compressed. Idempotent. */
    fun requestLibrary() = FeatureModules.opus.requestInstall()

    /**
     * [samples] (mono, in [-1, 1]) as a whole Ogg Opus file in one crossing,
     * or null when the library is not here or refused the input.
     */
    fun encode(
        samples: FloatArray,
        sampleRate: Int = WhisperMel.SAMPLE_RATE,
        bitrate: Int = BITRATE,
    ): ByteArray? {
        if (!available) return null
        return runCatching { nativeEncode(samples, samples.size, sampleRate, bitrate) }.getOrNull()
    }

    /** A fresh encoder to feed as audio arrives, or null when the library is not here. */
    fun newStream(sampleRate: Int = WhisperMel.SAMPLE_RATE, bitrate: Int = BITRATE): Stream? {
        if (!available) return null
        val handle = runCatching { nativeOpen(sampleRate, bitrate) }.getOrDefault(0L)
        return if (handle == 0L) null else Stream(handle)
    }

    /**
     * One clip encoded in chunks: [write] as it is recorded, [finish] for the
     * file. Not thread-safe: one capture thread uses it, and [close]s it once.
     */
    class Stream internal constructor(private var handle: Long) : AutoCloseable {

        /** Adds [count] samples of [chunk]. False once the encoder has failed. */
        fun write(chunk: FloatArray, count: Int = chunk.size): Boolean {
            val h = handle
            return h != 0L && count >= 0 && nativeWrite(h, chunk, count)
        }

        /** The whole file, or null if the encoder failed. Writing after this fails. */
        fun finish(): ByteArray? {
            val h = handle
            return if (h == 0L) null else nativeFinish(h)
        }

        override fun close() {
            val h = handle
            handle = 0L
            if (h != 0L) nativeFree(h)
        }
    }

    @JvmStatic private external fun nativeEncode(samples: FloatArray, count: Int, sampleRate: Int, bitrate: Int): ByteArray?

    @JvmStatic private external fun nativeOpen(sampleRate: Int, bitrate: Int): Long

    @JvmStatic private external fun nativeWrite(handle: Long, samples: FloatArray, count: Int): Boolean

    @JvmStatic private external fun nativeFinish(handle: Long): ByteArray?

    @JvmStatic private external fun nativeFree(handle: Long)
}

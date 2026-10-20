package com.wasimaster.wmkeyboard.core.voice

/**
 * One utterance through an engine that decodes while the user speaks
 * (Asr4All, Sherpa): fed as the microphone delivers, read at any moment,
 * finished when the clip ends. Not thread-safe: one thread feeds, reads and
 * finishes, the engine's own.
 */
interface VoiceStream {

    /** The first [count] samples of [chunk] (16 kHz mono, in [-1, 1]). */
    fun feed(chunk: FloatArray, count: Int = chunk.size)

    /** What has been heard so far. */
    fun text(): String

    /** Decodes what is left and returns the finished phrase. */
    fun finish(): String

    /**
     * True once the speaker has stopped: the engine heard words, then a pause
     * long enough to end the phrase. An engine that cannot tell says false,
     * and the recorder's own silence stop decides.
     */
    val endpoint: Boolean get() = false
}

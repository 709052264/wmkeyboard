package com.wasimaster.wmkeyboard.core.voice.whisper

/**
 * Where offline Whisper's GGML models run: the Compute choice on the engine's
 * screen (`WhisperSettings.compute`). LiteRT graphs are not affected.
 *
 * [GPU] runs the model through ggml's OpenCL backend ([WhisperCppGpu]) on an
 * Adreno GPU, the only kind that backend is written for. [AUTO] does that for
 * Base and larger models, whose encoder is where a GPU pays; Tiny's encoder is
 * over on a phone's cores in about the time the GPU takes to start. Anything
 * that keeps the GPU from running a clip (no Adreno, no vendor OpenCL library,
 * a driver that will not start, no memory for the model there, a crash on an
 * earlier try) hands it to the CPU builds instead.
 */
object WhisperCompute {

    /** Stored values of the setting. */
    const val AUTO = "auto"
    const val CPU = "cpu"
    const val GPU = "gpu"
    val CHOICES = listOf(AUTO, CPU, GPU)
}

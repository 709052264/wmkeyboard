package com.wasimaster.wmkeyboard.core.ocr

import java.io.Closeable
import java.io.File

/**
 * The seam between the scanner's PaddleOCR reader and the LiteRT interpreter
 * that runs its two graphs. `PaddleOcr` (full flavour) reaches the
 * implementation, `LitertPaddleRuntime`, only by reflection, see
 * [PaddleModule.BRIDGE_CLASS], because the class lives in a different place
 * per channel, exactly as Whisper's and the sticker cutout's do:
 *
 * - Play builds: in the on-demand `:feature:litert` module, fetched with the
 *   first download of Paddle's pack (see [OcrPacks.start]).
 * - Every other full build: compiled straight into `:core:intelligence` (the
 *   `src/paddlebridge` source directory).
 *
 * Only the graphs run on the other side. Fitting the photo to them and making
 * sense of what they answer is [PaddleOcrPipeline], on this side. No type
 * here may come from LiteRT: in Play builds the base APK compiles against
 * this interface without that library.
 *
 * Not safe to share between threads; `PaddleOcr` keeps one on a thread of its own.
 */
interface PaddleRuntime : Closeable {

    /** Loads both graphs. Throws when either will not load. */
    fun open(detector: File, recognizer: File, threads: Int)

    /** The recognizer's time steps per strip, once [open]. */
    val recognizerSteps: Int

    /** The recognizer's classes per step (blank, characters, space), once [open]. */
    val recognizerClasses: Int

    /**
     * Runs the detector on [input], a [PaddleOcrPipeline.DET_SIDE] square in
     * NHWC order, into [output], the text probability of each of its pixels.
     */
    fun detect(input: FloatArray, output: FloatArray)

    /**
     * Runs the recognizer on [input], one [PaddleOcrPipeline.REC_HEIGHT] by
     * [PaddleOcrPipeline.REC_WIDTH] strip in NHWC order, into [output]:
     * [recognizerSteps] rows of [recognizerClasses] probabilities.
     */
    fun recognize(input: FloatArray, output: FloatArray)
}

object PaddleModule {

    /** FQN `PaddleOcr` instantiates reflectively; keep rule in :app. */
    const val BRIDGE_CLASS = "com.wasimaster.wmkeyboard.core.ocr.bridge.LitertPaddleRuntime"
}

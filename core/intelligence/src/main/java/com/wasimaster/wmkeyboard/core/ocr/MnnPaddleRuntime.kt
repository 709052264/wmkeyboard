package com.wasimaster.wmkeyboard.core.ocr

import com.wasimaster.wmkeyboard.core.imagegen.ImageGenNative
import java.io.File
import java.io.IOException

/**
 * PaddleOCR's two graphs on MNN, for the builds without LiteRT (F-Droid's).
 * MNN arrives with the sticker generator's library (`libwmimagegen`, the
 * `imagegen` gate), whose JNI surface, [ImageGenNative], runs any `.mnn`
 * module on float tensors, so the scanner needs no native code of its own.
 *
 * The graphs are the MNN pair of [PaddleOcrModel], exported with the same
 * NHWC `image` input and `probs` output as the LiteRT pair, so the arrays
 * [PaddleOcrPipeline] fills go through unchanged. MNN computes in float32
 * here, as it does for the stickers: its float16 path misread lines the
 * LiteRT graphs read right (see the data repository's
 * `scripts/export_ppocrv5_mnn.py`).
 *
 * Compiled into every build, unlike the LiteRT bridge: nothing here imports
 * anything of Google's.
 */
internal class MnnPaddleRuntime : PaddleRuntime {

    private var detector = 0L
    private var recognizer = 0L

    override var recognizerSteps: Int = 0
        private set
    override var recognizerClasses: Int = 0
        private set

    override fun open(detector: File, recognizer: File, threads: Int) {
        close()
        if (!ImageGenNative.available) throw IOException("the MNN runtime is not here")
        val det = load(detector, threads)
        val rec = try {
            load(recognizer, threads)
        } catch (e: IOException) {
            ImageGenNative.nativeFree(det)
            throw e
        }
        this.detector = det
        this.recognizer = rec
        // The recognizer's output shape is fixed by its input: one blank
        // strip answers [1, steps, classes].
        val shape = IntArray(3)
        val probe = try {
            ImageGenNative.nativeRun(rec, arrayOf(FloatArray(REC_INPUT)), arrayOf(REC_SHAPE), shape)
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            null
        }
        if (probe == null || shape[1] <= 0 || shape[2] <= 0 || probe.size != shape[1] * shape[2]) {
            close()
            throw IOException("the recognizer gave no [1, steps, classes] output")
        }
        recognizerSteps = shape[1]
        recognizerClasses = shape[2]
    }

    override fun detect(input: FloatArray, output: FloatArray) = run(detector, input, DET_SHAPE, output)

    override fun recognize(input: FloatArray, output: FloatArray) = run(recognizer, input, REC_SHAPE, output)

    private fun run(handle: Long, input: FloatArray, shape: IntArray, output: FloatArray) {
        check(handle != 0L) { "not open" }
        val result = ImageGenNative.nativeRun(handle, arrayOf(input), arrayOf(shape), null)
            ?: throw IOException("MNN refused the input")
        if (result.size != output.size) throw IOException("MNN answered ${result.size} values, not ${output.size}")
        result.copyInto(output)
    }

    override fun close() {
        if (detector != 0L) ImageGenNative.nativeFree(detector)
        if (recognizer != 0L) ImageGenNative.nativeFree(recognizer)
        detector = 0L
        recognizer = 0L
        recognizerSteps = 0
        recognizerClasses = 0
    }

    private fun load(file: File, threads: Int): Long {
        val handle = ImageGenNative.nativeLoad(file.path, arrayOf(INPUT), arrayOf(OUTPUT), threads)
        if (handle == 0L) throw IOException("${file.name} would not load")
        return handle
    }

    private companion object {
        const val INPUT = "image"
        const val OUTPUT = "probs"
        val DET_SHAPE = intArrayOf(1, PaddleOcrPipeline.DET_SIDE, PaddleOcrPipeline.DET_SIDE, 3)
        val REC_SHAPE = intArrayOf(1, PaddleOcrPipeline.REC_HEIGHT, PaddleOcrPipeline.REC_WIDTH, 3)
        const val REC_INPUT = PaddleOcrPipeline.REC_HEIGHT * PaddleOcrPipeline.REC_WIDTH * 3
    }
}

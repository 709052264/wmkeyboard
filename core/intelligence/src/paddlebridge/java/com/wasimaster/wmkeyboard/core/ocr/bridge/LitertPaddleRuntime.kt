package com.wasimaster.wmkeyboard.core.ocr.bridge

import com.wasimaster.wmkeyboard.core.ocr.PaddleRuntime
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * The half of the scanner's PaddleOCR reader that touches LiteRT, and nothing
 * else: the detector and recognizer graphs on two interpreters. Reached only
 * by reflection (see `PaddleModule.BRIDGE_CLASS`), because on Play it ships
 * in the on-demand `:feature:litert` split and the base APK has no LiteRT
 * import. Everything around the graphs is `PaddleOcrPipeline`.
 *
 * Public with a public no-arg constructor, both for `newInstance()`.
 */
class LitertPaddleRuntime : PaddleRuntime {

    private var detector: Interpreter? = null
    private var recognizer: Interpreter? = null
    private var detIn: ByteBuffer? = null
    private var detOut: ByteBuffer? = null
    private var recIn: ByteBuffer? = null
    private var recOut: ByteBuffer? = null

    override var recognizerSteps: Int = 0
        private set
    override var recognizerClasses: Int = 0
        private set

    override fun open(detector: File, recognizer: File, threads: Int) {
        close()
        val options = Interpreter.Options().apply { numThreads = threads }
        val det = Interpreter(detector, options)
        val rec = try {
            Interpreter(recognizer, options)
        } catch (t: Throwable) {
            det.close()
            throw t
        }
        this.detector = det
        this.recognizer = rec
        detIn = direct(det.getInputTensor(0).numElements())
        detOut = direct(det.getOutputTensor(0).numElements())
        recIn = direct(rec.getInputTensor(0).numElements())
        recOut = direct(rec.getOutputTensor(0).numElements())
        // [1, steps, classes]
        val shape = rec.getOutputTensor(0).shape()
        recognizerSteps = shape[shape.size - 2]
        recognizerClasses = shape[shape.size - 1]
    }

    override fun detect(input: FloatArray, output: FloatArray) =
        run(checkNotNull(detector), checkNotNull(detIn), checkNotNull(detOut), input, output)

    override fun recognize(input: FloatArray, output: FloatArray) =
        run(checkNotNull(recognizer), checkNotNull(recIn), checkNotNull(recOut), input, output)

    private fun run(interpreter: Interpreter, inBuf: ByteBuffer, outBuf: ByteBuffer, input: FloatArray, output: FloatArray) {
        inBuf.rewind()
        inBuf.asFloatBuffer().put(input)
        outBuf.rewind()
        interpreter.run(inBuf, outBuf)
        outBuf.rewind()
        outBuf.asFloatBuffer().get(output)
    }

    override fun close() {
        detector?.close()
        recognizer?.close()
        detector = null
        recognizer = null
        detIn = null
        detOut = null
        recIn = null
        recOut = null
    }

    private fun direct(floats: Int): ByteBuffer =
        ByteBuffer.allocateDirect(floats * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
}

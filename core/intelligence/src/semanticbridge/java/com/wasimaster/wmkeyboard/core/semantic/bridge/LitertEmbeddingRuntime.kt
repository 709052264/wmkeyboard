package com.wasimaster.wmkeyboard.core.semantic.bridge

import com.wasimaster.wmkeyboard.core.semantic.EmbeddingInput
import com.wasimaster.wmkeyboard.core.semantic.EmbeddingRuntime
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * The half of search by meaning that touches LiteRT, and nothing else:
 * EmbeddingGemma 2's text encoder, and its vision encoder and adapter, each
 * run through the named signature that fits. Reached only by reflection (see
 * `SemanticModule.BRIDGE_CLASS`), because on Play it ships in the on-demand
 * `:feature:litert` split and the base APK has no LiteRT import.
 *
 * The encoder carries three signatures, 128, 256 and 512 tokens, sharing one
 * set of weights. The interpreter prepares subgraph 0 when it is built, which
 * the repackaging made the 128-token one; the longer two are prepared the
 * first time a text needs them.
 *
 * Public with a public no-arg constructor, both for `newInstance()`.
 */
class LitertEmbeddingRuntime : EmbeddingRuntime {

    private var text: Interpreter? = null
    private var vision: Interpreter? = null
    private var adapter: Interpreter? = null

    /** Per signature length: the embeddings, the mask, the output. */
    private val textBuffers = HashMap<Int, Array<ByteBuffer>>()
    private var visionBuffers: Array<ByteBuffer>? = null

    override fun openText(encoder: File, threads: Int) {
        text?.close()
        textBuffers.clear()
        text = Interpreter(encoder, Interpreter.Options().apply { numThreads = threads })
    }

    override fun encode(embeddings: FloatArray, length: Int, out: FloatArray) {
        val interpreter = checkNotNull(text) { "the text encoder is not open" }
        val sequence = EmbeddingInput.sequenceLength(length)
        val rows = minOf(length, sequence)
        val (input, mask, output) = textBuffers.getOrPut(sequence) {
            arrayOf(
                direct(sequence * EmbeddingInput.WIDTH * Float.SIZE_BYTES),
                direct(sequence * Float.SIZE_BYTES),
                direct(EmbeddingInput.DIMENSIONS * Float.SIZE_BYTES),
            )
        }
        input.clear()
        val floats = input.asFloatBuffer()
        floats.put(embeddings, 0, rows * EmbeddingInput.WIDTH)
        while (floats.hasRemaining()) floats.put(0f)
        mask.clear()
        val maskFloats = mask.asFloatBuffer()
        for (k in 0 until sequence) maskFloats.put(if (k < rows) 1f else 0f)
        input.rewind()
        mask.rewind()
        output.clear()
        interpreter.runSignature(
            mapOf("embeddings" to input, "input_mask" to mask),
            mapOf("encodings" to output),
            "encoder_1x$sequence",
        )
        output.rewind()
        output.asFloatBuffer().get(out, 0, EmbeddingInput.DIMENSIONS)
    }

    override fun openVision(encoder: File, adapter: File, threads: Int) {
        closeVision()
        val options = Interpreter.Options().apply { numThreads = threads }
        val vision = Interpreter(encoder, options)
        val adapting = try {
            Interpreter(adapter, options)
        } catch (t: Throwable) {
            vision.close()
            throw t
        }
        this.vision = vision
        this.adapter = adapting
        val tokens = EmbeddingInput.IMAGE_TOKENS
        visionBuffers = arrayOf(
            direct(EmbeddingInput.MAX_PATCHES * EmbeddingInput.PATCH_VALUES * Float.SIZE_BYTES),
            direct(EmbeddingInput.MAX_PATCHES * 2 * Int.SIZE_BYTES),
            direct(tokens * FEATURES * Float.SIZE_BYTES),
            direct(tokens),
            direct(tokens * EmbeddingInput.WIDTH * Float.SIZE_BYTES),
        )
    }

    override fun see(patches: FloatArray, positions: IntArray, out: FloatArray): Int {
        val encoder = checkNotNull(vision) { "the vision encoder is not open" }
        val adapting = checkNotNull(adapter)
        val (images, xy, features, mask, soft) = checkNotNull(visionBuffers)
        images.clear()
        images.asFloatBuffer().put(patches)
        xy.clear()
        xy.asIntBuffer().put(positions)
        features.clear()
        mask.clear()
        encoder.runSignature(
            mapOf("images" to images, "positions_xy" to xy),
            mapOf("features" to features, "mask" to mask),
            "vision_${EmbeddingInput.IMAGE_TOKENS}",
        )
        features.rewind()
        soft.clear()
        adapting.runSignature(
            mapOf("soft_tokens" to features),
            mapOf("mm_embedding" to soft),
            "vision_adapter_${EmbeddingInput.IMAGE_TOKENS}",
        )
        var valid = 0
        for (k in 0 until EmbeddingInput.IMAGE_TOKENS) if (mask.get(k).toInt() != 0) valid++
        soft.rewind()
        soft.asFloatBuffer().get(out, 0, valid * EmbeddingInput.WIDTH)
        return valid
    }

    override fun closeVision() {
        vision?.close()
        adapter?.close()
        vision = null
        adapter = null
        visionBuffers = null
    }

    override fun close() {
        closeVision()
        text?.close()
        text = null
        textBuffers.clear()
    }

    private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    private companion object {
        /** Width of the vision encoder's features, before the adapter. */
        const val FEATURES = 768
    }
}

package com.wasimaster.wmkeyboard.core.voice.asr4all

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decode around the model, against a fake program: a streaming method that
 * emits a scripted frame per call, and a second pass that writes a capital and
 * a full stop. The real model is checked on the desktop; see
 * native/asr4all-jni/README.md.
 */
class Asr4AllStreamTest {

    private val vocab = listOf(" hello", " wor", "ld", " again")
    private val blank = vocab.size

    private val tokens = vocab.joinToString(", ") { "\"$it\"" }

    private val metadata = Asr4AllMetadata.parse(
        """
        {"vocab": [$tokens], "blank_id": $blank, "samples_per_frame": 4,
         "tiers": [{"name": "stream", "pcec": {"name": "pcec", "flush": "pcec_flush", "cache_tensors": 1}}],
         "default_tier": "stream",
         "pcec": {"vocab": [$tokens], "blank_id": 99, "pad_in": 7, "pause_blackout": 9, "real_pause": false,
                  "ctrl": {"cap": 10, "allcaps": 11, "period": 12, "comma": 13, "question": 14}}}
        """,
    )

    /** Two frames per call, a window of four frames (16 samples), one-wide hidden state. */
    private inner class FakeProgram(private val frames: List<Int>) : Asr4AllRunner {
        var calls = 0

        /** The second pass's one-token delay line: what it writes lags what it reads. */
        private var delayed = 7L
        private val streamShape = MethodShape(
            inputs = listOf(
                TensorSpec(DType.FLOAT, longArrayOf(1, 16)),
                TensorSpec(DType.FLOAT, longArrayOf(1)),
                TensorSpec(DType.FLOAT, longArrayOf(1)),
            ),
            outputs = listOf(
                TensorSpec(DType.FLOAT, longArrayOf(1, 2, vocab.size + 1L)),
                TensorSpec(DType.FLOAT, longArrayOf(1)),
                TensorSpec(DType.FLOAT, longArrayOf(1)),
                TensorSpec(DType.FLOAT, longArrayOf(1, 2, 1)),
            ),
        )
        private val pcecInputs = listOf(
            TensorSpec(DType.LONG, longArrayOf(1, 1)),
            TensorSpec(DType.LONG, longArrayOf(1, 1)),
            TensorSpec(DType.FLOAT, longArrayOf(1, 1, 1)),
            TensorSpec(DType.FLOAT, longArrayOf(1)),
        )

        override fun shape(method: String): MethodShape? = when (method) {
            "stream" -> streamShape
            "pcec" -> MethodShape(pcecInputs, listOf(TensorSpec(DType.LONG, longArrayOf(1, 2)), pcecInputs[3]))
            // Flush: one slot of lookahead, so (5 - 1) / 2 - 1 = 1.
            "pcec_flush" -> MethodShape(
                pcecInputs.subList(0, 3) + TensorSpec(DType.LONG, LongArray(0)) + pcecInputs[3],
                listOf(TensorSpec(DType.LONG, longArrayOf(1, 5))),
            )
            else -> null
        }

        override fun run(method: String, inputs: List<Tensor>): List<Tensor> {
            if (method == "pcec") {
                // Writes the token it read one block ago, capitalised.
                val out = longArrayOf(10, delayed)
                delayed = inputs[0].longs[0]
                return listOf(Tensor(longArrayOf(1, 2), out), inputs[3])
            }
            if (method == "pcec_flush") {
                // Empties the delay line and ends the sentence.
                return listOf(Tensor(longArrayOf(1, 5), longArrayOf(10, delayed, 12, 99, 99)))
            }
            val logits = FloatArray(2 * (vocab.size + 1))
            for (f in 0 until 2) {
                val id = frames.getOrElse(calls * 2 + f) { blank }
                logits[f * (vocab.size + 1) + id] = 1f
            }
            calls++
            return listOf(
                Tensor(longArrayOf(1, 2, vocab.size + 1L), logits),
                inputs[1],
                inputs[2],
                Tensor(longArrayOf(1, 2, 1), FloatArray(2)),
            )
        }
    }

    @Test
    fun `frames collapse into words, repeats and blanks dropped`() {
        // hello hello blank wor ld ld blank again
        val program = FakeProgram(listOf(0, 0, blank, 1, 2, 2, blank, 3))
        val stream = Asr4AllStream(program, metadata, usePcec = false)
        stream.feed(FloatArray(4 * 8))
        stream.finish()
        assertEquals("hello world again", stream.text())
    }

    @Test
    fun `a window is only decoded once all of it has arrived`() {
        val program = FakeProgram(listOf(0, 0, 1, 2))
        val stream = Asr4AllStream(program, metadata, usePcec = false)
        stream.feed(FloatArray(15))
        assertEquals(0, program.calls)
        stream.feed(FloatArray(1))
        assertEquals(1, program.calls)
        assertEquals("hello", stream.text())
    }

    @Test
    fun `chunk size does not change the transcript`() {
        val frames = listOf(0, blank, 1, 2, blank, 3, 3, blank)
        val whole = Asr4AllStream(FakeProgram(frames), metadata, usePcec = false).run {
            feed(FloatArray(32)); finish(); text()
        }
        val trickled = Asr4AllStream(FakeProgram(frames), metadata, usePcec = false).run {
            repeat(32) { feed(FloatArray(1)) }
            finish()
            text()
        }
        assertEquals(whole, trickled)
    }

    @Test
    fun `the second pass capitalises and punctuates`() {
        val program = FakeProgram(listOf(0, blank, 3, blank))
        val stream = Asr4AllStream(program, metadata)
        stream.feed(FloatArray(16))
        val text = stream.finish()
        assertEquals("Hello Again.", text)
    }

    @Test
    fun `only English is asr4all's`() {
        assertTrue(Asr4AllStore.supports("en"))
        assertTrue(Asr4AllStore.supports("en_GB"))
        assertFalse(Asr4AllStore.supports("bn"))
    }

    @Test
    fun `method metadata reads back as shapes`() {
        val shape = Asr4AllNative.parseShape(longArrayOf(1, 1, 6, 2, 1, 22400, 4, 0))
        assertEquals(1, shape.inputs.size)
        assertEquals(22400L, shape.inputs[0].dims[1])
        assertEquals(DType.LONG, shape.outputs[0].dtype)
        assertEquals(0, shape.outputs[0].dims.size)
    }
}

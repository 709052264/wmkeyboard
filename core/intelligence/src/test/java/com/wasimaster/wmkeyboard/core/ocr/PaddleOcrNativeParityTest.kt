package com.wasimaster.wmkeyboard.core.ocr

import com.wasimaster.wmkeyboard.core.engine.NativeEngine
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The native engine's two OCR kernels (`kernels/box_average.rs`,
 * `kernels/argmax.rs`) against the Kotlin they replace, on generated pages
 * of printed text: the detector input bit for bit, every step's best class,
 * and the text a whole read gives, through a stand-in runtime whose answers
 * depend on what it is shown, so any difference in the inputs would show
 * in the lines.
 */
class PaddleOcrNativeParityTest {

    private val wasForced = NativeEngine.forceKotlin

    @Before
    fun needsTheLibrary() {
        assumeTrue("libwm_engine_jni is not built", NativeEngine.loaded)
    }

    @After
    fun restore() {
        NativeEngine.forceKotlin = wasForced
    }

    private fun <T> both(block: () -> T): Pair<T, T> {
        NativeEngine.forceKotlin = true
        val kotlin = block()
        NativeEngine.forceKotlin = false
        val native = block()
        return kotlin to native
    }

    @Test
    fun theDetectorInputIsBitForBitTheKotlins() {
        // Phone photos both ways round, a scale just under 1, odd ratios, a
        // long strip, and small photos the Kotlin grows (no kernel there).
        val sizes = listOf(
            4000 to 3000, 3024 to 4032, 641 to 480, 1203 to 1601, 2000 to 37, 37 to 2000,
            1000 to 999, 4096 to 4096, 640 to 640, 500 to 300,
        )
        for ((w, h) in sizes) {
            val page = page(w, h, seed = w * 31 + h)
            val (kotlin, native) = both {
                val input = FloatArray(PaddleOcrPipeline.DET_SIDE * PaddleOcrPipeline.DET_SIDE * 3) { Float.NaN }
                val box = PaddleOcrPipeline.detectorInput(page, w, h, input)
                Triple(box.scale, box.width to box.height, input.map { it.toRawBits() }.toIntArray())
            }
            assertEquals("${w}x$h scale", kotlin.first, native.first)
            assertEquals("${w}x$h size", kotlin.second, native.second)
            assertArrayEquals("${w}x$h pixels", kotlin.third, native.third)
        }
    }

    @Test
    fun everyStepPicksTheSameClass() {
        val random = Random(41)
        val steps = 40
        for (classes in listOf(1, 2, 3, 5, 15, 16, 17, 33, 6625, 18_385)) {
            val charset = List(classes) { if (it == 0) "" else "<$it>" }
            repeat(6) { kind ->
                val logits = logits(random, steps, classes, kind)
                val (kotlin, native) = both { PaddleOcrPipeline.ctcDecode(logits, steps, classes, charset) }
                assertEquals("classes=$classes kind=$kind", kotlin.text, native.text)
                assertEquals("classes=$classes kind=$kind", kotlin.confidence.toRawBits(), native.confidence.toRawBits())
            }
        }
    }

    @Test
    fun aWholeReadGivesTheSameText() {
        for ((w, h) in listOf(4000 to 3000, 3024 to 4032, 1600 to 1200)) {
            val page = page(w, h, seed = w + h)
            val (kotlin, native) = both { PaddleOcrPipeline.read(StandIn(), page, w, h, StandIn.CHARSET) }
            assertTrue("${w}x$h: the stand-in read nothing", kotlin.isNotEmpty())
            assertEquals("${w}x$h", kotlin, native)
        }
    }

    /**
     * Logits with what the strict scan must get right: softmax-like rows,
     * ties at the top, both zeros, NaNs (one at the start of a row), and
     * infinities.
     */
    private fun logits(random: Random, steps: Int, classes: Int, kind: Int): FloatArray {
        val out = FloatArray(steps * classes) { random.nextFloat() * 0.01f }
        for (t in 0 until steps) {
            val row = t * classes
            val top = random.nextInt(classes)
            when (kind) {
                0 -> out[row + top] = 0.9f
                1 -> { out[row + top] = 0.7f; out[row + random.nextInt(classes)] = 0.7f; out[row + classes - 1] = 0.7f }
                2 -> for (k in 0 until classes) out[row + k] = if (random.nextBoolean()) 0f else -0f
                3 -> { out[row + random.nextInt(classes)] = Float.NaN; out[row + top] = 0.8f; if (t % 5 == 0) out[row] = Float.NaN }
                4 -> { out[row + top] = Float.POSITIVE_INFINITY; out[row + random.nextInt(classes)] = Float.POSITIVE_INFINITY }
                else -> for (k in 0 until classes) out[row + k] = (random.nextInt(4)).toFloat()
            }
        }
        return out
    }

    /** A white page of printed lines: level, slightly tilted, and one vertical column. */
    private fun page(w: Int, h: Int, seed: Int): IntArray {
        val random = Random(seed)
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color(250, 248, 240)
        g.fillRect(0, 0, w, h)
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val size = max(8, min(w, h) / 30)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, size)
            g.color = Color(20, 20, 30)
            var y = size * 2
            var line = 0
            while (y < h - size) {
                val words = List(4 + random.nextInt(6)) { WORDS[random.nextInt(WORDS.size)] }.joinToString(" ")
                val saved = g.transform
                if (line % 3 == 1) g.rotate(0.03, w / 2.0, y.toDouble())
                g.drawString(words, size, y)
                g.transform = saved
                y += size * 2 + random.nextInt(size)
                line++
            }
            g.rotate(Math.PI / 2, w - size * 2.0, size.toDouble())
            g.drawString("縦書きの列", w - size * 2, size)
        } catch (e: Error) {
            // No fonts on this machine: bars of ink where words would be.
            g.color = Color(20, 20, 30)
            var y = 40
            while (y < h - 20) {
                var x = 20
                while (x < w - 60) { g.fillRect(x, y, 6 + random.nextInt(40), 12); x += 60 }
                y += 40
            }
        } finally {
            g.dispose()
        }
        val pixels = IntArray(w * h)
        image.getRGB(0, 0, w, h, pixels, 0, w)
        // Sensor noise, so no two neighbouring boxes average alike.
        for (i in pixels.indices) {
            val n = random.nextInt(9) - 4
            val p = pixels[i]
            val r = ((p shr 16 and 0xff) + n).coerceIn(0, 255)
            val gg = ((p shr 8 and 0xff) + n).coerceIn(0, 255)
            val b = ((p and 0xff) - n).coerceIn(0, 255)
            pixels[i] = (0xff shl 24) or (r shl 16) or (gg shl 8) or b
        }
        return pixels
    }

    /**
     * A runtime whose answers are functions of its inputs. The detector
     * calls a pixel text where the page is dark nearby, smeared along the
     * row so letters join into lines; the recognizer reads each eighth of
     * a strip's width as a class picked from its ink, with ties, so a
     * one-bit change anywhere upstream would change a character.
     */
    private class StandIn : PaddleRuntime {
        override val recognizerSteps = 40
        override val recognizerClasses = CLASSES

        override fun open(detector: File, recognizer: File, threads: Int) = Unit
        override fun close() = Unit

        override fun detect(input: FloatArray, output: FloatArray) {
            val side = PaddleOcrPipeline.DET_SIDE
            val dark = FloatArray(side * side)
            for (i in dark.indices) dark[i] = if (input[i * 3] + input[i * 3 + 1] + input[i * 3 + 2] < 0f) 1f else 0f
            for (y in 0 until side) {
                for (x in 0 until side) {
                    var ink = 0f
                    for (dy in -1..1) {
                        val yy = y + dy
                        if (yy !in 0 until side) continue
                        for (dx in -6..6) {
                            val xx = x + dx
                            if (xx in 0 until side) ink = max(ink, dark[yy * side + xx])
                        }
                    }
                    output[y * side + x] = ink * 0.95f
                }
            }
        }

        override fun recognize(input: FloatArray, output: FloatArray) {
            val h = PaddleOcrPipeline.REC_HEIGHT
            val w = PaddleOcrPipeline.REC_WIDTH
            output.fill(0f)
            val band = w / recognizerSteps
            for (t in 0 until recognizerSteps) {
                var ink = 0.0
                for (y in 0 until h) for (x in t * band until (t + 1) * band) {
                    val at = (y * w + x) * 3
                    ink += abs(input[at] + input[at + 1] + input[at + 2]).toDouble()
                }
                val bits = ink.toRawBits()
                val row = t * CLASSES
                val k = if (ink < 40.0) 0 else 1 + ((bits xor (bits ushr 29)) and 0x7fffffff).toInt() % (CLASSES - 1)
                output[row + k] = 0.9f
                // A tie later in the row, which the first-max rule must ignore.
                val other = (k * 7 + 3) % CLASSES
                if (other != k) output[row + other] = if (other > k) 0.9f else 0.1f
            }
        }

        companion object {
            const val CLASSES = 18_385
            val CHARSET = List(CLASSES) { if (it == 0) "" else ('a' + it % 26).toString() }
        }
    }

    private companion object {
        val WORDS = listOf(
            "receipt", "total", "the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog",
            "invoice", "2026", "Tk", "৳120", "amount", "due", "café", "naïve", "über", "OCR",
        )
    }
}

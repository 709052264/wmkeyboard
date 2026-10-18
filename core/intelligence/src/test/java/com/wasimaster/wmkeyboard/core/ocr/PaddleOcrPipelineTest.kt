package com.wasimaster.wmkeyboard.core.ocr

import com.wasimaster.wmkeyboard.core.ocr.PaddleOcrPipeline.TextBox
import java.io.File
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PaddleOCR's pre- and post-processing, with a stand-in for the two graphs. */
class PaddleOcrPipelineTest {

    private val side = PaddleOcrPipeline.DET_SIDE

    @Test
    fun charsetPutsTheBlankFirstAndTheSpaceLast() {
        val charset = PaddleOcrPipeline.charset("一\r\n二\n三\n")
        assertEquals(listOf("", "一", "二", "三", " "), charset)
    }

    @Test
    fun ctcMergesRepeatsAndDropsBlanks() {
        val charset = listOf("", "a", "b", " ")
        // a a _ a b b _ space
        val steps = listOf(1, 1, 0, 1, 2, 2, 0, 3)
        val output = FloatArray(steps.size * charset.size)
        steps.forEachIndexed { t, k -> output[t * charset.size + k] = 0.9f }
        val line = PaddleOcrPipeline.ctcDecode(output, steps.size, charset.size, charset)
        assertEquals("aab ", line.text)
        assertEquals(0.9f, line.confidence, 1e-6f)
    }

    @Test
    fun aLevelRegionBecomesItsBoundingRectangleGrown() {
        // A 200 × 20 block of text probability at (100, 50) in the detector's square.
        val prob = FloatArray(side * side)
        for (y in 50 until 70) for (x in 100 until 300) prob[y * side + x] = 0.9f
        val boxes = PaddleOcrPipeline.boxes(prob, PaddleOcrPipeline.Letterbox(0.5f, side, side))
        assertEquals(1, boxes.size)
        val box = boxes.single()
        // Back in photo pixels (scale 0.5): centre (400, 120), 400 × 40 before growing.
        assertEquals(400f, box.cx, 0.5f)
        assertEquals(120f, box.cy, 0.5f)
        assertEquals(0f, box.angle, 1e-4f)
        // DB's unclip: offset = area × 1.5 / perimeter = 200·20·1.5 / 440 ≈ 13.6 on each side.
        val grow = 200f * 20f * 1.5f / 440f
        assertEquals((200f + 2 * grow) * 2, box.width, 0.5f)
        assertEquals((20f + 2 * grow) * 2, box.height, 0.5f)
    }

    @Test
    fun weakOrTinyRegionsAreDropped() {
        val prob = FloatArray(side * side)
        // Above the binary threshold but under the box threshold on average.
        for (y in 10 until 40) for (x in 10 until 200) prob[y * side + x] = 0.4f
        // Strong but too small.
        prob[300 * side + 300] = 1f
        assertTrue(PaddleOcrPipeline.boxes(prob, PaddleOcrPipeline.Letterbox(1f, side, side)).isEmpty())
    }

    @Test
    fun aTiltedRectangleKeepsItsLongSideAlong() {
        // Corners of a 100 × 20 rectangle turned 30°, as hull points.
        val angle = PI / 6
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        val points = listOf(-50.0 to -10.0, 50.0 to -10.0, 50.0 to 10.0, -50.0 to 10.0).map { (u, v) ->
            val x = (200 + u * c - v * s).toInt()
            val y = (200 + u * s + v * c).toInt()
            (x.toLong() shl 32) or y.toLong()
        }
        val box = PaddleOcrPipeline.minAreaRect(PaddleOcrPipeline.hull(points))!!
        assertEquals(angle.toFloat(), box.angle, 0.03f)
        assertEquals(100f, box.width, 2f)
        assertEquals(20f, box.height, 2f)
    }

    @Test
    fun rowsReadTopToBottomAndColumnsRightToLeft() {
        val left = TextBox(100f, 100f, 150f, 30f, 0f)
        val right = TextBox(400f, 104f, 150f, 30f, 0f)
        val below = TextBox(120f, 200f, 300f, 30f, 0f)
        val columnA = TextBox(900f, 300f, 40f, 400f, 0f)
        val columnB = TextBox(800f, 300f, 40f, 400f, 0f)
        val rows = PaddleOcrPipeline.readingOrder(listOf(columnB, below, right, columnA, left))
        assertEquals(listOf(listOf(left, right), listOf(below), listOf(columnA), listOf(columnB)), rows)
    }

    @Test
    fun aLongLineIsCutInItsGaps() {
        // 700 columns of white paper, inked everywhere between 10 and 690
        // except a gap at 290..294: the first cut falls in the gap.
        val width = 700
        val rgb = FloatArray(width * PaddleOcrPipeline.REC_HEIGHT * 3) { 255f }
        for (y in 10 until 30) {
            for (x in 10 until 690) {
                if (x in 290..294) continue
                for (ch in 0 until 3) rgb[(y * width + x) * 3 + ch] = 0f
            }
        }
        val cuts = PaddleOcrPipeline.cuts(PaddleOcrPipeline.Strip(width, rgb))
        assertEquals(0, cuts.first())
        assertTrue(cuts.toString(), cuts[1] in 290..294)
        assertEquals(width, cuts.last())
        for ((from, to) in cuts.zipWithNext()) assertTrue("$from..$to", to - from <= PaddleOcrPipeline.REC_WIDTH)
    }

    @Test
    fun aWholeReadThroughAStandInRuntime() {
        // A 1280 × 960 photo: white, one black line of text. The detector
        // stand-in reports the line where it is; the recognizer reads "hi".
        val width = 1280
        val height = 960
        val pixels = IntArray(width * height) { 0xffffffff.toInt() }
        for (y in 400 until 440) for (x in 200 until 1000) if (x % 20 < 10) pixels[y * width + x] = 0xff000000.toInt()
        val charset = listOf("", "h", "i", " ")
        val runtime = object : PaddleRuntime {
            override val recognizerSteps = 4
            override val recognizerClasses = charset.size
            var strips = 0
            override fun open(detector: File, recognizer: File, threads: Int) = Unit
            override fun detect(input: FloatArray, output: FloatArray) {
                // The photo was halved into the square: the line is at 100..500 × 200..220.
                for (y in 200 until 220) for (x in 100 until 500) output[y * side + x] = 0.95f
            }
            override fun recognize(input: FloatArray, output: FloatArray) {
                strips++
                output.fill(0f)
                listOf(1, 0, 2, 0).forEachIndexed { t, k -> output[t * charset.size + k] = 0.8f }
            }
            override fun close() = Unit
        }
        val lines = PaddleOcrPipeline.read(runtime, pixels, width, height, charset)
        assertEquals(1, lines.size)
        // The grown line is wider than one strip, so it is read in pieces.
        assertTrue(runtime.strips > 1)
        assertEquals("hi".repeat(runtime.strips), lines.single())
    }
}

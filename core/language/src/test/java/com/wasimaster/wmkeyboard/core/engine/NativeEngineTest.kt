package com.wasimaster.wmkeyboard.core.engine

import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge's shared plumbing: the library loads on this JVM, the switch
 * flips the fast path off and on, and a batched kernel call crosses arrays
 * both ways and agrees with the same loop in Kotlin.
 */
class NativeEngineTest {

    @Test fun loadsAndTheSwitchTurnsTheFastPathOff() {
        assertTrue(NativeEngine.loaded)
        val before = NativeEngine.forceKotlin
        try {
            NativeEngine.forceKotlin = true
            assertFalse(NativeEngine.useNative)
            NativeEngine.forceKotlin = false
            assertTrue(NativeEngine.useNative)
        } finally {
            NativeEngine.forceKotlin = before
        }
    }

    @Test fun anArm64HostReportsNeon() {
        val arch = System.getProperty("os.arch").orEmpty()
        if (arch == "aarch64" || arch == "arm64") {
            assertTrue(NativeEngine.cpuFeatures() and NativeEngine.FEATURE_NEON != 0)
        }
    }

    @Test fun dotRowsMatchesKotlinWithTheKernelsOnAndOff() {
        val random = Random(42)
        val dim = 67
        val rows = 9
        val query = FloatArray(dim) { random.nextFloat() * 2 - 1 }
        val matrix = FloatArray(dim * rows + 5) { random.nextFloat() * 2 - 1 }
        val expected = FloatArray(rows) { r -> (0 until dim).sumOf { (query[it] * matrix[r * dim + it]).toDouble() }.toFloat() }
        val before = NativeEngine.forceKotlin
        try {
            for (scalar in listOf(false, true)) {
                NativeEngine.forceKotlin = scalar
                val out = FloatArray(rows + 1)
                assertEquals(rows, NativeEngine.dotRows(query, matrix, dim, out))
                for (r in 0 until rows) assertEquals("row $r, scalar=$scalar", expected[r], out[r], 1e-4f)
                assertEquals(0f, out[rows])
            }
        } finally {
            NativeEngine.forceKotlin = before
        }
        assertEquals(0, NativeEngine.dotRows(query, matrix, 0, FloatArray(1)))
    }

    /** WebmFrames.rgbOf's loop, on the same planes. */
    private fun kotlinRgb(
        y: ByteBuffer, yRow: Int, u: ByteBuffer, v: ByteBuffer, cRow: Int, cPixel: Int,
        left: Int, top: Int, rows: Int, cols: Int, width: Int, out: IntArray,
    ) {
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val luma = y.get((top + row) * yRow + left + col).toInt() and 0xFF
                val c = (top + row) / 2 * cRow + (left + col) / 2 * cPixel
                val cb = (u.get(c).toInt() and 0xFF) - 128
                val cr = (v.get(c).toInt() and 0xFF) - 128
                val l = 298 * (luma - 16)
                val r = ((l + 409 * cr + 128) shr 8).coerceIn(0, 255)
                val g = ((l - 100 * cb - 208 * cr + 128) shr 8).coerceIn(0, 255)
                val b = ((l + 516 * cb + 128) shr 8).coerceIn(0, 255)
                out[row * width + col] = (r shl 16) or (g shl 8) or b
            }
        }
    }

    @Test fun yuvToRgbMatchesTheKotlinLoopOnPlanesAsImagesHandThemOver() {
        val before = NativeEngine.forceKotlin
        NativeEngine.forceKotlin = false
        try {
            yuvCases()
        } finally {
            NativeEngine.forceKotlin = before
        }
    }

    private fun yuvCases() {
        val random = Random(7)
        fun direct(n: Int) = ByteBuffer.allocateDirect(n).also { b -> repeat(n) { b.put(it, random.nextInt(256).toByte()) } }
        for ((w, h) in listOf(1 to 1, 17 to 5, 64 to 9, 100 to 7)) {
            for (semi in listOf(false, true)) {
                val yRow = w + 3
                val y = direct(yRow * (h - 1) + w)
                val cw = (w + 1) / 2
                val ch = (h + 1) / 2
                val cPixel = if (semi) 2 else 1
                val cRow = cw * cPixel + 2
                // Semi-planar: one interleaved buffer, V a byte on, each view ending at its last byte.
                val (u, v) = if (semi) {
                    val uv = direct(cRow * (ch - 1) + 2 * cw)
                    uv.duplicate().apply { limit(capacity() - 1) }.slice() to uv.duplicate().apply { position(1) }.slice()
                } else {
                    direct(cRow * (ch - 1) + cw) to direct(cRow * (ch - 1) + cw)
                }
                for ((left, top) in listOf(0 to 0, 1 to 1, 2 to 0)) {
                    if (left >= w || top >= h) continue
                    val rows = h - top
                    val cols = w - left
                    val expected = IntArray((w + 1) * h)
                    kotlinRgb(y, yRow, u, v, cRow, cPixel, left, top, rows, cols, w + 1, expected)
                    val out = IntArray(expected.size)
                    assertTrue(NativeEngine.yuvToRgb(y, yRow, 1, u, cRow, cPixel, v, cRow, cPixel, left, top, rows, cols, w + 1, out))
                    assertArrayEquals("${w}x$h semi=$semi crop=($left,$top)", expected, out)
                }
            }
        }
        // A heap buffer, and a frame reaching past its planes, are refused untouched.
        val out = IntArray(16) { 7 }
        val heap = ByteBuffer.allocate(16)
        val small = ByteBuffer.allocateDirect(8)
        assertFalse(NativeEngine.yuvToRgb(heap, 16, 1, small, 8, 1, small, 8, 1, 0, 0, 1, 16, 16, out))
        assertFalse(NativeEngine.yuvToRgb(ByteBuffer.allocateDirect(15), 16, 1, small, 8, 1, small, 8, 1, 0, 0, 1, 16, 16, out))
        assertArrayEquals(IntArray(16) { 7 }, out)
    }
}

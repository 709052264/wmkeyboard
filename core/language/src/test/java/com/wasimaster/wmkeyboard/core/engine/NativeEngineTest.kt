package com.wasimaster.wmkeyboard.core.engine

import kotlin.random.Random
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
}

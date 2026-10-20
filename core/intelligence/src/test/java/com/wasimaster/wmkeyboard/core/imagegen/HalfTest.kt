package com.wasimaster.wmkeyboard.core.imagegen

import org.junit.Assert.assertEquals
import org.junit.Test

class HalfTest {

    @Test
    fun knownValues() {
        assertEquals(0x3c00.toShort(), Half.fromFloat(1f))
        assertEquals(0xc000.toShort(), Half.fromFloat(-2f))
        assertEquals(0x7bff.toShort(), Half.fromFloat(65504f))
        assertEquals(0x7c00.toShort(), Half.fromFloat(1e6f))
        assertEquals(0x0000.toShort(), Half.fromFloat(0f))
        assertEquals(1f, Half.toFloat(0x3c00), 0f)
        assertEquals(-2f, Half.toFloat(0xc000.toShort()), 0f)
        assertEquals(5.9604645e-8f, Half.toFloat(0x0001), 0f)
        assertEquals(Float.POSITIVE_INFINITY, Half.toFloat(0x7c00), 0f)
    }

    @Test
    fun roundTripsWithinHalfPrecision() {
        val values = floatArrayOf(0.1f, -0.333f, 3.14159f, 1000.5f, 6.1e-5f, 2e-6f, -12345f)
        for (v in values) {
            val back = Half.toFloat(Half.fromFloat(v))
            assertEquals("$v", v, back, maxOf(kotlin.math.abs(v) * 1e-3f, 6e-8f))
        }
    }
}

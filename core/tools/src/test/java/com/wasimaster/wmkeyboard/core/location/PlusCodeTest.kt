package com.wasimaster.wmkeyboard.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Open Location Code against the reference implementation (the
 * `openlocationcode` Python package, 1.0.1): every vector here is what that
 * library printed for the same input.
 */
class PlusCodeTest {

    private data class Vector(val lat: Double, val lon: Double, val length: Int, val code: String, val centerLat: Double, val centerLon: Double)

    private val vectors = listOf(
        Vector(23.7806, 90.4193, 10, "7MMGQCJ9+6P", 23.7805625, 90.4193125),
        Vector(47.36559, 8.524997, 10, "8FVC9G8F+6X", 47.3655625, 8.5249375),
        Vector(-33.856784, 151.215297, 10, "4RRH46V8+74", -33.8568125, 151.2153125),
        Vector(51.501364, -0.14189, 10, "9C3XGV25+G6", 51.5013125, -0.1419375),
        Vector(21.422487, 39.826206, 10, "7GHXCRCG+XF", 21.4224375, 39.8261875),
        Vector(40.689247, -74.044502, 11, "87G7MXQ4+M5X", 40.6892375, -74.0445156),
        Vector(23.7806, 90.4193, 8, "7MMGQCJ9+", 23.78125, 90.41875),
        Vector(23.7806, 90.4193, 6, "7MMGQC00+", 23.775, 90.425),
        Vector(90.0, 1.0, 10, "CFX3X2X2+X2", 89.9999375, 1.0000625),
        Vector(-90.0, -180.0, 10, "22222222+22", -89.9999375, -179.9999375),
        Vector(0.0, 179.99999, 10, "6VGX2X2X+2X", 0.0000625, 179.9999375),
        Vector(1.0, 181.0, 10, "62H32222+22", 1.0000625, -178.9999375),
    )

    @Test
    fun `encodes like the reference implementation`() {
        for (v in vectors) assertEquals("${v.lat},${v.lon}/${v.length}", v.code, PlusCode.encode(v.lat, v.lon, v.length))
    }

    @Test
    fun `decodes to the centre of the area`() {
        for (v in vectors) {
            val point = PlusCode.decode(v.code)
            assertNotNull(v.code, point)
            assertEquals(v.code, v.centerLat, point!!.latitude, 1e-7)
            assertEquals(v.code, v.centerLon, point.longitude, 1e-7)
        }
    }

    @Test
    fun `round trips stay inside the cell`() {
        val random = java.util.Random(350)
        repeat(500) {
            val lat = random.nextDouble() * 179.0 - 89.5
            val lon = random.nextDouble() * 359.0 - 179.5
            val decoded = PlusCode.decode(PlusCode.encode(lat, lon))!!
            assertTrue(GeoPoint(lat, lon).distanceTo(decoded) < 15.0)
        }
    }

    @Test
    fun `only full codes are accepted`() {
        assertTrue(PlusCode.isFull("7MMGQCJ9+6P"))
        assertTrue(PlusCode.isFull("7mmgqcj9+6p"))
        assertTrue(PlusCode.isFull("7MMGQC00+"))
        assertFalse("short code", PlusCode.isFull("QCJ9+6P"))
        assertFalse("no separator", PlusCode.isFull("7MMGQCJ96P"))
        assertFalse("one digit after the separator", PlusCode.isFull("7MMGQCJ9+6"))
        assertFalse("padding with digits after", PlusCode.isFull("7MMGQC00+6P"))
        assertFalse("letter outside the alphabet", PlusCode.isFull("7MMGQCJA+6P"))
        assertFalse("latitude off the globe", PlusCode.isFull("XMMGQCJ9+6P"))
        assertNull(PlusCode.decode("hello"))
    }
}

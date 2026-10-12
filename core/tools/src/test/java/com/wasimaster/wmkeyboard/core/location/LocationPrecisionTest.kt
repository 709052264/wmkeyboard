package com.wasimaster.wmkeyboard.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grid that coarse shares snap to (#350). */
class LocationPrecisionTest {

    @Test
    fun `exact changes nothing`() {
        val p = GeoPoint(23.7806, 90.4193)
        assertSame(p, LocationPrecision.EXACT.apply(p))
    }

    @Test
    fun `a snapped point stays inside its cell`() {
        val random = java.util.Random(350)
        for (precision in LocationPrecision.entries.drop(1)) {
            repeat(400) {
                val p = GeoPoint(random.nextDouble() * 160 - 80, random.nextDouble() * 359 - 179.5)
                val snapped = precision.apply(p)
                // Half the diagonal of the cell, with room for the earth not being flat.
                val limit = precision.cellMetres * 0.75
                assertTrue("$precision $p -> $snapped", p.distanceTo(snapped) <= limit)
            }
        }
    }

    @Test
    fun `nearby readings share one point, so they cannot be averaged`() {
        val sofa = GeoPoint(23.78061, 90.41931)
        val snapped = LocationPrecision.AREA.apply(sofa)
        val random = java.util.Random(7)
        var same = 0
        repeat(100) {
            val jitter = GeoPoint(sofa.latitude + (random.nextDouble() - 0.5) * 1e-4, sofa.longitude + (random.nextDouble() - 0.5) * 1e-4)
            if (LocationPrecision.AREA.apply(jitter) == snapped) same++
        }
        // Ten metres of GPS noise inside a one-kilometre cell.
        assertTrue("$same of 100", same >= 95)
    }

    @Test
    fun `snapping is idempotent`() {
        val p = GeoPoint(-33.856784, 151.215297)
        for (precision in LocationPrecision.entries) {
            val once = precision.apply(p)
            val twice = precision.apply(once)
            assertEquals(once.latitude, twice.latitude, 1e-9)
            assertEquals(once.longitude, twice.longitude, 1e-9)
        }
    }

    @Test
    fun `ids round trip and next cycles`() {
        for (precision in LocationPrecision.entries) assertSame(precision, LocationPrecision.of(precision.id))
        assertSame(LocationPrecision.EXACT, LocationPrecision.CITY.next())
    }
}

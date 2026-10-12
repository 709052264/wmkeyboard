package com.wasimaster.wmkeyboard.core.location

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoPointTest {

    @Test
    fun `distance and bearing between two known places`() {
        val dhaka = GeoPoint(23.8103, 90.4125)
        val makkah = GeoPoint(21.422487, 39.826206)
        // About 5,170 km, a little north of due west.
        assertEquals(5_172_000.0, dhaka.distanceTo(makkah), 2_000.0)
        assertEquals(277.6, dhaka.bearingTo(makkah), 1.0)
        assertEquals(0.0, dhaka.distanceTo(dhaka), 1e-6)
    }
}

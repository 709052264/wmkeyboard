package com.wasimaster.wmkeyboard.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTilesTest {

    @Test
    fun `slippy tile numbers`() {
        // The tile holding Dhaka at zoom 16, from the published formula.
        val (x, y) = MapTiles.tileOf(GeoPoint(23.7806, 90.4193), 16)
        assertEquals(49228, x.toInt())
        assertEquals(28308, y.toInt())
        assertEquals("https://t.example/16/49228/28308.png", MapTiles.url("https://t.example/{z}/{x}/{y}.png", 16, 49228, 28308))
    }

    @Test
    fun `the square around a point wraps at the date line`() {
        val tiles = MapTiles.around(GeoPoint(0.0, 179.999), 2)
        assertEquals(9, tiles.size)
        assertTrue(tiles.any { it.second == 0 })
    }

    @Test
    fun `only a full template is usable`() {
        assertTrue(MapTiles.isUsable("https://tile.example.org/{z}/{x}/{y}.png"))
        assertFalse(MapTiles.isUsable("https://tile.example.org/{z}/{x}.png"))
        assertFalse(MapTiles.isUsable("ftp://tile/{z}/{x}/{y}"))
        assertFalse(MapTiles.isUsable(""))
    }

    @Test
    fun `zoom follows the scale`() {
        // About a metre a pixel at the equator is zoom 17.
        assertEquals(17, MapTiles.zoomFor(pxPerMetre = 0.85, latitude = 0.0))
    }
}

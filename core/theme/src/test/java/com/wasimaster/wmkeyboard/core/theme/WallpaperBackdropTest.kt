package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** The parts of the wallpaper backdrop that need no phone. */
class WallpaperBackdropTest {

    @Test
    fun `a band name is read loosely and defaults to the bottom`() {
        assertEquals(WallpaperBand.CENTER, wallpaperBandOrDefault("center"))
        assertEquals(WallpaperBand.TOP, wallpaperBandOrDefault("TOP"))
        assertEquals(WallpaperBand.BOTTOM, wallpaperBandOrDefault(null))
        assertEquals(WallpaperBand.BOTTOM, wallpaperBandOrDefault("sideways"))
    }
}

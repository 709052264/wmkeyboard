package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Themes taking the colours of the app being typed into (issue #592): which of
 * an app's colours count, and the palettes grown from one.
 */
class AppPaletteTest {

    private val whatsappGreen = 0xFF25D366L

    @Test
    fun `a palette tone has the lightness it was asked for`() {
        for (tone in listOf(10, 40, 80, 95)) {
            val argb = TonalPalette(140f, 36f).tone(tone)
            assertEquals("tone $tone", tone.toFloat(), Lab.of(argb).l, 1f)
        }
    }

    @Test
    fun `a chroma the screen cannot show is lowered, not clipped`() {
        // Clipping channels moves the lightness; lowering the chroma does not.
        val argb = TonalPalette(140f, 200f).tone(90)
        assertEquals(90f, Lab.of(argb).l, 1f)
        assertTrue(Lab.of(argb).chroma > 10f)
    }

    @Test
    fun `every style gives opaque tones running from white to black`() {
        for (style in PaletteStyle.entries) {
            val tones = appPalette(whatsappGreen, style).tones
            for (ramp in listOf(tones.primary, tones.secondary, tones.tertiary, tones.neutral, tones.neutralVariant)) {
                assertEquals(WallpaperTones.TONES.size, ramp.size)
                assertTrue(ramp.all { it ushr 24 == 0xFFL })
                val lightness = ramp.map { Lab.of(it).l }
                assertTrue("$style not descending: $lightness", lightness.zipWithNext().all { (a, b) -> a >= b - 0.5f })
            }
        }
    }

    @Test
    fun `tonal spot keeps the app hue in its primary`() {
        val primary = appPalette(whatsappGreen, PaletteStyle.TONAL_SPOT).roles.resolve(false, "primary")!!
        assertTrue(hueDistance(Lab.of(primary).hue, Lab.of(whatsappGreen).hue) < 10f)
    }

    @Test
    fun `monochrome has no colour in it`() {
        val tones = appPalette(whatsappGreen, PaletteStyle.MONOCHROME).tones
        assertTrue(tones.primary.all { Lab.of(it).chroma < 2f })
    }

    @Test
    fun `styles differ from each other`() {
        val swatches = PaletteStyle.entries.map { paletteStyleSwatch(whatsappGreen, it) }
        assertEquals(swatches.size, swatches.toSet().size)
    }

    @Test
    fun `greys, template purple and near duplicates are not candidates`() {
        val sample = AppColorSample(
            packageName = "app",
            themeColors = listOf(0xFF6200EEL, 0xFF888888L, 0xFF075E54L),
            iconColors = listOf(0xFFFFFFFFL, 0xFF076057L, whatsappGreen),
        )
        // The template purple and the grey go, the white goes, and the icon's
        // dark teal is the theme's dark teal again.
        assertEquals(listOf(0xFF075E54L, whatsappGreen), sample.candidates(AppColorSource.AUTO))
    }

    @Test
    fun `the source decides which list is read and in what order`() {
        val sample = AppColorSample("app", themeColors = listOf(0xFF1565C0L), iconColors = listOf(0xFFE53935L))
        assertEquals(listOf(0xFF1565C0L, 0xFFE53935L), sample.candidates(AppColorSource.AUTO))
        assertEquals(listOf(0xFFE53935L), sample.candidates(AppColorSource.ICON))
        assertEquals(listOf(0xFF1565C0L), sample.candidates(AppColorSource.APP_THEME))
    }

    @Test
    fun `recolouring keeps a dark theme dark and a light one light`() {
        val palette = appPalette(whatsappGreen, PaletteStyle.VIBRANT)
        val dark = ThemeSpec(id = "d", name = "D").recoloredBy(palette)
        val light = themeFromSeed("l", "L", 0xFF3F51B5L, dark = false).recoloredBy(palette)
        assertTrue(Lab.of(dark.boardBackground).l < 20f)
        assertTrue(Lab.of(light.boardBackground).l > 80f)
        // And the colour moved: the accent is the app's green now.
        assertTrue(hueDistance(Lab.of(dark.accent).hue, Lab.of(whatsappGreen).hue) < 25f)
    }

    @Test
    fun `recolouring keeps alpha`() {
        val spec = ThemeSpec(id = "t", name = "T", keyBackground = 0x80303338L)
        val moved = spec.recoloredBy(appPalette(whatsappGreen, PaletteStyle.TONAL_SPOT))
        assertEquals(0x80L, moved.keyBackground ushr 24)
        assertNotEquals(spec.keyBackground, moved.keyBackground)
    }

    @Test
    fun `light roles read dark on light`() {
        val roles = appPalette(whatsappGreen, PaletteStyle.TONAL_SPOT).roles
        val surface = Lab.of(roles.resolve(false, "surface")!!).l
        val onSurface = Lab.of(roles.resolve(false, "onSurface")!!).l
        assertTrue(abs(surface - onSurface) > 70f)
        assertTrue(Lab.of(roles.resolve(true, "surface")!!).l < 10f)
    }
}

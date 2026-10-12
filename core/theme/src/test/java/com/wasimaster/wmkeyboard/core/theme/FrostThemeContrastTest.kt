package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The Frost family's labels stay readable in the worst case: the glass tints
 * composited over a white app and over a black one, both with the window
 * blurred (the board at its own alpha) and without it (the board at its
 * fallback opacity). Glass is see-through by design, so this is the one
 * place its colours are held to the usual 4.5:1.
 */
class FrostThemeContrastTest {

    private val frost = BuiltInThemes.first { it.id == "builtin_frost" }

    private fun channel(c: Long, shift: Int) = ((c ushr shift) and 0xFF) / 255.0

    private fun alpha(c: Long) = channel(c, 24)

    /** [top] composited over an opaque [base]; both as RGB doubles. */
    private fun over(top: Long, base: DoubleArray): DoubleArray {
        val a = alpha(top)
        return DoubleArray(3) { i -> channel(top, 16 - 8 * i) * a + base[i] * (1 - a) }
    }

    private fun luminance(rgb: DoubleArray): Double {
        fun lin(v: Double) = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2])
    }

    private fun contrast(a: DoubleArray, b: DoubleArray): Double {
        val la = luminance(a) + 0.05
        val lb = luminance(b) + 0.05
        return if (la > lb) la / lb else lb / la
    }

    private fun Long.withAlphaAtLeast(fraction: Float): Long =
        if (alpha(this) >= fraction) this else withAlphaFraction(fraction)

    private fun check(theme: ThemeSpec, app: DoubleArray, boardAlphaFloor: Float?, what: String) {
        val board = over(boardAlphaFloor?.let { theme.boardBackground.withAlphaAtLeast(it) } ?: theme.boardBackground, app)
        val text = over(theme.keyText, board)
        val pairs = listOf(
            "key" to over(theme.keyBackground, board),
            "modifier" to over(theme.modifierKeyBackground, board),
            "chip" to over(theme.chipBackground ?: theme.modifierKeyBackground, board),
            "popup" to over(theme.popupBackground ?: theme.keyBackground, board),
        )
        for ((name, fill) in pairs) {
            val ratio = contrast(text, fill)
            assertTrue("${theme.id} $name over $what: %.2f:1".format(ratio), ratio >= 4.5)
        }
    }

    @Test
    fun `every Frost look reads over a white app and a black app`() {
        val white = doubleArrayOf(1.0, 1.0, 1.0)
        val black = doubleArrayOf(0.0, 0.0, 0.0)
        for (theme in frost.selfAndVariants()) {
            val glass = requireNotNull(theme.frost) { "${theme.id} has no glass" }
            if (glass.backdropSource == FrostBackdropSource.WALLPAPER) {
                // Frost wallpaper sits on the wallpaper; a mid grey stands in for it.
                val grey = doubleArrayOf(0.5, 0.5, 0.5)
                check(theme, grey, null, "its wallpaper")
                continue
            }
            check(theme, white, null, "white, blurred")
            check(theme, black, null, "black, blurred")
            check(theme, white, glass.fallbackOpacity, "white, no blur")
            check(theme, black, glass.fallbackOpacity, "black, no blur")
        }
    }

    @Test
    fun `the family is what the gallery promises`() {
        val looks = frost.selfAndVariants().associateBy { it.id }
        assertTrue(looks.keys.containsAll(listOf("builtin_frost", "builtin_frost_wallpaper", "builtin_frost_light")))
        assertTrue(looks.getValue("builtin_frost").frost?.windowBlur == true)
        assertTrue(looks.getValue("builtin_frost_wallpaper").frost?.windowBlur == false)
        assertTrue(looks.getValue("builtin_frost_wallpaper").frost?.backdropSource == FrostBackdropSource.WALLPAPER)
        assertTrue(!looks.getValue("builtin_frost_light").dark)
        assertTrue(looks.getValue("builtin_frost_light").frost?.windowBlur == true)
    }
}

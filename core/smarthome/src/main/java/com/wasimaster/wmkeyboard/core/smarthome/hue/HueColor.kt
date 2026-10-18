package com.wasimaster.wmkeyboard.core.smarthome.hue

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * CIE xy, as a Hue bridge speaks colour, to and from the hue and saturation
 * the panel's colour controls use. The sRGB conversions are the ones
 * Signify documents for its bridges; gamut clamping is left to the bridge,
 * which clamps to the lamp's own gamut and reports the result.
 */
@Suppress("detekt.MagicNumber")
object HueColor {

    /** Hue 0..360 and saturation 0..100 for an xy point at full brightness. */
    fun xyToHs(x: Double, y: Double): Pair<Double, Double> {
        val (r, g, b) = xyToRgb(x, y)
        return rgbToHs(r, g, b)
    }

    /** The xy point of a hue 0..360 and saturation 0..100. */
    fun hsToXy(hue: Double, saturation: Double): Pair<Double, Double> {
        val (r, g, b) = hsToRgb(hue, saturation)
        return rgbToXy(r, g, b)
    }

    fun xyToRgb(x: Double, y: Double): Triple<Double, Double, Double> {
        if (y <= 0.0) return Triple(0.0, 0.0, 0.0)
        val z = 1.0 - x - y
        val yy = 1.0
        val xx = (yy / y) * x
        val zz = (yy / y) * z
        var r = xx * 1.656492 - yy * 0.354851 - zz * 0.255038
        var g = -xx * 0.707196 + yy * 1.655397 + zz * 0.036152
        var b = xx * 0.051713 - yy * 0.121364 + zz * 1.011530
        val top = max(r, max(g, b))
        if (top > 1.0) {
            r /= top
            g /= top
            b /= top
        }
        return Triple(gamma(r), gamma(g), gamma(b))
    }

    fun rgbToXy(r: Double, g: Double, b: Double): Pair<Double, Double> {
        val rr = linear(r)
        val gg = linear(g)
        val bb = linear(b)
        val x = rr * 0.664511 + gg * 0.154324 + bb * 0.162028
        val y = rr * 0.283881 + gg * 0.668433 + bb * 0.047685
        val z = rr * 0.000088 + gg * 0.072310 + bb * 0.986039
        val sum = x + y + z
        if (sum <= 0.0) return 0.3127 to 0.3290
        return (x / sum) to (y / sum)
    }

    fun rgbToHs(r: Double, g: Double, b: Double): Pair<Double, Double> {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val delta = mx - mn
        val s = if (mx <= 0.0) 0.0 else delta / mx
        var h = when {
            delta < 1e-9 -> 0.0
            mx == r -> 60.0 * (((g - b) / delta) % 6.0)
            mx == g -> 60.0 * (((b - r) / delta) + 2.0)
            else -> 60.0 * (((r - g) / delta) + 4.0)
        }
        if (h < 0) h += 360.0
        return h to s * 100.0
    }

    fun hsToRgb(hue: Double, saturation: Double): Triple<Double, Double, Double> {
        val h = ((hue % 360.0) + 360.0) % 360.0
        val s = (saturation / 100.0).coerceIn(0.0, 1.0)
        val c = s
        val x = c * (1 - abs((h / 60.0) % 2 - 1))
        val m = 1.0 - c
        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return Triple(r1 + m, g1 + m, b1 + m)
    }

    private fun gamma(v: Double): Double {
        val c = v.coerceIn(0.0, 1.0)
        return if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055
    }

    private fun linear(v: Double): Double {
        val c = v.coerceIn(0.0, 1.0)
        return if (c > 0.04045) ((c + 0.055) / 1.055).pow(2.4) else c / 12.92
    }

    /** Mireds to kelvin and back; Hue's scale is 153 (6500 K) to 500 (2000 K). */
    fun mirekToKelvin(mirek: Int): Int = (1_000_000.0 / mirek.coerceAtLeast(1)).toInt()

    fun kelvinToMirek(kelvin: Int): Int = (1_000_000.0 / kelvin.coerceAtLeast(1)).toInt().coerceIn(153, 500)
}

package com.wasimaster.wmkeyboard.core.location

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Map tiles behind the Location tool's dial (#350), only when the user names
 * a tile server: none is built in.
 *
 * Every tile request tells the server roughly where the phone is, and the
 * public OpenStreetMap servers do not allow apps that ship with them as the
 * default. So the dial is drawn without a map unless the user supplies a
 * `{z}/{x}/{y}` address of a server whose terms suit them, their own or a
 * provider's.
 *
 * Web Mercator ("slippy map") tile numbering, 256 pixel tiles.
 */
object MapTiles {

    /** Earth's equatorial circumference in metres, the width of zoom 0's one tile. */
    private const val EQUATOR_M = 40_075_016.686

    /** Whether [template] is something this can fetch from. */
    fun isUsable(template: String): Boolean {
        val t = template.trim()
        return (t.startsWith("https://") || t.startsWith("http://")) &&
            "{z}" in t && "{x}" in t && "{y}" in t && t.none { it.isWhitespace() }
    }

    fun url(template: String, z: Int, x: Int, y: Int): String =
        template.trim().replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())

    /** Fractional tile coordinates of [point] at [zoom]. */
    fun tileOf(point: GeoPoint, zoom: Int): Pair<Double, Double> {
        val n = (1 shl zoom).toDouble()
        val lat = Math.toRadians(point.latitude.coerceIn(-85.0511, 85.0511))
        val x = (point.longitude + 180.0) / 360.0 * n
        val y = (1.0 - ln(tan(lat) + 1.0 / cos(lat)) / PI) / 2.0 * n
        return x to y
    }

    /** Metres one tile spans at [zoom] and [latitude]. */
    fun tileMetres(zoom: Int, latitude: Double): Double =
        EQUATOR_M * cos(Math.toRadians(latitude)) / (1 shl zoom)

    /** The zoom whose tiles draw closest to [tilePx] pixels across at [pxPerMetre]. */
    fun zoomFor(pxPerMetre: Double, latitude: Double, tilePx: Double = 256.0): Int {
        val ideal = log2(EQUATOR_M * cos(Math.toRadians(latitude)) * pxPerMetre / tilePx)
        return ideal.roundToInt().coerceIn(1, 19)
    }

    /** The tile numbers around [point], a [radius]-tile square, wrapped east and west. */
    fun around(point: GeoPoint, zoom: Int, radius: Int = 1): List<Triple<Int, Int, Int>> {
        val (fx, fy) = tileOf(point, zoom)
        val n = 1 shl zoom
        val cx = floor(fx).toInt()
        val cy = floor(fy).toInt()
        return buildList {
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val y = cy + dy
                if (y !in 0 until n) continue
                add(Triple(zoom, ((cx + dx) % n + n) % n, y))
            }
        }
    }

    /** Blocking; call on an IO dispatcher. The raw bytes of one tile, or null. */
    fun fetch(template: String, z: Int, x: Int, y: Int): ByteArray? {
        val url = url(template, z, x, y)
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        val netCall = NetLog.call(NetSource.LOCATION, "GET", url, route = "/{z}/{x}/{y}")
        return try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "WMKeyboard/${BuildConfig.VERSION_NAME} (+https://github.com/wasi-master/wmkeyboard)")
            netCall.status = connection.responseCode
            if (connection.responseCode !in 200..299) null else netCall.countIn(connection.inputStream).use { it.readBytes() }
        } catch (t: Throwable) {
            netCall.fail(t)
            null
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }
}

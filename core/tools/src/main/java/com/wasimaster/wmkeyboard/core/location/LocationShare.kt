package com.wasimaster.wmkeyboard.core.location

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.tools.R
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * How much of a position a share gives away (#350).
 *
 * Anything coarser than [EXACT] snaps the point to the centre of a fixed grid
 * cell instead of rounding or blurring it. The grid is the same on every
 * share, so ten shares from one sofa all land on the same point and cannot be
 * averaged back to the sofa, which random noise would allow.
 */
enum class LocationPrecision(
    /** Stable on disk. Never store [name]. */
    val id: String,
    /** Side of a grid cell; 0 is no snapping. */
    val cellMetres: Int,
    /** Decimal places a coordinate is written with. */
    val decimals: Int,
    /** Digits of the Plus code. */
    val plusCodeLength: Int,
    /** Nominatim's `zoom`: 18 a building, 17 a street, 14 a neighbourhood, 10 a city. */
    val addressZoom: Int,
    /** The zoom a map link opens at. */
    val mapZoom: Int,
    @StringRes val labelRes: Int,
    @StringRes val shortLabelRes: Int,
) {
    EXACT("exact", 0, 6, 10, 18, 18, R.string.core_tools_location_precision_exact, R.string.core_tools_location_precision_exact_short),
    STREET("street", 100, 4, 10, 17, 17, R.string.core_tools_location_precision_street, R.string.core_tools_location_precision_street_short),
    AREA("area", 1_000, 3, 8, 14, 15, R.string.core_tools_location_precision_area, R.string.core_tools_location_precision_area_short),
    CITY("city", 10_000, 2, 6, 10, 12, R.string.core_tools_location_precision_city, R.string.core_tools_location_precision_city_short),
    ;

    /** [point] as this precision shares it. */
    fun apply(point: GeoPoint): GeoPoint {
        if (cellMetres == 0) return point
        val latStep = cellMetres / METRES_PER_DEGREE
        val latCell = floor((point.latitude + 90.0) / latStep)
        val lat = ((latCell + 0.5) * latStep - 90.0).coerceIn(-90.0, 90.0)
        // Columns are cut by the snapped row's latitude, not the real one, so
        // every point in a row agrees on the grid. Near the poles a degree of
        // longitude is nothing; the floor on the cosine keeps cells finite.
        val lonStep = latStep / cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val lonCell = floor((point.longitude + 180.0) / lonStep)
        var lon = (lonCell + 0.5) * lonStep - 180.0
        if (lon >= 180.0) lon -= 360.0
        return GeoPoint(lat, lon)
    }

    fun next(): LocationPrecision = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val METRES_PER_DEGREE = 111_320.0

        fun of(id: String?): LocationPrecision? = entries.firstOrNull { it.id == id }
    }
}

/** Which map a shared link opens. */
enum class MapLink(
    /** Stable on disk. */
    val id: String,
    /** A product name, so not translated; [GEO] reads in words. */
    val label: String,
) {
    GOOGLE("google", "Google Maps"),
    OSM("osm", "OpenStreetMap"),
    APPLE("apple", "Apple Maps"),
    OSMAND("osmand", "OsmAnd"),
    WAZE("waze", "Waze"),

    /** `geo:` (RFC 5870): opens whichever maps app the reader's phone uses, but is not a web link. */
    GEO("geo", "geo:"),
    ;

    companion object {
        fun of(id: String?): MapLink? = entries.firstOrNull { it.id == id }
    }
}

/** What one tap on Insert puts in the field. */
enum class LocationFormat(
    /** Stable on disk. */
    val id: String,
    @StringRes val labelRes: Int,
) {
    /** "📍 Road 11, Banani, Dhaka" and a map link under it: what a chat app's own share looks like. */
    MESSAGE("message", R.string.core_tools_location_format_message),
    LINK("link", R.string.core_tools_location_format_link),
    COORDINATES("coordinates", R.string.core_tools_location_format_coordinates),
    DMS("dms", R.string.core_tools_location_format_dms),
    ADDRESS("address", R.string.core_tools_location_format_address),
    PLUS_CODE("plus_code", R.string.core_tools_location_format_plus_code),

    /** The user's own template, see [LocationFormatter.expand]. */
    CUSTOM("custom", R.string.core_tools_location_format_custom),
    ;

    companion object {
        fun of(id: String?): LocationFormat? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Everything a share can say about one place. [point] is the real position;
 * the formatter applies [precision] itself, so no caller can forget to.
 */
data class LocationShare(
    val point: GeoPoint,
    val precision: LocationPrecision = LocationPrecision.EXACT,
    /** A short address for this precision, already looked up; null when there is none. */
    val address: String? = null,
    /** A saved place's name, "Home". */
    val placeName: String? = null,
    val accuracyM: Float? = null,
    /** The time of the reading, already formatted for the reader's locale. */
    val timeText: String = "",
)

/**
 * Turns a [LocationShare] into text (#350). Pure, so every format is pinned by
 * a unit test and the panel's preview is exactly what Insert types.
 *
 * Numbers always use a dot and Latin digits: a coordinate written with a
 * decimal comma is two numbers to a map app.
 */
object LocationFormatter {

    /** The tokens a custom template understands, in the order the settings screen lists them. */
    val TOKENS: List<String> = listOf(
        "{name}", "{address}", "{coords}", "{lat}", "{lon}", "{dms}", "{plus}", "{link}",
        "{google}", "{osm}", "{apple}", "{osmand}", "{waze}", "{geo}", "{accuracy}", "{time}",
    )

    const val DEFAULT_TEMPLATE = "📍 {name} {address}\n{coords} · {link}"

    fun format(
        share: LocationShare,
        format: LocationFormat,
        mapLink: MapLink,
        template: String = DEFAULT_TEMPLATE,
    ): String {
        val point = share.precision.apply(share.point)
        val coords = coordinates(point, share.precision)
        val link = link(mapLink, point, share.precision)
        val title = title(share) ?: coords
        return when (format) {
            LocationFormat.MESSAGE -> "📍 $title\n$link"
            LocationFormat.LINK -> link
            LocationFormat.COORDINATES -> coords
            LocationFormat.DMS -> dms(point, share.precision)
            LocationFormat.ADDRESS -> title
            LocationFormat.PLUS_CODE -> PlusCode.encode(point, share.precision.plusCodeLength)
            LocationFormat.CUSTOM -> expand(template.ifBlank { DEFAULT_TEMPLATE }, share, mapLink)
        }
    }

    /** The place's name and address, without repeating one inside the other. */
    private fun title(share: LocationShare): String? {
        val name = share.placeName?.trim()?.takeIf { it.isNotEmpty() }
        val address = share.address?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            name == null -> address
            address == null || address.contains(name, ignoreCase = true) -> name
            else -> "$name, $address"
        }
    }

    /**
     * Fills `{token}`s. An unknown token stays as typed, so a brace the user
     * meant literally survives; a known one with nothing to say becomes empty
     * and the doubled spaces it leaves are folded.
     */
    fun expand(template: String, share: LocationShare, mapLink: MapLink): String {
        val point = share.precision.apply(share.point)
        val precision = share.precision
        val values = mapOf(
            "name" to share.placeName.orEmpty(),
            "address" to share.address.orEmpty(),
            "coords" to coordinates(point, precision),
            "lat" to number(point.latitude, precision.decimals),
            "lon" to number(point.longitude, precision.decimals),
            "dms" to dms(point, precision),
            "plus" to PlusCode.encode(point, precision.plusCodeLength),
            "link" to link(mapLink, point, precision),
            "google" to link(MapLink.GOOGLE, point, precision),
            "osm" to link(MapLink.OSM, point, precision),
            "apple" to link(MapLink.APPLE, point, precision),
            "osmand" to link(MapLink.OSMAND, point, precision),
            "waze" to link(MapLink.WAZE, point, precision),
            "geo" to link(MapLink.GEO, point, precision),
            "accuracy" to accuracy(share.accuracyM, precision),
            "time" to share.timeText,
        )
        val filled = TOKEN.replace(template) { match -> values[match.groupValues[1]] ?: match.value }
        return filled.lines().joinToString("\n") { line -> line.replace(SPACES, " ").trim() }
            .trim()
    }

    fun coordinates(point: GeoPoint, precision: LocationPrecision = LocationPrecision.EXACT): String =
        number(point.latitude, precision.decimals) + ", " + number(point.longitude, precision.decimals)

    /** `23°46'50.2"N 90°25'09.5"E`, the way Google Maps writes it. */
    fun dms(point: GeoPoint, precision: LocationPrecision = LocationPrecision.EXACT): String {
        val tenths = precision == LocationPrecision.EXACT
        return dmsPart(point.latitude, if (point.latitude >= 0) 'N' else 'S', tenths) + " " +
            dmsPart(point.longitude, if (point.longitude >= 0) 'E' else 'W', tenths)
    }

    private fun dmsPart(value: Double, hemisphere: Char, tenths: Boolean): String {
        // Work in whole tenths of a second so 59.96" rounds up into the next minute, not to 60.0".
        val unit = if (tenths) 36_000L else 3_600L
        val total = Math.round(abs(value) * unit)
        val perMinute = unit / 60
        val degrees = total / unit
        val minutes = (total % unit) / perMinute
        val seconds = total % perMinute
        val secondsText = if (tenths) {
            String.format(Locale.US, "%02d.%d", seconds / 10, seconds % 10)
        } else {
            String.format(Locale.US, "%02d", seconds)
        }
        return String.format(Locale.US, "%d°%02d'%s\"%c", degrees, minutes, secondsText, hemisphere)
    }

    fun link(mapLink: MapLink, point: GeoPoint, precision: LocationPrecision = LocationPrecision.EXACT): String {
        val lat = number(point.latitude, precision.decimals)
        val lon = number(point.longitude, precision.decimals)
        val zoom = precision.mapZoom
        return when (mapLink) {
            // The documented Maps URLs form: works in the app, the browser,
            // and without a Google account.
            MapLink.GOOGLE -> "https://www.google.com/maps/search/?api=1&query=$lat%2C$lon"
            MapLink.OSM -> "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=$zoom/$lat/$lon"
            MapLink.APPLE -> "https://maps.apple.com/?ll=$lat,$lon&q=" + URLEncoder.encode("$lat,$lon", "UTF-8")
            MapLink.OSMAND -> "https://osmand.net/map?pin=$lat,$lon#$zoom/$lat/$lon"
            MapLink.WAZE -> "https://waze.com/ul?ll=$lat%2C$lon"
            MapLink.GEO -> "geo:$lat,$lon?q=$lat,$lon"
        }
    }

    /**
     * "±12 m". Past [LocationPrecision.EXACT] the cell is the honest error
     * bar, not the reading's, so that is what is said.
     */
    fun accuracy(accuracyM: Float?, precision: LocationPrecision): String {
        val metres = maxOf(accuracyM?.roundToInt() ?: 0, precision.cellMetres / 2)
        if (metres <= 0) return ""
        return if (metres >= 1000) {
            "±" + String.format(Locale.US, "%.1f", metres / 1000.0).removeSuffix(".0") + " km"
        } else {
            "±$metres m"
        }
    }

    /** A distance for a list row: "350 m", "1.2 km", "84 km". */
    fun distance(metres: Double): String = when {
        metres < 1000 -> "${(metres / 10).roundToInt() * 10} m"
        metres < 10_000 -> String.format(Locale.US, "%.1f km", metres / 1000)
        else -> "${(metres / 1000).roundToInt()} km"
    }

    private fun number(value: Double, decimals: Int): String {
        val text = String.format(Locale.US, "%.${decimals}f", value)
        // A value that rounds to zero prints as "-0.0000" from a tiny negative; a map reads that fine, people trip on it.
        return if (text.startsWith('-') && text.drop(1).all { it == '0' || it == '.' }) text.drop(1) else text
    }

    private val TOKEN = Regex("""\{([a-z]+)\}""")
    private val SPACES = Regex("""[ \t]{2,}""")
}

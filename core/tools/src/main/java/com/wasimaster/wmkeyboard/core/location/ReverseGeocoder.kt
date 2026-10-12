package com.wasimaster.wmkeyboard.core.location

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A short address for a point (#350): "Road 11, Banani, Dhaka", not the
 * twelve-part line a geocoder answers with.
 *
 * Nominatim (OpenStreetMap's own geocoder) by default, and any server that
 * answers its `/reverse` API, Nominatim self-hosted or Photon's compatible
 * mode, through [ServiceEndpoint.NOMINATIM]. Its usage policy asks for one
 * request a second at most, an identifying User-Agent, and caching; this does
 * all three. One request per share is the whole load.
 *
 * Only the point at the share's precision is ever sent, and at the matching
 * zoom: a city-level share asks for a city, so the server never learns more
 * than the person receiving the message will.
 */
object ReverseGeocoder {

    private val json = Json { ignoreUnknownKeys = true }

    private const val CACHE_SIZE = 32
    private const val MIN_INTERVAL_MS = 1_100L

    private val cache = object : LinkedHashMap<String, String>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_SIZE
    }
    private val lock = Any()
    private var lastCallAt = 0L

    /** The answer for this exact request if it was asked before; no network. */
    fun cached(point: GeoPoint, precision: LocationPrecision, language: String): String? =
        synchronized(lock) { cache[key(point, precision, language)] }

    /**
     * Blocking; call on an IO dispatcher. Null when the server knows no
     * address there (the sea), throws on a network failure. [point] is sent as
     * given, so pass it through [LocationPrecision.apply] first.
     */
    fun lookup(point: GeoPoint, precision: LocationPrecision, language: String): String? {
        val key = key(point, precision, language)
        synchronized(lock) { cache[key]?.let { return it } }
        throttle()
        val url = ServiceEndpoints.base(ServiceEndpoint.NOMINATIM) + String.format(
            Locale.US,
            "/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=%d&addressdetails=1",
            point.latitude,
            point.longitude,
            precision.addressZoom,
        ) + "&accept-language=" + URLEncoder.encode(language.ifBlank { "en" }, "UTF-8")
        val address = parse(get(url), precision) ?: return null
        synchronized(lock) { cache[key] = address }
        return address
    }

    private fun throttle() {
        val wait = synchronized(lock) {
            val now = System.currentTimeMillis()
            val next = maxOf(now, lastCallAt + MIN_INTERVAL_MS)
            lastCallAt = next
            next - now
        }
        if (wait > 0) Thread.sleep(wait)
    }

    private fun key(point: GeoPoint, precision: LocationPrecision, language: String) =
        String.format(Locale.US, "%.5f,%.5f,%s,%s", point.latitude, point.longitude, precision.id, language)

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        // The path only: the query is the user's position.
        val netCall = NetLog.call(NetSource.LOCATION, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            // Nominatim blocks the generic Java agent and any browser's; it
            // asks each application to name itself.
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            netCall.status = connection.responseCode
            return netCall.countIn(connection.inputStream).bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    private val USER_AGENT = "WMKeyboard/${BuildConfig.VERSION_NAME} (+https://github.com/wasi-master/wmkeyboard)"

    /** The short form of one `/reverse` answer, or null when it found nothing. */
    internal fun parse(body: String, precision: LocationPrecision): String? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root["error"] != null) return null
        val address = root["address"]?.takeIf { it !is JsonNull }?.jsonObject
        val parts = address?.let { shortParts(it, root.text("name"), precision) }.orEmpty()
        if (parts.isNotEmpty()) return parts.joinToString(", ")
        // No structured address: the first few parts of the long line.
        return root.text("display_name")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.distinct()?.take(3)?.joinToString(", ")?.takeIf { it.isNotEmpty() }
    }

    private fun shortParts(address: JsonObject, name: String?, precision: LocationPrecision): List<String> {
        val road = address.text("road") ?: address.text("pedestrian") ?: address.text("footway") ?: address.text("path")
        val house = address.text("house_number")
        val area = address.text("neighbourhood") ?: address.text("suburb") ?: address.text("quarter")
            ?: address.text("hamlet") ?: address.text("city_district")
        val city = address.text("city") ?: address.text("town") ?: address.text("village")
            ?: address.text("municipality") ?: address.text("county")
        val region = address.text("state") ?: address.text("region")
        val country = address.text("country")
        val parts = when (precision) {
            LocationPrecision.EXACT -> listOf(
                // A named place (a shop, a station) reads better than its street.
                name?.takeIf { it != road },
                if (house != null && road != null) "$house $road" else road,
                area,
                city,
            )
            LocationPrecision.STREET -> listOf(road, area, city)
            LocationPrecision.AREA -> listOf(area, city)
            LocationPrecision.CITY -> listOf(city ?: region, country)
        }
        return parts.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
}

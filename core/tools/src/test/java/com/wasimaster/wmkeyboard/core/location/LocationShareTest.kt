package com.wasimaster.wmkeyboard.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every format the Location tool can type, pinned (#350). */
class LocationShareTest {

    private val dhaka = GeoPoint(23.7806, 90.4193)

    @Test
    fun `coordinates use a dot and the precision's decimals`() {
        assertEquals("23.780600, 90.419300", LocationFormatter.coordinates(dhaka))
        assertEquals("23.78, 90.42", LocationFormatter.coordinates(dhaka, LocationPrecision.CITY))
        assertEquals("0.000000, -0.500000", LocationFormatter.coordinates(GeoPoint(-0.0000001, -0.5)))
    }

    @Test
    fun `degrees minutes seconds read like Google Maps`() {
        assertEquals("23°46'50.2\"N 90°25'09.5\"E", LocationFormatter.dms(dhaka))
        assertEquals("33°51'24.4\"S 151°12'55.1\"E", LocationFormatter.dms(GeoPoint(-33.856784, 151.215297)))
        // 59.96 seconds rounds into the next minute, never to 60.0.
        assertEquals("10°01'00.0\"N 0°00'00.0\"E", LocationFormatter.dms(GeoPoint(10.0 + 59.96 / 3600, 0.0)))
    }

    @Test
    fun `each map link`() {
        val p = LocationPrecision.EXACT
        assertEquals("https://www.google.com/maps/search/?api=1&query=23.780600%2C90.419300", LocationFormatter.link(MapLink.GOOGLE, dhaka, p))
        assertEquals(
            "https://www.openstreetmap.org/?mlat=23.780600&mlon=90.419300#map=18/23.780600/90.419300",
            LocationFormatter.link(MapLink.OSM, dhaka, p),
        )
        assertEquals("https://maps.apple.com/?ll=23.780600,90.419300&q=23.780600%2C90.419300", LocationFormatter.link(MapLink.APPLE, dhaka, p))
        assertEquals("https://osmand.net/map?pin=23.780600,90.419300#18/23.780600/90.419300", LocationFormatter.link(MapLink.OSMAND, dhaka, p))
        assertEquals("https://waze.com/ul?ll=23.780600%2C90.419300", LocationFormatter.link(MapLink.WAZE, dhaka, p))
        assertEquals("geo:23.780600,90.419300?q=23.780600,90.419300", LocationFormatter.link(MapLink.GEO, dhaka, p))
        assertEquals("https://wmkeyboard.pages.dev/place/#23.780600,90.419300", LocationFormatter.link(MapLink.CARD, dhaka, p))
        assertEquals(
            "https://wmkeyboard.pages.dev/place/#23.780600,90.419300;n=North%20End%20Coffee%2C%20Banani%3B%201",
            LocationFormatter.link(MapLink.CARD, dhaka, p, label = "North End Coffee, Banani; 1"),
        )
    }

    @Test
    fun `message is the address and the link, falling back to coordinates`() {
        val share = LocationShare(dhaka, address = "Road 11, Banani, Dhaka")
        assertEquals(
            "📍 Road 11, Banani, Dhaka\nhttps://www.google.com/maps/search/?api=1&query=23.780600%2C90.419300",
            LocationFormatter.format(share, LocationFormat.MESSAGE, MapLink.GOOGLE),
        )
        assertEquals(
            "📍 23.780600, 90.419300\ngeo:23.780600,90.419300?q=23.780600,90.419300",
            LocationFormatter.format(LocationShare(dhaka), LocationFormat.MESSAGE, MapLink.GEO),
        )
    }

    @Test
    fun `a place name leads, and is not repeated when the address holds it`() {
        val home = LocationShare(dhaka, address = "Road 11, Banani", placeName = "Home")
        assertEquals("Home, Road 11, Banani", LocationFormatter.format(home, LocationFormat.ADDRESS, MapLink.GOOGLE))
        val station = LocationShare(dhaka, address = "Kamalapur Station, Dhaka", placeName = "Kamalapur Station")
        assertEquals("Kamalapur Station", LocationFormatter.format(station, LocationFormat.ADDRESS, MapLink.GOOGLE))
    }

    @Test
    fun `plus code follows the precision`() {
        assertEquals("7MMGQCJ9+6P", LocationFormatter.format(LocationShare(dhaka), LocationFormat.PLUS_CODE, MapLink.GOOGLE))
        val city = LocationFormatter.format(LocationShare(dhaka, LocationPrecision.CITY), LocationFormat.PLUS_CODE, MapLink.GOOGLE)
        assertTrue(city, city.endsWith("00+"))
    }

    @Test
    fun `custom templates fill known tokens and keep unknown ones`() {
        val share = LocationShare(dhaka, address = "Banani", accuracyM = 12f, timeText = "9:41")
        assertEquals(
            "I am at Banani (23.780600, 90.419300) ±12 m at 9:41 {unknown}",
            LocationFormatter.expand("I am at {address} ({coords}) {accuracy} at {time} {unknown}", share, MapLink.GOOGLE),
        )
        // An empty token leaves no double space behind.
        assertEquals(
            "📍 Banani\n23.780600, 90.419300 · geo:23.780600,90.419300?q=23.780600,90.419300",
            LocationFormatter.expand(LocationFormatter.DEFAULT_TEMPLATE, share, MapLink.GEO),
        )
    }

    @Test
    fun `accuracy reports the cell past exact`() {
        assertEquals("±12 m", LocationFormatter.accuracy(12f, LocationPrecision.EXACT))
        assertEquals("±50 m", LocationFormatter.accuracy(12f, LocationPrecision.STREET))
        assertEquals("±5 km", LocationFormatter.accuracy(12f, LocationPrecision.CITY))
        assertEquals("", LocationFormatter.accuracy(null, LocationPrecision.EXACT))
    }

    @Test
    fun `distances read at the right scale`() {
        assertEquals("350 m", LocationFormatter.distance(347.0))
        assertEquals("1.2 km", LocationFormatter.distance(1_234.0))
        assertEquals("84 km", LocationFormatter.distance(84_400.0))
    }
}

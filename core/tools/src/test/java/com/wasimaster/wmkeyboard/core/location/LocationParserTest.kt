package com.wasimaster.wmkeyboard.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading the places people send (#350). */
class LocationParserTest {

    private val dhaka = GeoPoint(23.7806, 90.4193)

    private fun assertNear(expected: GeoPoint, actual: GeoPoint?, metres: Double = 1.0) {
        assertTrue("$actual", actual != null && expected.distanceTo(actual) <= metres)
    }

    @Test
    fun `everything the tool writes reads back`() {
        for (precision in listOf(LocationPrecision.EXACT, LocationPrecision.STREET)) {
            val shared = precision.apply(dhaka)
            for (link in MapLink.entries) {
                val text = "Meet me here " + LocationFormatter.link(link, dhaka, precision) + " ok?"
                val found = LocationParser.first(text)
                assertNear(shared, found?.point, metres = 15.0)
            }
            for (format in listOf(LocationFormat.MESSAGE, LocationFormat.COORDINATES, LocationFormat.DMS, LocationFormat.PLUS_CODE)) {
                val text = LocationFormatter.format(LocationShare(dhaka, precision), format, MapLink.GOOGLE)
                assertNear(shared, LocationParser.first(text)?.point, metres = 15.0)
            }
        }
    }

    @Test
    fun `a Google place link prefers its pin and keeps its name`() {
        val link = "https://www.google.com/maps/place/Banani+Lake/@23.7937,90.4066,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d23.7941!4d90.4071"
        val found = LocationParser.first(link)!!
        assertNear(GeoPoint(23.7941, 90.4071), found.point)
        assertEquals("Banani Lake", found.label)
        assertEquals(ParsedLocation.Kind.LINK, found.kind)
    }

    @Test
    fun `a card link reads back with its name`() {
        val link = LocationFormatter.link(MapLink.CARD, dhaka, LocationPrecision.EXACT, label = "North End Coffee, Banani")
        val found = LocationParser.first("see $link")!!
        assertNear(dhaka, found.point)
        assertEquals("North End Coffee, Banani", found.label)
    }

    @Test
    fun `geo uris with a label`() {
        val found = LocationParser.first("geo:0,0?q=23.78,90.41(Home)")!!
        assertNear(GeoPoint(23.78, 90.41), found.point)
        assertEquals("Home", found.label)
        assertEquals(ParsedLocation.Kind.GEO_URI, found.kind)
    }

    @Test
    fun `hemisphere letters and degrees`() {
        assertNear(GeoPoint(-33.8568, 151.2153), LocationParser.first("33.8568 S, 151.2153 E")?.point)
        assertNear(GeoPoint(-33.856784, 151.215297), LocationParser.first("""33°51'24.4"S 151°12'55.1"E""")?.point, metres = 5.0)
    }

    @Test
    fun `places are listed in order and never overlap`() {
        val text = "home 23.780600, 90.419300 and work 7MMGQCJ9+6P, also https://waze.com/ul?ll=23.8%2C90.4"
        val found = LocationParser.findAll(text)
        assertEquals(
            listOf(ParsedLocation.Kind.COORDINATES, ParsedLocation.Kind.PLUS_CODE, ParsedLocation.Kind.LINK),
            found.map { it.kind },
        )
        assertEquals("7MMGQCJ9+6P", found[1].text)
    }

    @Test
    fun `things that are not places stay text`() {
        assertNull(LocationParser.first("add 1.5, 2.5 cups of flour"))
        assertNull(LocationParser.first("https://example.com/?q=23.7806,90.4193"))
        // A shortened link names no place until a server is asked.
        assertNull(LocationParser.first("https://maps.app.goo.gl/AbCdEf123"))
        assertNull(LocationParser.first("91.0000, 10.0000"))
        assertNull(LocationParser.first("QCJ9+6P Dhaka"))
    }
}

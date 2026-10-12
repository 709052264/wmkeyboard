package com.wasimaster.wmkeyboard.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The short address made from a Nominatim `/reverse?format=jsonv2` answer (#350). */
class ReverseGeocoderTest {

    private val answer = """
        {"place_id":1,"lat":"23.7937","lon":"90.4066","category":"amenity","type":"cafe",
         "name":"North End Coffee","display_name":"North End Coffee, 11, Road 11, Banani, Dhaka, Dhaka Division, 1213, Bangladesh",
         "address":{"amenity":"North End Coffee","house_number":"11","road":"Road 11","suburb":"Banani",
                    "city":"Dhaka","state":"Dhaka Division","postcode":"1213","country":"Bangladesh","country_code":"bd"}}
    """.trimIndent()

    @Test
    fun `each precision says only as much as it shares`() {
        assertEquals("North End Coffee, 11 Road 11, Banani, Dhaka", ReverseGeocoder.parse(answer, LocationPrecision.EXACT))
        assertEquals("Road 11, Banani, Dhaka", ReverseGeocoder.parse(answer, LocationPrecision.STREET))
        assertEquals("Banani, Dhaka", ReverseGeocoder.parse(answer, LocationPrecision.AREA))
        assertEquals("Dhaka, Bangladesh", ReverseGeocoder.parse(answer, LocationPrecision.CITY))
    }

    @Test
    fun `no structured address falls back to the long line`() {
        val bare = """{"display_name":"Somewhere, Some Region, Some Country, Earth"}"""
        assertEquals("Somewhere, Some Region, Some Country", ReverseGeocoder.parse(bare, LocationPrecision.EXACT))
    }

    @Test
    fun `nothing found is null`() {
        assertNull(ReverseGeocoder.parse("""{"error":"Unable to geocode"}""", LocationPrecision.EXACT))
        assertNull(ReverseGeocoder.parse("not json", LocationPrecision.EXACT))
    }
}

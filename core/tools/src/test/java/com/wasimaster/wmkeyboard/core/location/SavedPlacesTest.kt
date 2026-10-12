package com.wasimaster.wmkeyboard.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPlacesTest {

    @Test
    fun `round trips`() {
        val places = listOf(
            SavedPlace(1, "Home", GeoPoint(23.7806, 90.4193), "Road 11, Banani"),
            SavedPlace(2, "Work, \"HQ\"", GeoPoint(-33.856784, 151.215297)),
        )
        assertEquals(places, SavedPlaces.decode(SavedPlaces.encode(places)))
    }

    @Test
    fun `bad rows are dropped, not the whole list`() {
        val text = """[{"id":1,"name":"ok","lat":1.5,"lon":2.5},{"id":2,"name":"off the globe","lat":95,"lon":0},
            {"name":"no id","lat":1,"lon":1},"junk",{"id":1,"name":"duplicate","lat":0,"lon":0}]"""
        val decoded = SavedPlaces.decode(text)
        assertEquals(listOf("ok"), decoded.map { it.name })
        assertTrue(SavedPlaces.decode("not json").isEmpty())
        assertTrue(SavedPlaces.decode(null).isEmpty())
    }

    @Test
    fun `new ids never collide`() {
        val places = listOf(SavedPlace(100, "a", GeoPoint(0.0, 0.0)), SavedPlace(101, "b", GeoPoint(0.0, 0.0)))
        assertEquals(102, SavedPlaces.newId(places, now = 100))
    }
}

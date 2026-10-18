package com.wasimaster.wmkeyboard.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PluginDatesTest {

    private val zone = TimeZone.getTimeZone("Europe/London")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis / 1000

    @Test
    fun `the first press is the next whole hour, or the start of today`() {
        val now = at(2026, 10, 19, 14, 37) * 1000
        assertEquals(at(2026, 10, 19, 15), PluginDates.initial(now, time = true, zone = zone))
        assertEquals(at(2026, 10, 19, 0), PluginDates.initial(now, time = false, zone = zone))
        assertEquals(at(2026, 10, 20, 0), PluginDates.initial(at(2026, 10, 19, 23, 30) * 1000, time = true, zone = zone))
    }

    @Test
    fun `a day keeps its time of day across a clock change`() {
        // The UK leaves summer time on 25 October 2026.
        val before = at(2026, 10, 24, 9)
        assertEquals(at(2026, 10, 26, 9), PluginDates.addDays(before, 2, zone))
        assertEquals(before, PluginDates.addDays(at(2026, 10, 26, 9), -2, zone))
    }

    @Test
    fun `minutes move plainly and today keeps the time`() {
        assertEquals(at(2026, 10, 19, 9, 15), PluginDates.addMinutes(at(2026, 10, 19, 9), 15))
        val now = at(2026, 10, 19, 14) * 1000
        assertEquals(at(2026, 10, 19, 8, 30), PluginDates.today(at(2026, 12, 1, 8, 30), now, zone))
        assertEquals(at(2026, 12, 1, 0), PluginDates.startOfDay(at(2026, 12, 1, 8, 30), zone))
    }
}

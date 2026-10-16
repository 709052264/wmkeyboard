package com.wasimaster.wmkeyboard.core.tools

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dates, times and lengths as a model writes them for the calendar and clock tools (#698). */
class AiToolArgsTest {

    private val dhaka = TimeZone.getTimeZone("Asia/Dhaka")
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `a date and time read in their usual spellings`() {
        assertEquals(AiCalendarArgs.Moment(2026, 10, 17, 17, 0), AiCalendarArgs.parse("2026-10-17T17:00"))
        assertEquals(AiCalendarArgs.Moment(2026, 10, 17, 17, 5), AiCalendarArgs.parse("2026-10-17 17:05:00Z"))
        assertEquals(AiCalendarArgs.Moment(2026, 10, 17), AiCalendarArgs.parse("2026-10-17"))
        assertNull(AiCalendarArgs.parse("next Friday"))
        assertNull(AiCalendarArgs.parse("2026-13-01"))
    }

    @Test
    fun `a timed event without an end lasts an hour, in the phone's zone`() {
        val span = AiCalendarArgs.span("2026-10-17T17:00", null, dhaka)!!
        assertFalse(span.allDay)
        assertEquals(60 * 60 * 1000L, span.end - span.begin)
        // 17:00 in Dhaka (UTC+6) is 11:00 UTC.
        assertEquals(AiCalendarArgs.millis(AiCalendarArgs.Moment(2026, 10, 17, 11, 0), utc), span.begin)
    }

    @Test
    fun `an all-day event runs between UTC midnights, its last day included`() {
        val one = AiCalendarArgs.span("2026-10-17", null, dhaka)!!
        assertTrue(one.allDay)
        assertEquals(AiCalendarArgs.DAY_MS, one.end - one.begin)
        val three = AiCalendarArgs.span("2026-10-17", "2026-10-19", dhaka)!!
        assertEquals(3 * AiCalendarArgs.DAY_MS, three.end - three.begin)
        assertEquals(0L, three.begin % AiCalendarArgs.DAY_MS)
    }

    @Test
    fun `an end before the start is ignored rather than trusted`() {
        val span = AiCalendarArgs.span("2026-10-17T17:00", "2026-10-17T16:00", dhaka)!!
        assertEquals(60 * 60 * 1000L, span.end - span.begin)
    }

    @Test
    fun `times of day read with and without a meridiem`() {
        assertEquals(7 to 30, AiClockArgs.time("07:30"))
        assertEquals(19 to 30, AiClockArgs.time("7:30 pm"))
        assertEquals(18 to 0, AiClockArgs.time("6pm"))
        assertEquals(0 to 15, AiClockArgs.time("12:15 a.m."))
        assertNull(AiClockArgs.time("6"))
        assertNull(AiClockArgs.time("25:00"))
    }

    @Test
    fun `durations read in every common shape`() {
        assertEquals(90_000L, AiClockArgs.duration("90s"))
        assertEquals(25 * 60_000L, AiClockArgs.duration("25m"))
        assertEquals(90 * 60_000L, AiClockArgs.duration("1h30m"))
        assertEquals(80 * 60_000L, AiClockArgs.duration("1 hour 20 minutes"))
        assertEquals(90 * 60_000L, AiClockArgs.duration("1:30:00"))
        assertEquals(10 * 60_000L, AiClockArgs.duration("10"))
        assertNull(AiClockArgs.duration("a while"))
        assertNull(AiClockArgs.duration("3 months"))
    }

    @Test
    fun `repeat days read as the clock's bits, Monday first`() {
        assertEquals(0, AiClockArgs.days(null))
        assertEquals(0b001_0101, AiClockArgs.days("mon,wed,fri"))
        assertEquals(0b001_1111, AiClockArgs.days("weekdays"))
        assertEquals(0b111_1111, AiClockArgs.days("every day"))
        assertEquals(0b100_0000, AiClockArgs.days("Sunday"))
        assertNull(AiClockArgs.days("someday"))
    }
}

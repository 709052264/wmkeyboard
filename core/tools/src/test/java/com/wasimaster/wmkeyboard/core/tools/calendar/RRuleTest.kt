package com.wasimaster.wmkeyboard.core.tools.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RRuleTest {

    private val dhaka = TimeZone.getTimeZone("Asia/Dhaka")

    /** Monday 19 October 2026. */
    private val monday = EventDate(2026, 10, 19)

    /** Wednesday 21 October 2026. */
    private val wednesday = EventDate(2026, 10, 21)

    private fun encode(r: Recurrence, allDay: Boolean = false) = RRule.encode(r, allDay, dhaka)

    @Test
    fun `weekly with interval and days`() {
        val r = Recurrence(Frequency.WEEKLY, interval = 2, weekdays = Recurrence.bit(1) or Recurrence.bit(3))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", encode(r))
        assertEquals(r, RRule.decode(encode(r), monday, dhaka))
    }

    @Test
    fun `monthly on the nth and the last weekday`() {
        assertEquals("FREQ=MONTHLY;BYDAY=2TU", encode(Recurrence(Frequency.MONTHLY, nth = NthWeekday(2, 2))))
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", encode(Recurrence(Frequency.MONTHLY, nth = NthWeekday(-1, 5))))
        assertEquals(
            Recurrence(Frequency.MONTHLY, nth = NthWeekday(2, 2)),
            RRule.decode("FREQ=MONTHLY;BYDAY=TU;BYSETPOS=2", monday, dhaka),
        )
        assertEquals(
            Recurrence(Frequency.MONTHLY, nth = NthWeekday(-1, 5)),
            RRule.decode("FREQ=MONTHLY;BYDAY=5FR", monday, dhaka),
        )
    }

    @Test
    fun `ends by count and by date`() {
        assertEquals("FREQ=DAILY;COUNT=5", encode(Recurrence(Frequency.DAILY, end = RepeatEnd.Count(5))))
        val until = Recurrence(Frequency.DAILY, end = RepeatEnd.Until(EventDate(2026, 12, 3)))
        assertEquals("FREQ=DAILY;UNTIL=20261203", encode(until, allDay = true))
        // The last second of 3 December in Dhaka (UTC+6), in UTC.
        assertEquals("FREQ=DAILY;UNTIL=20261203T175959Z", encode(until))
        assertEquals(until, RRule.decode("FREQ=DAILY;UNTIL=20261203T175959Z", monday, dhaka))
        assertEquals(until, RRule.decode("FREQ=DAILY;UNTIL=20261203", monday, dhaka))
    }

    @Test
    fun `google's weekday rule reads as workdays`() {
        val r = RRule.decode("RRULE:FREQ=WEEKLY;WKST=SU;BYDAY=MO,TU,WE,TH,FR", monday, dhaka)
        assertTrue(r!!.isWorkdays)
    }

    @Test
    fun `a bare weekly rule repeats on the start's weekday`() {
        assertEquals(Recurrence(Frequency.WEEKLY, weekdays = Recurrence.bit(3)), RRule.decode("FREQ=WEEKLY", wednesday, dhaka))
    }

    @Test
    fun `yearly and monthly parts that only restate the start are plain`() {
        assertEquals(Recurrence.yearly(), RRule.decode("FREQ=YEARLY;BYMONTH=10;BYMONTHDAY=21", wednesday, dhaka))
        assertEquals(Recurrence.monthly(), RRule.decode("FREQ=MONTHLY;BYMONTHDAY=21", wednesday, dhaka))
    }

    @Test
    fun `rules this model cannot hold are kept verbatim`() {
        val many = RRule.decode("FREQ=MONTHLY;BYMONTHDAY=1,15", wednesday, dhaka)!!
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=1,15", many.custom)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=1,15", encode(many))
        assertTrue(RRule.decode("FREQ=DAILY;BYHOUR=9", wednesday, dhaka)!!.isCustom)
        assertTrue(RRule.decode("FREQ=HOURLY", wednesday, dhaka)!!.isCustom)
    }

    @Test
    fun `an empty rule is no rule`() {
        assertNull(RRule.decode(null, monday, dhaka))
        assertNull(RRule.decode("  ", monday, dhaka))
    }

    @Test
    fun `truncating replaces count or until with an until just before`() {
        val nine = EventTimes.millis(EventDate(2026, 10, 26), 9 * 60, dhaka)
        assertEquals(
            "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261026T025959Z",
            RRule.truncated("FREQ=WEEKLY;BYDAY=MO;COUNT=10", nine, allDay = false),
        )
        val midnight = EventTimes.millis(EventDate(2026, 10, 26), 0, EventDraft.UTC)
        assertEquals("FREQ=DAILY;UNTIL=20261025", RRule.truncated("FREQ=DAILY;UNTIL=20271231", midnight, allDay = true))
    }

    @Test
    fun `count surgery`() {
        assertEquals("FREQ=DAILY;COUNT=7", RRule.withCount("FREQ=DAILY;COUNT=10", 7))
        assertEquals("FREQ=DAILY", RRule.withCount("FREQ=DAILY", 7))
        assertEquals(10, RRule.count("FREQ=DAILY;COUNT=10"))
        assertNull(RRule.count("FREQ=DAILY"))
    }

    @Test
    fun `a rule on the start's weekday follows a moved start`() {
        val tuesday = monday.plusDays(1)
        assertEquals(Recurrence.weekly(tuesday), Recurrence.weekly(monday).followingStart(monday, tuesday))
        assertEquals(Recurrence.workdays(), Recurrence.workdays().followingStart(monday, tuesday))
    }

    @Test
    fun `nth weekday of a date`() {
        val tuesday27 = EventDate(2026, 10, 27)
        assertEquals(NthWeekday(4, 2), NthWeekday.of(tuesday27))
        assertEquals(NthWeekday(-1, 2), NthWeekday.lastOf(tuesday27))
        assertNull(NthWeekday.lastOf(monday))
    }
}

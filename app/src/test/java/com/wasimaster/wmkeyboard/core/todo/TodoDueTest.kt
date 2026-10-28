package com.wasimaster.wmkeyboard.core.todo

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The quick due dates and when a task reminds (#995). */
class TodoDueTest {

    private val zone = ZoneId.of("Asia/Dhaka")

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun laterTodayIsAtLeastThreeHoursOnAtTheNextHour() {
        assertEquals(at("2026-10-28T18:00"), TodoDue.at(TodoDuePreset.LATER_TODAY, at("2026-10-28T14:20"), zone))
    }

    @Test
    fun laterTodayIsNotOfferedLateAtNight() {
        assertNull(TodoDue.at(TodoDuePreset.LATER_TODAY, at("2026-10-28T21:30"), zone))
    }

    @Test
    fun thisEveningOnlyBeforeSix() {
        assertEquals(at("2026-10-28T18:00"), TodoDue.at(TodoDuePreset.THIS_EVENING, at("2026-10-28T10:00"), zone))
        assertNull(TodoDue.at(TodoDuePreset.THIS_EVENING, at("2026-10-28T19:00"), zone))
    }

    @Test
    fun tomorrowAndNextWeekAreMornings() {
        // 28 October 2026 is a Wednesday.
        assertEquals(at("2026-10-29T09:00"), TodoDue.at(TodoDuePreset.TOMORROW, at("2026-10-28T22:00"), zone))
        assertEquals(at("2026-11-02T09:00"), TodoDue.at(TodoDuePreset.NEXT_WEEK, at("2026-10-28T22:00"), zone))
    }

    @Test
    fun noDateIsOfferedOnlyForADatedTask() {
        val now = at("2026-10-28T10:00")
        assertFalse(TodoDuePreset.NO_DATE in TodoDue.offered(now, zone, hasDue = false))
        assertTrue(TodoDuePreset.NO_DATE in TodoDue.offered(now, zone, hasDue = true))
    }

    @Test
    fun anAllDayTaskRemindsThatMorningWhereThePhoneIs() {
        val day = LocalDateTime.parse("2026-10-30T00:00").toInstant(ZoneOffset.UTC).toEpochMilli()
        val task = TodoTask(id = 1, text = "Pay rent", due = day, dueAllDay = true)
        assertEquals(at("2026-10-30T09:00"), TodoDue.remindAt(task, zone))
    }

    @Test
    fun aTickedTaskDoesNotRemind() {
        val task = TodoTask(id = 1, text = "Pay rent", due = at("2026-10-30T09:00"), done = true)
        assertNull(TodoDue.remindAt(task, zone))
    }
}

package com.wasimaster.wmkeyboard.core.tools.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class EventWritesTest {

    private val dhakaId = "Asia/Dhaka"
    private val dhaka = TimeZone.getTimeZone(dhakaId)

    /** A weekly Monday 09:00 series that began Monday 5 October 2026. */
    private val firstDay = EventDate(2026, 10, 5)
    private val rule = "FREQ=WEEKLY;BYDAY=MO"
    private val occurrenceDay = EventDate(2026, 10, 19)
    private val occurrenceAt = EventTimes.millis(occurrenceDay, 9 * 60, dhaka)
    private val masterAt = EventTimes.millis(firstDay, 9 * 60, dhaka)

    private fun series(exceptionId: Long? = null, original: Long = occurrenceAt, rule: String = this.rule) = SeriesInfo(
        masterId = 7,
        masterStart = masterAt,
        masterStartDate = firstDay,
        rule = rule,
        allDay = false,
        exceptionId = exceptionId,
        originalInstanceTime = original,
        occurrenceDate = if (original == masterAt) firstDay else occurrenceDay,
    )

    private fun draft(rule: String = this.rule, day: EventDate = occurrenceDay): EventDraft {
        val r = RRule.decode(rule, day, dhaka)
        return EventDraft(
            eventId = 7, calendarId = 3, title = "Standup", startDate = day, startMinute = 9 * 60,
            zoneId = dhakaId, recurrence = r, originalRule = rule, originalRecurrence = r,
        )
    }

    @Test
    fun `a one-off event gets an end and no rule`() {
        val op = EventWrites.create(draft().copy(eventId = null, recurrence = null, originalRule = null)).single()
        val row = (op as CalendarOp.Insert).row
        assertNull(row.rrule)
        assertNull(row.duration)
        assertEquals(row.dtStart + 3_600_000L, row.dtEnd)
        assertEquals(dhakaId, row.timezone)
    }

    @Test
    fun `a repeating event gets a duration instead of an end`() {
        val row = (EventWrites.create(draft().copy(eventId = null)).single() as CalendarOp.Insert).row
        assertNull(row.dtEnd)
        assertEquals("P3600S", row.duration)
        assertEquals(rule, row.rrule)
    }

    @Test
    fun `an all-day event is stored at utc midnights`() {
        val d = EventDraft(startDate = occurrenceDay, endDate = occurrenceDay.plusDays(1), allDay = true, zoneId = dhakaId)
        val row = EventWrites.row(d)
        assertEquals(EventTimes.millis(occurrenceDay, 0, EventDraft.UTC), row.dtStart)
        assertEquals(EventTimes.millis(occurrenceDay.plusDays(2), 0, EventDraft.UTC), row.dtEnd)
        assertEquals("UTC", row.timezone)
    }

    @Test
    fun `editing one occurrence makes an exception without a rule`() {
        val op = EventWrites.edit(draft().copy(title = "Retro"), series(), EditScope.THIS).single()
        op as CalendarOp.InsertException
        assertEquals(7L, op.masterId)
        assertEquals(occurrenceAt, op.originalInstanceTime)
        assertNull(op.row!!.rrule)
        assertNotNull(op.row!!.dtEnd)
        assertEquals("Retro", op.row!!.title)
    }

    @Test
    fun `editing an occurrence that already is an exception updates it in place`() {
        val op = EventWrites.edit(draft(), series(exceptionId = 9), EditScope.THIS).single()
        assertEquals(9L, (op as CalendarOp.Update).eventId)
        assertNull(op.row.rrule)
    }

    @Test
    fun `this and following cuts the series and starts a new one`() {
        val ops = EventWrites.edit(draft().copy(title = "New standup"), series(), EditScope.FOLLOWING)
        assertEquals(CalendarOp.SetRule(7, "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261019T025959Z"), ops[0])
        val insert = ops[1] as CalendarOp.Insert
        assertEquals(occurrenceAt, insert.row.dtStart)
        assertEquals(rule, insert.row.rrule)
        assertEquals("New standup", insert.row.title)
    }

    @Test
    fun `a count carries over less what already happened`() {
        val counted = "FREQ=WEEKLY;BYDAY=MO;COUNT=10"
        val ops = EventWrites.edit(draft(counted), series(rule = counted), EditScope.FOLLOWING, occurrencesBefore = 2)
        assertEquals("FREQ=WEEKLY;BYDAY=MO;COUNT=8", (ops[1] as CalendarOp.Insert).row.rrule)
    }

    @Test
    fun `this and following from the first occurrence is the whole series`() {
        val ops = EventWrites.edit(draft(day = firstDay), series(original = masterAt), EditScope.FOLLOWING)
        assertEquals(7L, (ops.single() as CalendarOp.Update).eventId)
    }

    @Test
    fun `a new time for all moves the series start, not its first day`() {
        val d = draft().withStart(occurrenceDay, 10 * 60 + 30)
        val op = EventWrites.edit(d, series(), EditScope.ALL).single() as CalendarOp.Update
        assertEquals(7L, op.eventId)
        assertEquals(EventTimes.millis(firstDay, 10 * 60 + 30, dhaka), op.row.dtStart)
        assertEquals("P3600S", op.row.duration)
        assertEquals(rule, op.row.rrule)
    }

    @Test
    fun `moving all occurrences a day later moves the rule's weekday too`() {
        val d = draft().withStart(occurrenceDay.plusDays(1))
        val op = EventWrites.edit(d, series(), EditScope.ALL).single() as CalendarOp.Update
        assertEquals(EventTimes.millis(firstDay.plusDays(1), 9 * 60, dhaka), op.row.dtStart)
        assertEquals("FREQ=WEEKLY;BYDAY=TU", op.row.rrule)
    }

    @Test
    fun `deleting by scope`() {
        assertEquals(listOf(CalendarOp.Delete(4)), EventWrites.delete(4, null, EditScope.ALL))
        val one = EventWrites.delete(7, series(), EditScope.THIS).single() as CalendarOp.InsertException
        assertNull(one.row)
        assertEquals(occurrenceAt, one.originalInstanceTime)
        assertEquals(listOf(CalendarOp.Cancel(9)), EventWrites.delete(9, series(exceptionId = 9), EditScope.THIS))
        assertEquals(
            listOf(CalendarOp.SetRule(7, "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261019T025959Z")),
            EventWrites.delete(7, series(), EditScope.FOLLOWING),
        )
        assertEquals(listOf(CalendarOp.Delete(7)), EventWrites.delete(7, series(), EditScope.ALL))
        assertEquals(listOf(CalendarOp.Delete(7)), EventWrites.delete(7, series(original = masterAt), EditScope.FOLLOWING))
    }

    @Test
    fun `durations read and write`() {
        assertEquals("P3600S", EventTimes.duration(3_600_000L, allDay = false))
        assertEquals("P2D", EventTimes.duration(2 * EventDraft.DAY_MS, allDay = true))
        assertEquals(5_400_000L, EventTimes.parseDuration("PT1H30M"))
        assertEquals(EventDraft.DAY_MS, EventTimes.parseDuration("P1D"))
        assertEquals(7 * EventDraft.DAY_MS, EventTimes.parseDuration("P1W"))
        assertEquals(3_600_000L, EventTimes.parseDuration("P3600S"))
        assertNull(EventTimes.parseDuration("P"))
        assertNull(EventTimes.parseDuration("an hour"))
    }

    @Test
    fun `moving the start keeps the length across midnight`() {
        val d = EventDraft(startDate = occurrenceDay, startMinute = 23 * 60 + 30, zoneId = dhakaId)
        assertEquals(occurrenceDay.plusDays(1), d.withLength(60).endDate)
        val moved = d.withLength(60).withStart(occurrenceDay.plusDays(4), 10 * 60)
        assertEquals(occurrenceDay.plusDays(4), moved.endDate)
        assertEquals(11 * 60, moved.endMinute)
        assertEquals(60, moved.lengthMinutes)
    }

    @Test
    fun `all day off puts the event at the default hour`() {
        val d = EventDraft(startDate = occurrenceDay, allDay = true, zoneId = dhakaId).withAllDay(false, 14 * 60, 30)
        assertEquals(14 * 60, d.startMinute)
        assertEquals(14 * 60 + 30, d.endMinute)
        assertTrue(!d.allDay)
    }
}

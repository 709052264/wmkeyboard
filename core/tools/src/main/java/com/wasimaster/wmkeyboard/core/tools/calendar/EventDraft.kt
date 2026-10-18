package com.wasimaster.wmkeyboard.core.tools.calendar

import com.wasimaster.wmkeyboard.core.tools.CalendarSystems
import java.util.Calendar
import java.util.TimeZone

/**
 * A calendar date, month 1-12. Arithmetic goes through Julian Day Numbers
 * ([CalendarSystems]), since minSdk 24 has no java.time.
 */
data class EventDate(val year: Int, val month: Int, val day: Int) : Comparable<EventDate> {

    val jdn: Long get() = CalendarSystems.gregorianToJdn(year, month, day)

    /** 0 = Sunday .. 6 = Saturday, as [CalendarSystems.dayOfWeek]. */
    val dayOfWeek: Int get() = CalendarSystems.dayOfWeek(jdn)

    val monthLength: Int get() = CalendarSystems.gregorianMonthLength(year, month)

    fun plusDays(days: Long): EventDate = ofJdn(jdn + days)

    /** The same day [months] later, held to the end of a shorter month. */
    fun plusMonths(months: Int): EventDate {
        val index = year * 12 + (month - 1) + months
        val y = Math.floorDiv(index, 12)
        val m = Math.floorMod(index, 12) + 1
        return EventDate(y, m, day.coerceAtMost(CalendarSystems.gregorianMonthLength(y, m)))
    }

    fun daysUntil(other: EventDate): Long = other.jdn - jdn

    override fun compareTo(other: EventDate): Int = jdn.compareTo(other.jdn)

    companion object {
        fun ofJdn(jdn: Long): EventDate = CalendarSystems.jdnToGregorian(jdn).let { EventDate(it.year, it.month, it.day) }

        /** The date [millis] falls on in [zone]. */
        fun of(millis: Long, zone: TimeZone): EventDate = Calendar.getInstance(zone).let {
            it.timeInMillis = millis
            EventDate(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
        }
    }
}

/** Whether an event blocks the time, as `CalendarContract.Events.AVAILABILITY` stores it. */
enum class Availability { BUSY, FREE }

/** Someone invited to an event, by address. */
data class Guest(val email: String, val name: String = "") {
    val label: String get() = name.ifBlank { email }
}

/**
 * An event being written or changed: what the composer shows and edits, and
 * what [EventWrites] turns into provider rows.
 *
 * Times are wall-clock times in [zoneId]: [startMinute] and [endMinute] are
 * minutes past midnight on [startDate] and [endDate]. An [allDay] event
 * ignores the minutes and runs from the start of [startDate] to the end of
 * [endDate], both days included.
 */
data class EventDraft(
    /** The row being changed (a series' master or an exception), or null for a new event. */
    val eventId: Long? = null,
    val calendarId: Long = -1L,
    val title: String = "",
    val location: String = "",
    val notes: String = "",
    val allDay: Boolean = false,
    val startDate: EventDate,
    val startMinute: Int = 9 * 60,
    val endDate: EventDate = startDate,
    val endMinute: Int = (startMinute + 60).coerceAtMost(MINUTES_PER_DAY - 1),
    val zoneId: String = TimeZone.getDefault().id,
    val recurrence: Recurrence? = null,
    /**
     * The rule the event had when it was opened, written back untouched while
     * [recurrence] still equals [originalRecurrence]: a decode and re-encode
     * would drop whatever parts of a foreign rule this model does not carry.
     */
    val originalRule: String? = null,
    val originalRecurrence: Recurrence? = null,
    /** Minutes before the start; empty for no reminder. */
    val reminders: List<Int> = emptyList(),
    val availability: Availability = Availability.BUSY,
    val guests: List<Guest> = emptyList(),
) {
    val zone: TimeZone get() = if (allDay) UTC else TimeZone.getTimeZone(zoneId)

    /** The first instant, in the provider's terms: a UTC midnight for an all-day event. */
    val startMillis: Long get() = EventTimes.millis(startDate, if (allDay) 0 else startMinute, zone)

    /** The instant after the last one: the UTC midnight after [endDate] for an all-day event. */
    val endMillis: Long
        get() = if (allDay) {
            EventTimes.millis(endDate.plusDays(1), 0, UTC)
        } else {
            EventTimes.millis(endDate, endMinute, zone)
        }

    /** How long it runs, never less than a minute (a day, all day). */
    val lengthMillis: Long get() = (endMillis - startMillis).coerceAtLeast(if (allDay) DAY_MS else MINUTE_MS)

    /** Whether the end is not after the start, which Save refuses. */
    val endsBeforeStart: Boolean get() = endMillis <= startMillis

    /** Whether [recurrence] is still the rule the event was opened with. */
    val ruleUnchanged: Boolean get() = originalRule != null && recurrence == originalRecurrence

    /**
     * The start moved to [date] at [minute], with the end following so the
     * length stays the same. A weekly rule on just the old weekday, or a
     * monthly one on its nth weekday, moves with it; otherwise a Monday
     * event dragged to Tuesday would keep landing on Mondays.
     */
    fun withStart(date: EventDate, minute: Int = startMinute): EventDraft {
        val m = minute.coerceIn(0, MINUTES_PER_DAY - 1)
        val shiftDays = startDate.daysUntil(date)
        val shiftMinutes = m - startMinute
        var endTotal = endDate.plusDays(shiftDays).jdn * MINUTES_PER_DAY + endMinute + shiftMinutes
        val newEnd = EventDate.ofJdn(Math.floorDiv(endTotal, MINUTES_PER_DAY.toLong()))
        endTotal = Math.floorMod(endTotal, MINUTES_PER_DAY.toLong())
        return copy(
            startDate = date,
            startMinute = m,
            endDate = newEnd,
            endMinute = endTotal.toInt(),
            recurrence = recurrence?.followingStart(startDate, date),
        )
    }

    /** The end moved, the start left where it is. */
    fun withEnd(date: EventDate, minute: Int = endMinute): EventDraft =
        copy(endDate = date, endMinute = minute.coerceIn(0, MINUTES_PER_DAY - 1))

    /** The end set [minutes] after the start. */
    fun withLength(minutes: Int): EventDraft {
        val total = startDate.jdn * MINUTES_PER_DAY + startMinute + minutes.coerceAtLeast(1)
        return copy(
            endDate = EventDate.ofJdn(Math.floorDiv(total, MINUTES_PER_DAY.toLong())),
            endMinute = Math.floorMod(total, MINUTES_PER_DAY.toLong()).toInt(),
        )
    }

    /** Length in minutes as the composer's duration chips read it; 0 for all-day. */
    val lengthMinutes: Int
        get() = if (allDay) 0 else ((endDate.jdn - startDate.jdn) * MINUTES_PER_DAY + endMinute - startMinute).toInt()

    /**
     * All day on or off. Turning it on keeps the dates; turning it off puts the
     * event at [defaultMinute] for [defaultLength] minutes on the first day.
     */
    fun withAllDay(on: Boolean, defaultMinute: Int = 9 * 60, defaultLength: Int = 60): EventDraft =
        if (on == allDay) {
            this
        } else if (on) {
            copy(allDay = true, endDate = if (endDate < startDate) startDate else endDate)
        } else {
            copy(allDay = false, startMinute = defaultMinute).withLength(defaultLength)
        }

    companion object {
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
        const val MINUTES_PER_DAY = 24 * 60
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L
    }
}

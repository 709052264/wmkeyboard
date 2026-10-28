package com.wasimaster.wmkeyboard.core.todo

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The quick due dates a task can be given from the keyboard (#995), and when
 * a task's reminder goes off. A keyboard panel has no room for a calendar, and
 * these few cover what a to-do list is mostly told: later, tonight, tomorrow,
 * next week.
 */
enum class TodoDuePreset {
    /** At least three hours on, at the next full hour. Only while that is still today. */
    LATER_TODAY,

    /** 18:00 today, while that is still to come. */
    THIS_EVENING,

    /** 09:00 tomorrow. */
    TOMORROW,

    /** 09:00 next Monday. */
    NEXT_WEEK,

    /** Takes the date off. Only for a task that has one. */
    NO_DATE,
}

object TodoDue {

    /** The hour a day-only date ("tomorrow", an all-day task from a task app) reminds at. */
    const val MORNING_HOUR = 9
    private const val EVENING_HOUR = 18
    private const val LATER_HOURS = 3L

    /** The presets worth offering at [now]; [hasDue] adds the one that clears. */
    fun offered(now: Long, zone: ZoneId, hasDue: Boolean): List<TodoDuePreset> =
        TodoDuePreset.entries.filter { preset ->
            when (preset) {
                TodoDuePreset.NO_DATE -> hasDue
                else -> at(preset, now, zone) != null
            }
        }

    /** When [preset] falls, from [now]; null for [TodoDuePreset.NO_DATE] or one already past. */
    fun at(preset: TodoDuePreset, now: Long, zone: ZoneId): Long? {
        val here = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val today = here.toLocalDate()
        val at = when (preset) {
            TodoDuePreset.LATER_TODAY -> here.plusHours(LATER_HOURS).truncatedTo(ChronoUnit.HOURS).plusHours(1)
                .takeIf { it.toLocalDate() == today }
            TodoDuePreset.THIS_EVENING -> today.atTime(EVENING_HOUR, 0).atZone(zone).takeIf { it.isAfter(here) }
            TodoDuePreset.TOMORROW -> today.plusDays(1).atTime(MORNING_HOUR, 0).atZone(zone)
            TodoDuePreset.NEXT_WEEK -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(MORNING_HOUR, 0).atZone(zone)
            TodoDuePreset.NO_DATE -> null
        }
        return at?.toInstant()?.toEpochMilli()
    }

    /**
     * When [task]'s reminder goes off, or null for a task that has no date or
     * is ticked. A timed task reminds at its time; an all-day one, whose date
     * is midnight UTC of its day, at [MORNING_HOUR] on that day where the
     * phone is.
     */
    fun remindAt(task: TodoTask, zone: ZoneId): Long? {
        if (task.done) return null
        val due = task.due ?: return null
        if (!task.dueAllDay) return due
        val day = Instant.ofEpochMilli(due).atZone(ZoneOffset.UTC).toLocalDate()
        return day.atTime(LocalTime.of(MORNING_HOUR, 0)).atZone(zone).toInstant().toEpochMilli()
    }
}

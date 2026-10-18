package com.wasimaster.wmkeyboard.core.plugins

import java.util.Calendar
import java.util.TimeZone

/**
 * What each button on a `ui.date` widget does to its value.
 *
 * Every value is seconds since the epoch, the unit a script holds. Days move on
 * the calendar in the phone's time zone rather than by 86,400 seconds, so 9:00
 * stays 9:00 across a daylight-saving change. Kept apart from the renderer so
 * the rules run in a plain JVM test.
 */
object PluginDates {

    private const val MILLIS = 1000L
    private const val SECONDS_PER_MINUTE = 60L

    /**
     * The value the first press gives a widget with no date: the start of
     * today, or with [time] the next whole hour. A reminder for "now" is no use
     * to anyone, and the next hour is the nearest time worth setting.
     */
    fun initial(nowMillis: Long, time: Boolean, zone: TimeZone = TimeZone.getDefault()): Long {
        val calendar = calendar(nowMillis / MILLIS, zone)
        if (!time) return startOfDay(calendar)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.add(Calendar.HOUR_OF_DAY, 1)
        return calendar.timeInMillis / MILLIS
    }

    /** [value] moved by [days] on the calendar, keeping its time of day. */
    fun addDays(value: Long, days: Int, zone: TimeZone = TimeZone.getDefault()): Long {
        val calendar = calendar(value, zone)
        calendar.add(Calendar.DAY_OF_MONTH, days)
        return calendar.timeInMillis / MILLIS
    }

    /** [value] moved by [minutes]. */
    fun addMinutes(value: Long, minutes: Int): Long = value + minutes * SECONDS_PER_MINUTE

    /** Today's date with [value]'s time of day. */
    fun today(value: Long, nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val from = calendar(value, zone)
        val calendar = calendar(nowMillis / MILLIS, zone)
        calendar.set(Calendar.HOUR_OF_DAY, from.get(Calendar.HOUR_OF_DAY))
        calendar.set(Calendar.MINUTE, from.get(Calendar.MINUTE))
        calendar.set(Calendar.SECOND, from.get(Calendar.SECOND))
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis / MILLIS
    }

    /** The start of [value]'s day, for a widget that picks no time. */
    fun startOfDay(value: Long, zone: TimeZone = TimeZone.getDefault()): Long = startOfDay(calendar(value, zone))

    private fun startOfDay(calendar: Calendar): Long {
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis / MILLIS
    }

    private fun calendar(seconds: Long, zone: TimeZone): Calendar =
        Calendar.getInstance(zone).apply { timeInMillis = seconds * MILLIS }
}

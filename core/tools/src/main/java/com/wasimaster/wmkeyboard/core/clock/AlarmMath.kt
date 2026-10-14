package com.wasimaster.wmkeyboard.core.clock

import java.util.Calendar
import java.util.TimeZone

/**
 * When alarms ring. Pure arithmetic over `java.util.Calendar` (no `java.time`
 * below API 26 without desugaring, which this project does not turn on), so
 * the rules are unit-tested off the device.
 */
object AlarmMath {

    /** A week and a day: the furthest any enabled alarm can be from now. */
    private const val SEARCH_DAYS = 8

    /**
     * The next instant [alarm] rings strictly after [now], or null when it is
     * off.
     *
     * A snooze in progress wins over the schedule. A time that does not exist
     * on a given day, because the clocks jump forward through it, rings at the
     * first minute that does (Calendar's lenient roll does exactly that); a
     * time that happens twice, when the clocks go back, rings the first time.
     */
    fun nextFire(alarm: Alarm, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (!alarm.enabled) return null
        if (alarm.snoozedUntil > now) return alarm.snoozedUntil
        val floor = maxOf(now, alarm.skipUntil - 1)
        val cal = Calendar.getInstance(zone).apply { timeInMillis = floor }
        val startDay = cal.clone() as Calendar
        repeat(SEARCH_DAYS) { offset ->
            val day = (startDay.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val at = day.timeInMillis
            val dayOk = !alarm.repeats ||
                Weekdays.has(alarm.days, Weekdays.isoIndex(day.get(Calendar.DAY_OF_WEEK)))
            if (dayOk && at > floor) return at
        }
        return null
    }

    /** The alarm that rings first, and when; null when none is on. */
    fun nextOfAll(alarms: List<Alarm>, now: Long, zone: TimeZone = TimeZone.getDefault()): Pair<Alarm, Long>? =
        alarms.mapNotNull { a -> nextFire(a, now, zone)?.let { a to it } }.minByOrNull { it.second }

    /**
     * The alarms due at [at], the instant a scheduled wake-up fired.
     *
     * A wake-up can arrive late (Doze, a busy phone), never early, so an alarm
     * is due when its next ring counted from a little before [at] has passed.
     * The window is a minute and a half: wider than any delivery delay worth
     * ringing for, narrower than the gap between two alarms a minute apart
     * being merged into one ring by accident is worth worrying about.
     */
    fun dueAt(alarms: List<Alarm>, at: Long, zone: TimeZone = TimeZone.getDefault()): List<Alarm> =
        alarms.filter { a ->
            val next = nextFire(a, at - DUE_WINDOW_MS, zone) ?: return@filter false
            next <= at + EARLY_SLACK_MS
        }

    /** How far back [dueAt] looks. */
    const val DUE_WINDOW_MS = 90_000L

    /** A wake-up a hair early still counts. */
    private const val EARLY_SLACK_MS = 2_000L

    /**
     * What the alarm becomes once it has rung and been dismissed: a one-shot
     * turns itself off, a repeating one just forgets its snooze.
     */
    fun afterDismiss(alarm: Alarm): Alarm =
        alarm.copy(
            enabled = alarm.repeats && alarm.enabled,
            snoozedUntil = 0,
            snoozeCount = 0,
        )

    /** The alarm snoozed at [now] for [minutes]. */
    fun snoozed(alarm: Alarm, now: Long, minutes: Int): Alarm =
        alarm.copy(snoozedUntil = now + minutes * 60_000L, snoozeCount = alarm.snoozeCount + 1)

    /**
     * Skips the next ring of a repeating alarm and keeps the rest. Returns the
     * alarm unchanged when it is not on.
     */
    fun skipNext(alarm: Alarm, now: Long, zone: TimeZone = TimeZone.getDefault()): Alarm {
        val next = nextFire(alarm.copy(snoozedUntil = 0), now, zone) ?: return alarm
        return alarm.copy(skipUntil = next + 1, snoozedUntil = 0, snoozeCount = 0)
    }

    /** Whether the next ring is being skipped. */
    fun isSkipping(alarm: Alarm, now: Long): Boolean = alarm.skipUntil > now

    /**
     * Hour and minute [minutes] from [now], for the "in 20 min" quick alarms.
     * Seconds are dropped, so the alarm rings at the start of the minute.
     */
    fun inMinutes(now: Long, minutes: Int, zone: TimeZone = TimeZone.getDefault()): Pair<Int, Int> {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = now + minutes * 60_000L
        }
        return cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
    }
}

/** A span broken into days, hours, minutes and seconds, all non-negative. */
data class Span(val days: Long, val hours: Int, val minutes: Int, val seconds: Int) {
    companion object {
        fun of(ms: Long): Span {
            val total = (ms.coerceAtLeast(0)) / 1000
            return Span(
                days = total / 86_400,
                hours = ((total / 3600) % 24).toInt(),
                minutes = ((total / 60) % 60).toInt(),
                seconds = (total % 60).toInt(),
            )
        }
    }
}

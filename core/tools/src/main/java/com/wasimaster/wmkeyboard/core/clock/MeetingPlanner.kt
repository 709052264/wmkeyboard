package com.wasimaster.wmkeyboard.core.clock

import java.util.Calendar
import java.util.TimeZone

/** How a stretch of the day suits a meeting for the people in one place. */
enum class DayPart { WORK, AWAKE, NIGHT }

/**
 * A day split into half-hour slots, and for each slot how it suits every place
 * on the list. Half hours because a fifth of the world's population lives on
 * a half-hour offset (India alone), and an hour grid would show them a meeting
 * at "9" that is really at 9:30.
 */
data class PlannerDay(
    /** The instant each slot starts, [SLOTS] of them. */
    val slots: List<Long>,
    /** Per place, per slot. */
    val parts: List<List<DayPart>>,
) {
    /** Slots where every place is at work. */
    val overlap: List<Int> get() = slots.indices.filter { i -> parts.all { it[i] == DayPart.WORK } }

    /**
     * The slot that suits the most people: everyone at work if possible,
     * otherwise the most at work with nobody asleep, otherwise the fewest
     * asleep. Earliest wins a tie.
     */
    val best: Int?
        get() {
            if (slots.isEmpty() || parts.isEmpty()) return null
            return slots.indices.maxByOrNull { i ->
                val work = parts.count { it[i] == DayPart.WORK }
                val night = parts.count { it[i] == DayPart.NIGHT }
                // Lexicographic: no sleepers first, then most at work. A slot
                // index tie-breaker keeps the earliest of equals (maxBy keeps
                // the first maximum).
                (if (night == 0) 1_000 else 0) + work * 10 - night
            }
        }

    companion object {
        const val SLOTS = 48
        const val SLOT_MS = 30 * 60_000L
    }
}

/**
 * The meeting planner's arithmetic: the user's day in [home], and what each
 * half hour of it is in every other place.
 */
object MeetingPlanner {

    /**
     * [day] is any instant on the day to plan, read in [home]. Work and awake
     * hours are minutes past local midnight; work hours that wrap past midnight
     * (a night shift, 22:00 to 06:00) are allowed.
     */
    @Suppress("detekt.LongParameterList")
    fun plan(
        day: Long,
        home: TimeZone,
        zones: List<TimeZone>,
        workStart: Int = 9 * 60,
        workEnd: Int = 17 * 60,
        awakeStart: Int = 7 * 60,
        awakeEnd: Int = 23 * 60,
    ): PlannerDay {
        val start = startOfDay(day, home)
        val slots = List(PlannerDay.SLOTS) { start + it * PlannerDay.SLOT_MS }
        val parts = zones.map { z ->
            slots.map { at ->
                val m = minuteOfDay(at, z)
                when {
                    within(m, workStart, workEnd) -> DayPart.WORK
                    within(m, awakeStart, awakeEnd) -> DayPart.AWAKE
                    else -> DayPart.NIGHT
                }
            }
        }
        return PlannerDay(slots, parts)
    }

    /** Local midnight of the day [at] falls on, in [zone]. */
    fun startOfDay(at: Long, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = at
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    fun minuteOfDay(at: Long, zone: TimeZone): Int =
        Calendar.getInstance(zone).run {
            timeInMillis = at
            get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
        }

    /** [m] in [start, end), where the window may wrap past midnight. */
    fun within(m: Int, start: Int, end: Int): Boolean =
        if (start <= end) m in start until end else m >= start || m < end
}

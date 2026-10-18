package com.wasimaster.wmkeyboard.core.tools.calendar

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/** How a repeating event stops. */
sealed interface RepeatEnd {
    data object Never : RepeatEnd

    /** The last day an occurrence may fall on, inclusive. */
    data class Until(val date: EventDate) : RepeatEnd

    data class Count(val times: Int) : RepeatEnd
}

/**
 * "The 2nd Tuesday" ([ordinal] 2, [weekday] 2) or "the last Friday"
 * ([ordinal] -1). Weekdays count from Sunday = 0, as [EventDate.dayOfWeek].
 */
data class NthWeekday(val ordinal: Int, val weekday: Int) {
    companion object {
        /** Which weekday of its month [date] is, counted from the front. */
        fun of(date: EventDate): NthWeekday = NthWeekday((date.day - 1) / 7 + 1, date.dayOfWeek)

        /** [date] as "the last X" when no later X fits in its month, else null. */
        fun lastOf(date: EventDate): NthWeekday? =
            if (date.day + 7 > date.monthLength) NthWeekday(-1, date.dayOfWeek) else null
    }
}

/**
 * A repeat rule, as much of RFC 5545's RRULE as the composer edits.
 *
 * [weekdays] is a bit set, Sunday in bit 0 to Saturday in bit 6, used by
 * WEEKLY. MONTHLY repeats on the start's day of the month unless [nth] says
 * a weekday. DAILY and YEARLY follow the start date.
 *
 * A rule read from the provider that this model cannot hold comes back from
 * [RRule.decode] with [custom] set to the rule text. It is shown as "custom"
 * and written back verbatim while the user leaves it alone.
 */
data class Recurrence(
    val frequency: Frequency,
    val interval: Int = 1,
    val weekdays: Int = 0,
    val nth: NthWeekday? = null,
    val end: RepeatEnd = RepeatEnd.Never,
    val custom: String? = null,
) {
    val isCustom: Boolean get() = custom != null

    /** Whether this is the Monday-to-Friday weekly rule. */
    val isWorkdays: Boolean get() = frequency == Frequency.WEEKLY && interval == 1 && weekdays == WORKDAYS

    /**
     * The rule after the start moved from [from] to [to]: a weekly rule on
     * exactly the old weekday, or a monthly one on its nth weekday, follows
     * the start. Anything else stays as the user set it.
     */
    fun followingStart(from: EventDate, to: EventDate): Recurrence {
        if (custom != null || from == to) return this
        return when {
            frequency == Frequency.WEEKLY && weekdays == bit(from.dayOfWeek) -> copy(weekdays = bit(to.dayOfWeek))
            frequency == Frequency.MONTHLY && nth != null ->
                copy(nth = if (nth.ordinal < 0) NthWeekday.lastOf(to) ?: NthWeekday.of(to) else NthWeekday.of(to))
            else -> this
        }
    }

    companion object {
        const val WORKDAYS = 0b011_1110

        fun bit(weekday: Int): Int = 1 shl weekday

        fun has(weekdays: Int, weekday: Int): Boolean = weekdays and bit(weekday) != 0

        fun daily() = Recurrence(Frequency.DAILY)

        fun weekly(start: EventDate) = Recurrence(Frequency.WEEKLY, weekdays = bit(start.dayOfWeek))

        fun workdays() = Recurrence(Frequency.WEEKLY, weekdays = WORKDAYS)

        fun monthly() = Recurrence(Frequency.MONTHLY)

        fun monthlyOnWeekday(start: EventDate, last: Boolean = false) =
            Recurrence(Frequency.MONTHLY, nth = (if (last) NthWeekday.lastOf(start) else null) ?: NthWeekday.of(start))

        fun yearly() = Recurrence(Frequency.YEARLY)
    }
}

/**
 * [Recurrence] to and from the RRULE text `CalendarContract.Events.RRULE`
 * holds, plus the two string surgeries a split series needs.
 */
object RRule {

    private val DAY_CODES = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")

    /** The rule text for [r]; a custom rule is its own text. */
    fun encode(r: Recurrence, allDay: Boolean, zone: TimeZone): String {
        r.custom?.let { return it }
        val parts = mutableListOf("FREQ=${r.frequency.name}")
        if (r.interval > 1) parts += "INTERVAL=${r.interval}"
        when (r.frequency) {
            Frequency.WEEKLY -> if (r.weekdays != 0) {
                parts += "BYDAY=" + (0 until 7).filter { Recurrence.has(r.weekdays, it) }.joinToString(",") { DAY_CODES[it] }
            }
            Frequency.MONTHLY -> r.nth?.let { parts += "BYDAY=${it.ordinal}${DAY_CODES[it.weekday]}" }
            Frequency.DAILY, Frequency.YEARLY -> Unit
        }
        when (val end = r.end) {
            RepeatEnd.Never -> Unit
            is RepeatEnd.Count -> parts += "COUNT=${end.times.coerceAtLeast(1)}"
            is RepeatEnd.Until -> parts += "UNTIL=" + untilOfDay(end.date, allDay, zone)
        }
        return parts.joinToString(";")
    }

    /**
     * [rule] read against the event's first day [start] and [zone] (an
     * UNTIL with a time names a local day only once it is put in a zone).
     * Null for an empty rule; a rule with parts this model does not carry
     * comes back as [Recurrence.custom].
     */
    @Suppress("detekt.CyclomaticComplexMethod", "detekt.ReturnCount")
    fun decode(rule: String?, start: EventDate, zone: TimeZone): Recurrence? {
        val text = rule?.trim()?.removePrefix("RRULE:")?.trim().orEmpty()
        if (text.isEmpty()) return null
        val custom = Recurrence(Frequency.DAILY, custom = text)
        val map = LinkedHashMap<String, String>()
        for (part in text.split(';')) {
            if (part.isBlank()) continue
            val eq = part.indexOf('=')
            if (eq <= 0) return custom
            map[part.substring(0, eq).trim().uppercase(Locale.ROOT)] = part.substring(eq + 1).trim().uppercase(Locale.ROOT)
        }
        val frequency = runCatching { Frequency.valueOf(map.remove("FREQ").orEmpty()) }.getOrNull() ?: return custom
        val interval = map.remove("INTERVAL")?.let { it.toIntOrNull() ?: return custom } ?: 1
        if (interval < 1) return custom
        map.remove("WKST")
        val end: RepeatEnd = when {
            map.containsKey("COUNT") && map.containsKey("UNTIL") -> return custom
            map.containsKey("COUNT") -> RepeatEnd.Count(map.remove("COUNT")!!.toIntOrNull()?.takeIf { it > 0 } ?: return custom)
            map.containsKey("UNTIL") -> RepeatEnd.Until(untilDate(map.remove("UNTIL")!!, zone) ?: return custom)
            else -> RepeatEnd.Never
        }
        val byDay = map.remove("BYDAY")
        val byMonthDay = map.remove("BYMONTHDAY")
        val byMonth = map.remove("BYMONTH")
        val bySetPos = map.remove("BYSETPOS")
        if (map.isNotEmpty()) return custom
        return when (frequency) {
            Frequency.DAILY -> {
                if (byDay != null || byMonthDay != null || byMonth != null || bySetPos != null) return custom
                Recurrence(Frequency.DAILY, interval, end = end)
            }
            Frequency.WEEKLY -> {
                if (byMonthDay != null || byMonth != null || bySetPos != null) return custom
                val days = if (byDay == null) {
                    Recurrence.bit(start.dayOfWeek)
                } else {
                    var bits = 0
                    for (code in byDay.split(',')) {
                        val i = DAY_CODES.indexOf(code.trim())
                        if (i < 0) return custom
                        bits = bits or Recurrence.bit(i)
                    }
                    bits
                }
                Recurrence(Frequency.WEEKLY, interval, weekdays = days, end = end)
            }
            Frequency.MONTHLY -> {
                if (byMonth != null) return custom
                when {
                    byDay != null -> {
                        if (byMonthDay != null) return custom
                        val nth = nthOf(byDay, bySetPos) ?: return custom
                        Recurrence(Frequency.MONTHLY, interval, nth = nth, end = end)
                    }
                    bySetPos != null -> custom
                    byMonthDay != null && byMonthDay.toIntOrNull() != start.day -> custom
                    else -> Recurrence(Frequency.MONTHLY, interval, end = end)
                }
            }
            Frequency.YEARLY -> {
                if (byDay != null || bySetPos != null) return custom
                if (byMonth != null && byMonth.toIntOrNull() != start.month) return custom
                if (byMonthDay != null && byMonthDay.toIntOrNull() != start.day) return custom
                Recurrence(Frequency.YEARLY, interval, end = end)
            }
        }
    }

    /**
     * [rule] cut so its last occurrence is before [beforeMillis]: any COUNT
     * or UNTIL replaced by an UNTIL one second (one day, all day) earlier.
     * Works on custom rules too, since it only touches those two parts.
     */
    fun truncated(rule: String, beforeMillis: Long, allDay: Boolean): String {
        val until = if (allDay) {
            format(beforeMillis - EventDraft.DAY_MS, "yyyyMMdd")
        } else {
            format(beforeMillis - 1000L, "yyyyMMdd'T'HHmmss'Z'")
        }
        return withoutEnd(rule) + ";UNTIL=" + until
    }

    /** [rule] with its COUNT set to [count]; unchanged when it has no COUNT. */
    fun withCount(rule: String, count: Int): String {
        val parts = parts(rule)
        if (parts.none { it.startsWith("COUNT=", ignoreCase = true) }) return rule
        return parts.joinToString(";") { if (it.startsWith("COUNT=", ignoreCase = true)) "COUNT=${count.coerceAtLeast(1)}" else it }
    }

    /** The COUNT a rule carries, or null. */
    fun count(rule: String?): Int? =
        rule?.let { parts(it).firstOrNull { p -> p.startsWith("COUNT=", ignoreCase = true) }?.substringAfter('=')?.toIntOrNull() }

    private fun withoutEnd(rule: String): String =
        parts(rule).filterNot { it.startsWith("COUNT=", ignoreCase = true) || it.startsWith("UNTIL=", ignoreCase = true) }
            .joinToString(";")

    private fun parts(rule: String): List<String> =
        rule.trim().removePrefix("RRULE:").split(';').map { it.trim() }.filter { it.isNotEmpty() }

    /** "2TU", "-1FR", or "TU" with BYSETPOS=2. Only one weekday. */
    private fun nthOf(byDay: String, bySetPos: String?): NthWeekday? {
        if (byDay.contains(',')) return null
        val match = Regex("""([+-]?\d)?(SU|MO|TU|WE|TH|FR|SA)""").matchEntire(byDay) ?: return null
        val weekday = DAY_CODES.indexOf(match.groupValues[2])
        val ordinal = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()
            ?: bySetPos?.toIntOrNull()
            ?: return null
        if (bySetPos != null && match.groupValues[1].isNotEmpty()) return null
        if (ordinal == 0 || ordinal !in -1..5) return null
        return NthWeekday(if (ordinal == 5) -1 else ordinal, weekday)
    }

    /** The UNTIL text that ends a rule on [date]: the date itself all day, else that day's last second in UTC. */
    private fun untilOfDay(date: EventDate, allDay: Boolean, zone: TimeZone): String {
        if (allDay) return "%04d%02d%02d".format(Locale.ROOT, date.year, date.month, date.day)
        val endOfDay = EventTimes.millis(date.plusDays(1), 0, zone) - 1000L
        return format(endOfDay, "yyyyMMdd'T'HHmmss'Z'")
    }

    private fun untilDate(text: String, zone: TimeZone): EventDate? {
        val m = Regex("""(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})(Z)?)?""").matchEntire(text) ?: return null
        val (y, mo, d, h, mi, s, z) = m.destructured
        if (h.isEmpty()) return EventDate(y.toInt(), mo.toInt(), d.toInt())
        val cal = Calendar.getInstance(if (z.isNotEmpty()) EventDraft.UTC else zone).apply {
            clear()
            set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        }
        return EventDate.of(cal.timeInMillis, zone)
    }

    private fun format(millis: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = EventDraft.UTC }.format(Date(millis))
}

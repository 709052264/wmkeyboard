package com.wasimaster.wmkeyboard.core.tools.calendar

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Times as the calendar provider stores them. */
object EventTimes {

    /** [date] at [minute] past midnight in [zone], as epoch millis. */
    fun millis(date: EventDate, minute: Int, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        clear()
        set(date.year, date.month - 1, date.day, minute / 60, minute % 60, 0)
    }.timeInMillis

    /** Minutes past midnight [millis] is at in [zone]. */
    fun minuteOfDay(millis: Long, zone: TimeZone): Int = Calendar.getInstance(zone).let {
        it.timeInMillis = millis
        it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
    }

    /**
     * A length as RFC 5545 DURATION text: whole days all day (`P1D`), seconds
     * otherwise (`P3600S`), which is what the platform's own calendar writes.
     */
    fun duration(lengthMillis: Long, allDay: Boolean): String =
        if (allDay) {
            "P${(lengthMillis / EventDraft.DAY_MS).coerceAtLeast(1)}D"
        } else {
            "P${(lengthMillis / 1000L).coerceAtLeast(60)}S"
        }

    /** DURATION text (`P1D`, `P3600S`, `PT1H30M`, `P1W`) in millis; null when it does not read. */
    fun parseDuration(text: String?): Long? {
        val m = DURATION.matchEntire(text?.trim()?.uppercase(Locale.ROOT).orEmpty()) ?: return null
        val (sign, w, d, h, mi, s) = m.destructured
        if (listOf(w, d, h, mi, s).all { it.isEmpty() }) return null
        val seconds = (w.toLongOrNull() ?: 0) * 7 * 86_400 + (d.toLongOrNull() ?: 0) * 86_400 +
            (h.toLongOrNull() ?: 0) * 3_600 + (mi.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
        return seconds * 1000L * if (sign == "-") -1 else 1
    }

    private val DURATION = Regex("""([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")
}

/** What one write to `CalendarContract.Events` carries: every column the composer owns. */
data class EventRow(
    val calendarId: Long,
    val title: String,
    val location: String,
    val notes: String,
    val dtStart: Long,
    /** Set for a one-off event; null for a repeating one, which has [duration]. */
    val dtEnd: Long?,
    val duration: String?,
    val rrule: String?,
    val timezone: String,
    val allDay: Boolean,
    val availability: Availability,
)

/** Which occurrences of a repeating event a change is for. */
enum class EditScope { THIS, FOLLOWING, ALL }

/**
 * Where the opened occurrence sits in its series.
 *
 * [masterId]'s row carries the rule. [exceptionId] is set when the opened
 * row is itself an exception (a single occurrence already changed), and
 * [originalInstanceTime] is when that occurrence was due before any change:
 * the exception's ORIGINAL_INSTANCE_TIME, or else the instance's own start.
 */
data class SeriesInfo(
    val masterId: Long,
    val masterStart: Long,
    val masterStartDate: EventDate,
    val rule: String,
    val allDay: Boolean,
    val exceptionId: Long?,
    val originalInstanceTime: Long,
    /** The opened occurrence's date, as the draft was filled from it. */
    val occurrenceDate: EventDate,
)

/** One change to the provider, in order. The Android side runs them. */
sealed interface CalendarOp {
    data class Insert(val row: EventRow, val reminders: List<Int>, val guests: List<Guest>) : CalendarOp

    /** A full rewrite of [eventId]'s columns, its reminders replaced and its guests brought in line. */
    data class Update(val eventId: Long, val row: EventRow, val reminders: List<Int>, val guests: List<Guest>) : CalendarOp

    /** Only the rule changes: how a series is cut short. */
    data class SetRule(val eventId: Long, val rrule: String) : CalendarOp

    data class Delete(val eventId: Long) : CalendarOp

    /** An exception row marked cancelled: that one occurrence is gone. */
    data class Cancel(val eventId: Long) : CalendarOp

    /**
     * A new exception to [masterId] for the occurrence due at
     * [originalInstanceTime]: changed to [row], or cancelled when [row] is null.
     */
    data class InsertException(
        val masterId: Long,
        val originalInstanceTime: Long,
        val row: EventRow?,
        val reminders: List<Int> = emptyList(),
        val guests: List<Guest> = emptyList(),
    ) : CalendarOp
}

/**
 * Turns a draft and a scope into [CalendarOp]s. Pure, so every edge of
 * splitting a series is tested without a provider:
 *
 * - THIS changes one occurrence through an exception row (or the exception
 *   that is already there), and deletes one by cancelling it, never by
 *   deleting an exception, which would bring the occurrence back.
 * - FOLLOWING cuts the series off before the occurrence and, for an edit,
 *   starts a new series there. A COUNT carries over less the occurrences
 *   that came before. On the first occurrence it is the same as ALL.
 * - ALL rewrites the series. A change of time or day on the opened
 *   occurrence moves the series' own start by the same amount, so a series
 *   that began in March still begins in March.
 */
object EventWrites {

    fun create(draft: EventDraft): List<CalendarOp> =
        listOf(CalendarOp.Insert(row(draft), draft.reminders, draft.guests))

    /** A changed event. [series] is null for a one-off; [occurrencesBefore] matters only for FOLLOWING. */
    fun edit(draft: EventDraft, series: SeriesInfo?, scope: EditScope, occurrencesBefore: Int = 0): List<CalendarOp> {
        val id = draft.eventId ?: return create(draft)
        if (series == null) return listOf(CalendarOp.Update(id, row(draft), draft.reminders, draft.guests))
        return when (effective(scope, series)) {
            EditScope.THIS -> {
                val single = row(draft, single = true)
                series.exceptionId?.let { listOf(CalendarOp.Update(it, single, draft.reminders, draft.guests)) }
                    ?: listOf(CalendarOp.InsertException(series.masterId, series.originalInstanceTime, single, draft.reminders, draft.guests))
            }
            EditScope.FOLLOWING -> {
                val cut = CalendarOp.SetRule(series.masterId, RRule.truncated(series.rule, series.originalInstanceTime, series.allDay))
                val next = following(draft, occurrencesBefore)
                listOf(cut, CalendarOp.Insert(row(next), next.reminders, next.guests))
            }
            EditScope.ALL -> {
                val moved = movedSeries(draft, series)
                listOf(CalendarOp.Update(series.masterId, row(moved), moved.reminders, moved.guests))
            }
        }
    }

    /** A deleted event, or the occurrences [scope] names. */
    fun delete(eventId: Long, series: SeriesInfo?, scope: EditScope): List<CalendarOp> {
        if (series == null) return listOf(CalendarOp.Delete(eventId))
        return when (effective(scope, series)) {
            EditScope.THIS -> series.exceptionId?.let { listOf(CalendarOp.Cancel(it)) }
                ?: listOf(CalendarOp.InsertException(series.masterId, series.originalInstanceTime, null))
            EditScope.FOLLOWING ->
                listOf(CalendarOp.SetRule(series.masterId, RRule.truncated(series.rule, series.originalInstanceTime, series.allDay)))
            EditScope.ALL -> listOf(CalendarOp.Delete(series.masterId))
        }
    }

    /** FOLLOWING from the first occurrence leaves nothing before it to keep. */
    private fun effective(scope: EditScope, series: SeriesInfo): EditScope =
        if (scope == EditScope.FOLLOWING && series.originalInstanceTime <= series.masterStart) EditScope.ALL else scope

    /** The new series a FOLLOWING edit starts: the draft as is, its COUNT less what already happened. */
    private fun following(draft: EventDraft, before: Int): EventDraft {
        val r = draft.recurrence ?: return draft
        val end = r.end
        val originalCount = RRule.count(draft.originalRule)
        return when {
            draft.ruleUnchanged && originalCount != null ->
                draft.copy(originalRule = RRule.withCount(draft.originalRule!!, (originalCount - before).coerceAtLeast(1)))
            end is RepeatEnd.Count && draft.originalRecurrence?.end == end ->
                draft.copy(recurrence = r.copy(end = RepeatEnd.Count((end.times - before).coerceAtLeast(1))))
            else -> draft
        }
    }

    /**
     * The draft with its start put back on the series' first day, shifted by
     * as many days as the occurrence was. The rule already follows the new
     * weekday (see [EventDraft.withStart]), and the series' first day moves
     * by the same number of days, so the two still agree.
     */
    private fun movedSeries(draft: EventDraft, series: SeriesInfo): EventDraft {
        val newStart = series.masterStartDate.plusDays(series.occurrenceDate.daysUntil(draft.startDate))
        val atStart = draft.copy(startDate = newStart)
        return if (draft.allDay) {
            atStart.copy(endDate = newStart.plusDays(draft.startDate.daysUntil(draft.endDate)))
        } else {
            atStart.withLength(draft.lengthMinutes)
        }
    }

    /** The provider row for [d]; [single] drops the rule, as an exception row must. */
    fun row(d: EventDraft, single: Boolean = false): EventRow {
        val rule = if (single) null else ruleOf(d)
        val start = d.startMillis
        return EventRow(
            calendarId = d.calendarId,
            title = d.title.trim(),
            location = d.location.trim(),
            notes = d.notes.trim(),
            dtStart = start,
            dtEnd = if (rule == null) start + d.lengthMillis else null,
            duration = rule?.let { EventTimes.duration(d.lengthMillis, d.allDay) },
            rrule = rule,
            timezone = if (d.allDay) "UTC" else d.zoneId,
            allDay = d.allDay,
            availability = d.availability,
        )
    }

    private fun ruleOf(d: EventDraft): String? {
        val r = d.recurrence ?: return null
        return if (d.ruleUnchanged) d.originalRule else RRule.encode(r, d.allDay, TimeZone.getTimeZone(d.zoneId))
    }
}

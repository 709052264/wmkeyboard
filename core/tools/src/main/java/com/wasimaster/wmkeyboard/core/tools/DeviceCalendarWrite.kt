package com.wasimaster.wmkeyboard.core.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.provider.ContactsContract
import com.wasimaster.wmkeyboard.core.tools.calendar.Availability
import com.wasimaster.wmkeyboard.core.tools.calendar.CalendarOp
import com.wasimaster.wmkeyboard.core.tools.calendar.EventDate
import com.wasimaster.wmkeyboard.core.tools.calendar.EventDraft
import com.wasimaster.wmkeyboard.core.tools.calendar.EventRow
import com.wasimaster.wmkeyboard.core.tools.calendar.EventTimes
import com.wasimaster.wmkeyboard.core.tools.calendar.Guest
import com.wasimaster.wmkeyboard.core.tools.calendar.RRule
import com.wasimaster.wmkeyboard.core.tools.calendar.SeriesInfo
import java.util.TimeZone

/** One guest row as the provider holds it. */
data class StoredGuest(
    val email: String,
    val name: String,
    /** `Attendees.ATTENDEE_STATUS_*`. */
    val status: Int,
    val organizer: Boolean,
)

/**
 * One event read whole, for the detail sheet and the editor: its own row,
 * its reminders and guests, and which occurrence was opened
 * ([instanceBegin]..[instanceEnd]).
 */
data class StoredEvent(
    val id: Long,
    val calendarId: Long,
    val calendarName: String,
    /** The event's own colour, or else its calendar's (ARGB); 0 for none. */
    val color: Int,
    val title: String,
    val location: String,
    val notes: String,
    val dtStart: Long,
    val dtEnd: Long?,
    val duration: String?,
    val rrule: String?,
    val allDay: Boolean,
    val timezone: String,
    val availability: Availability,
    /** Set on an exception: the series it belongs to, and when it was due. */
    val originalId: Long?,
    val originalInstanceTime: Long?,
    val reminders: List<Int>,
    val guests: List<StoredGuest>,
    val ownerAccount: String,
    val canModify: Boolean,
    val instanceBegin: Long,
    val instanceEnd: Long,
) {
    val recurring: Boolean get() = rrule != null || originalId != null

    /** The zone its wall-clock times are in: UTC all day, else its own. */
    val zone: TimeZone get() = if (allDay) EventDraft.UTC else TimeZone.getTimeZone(timezone.ifBlank { TimeZone.getDefault().id })

    /** Everyone invited but the calendar's owner, who is not a guest at their own event. */
    val invited: List<StoredGuest>
        get() = guests.filter { !it.organizer && !it.email.equals(ownerAccount, ignoreCase = true) }
}

/** What running a list of [CalendarOp]s came to. */
data class CalendarWriteResult(val ok: Boolean, val insertedId: Long? = null)

/**
 * The calendar tool's writes (and the reads the editor needs), through
 * [CalendarContract]. Everything needs WRITE_CALENDAR, and returns a failed
 * result rather than throwing when a grant is missing or a provider says no.
 *
 * The provider has no transactions across the event and its children, and an
 * exception row can only be inserted through its own URI, so the ops run one
 * after another and the first failure stops the rest.
 */
object DeviceCalendarWrite {

    private val EVENT_PROJECTION = arrayOf(
        Events._ID, // 0
        Events.CALENDAR_ID, // 1
        Events.TITLE, // 2
        Events.EVENT_LOCATION, // 3
        Events.DESCRIPTION, // 4
        Events.DTSTART, // 5
        Events.DTEND, // 6
        Events.DURATION, // 7
        Events.RRULE, // 8
        Events.ALL_DAY, // 9
        Events.EVENT_TIMEZONE, // 10
        Events.AVAILABILITY, // 11
        Events.ORIGINAL_ID, // 12
        Events.ORIGINAL_INSTANCE_TIME, // 13
        Events.CALENDAR_DISPLAY_NAME, // 14
        Events.DISPLAY_COLOR, // 15
        Events.OWNER_ACCOUNT, // 16
        Events.CALENDAR_ACCESS_LEVEL, // 17
        Events.GUESTS_CAN_MODIFY, // 18
        Events.ORGANIZER, // 19
    )

    /** [eventId] read whole, as the occurrence [instanceBegin]..[instanceEnd]; null when it is gone or unreadable. */
    fun load(context: Context, eventId: Long, instanceBegin: Long, instanceEnd: Long): StoredEvent? = runCatching {
        val cr = context.contentResolver
        val row = cr.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), EVENT_PROJECTION, null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) return@use null
                StoredEvent(
                    id = c.getLong(0),
                    calendarId = c.getLong(1),
                    calendarName = c.getString(14).orEmpty(),
                    color = c.getInt(15),
                    title = c.getString(2).orEmpty(),
                    location = c.getString(3).orEmpty(),
                    notes = c.getString(4).orEmpty(),
                    dtStart = c.getLong(5),
                    dtEnd = if (c.isNull(6)) null else c.getLong(6),
                    duration = c.getString(7),
                    rrule = c.getString(8)?.takeIf { it.isNotBlank() },
                    allDay = c.getInt(9) != 0,
                    timezone = c.getString(10).orEmpty(),
                    availability = if (c.getInt(11) == Events.AVAILABILITY_FREE) Availability.FREE else Availability.BUSY,
                    originalId = if (c.isNull(12)) null else c.getLong(12),
                    originalInstanceTime = if (c.isNull(13)) null else c.getLong(13),
                    reminders = emptyList(),
                    guests = emptyList(),
                    ownerAccount = c.getString(16).orEmpty(),
                    canModify = DeviceCalendar.canModify(c.getInt(17), c.getInt(18) != 0, c.getString(19), c.getString(16)),
                    instanceBegin = instanceBegin,
                    instanceEnd = instanceEnd,
                )
            } ?: return@runCatching null
        row.copy(reminders = reminders(context, eventId), guests = guests(context, eventId))
    }.getOrNull()

    /**
     * Where [event] sits in its series, or null for a one-off (or an
     * exception whose series is gone, which then edits as a one-off).
     */
    fun series(context: Context, event: StoredEvent): SeriesInfo? {
        val master = when {
            event.originalId != null -> load(context, event.originalId, event.instanceBegin, event.instanceEnd) ?: return null
            event.rrule != null -> event
            else -> return null
        }
        val rule = master.rrule ?: return null
        return SeriesInfo(
            masterId = master.id,
            masterStart = master.dtStart,
            masterStartDate = EventDate.of(master.dtStart, master.zone),
            rule = rule,
            allDay = master.allDay,
            exceptionId = event.id.takeIf { event.originalId != null },
            originalInstanceTime = event.originalInstanceTime ?: event.instanceBegin,
            occurrenceDate = EventDate.of(event.instanceBegin, event.zone),
        )
    }

    /** [event] as the editor's draft, its series' rule riding along so an untouched one is written back as it was. */
    fun draftOf(event: StoredEvent, series: SeriesInfo?): EventDraft {
        val zone = event.zone
        val begin = event.instanceBegin
        val end = event.instanceEnd.takeIf { it > begin }
            ?: event.dtEnd
            ?: (begin + (EventTimes.parseDuration(event.duration) ?: if (event.allDay) EventDraft.DAY_MS else 3_600_000L))
        val startDate = EventDate.of(begin, zone)
        val rule = series?.rule
        val recurrence = RRule.decode(rule, startDate, zone)
        val base = EventDraft(
            eventId = event.id,
            calendarId = event.calendarId,
            title = event.title,
            location = event.location,
            notes = event.notes,
            allDay = event.allDay,
            startDate = startDate,
            startMinute = if (event.allDay) 9 * 60 else EventTimes.minuteOfDay(begin, zone),
            zoneId = if (event.allDay) TimeZone.getDefault().id else zone.id,
            recurrence = recurrence,
            originalRule = rule,
            originalRecurrence = recurrence,
            reminders = event.reminders.sorted(),
            availability = event.availability,
            guests = event.invited.map { Guest(it.email, it.name) },
        )
        return if (event.allDay) {
            // An all-day end is the midnight after its last day.
            val last = EventDate.of(end, zone).plusDays(-1)
            base.copy(endDate = if (last < startDate) startDate else last)
        } else {
            base.copy(endDate = EventDate.of(end, zone), endMinute = EventTimes.minuteOfDay(end, zone))
        }
    }

    /**
     * How many occurrences of [masterId] came before [before], for a COUNT
     * carried into the second half of a split series.
     */
    fun occurrencesBefore(context: Context, masterId: Long, masterStart: Long, before: Long): Int {
        if (before <= masterStart) return 0
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(masterStart.toString())
            .appendPath((before - 1).toString())
            .build()
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(CalendarContract.Instances.BEGIN),
                "${CalendarContract.Instances.EVENT_ID} = ?", arrayOf(masterId.toString()), null,
            )?.use { it.count } ?: 0
        }.getOrDefault(0)
    }

    /** Runs [ops] in order, stopping at the first that fails. */
    fun apply(context: Context, ops: List<CalendarOp>): CalendarWriteResult {
        if (!DeviceCalendar.canWrite(context)) return CalendarWriteResult(false)
        var inserted: Long? = null
        for (op in ops) {
            val ok = runCatching {
                when (op) {
                    is CalendarOp.Insert -> {
                        val id = insert(context, Events.CONTENT_URI, values(op.row, op.reminders, op.guests)) ?: return@runCatching false
                        inserted = id
                        setReminders(context, id, op.reminders)
                        setGuests(context, id, op.guests)
                        true
                    }
                    is CalendarOp.Update -> {
                        update(context, op.eventId, values(op.row, op.reminders, op.guests)) &&
                            setReminders(context, op.eventId, op.reminders) &&
                            setGuests(context, op.eventId, op.guests)
                    }
                    is CalendarOp.SetRule -> update(context, op.eventId, ContentValues().apply { put(Events.RRULE, op.rrule) })
                    is CalendarOp.Delete ->
                        context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, op.eventId), null, null) > 0
                    is CalendarOp.Cancel ->
                        update(context, op.eventId, ContentValues().apply { put(Events.STATUS, Events.STATUS_CANCELED) })
                    is CalendarOp.InsertException -> {
                        val values = ContentValues().apply { put(Events.ORIGINAL_INSTANCE_TIME, op.originalInstanceTime) }
                        val row = op.row
                        if (row == null) {
                            values.put(Events.STATUS, Events.STATUS_CANCELED)
                        } else {
                            values.putAll(exceptionValues(row))
                        }
                        val uri = Uri.withAppendedPath(Events.CONTENT_EXCEPTION_URI, op.masterId.toString())
                        val id = insert(context, uri, values) ?: return@runCatching false
                        inserted = id
                        if (row != null) {
                            setReminders(context, id, op.reminders)
                            setGuests(context, id, op.guests)
                        }
                        true
                    }
                }
            }.getOrDefault(false)
            if (!ok) return CalendarWriteResult(false, inserted)
        }
        return CalendarWriteResult(true, inserted)
    }

    /**
     * Contacts whose name or address matches [query], at most [limit], for
     * the guest row. Empty without READ_CONTACTS.
     */
    fun guestSuggestions(context: Context, query: String, limit: Int = 6): List<Guest> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI, Uri.encode(q))
        val out = LinkedHashMap<String, Guest>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val email = c.getString(0)?.trim().orEmpty()
                    if ('@' !in email) continue
                    out.putIfAbsent(email.lowercase(), Guest(email, c.getString(1).orEmpty()))
                }
            }
        }
        return out.values.toList()
    }

    private fun reminders(context: Context, eventId: Long): List<Int> = runCatching {
        Reminders.query(context.contentResolver, eventId, arrayOf(Reminders.MINUTES))?.use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0)) }
        }.orEmpty().filter { it >= 0 }.distinct()
    }.getOrDefault(emptyList())

    private fun guests(context: Context, eventId: Long): List<StoredGuest> = runCatching {
        Attendees.query(
            context.contentResolver, eventId,
            arrayOf(Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_RELATIONSHIP),
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val email = c.getString(0)?.trim().orEmpty()
                    if (email.isEmpty()) continue
                    add(StoredGuest(email, c.getString(1).orEmpty(), c.getInt(2), c.getInt(3) == Attendees.RELATIONSHIP_ORGANIZER))
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun values(row: EventRow, reminders: List<Int>, guests: List<Guest>) = ContentValues().apply {
        put(Events.CALENDAR_ID, row.calendarId)
        put(Events.TITLE, row.title)
        put(Events.EVENT_LOCATION, row.location)
        put(Events.DESCRIPTION, row.notes)
        put(Events.DTSTART, row.dtStart)
        if (row.dtEnd != null) put(Events.DTEND, row.dtEnd) else putNull(Events.DTEND)
        if (row.duration != null) put(Events.DURATION, row.duration) else putNull(Events.DURATION)
        if (row.rrule != null) put(Events.RRULE, row.rrule) else putNull(Events.RRULE)
        put(Events.ALL_DAY, if (row.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, row.timezone)
        put(Events.AVAILABILITY, if (row.availability == Availability.FREE) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY)
        put(Events.HAS_ALARM, if (reminders.isEmpty()) 0 else 1)
        put(Events.HAS_ATTENDEE_DATA, if (guests.isEmpty()) 0 else 1)
    }

    /** An exception is one occurrence: its own start and end, never a rule. */
    private fun exceptionValues(row: EventRow) = ContentValues().apply {
        put(Events.TITLE, row.title)
        put(Events.EVENT_LOCATION, row.location)
        put(Events.DESCRIPTION, row.notes)
        put(Events.DTSTART, row.dtStart)
        put(Events.DTEND, row.dtEnd ?: row.dtStart)
        put(Events.ALL_DAY, if (row.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, row.timezone)
        put(Events.AVAILABILITY, if (row.availability == Availability.FREE) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY)
        put(Events.STATUS, Events.STATUS_CONFIRMED)
    }

    private fun insert(context: Context, uri: Uri, values: ContentValues): Long? =
        context.contentResolver.insert(uri, values)?.lastPathSegment?.toLongOrNull()

    private fun update(context: Context, eventId: Long, values: ContentValues): Boolean =
        context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values, null, null) > 0

    /** [eventId]'s reminders made exactly [minutes]. */
    private fun setReminders(context: Context, eventId: Long, minutes: List<Int>): Boolean {
        val cr = context.contentResolver
        cr.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        for (m in minutes.distinct()) {
            cr.insert(
                Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(Reminders.EVENT_ID, eventId)
                    put(Reminders.MINUTES, m)
                    put(Reminders.METHOD, Reminders.METHOD_ALERT)
                },
            ) ?: return false
        }
        return true
    }

    /**
     * [eventId]'s guests brought in line with [wanted]: the ones no longer
     * wanted removed and the new ones added, the rest left alone, so nobody
     * already invited is invited again. The organiser's own row is never touched.
     */
    private fun setGuests(context: Context, eventId: Long, wanted: List<Guest>): Boolean {
        val cr = context.contentResolver
        val current = guests(context, eventId).filterNot { it.organizer }
        val wantedEmails = wanted.map { it.email.lowercase() }.toSet()
        val currentEmails = current.map { it.email.lowercase() }.toSet()
        for (g in current) {
            if (g.email.lowercase() in wantedEmails) continue
            cr.delete(
                Attendees.CONTENT_URI,
                "${Attendees.EVENT_ID} = ? AND ${Attendees.ATTENDEE_EMAIL} = ? AND ${Attendees.ATTENDEE_RELATIONSHIP} != ?",
                arrayOf(eventId.toString(), g.email, Attendees.RELATIONSHIP_ORGANIZER.toString()),
            )
        }
        for (g in wanted) {
            if (g.email.lowercase() in currentEmails) continue
            cr.insert(
                Attendees.CONTENT_URI,
                ContentValues().apply {
                    put(Attendees.EVENT_ID, eventId)
                    put(Attendees.ATTENDEE_EMAIL, g.email)
                    if (g.name.isNotBlank()) put(Attendees.ATTENDEE_NAME, g.name)
                    put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
                    put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
                    put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_INVITED)
                },
            ) ?: return false
        }
        return true
    }
}

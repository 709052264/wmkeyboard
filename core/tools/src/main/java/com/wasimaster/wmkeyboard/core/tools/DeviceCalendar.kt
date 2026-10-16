package com.wasimaster.wmkeyboard.core.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import com.wasimaster.wmkeyboard.tools.R
import java.util.Calendar
import java.util.TimeZone

/**
 * One event instance read from the device calendar for a single day.
 * [begin]/[end] are epoch millis; an [allDay] event covers the whole day and
 * carries no meaningful clock time.
 */
data class DeviceCalendarEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String?,
    /** The calendar's display colour (ARGB int); 0 when the provider gives none. */
    val color: Int,
)

/** One calendar an event can be added to (#698). */
data class DeviceCalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val primary: Boolean,
)

/**
 * The device's calendar through [CalendarContract.Instances].
 * The Instances table expands recurring events, so a weekly meeting shows up
 * on every day it actually lands on. Callers must hold `READ_CALENDAR`;
 * without it the query throws and we return an empty list rather than crash.
 */
object DeviceCalendar {

    private val PROJECTION = arrayOf(
        CalendarContract.Instances.EVENT_ID,      // 0
        CalendarContract.Instances.TITLE,         // 1
        CalendarContract.Instances.BEGIN,         // 2
        CalendarContract.Instances.END,           // 3
        CalendarContract.Instances.ALL_DAY,       // 4
        CalendarContract.Instances.EVENT_LOCATION, // 5
        CalendarContract.Instances.DISPLAY_COLOR, // 6
    )

    /**
     * Events touching the local day [year]-[month]-[day] (month 1-12), all-day
     * first then by start time. Returns empty on any failure — no permission,
     * no provider, or a grant revoked mid-session.
     */
    fun eventsForDay(context: Context, year: Int, month: Int, day: Int): List<DeviceCalendarEvent> {
        val dayStart = Calendar.getInstance().apply {
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return eventsBetween(context, dayStart, dayStart + DateUtils.DAY_IN_MILLIS)
    }

    /**
     * Events touching the local span [from] until [until], all-day first then
     * by start time; what the AI's calendar tool lists (#698). An all-day
     * event counts on each local date it covers. Empty on any failure, like
     * [eventsForDay]; check [canRead] first to tell "no events" from "no
     * access".
     */
    fun eventsBetween(context: Context, from: Long, until: Long): List<DeviceCalendarEvent> {
        // All-day events are stored at UTC midnight, so one can sit just
        // outside a local-time window; widen the query a day each side and
        // re-filter to the exact span below.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath((from - DateUtils.DAY_IN_MILLIS).toString())
            .appendPath((until + DateUtils.DAY_IN_MILLIS).toString())
            .build()
        // Read once, outside the cursor loop.
        val noTitle = context.getString(R.string.core_tools_calendar_event_no_title)
        val events = ArrayList<DeviceCalendarEvent>()
        runCatching {
            context.contentResolver.query(
                uri, PROJECTION, null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val begin = c.getLong(2)
                    val end = c.getLong(3)
                    val allDay = c.getInt(4) != 0
                    // An all-day event's ends are UTC midnights naming dates;
                    // the same dates' local midnights are what the span means.
                    val shownBegin = if (allDay) localMidnightOf(begin) else begin
                    val shownEnd = if (allDay) localMidnightOf(end) else end
                    if (shownEnd <= from || shownBegin >= until) continue
                    events.add(
                        DeviceCalendarEvent(
                            id = c.getLong(0),
                            title = c.getString(1)?.takeIf { it.isNotBlank() } ?: noTitle,
                            begin = begin,
                            end = end,
                            allDay = allDay,
                            location = c.getString(5)?.takeIf { it.isNotBlank() },
                            color = c.getInt(6),
                        ),
                    )
                }
            }
        }
        return events.sortedWith(
            compareByDescending<DeviceCalendarEvent> { it.allDay }.thenBy { it.begin },
        )
    }

    /** Whether the calendar may be read now. */
    fun canRead(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Whether an event may be written now, for the AI's "add directly" (#698). */
    fun canWrite(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /**
     * The calendars an event can be added to: visible ones the user may write
     * in, the primary one of each account first. Empty without READ_CALENDAR.
     */
    fun writableCalendars(context: Context): List<DeviceCalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        val out = ArrayList<DeviceCalendarInfo>()
        runCatching {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)
                ?.use { c ->
                    while (c.moveToNext()) {
                        out.add(
                            DeviceCalendarInfo(
                                id = c.getLong(0),
                                name = c.getString(1).orEmpty(),
                                account = c.getString(2).orEmpty(),
                                primary = c.getInt(3) != 0,
                            ),
                        )
                    }
                }
        }
        return out.sortedWith(compareByDescending<DeviceCalendarInfo> { it.primary }.thenBy { it.name.lowercase() })
    }

    /**
     * Adds one event to [calendarId] and returns its id, or null when the
     * provider refused it. Needs WRITE_CALENDAR. An [allDay] event's [begin]
     * and [end] are UTC midnights, and it is stored in UTC as the provider
     * requires.
     */
    @Suppress("LongParameterList")
    fun insertEvent(
        context: Context,
        calendarId: Long,
        title: String,
        begin: Long,
        end: Long,
        allDay: Boolean,
        location: String?,
        description: String?,
    ): Long? = runCatching {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
            location?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            description?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
    }.getOrNull()

    /** The local midnight of the date a UTC midnight names. */
    private fun localMidnightOf(utcMidnight: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }
}

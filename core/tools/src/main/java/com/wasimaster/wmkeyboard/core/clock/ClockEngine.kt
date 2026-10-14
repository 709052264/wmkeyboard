package com.wasimaster.wmkeyboard.core.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wasimaster.wmkeyboard.app.MainActivityContract
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * The one door every change to the clock goes through.
 *
 * [update] applies a change to [ClockStore], and then does the two things a
 * change can make stale: the wake-ups registered with [AlarmManager] and the
 * notifications in the shade. A screen that changed the data and forgot either
 * would leave an alarm that rings at its old time, or a timer in the shade that
 * was cancelled a minute ago.
 */
object ClockEngine {

    /** Applies [change] and brings the system's view of the clock up to date. */
    fun update(context: Context, change: (ClockData) -> ClockData): ClockData {
        val app = context.applicationContext
        val data = ClockStore.get(app).update(change)
        rearm(app, data)
        ClockNotifications.refresh(app, data)
        return data
    }

    // ---- Wake-ups ------------------------------------------------------

    /**
     * Registers the next alarm and the next timer end with [AlarmManager],
     * replacing whatever was registered before.
     *
     * Two wake-ups at most, whatever the number of alarms and timers: the
     * receiver works out what is due when it fires, and re-arms for the next.
     * That keeps the registration a pure function of the data, which is what
     * makes re-arming after a reboot or a zone change safe to do at any time.
     */
    fun rearm(context: Context, data: ClockData = ClockStore.get(context).data) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val nextAlarm = AlarmMath.nextOfAll(data.alarms, now, TimeZone.getDefault())
        val alarmPi = broadcast(context, ClockReceiver.ACTION_ALARM, REQUEST_ALARM)
        if (nextAlarm == null) {
            am.cancel(alarmPi)
        } else {
            setAlarmClock(context, am, nextAlarm.second, alarmPi)
        }
        val nextTimer = data.timers.filterNot { it.paused }.minOfOrNull { it.endsAt }
        val timerPi = broadcast(context, ClockReceiver.ACTION_TIMER, REQUEST_TIMER)
        if (nextTimer == null) {
            am.cancel(timerPi)
        } else {
            setExact(am, nextTimer, timerPi)
        }
    }

    /**
     * Re-arms from a cold process start. The tz database and the phone's
     * clock may have moved while the process was dead (after a reboot, the
     * registrations are gone outright), and this app deliberately has no boot
     * receiver: the keyboard starts at boot anyway, and this runs then.
     *
     * Off the main thread, and free when the user never used the clock.
     */
    fun rearmOnStart(context: Context) {
        val app = context.applicationContext
        if (!ClockStore.exists(app)) return
        thread(name = "clock-rearm", isDaemon = true) {
            runCatching {
                val store = ClockStore.get(app)
                // Timers that ran out while nothing was alive ring now rather
                // than never: late is better than silent.
                onTimerWake(app, System.currentTimeMillis(), ringIfDue = true)
                rearm(app, store.data)
                ClockNotifications.refresh(app, store.data)
            }.onFailure { DebugLog.e("Clock", "re-arm at start failed", it) }
        }
    }

    /** Whether alarms can be registered to the minute (Android 12 and up ask the user). */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    private fun setAlarmClock(context: Context, am: AlarmManager, at: Long, pi: PendingIntent) {
        if (canScheduleExact(context)) {
            val show = PendingIntent.getActivity(
                context, REQUEST_SHOW,
                MainActivityContract.intent(context, ROUTE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val ok = runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi) }.isSuccess
            if (ok) return
        }
        // No exact-alarm grant: the best Android allows is a wake-up that Doze
        // may hold back by some minutes. The Alarms tab says so and offers the grant.
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    private fun setExact(am: AlarmManager, at: Long, pi: PendingIntent) {
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        val ok = exact && runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }.isSuccess
        if (!ok) runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    internal fun broadcast(context: Context, action: String, request: Int, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(
            context, request,
            Intent(context, ClockReceiver::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ---- What a wake-up does --------------------------------------------

    /** The alarm wake-up fired: ring whatever is due. */
    fun onAlarmWake(context: Context, at: Long) {
        var started: List<Ringing> = emptyList()
        update(context) { d ->
            val (next, rang) = ClockActions.fireAlarms(d, at, TimeZone.getDefault())
            started = rang
            next
        }
        if (started.isNotEmpty()) ClockRinger.start(context)
    }

    /** The timer wake-up fired, or a process start found timers that ran out. */
    fun onTimerWake(context: Context, at: Long, ringIfDue: Boolean = true) {
        val config = ClockPrefs.get(context)
        var started: List<Ringing> = emptyList()
        update(context) { d ->
            val (next, rang) = ClockActions.fireTimers(d, at, config.pomodoro)
            started = rang
            next
        }
        if (ringIfDue && started.isNotEmpty()) ClockRinger.start(context)
    }

    // ---- Answers to a ring ----------------------------------------------

    fun dismiss(context: Context, ring: Ringing) {
        update(context) { ClockActions.dismiss(it, ring, System.currentTimeMillis()) }
    }

    fun dismissAll(context: Context) {
        val now = System.currentTimeMillis()
        update(context) { d -> d.ringing.fold(d) { acc, r -> ClockActions.dismiss(acc, r, now) } }
    }

    fun snooze(context: Context, ring: Ringing, minutes: Int = snoozeMinutesFor(context, ring)) {
        update(context) { ClockActions.snooze(it, ring, System.currentTimeMillis(), minutes) }
    }

    fun snoozeAll(context: Context) {
        val now = System.currentTimeMillis()
        update(context) { d ->
            d.ringing.fold(d) { acc, r -> ClockActions.snooze(acc, r, now, snoozeMinutesFor(context, r, d)) }
        }
    }

    fun missedAll(context: Context) {
        val now = System.currentTimeMillis()
        update(context) { d -> d.ringing.fold(d) { acc, r -> ClockActions.missed(acc, r, now) } }
    }

    /** The alarm's own snooze, or the tool's. Timers snooze for one minute. */
    fun snoozeMinutesFor(context: Context, ring: Ringing, data: ClockData = ClockStore.get(context).data): Int =
        when (ring.kind) {
            RingKind.TIMER -> 1
            RingKind.ALARM -> data.alarm(ring.ref)?.snoozeMinutes?.takeIf { it > 0 }
                ?: ClockPrefs.get(context).snoozeMinutes
        }

    /** Drops history past the retention setting. Called when a clock screen opens. */
    fun prune(context: Context) {
        val days = ClockPrefs.get(context).historyDays
        ClockStore.get(context).update { ClockActions.pruneHistory(it, System.currentTimeMillis(), days) }
    }

    /** The settings screen the "next alarm" icon in the status bar opens. */
    const val ROUTE = "clock"

    private const val REQUEST_ALARM = 1
    private const val REQUEST_TIMER = 2
    private const val REQUEST_SHOW = 3
}

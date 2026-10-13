package com.wasimaster.wmkeyboard.app.lock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wasimaster.wmkeyboard.core.settings.AppLockRelock

/**
 * How long ago the user last proved who they are.
 *
 * A process-scoped object holding Compose state. **Nothing here is written to
 * disk, to a `Bundle`, or to `savedInstanceState`** — a grant that outlives the
 * process is a grant the user never gave. A rotation keeps it, because that is
 * the same process; a swipe out of recents or a low-memory kill does not, and
 * the gate is back on the next launch.
 *
 * ## One grant, not one per screen
 *
 * A successful check opens every locked *screen* at once, until the user's
 * chosen [AppLockRelock] ends it. Asking again per screen is prompt fatigue
 * that buys nothing: the same person is still standing in the same place.
 *
 * Destructive actions do not consult this at all — they always ask. The
 * fingerprint in front of an irreversible write is the one that earns its
 * keep, and it is also the last thing between a mis-aimed tap and a wipe. The
 * configurator is the same: it holds the off switch.
 *
 * Because the grant is Compose state, a successful check recomposes every gate
 * reading it on the same frame, with no invalidation to remember.
 */
internal object AppLockSession {

    /**
     * When the last check succeeded, on [android.os.SystemClock.elapsedRealtime].
     *
     * That clock and not `currentTimeMillis`: winding the phone's clock forward
     * must not extend a grace window, and winding it back must not freeze one.
     */
    private var grantedAt: Long? by mutableStateOf(null)

    /**
     * Whether the configurator's own check still counts on this visit.
     *
     * Deliberately separate from [grantedAt], in both directions: the screen
     * holding the off switch must not open on a grant earned in front of some
     * other screen, and passing its gate must not open the rest of the app.
     *
     * A flag rather than a timestamp because it is ended by events and never by
     * the clock — leaving the screen ends it, and so does leaving the app. What
     * it exists for is the frame after a successful check: without it
     * `isLocked(SELF)` answers "locked" forever and the gate redraws over its
     * own success, which is a lockout, not a lock.
     */
    var configGranted: Boolean by mutableStateOf(false)
        private set

    /**
     * The id of the target a prompt is currently up for, or null.
     *
     * Guards the one case the lifecycle cannot: a rotation destroys and
     * rebuilds the gate composable while the system sheet is still on screen,
     * and without this the rebuilt gate fires a second prompt on top of the
     * first. Process-scoped rather than activity-scoped for the same reason.
     */
    var prompting: String? by mutableStateOf(null)

    /**
     * Whether the app's own PIN, password or pattern sheet is up. Here and not
     * on the lock object for the same reason as [prompting]: a rotation must
     * bring the sheet back, answering to the rebuilt activity.
     */
    var secretEntry: Boolean by mutableStateOf(false)

    /** Which system sheet is up for the current check, if any. */
    var sheet: SystemSheet? = null

    /** Whether a past check still counts under [policy]. */
    fun isOpen(policy: AppLockRelock, now: Long): Boolean {
        val at = grantedAt ?: return false
        return when (policy) {
            // Ended by an event rather than by the clock. IMMEDIATE closes
            // when the gate leaves composition and again on the way to the
            // background; ON_LEAVE on the way to the background;
            // UNTIL_APP_CLOSES only when the process goes, which takes this
            // whole object with it.
            AppLockRelock.IMMEDIATE,
            AppLockRelock.ON_LEAVE,
            AppLockRelock.UNTIL_APP_CLOSES,
            -> true

            AppLockRelock.AFTER_1_MIN -> now - at < MINUTE_MS
            AppLockRelock.AFTER_5_MIN -> now - at < 5 * MINUTE_MS
        }
    }

    fun grant(now: Long) {
        grantedAt = now
    }

    fun clear() {
        grantedAt = null
    }

    /** The user answered a prompt raised by the configurator itself. */
    fun grantConfig() {
        configGranted = true
    }

    /** Ends that grant: the configurator asks again on the next visit. */
    fun clearConfig() {
        configGranted = false
    }

    /**
     * The settings app went to the background.
     *
     * Only the two event-driven policies do anything here. The timed ones are
     * measured from the grant on a clock that keeps running while the app is
     * away, so [isOpen] has already counted the time spent outside and a
     * second check here would be the same arithmetic twice.
     */
    fun onStopped(policy: AppLockRelock) {
        if (policy == AppLockRelock.IMMEDIATE || policy == AppLockRelock.ON_LEAVE) clear()
        // The configurator's grant ends here whatever the policy says. That
        // setting is about how long one check keeps the *locked screens* open;
        // the screen holding the off switch is not one of them, and a grant
        // that survived a trip to the background would let the next person
        // holding the phone switch the lock off.
        clearConfig()
    }

    private const val MINUTE_MS = 60_000L
}

/** Why the system prompt is on screen. */
internal enum class SystemSheet {
    /** The fingerprint method itself. */
    MAIN,

    /** The fingerprint offered ahead of the app's own secret. Backing out of it falls through to the secret. */
    SHORTCUT,

    /** "Forgot?" on the secret sheet: the phone's own lock stands in for the app's. */
    RECOVERY,
}

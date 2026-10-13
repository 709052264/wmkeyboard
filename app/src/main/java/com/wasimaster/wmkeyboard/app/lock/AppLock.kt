package com.wasimaster.wmkeyboard.app.lock

import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.AppLockSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether this phone can answer a prompt at all, and what to say if it cannot.
 *
 * Recomputed every time the settings app resumes, because enrolling a
 * fingerprint happens on a system screen the user has to leave this app to
 * reach. See [AppLockSettings] for the invariant this exists to serve: the
 * stored flag is a wish, this is the truth.
 */
internal enum class AppLockStatus(@StringRes val reason: Int) {

    /** A fingerprint, or an accepted screen lock, is ready. Gates are live. */
    READY(0),

    /** The hardware is there but nothing is enrolled. */
    NOT_ENROLLED(R.string.privacy_lock_unavailable_none_enrolled),

    /** No reader, or one the platform will not use. */
    NO_HARDWARE(R.string.privacy_lock_unavailable_no_hardware),

    /**
     * The sensor is busy, or the platform wants a security update first.
     * Transient by definition.
     */
    TEMPORARILY_UNAVAILABLE(R.string.privacy_lock_unavailable_temporary);

    /** Whether the lock can be switched on and off from the configurator. */
    val canEnable: Boolean get() = this == READY

    /**
     * Whether the gates stand aside.
     *
     * True for the two permanent states, and this is the most consequential
     * line in the feature. A phone with nothing enrolled cannot answer a
     * prompt, so a gate that stayed shut there would be a wall: the screen
     * holding the off switch is itself behind the lock, and a config restored
     * from a backup would shut its owner out of their own settings for good.
     *
     * False for [TEMPORARILY_UNAVAILABLE] — that one clears on its own, so
     * standing aside would turn "the sensor is busy" into a way past the lock.
     */
    val failsOpen: Boolean get() = this == NOT_ENROLLED || this == NO_HARDWARE
}

/**
 * How a check ended.
 *
 * Three outcomes rather than a boolean, because the gate treats them
 * differently: a cancel means the user changed their mind and the screen
 * should get out of the way, while a lockout means the screen has to stay and
 * explain itself. Popping the back stack on a lockout would throw away the
 * only sentence that tells the user what happened.
 *
 * A wrong finger is not here. The system sheet counts those itself and stays
 * up; the app hears nothing until it succeeds or the platform gives up.
 */
internal enum class AppLockResult(@StringRes val message: Int) {

    SUCCESS(0),

    /** Cancel, the back gesture, or the negative button. */
    CANCELLED(0),

    /** Too many wrong tries. Clears itself after about thirty seconds. */
    LOCKED_OUT(R.string.privacy_lock_error_lockout),

    /** Too many wrong tries again. Only a screen lock clears this one. */
    LOCKED_OUT_PERMANENT(R.string.privacy_lock_error_lockout_permanent),

    /** Anything else the platform reported. */
    FAILED(R.string.privacy_lock_error_generic);

    val succeeded: Boolean get() = this == SUCCESS

    /** Whether the gate should stay put and show [message] instead of leaving. */
    val explains: Boolean get() = this != SUCCESS && this != CANCELLED
}

/**
 * The gate in front of the settings the user chose to protect: a fingerprint,
 * or the app's own PIN, password or pattern.
 *
 * The interface is separate from its one implementation so that a preview, a
 * test, or a screen composed outside
 * [com.wasimaster.wmkeyboard.app.MainActivity] gets [NoAppLock] and simply
 * draws everything, rather than needing a `FragmentActivity` to exist.
 */
internal interface AppLock {

    /**
     * Whether the lock can ask, refreshed on every resume. Always
     * [AppLockStatus.READY] while the app's own PIN, password or pattern is
     * the answer: the app can always ask for that.
     */
    val status: StateFlow<AppLockStatus>

    /**
     * What the system prompt could do on this phone. The same as [status] for
     * the fingerprint method; for the app's own secret it is the fingerprint
     * alone, which is what decides whether the shortcut can be offered.
     */
    val biometric: StateFlow<AppLockStatus>

    /** The user's configuration, or null until DataStore's first emission. */
    val config: StateFlow<AppLockSettings?>

    /**
     * Whether [target] should be standing behind a prompt right now.
     *
     * Answers **true while [config] is still null.** The settings app already
     * waits for its own settings before drawing anything, but nothing orders
     * that against this flow's first value, and a deep link arriving in that
     * window would otherwise walk straight through. Failing closed turns a
     * one-frame hole into a one-frame gate.
     */
    fun isLocked(target: LockTarget): Boolean

    /**
     * Puts the system prompt up for [target] and reports what happened.
     *
     * [onResult] runs on the main thread and is called exactly once. A success
     * opens the session for every locked screen; see [AppLockSession] for why
     * that is one grant and not one per screen.
     */
    fun unlock(target: LockTarget, onResult: (AppLockResult) -> Unit)

    /**
     * A check with nothing behind it, for the destructive actions and for
     * anything else that must ask every time.
     *
     * Separate from [unlock] because it neither reads nor opens any grant:
     * wiping every setting is exactly the moment to ask again however recently
     * the user last answered. The master toggle goes through [unlock] against
     * [AppLockTargets.SELF] instead, so that its answer counts for the gate it
     * raises in front of its own screen.
     */
    fun authenticate(onResult: (AppLockResult) -> Unit)

    /** Re-reads [status]. Called when the activity resumes. */
    fun refresh()
}

/**
 * The lock for anything composed without a host activity behind it.
 *
 * Reports no hardware and never locks anything, so previews and tests draw the
 * screens they are asking for.
 */
internal object NoAppLock : AppLock {
    override val status: StateFlow<AppLockStatus> = MutableStateFlow(AppLockStatus.NO_HARDWARE)
    override val biometric: StateFlow<AppLockStatus> = status
    override val config: StateFlow<AppLockSettings?> = MutableStateFlow(null)
    override fun isLocked(target: LockTarget): Boolean = false
    override fun unlock(target: LockTarget, onResult: (AppLockResult) -> Unit) =
        onResult(AppLockResult.SUCCESS)

    override fun authenticate(onResult: (AppLockResult) -> Unit) =
        onResult(AppLockResult.SUCCESS)
    override fun refresh() = Unit
}

/**
 * The lock every settings screen reads.
 *
 * Static, like [com.wasimaster.wmkeyboard.app.updates.LocalAppUpdater] and for
 * the same reason: the gate wrapping every destination and the buttons deep
 * inside those destinations must not disagree about whether the user has
 * already proved who they are.
 */
internal val LocalAppLock = staticCompositionLocalOf<AppLock> { NoAppLock }

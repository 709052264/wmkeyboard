package com.wasimaster.wmkeyboard.app.lock

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.AppLockDefaults
import com.wasimaster.wmkeyboard.core.settings.AppLockSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.AppLockMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How an answer on the app's own sheet went. */
internal sealed interface SecretAnswer {
    data object Right : SecretAnswer

    /** Wrong. [waitMs] is the wait this miss started, or 0. */
    data class Wrong(val waitMs: Long) : SecretAnswer

    /** Not checked: an earlier run of misses still has [waitMs] to go. */
    data class Wait(val waitMs: Long) : SecretAnswer
}

/**
 * The real lock: `androidx.biometric` in front of the settings the user chose,
 * or the app's own PIN, password or pattern when the user picked one of those.
 *
 * Both kinds of check go through the same [show]/[finish] pair, so the session
 * rules in [AppLockSession] do not care which one answered. The app's own
 * sheet is drawn by [AppLockSecretPrompt], which reads
 * [AppLockSession.secretEntry] and answers through [submitSecret].
 *
 * **Construct this from `onCreate`, before `setContent`.** The library keeps
 * its prompt in a retained fragment, and building the [BiometricPrompt] while
 * the activity is being created is what lets that fragment find its callback
 * again after a rotation. Built later, a prompt that survives the
 * configuration change would report to an object nobody is holding.
 *
 * @param activity a [FragmentActivity] because that is what the library needs
 *   to host the retained fragment. `androidx.fragment` is already on this
 *   app's classpath by way of appcompat; see the note in the version catalog.
 * @param repository the source of [config]. Collected for the lifetime of the
 *   activity rather than read once, because the configurator writes to it and
 *   every gate has to see the change on the same frame.
 */
internal class BiometricAppLock(
    private val activity: FragmentActivity,
    repository: SettingsRepository,
) : AppLock {

    private val _status = MutableStateFlow(AppLockStatus.NO_HARDWARE)
    override val status: StateFlow<AppLockStatus> = _status.asStateFlow()

    private val _config = MutableStateFlow<AppLockSettings?>(null)
    override val config: StateFlow<AppLockSettings?> = _config.asStateFlow()

    private val _biometric = MutableStateFlow(AppLockStatus.NO_HARDWARE)
    override val biometric: StateFlow<AppLockStatus> = _biometric.asStateFlow()

    private val biometricManager = BiometricManager.from(activity)

    private val throttle = AppLockThrottle(activity)

    /**
     * Where the current prompt reports to.
     *
     * One at a time: the system shows one sheet, and [prompt] refuses to open
     * a second while this is set. Cleared before the callback runs so that a
     * caller is free to start another check from inside its own result.
     */
    private var pending: ((AppLockResult) -> Unit)? = null

    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // The phone's own lock vouched for the user who forgot the
                // app's: the run of misses on the app's sheet is over too.
                if (AppLockSession.sheet == SystemSheet.RECOVERY) throttle.reset()
                finish(AppLockResult.SUCCESS)
            }

            override fun onAuthenticationError(code: Int, message: CharSequence) {
                val sheet = AppLockSession.sheet
                if (sheet == SystemSheet.SHORTCUT || sheet == SystemSheet.RECOVERY) {
                    AppLockSession.sheet = null
                    // Backing out of the fingerprint offered ahead of the
                    // app's secret is backing out of the check. Everything
                    // else there — the "Use PIN" button, a lockout, a sensor
                    // that went away — and anything at all on the recovery
                    // sheet falls through to the app's own sheet, which can
                    // always answer.
                    val backedOut = code == BiometricPrompt.ERROR_USER_CANCELED ||
                        code == BiometricPrompt.ERROR_CANCELED
                    if (sheet == SystemSheet.SHORTCUT && backedOut) {
                        finish(AppLockResult.CANCELLED)
                    } else {
                        AppLockSession.secretEntry = true
                    }
                    return
                }
                // These three say the capability changed underneath us: the
                // last fingerprint was removed, the screen lock was cleared,
                // the reader went away. Re-read it, so the next gate fails
                // open rather than asking again for something unanswerable.
                if (code == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ||
                    code == BiometricPrompt.ERROR_HW_NOT_PRESENT ||
                    code == BiometricPrompt.ERROR_NO_BIOMETRICS
                ) {
                    refresh()
                }
                finish(resultFor(code))
            }

            // Deliberately not overridden: a finger that did not match leaves
            // the sheet up and lets the user try again, which is the system's
            // job and not this app's. Reporting it here would close a prompt
            // the platform has not finished with.
        },
    )

    init {
        refresh()
        activity.lifecycleScope.launch {
            repository.appLock.collect {
                _config.value = it
                // "Allow the screen lock instead" changes which authenticators
                // are asked for, and so can change the answer to whether this
                // phone can authenticate at all.
                refresh()
            }
        }
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _: LifecycleOwner, event: Lifecycle.Event ->
                when (event) {
                    // Enrolling a fingerprint happens on a system screen, so
                    // the answer to "can this phone do it" can change while
                    // the app is away.
                    Lifecycle.Event.ON_RESUME -> refresh()
                    Lifecycle.Event.ON_STOP -> {
                        // The app's sheet goes when the app does, the way the
                        // system prompt cancels itself; a rotation keeps it.
                        if (AppLockSession.secretEntry && !activity.isChangingConfigurations) {
                            finish(AppLockResult.CANCELLED)
                        }
                        AppLockSession.onStopped(_config.value?.relock ?: AppLockDefaults.relock)
                    }

                    else -> Unit
                }
            },
        )
    }

    override fun refresh() {
        val cfg = _config.value
        if (cfg?.usesSecret == true) {
            // The app can always ask for its own secret, so the gates never
            // stand aside for want of a sensor. The fingerprint is only the
            // shortcut here, and asked about on its own.
            _biometric.value = statusFor(Authenticators.BIOMETRIC_WEAK)
            _status.value = AppLockStatus.READY
        } else {
            val allowCredential = cfg?.allowDeviceCredential ?: AppLockDefaults.allowDeviceCredential
            _status.value = statusFor(authenticators(allowCredential))
            _biometric.value = _status.value
        }
    }

    private fun statusFor(authenticators: Int): AppLockStatus =
        when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> AppLockStatus.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AppLockStatus.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            -> AppLockStatus.NO_HARDWARE

            else -> AppLockStatus.TEMPORARILY_UNAVAILABLE
        }

    /** The method the open sheet is asking for, for its wording. */
    val secretMethod: AppLockMethod
        get() = _config.value?.method ?: AppLockDefaults.method

    /** Whether the app's sheet can offer the fingerprint, right now. */
    fun canUseFingerprint(): Boolean = statusFor(Authenticators.BIOMETRIC_WEAK) == AppLockStatus.READY

    /**
     * Whether "Forgot?" has anything to hand over to: a fingerprint, or the
     * phone's own screen lock when the user allows it.
     */
    fun canRecover(): Boolean {
        val allowCredential = _config.value?.allowDeviceCredential ?: AppLockDefaults.allowDeviceCredential
        return statusFor(authenticators(allowCredential)) == AppLockStatus.READY
    }

    /** Milliseconds before the app's sheet will take another answer. */
    fun waitMs(): Long = throttle.remainingMs()

    /**
     * Checks [input] against the stored secret, and ends the check on a match.
     * Call from the main thread; the hashing itself runs off it.
     */
    suspend fun submitSecret(input: String): SecretAnswer {
        val wait = throttle.remainingMs()
        if (wait > 0) return SecretAnswer.Wait(wait)
        val stored = _config.value?.secret
        if (stored == null) {
            // The secret went away under an open sheet: the method was changed
            // somewhere else. Nothing can be checked, so nothing is granted.
            finish(AppLockResult.CANCELLED)
            return SecretAnswer.Wrong(0)
        }
        val right = withContext(Dispatchers.Default) { AppLockSecret.verify(input, stored) }
        if (!AppLockSession.secretEntry) return SecretAnswer.Wrong(0)
        return if (right) {
            throttle.reset()
            finish(AppLockResult.SUCCESS)
            SecretAnswer.Right
        } else {
            SecretAnswer.Wrong(throttle.fail())
        }
    }

    /** The app's sheet was dismissed. */
    fun cancelSecret() {
        if (AppLockSession.secretEntry) finish(AppLockResult.CANCELLED)
    }

    /** "Use fingerprint" on the app's sheet. */
    fun switchToFingerprint() {
        AppLockSession.secretEntry = false
        showSheet(SystemSheet.SHORTCUT)
    }

    /** "Forgot?" on the app's sheet: let the phone's own lock answer instead. */
    fun recover() {
        AppLockSession.secretEntry = false
        showSheet(SystemSheet.RECOVERY)
    }

    private fun showSheet(sheet: SystemSheet) {
        AppLockSession.sheet = sheet
        prompt.authenticate(promptInfo(sheet))
    }

    override fun isLocked(target: LockTarget): Boolean {
        // Null means the store has not answered yet. Treat that as locked: the
        // window is one frame wide, and a deep link that arrives inside it
        // should wait rather than walk through.
        val cfg = _config.value ?: return true
        if (!cfg.enabled) return false
        if (_status.value.failsOpen) return false
        // SELF is locked by the feature being on, not by being ticked.
        if (target !== AppLockTargets.SELF && target.id !in cfg.lockedTargets) return false
        // Actions ask every time; only screens ride the session. See
        // [AppLockSession].
        if (target.kind == LockKind.ACTION) return true
        // The configurator asks fresh too, but "fresh" means once per visit and
        // not once per frame. Answering an unconditional `true` here is what
        // made the gate in front of the off switch redraw over its own success:
        // the prompt was passed, the gate recomposed, and this said "locked"
        // again. It reads its own grant, which no other screen can open and
        // which ends when the screen or the app is left.
        if (target === AppLockTargets.SELF) return !AppLockSession.configGranted
        return !AppLockSession.isOpen(cfg.relock, SystemClock.elapsedRealtime())
    }

    override fun unlock(target: LockTarget, onResult: (AppLockResult) -> Unit) =
        show(target.id, onResult)

    override fun authenticate(onResult: (AppLockResult) -> Unit) = show(BARE, onResult)

    private fun show(id: String, onResult: (AppLockResult) -> Unit) {
        // A sheet is already up: drop the second request rather than stacking
        // one on top of it. The one on screen still reports, and [finish]
        // opens the session off the id it was raised for.
        //
        // Both halves are needed. `pending` catches a second request inside
        // this instance; the session flag catches the case where a rotation
        // rebuilt everything while the sheet stayed on screen, which leaves
        // this instance with no pending callback and a live prompt anyway.
        if (pending != null || AppLockSession.prompting != null) return
        // Nothing here can answer, so there is nothing to ask. Callers gate on
        // [isLocked] first, which already stands aside, but the master toggle
        // reaches [authenticate] directly.
        if (_status.value.failsOpen) {
            onResult(AppLockResult.SUCCESS)
            return
        }
        pending = onResult
        AppLockSession.prompting = id
        val cfg = _config.value
        when {
            cfg?.usesSecret != true -> showSheet(SystemSheet.MAIN)
            cfg.biometricShortcut && canUseFingerprint() -> showSheet(SystemSheet.SHORTCUT)
            else -> AppLockSession.secretEntry = true
        }
    }

    private fun finish(result: AppLockResult) {
        val callback = pending
        val id = AppLockSession.prompting
        pending = null
        AppLockSession.prompting = null
        AppLockSession.secretEntry = false
        AppLockSession.sheet = null
        // Open the session off the id the prompt was raised for, not off the
        // callback. A rotation destroys this object while the sheet is still
        // up; the retained fragment then reports to the *new* instance, whose
        // `pending` is null because it never asked for anything. Granting here
        // is what makes a check the user passed mid-rotation still count, and
        // the gate redraws on its own because the grant is Compose state.
        if (result.succeeded) {
            // The configurator's grant is its own and has to be matched by id:
            // [AppLockTargets.get] cannot answer for SELF, which is kept out of
            // `all` so it never draws as a checkbox, so the lookup below
            // returns null for it and the check the user just passed would be
            // thrown away. Nor does it open the shared session — passing the
            // gate on the off switch is not a reason to open the screens.
            if (id == AppLockTargets.SELF.id) {
                AppLockSession.grantConfig()
            } else {
                val target = id?.let { AppLockTargets[it] }
                if (target != null && target.kind == LockKind.SCREEN) {
                    AppLockSession.grant(SystemClock.elapsedRealtime())
                }
            }
        }
        callback?.invoke(result)
    }

    private fun promptInfo(sheet: SystemSheet): BiometricPrompt.PromptInfo {
        // The shortcut is the fingerprint alone: the phone's screen lock is
        // not the answer the user picked, and its negative button is the way
        // to the one they did.
        val allowCredential = sheet != SystemSheet.SHORTCUT &&
            (_config.value?.allowDeviceCredential ?: AppLockDefaults.allowDeviceCredential)
        return BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.privacy_lock_prompt_title))
            .setSubtitle(
                activity.getString(
                    if (sheet == SystemSheet.RECOVERY) {
                        R.string.privacy_lock_prompt_recover_subtitle
                    } else {
                        R.string.privacy_lock_prompt_subtitle
                    },
                ),
            )
            .setAllowedAuthenticators(authenticators(allowCredential))
            .apply {
                // The builder rejects a negative button when the screen lock
                // is one of the allowed answers, because the sheet draws its
                // own "Use PIN" control there, and rejects the absence of one
                // when it is not. So this forks on the user's setting, never
                // on the API level.
                if (!allowCredential) {
                    setNegativeButtonText(
                        activity.getString(
                            if (sheet == SystemSheet.SHORTCUT) {
                                useSecretLabel(secretMethod)
                            } else {
                                com.wasimaster.wmkeyboard.common.R.string.common_cancel
                            },
                        ),
                    )
                }
            }
            .build()
    }

    private companion object {

        /** The id [AppLockSession.prompting] carries for a check with no target. */
        const val BARE = "applock_bare"

        /**
         * Which kinds of answer the prompt accepts.
         *
         * Class 2 (weak) rather than Class 3 (strong), on purpose. Strong is
         * the class you need when a key is unwrapped by the authentication,
         * and nothing here is encrypted; asking for it would only shut out
         * phones whose face unlock is Class 2 while buying no real security.
         *
         * It also sidesteps a real limitation: the library refuses
         * `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` on API 28 and 29, while
         * `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` is accepted on every version
         * this app runs on. So there is no SDK fork anywhere in this file.
         */
        fun authenticators(allowCredential: Boolean): Int =
            if (allowCredential) Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
            else Authenticators.BIOMETRIC_WEAK

        fun useSecretLabel(method: AppLockMethod): Int = when (method) {
            AppLockMethod.PASSWORD -> R.string.privacy_lock_use_password
            AppLockMethod.PATTERN -> R.string.privacy_lock_use_pattern
            else -> R.string.privacy_lock_use_pin
        }

        fun resultFor(code: Int): AppLockResult = when (code) {
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_CANCELED,
            -> AppLockResult.CANCELLED

            BiometricPrompt.ERROR_LOCKOUT -> AppLockResult.LOCKED_OUT
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> AppLockResult.LOCKED_OUT_PERMANENT
            else -> AppLockResult.FAILED
        }
    }
}

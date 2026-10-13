package com.wasimaster.wmkeyboard.core.settings

/**
 * The settings app's optional lock: which of its screens and destructive
 * actions ask for a check first, what answers that check (a fingerprint and
 * the phone's own screen lock, or a PIN, password or pattern set in this app),
 * and how long one check lasts.
 *
 * **This is a lock on the user interface, not encryption.** Nothing it hides
 * is stored differently; a settings export, Android's own backup, `adb` with
 * USB debugging on, or a rooted phone still reach all of it, and the keyboard
 * service reads the same DataStore without passing any of this. What it stops
 * is the realistic threat it was asked for: somebody holding the unlocked
 * phone for thirty seconds. The strings on the screen say exactly that.
 *
 * Its own flow off [SettingsRepository] rather than a field on
 * [KeyboardSettings], for the reason spelled out on that class: the data class
 * sits one field under the JVM's `copy$default` ceiling, and its remaining
 * room belongs to things that shape typing. Nothing in the IME reads any of
 * this — it is settings-app state end to end. It is still kept in DataStore
 * rather than a private [android.content.SharedPreferences] the way
 * `UpdatePrefs` and `EggPrefs` are, because [SettingsBackup] walks the raw
 * preference map, and that is how this config travels to a new phone for free.
 *
 * **[enabled] is a wish; `BiometricManager.canAuthenticate()` is the truth.**
 * A phone with no enrolled fingerprint and no screen lock cannot answer a
 * prompt, so on one the gates open and this flag does nothing. That is
 * deliberate: the alternative is a backup restored onto such a phone shutting
 * its owner out of the very screen that holds the off switch.
 *
 * Note that [LockedSettings] mirrors these keys into device-protected storage
 * along with every other non-secret preference. That is not a leak — it is a
 * list of screen names, not the content of any of them, and the mirror is
 * still app-private — but it is worth knowing before adding anything here.
 */
data class AppLockSettings(
    /** Whether the lock is on at all. Turning it either way asks for a check. */
    val enabled: Boolean = false,
    /**
     * Ids from the settings app's own registry of lockable things.
     *
     * Opaque strings here: the curated list lives in `:app`, which this module
     * cannot see. Unrecognised ids are kept rather than dropped, so restoring
     * a backup written by a newer build and then upgrading does not silently
     * lose the user's picks.
     */
    val lockedTargets: Set<String> = emptySet(),
    /** How long one successful check keeps the locked screens open. */
    val relock: AppLockRelock = AppLockRelock.ON_LEAVE,
    /**
     * Whether the device's PIN, pattern or password is accepted instead of a
     * fingerprint. On by default: it is the only way back in after the sensor
     * locks itself out, and the only way this works at all on a phone whose
     * reader has broken or that never had one.
     */
    val allowDeviceCredential: Boolean = true,
    /**
     * What the check asks for. [AppLockMethod.BIOMETRIC] hands it to the
     * system prompt; the other three ask for [secret] in the app's own sheet.
     */
    val method: AppLockMethod = AppLockMethod.BIOMETRIC,
    /**
     * The app's own PIN, password or pattern, as a salted PBKDF2 hash —
     * never the secret itself. Null until one is set.
     *
     * Listed in [SettingsBackup.SECRET_KEYS], so it stays out of every backup
     * that leaves the API keys out and out of the direct-boot mirror. A
     * [method] that needs a secret but has none (a restore without secrets)
     * falls back to [AppLockMethod.BIOMETRIC] rather than to no lock — see
     * [usesSecret] — and from there to the usual fail-open rule.
     */
    val secret: String? = null,
    /**
     * Whether a fingerprint is offered first when [method] is a secret, with
     * the PIN, password or pattern one tap away. Has no effect when this phone
     * has no fingerprint, or when [method] is already the fingerprint.
     */
    val biometricShortcut: Boolean = true,
) {
    /**
     * Whether checks go to the app's own sheet. False when the chosen method
     * needs a secret that is not there, which reads as the fingerprint.
     */
    val usesSecret: Boolean get() = method.usesSecret && secret != null
}

/** What answers the lock's check. */
enum class AppLockMethod {
    /** The system prompt: a fingerprint or face, and the phone's screen lock if allowed. */
    BIOMETRIC,
    /** Digits only, 4 to 16 of them, on the app's own number pad. */
    PIN,
    /** Any text, at least 4 characters. */
    PASSWORD,
    /** At least 4 dots joined on a 3 by 3 grid. */
    PATTERN,
    ;

    /** Whether this method asks for the app's own [AppLockSettings.secret]. */
    val usesSecret: Boolean get() = this != BIOMETRIC
}

/** When a successful check stops counting. */
enum class AppLockRelock {
    /** Every locked screen asks again, every time. */
    IMMEDIATE,
    AFTER_1_MIN,
    AFTER_5_MIN,
    /** Ends when the settings app goes to the background. The default. */
    ON_LEAVE,
    /** Ends only when the process does — a swipe out of recents, or a kill. */
    UNTIL_APP_CLOSES,
}

/**
 * The shipped values, for the `default =` argument every settings row passes
 * so it can draw its own restore control. The twin of [SettingsDefaults].
 */
val AppLockDefaults = AppLockSettings()

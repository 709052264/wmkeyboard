package com.wasimaster.wmkeyboard.core.feedback

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.wasimaster.wmkeyboard.core.settings.HapticStyle

/**
 * Plays the key-press haptic effect. Lives outside the IME service so the
 * settings app and the sound & haptics tool can preview exactly what a key
 * press will feel like while the user is still adjusting the sliders.
 */
object HapticPlayer {

    /** Minimum spacing between previews so slider drags don't buzz-saw. */
    private const val PREVIEW_GAP_MS = 150L

    private var lastPreviewAt = 0L

    /**
     * Held rather than looked up per press: [play] runs inside the pointer-down
     * handler, and resolving the vibrator there put a service lookup on the
     * touch path of every keystroke. Built from the application context so the
     * singleton never pins an activity or the IME's own context.
     */
    @Volatile
    private var vibrator: Vibrator? = null

    /** Primes [vibrator] off the touch path, so the first press doesn't pay for it. */
    fun warmUp(context: Context) {
        vibratorFor(context)
    }

    @Suppress("DEPRECATION")
    private fun vibratorFor(context: Context): Vibrator {
        vibrator?.let { return it }
        val app = context.applicationContext
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator = v
        return v
    }

    /**
     * The style to start a device on: the platform's own key haptic where the
     * motor can actually play it, and a heavy click where it cannot.
     *
     * [HapticStyle.SYSTEM_KEY] is the better answer wherever it works — it is
     * the effect the phone's own keyboard uses, tuned per device by the
     * vendor. It falls through to the predefined `EFFECT_CLICK` family, so a
     * device that reports those as unsupported plays nothing recognisable as a
     * key press through the system route; there [HapticStyle.HEAVY_CLICK] at
     * least drives the coil hard enough to be felt.
     *
     * Support is only knowable from API 30, where `areAllEffectsSupported`
     * exists. Below it the predefined effects are still the best guess, and an
     * older device is also the one most likely to have a mushy motor — so the
     * cut-off doubles as the version check.
     *
     * Never returns `HapticStyle.SYSTEM_TAP`, even though that is what
     * `HapticSettings.style` declares. Onboarding calls this and stores
     * the answer, so a phone that has been through the wizard types on one of
     * the two styles below and not on the declared default.
     */
    fun bestSupportedStyle(context: Context): HapticStyle {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return HapticStyle.HEAVY_CLICK
        val vibrator = vibratorFor(context)
        if (!vibrator.hasVibrator()) return HapticStyle.SYSTEM_KEY
        val supported = vibrator.areAllEffectsSupported(
            VibrationEffect.EFFECT_CLICK,
            VibrationEffect.EFFECT_HEAVY_CLICK,
        ) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
        return if (supported) HapticStyle.SYSTEM_KEY else HapticStyle.HEAVY_CLICK
    }

    /**
     * Rate-limited [play] for settings UIs: fires the motor with the values
     * being edited (not yet necessarily persisted), at most once per
     * [PREVIEW_GAP_MS].
     */
    fun preview(
        context: Context,
        style: HapticStyle,
        amplitude: Int,
        durationMs: Int,
        view: View? = null,
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewAt < PREVIEW_GAP_MS) return
        lastPreviewAt = now
        play(context, style, amplitude, durationMs, view)
    }

    /**
     * Delegates to the platform key haptic. Returns false when the motor was
     * not driven (no such effect, or the view is detached) so the caller can
     * fall back to a direct vibrator effect. The IGNORE flags fire the buzz
     * even when the system-wide "Touch feedback" switch is off — the keyboard
     * gates haptics with its own toggle — while the OS still scales the
     * strength by the haptic-intensity setting.
     */
    @Suppress("DEPRECATION")
    private fun playSystem(view: View, style: HapticStyle, respectSystemSetting: Boolean): Boolean {
        val constant = when (style) {
            HapticStyle.SYSTEM_TAP -> HapticFeedbackConstants.KEYBOARD_TAP
            else -> HapticFeedbackConstants.VIRTUAL_KEY
        }
        // The VIEW flag always stays: the IME's own root view has haptics off,
        // and that is a property of how the window is built rather than
        // anything the user chose. The GLOBAL flag is the one that overrides
        // the phone's "Touch feedback" switch, so it comes off when the user
        // has asked the keyboard to follow it.
        val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
            if (respectSystemSetting) 0 else HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        return view.performHapticFeedback(constant, flags)
    }

    /**
     * The phone's own "Touch feedback" switch. Read per press rather than
     * cached: it is a single ContentResolver lookup against a value the
     * platform keeps in a settings cache, and a stale answer here means the
     * keyboard keeps buzzing after the user switched it off. Public so the
     * haptics settings can say when the switch is off.
     */
    fun systemTouchFeedbackOn(context: Context): Boolean =
        android.provider.Settings.System.getInt(
            context.contentResolver,
            android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1,
        ) != 0

    /**
     * Whether the direct-vibrator styles can be tagged so the system "Touch
     * feedback" switch leaves them alone. Needs the `VibrationAttributes`
     * overload of `vibrate`, which is API 33.
     */
    fun canBypassSystemTouch(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Whether [style] drives the vibrator itself rather than the platform key haptic. */
    fun isDirectStyle(style: HapticStyle): Boolean =
        style != HapticStyle.SYSTEM_KEY && style != HapticStyle.SYSTEM_TAP

    /**
     * Plays [effect] on the vibrator. With [asHardwareFeedback] on API 33+ it
     * goes out as `USAGE_HARDWARE_FEEDBACK` (issue #786).
     *
     * Untagged, a short effect is classed `USAGE_TOUCH` by the platform's
     * haptic-feedback heuristic in `VibrationAttributes.Builder`, and
     * `VibrationSettings` turns `USAGE_TOUCH` off outright while
     * `HAPTIC_FEEDBACK_ENABLED` is 0. So the Custom, Sharp and Click styles
     * went silent with the system "Touch feedback" switch off, while the
     * System styles kept working through `FLAG_IGNORE_GLOBAL_SETTING`.
     * Hardware feedback is scaled by its own intensity setting, which falls
     * back to the touch intensity, and never consults the boolean switch.
     */
    private fun vibrate(vibrator: Vibrator, effect: VibrationEffect, asHardwareFeedback: Boolean) {
        if (asHardwareFeedback && canBypassSystemTouch()) {
            vibrator.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK),
            )
        } else {
            vibrator.vibrate(effect)
        }
    }

    /**
     * [respectSystemSetting] makes the phone's own "Touch feedback" switch
     * silence the keyboard along with everything else. Off by default, which
     * is what the keyboard has always done. Previews pass false: someone
     * dragging the intensity slider wants to feel the result whatever the
     * system switch says.
     *
     * [asHardwareFeedback] lets the direct-vibrator styles get past that
     * switch too; see [vibrate]. It only applies while [respectSystemSetting]
     * is off.
     */
    @Suppress("DEPRECATION")
    fun play(
        context: Context,
        style: HapticStyle,
        amplitude: Int,
        durationMs: Int,
        view: View? = null,
        respectSystemSetting: Boolean = false,
        asHardwareFeedback: Boolean = true,
    ) {
        // System styles ride the platform's tuned key haptic. If it actually
        // played we're done; otherwise fall through to a hardware click so
        // there's always feedback (e.g. preview before the view is attached).
        if (style == HapticStyle.SYSTEM_KEY || style == HapticStyle.SYSTEM_TAP) {
            if (view != null && playSystem(view, style, respectSystemSetting)) return
        }
        // The direct-vibrator paths below never consult the platform switch on
        // their own, so honouring it is this check.
        if (respectSystemSetting && !systemTouchFeedbackOn(context)) return
        val vibrator = vibratorFor(context)
        val hardware = asHardwareFeedback && !respectSystemSetting
        if (style == HapticStyle.SHARP &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
        ) {
            // Composed primitive: the HAL overdrives the motor on attack and
            // actively brakes it, so the click is both short (~20ms) and
            // strong — the crisp thump stock keyboards have. A raw one-shot
            // can't do this: it just drives the coil at constant amplitude,
            // which feels mushy. Scale reuses the intensity slider (0..1).
            vibrate(
                vibrator,
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        amplitude.coerceIn(1, 255) / 255f,
                    )
                    .compose(),
                hardware,
            )
        } else if (style != HapticStyle.CUSTOM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Hardware-tuned click effects — crisper than a raw one-shot and
            // what most stock keyboards use.
            vibrate(
                vibrator,
                VibrationEffect.createPredefined(
                    when (style) {
                        HapticStyle.HEAVY_CLICK -> VibrationEffect.EFFECT_HEAVY_CLICK
                        else -> VibrationEffect.EFFECT_CLICK
                    }
                ),
                hardware,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Explicit amplitude: DEFAULT_AMPLITUDE defers to a (often weak)
            // device default. Devices without amplitude control ignore the
            // value, so fall back to the default constant there.
            val oneShotAmplitude = if (vibrator.hasAmplitudeControl()) {
                amplitude.coerceIn(1, 255)
            } else {
                VibrationEffect.DEFAULT_AMPLITUDE
            }
            vibrate(vibrator, VibrationEffect.createOneShot(durationMs.toLong(), oneShotAmplitude), hardware)
        } else {
            vibrator.vibrate(durationMs.toLong())
        }
    }
}

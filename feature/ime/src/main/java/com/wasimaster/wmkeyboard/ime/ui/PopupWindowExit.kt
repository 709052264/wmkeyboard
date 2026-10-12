package com.wasimaster.wmkeyboard.ime.ui

import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import com.wasimaster.wmkeyboard.ime.R

/*
 * A popup that goes away on a timer has to go away *then*. Compose removes a
 * Popup's window the moment it leaves composition, but the window manager may
 * still play an exit animation over the window's last frame, and an OEM build
 * can fill in a fade of its own where the window names none. On a OnePlus
 * (ColorOS, Android 15) that kept the spacebar's language preview on screen
 * ~400 ms past the moment the keyboard took it down, so a 100 ms "Keep the new
 * language on screen" read as half a second.
 *
 * Two measures, because the second does not trust the first to be honoured:
 *  - [PopupNoWindowAnimation] names an animation style with no enter or exit
 *    animation, so a well-behaved window manager has nothing to play;
 *  - [rememberPopupExit] keeps the popup composed for a couple of frames after
 *    it is hidden, drawn empty, so whatever exit animation does run animates
 *    a transparent window.
 */

/**
 * Gives the popup window this is composed in [R.style.WmPopupNoAnimation].
 * Call it at the top of the popup's content, where [LocalView] is the popup's
 * inner view; the window's params sit on the ancestor the window manager holds,
 * the same walk [PopupBlurBehind] makes. The params object is the one the
 * Popup itself re-sends on every move, so the style sticks.
 */
@Composable
internal fun PopupNoWindowAnimation() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = popupWindowView(view) ?: return@LaunchedEffect
        val params = window.layoutParams as WindowManager.LayoutParams
        if (params.windowAnimations == R.style.WmPopupNoAnimation) return@LaunchedEffect
        params.windowAnimations = R.style.WmPopupNoAnimation
        if (!window.isAttachedToWindow) return@LaunchedEffect
        val windowManager = window.context.getSystemService(WindowManager::class.java)
        runCatching { windowManager.updateViewLayout(window, params) }
    }
}

/**
 * Takes the blur [PopupBlurBehind] put behind the popup's window off again, for
 * the empty frames of [rememberPopupExit]: an empty window over a blurred
 * board would still read as the popup.
 */
@Composable
internal fun PopupDropBlurBehind() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = popupWindowView(view) ?: return@LaunchedEffect
        val params = window.layoutParams as WindowManager.LayoutParams
        if (params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND == 0) return@LaunchedEffect
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        params.blurBehindRadius = 0
        if (!window.isAttachedToWindow) return@LaunchedEffect
        val windowManager = window.context.getSystemService(WindowManager::class.java)
        runCatching { windowManager.updateViewLayout(window, params) }
    }
}

private fun popupWindowView(view: View): View? =
    generateSequence(view) { it.parent as? View }
        .firstOrNull { it.layoutParams is WindowManager.LayoutParams }

/**
 * What a timed popup should show: [value] while there is one, and for
 * [PopupExitFrames] frames after it goes null the last value it had, with
 * `exiting` true so the popup draws nothing. Returns null once those frames are
 * spent, which is when the popup should leave composition.
 *
 * The last value is held in a plain remembered slot, not state: it has to be
 * there in the very composition where [value] turns null, or the popup would
 * be disposed in that pass with its last frame still on screen.
 */
@Composable
internal fun <T : Any> rememberPopupExit(value: T?): PopupExit<T>? {
    val last = remember { arrayOfNulls<Any>(1) }
    var spent by remember { mutableIntStateOf(0) }
    if (value != null) last[0] = value
    LaunchedEffect(value == null) {
        if (value != null || last[0] == null) return@LaunchedEffect
        repeat(PopupExitFrames) { withFrameNanos { } }
        last[0] = null
        spent++
    }
    // `spent` is read so the clear above recomposes this.
    @Suppress("UNCHECKED_CAST")
    val shown = (value ?: last[0].takeIf { spent >= 0 }) as T? ?: return null
    return PopupExit(shown, exiting = value == null)
}

internal data class PopupExit<T>(val value: T, val exiting: Boolean)

/**
 * Frames an exiting popup stays composed, drawn empty: one for the empty frame
 * to be composed and drawn, one for it to reach the screen before the window
 * goes.
 */
private const val PopupExitFrames = 2

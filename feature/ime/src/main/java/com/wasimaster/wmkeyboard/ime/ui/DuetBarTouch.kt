package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideCase
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.settings.TwoThumbWordEnd
import com.wasimaster.wmkeyboard.ime.DUET_PING_BAR_DOWN
import com.wasimaster.wmkeyboard.ime.DUET_PING_BAR_UP
import com.wasimaster.wmkeyboard.ime.KeyboardUiState

/**
 * Tells the service when a finger is on this bar, so the two-thumb
 * all-thumbs-up pause waits for it as it waits for a thumb on the keys (#743):
 * a thumb gone up to the strip for another reading is still working on the
 * word. One down when the bar's first finger lands and one up when its last
 * lifts, sent through the preview channel the key grid's own pings use.
 *
 * Watches on the initial pass and consumes nothing, so the bar's own taps,
 * holds and scrolls work exactly as they would without it. A touch that ends
 * any way at all, cancelled or the bar leaving the screen under it included,
 * still sends its up, so the word is never held open by a finger that is
 * gone. Only there under that word end: nothing else listens.
 */
@Composable
internal fun Modifier.duetBarTouch(
    state: KeyboardUiState,
    onGesturePreview: (List<GesturePoint>, List<KeyCenter>, Float, GlideCase) -> Unit,
): Modifier {
    val gesture = state.settings.gesture
    if (!gesture.twoThumbGlide || gesture.twoThumbWordEnd != TwoThumbWordEnd.ALL_UP) return this
    val report by rememberUpdatedState(onGesturePreview)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            report(emptyList(), emptyList(), DUET_PING_BAR_DOWN, GlideCase.None)
            try {
                while (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) Unit
            } finally {
                report(emptyList(), emptyList(), DUET_PING_BAR_UP, GlideCase.None)
            }
        }
    }
}

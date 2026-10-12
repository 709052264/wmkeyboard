package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import kotlinx.coroutines.delay

/**
 * Holding an icon-only button names it: the bubble [ToolCircle] has always
 * shown over the toolbar, for the buttons in the panels too. A panel's header
 * and a clip's action row are bare glyphs — two bars, a pair of scissors, a
 * broom — and the only way to learn what one does was to press it and see.
 *
 * Wraps the button rather than replacing its click: the hold is read on the
 * initial pass, ahead of the button's own clickable, and a hold that becomes a
 * hint swallows the rest of the gesture so the release does not also fire the
 * button. A tap, or a finger that slides off to scroll, passes through
 * untouched. Not for a button that does something of its own on a hold.
 *
 * [modifier] goes on the wrapper, so a caller's `weight` or `align` still
 * reaches the parent. A null or blank [label] wraps nothing.
 */
@Composable
internal fun IconHint(
    label: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (label.isNullOrBlank()) {
        Box(modifier, propagateMinConstraints = true) { content() }
        return
    }
    var shown by remember { mutableStateOf(false) }
    val feedback = LocalKeyPressFeedback.current
    Box(
        modifier = modifier.pointerInput(label) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val slop = viewConfiguration.touchSlop
                // Null when the timeout ran out with the finger still down and
                // still: that is a hold.
                val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var ended = false
                    while (!ended) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.id == down.id }
                        ended = change == null || !change.pressed || change.isConsumed ||
                            (change.position - down.position).getDistance() > slop
                    }
                    true
                }
                if (endedEarly != null) return@awaitEachGesture
                feedback()
                shown = true
                // The button under the finger must not see this release.
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        },
        propagateMinConstraints = true,
    ) {
        content()
        if (shown) IconHintBubble(label) { shown = false }
    }
}

/**
 * The name bubble itself, above its anchor: the toolbar's long-press label,
 * gone on its own after a moment.
 */
@Composable
internal fun IconHintBubble(label: String, onDismiss: () -> Unit) {
    val kb = LocalKbTheme.current
    LaunchedEffect(Unit) {
        delay(IconHintShownMs)
        onDismiss()
    }
    Popup(
        popupPositionProvider = rememberAboveAnchorPopup(),
        onDismissRequest = onDismiss,
    ) {
        GlassPopupBackdrop(kb, FrostSurface.POPUPS)
        Surface(
            modifier = Modifier.frostGlass(kb, kb.popupShape(), FrostSurface.POPUPS, sampleBackdrop = false),
            shape = kb.popupShape(),
            color = kb.popupFill(FrostSurface.POPUPS),
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.popupShapeKind, 6.dp),
        ) {
            Text(
                label,
                color = kb.popupText,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** How long a hint stays up: long enough to read a few words. */
private const val IconHintShownMs = 1200L

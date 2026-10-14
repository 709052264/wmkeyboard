package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A sideways swipe across a tabbed panel's body steps to the tab beside the
 * one open, [index] of [count]: the way a pager turns, without making the
 * body one. Leftward is the next tab, rightward the one before, mirrored in a
 * right-to-left layout; the ends do not wrap.
 *
 * It yields to everything inside it. It reads each move after the children
 * have, so a list that scrolls, a chip row that scrolls sideways, a card that
 * swipes away or a strip that drags keeps its gesture, and a move that is
 * more down than across is left to the scroll. Once a move is clearly
 * sideways it is taken, which cancels the press under the finger, and the
 * tab changes on release past [TabSwipeDistance] or on a fling.
 */
internal fun Modifier.swipeTabs(
    index: Int,
    count: Int,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
): Modifier = if (!enabled || count < 2 || index !in 0 until count) {
    this
} else {
    composed {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val at by rememberUpdatedState(index)
        val select by rememberUpdatedState(onSelect)
        pointerInput(count, rtl) {
            val distance = TabSwipeDistance.toPx()
            val fling = TabSwipeFling.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                val slop = viewConfiguration.touchSlop
                var dx = 0f
                var dy = 0f
                var claimed = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!claimed && (event.changes.size > 1 || change.isConsumed)) return@awaitEachGesture
                    if (!change.pressed) break
                    val delta = change.positionChange()
                    dx += delta.x
                    dy += delta.y
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (!claimed) {
                        if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
                        if (abs(dx) > slop && abs(dx) > abs(dy) * 2) claimed = true
                    }
                    if (claimed) change.consume()
                }
                if (!claimed) return@awaitEachGesture
                val vx = tracker.calculateVelocity().x
                val far = abs(dx) > distance
                val flung = abs(vx) > fling && (vx > 0) == (dx > 0)
                if (!far && !flung) return@awaitEachGesture
                val forward = if (rtl) dx > 0 else dx < 0
                val target = at + if (forward) 1 else -1
                if (target in 0 until count) select(target)
            }
        }
    }
}

/** How far a slow swipe travels before it changes the tab. */
private val TabSwipeDistance = 56.dp

/** How fast a short swipe must leave the glass to change the tab anyway, per second. */
private val TabSwipeFling = 600.dp

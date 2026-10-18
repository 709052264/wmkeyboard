package com.wasimaster.wmkeyboard.ime.ui.homeassistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** The curves every Home Assistant animation shares, the same ones the panel slot uses. */
internal object HaMotion {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val EXPAND_MS = 190
    const val COLLAPSE_MS = 140
    const val TAB_MS = 220
    const val PULSE_MS = 420
    const val SHAKE_MS = 300
    const val FLASH_MS = 500
    const val HOLD_MS = 800
    const val STAGGER_MS = 18
    const val STAGGER_CAP_MS = 180
    const val PENDING_ALPHA = 0.6f
    val appear = spring<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
    val pop = spring<Float>(dampingRatio = 0.5f, stiffness = 700f)
}

/**
 * A tile's entrance: scale 0.85 → 1 and fade, on a spring, staggered by
 * [index] so a room fills in left to right, top to bottom, and never for
 * longer than a fifth of a second however many tiles there are.
 */
internal fun Modifier.tileAppear(index: Int, animate: Boolean): Modifier = composed {
    if (!animate) return@composed this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index * HaMotion.STAGGER_MS).coerceAtMost(HaMotion.STAGGER_CAP_MS).toLong())
        progress.animateTo(1f, HaMotion.appear)
    }
    graphicsLayer {
        val p = progress.value
        alpha = p
        scaleX = 0.85f + 0.15f * p
        scaleY = scaleX
    }
}

/**
 * One ring that grows out of the tile and fades, the moment the server says
 * the entity changed: a light somebody else switched, a door that opened.
 * Keyed on [changedAtMs], so a change the panel already showed does not ring
 * again on recomposition.
 */
internal fun Modifier.statePulse(changedAtMs: Long, animate: Boolean, color: Color): Modifier = composed {
    if (!animate || changedAtMs == 0L) return@composed this
    val progress = remember { Animatable(1f) }
    LaunchedEffect(changedAtMs) {
        // Only a change that is fresh: a tile composed long after it should stay still.
        if (System.currentTimeMillis() - changedAtMs > HaMotion.PULSE_MS * 2) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(HaMotion.PULSE_MS, easing = FastOutSlowInEasing))
    }
    drawWithContent {
        drawContent()
        val p = progress.value
        if (p < 1f) {
            val inset = -6.dp.toPx() * p
            drawRoundRect(
                color = color.copy(alpha = 0.35f * (1f - p)),
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/** Three quick sideways cycles of two pixels: the server said no, or said something else. [nonce] changes per shake. */
internal fun Modifier.shake(nonce: Long?, animate: Boolean): Modifier = composed {
    if (!animate || nonce == null) return@composed this
    val progress = remember { Animatable(1f) }
    LaunchedEffect(nonce) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(HaMotion.SHAKE_MS, easing = LinearEasing))
    }
    graphicsLayer {
        val p = progress.value
        translationX = if (p < 1f) (sin(p * PI * 6).toFloat() * 2.dp.toPx() * (1f - p)) else 0f
    }
}

/** A brief wash of the accent over a tile whose scene or script just ran, then a return to rest. */
internal fun Modifier.runFlash(ranAtMs: Long?, animate: Boolean, color: Color): Modifier = composed {
    if (ranAtMs == null) return@composed this
    val progress = remember { Animatable(1f) }
    LaunchedEffect(ranAtMs) {
        if (System.currentTimeMillis() - ranAtMs > HaMotion.FLASH_MS * 2) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, if (animate) tween(HaMotion.FLASH_MS, easing = FastOutSlowInEasing) else tween(0))
    }
    drawWithContent {
        drawContent()
        val p = progress.value
        if (p < 1f) drawRoundRect(color.copy(alpha = 0.22f * (1f - p)), cornerRadius = CornerRadius(16.dp.toPx()))
    }
}

/**
 * Press and hold for [HaMotion.HOLD_MS] to fire: a ring fills around the
 * control while the finger is down and empties if it lifts early. For locks,
 * alarms, garage doors and sirens, where a stray tap must not act.
 */
@Composable
internal fun rememberHold(enabled: Boolean, onFire: () -> Unit): HoldState {
    val scope = rememberCoroutineScope()
    val state = remember { HoldState() }
    val modifier = if (!enabled) {
        Modifier
    } else {
        Modifier.pointerInput(onFire) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                state.job?.cancel()
                state.pressed = true
                state.job = scope.launch {
                    state.progress.snapTo(0f)
                    state.progress.animateTo(1f, tween(HaMotion.HOLD_MS, easing = LinearEasing))
                    if (state.pressed) {
                        state.fired = true
                        onFire()
                    }
                }
                waitForUpOrCancellation()
                state.pressed = false
                if (!state.fired) {
                    state.job?.cancel()
                    state.job = scope.launch { state.progress.animateTo(0f, tween(HaMotion.COLLAPSE_MS)) }
                } else {
                    state.fired = false
                    state.job = scope.launch { state.progress.snapTo(0f) }
                }
            }
        }
    }
    state.modifier = modifier
    return state
}

internal class HoldState {
    val progress = Animatable(0f)
    var pressed by mutableStateOf(false)
    var fired = false
    var job: Job? = null
    var modifier: Modifier = Modifier
}

/** The ring a hold draws: the accent sweeping round the control's rounded rect. */
internal fun Modifier.holdRing(state: HoldState, color: Color, radius: androidx.compose.ui.unit.Dp): Modifier = drawWithContent {
    drawContent()
    val p = state.progress.value
    if (p > 0f) {
        val stroke = 3.dp.toPx()
        val r = radius.toPx()
        // A rounded rect has no single sweep; a growing inset ring reads the same and is honest about it.
        drawRoundRect(
            color = color.copy(alpha = 0.9f),
            topLeft = Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(r),
            style = Stroke(width = stroke * p),
        )
        drawRoundRect(color = color.copy(alpha = 0.18f * p), cornerRadius = CornerRadius(r))
    }
}

/** The alpha of a status dot: breathing while connecting, steady otherwise. */
@Composable
internal fun breathing(active: Boolean, animate: Boolean): Float {
    if (!active || !animate) return 1f
    val transition = rememberInfiniteTransition(label = "haBreath")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "haBreathAlpha",
    )
    return alpha
}

/** A number that rolls to its new value rather than jumping: for the climate dial's digits. */
@Composable
internal fun rollingValue(target: Float, animate: Boolean): Float {
    var shown by remember { mutableFloatStateOf(target) }
    val anim = remember { Animatable(target) }
    LaunchedEffect(target, animate) {
        if (!animate) {
            anim.snapTo(target)
        } else {
            anim.animateTo(target, tween(650, easing = FastOutSlowInEasing))
        }
    }
    shown = anim.value
    return shown
}

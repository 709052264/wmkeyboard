package com.wasimaster.wmkeyboard.app.clock

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowForward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Alarm
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HourglassBottom
import com.wasimaster.wmkeyboard.core.clock.ClockActions
import com.wasimaster.wmkeyboard.core.clock.ClockEngine
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.ClockPrefs
import com.wasimaster.wmkeyboard.core.clock.ClockStore
import com.wasimaster.wmkeyboard.core.clock.RingKind
import com.wasimaster.wmkeyboard.core.clock.Ringing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The screen an alarm or a finished timer puts in front of the user, over the
 * lock screen.
 *
 * Always dark, whatever the app's theme: it is usually seen at 6 a.m. by eyes
 * that just opened. It cannot read the user's settings anyway, since it may
 * run before the first unlock, when the settings are locked away; the clock's
 * own config is in device-protected storage and is all it needs.
 *
 * Dismiss is a slide, not a tap, so a hand fumbling for the phone does not
 * turn the alarm off by accident; snooze is the big easy target, which is the
 * mistake that costs nothing.
 */
class ClockRingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val reduceMotion = runCatching {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val data by ClockStore.get(this).state.collectAsStateWithLifecycle()
                LaunchedEffect(data.ringing.isEmpty()) {
                    // Answered from the shade, or gave up by itself.
                    if (data.ringing.isEmpty()) finish()
                }
                if (data.ringing.isNotEmpty()) {
                    RingScreen(
                        ringing = data.ringing,
                        reduceMotion = reduceMotion,
                        snoozeDefault = ClockPrefs.get(this).snoozeMinutes,
                        onDismiss = { ClockEngine.dismissAll(this) },
                        onSnooze = { minutes ->
                            val now = System.currentTimeMillis()
                            ClockEngine.update(this) { d ->
                                d.ringing.fold(d) { acc, r -> ClockActions.snooze(acc, r, now, if (r.kind == RingKind.TIMER) 1 else minutes) }
                            }
                        },
                        onDismissOne = { ClockEngine.dismiss(this, it) },
                    )
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_CAMERA
        ) {
            if (ClockPrefs.get(this).volumeKeysSnooze) ClockEngine.snoozeAll(this)
            // Swallowed either way: the volume of an alarm is not changed by
            // someone reaching for the phone in the dark.
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

private val RingTop = Color(0xFF1B1F3B)
private val RingBottom = Color(0xFF0A0B14)
private val RingAccent = Color(0xFF8C9EFF)

@Composable
private fun RingScreen(
    ringing: List<Ringing>,
    reduceMotion: Boolean,
    snoozeDefault: Int,
    onDismiss: () -> Unit,
    onSnooze: (Int) -> Unit,
    onDismissOne: (Ringing) -> Unit,
) {
    val first = ringing.first()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val hour24 = android.text.format.DateFormat.is24HourFormat(context)
    val zone = TimeZone.getDefault()
    val isTimer = first.kind == RingKind.TIMER
    val title = first.label.ifEmpty {
        stringResource(if (isTimer) R.string.clock_ring_timer_done else R.string.clock_ring_alarm)
    }
    Surface(color = RingBottom, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(RingTop, RingBottom))),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))
                Text(
                    ClockFormats.longDate(now, zone),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                Pulse(reduceMotion = reduceMotion, timer = isTimer)
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        ClockFormats.timeDigits(now, zone, hour24),
                        color = Color.White,
                        fontSize = 84.sp,
                        fontWeight = FontWeight.Light,
                    )
                    val ampm = ClockFormats.amPm(now, zone, hour24)
                    if (ampm.isNotEmpty()) {
                        Text(
                            ampm,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 22.sp,
                            modifier = Modifier.padding(start = 6.dp, bottom = 18.dp),
                        )
                    }
                }
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ringing.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    ringing.drop(1).take(MAX_EXTRA).forEach { r ->
                        ExtraRing(r, onDismiss = { onDismissOne(r) })
                    }
                }
                Spacer(Modifier.weight(1f))
                SnoozeRow(isTimer = isTimer, default = snoozeDefault, onSnooze = onSnooze)
                Spacer(Modifier.height(20.dp))
                SlideToDismiss(
                    label = stringResource(if (isTimer) R.string.clock_ring_slide_stop else R.string.clock_ring_slide_dismiss),
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

/** Three rings swelling out of the icon, staggered: the alarm "sounding" without any text saying so. */
@Composable
private fun Pulse(reduceMotion: Boolean, timer: Boolean) {
    val transition = rememberInfiniteTransition(label = "ring")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        if (!reduceMotion) {
            Canvas(Modifier.fillMaxSize()) {
                for (i in 0 until 3) {
                    val p = (t + i / 3f) % 1f
                    drawCircle(
                        color = RingAccent.copy(alpha = (1f - p) * 0.5f),
                        radius = size.minDimension / 2f * (0.35f + 0.65f * p),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
        }
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(RingAccent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (timer) Icons.Outlined.HourglassBottom else Icons.Outlined.Alarm,
                contentDescription = null,
                tint = RingBottom,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun ExtraRing(ring: Ringing, onDismiss: () -> Unit) {
    val label = ring.label.ifEmpty {
        stringResource(if (ring.kind == RingKind.TIMER) R.string.clock_ring_timer_done else R.string.clock_ring_alarm)
    }
    Row(
        Modifier
            .padding(top = 6.dp)
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        androidx.compose.material3.TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.clock_ring_dismiss), color = RingAccent)
        }
    }
}

@Composable
private fun SnoozeRow(isTimer: Boolean, default: Int, onSnooze: (Int) -> Unit) {
    if (isTimer) {
        FilledTonalButton(
            onClick = { onSnooze(1) },
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(32.dp),
        ) {
            Text(stringResource(R.string.clock_ring_add_minute), style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    val options = remember(default) { (listOf(default) + SNOOZE_CHOICES).distinct().sorted() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalButton(
            onClick = { onSnooze(default) },
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(32.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.clock_ring_snooze_for, default, default),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.filter { it != default }.forEach { m ->
                Text(
                    pluralStringResource(R.plurals.clock_ring_minutes_short, m, m),
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { onSnooze(m) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * A track with a thumb that has to be dragged most of the way across. Let go
 * early and it springs back. TalkBack users get a plain click action on it.
 */
@Composable
private fun SlideToDismiss(label: String, onDismiss: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(
        Modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .semantics {
                contentDescription = label
                onClick { onDismiss(); true }
            },
    ) {
        val thumb = 60.dp
        val maxPx = with(LocalDensity.current) { (maxWidth - thumb - 8.dp).toPx() }.coerceAtLeast(1f)
        val offset = remember { Animatable(0f) }
        Text(
            label,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier
                .align(Alignment.Center)
                .alpha(1f - (offset.value / maxPx).coerceIn(0f, 1f)),
        )
        Box(
            Modifier
                .padding(4.dp)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(thumb)
                .clip(CircleShape)
                .background(RingAccent)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, maxPx)) }
                    },
                    onDragStopped = {
                        if (offset.value >= maxPx * DISMISS_FRACTION) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            offset.animateTo(maxPx)
                            onDismiss()
                        } else {
                            offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = RingBottom,
            )
        }
    }
}

private val SNOOZE_CHOICES = listOf(5, 10, 15, 30)
private const val MAX_EXTRA = 3
private const val PULSE_MS = 2400
private const val DISMISS_FRACTION = 0.85f

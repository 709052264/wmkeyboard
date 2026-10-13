package com.wasimaster.wmkeyboard.app.lock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Backspace
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Apps
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Fingerprint
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Password
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pin
import com.wasimaster.wmkeyboard.core.settings.AppLockMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR

/** The glyph for each way of answering the lock. */
internal fun AppLockMethod.icon() = when (this) {
    AppLockMethod.BIOMETRIC -> Icons.Outlined.Fingerprint
    AppLockMethod.PIN -> Icons.Outlined.Pin
    AppLockMethod.PASSWORD -> Icons.Outlined.Password
    AppLockMethod.PATTERN -> Icons.Outlined.Apps
}

/**
 * The app's own PIN, password or pattern sheet, for whichever check is open.
 *
 * Composed once, at the root of the settings app, and drawn only while
 * [AppLockSession.secretEntry] says a check is waiting on it — the counterpart
 * of the system sheet `androidx.biometric` draws for the fingerprint. Every
 * gate and button reaches it through [AppLock.unlock] and
 * [AppLock.authenticate] exactly as before, so none of them know which of the
 * two answered.
 */
@Composable
internal fun AppLockSecretPrompt(lock: BiometricAppLock) {
    if (!AppLockSession.secretEntry) return
    val method = lock.secretMethod
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var waitUntil by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    // Bumped to clear the input after a wrong answer.
    var attempt by remember { mutableIntStateOf(0) }
    val wrong = stringResource(R.string.privacy_lock_secret_wrong)

    LaunchedEffect(Unit) {
        val wait = lock.waitMs()
        if (wait > 0) waitUntil = System.currentTimeMillis() + wait
    }
    val waitLeft = rememberCountdown(waitUntil)

    Dialog(
        onDismissRequest = { if (!busy) lock.cancelSecret() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        SecretSheet(
            title = stringResource(enterTitle(method)),
            icon = method,
        ) {
            SecretInput(
                method = method,
                resetKey = attempt,
                enabled = !busy && waitLeft == 0L,
                onSubmit = { input ->
                    busy = true
                    scope.launch {
                        when (val answer = lock.submitSecret(input)) {
                            SecretAnswer.Right -> Unit
                            is SecretAnswer.Wrong -> {
                                error = wrong
                                if (answer.waitMs > 0) waitUntil = System.currentTimeMillis() + answer.waitMs
                            }
                            is SecretAnswer.Wait -> waitUntil = System.currentTimeMillis() + answer.waitMs
                        }
                        attempt++
                        busy = false
                    }
                },
            )
            StatusLine(
                text = when {
                    waitLeft > 0 -> stringResource(R.string.privacy_lock_secret_wait, formatWait(waitLeft))
                    else -> error
                },
                busy = busy,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row {
                    if (lock.canUseFingerprint()) {
                        TextButton(onClick = lock::switchToFingerprint, enabled = !busy) {
                            Text(stringResource(R.string.privacy_lock_secret_use_fingerprint))
                        }
                    }
                    if (lock.canRecover()) {
                        TextButton(onClick = lock::recover, enabled = !busy) {
                            Text(stringResource(R.string.privacy_lock_secret_forgot))
                        }
                    }
                }
                TextButton(onClick = lock::cancelSecret, enabled = !busy) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            }
        }
    }
}

/**
 * Sets a new PIN, password or pattern: once, then again to confirm.
 *
 * [onSet] gets the hash, never the secret. The hashing runs off the main
 * thread before it is called.
 */
@Composable
internal fun AppLockSecretSetupDialog(
    method: AppLockMethod,
    onDismiss: () -> Unit,
    onSet: (hash: String) -> Unit,
) {
    var first by remember(method) { mutableStateOf<String?>(null) }
    var error by remember(method) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val mismatch = stringResource(R.string.privacy_lock_setup_mismatch)
    val tooShort = stringResource(setupHint(method))

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        SecretSheet(
            title = stringResource(if (first == null) setupTitle(method) else confirmTitle(method)),
            icon = method,
        ) {
            SecretInput(
                method = method,
                resetKey = attempt,
                enabled = !busy,
                onSubmit = { input ->
                    val chosen = first
                    when {
                        chosen == null && !AppLockSecret.isAcceptable(method, input) -> error = tooShort
                        chosen == null -> {
                            first = input
                            error = null
                        }
                        chosen != input -> {
                            first = null
                            error = mismatch
                        }
                        else -> {
                            busy = true
                            scope.launch {
                                val hash = withContext(Dispatchers.Default) { AppLockSecret.hash(input) }
                                busy = false
                                onSet(hash)
                            }
                        }
                    }
                    attempt++
                },
            )
            StatusLine(text = error ?: tooShort.takeIf { first == null }, busy = busy, isError = error != null)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            }
        }
    }
}

@Composable
private fun SecretSheet(title: String, icon: AppLockMethod, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        modifier = Modifier
            .padding(24.dp)
            .widthIn(max = 400.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            content()
        }
    }
}

@Composable
private fun StatusLine(text: String?, busy: Boolean, isError: Boolean = true) {
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
        when {
            busy -> LinearProgressIndicator(modifier = Modifier.width(96.dp))
            text != null -> Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The input for [method]: a number pad, a password field or a 3×3 grid. */
@Composable
private fun SecretInput(method: AppLockMethod, resetKey: Int, enabled: Boolean, onSubmit: (String) -> Unit) {
    when (method) {
        AppLockMethod.PIN -> PinPad(resetKey, enabled, onSubmit)
        AppLockMethod.PASSWORD -> PasswordField(resetKey, enabled, onSubmit)
        AppLockMethod.PATTERN -> PatternGrid(resetKey, enabled, onSubmit)
        AppLockMethod.BIOMETRIC -> Unit
    }
}

/**
 * Digits on the app's own pad rather than in a text field. This app is a
 * keyboard, and the keyboard being configured may be the one that would pop
 * up for the field — a lock should not depend on the thing it protects.
 */
@Composable
private fun PinPad(resetKey: Int, enabled: Boolean, onSubmit: (String) -> Unit) {
    var pin by remember(resetKey) { mutableStateOf("") }
    Text(
        // A dot per digit, so the user can count without the digits showing.
        if (pin.isEmpty()) " " else "●".repeat(pin.length),
        style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp),
        modifier = Modifier.height(36.dp),
    )
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (key in row) {
                    FilledTonalIconButton(
                        onClick = {
                            when (key) {
                                "⌫" -> pin = pin.dropLast(1)
                                "✓" -> if (pin.isNotEmpty()) onSubmit(pin)
                                else -> if (pin.length < AppLockSecret.MAX_PIN) pin += key
                            }
                        },
                        enabled = enabled,
                        modifier = Modifier.size(64.dp),
                        shape = CircleShape,
                    ) {
                        when (key) {
                            "⌫" -> Icon(
                                Icons.AutoMirrored.Outlined.Backspace,
                                contentDescription = stringResource(CommonR.string.common_delete),
                            )
                            "✓" -> Icon(
                                Icons.Outlined.Check,
                                contentDescription = stringResource(CommonR.string.common_done),
                            )
                            else -> Text(key, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordField(resetKey: Int, enabled: Boolean, onSubmit: (String) -> Unit) {
    var text by remember(resetKey) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(resetKey) { runCatching { focus.requestFocus() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotEmpty()) onSubmit(text) }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(onClick = { if (text.isNotEmpty()) onSubmit(text) }, enabled = enabled) {
            Icon(Icons.Outlined.Check, contentDescription = stringResource(CommonR.string.common_done))
        }
    }
}

/**
 * A 3×3 grid joined by dragging. A dot the line passes straight over is
 * joined on the way, as on every Android pattern lock; it is submitted when
 * the finger lifts.
 */
@Composable
private fun PatternGrid(resetKey: Int, enabled: Boolean, onSubmit: (String) -> Unit) {
    val dots = remember(resetKey) { mutableStateListOf<Int>() }
    var finger by remember(resetKey) { mutableStateOf<Offset?>(null) }
    val idle = MaterialTheme.colorScheme.outline
    val active = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.privacy_lock_pattern_description)

    fun centre(i: Int, size: Float) = Offset((i % 3 + 0.5f) * size / 3f, (i / 3 + 0.5f) * size / 3f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .aspectRatio(1f)
            .pointerInput(resetKey, enabled) {
                if (!enabled) return@pointerInput
                val side = size.width.toFloat()
                val radius = side / 3f * 0.35f
                fun hit(at: Offset): Int? =
                    (0..8).firstOrNull { (centre(it, side) - at).getDistance() <= radius }
                fun join(i: Int) {
                    if (i in dots) return
                    dots.lastOrNull()?.let { last ->
                        PatternCodec.between(last, i)?.takeIf { it !in dots }?.let(dots::add)
                    }
                    dots.add(i)
                }
                detectDragGestures(
                    onDragStart = { at ->
                        dots.clear()
                        hit(at)?.let(::join)
                        finger = at
                    },
                    onDrag = { change, _ ->
                        finger = change.position
                        hit(change.position)?.let(::join)
                    },
                    onDragEnd = {
                        finger = null
                        if (dots.isNotEmpty()) onSubmit(PatternCodec.encode(dots))
                    },
                    onDragCancel = {
                        finger = null
                        dots.clear()
                    },
                )
            },
    ) {
        val side = size.width
        val stroke = side / 40f
        for (k in 1 until dots.size) {
            drawLine(active, centre(dots[k - 1], side), centre(dots[k], side), stroke, StrokeCap.Round)
        }
        val tip = finger
        if (tip != null && dots.isNotEmpty()) {
            drawLine(active.copy(alpha = 0.5f), centre(dots.last(), side), tip, stroke, StrokeCap.Round)
        }
        for (i in 0..8) {
            val on = i in dots
            drawCircle(if (on) active else idle, radius = side / (if (on) 18f else 26f), center = centre(i, side))
        }
    }
    // The grid is a drawing; say what it is for the screen reader.
    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Milliseconds left until [until], ticking once a second. */
@Composable
private fun rememberCountdown(until: Long): Long {
    var left by remember(until) { mutableLongStateOf((until - System.currentTimeMillis()).coerceAtLeast(0L)) }
    LaunchedEffect(until) {
        while (left > 0) {
            delay(minOf(left, 1000L))
            left = (until - System.currentTimeMillis()).coerceAtLeast(0L)
        }
    }
    return left
}

private fun formatWait(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun enterTitle(method: AppLockMethod) = when (method) {
    AppLockMethod.PASSWORD -> R.string.privacy_lock_secret_title_password
    AppLockMethod.PATTERN -> R.string.privacy_lock_secret_title_pattern
    else -> R.string.privacy_lock_secret_title_pin
}

private fun setupTitle(method: AppLockMethod) = when (method) {
    AppLockMethod.PASSWORD -> R.string.privacy_lock_setup_title_password
    AppLockMethod.PATTERN -> R.string.privacy_lock_setup_title_pattern
    else -> R.string.privacy_lock_setup_title_pin
}

private fun confirmTitle(method: AppLockMethod) = when (method) {
    AppLockMethod.PASSWORD -> R.string.privacy_lock_setup_confirm_password
    AppLockMethod.PATTERN -> R.string.privacy_lock_setup_confirm_pattern
    else -> R.string.privacy_lock_setup_confirm_pin
}

private fun setupHint(method: AppLockMethod) = when (method) {
    AppLockMethod.PASSWORD -> R.string.privacy_lock_setup_hint_password
    AppLockMethod.PATTERN -> R.string.privacy_lock_setup_hint_pattern
    else -> R.string.privacy_lock_setup_hint_pin
}

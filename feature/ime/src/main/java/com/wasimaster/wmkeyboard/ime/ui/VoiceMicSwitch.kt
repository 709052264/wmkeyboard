package com.wasimaster.wmkeyboard.ime.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bluetooth
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HeadsetMic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Smartphone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Usb
import com.wasimaster.wmkeyboard.core.voice.VoiceMics
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.VoiceStatus
import com.wasimaster.wmkeyboard.ime.VoiceUi
import com.wasimaster.wmkeyboard.ime.clipBased
import kotlinx.coroutines.delay

/**
 * The microphone switch every voice surface carries (#581), after FUTO's
 * Bluetooth toggle: an icon button showing the kind of microphone in use,
 * filled while a picked microphone is the one listening and outlined while
 * Android's own choice is. A press moves to the next microphone plugged in
 * ([com.wasimaster.wmkeyboard.ime.VoiceBarAction.CycleMic]); which one is in
 * use is said by [rememberVoiceMicNotice].
 */

/**
 * The microphones the switch can move between: automatic, then each kind
 * plugged in that the session's engine can be pointed at.
 */
internal fun KeyboardUiState.voiceMicChoices(): List<String> =
    VoiceMics.choices(voice.mics, ownCapture = voice.clipBased || settings.whisper.engine != "system")

/**
 * The switch is drawn when there is a microphone to switch to, or a pick
 * already made that the user may want to undo; never in a password field, or
 * before the microphone may be used at all.
 */
internal fun KeyboardUiState.voiceMicSwitchVisible(): Boolean =
    !secureField &&
        voice.status != VoiceStatus.UNAVAILABLE &&
        voice.status != VoiceStatus.NEED_PERMISSION &&
        (voiceMicChoices().size > 1 || settings.voiceBar.mic != VoiceMics.AUTO)

/** The kind shown: the one listening, or before any session the one picked. */
private fun KeyboardUiState.shownMicKind(): String =
    voice.micKind.ifEmpty { settings.voiceBar.mic }

/** "Bluetooth mic (X7)": the kind, and the device's own name where it gives one. */
@Composable
internal fun voiceMicName(voice: VoiceUi, fallbackKind: String): String {
    val kind = stringResource(voiceMicLabelRes(voice.micKind.ifEmpty { fallbackKind }))
    return if (voice.micName.isBlank()) kind else stringResource(R.string.ime_voice_mic_named, kind, voice.micName)
}

/**
 * "Using Bluetooth mic (X7)" for a couple of seconds after the service asks
 * for it ([VoiceUi.micNoticeAt]), then null. Only while the switch is drawn: a
 * keyboard with one microphone has nothing to announce.
 */
@Composable
internal fun rememberVoiceMicNotice(state: KeyboardUiState): String? {
    val at = state.voice.micNoticeAt
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(at) {
        val left = at + VOICE_MIC_NOTICE_MS - SystemClock.uptimeMillis()
        if (at == 0L || left <= 0) {
            showing = false
            return@LaunchedEffect
        }
        showing = true
        delay(left)
        showing = false
    }
    if (!showing || !state.voiceMicSwitchVisible()) return null
    return stringResource(R.string.ime_voice_mic_using, voiceMicName(state.voice, state.settings.voiceBar.mic))
}

/** The switch itself. [size] is the touch target; the outline sits inside it. */
@Composable
internal fun VoiceMicSwitch(
    state: KeyboardUiState,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val picked = state.settings.voiceBar.mic
    // Filled while a microphone the user picked is the one listening, as
    // FUTO fills its Bluetooth button while the headset is in use.
    val active = picked != VoiceMics.AUTO && picked in state.voice.mics
    val shape = kb.toolShape()
    val description = stringResource(R.string.ime_voice_mic_desc, voiceMicName(state.voice, picked))
    val switchLabel = stringResource(R.string.ime_voice_mic_switch)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(shape)
            .clickable(onClickLabel = switchLabel, role = Role.Button) {
                feedback()
                onCycle()
            }
            .semantics { contentDescription = description },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size - 8.dp)
                .clip(shape)
                .then(
                    if (active) {
                        Modifier.background(kb.toolCircleActive)
                    } else {
                        Modifier.border(1.5.dp, kb.toolCircleActive, shape)
                    },
                ),
        ) {
            Icon(
                voiceMicIcon(state.shownMicKind()),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (active) kb.toolCircleActiveIcon else kb.toolbarIcon,
            )
        }
    }
}

/** The switch's wording for a [VoiceMics] kind. */
internal fun voiceMicLabelRes(kind: String): Int = when (kind) {
    VoiceMics.PHONE -> R.string.ime_voice_mic_phone
    VoiceMics.WIRED -> R.string.ime_voice_mic_wired
    VoiceMics.USB -> R.string.ime_voice_mic_usb
    VoiceMics.BLUETOOTH -> R.string.ime_voice_mic_bluetooth
    else -> R.string.ime_voice_mic_auto
}

internal fun voiceMicIcon(kind: String): ImageVector = when (kind) {
    VoiceMics.PHONE -> Icons.Outlined.Smartphone
    VoiceMics.WIRED -> Icons.Outlined.HeadsetMic
    VoiceMics.USB -> Icons.Outlined.Usb
    VoiceMics.BLUETOOTH -> Icons.Outlined.Bluetooth
    else -> Icons.Outlined.Mic
}

/** How long a surface says which microphone is in use; FUTO's toast stays 2.5 s. */
private const val VOICE_MIC_NOTICE_MS = 2500L

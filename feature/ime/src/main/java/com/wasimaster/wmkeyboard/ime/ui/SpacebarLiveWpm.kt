package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R

/**
 * The typing speed the spacebar shows beside its label (#752). The service
 * writes it, on the main thread, each time a word lands and when a pause
 * clears it; 0 means nothing to show, which is also all it ever holds while
 * the setting is off. A snapshot state rather than a [KeyboardUiState] field,
 * so a new reading recomposes the one text that draws it, not the key grid.
 */
internal object LiveWpmReadout {
    val wpm = mutableIntStateOf(0)
}

/**
 * Whether the field the keyboard is drawn for is a password or incognito
 * field ([KeyboardUiState.liveWpmPrivate], #796). Read from the frame's own
 * state, so a field turning private takes the speed off the spacebar on its
 * first frame, before the service has cleared [LiveWpmReadout]. A [State] so
 * only the readout reads it, not every key it is provided around.
 */
internal val LocalLiveWpmPrivate = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/** [LocalLiveWpmPrivate]'s value for the frame that draws [state]. */
@Composable
internal fun rememberLiveWpmPrivate(state: KeyboardUiState): State<Boolean> =
    rememberUpdatedState(state.liveWpmPrivate)

/** The readout itself: "42 wpm", in the label's colour, or nothing. */
@Composable
internal fun SpacebarLiveWpm(contentColor: Color, fontScale: Float) {
    if (LocalLiveWpmPrivate.current.value) return
    val wpm = LiveWpmReadout.wpm.intValue
    if (wpm <= 0) return
    Text(
        text = "$wpm " + stringResource(R.string.ime_typing_wpm_label),
        fontSize = (11 * fontScale).sp,
        fontWeight = FontWeight.Medium,
        color = contentColor.copy(alpha = 0.7f),
        maxLines = 1,
        softWrap = false,
    )
}

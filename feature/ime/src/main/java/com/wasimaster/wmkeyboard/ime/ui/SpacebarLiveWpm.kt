package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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

/** The readout itself: "42 wpm", in the label's colour, or nothing. */
@Composable
internal fun SpacebarLiveWpm(contentColor: Color, fontScale: Float) {
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

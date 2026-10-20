package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.GestureSettings
import com.wasimaster.wmkeyboard.core.settings.TWO_THUMB_PAUSE_RANGE
import com.wasimaster.wmkeyboard.core.settings.TWO_THUMB_RETRACT_RANGE

/** How long a live two-thumb stroke may go without a preview before it is taken as ended (#849). */
internal const val LIVE_STROKE_QUIET_MS = 1500L

/** How far back the word behind an all-thumbs-up space is read, space included (#747). */
internal const val ALL_UP_RETRACT_LOOKBACK = 48

/**
 * How long the all-thumbs-up word end waits before it types a word (#747): the
 * swiped-word pause for a word with a swipe in it, the tapped-word pause for
 * one of taps alone, and never less than the one-letter floor for a word of
 * one letter, which is far more often the start of a word than a word.
 */
internal fun allUpPauseMs(gesture: GestureSettings, swiped: Boolean, letters: Int): Long {
    val base = if (swiped) gesture.twoThumbPauseMs else gesture.twoThumbTapPauseMs
    val pause = if (letters == 1) maxOf(base, gesture.twoThumbOneLetterPauseMs) else base
    return pause.coerceIn(TWO_THUMB_PAUSE_RANGE).toLong()
}

/** How long after an all-thumbs-up space a letter may still take it back (#747); 0 never does. */
internal fun allUpRetractWindowMs(gesture: GestureSettings): Long =
    gesture.twoThumbRetractWindowMs.coerceIn(TWO_THUMB_RETRACT_RANGE).toLong()

/**
 * Whether a thumb that landed at [landedAt] (uptime ms) is still inside the
 * window an all-thumbs-up space that landed at [spaceAt] leaves open for
 * [windowMs]. The landing counts, not the letter it types: a swipe begun in
 * time still joins, however long it takes to draw.
 */
internal fun insideRetractWindow(spaceAt: Long, landedAt: Long, windowMs: Long): Boolean =
    windowMs > 0 && landedAt >= spaceAt && landedAt - spaceAt <= windowMs

/**
 * The dictionary gate in front of taking an all-thumbs-up space back (#747):
 * whether [word], followed by any one of [starts] (the letter tapped, or each
 * letter a swipe may have started on), still leads to a word the keyboard
 * knows. `car` then `p` does ("carpet"); `car` then a swipe starting on `d`
 * near nothing that follows "car" does not, and the space stays.
 */
internal fun continuesWord(word: String, starts: Collection<String>, isLivePrefix: (String) -> Boolean): Boolean =
    word.isNotEmpty() && starts.any { it.isNotEmpty() && isLivePrefix(word + it) }

/**
 * Whether a word reopened by taking its space back still holds together
 * (#747): [shown] is what the field shows now, which has to be [reopened]
 * carried on by at least one letter, that letter one of [starts], and the
 * whole still a word ([complete]) or on its way to one. When it stops
 * holding, the space goes back in after [reopened] and the rest is a word of
 * its own: `car`, `d`, `o`, `g` reads "card", "cardo", and splits at the `g`
 * into "car dog".
 */
internal fun reopenedWordHolds(
    reopened: String,
    shown: String,
    starts: Collection<String>,
    complete: Boolean,
    isLivePrefix: (String) -> Boolean,
): Boolean {
    if (shown.length <= reopened.length || !shown.startsWith(reopened, ignoreCase = true)) return false
    val next = String(Character.toChars(shown.codePointAt(reopened.length))).lowercase()
    if (starts.none { it.equals(next, ignoreCase = true) }) return false
    return complete || isLivePrefix(shown)
}

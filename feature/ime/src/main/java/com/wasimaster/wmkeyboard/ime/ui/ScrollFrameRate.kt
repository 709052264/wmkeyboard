package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate

/**
 * Asks the display for its high refresh rate while [scrolling] (#847).
 *
 * A phone with an adaptive refresh rate raises it for the app being touched,
 * and on a Galaxy S25+ the keyboard's window was not counted as one: its
 * toolbox and gallery scrolled at 60 Hz while every app scrolled at 120. The
 * request is the window's own vote, and it is withdrawn the moment the
 * scroll stops, so a still keyboard costs the battery nothing. Below Android
 * 15 nothing reads it.
 */
internal fun Modifier.highFrameRateWhile(scrolling: Boolean): Modifier =
    preferredFrameRate(if (scrolling) FrameRateCategory.High else FrameRateCategory.Default)

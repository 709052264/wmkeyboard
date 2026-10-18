package com.wasimaster.wmkeyboard.app.tips

import androidx.compose.runtime.Composable

/**
 * The [rememberTipJar] of every channel that is not Play. These builds keep
 * the SupportKori link instead, and must not link Play Billing at all: F-Droid
 * refuses the library, and a direct-download APK has no Play order to finish.
 */
@Composable
internal fun rememberTipJar(): TipJar = NoTipJar

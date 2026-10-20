package com.wasimaster.wmkeyboard.core.mlkit

import android.content.Context

/** F-Droid stand-in: no ML Kit in the build, so nothing to initialize. */
object MlKitInit {

    fun ensure(context: Context) = Unit
}

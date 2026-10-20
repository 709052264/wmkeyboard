package com.wasimaster.wmkeyboard.ime.aichat

import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript

/**
 * F-Droid's stand-in for the chat's picture reader (#698). That build ships
 * no ML Kit; the online engine of the scan text tool still
 * works, and without it the AI is not offered the tool at all.
 */
object ChatImageOcr {

    /** No recognizer in this build. */
    val available: Boolean get() = false

    /** Nothing to read with. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun read(bitmap: Bitmap, script: MlKitScript = MlKitScript.LATIN): String = ""
}

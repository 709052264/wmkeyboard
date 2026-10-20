package com.wasimaster.wmkeyboard.ime.reply

import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript
import com.wasimaster.wmkeyboard.core.tools.ReplyOcrLines
import com.wasimaster.wmkeyboard.core.tools.ReplyTurn

/**
 * F-Droid's stand-in for the screenshot reader (#234).
 *
 * That build ships no ML Kit, so there is no recognizer to run. The
 * reply tool asks [available] first and, when it is false, offers a screenshot
 * only to the providers that can read a picture themselves — which is the
 * honest answer rather than an empty result the user would read as a bug.
 */
object ReplyOcr {

    /** No recognizer in this build. */
    val available: Boolean get() = false

    /** Nothing to read with. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun read(
        bitmap: Bitmap,
        frame: ReplyOcrLines.ReplyOcrFrame = ReplyOcrLines.ReplyOcrFrame(),
        script: MlKitScript = MlKitScript.LATIN,
    ): List<ReplyTurn> = emptyList()
}

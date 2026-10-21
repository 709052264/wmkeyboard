package com.wasimaster.wmkeyboard.ime.ocr

import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript

/**
 * F-Droid stand-in for the scanner's way to ML Kit, which this build does
 * not have. [com.wasimaster.wmkeyboard.core.ocr.OcrLanguages] never routes a
 * read here in this build, so none of it is reached.
 */
internal object MlKitOcr {

    const val IN_BUILD = false

    fun ready(script: MlKitScript): Boolean = false

    fun isDownloading(failure: Throwable?): Boolean = false

    class Readers : AutoCloseable {
        fun prepare(script: MlKitScript) = Unit

        suspend fun read(script: MlKitScript, bitmap: Bitmap): List<List<String>> =
            throw UnsupportedOperationException("ML Kit is not in this build")

        override fun close() = Unit
    }
}

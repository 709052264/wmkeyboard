package com.wasimaster.wmkeyboard.ime.ocr

import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript

/**
 * ML Kit's text recognizers, one per script (#757). The Latin one was the
 * only one the keyboard carried, so Chinese, Japanese, Korean and Devanagari
 * text came back as Latin guesses. Each of the others reads Latin too.
 *
 * The other four are in Play builds only ([MlKitScriptOptions]), as the
 * unbundled artifacts: Play services fetches a script's model the first time
 * it is used, and until it is there a read fails with [isDownloading].
 * Sideload builds stay Latin only and read those scripts with Tesseract.
 */
internal object MlKitRecognizers {

    /** A new recognizer for [script]; the caller closes it. */
    fun client(script: MlKitScript): TextRecognizer =
        TextRecognition.getClient(MlKitScriptOptions.of(script) ?: TextRecognizerOptions.DEFAULT_OPTIONS)

    /** True when [failure] means Play services is still downloading the model. */
    fun isDownloading(failure: Throwable?): Boolean =
        failure is MlKitException && failure.errorCode == MlKitException.UNAVAILABLE
}

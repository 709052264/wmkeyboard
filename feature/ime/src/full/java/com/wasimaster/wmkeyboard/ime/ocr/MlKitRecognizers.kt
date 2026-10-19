package com.wasimaster.wmkeyboard.ime.ocr

import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript

/**
 * ML Kit's text recognizers, one per script (#757). The Latin one was the
 * only one the keyboard carried, so Chinese, Japanese, Korean and Devanagari
 * text came back as Latin guesses. Each of the others reads Latin too.
 *
 * The other four are the unbundled artifacts in every full build: Play
 * services fetches a script's model the first time it is used, and until it
 * is there a read fails with [isDownloading]. Without Play services
 * [com.wasimaster.wmkeyboard.core.ocr.OcrLanguages.mlKitScript] never hands
 * them out, and those scripts read with Paddle or Tesseract.
 */
internal object MlKitRecognizers {

    /** A new recognizer for [script]; the caller closes it. */
    fun client(script: MlKitScript): TextRecognizer = TextRecognition.getClient(
        when (script) {
            MlKitScript.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
            MlKitScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
            MlKitScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
            MlKitScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            MlKitScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
        }
    )

    /** True when [failure] means Play services is still downloading the model. */
    fun isDownloading(failure: Throwable?): Boolean =
        failure is MlKitException && failure.errorCode == MlKitException.UNAVAILABLE
}

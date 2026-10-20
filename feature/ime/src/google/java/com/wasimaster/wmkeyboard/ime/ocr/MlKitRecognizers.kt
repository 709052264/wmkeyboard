package com.wasimaster.wmkeyboard.ime.ocr

import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.FeatureModules.awaitInstalled
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript

/**
 * ML Kit's text recognizers, one per script (#757). The Latin one was the
 * only one the keyboard carried, so Chinese, Japanese, Korean and Devanagari
 * text came back as Latin guesses. Each of the others reads Latin too.
 *
 * The other four are the unbundled artifacts in every build with ML Kit: Play
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

    /** The bundled Latin reader's library, fetched on demand with native delivery. */
    const val OCR_LIBRARY = "mlkit_google_ocr_pipeline"

    /** The barcode reader's library, the same way. */
    const val BARCODE_LIBRARY = "barhopper_v3"

    /**
     * Whether [script]'s reader can run now. Only the bundled Latin reader
     * has a library of its own; the others are Play services'. On a build
     * with native delivery that library may not be here yet
     * ([FeatureModules.scanner]); everywhere else this is always true.
     */
    fun ready(script: MlKitScript): Boolean =
        script != MlKitScript.LATIN || (FeatureModules.scanner.installed && NativeDelivery.preload(OCR_LIBRARY))

    /** [ready], fetching the library first when it is missing. False when it could not be. */
    suspend fun awaitReady(script: MlKitScript): Boolean =
        script != MlKitScript.LATIN || (FeatureModules.scanner.awaitInstalled() && NativeDelivery.preload(OCR_LIBRARY))

    /** Whether the barcode reader can run now. */
    fun barcodeReady(): Boolean = FeatureModules.scanner.installed && NativeDelivery.preload(BARCODE_LIBRARY)

    /** True when [failure] means Play services is still downloading the model. */
    fun isDownloading(failure: Throwable?): Boolean =
        failure is MlKitException && failure.errorCode == MlKitException.UNAVAILABLE
}

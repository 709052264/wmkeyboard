package com.wasimaster.wmkeyboard.ime.ocr

import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript

/**
 * Sideload builds: no recognizer past Latin. The other four ship only in Play
 * builds (see the twin in `src/ocrplay`), and
 * [com.wasimaster.wmkeyboard.core.ocr.OcrLanguages.mlKitScript] never asks for
 * them here, so their scripts read with Tesseract.
 */
internal object MlKitScriptOptions {
    @Suppress("UNUSED_PARAMETER")
    fun of(script: MlKitScript): TextRecognizerOptionsInterface? = null
}

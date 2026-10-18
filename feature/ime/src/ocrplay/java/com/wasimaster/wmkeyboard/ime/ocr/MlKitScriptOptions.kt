package com.wasimaster.wmkeyboard.ime.ocr

import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript

/**
 * Play builds only (#757): the four extra scripts' recognizers, whose models
 * Play services downloads on first use. Compiled in only when
 * `wmkb.enablePlayStore` is on; the sideload twin in `src/ocrsideload` has
 * none, so those builds carry no part of them.
 */
internal object MlKitScriptOptions {
    fun of(script: MlKitScript): TextRecognizerOptionsInterface? = when (script) {
        MlKitScript.LATIN -> null
        MlKitScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
        MlKitScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
        MlKitScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
        MlKitScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
    }
}

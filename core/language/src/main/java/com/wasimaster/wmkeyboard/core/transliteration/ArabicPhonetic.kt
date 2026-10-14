package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Arabic phonetic: Arabizi in, Arabic script out ("7abibi", "3arabi" →
 * حبيبي, عربي). The table lives in the Rust engine
 * (`native/wm-engine/src/arabic.rs`); this is the call into it.
 */
object ArabicPhonetic {

    /** Transliterates Arabizi, one word or free text, into the Arabic script. */
    fun transliterate(input: String): String = EngineNative.arabic(input)

    /** Every reading of [input] worth offering, the literal one first. */
    fun variants(input: String): List<String> = EngineNative.arabicVariants(input).toList()
}

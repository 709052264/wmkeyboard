package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Persian phonetic: Finglish in, Persian script out ("salam khoobi?" →
 * سلام خوبی؟). The table lives in the Rust engine
 * (`native/wm-engine/src/persian.rs`); this is the call into it.
 */
object PersianPhonetic {

    /** Transliterates Finglish, one word or free text, into the Persian script. */
    fun transliterate(input: String): String = EngineNative.persian(input)

    /** Every reading of [input] worth offering, the literal one first. */
    fun variants(input: String): List<String> = EngineNative.persianVariants(input).toList()
}

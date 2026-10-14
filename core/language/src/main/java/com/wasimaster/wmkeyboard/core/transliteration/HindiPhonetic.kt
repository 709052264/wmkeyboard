package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Rule-based phonetic transliteration from romanized Hindi ("Hinglish") to
 * Devanagari: "kaise ho" → कैसे हो. The rules live in the Rust engine
 * (`native/wm-engine/src/hindi.rs`); this is the call into it.
 *
 * Dental against retroflex, and every other distinction the spelling does
 * not carry, is the dictionary's job ([HindiPhoneticIndex]), not this
 * transliterator's.
 */
object HindiPhonetic {

    /** Transliterates romanized Hindi, one word or free text, into Devanagari. */
    fun transliterate(input: String): String = EngineNative.hindi(input)

    /**
     * Every reading of [input] worth offering, the likely one first: the same
     * spelling with its last "a" long, with every cluster joined, with none
     * joined, and with its final vowel left short. Never the same reading twice.
     */
    fun variants(input: String): List<String> = EngineNative.hindiVariants(input).toList()
}

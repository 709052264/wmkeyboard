package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Bengali script written back in Latin letters, the way people spell it in a
 * chat: তোমার → tomar, ধন্যবাদ → dhonnobad, কী → ki. Heuristic by nature: a
 * converter puts a dictionary of known spellings in front of this and lets
 * the rules catch the rest. The rules live in the Rust engine
 * (`native/wm-engine/src/bengali_romanizer.rs`); this is the call into it.
 */
object BengaliRomanizer {

    /** Decomposed nukta letters, two-part vowels and the অ+া misspelling folded to one code point each. */
    fun normalize(text: String): String = EngineNative.bengaliNormalize(text)

    /** Whole text: every Bengali run becomes Latin, everything else passes through. */
    fun romanize(text: String): String = EngineNative.bengaliRomanize(text)

    /** One Bengali word, without spaces. */
    fun romanizeWord(word: String): String = EngineNative.bengaliRomanizeWord(word)
}

package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Hindi written back in Latin letters, the way people spell it in a chat:
 * करना → karna, नमस्ते → namaste, लड़की → ladki. The rules live in the Rust
 * engine (`native/wm-engine/src/devanagari_romanizer.rs`); this is the call
 * into it.
 */
object DevanagariRomanizer {

    /** Devanagari letters and signs, not its digits, not the danda. */
    fun isDevanagari(c: Char): Boolean = c.code in 0x0900..0x0963 || c.code in 0x0970..0x097F

    /** Precomposed nukta letters taken apart, and the joiners dropped. */
    fun normalize(text: String): String = EngineNative.devanagariNormalize(text)

    /** Whole text: every Devanagari run becomes Latin, everything else passes through. */
    fun romanize(text: String): String = EngineNative.devanagariRomanize(text)

    /** One Hindi word, without spaces. */
    fun romanizeWord(word: String): String = EngineNative.devanagariRomanizeWord(word)
}

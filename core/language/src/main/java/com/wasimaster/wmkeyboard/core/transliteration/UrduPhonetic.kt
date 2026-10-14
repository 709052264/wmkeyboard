package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Rule-based phonetic transliteration from roman Urdu to the Urdu script:
 * "aap kaise hain" → آپ کیسے ہیں. The rules live in the Rust engine
 * (`native/wm-engine/src/urdu.rs`, on the walk every Arabic-script language
 * shares); this is the call into it.
 */
object UrduPhonetic {

    /** Transliterates roman Urdu, one word or free text, into the Urdu script. */
    fun transliterate(input: String): String = EngineNative.urdu(input)

    /**
     * Every reading of [input] worth offering, the literal one first. Only
     * the "a" moves: whether a lone "a" inside a word is a long ا or nothing.
     */
    fun variants(input: String): List<String> = EngineNative.urduVariants(input).toList()
}

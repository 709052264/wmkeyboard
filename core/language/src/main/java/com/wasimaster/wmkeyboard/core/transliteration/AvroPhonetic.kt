package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Rule-based Avro-style phonetic transliteration from romanized Bengali
 * ("banglish") to Bengali script. The rules live in the Rust engine
 * (`native/wm-engine/src/avro.rs`, which documents every one of them); this
 * is the call into it.
 *
 * Dictionary-level corrections ("asi" → আছি rather than আসি) are the
 * suggestion engine's job, not this transliterator's.
 */
object AvroPhonetic {

    /** Transliterates one romanized word (or free text) into Bengali script. */
    fun transliterate(input: String): String = EngineNative.avro(input)
}

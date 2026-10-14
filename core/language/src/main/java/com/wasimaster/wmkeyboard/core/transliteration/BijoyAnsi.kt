package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Unicode Bengali to "ANSI" Bengali: the encoding Bijoy Bayanno and the
 * SutonnyMJ family of fonts used before Unicode, where a Bengali glyph sits on
 * a Latin code point. আমার সোনার বাংলা is `Avgvi †mvbvi evsjv`.
 *
 * A port of okkhor52.com's converters, one per [Version], living in the Rust
 * engine (`native/wm-engine/src/bijoy.rs` and its generated tables); this is
 * the call into it.
 */
object BijoyAnsi {

    /** The ANSI encoding to write; each is a different font's layout. */
    enum class Version(val number: Int) {
        V1(1),
        V2(2),
        V3(3),
        ;

        companion object {
            /** The most common one, and what the converters open on. */
            val DEFAULT = V2

            /** The version numbered [number], or [DEFAULT] for one this build does not know. */
            fun of(number: Int): Version = entries.firstOrNull { it.number == number } ?: DEFAULT
        }
    }

    /**
     * Whether [text] holds anything the converter changes: a Bengali letter,
     * sign or digit, the danda, or a curly quote (the fonts keep glyphs of
     * their own on those code points, so a quote has to move too).
     */
    fun needsConversion(text: CharSequence): Boolean = text.any(::converts)

    /** Whether [c] is one of the characters [needsConversion] looks for. */
    fun converts(c: Char): Boolean =
        c in 'ঀ'..'৿' || c == '।' || c == '॥' || c in '‘'..'’' || c in '“'..'”'

    /**
     * [text] in ANSI [version]. [before] is what sits in front of it in the
     * field, of which only the last character is read; empty means [text]
     * starts the field. Text with nothing Bengali in it comes back as it was.
     */
    fun convert(text: String, version: Version = Version.DEFAULT, before: CharSequence = ""): String {
        if (!needsConversion(text)) return text
        return EngineNative.bijoy(text, version.number, before.takeLast(1).toString())
    }
}

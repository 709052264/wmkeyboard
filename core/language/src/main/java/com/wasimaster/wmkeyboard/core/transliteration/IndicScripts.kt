package com.wasimaster.wmkeyboard.core.transliteration

/**
 * The Indic scripts the Indic engine writes, and the move between each of
 * them and Devanagari.
 *
 * Every Unicode block from Devanagari to Malayalam is laid out in parallel,
 * the same letter 0x80 apart each time, which is what [block] and [isNative]
 * use. The move itself ([fromDevanagari], [toDevanagari]), with each script's
 * own conventions (Tamil's folds, Malayalam's chillus, Gurmukhi's addak and
 * tippi, Odia's ya), lives in the Rust engine
 * (`native/wm-engine/src/indic_script.rs`). The declaration order here is the
 * ordinal the engine reads; do not reorder.
 */
enum class IndicScript(private val offset: Int) {
    DEVANAGARI(0x000),
    BENGALI(0x080),
    GURMUKHI(0x100),
    GUJARATI(0x180),
    ORIYA(0x200),
    TAMIL(0x280),
    TELUGU(0x300),
    KANNADA(0x380),
    MALAYALAM(0x400),
    ;

    /** This script's Unicode block. */
    val block: IntRange get() = (DEV_START + offset)..(DEV_END + offset)

    /** A letter or sign of this script, not its digits, not the dandas. */
    fun isNative(c: Char): Boolean {
        val code = c.code - offset
        return c.code in block && code !in 0x0966..0x096F && code != 0x0964 && code != 0x0965
    }

    /** Devanagari, as the engine spells it, moved into this script. */
    fun fromDevanagari(dev: String): String =
        if (this == DEVANAGARI) dev else EngineNative.indicFromDevanagari(ordinal, dev)

    /**
     * Text in this script spelled in Devanagari, letter for letter, with this
     * script's own conventions undone.
     */
    fun toDevanagari(text: String): String =
        if (this == DEVANAGARI) text else EngineNative.indicToDevanagari(ordinal, text)

    private companion object {
        const val DEV_START = 0x0900
        const val DEV_END = 0x097F
    }
}

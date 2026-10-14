package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Bengali grapheme-cluster helpers for editing. Backspace normally removes
 * one code unit, which visually shatters a conjunct (জ্ঞ → জ্ + delete → জ).
 * With the conjunct-aware option the whole cluster is removed as a unit. The
 * rules live in the Rust engine (`native/wm-engine/src/bengali_graphemes.rs`);
 * this is the call into it.
 */
object BengaliGraphemes {

    /** What a vowel key should produce given the character before the cursor. Ordinals cross the bridge; do not reorder. */
    enum class VowelKeyForm { INDEPENDENT, KAR, GLIDE }

    /**
     * Chooses the vowel-key form from the preceding character: kar after
     * anything it can attach to, the য়-glide after a vowel (sign or
     * independent, including trailing modifiers like ঁ), and the independent
     * letter at a word start or after non-Bengali text.
     */
    fun vowelFormAfter(previous: Char?): VowelKeyForm =
        VowelKeyForm.entries[EngineNative.vowelFormAfter(previous?.code ?: -1)]

    /**
     * Output of a vowel key whose base (kar) form is [kar], in the given
     * [form]. Null for a key that is not a vowel.
     */
    fun vowelKeyText(kar: Char, form: VowelKeyForm): String? = EngineNative.vowelKeyText(kar.code, form.ordinal)

    /** Anything in the Bengali block: letters, signs, digits and punctuation. */
    fun isBengali(c: Char): Boolean = c.code in 0x0980..0x09FF

    /**
     * How many chars to remove from the end of [text] to delete the last
     * Bengali cluster as one unit. Returns 0 when [text] is empty; falls
     * back to 1 (or 2 for a surrogate pair) for non-Bengali endings.
     */
    fun clusterDeleteLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        // Only the tail can be part of the last cluster; no cluster is this long.
        return EngineNative.clusterDeleteLength(text.takeLast(TAIL).toString())
    }

    private const val TAIL = 32
}

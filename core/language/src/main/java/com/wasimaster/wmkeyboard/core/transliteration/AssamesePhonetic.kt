package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Assamese phonetic: roman letters in, Assamese script out, on the Avro
 * engine with three fix-ups of Assamese's own (ৰ for র, "x" as স, ৱ for ওয়
 * inside a word), all in the Rust engine (`native/wm-engine/src/assamese.rs`);
 * this is the call into it.
 */
object AssamesePhonetic {

    /** Transliterates roman Assamese, one word or free text, into the Assamese script. */
    fun transliterate(input: String): String = EngineNative.assamese(input)

    /**
     * Assamese text in the Bangla letters Avro and the Bengali index read: ৰ as
     * র and ৱ as ব. The way into [BengaliPhoneticIndex] and [BengaliRomanizer].
     * Two characters, done here rather than across the bridge, because the
     * index calls it once per word of a 300k-word list.
     */
    fun toBengali(text: String): String = text.replace('ৰ', 'র').replace('ৱ', 'ব')

    /** An Assamese letter or sign: the Bengali block, which holds ৰ and ৱ too. */
    fun isAssamese(c: Char): Boolean = BengaliGraphemes.isBengali(c)
}

/**
 * Reverse-phonetic lookup for Assamese: [BengaliPhoneticIndex] over the list
 * spelled in Bangla letters ([AssamesePhonetic.toBengali]), answering in the
 * list's own Assamese spelling.
 */
class AssamesePhoneticIndex(entries: List<Pair<String, Int>>) : PhoneticIndex {

    private val native = HashMap<String, String>()

    private val inner: BengaliPhoneticIndex

    init {
        val kept = boundedEntries(entries)
        val converted = ArrayList<Pair<String, Int>>(kept.size)
        for ((word, frequency) in kept) {
            val bn = AssamesePhonetic.toBengali(word)
            native.putIfAbsent(bn, word)
            converted += bn to frequency
        }
        inner = BengaliPhoneticIndex(converted)
    }

    override val isEmpty: Boolean get() = inner.isEmpty

    override fun lookup(input: String): List<String> =
        inner.lookup(input.replace('x', 's')).mapNotNull { native[it] }.distinct()

    override fun frequencyOf(word: String): Int = inner.frequencyOf(AssamesePhonetic.toBengali(word))

    override fun matchStrength(input: String): Int = inner.matchStrength(input.replace('x', 's'))

    override val maxFrequency: Int get() = inner.maxFrequency
}

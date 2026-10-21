package com.wasimaster.wmkeyboard.core.input.rime

/**
 * A composing buffer spelled in the keys a RIME schema reads, with the way
 * back: [bufferEnd]`[k]` is how many buffer characters the first `k` input
 * characters cover, any dropped characters right after them included (a
 * Jyutping tone digit goes with its syllable), and so any added ones (the
 * `'` after a picked T9 syllable).
 */
class RimeInput(val input: String, private val bufferEnd: IntArray) {

    /** Buffer characters covered by the first [inputChars] characters of [input]. */
    fun bufferChars(inputChars: Int): Int = bufferEnd[inputChars.coerceIn(0, bufferEnd.size - 1)]
}

/**
 * What the keyboard's layouts type, in the keys RIME's schemas expect.
 *
 * The layouts stay the ones the built-in composers use, so their keys are
 * what the user sees on screen: bopomofo on the Zhuyin board, radicals on
 * Cangjie, stroke glyphs on the stroke pad. RIME's schemas read the keys of a
 * desktop keyboard instead (bopomofo on the Dachen map, strokes as h s p n z),
 * so each character is mapped on the way in, one character to at most one.
 * The one addition is T9's: a syllable picked from the reading column sits
 * in the buffer as letters (`xi26`), and RIME's T9 schema spells every
 * syllable both in letters and in digits, mixed group by group, so `xi26`
 * would read as one syllable, `xiao`. A `'` after the picked letters keeps
 * them a syllable of their own: `xi'26` is 西安.
 */
object RimeKeys {

    /** The standard Dachen (大千) map, as RIME's bopomofo schema spells it. */
    private val DACHEN: Map<Char, Char> = mapOf(
        'ㄅ' to '1', 'ㄆ' to 'q', 'ㄇ' to 'a', 'ㄈ' to 'z', 'ㄉ' to '2', 'ㄊ' to 'w', 'ㄋ' to 's', 'ㄌ' to 'x',
        'ㄍ' to 'e', 'ㄎ' to 'd', 'ㄏ' to 'c', 'ㄐ' to 'r', 'ㄑ' to 'f', 'ㄒ' to 'v', 'ㄓ' to '5', 'ㄔ' to 't',
        'ㄕ' to 'g', 'ㄖ' to 'b', 'ㄗ' to 'y', 'ㄘ' to 'h', 'ㄙ' to 'n', 'ㄧ' to 'u', 'ㄨ' to 'j', 'ㄩ' to 'm',
        'ㄚ' to '8', 'ㄛ' to 'i', 'ㄜ' to 'k', 'ㄝ' to ',', 'ㄞ' to '9', 'ㄟ' to 'o', 'ㄠ' to 'l', 'ㄡ' to '.',
        'ㄢ' to '0', 'ㄣ' to 'p', 'ㄤ' to ';', 'ㄥ' to '/', 'ㄦ' to '-',
        'ˇ' to '3', 'ˋ' to '4', 'ˊ' to '6', '˙' to '7',
    )

    /** Cangjie radicals as printed on the keys, to their letters. */
    private val CANGJIE: Map<Char, Char> = mapOf(
        '日' to 'a', '月' to 'b', '金' to 'c', '木' to 'd', '水' to 'e', '火' to 'f', '土' to 'g', '竹' to 'h',
        '戈' to 'i', '十' to 'j', '大' to 'k', '中' to 'l', '一' to 'm', '弓' to 'n', '人' to 'o', '心' to 'p',
        '手' to 'q', '口' to 'r', '尸' to 's', '廿' to 't', '山' to 'u', '女' to 'v', '田' to 'w', '難' to 'x',
        '卜' to 'y',
    )

    /** Stroke glyphs and the stroke pad's digits, to RIME's h s p n z. */
    private val STROKE: Map<Char, Char> = mapOf(
        '一' to 'h', '丨' to 's', '丿' to 'p', '丶' to 'n', '乙' to 'z',
        '1' to 'h', '2' to 's', '3' to 'p', '4' to 'n', '5' to 'z',
        'h' to 'h', 's' to 's', 'p' to 'p', 'n' to 'n', 'z' to 'z', 'd' to 'n',
    )

    /** [buffer] in [method]'s RIME keys. */
    fun translate(method: RimeMethod, buffer: String): RimeInput {
        val input = StringBuilder(buffer.length)
        // Room for a `'` after every buffer character, which T9 can add.
        val ends = IntArray(2 * buffer.length + 1)
        var kept = 0
        for ((i, c) in buffer.withIndex()) {
            val key: Char? = when (method) {
                RimeMethod.PINYIN, RimeMethod.T9_PINYIN -> c.lowercaseChar()
                // RIME's jyut6ping3 reads no tone digits (its dictionary is
                // toneless, as the built-in one is), so they are dropped and
                // ride along with the syllable they follow.
                RimeMethod.JYUTPING -> c.lowercaseChar().takeUnless { it in '0'..'9' }
                RimeMethod.ZHUYIN -> DACHEN[c] ?: c.lowercaseChar()
                RimeMethod.CANGJIE -> CANGJIE[c] ?: c.lowercaseChar()
                // The wildcard stroke has no RIME counterpart.
                RimeMethod.STROKE -> STROKE[c.lowercaseChar()]
            }
            if (key != null) {
                input.append(key)
                kept++
            }
            if (method == RimeMethod.T9_PINYIN && c.isLetter() && buffer.getOrNull(i + 1)?.isDigit() == true) {
                ends[kept] = i + 1
                input.append('\'')
                kept++
            }
            ends[kept] = i + 1
        }
        return RimeInput(input.toString(), ends.copyOf(kept + 1))
    }
}

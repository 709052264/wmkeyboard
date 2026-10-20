package com.wasimaster.wmkeyboard.core.input.composer

/**
 * The Chinese text just before the caret, for the decoder to read the next
 * buffer as following it: `ta` after 我爱 and after 她说 are not the same
 * question, and a word pair can only tell them apart if it knows the word
 * before (fcitx's "keep current context").
 *
 * The service sets [before] from the field when a buffer starts, and extends
 * it with each conversion it commits. Empty is a working state: the decoder
 * then reads the buffer as the start of a sentence, as it always did.
 */
object CjkContext {

    /** Longest stretch kept: the longest word a context lookup tries, and some. */
    const val KEEP = 8

    /** Longest suffix of [before] tried as the previous word. */
    private const val MAX_WORD = 4

    /** How much of [text] is kept: a few of Mozc's history segments. */
    private const val TEXT_KEEP = 16

    @Volatile
    var before: String = ""
        set(value) {
            // Extending what was there extends [text] too; anything else
            // replaces both.
            val old = field
            text = if (old.isNotEmpty() && value.startsWith(old)) {
                (text + value.substring(old.length)).takeLast(TEXT_KEEP)
            } else {
                value.takeLast(TEXT_KEEP)
            }
            field = hanTail(value)
        }

    /**
     * The same context in every script, not only its Han tail: what Mozc
     * reads on from, since Japanese ends most phrases in kana (今日は) and
     * [before] would be empty after each of them. Follows [before]: emptied
     * when it is, extended when it is.
     */
    @Volatile
    var text: String = ""
        private set

    /** Both contexts from the field's own text before the caret. */
    fun fromField(textBeforeCaret: CharSequence) {
        before = hanTail(textBeforeCaret)
        text = textBeforeCaret.toString().takeLast(TEXT_KEEP)
    }

    /** [before] followed by [committed], as the context for what is typed next. */
    fun committed(committed: String) {
        // [text] keeps the kana [before]'s Han tail drops (今日は, then 天気).
        val all = (text + committed).takeLast(TEXT_KEEP)
        before += committed
        text = all
    }

    /**
     * The previous word for the decoder: the longest suffix of [text], four
     * characters at most, that [known] recognises. Null when none is.
     */
    fun previousWord(text: String, known: (String) -> Boolean): String? {
        if (text.isEmpty()) return null
        val points = text.codePointCount(0, text.length)
        for (len in minOf(MAX_WORD, points) downTo 1) {
            val start = text.offsetByCodePoints(text.length, -len)
            val suffix = text.substring(start)
            if (known(suffix)) return suffix
        }
        return null
    }

    /**
     * The run of Han characters [text] ends with, at most [KEEP] of them: a
     * space, a mark or a Latin word in between ends the sentence the decoder
     * could read on from.
     */
    fun hanTail(text: CharSequence): String {
        var start = text.length
        var count = 0
        while (start > 0 && count < KEEP) {
            val cp = Character.codePointBefore(text, start)
            if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) break
            start -= Character.charCount(cp)
            count++
        }
        return text.subSequence(start, text.length).toString()
    }
}

package com.wasimaster.wmkeyboard.ime

/**
 * The one question every "should I type a space here?" rule asks of the text in
 * front of the caret: is a separator already there?
 *
 * Three rules ask it — the space a glided word is followed by, the trailing
 * space a tapped suggestion earns, and the space after an auto-spaced
 * punctuation mark — and all three mean the same thing by it, so they share an
 * answer rather than each spelling one out.
 */

/**
 * The blanks that already separate the caret from what follows, so a word
 * committed here needs no space of its own.
 *
 * A line break is deliberately not among them, and that absence is the whole
 * reason this is not `Char.isWhitespace`. Editors built on a web view hand the
 * IME the rest of the document rather than the rest of the line, so with the
 * caret at the end of any line but the last, [android.view.inputmethod.InputConnection.getTextAfterCursor]
 * answers with the next line's "\n". Reading that as "a space is already there"
 * left every glided word at the end of a line running straight into the word
 * tapped after it — "when I" came out "wheni" in Obsidian (issue #27). A
 * newline ends the line; it does not space two words that share one.
 *
 * The no-break space counts because a field that already has one is spaced,
 * whoever typed it, and a second gap would be as wrong there as anywhere.
 */
private val CARET_SPACERS = charArrayOf(' ', ' ', '\t')

/**
 * Whether [after] — the text right after the caret, as the field reported it —
 * already starts with a space. Null and empty are both "nothing follows", which
 * is not spaced: the caret at the end of the text still needs its own.
 */
internal fun spacedAfterCaret(after: CharSequence?): Boolean =
    !after.isNullOrEmpty() && after[0] in CARET_SPACERS

/**
 * Whether [after], the text right after the caret, starts with a mark that
 * belongs to the word in front of it: a full stop, a comma, a question mark, a
 * closing bracket or quote. A word picked in front of one gets no space of its
 * own, or "A question?" with "questionable" picked over "question" reads
 * "A questionable ?" (#679). The space is held back instead and typed only if
 * a letter follows (see `WMKeyboardService.deferredWordSpace`), which is what
 * FUTO and SwiftKey do.
 */
internal fun markHugsCaretWord(after: CharSequence?): Boolean =
    !after.isNullOrEmpty() && after[0] in WORD_HUGGING_MARKS

/** What [markHugsCaretWord] counts as closing up onto the word before it. */
private const val WORD_HUGGING_MARKS = ".,;:!?…)]}\"'”’»،؛؟۔।"

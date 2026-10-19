package com.wasimaster.wmkeyboard.core.tools

import kotlin.math.roundToInt

/**
 * The words-per-minute readout on the spacebar while you type (#752): a burst
 * meter over the last few words, not the lifetime average the Statistics
 * screen shows.
 *
 * One record per word that lands, from its first keystroke (or the start of
 * its stroke) to the moment it is done. Records older than [WINDOW_MS] drop
 * off, a pause longer than [IDLE_BREAK_MS] starts the history again, and the
 * number shown moves half way towards each new reading, so one quick word does
 * not make it jump. The rate is the usual characters over five per minute,
 * with one space counted after every word.
 *
 * Nothing is stored: it lives as long as the keyboard process and holds no
 * text, only lengths and times. [TypingStats] feeds it from the same events it
 * counts, before its own recording gate, so the readout works with the
 * statistics off; [privateField] is its own gate.
 *
 * Behaviour modelled on Kinetica's `TypingSpeed` (described in #752); no code
 * taken from it.
 */
class LiveTypingSpeed(private val clock: () -> Long) {

    private class Word(val start: Long, val end: Long, val chars: Int)

    private val words = ArrayDeque<Word>()

    /** The word being typed: when its first letter landed, and how many are in it. */
    private var openStart = 0L
    private var openLetters = 0

    private var shown = 0.0

    /**
     * A password or incognito field (#796): nothing typed there is timed and
     * the reading is 0. Turning it on or off forgets every word held so far,
     * so the speed typed before a private field never shows inside it or
     * after it.
     */
    var privateField = false
        @Synchronized get

        @Synchronized set(value) {
            if (field == value) return
            field = value
            reset()
        }

    /** A letter, digit or mark was typed at [uptimeMs]. A mark ends the word in progress. */
    @Synchronized
    fun onTyped(wordChar: Boolean, length: Int, uptimeMs: Long) {
        if (privateField) return
        if (wordChar) {
            if (openLetters == 0) openStart = uptimeMs
            openLetters += length
        } else {
            close(uptimeMs)
        }
    }

    /** A space or enter: the end of the word typed before it. */
    @Synchronized
    fun onSeparator(uptimeMs: Long) {
        if (!privateField) close(uptimeMs)
    }

    /** A backspace takes a letter back off the word in progress; its start stays. */
    @Synchronized
    fun onBackspace() {
        if (!privateField && openLetters > 0) openLetters--
    }

    /**
     * A whole word landed at once: a strip pick, a glide, a two-thumb word.
     * [letters] 0 means the word typed so far is what landed. [startUptimeMs]
     * is when its gesture began, when the caller knows; otherwise the word
     * started at its first typed letter, or now.
     */
    @Synchronized
    fun onWord(letters: Int, startUptimeMs: Long) {
        if (privateField) return
        val now = clock()
        val start = when {
            startUptimeMs in 1..now -> startUptimeMs
            openLetters > 0 -> openStart
            else -> now
        }
        val count = if (letters > 0) letters else openLetters
        openLetters = 0
        log(start, now, count)
    }

    /** What to show at [nowUptimeMs]: the eased reading, or 0 after a pause or before there is one. */
    @Synchronized
    fun reading(nowUptimeMs: Long): Int {
        if (privateField) return 0
        val last = words.lastOrNull() ?: return 0
        if (nowUptimeMs - last.end > IDLE_BREAK_MS) return 0
        return shown.roundToInt()
    }

    @Synchronized
    fun reset() {
        words.clear()
        openLetters = 0
        shown = 0.0
    }

    private fun close(uptimeMs: Long) {
        if (openLetters <= 0) return
        log(openStart, uptimeMs, openLetters)
        openLetters = 0
    }

    private fun log(start: Long, end: Long, letters: Int) {
        if (letters <= 0 || end < start) return
        val previous = words.lastOrNull()
        if (previous != null && start - previous.end > IDLE_BREAK_MS) {
            words.clear()
            shown = 0.0
        }
        words.addLast(Word(start, end, letters + 1))
        while (words.size > 1 && end - words.first().start > WINDOW_MS) words.removeFirst()
        val span = end - words.first().start
        if (words.size < MIN_WORDS || span < MIN_SPAN_MS) return
        val raw = (words.sumOf { it.chars } / CHARS_PER_WORD) / (span / MINUTE_MS)
        val capped = raw.coerceAtMost(MAX_WPM)
        shown = if (shown <= 0.0) capped else shown + (capped - shown) * EASING
    }

    companion object {
        /**
         * A gap this long between words is a pause, and the readout goes back
         * to the label. The same gap [TypingStats.BURST_GAP_MS] uses for
         * active time, so the two speeds agree on what typing is.
         */
        const val IDLE_BREAK_MS = TypingStats.BURST_GAP_MS

        /** How far back the readout looks: long enough for a sentence, short enough to follow a change of pace. */
        const val WINDOW_MS = 30_000L

        /** A reading needs two words, and this much time, before it means anything. */
        const val MIN_WORDS = 2
        const val MIN_SPAN_MS = 2_000L

        /** Share of the way the shown number moves towards each new reading. */
        const val EASING = 0.5

        /** Above this a reading is a stray burst of picks, not typing. */
        const val MAX_WPM = 250.0

        private const val CHARS_PER_WORD = 5.0
        private const val MINUTE_MS = 60_000.0
    }
}

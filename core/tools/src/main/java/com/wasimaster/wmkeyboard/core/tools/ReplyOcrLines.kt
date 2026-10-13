package com.wasimaster.wmkeyboard.core.tools

/**
 * Turns the lines OCR read off a chat screenshot into messages (#234).
 *
 * Pure, so it is testable without ML Kit: the recognizer hands over lines and
 * their boxes, and everything that decides what a *message* is happens here.
 *
 * Three jobs, in order:
 *
 * 1. **Crop.** A screenshot taken with the keyboard up has the keyboard in it —
 *    the reply panel's own buttons and notices, read back as "what they said".
 *    The caller passes where the keyboard started ([ReplyOcrFrame.bottomCut])
 *    when it knows, and the status bar and app bar ([ReplyOcrFrame.topCut]).
 * 2. **Drop chrome.** Times, "Active now", "Seen", the composer's "Message"
 *    placeholder: words on the screen that nobody said ([isChrome]).
 * 3. **Group.** A bubble that wraps is two OCR lines. Lines on the same side
 *    that sit closer than a bubble's own padding are one message. What this
 *    gets wrong the panel lets the user merge by hand.
 */
object ReplyOcrLines {

    /** One recognised line, in the screenshot's pixels. */
    data class Line(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    /**
     * The part of the screenshot that is the conversation, as fractions of its
     * height: lines starting above [topCut] or at or below [bottomCut] are not
     * read. `0f` and `1f` read everything.
     */
    data class ReplyOcrFrame(val topCut: Float = 0f, val bottomCut: Float = 1f)

    /**
     * Where a line has to start before it reads as the user's own message.
     *
     * Past the middle rather than at it: a long message from the other person
     * wraps across most of the width, and its box would otherwise straddle the
     * centre and flip sides between screenshots.
     */
    const val MINE_FROM = 0.52f

    /**
     * Largest gap between two lines, as a fraction of a line's height, that
     * still reads as one bubble wrapping. Line spacing inside a bubble runs
     * about a fifth of a line; the gap between two bubbles is their padding
     * twice over, comfortably more than half.
     */
    const val WRAP_GAP = 0.55f

    /** Left edges further apart than this, in line heights, are two bubbles. */
    private const val WRAP_INDENT = 1.5f

    private const val MIN_CHARS = 2

    /** A month name, full or short; only these make "word number" a date. */
    private const val MONTH = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]{0,6}\\.?"

    /** A clock time, alone or with a date word and delivery ticks around it. */
    private val TIME = Regex(
        "^((today|yesterday|(mon|tue|wed|thu|fri|sat|sun)[a-z]*),?\\s*(at\\s+)?)?" +
            "\\d{1,2}[:.]\\d{2}(\\s?[ap]\\.?m\\.?)?[\\s✓✔·•]*$",
        RegexOption.IGNORE_CASE,
    )

    /** A date separator: "12 Oct", "Oct 12, 2026", "13/10/2026", "Today". */
    private val DATE = Regex(
        "^(today|yesterday|" +
            "\\d{1,2}[/.\\-]\\d{1,2}([/.\\-]\\d{2,4})?|" +
            "\\d{1,2}\\s+$MONTH(\\s+\\d{4})?|" +
            "$MONTH\\s+\\d{1,2}(,?\\s+\\d{4})?)" +
            "(\\s*(,|at)?\\s*\\d{1,2}[:.]\\d{2}(\\s?[ap]\\.?m\\.?)?)?$",
        RegexOption.IGNORE_CASE,
    )

    /** Presence notices under the contact's name: "Active now", "last seen today at 3:51". */
    private val PRESENCE = Regex(
        "^(active\\s+(now|today|yesterday|\\d+\\s*\\w+\\s+ago|\\d+\\s*[smhd]\\s+ago)|online|offline|" +
            "last seen\\b.*|(\\S+\\s+)?is typing\\W*|typing\\W*)$",
        RegexOption.IGNORE_CASE,
    )

    /** Delivery marks under a bubble: "Seen", "Delivered 3:51", "Edited", "Read by Sam". */
    private val DELIVERY = Regex(
        "^(seen|delivered|read|sent|sending|edited|forwarded)" +
            "(\\s+(by|at|on|today|yesterday|just now|\\d)\\b.*)?\\W*$",
        RegexOption.IGNORE_CASE,
    )

    /** Composer placeholders, checked as whole lines. */
    private val PLACEHOLDERS = setOf(
        "message", "message…", "message...", "messages", "type a message", "type a message…",
        "write a message", "write a message…", "write a message...", "imessage", "text message",
        "aa", "reply", "send a message", "send message", "chat", "say something",
    )

    /** Status-bar and badge leftovers: digits, punctuation, ticks. */
    private val SYMBOLS_ONLY = Regex("^[\\d\\s\\p{P}\\p{S}]+$")

    /** Whether [text] is something the app drew rather than something anybody said. */
    fun isChrome(text: String): Boolean {
        val t = text.trim()
        if (t.length < MIN_CHARS) return true
        if (SYMBOLS_ONLY.matches(t)) return true
        if (t.lowercase() in PLACEHOLDERS) return true
        // A status line is short; a long one starting "Read" is a message.
        if (t.length <= STATUS_MAX && (PRESENCE.matches(t) || DELIVERY.matches(t))) return true
        return TIME.matches(t) || DATE.matches(t)
    }

    private const val STATUS_MAX = 40

    /**
     * [lines] read off a screenshot [width] by [height], as a conversation,
     * oldest first.
     */
    fun messages(
        lines: List<Line>,
        width: Int,
        height: Int,
        frame: ReplyOcrFrame = ReplyOcrFrame(),
    ): List<ReplyTurn> {
        if (width <= 0 || height <= 0) return emptyList()
        val top = frame.topCut * height
        val bottom = frame.bottomCut * height
        val kept = lines
            .filter { it.top >= top && it.top < bottom }
            .map { it.copy(text = it.text.trim()) }
            .filterNot { isChrome(it.text) }
            // Sorted by where it sits down the page: ML Kit returns blocks in
            // its own reading order, and a chat is only a conversation in the
            // order it happened.
            .sortedBy { it.top }
        val out = ArrayList<ReplyTurn>()
        var prev: Line? = null
        var prevMine = false
        for (line in kept) {
            val mine = line.left.toFloat() / width >= MINE_FROM
            val p = prev
            val joins = p != null && mine == prevMine &&
                line.top - p.bottom <= WRAP_GAP * p.height &&
                kotlin.math.abs(line.left - p.left) <= WRAP_INDENT * p.height
            if (joins) {
                val last = out.removeAt(out.lastIndex)
                out.add(last.copy(text = last.text + " " + line.text))
            } else {
                out.add(ReplyTurn(fromMe = mine, text = line.text))
            }
            prev = line
            prevMine = mine
        }
        return out
    }
}

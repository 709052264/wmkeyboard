package com.wasimaster.wmkeyboard.core.tools

/**
 * Splits reasoning-model output around `<think>…</think>` passages (Qwen 3
 * and friends emit these before the actual answer). Works on streaming
 * partials: an opened-but-unclosed block means the model is still reasoning.
 */
object AiThinking {

    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    data class Split(
        /** Response text with the think block removed. */
        val output: String,
        /** True while a think block is open with no `</think>` yet. */
        val thinking: Boolean,
    )

    /**
     * [implicitThink] is for models (Qwen 3 style) whose chat template puts
     * the opening `<think>` inside the prompt — their raw output is bare
     * reasoning terminated by `</think>`, with no opener ever emitted. For
     * those, everything before the close tag is reasoning.
     */
    fun split(raw: String, implicitThink: Boolean = false): Split {
        val close = raw.indexOf(CLOSE)
        if (close != -1) {
            val open = raw.indexOf(OPEN)
            val head = if (open in 0 until close) raw.take(open) else ""
            return Split(head + raw.substring(close + CLOSE.length), thinking = false)
        }
        val open = raw.indexOf(OPEN)
        return when {
            open != -1 -> Split(raw.take(open), thinking = true)
            implicitThink -> Split("", thinking = true)
            else -> Split(raw, thinking = false)
        }
    }

    /**
     * The reasoning alone, tags removed: what sits inside `<think>…</think>`,
     * or everything before the close under [implicitThink]. Empty when there
     * is none. With "show reasoning" on, a streaming bubble draws this dimmed
     * and the answer after it as markdown, so tables and maths take shape
     * while they stream instead of showing as raw text until the end.
     */
    fun reasoning(raw: String, implicitThink: Boolean = false): String {
        val open = raw.indexOf(OPEN)
        val close = raw.indexOf(CLOSE, if (open >= 0) open else 0)
        return when {
            open >= 0 && close >= 0 -> raw.substring(open + OPEN.length, close)
            open >= 0 -> raw.substring(open + OPEN.length)
            close >= 0 -> raw.substring(0, close)
            implicitThink -> raw
            else -> ""
        }.trim()
    }

    /**
     * Every reasoning passage and the answer around them, apart (#882).
     *
     * [split] and [reasoning] read the first passage only, which is all a
     * single answer has. A chat answer built over several tool rounds is
     * each round's text joined, and each round may reason before it writes,
     * so this walks them all: [Parts.reasoning] is every passage, oldest
     * first, and [Parts.output] the text with none of them left in it.
     */
    data class Parts(
        val reasoning: String,
        val output: String,
        /** A passage is open with no close yet: the model is reasoning now. */
        val thinking: Boolean,
    )

    fun parts(raw: String, implicitThink: Boolean = false): Parts {
        val passages = ArrayList<String>()
        val output = StringBuilder()
        var at = 0
        var thinking = false
        val firstOpen = raw.indexOf(OPEN)
        val firstClose = raw.indexOf(CLOSE)
        when {
            // A close with no opener before it: the template put the opener
            // in the prompt, so all before it was reasoning.
            firstClose >= 0 && (firstOpen < 0 || firstClose < firstOpen) -> {
                passages += raw.substring(0, firstClose)
                at = firstClose + CLOSE.length
            }
            firstOpen < 0 && implicitThink -> return Parts(raw.trim(), "", thinking = true)
        }
        while (at < raw.length) {
            val open = raw.indexOf(OPEN, at)
            if (open < 0) {
                output.append(raw, at, raw.length)
                break
            }
            output.append(raw, at, open)
            val close = raw.indexOf(CLOSE, open + OPEN.length)
            if (close < 0) {
                passages += raw.substring(open + OPEN.length)
                thinking = true
                break
            }
            passages += raw.substring(open + OPEN.length, close)
            at = close + CLOSE.length
        }
        return Parts(
            reasoning = passages.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n"),
            output = output.toString(),
            thinking = thinking,
        )
    }

    /**
     * Final-result cleanup: the answer without reasoning, trimmed. A finished
     * response with no think markers at all is returned whole even under
     * [implicitThink] — the model simply didn't reason this time.
     */
    fun stripped(raw: String): String = split(raw, implicitThink = false).output.trim()
}

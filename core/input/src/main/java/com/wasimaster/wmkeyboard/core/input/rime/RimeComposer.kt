package com.wasimaster.wmkeyboard.core.input.rime

import com.wasimaster.wmkeyboard.core.input.composer.Composer

/**
 * A Chinese conversion composer backed by librime: the candidates, their
 * spans and the learning are RIME's, under [schema].
 *
 * Everything about *keys* comes from [base], the built-in composer the layout
 * asked for: which characters the buffer admits, whether digits start it,
 * what the composing region shows, what Enter writes, what backspace takes.
 * The layout is unchanged, so its keys must keep behaving exactly as they
 * did; only the conversion is RIME's. When RIME cannot answer (a deploy is
 * running, the library was dropped), [base] converts instead, so the
 * keyboard never stops typing Chinese.
 *
 * The prefix-commit contract holds by construction: a candidate's span is
 * the end RIME's own menu gives it, mapped back through [RimeKeys] to buffer
 * characters, and indexed by position, never by text. RIME's Simplified and
 * Traditional switches run inside its menu ([RimeOptions]); nothing rewrites
 * a candidate after its span is known.
 */
class RimeComposer internal constructor(
    val base: Composer,
    val schema: String,
    private val method: RimeMethod,
    private val options: () -> RimeOptions,
) : Composer {

    private class Ranked(
        val key: String,
        val generation: Int,
        val texts: List<String>,
        /** Buffer characters each candidate consumes; null when [base] ranked it. */
        val consumed: IntArray?,
    )

    @Volatile private var last: Ranked? = null

    override val isTransliterating: Boolean get() = base.isTransliterating
    override val resumesComposedText: Boolean get() = base.resumesComposedText
    override fun resumeBuffer(text: String): String? = base.resumeBuffer(text)
    override fun backspaceBuffer(buffer: String): String = base.backspaceBuffer(buffer)
    override fun deleteLength(before: CharSequence): Int = base.deleteLength(before)
    override val isRomanBuffer: Boolean get() = base.isRomanBuffer
    override val bufferDigits: Boolean get() = base.bufferDigits
    override val digitsStartBuffer: Boolean get() = base.digitsStartBuffer
    override val spaceEndsMultitap: Boolean get() = base.spaceEndsMultitap
    override fun buffersChar(c: Char): Boolean = base.buffersChar(c)
    override fun buffersChar(c: Char, composing: CharSequence): Boolean = base.buffersChar(c, composing)
    override val glideKeys: Set<Int> get() = base.glideKeys
    override val isConversion: Boolean get() = true
    override val keepsUnconvertedReading: Boolean get() = base.keepsUnconvertedReading
    override val readsLatinLetters: Boolean get() = base.readsLatinLetters
    override fun composeBuffer(buffer: String): String = base.composeBuffer(buffer)
    override fun finishBuffer(buffer: String): String = base.finishBuffer(buffer)
    override fun typedReading(buffer: String): String = base.typedReading(buffer)

    // RIME needs no CJK pack of the built-in kind, and offers none.
    override val missingPack: String? get() = null
    override val improvingPack: String? get() = null

    override fun candidates(buffer: String): List<String> = candidates(buffer, STRIP)

    /** Ranked once to [DEPTH] and cut, so a shorter list is always a prefix of a longer one. */
    override fun candidates(buffer: String, limit: Int): List<String> = ranked(buffer).texts.take(limit)

    override fun consumedFor(buffer: String, chosen: String): Int {
        val ranked = ranked(buffer)
        val index = ranked.texts.indexOf(chosen)
        return if (index >= 0) consumedForIndex(buffer, index) else buffer.length
    }

    override fun consumedForIndex(buffer: String, index: Int): Int {
        val ranked = ranked(buffer)
        val consumed = ranked.consumed ?: return base.consumedForIndex(buffer, index)
        return consumed.getOrNull(index) ?: buffer.length
    }

    /**
     * Tells RIME the pick, which is what its user dictionary learns from. The
     * keyboard calls this only where learning is allowed (not in incognito or
     * a password field), after it has asked [consumedForIndex].
     */
    override fun learnChoice(buffer: String, index: Int) {
        val ranked = ranked(buffer)
        if (ranked.consumed == null) {
            base.learnChoice(buffer, index)
            return
        }
        val consumed = ranked.consumed.getOrNull(index) ?: return
        val input = RimeKeys.translate(method, buffer)
        RimeRuntime.pick(schema, options(), input.input, index, inputCharsFor(input, consumed))
        last = null
    }

    private fun ranked(buffer: String): Ranked {
        val opts = options()
        val key = "$schema|$opts|$buffer"
        val generation = RimeRuntime.generation
        last?.takeIf { it.key == key && it.generation == generation }?.let { return it }
        val input = RimeKeys.translate(method, buffer)
        val menu = if (input.input.isEmpty()) null else RimeRuntime.menu(schema, opts, input.input, DEPTH)
        val ranked = if (menu != null) {
            Ranked(key, generation, menu.texts, IntArray(menu.texts.size) { i -> input.bufferChars(menu.consumed[i]).coerceIn(1, buffer.length) })
        } else {
            // Not cached under the RIME key: the next call may find RIME back.
            return Ranked("", -1, base.candidates(buffer, DEPTH), null)
        }
        last = ranked
        return ranked
    }

    /** The input characters behind [bufferChars] buffer characters. */
    private fun inputCharsFor(input: RimeInput, bufferChars: Int): Int {
        var k = 0
        while (k < input.input.length && input.bufferChars(k + 1) <= bufferChars) k++
        return k.coerceAtLeast(1)
    }

    private companion object {
        /** What the strip shows, as the built-in composers do. */
        const val STRIP = 12

        /** How deep a reading is ranked; the expanded grid pages through this. */
        const val DEPTH = 100
    }
}

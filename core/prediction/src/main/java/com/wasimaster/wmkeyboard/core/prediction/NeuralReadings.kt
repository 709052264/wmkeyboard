package com.wasimaster.wmkeyboard.core.prediction

/**
 * Where a neural model's readings of the word being typed go in a phonetic
 * layout's strip (IndicXlit, see `core.xlit` in `:core:intelligence`).
 *
 * Right after the strip's first word in the layout's own script: that is the
 * reading the space bar commits (or, with the strip's first chips fixed to
 * the word as typed and its transliteration, the transliteration). Nothing
 * before it moves, so the model never changes what a space writes and never
 * corrects anything on its own; it only offers. At most [MAX] readings, the
 * ones the language's word list knows first, each in the model's order. A
 * reading the strip already had further down moves up to its new place.
 *
 * Measured on held-out romanized words (Aksharantar's test split, and chat
 * spellings the spelling map was switched off for), this placement lifted
 * Bengali top-4 from 52% to 80% and Hindi from 88% to 93% on the chat words,
 * with top-1 unchanged by construction.
 */
object NeuralReadings {

    const val MAX = 3

    fun merge(
        strip: List<String>,
        readings: List<String>,
        isNative: (String) -> Boolean,
        isListed: (String) -> Boolean,
        max: Int = MAX,
    ): List<String> {
        if (readings.isEmpty() || max <= 0) return strip
        val anchor = strip.indexOfFirst(isNative)
        val at = if (anchor >= 0) anchor + 1 else minOf(1, strip.size)
        val head = strip.subList(0, at)
        val fresh = readings.distinct().filter { it.isNotEmpty() && it !in head }
        if (fresh.isEmpty()) return strip
        val (listed, unlisted) = fresh.partition(isListed)
        val picked = (listed + unlisted).take(max)
        val tail = strip.subList(at, strip.size).filter { it !in picked }
        return head + picked + tail
    }
}

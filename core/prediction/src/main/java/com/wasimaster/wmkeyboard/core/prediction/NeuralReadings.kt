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
 *
 * With `lead`, a language's own setting, the model may also take the first
 * place, and with it the space bar's word, from a head that is not a known
 * word ([leader]). The head the rules wrote then comes second, one tap away.
 * With the spelling map on, that took top-1 from 25.0% to 56.4% on Bengali
 * test words and 40.9% to 48.6% on Hindi ones, and made 22 and 24 of 4,000
 * worse. The caller decides when leading is allowed at all: never over a
 * spelling map entry, which is a curated answer the model must not overrule.
 */
object NeuralReadings {

    const val MAX = 3

    fun merge(
        strip: List<String>,
        readings: List<String>,
        isNative: (String) -> Boolean,
        isListed: (String) -> Boolean,
        max: Int = MAX,
        lead: Boolean = false,
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
        // Only a native head the space bar commits: an English word leading
        // the strip is the commit, and the native word behind it is not.
        val leader = if (lead && anchor == 0) leader(strip[0], fresh, isListed) else null
        if (leader != null) return listOf(leader, strip[0]) + picked.filter { it != leader } + tail
        return head + picked + tail
    }

    /**
     * The reading that takes the space bar's word from [head]: the first of
     * [readings] that [isListed] knows, but only when [head] itself is not
     * known. Null leaves [head] where it is. The commit path asks this
     * directly when the strip was drawn before the model answered, so a space
     * writes what the strip would have shown.
     */
    fun leader(head: String, readings: List<String>, isListed: (String) -> Boolean): String? {
        if (head.isEmpty() || isListed(head)) return null
        return readings.firstOrNull { it.isNotEmpty() && it != head && isListed(it) }
    }
}

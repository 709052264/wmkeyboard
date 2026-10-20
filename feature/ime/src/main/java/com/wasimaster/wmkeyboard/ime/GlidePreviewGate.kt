package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.GlidePreviewSteadiness

/**
 * Keeps the word a stroke is being read as from changing under the finger
 * drawing it.
 *
 * A glide re-decodes on every touch move, and early in a stroke the leader is
 * genuinely unstable — two letters in, a great many words fit. Published raw,
 * that is the suggestion strip, the pill above the finger and the words drawn
 * on the keys all changing several times a second while the user is still
 * moving. The mid-swipe-prediction literature is blunt that this, rather than
 * decode latency, is what makes live previews tiring: people report looking
 * back and forth between their finger and the preview, which breaks the motor
 * flow the preview was meant to support.
 *
 * The gate is the standard answer — hysteresis plus a minimum display time —
 * with one rule that matters more than either:
 *
 *  - a word that has **dropped out of the reading** is replaced at once,
 *    whatever the tier says. It is not a reading of this stroke any more, and
 *    showing it would be worse than any amount of churn;
 *  - a word that has **not had its [GlidePreviewSteadiness.holdMs]** stays,
 *    because a word that appears and vanishes within a tenth of a second was
 *    never worth drawing;
 *  - otherwise a challenger must be [GlidePreviewSteadiness.margin] nats better
 *    *within the same reading*.
 *
 * That last qualifier is the whole reason this is not a one-line comparison.
 * Scores from two readings of the same stroke are not comparable: a longer
 * stroke has more samples to explain, so every candidate's cost grows as the
 * finger moves and "this frame's leader beats last frame's leader" is close to
 * meaningless. Asking instead what the *current* evidence says about the word
 * currently on screen is both cheap and exactly the right question.
 *
 * [commit] is the other half, and it is not optional. A gate that steadies the
 * display while the lift types something else has replaced a visible annoyance
 * with an invisible one. So the shown word wins a lift, bounded by the same
 * margin against the finished stroke's own leader — past that the stroke really
 * has become a different word and the screen was merely behind.
 *
 * Not thread-safe, and does not need to be: one stroke is decoded on one
 * coroutine and committed on the same scope.
 */
class GlidePreviewGate {

    /** The word on screen, or null between strokes. */
    var shown: String? = null
        private set

    private var shownAt = 0L

    /**
     * Which index of [words] should lead the published reading, given each
     * word's decoder score in [scores] and the clock reading [now].
     *
     * 0 whenever the gate has nothing to say, so a caller that gets 0 can
     * publish the reading untouched.
     */
    fun steady(
        words: List<String>,
        scores: List<Double>,
        steadiness: GlidePreviewSteadiness,
        now: Long,
    ): Int {
        val leader = words.firstOrNull() ?: return 0
        if (steadiness == GlidePreviewSteadiness.OFF) {
            adopt(leader, now)
            return 0
        }
        val held = shown
        if (held == null || held.equals(leader, ignoreCase = true)) {
            if (held == null) shownAt = now
            shown = leader
            return 0
        }
        val at = words.indexOfFirst { it.equals(held, ignoreCase = true) }
        // Gone from the reading: the stroke has stopped meaning it.
        if (at < 0) {
            adopt(leader, now)
            return 0
        }
        val stillYoung = now - shownAt < steadiness.holdMs
        val lead = (scores.firstOrNull() ?: 0.0) - (scores.getOrNull(at) ?: 0.0)
        if (stillYoung || lead < steadiness.margin) return at
        adopt(leader, now)
        return 0
    }

    /**
     * The word a lift should type: the one on screen while the finished
     * stroke still ranks it within [GlidePreviewSteadiness.margin] of its own
     * leader, and that leader otherwise.
     *
     * [shownAtLift] is the word that was on screen when the finger lifted.
     * The lift retires the previews, and this gate with them, before the
     * finished stroke is decoded, so a caller reads [shown] first and passes
     * it here (#816): asked after the reset, the gate had nothing on screen
     * and the lift took the leader, whatever the user had been shown.
     */
    fun commit(
        words: List<String>,
        scores: List<Double>,
        steadiness: GlidePreviewSteadiness,
        shownAtLift: String? = shown,
    ): String? {
        val leader = words.firstOrNull() ?: return null
        if (steadiness == GlidePreviewSteadiness.OFF) return leader
        val held = shownAtLift ?: return leader
        if (held.equals(leader, ignoreCase = true)) return leader
        val at = words.indexOfFirst { it.equals(held, ignoreCase = true) }
        if (at < 0) return leader
        val lead = (scores.firstOrNull() ?: 0.0) - (scores.getOrNull(at) ?: 0.0)
        return if (lead < steadiness.margin) held else leader
    }

    /**
     * A stroke ended. Called wherever previews are retired — commit, cancel and
     * picker alike — so the next stroke's first reading is never judged against
     * a word left over from the last one.
     */
    fun reset() {
        shown = null
        shownAt = 0L
        sides.fill(null)
        sidesAt.fill(0L)
    }

    /** The words in the strip's side slots (1 and 2), and when each arrived there (#849). */
    private val sides = arrayOfNulls<String>(SIDE_SLOTS + 1)
    private val sidesAt = LongArray(SIDE_SLOTS + 1)

    /**
     * [words], already led by [steady]'s pick, with each side slot of the
     * strip held the way the leader is (#849): a word still in the reading
     * keeps its slot while it is younger than the tier's hold, or while the
     * word that would take the slot beats it by less than the tier's margin.
     * A word gone from the reading leaves at once, as the leader does.
     *
     * Display only. [commit] never reads it, so a held side word cannot
     * change what a lift types.
     */
    fun steadySides(
        words: List<String>,
        scores: List<Double>,
        steadiness: GlidePreviewSteadiness,
        now: Long,
    ): List<String> {
        if (steadiness == GlidePreviewSteadiness.OFF || words.size < 2) {
            recordSides(words, now)
            return words
        }
        val score = HashMap<String, Double>()
        words.forEachIndexed { i, w -> scores.getOrNull(i)?.let { score.getOrPut(w) { it } } }
        val out = words.toMutableList()
        for (slot in 1..minOf(SIDE_SLOTS, out.lastIndex)) {
            val held = sides[slot] ?: continue
            if (out[slot].equals(held, ignoreCase = true)) continue
            val at = out.indexOfFirst { it.equals(held, ignoreCase = true) }
            // Gone from the reading, or now the leader or an earlier side word.
            if (at < slot) continue
            val young = now - sidesAt[slot] < steadiness.holdMs
            val lead = (score[out[slot]] ?: 0.0) - (score[out[at]] ?: 0.0)
            if (young || lead < steadiness.margin) {
                val word = out[at]
                out[at] = out[slot]
                out[slot] = word
            }
        }
        recordSides(out, now)
        return out
    }

    private fun recordSides(words: List<String>, now: Long) {
        for (slot in 1..SIDE_SLOTS) {
            val word = words.getOrNull(slot)
            if (word == null || !word.equals(sides[slot], ignoreCase = true)) {
                sides[slot] = word
                sidesAt[slot] = now
            }
        }
    }

    private companion object {
        /** The strip's slots beside the centre one that [steadySides] holds. */
        const val SIDE_SLOTS = 2
    }

    private fun adopt(word: String, now: Long) {
        shown = word
        shownAt = now
    }
}

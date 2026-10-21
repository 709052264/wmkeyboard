package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch

/**
 * Glide decoding off a neural model's emissions ([SwipeModel]): a beam search
 * over the dictionary tries, time step by time step, that only ever spells
 * what a trie holds.
 *
 * A hypothesis is a trie node and whether its last step was the blank. Each
 * step it may take the blank and stay, take a child's letter and descend, or
 * take its own letter again and stay; the score is the sum of the chosen
 * log-probabilities, kept as the best path into each (node, blank) pair rather
 * than the sum over paths. A doubled letter needs no blank between its two
 * halves: the stroke shows `ll` as one key visited once, and the models were
 * trained to emit it that way.
 *
 * A finished word scores
 *
 *     path / L^lengthNorm + lengthBonus * L + freqWeight * (logWeight + ln(1 + f))
 *
 * for L letters: the path's log-probability flattened by length, so a long
 * word is not punished for having more steps to be wrong on, a bonus per
 * letter against the model's lean toward short readings, and the word's
 * frequency in the same log space [GlideBeam] scores in. The beam is pruned
 * each step on a length-aware key, `path / max(L, 1)^pruneNorm + pruneBonus * L`,
 * which keeps long prefixes alive against short ones that have merely had
 * fewer chances to go wrong. The weights are FUTO's, tuned for their encoder
 * and English decoder (`scoring.json` in the model repository), with the
 * frequency weight converted from AOSP's 0..255 scale to natural log counts.
 *
 * Three things the stroke says beside the emissions, all of them
 * [GlideBeam]'s and measured on FUTO's real swipes (`native/bench/futo-swipe`):
 *
 * - **Finishing a long word early.** With look-ahead asked for, a prefix
 *   still alive at the stroke's end offers the commonest word under it, scored
 *   on the letters drawn and charged [Scoring.lookAheadCost] for each letter
 *   past them. A loop closing the stroke says the word ends there and asks for
 *   no guesses.
 * - **A loop for a doubled letter.** The model reads the same keys for "god"
 *   and "good"; a loop on the o charges the spelling that does not double it
 *   [Scoring.loopWeight], and moves no word that has no such rival. Pauses and
 *   wiggles are not read: the model reads timing for itself, and charging
 *   words that skip a paused key cost real strokes accuracy at every weight.
 * - **The user's learned shapes.** A reading drawn the way the user drew it
 *   before gains [Scoring.shapeWeight] times what [GlideBeam]'s shape channel
 *   would forgive it. No seeding of stored words the beam missed.
 *
 * Written for this project from the scoring the model card and technical
 * report describe; FUTO's own decoder library is GPL and none of it is here.
 *
 * The search runs in Rust (`wm-engine`'s `glide::ctc`, through
 * [NativeGlide.ctcDecode]); the Kotlin one it replaced is the test sources'
 * `reference/KotlinCtcGlideBeam`, the reference the parity test holds it to.
 */
class CtcGlideBeam(private val scoring: Scoring = Scoring()) {

    data class Scoring(
        /** Exponent of the length normalisation on a finished word's path score. */
        val lengthNorm: Float = 0.5949f,
        /** Nats per letter for a finished word. */
        val lengthBonus: Float = 0.7271f,
        /**
         * Weight on a word's log frequency. FUTO's 0.0134 per AOSP probability
         * step, at 8.59 steps to a doubling, is 0.166 per nat of count.
         */
        val freqWeight: Float = 0.166f,
        /** Length normalisation of the pruning key. */
        val pruneNorm: Float = 0.1902f,
        /** Per-letter bonus in the pruning key. */
        val pruneBonus: Float = 1.2727f,
        /** Hypotheses kept per step and source. */
        val width: Int = 100,
        /** Charge per letter a guess adds past what the stroke drew. */
        val lookAheadCost: Float = 0.5f,
        /** Charge per loop on a key a reading does not double, against a rival spelling that does. */
        val loopWeight: Float = 2f,
        /** Weight on the learned-shape benefit, in [GlideBeam]'s normalised shape distance. */
        val shapeWeight: Float = 10f,
    )

    /**
     * Words for [emissions] out of [sources], best first, at most [limit].
     * Scores are in this decoder's own units: comparable with one another,
     * not with [GlideBeam]'s. [GlideBeam.Candidate.shapeCost] is the path's
     * negated, length-normalised log-probability, low when the stroke read
     * cleanly as the word.
     *
     * [stroke] is the stroke the emissions came from, for its loops and the
     * learned shapes; null decodes the emissions alone. [lookAhead] guesses
     * may join the readings, marked by [GlideBeam.Candidate.ahead].
     */
    @Suppress("LongParameterList")
    fun decode(
        emissions: SwipeEmissions,
        sources: List<FuzzyBeamSearch.WalkSource>,
        limit: Int = 4,
        width: Int = scoring.width,
        /** How many of a dictionary's commonest words may be read; 0 for all. See [GlideBeam.Tuning.vocabularyRank]. */
        vocabularyRank: Int = 0,
        stroke: Stroke? = null,
        lookAhead: Int = 0,
    ): List<GlideBeam.Candidate> {
        if (limit <= 0 || sources.isEmpty() || emissions.steps == 0) return emptyList()
        return NativeGlide.ctcDecode(scoring, emissions, sources, limit, width, vocabularyRank, stroke, lookAhead)
            ?: error("a neural glide source with no Rust twin: every TrieWalker the keyboard builds has one")
    }

    /**
     * The stroke behind a decode's emissions: [path] in pixels on [keys],
     * keys [keyWidth] wide, its loops read with [tuning]'s loop settings, and
     * the user's learned [shapes] for this grid, when they keep any.
     */
    class Stroke(
        val path: List<GesturePoint>,
        val keys: GlideKeyMap,
        val keyWidth: Float,
        val tuning: GlideBeam.Tuning,
        val shapes: GlideShapeSource?,
    )
}

package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch

/**
 * Decoder for a word typed with both thumbs at once (issue #638): any mix of
 * taps and swipes, from either thumb, overlapping in time, read as ONE word.
 *
 * Left thumb taps `s`, right thumb swipes `o→m`, left taps `e`, right swipes
 * `t→h→i→n→g`: "something". A swipe `h→e→l→o` with a tap on `l` landing while
 * it is still going: "hello".
 *
 * **What it models.** Every token gives the word its letters in its own order:
 * a tap gives one letter, a swipe gives two or more, placed along its path
 * exactly the way [GlideBeam] places a stroke's letters (location cost plus the
 * travel term, anchored at both ends). What a single stroke never has to decide
 * is how the tokens' letters *interleave*, and that is the whole new problem.
 *
 * **Interleaving is searched, not guessed.** The trie walk decides, letter by
 * letter, which token the next letter comes from. A state is `(trie node,
 * tokens finished, up to two open swipes each with its own alignment column)`,
 * so `s|om|e|thing`, `so|m...` and every other order is a path through one
 * lattice, and the dictionary settles which ones spell words. That is the
 * difference from enumerating candidate orders up front and decoding each.
 *
 * **What keeps the interleavings honest is the clock.** Two tokens that do not
 * overlap in time came in that order and keep it: one thumb is one pointer at
 * a time, so tokens overlap only when two fingers are down, and there is no
 * need to know which thumb made which token. Concretely, with a slack of
 * [Duet.orderSlackMs] for two fingers landing together:
 *
 *  - a token may give a letter only once every token that *ended* before it
 *    *started* has given all of its letters;
 *  - and only once every token that started before it has started;
 *  - a tap that lands while a swipe is open sits where the swipe's own clock
 *    says it was at that moment: the swipe's letters so far must lie before
 *    that point, give or take [Duet.tapWindowMs], and the rest after it. That
 *    is what puts the `l` tap inside `h→e→l→o`.
 *
 * Two swipes overlapping each other are ordered only by those first two rules;
 * inside the overlap the dictionary decides.
 *
 * **Why the bound stays admissible.** Every cost is non-negative and an open
 * column's floor is a minimum over its possible completions, so a state's
 * `settled + Σ open floors` never exceeds what any word under it finishes at —
 * the [GlideBeam] argument, summed over the open swipes.
 *
 * **Loops.** A swipe's loops are found by the search [GlideBeam] runs on a
 * stroke ([LoopScan]), one swipe at a time, and mean what they mean there: a
 * loop on a key waives [GlideBeam.Tuning.unloopedRepeat] for a letter doubled
 * there, and a finished word that leaves a loop undoubled pays
 * [GlideBeam.Tuning.unclaimedLoop] (issue #740). Tap M and swipe E→T with a
 * loop on the e, and `meet` is asked for.
 *
 * **Not here yet**, unlike a single stroke: pause and wiggle marks, a loop's
 * inside kept to its own key and its arc collapsed out of the travel term, the
 * shape channel, learned shapes and look-ahead. A pause would only ever say a
 * letter is in the word, never that it is in it twice. A word with a single
 * swipe and nothing else should go to [GlideBeam], which has all of them; the
 * caller routes.
 *
 * The decoder runs in Rust (`wm-engine`'s `glide::duet`, through
 * [NativeGlide.duetDecode]); the Kotlin one it replaced is the test sources'
 * `reference/KotlinDuetBeam`, the reference the parity test holds it to.
 */
class DuetBeam(
    private val tuning: GlideBeam.Tuning = GlideBeam.Tuning(),
    private val duet: Duet = Duet(),
) {

    /** The two-thumb weights, on top of the stroke weights in [GlideBeam.Tuning]. */
    data class Duet(
        /** Two tokens closer than this in time are treated as simultaneous. */
        val orderSlackMs: Long = 60L,
        /**
         * How far either side of a tap's moment, along the other thumb's
         * swipe, the swipe's letters may still fall on the "wrong" side of
         * it: reaction time between two thumbs is not zero.
         */
        val tapWindowMs: Long = 120L,
        /** Tap scatter around the key meant, in key widths: the typing model's. */
        val tapSigma: Float = 0.5f,
        /** Farthest a tap may land from a key and still spell it, in key widths. */
        val tapNearRadius: Float = 1.3f,
        /** Ceiling on one tap's cost, in nats. */
        val tapMaxCost: Float = 6f,
        /** Charge for reading one tap as a doubled letter (`tutti` off one `t`), in nats. */
        val tapRepeatCost: Float = 1.2f,
        /** Samples per key width a swipe is resampled to, before the clamp. */
        val samplesPerKey: Float = 6f,
        /** Fewest samples a swipe is resampled to. */
        val minSamples: Int = 8,
    ) {
    }

    /**
     * Decodes [tokens] (pixel coordinates, keys [keyWidth] wide) into up to
     * [limit] words, best first. Empty when there is nothing to decode, more
     * than [DuetWorkspace.MAX_TOKENS] tokens, or no word explains them.
     *
     * Tokens with no clock (every time 0) are taken in list order.
     *
     * [partial] reads the gestures as the *start* of a word instead: every
     * result is a spelling some dictionary word begins with, scored by the
     * commonest word under it. That is what a word still being built shows
     * while its gestures so far finish no word (issue #640) — `s` tapped and
     * `o→m` swiped are "som", on the way to "something".
     */
    @Suppress("LongParameterList", "ReturnCount", "UNUSED_PARAMETER")
    fun decode(
        tokens: List<GlideToken>,
        keys: GlideKeyMap,
        keyWidth: Float,
        sources: List<FuzzyBeamSearch.WalkSource>,
        ws: DuetWorkspace,
        limit: Int = 4,
        partial: Boolean = false,
    ): List<GlideBeam.Candidate> {
        if (tokens.isEmpty() || tokens.size > DuetWorkspace.MAX_TOKENS) return emptyList()
        if (keyWidth <= 0f || keys.keyCount == 0 || sources.isEmpty() || limit <= 0) return emptyList()
        return NativeGlide.duetDecode(tuning, duet, tokens, keys, keyWidth, sources, limit, partial)
            ?: error("a duet source with no Rust twin: every TrieWalker the keyboard builds has one")
    }

    companion object {
        /**
         * Which decoder a word's gestures belong to. One swipe alone is a
         * stroke and goes to [GlideBeam], which also reads its pauses and loops;
         * taps alone are typing. Anything else is this decoder's.
         */
        fun needsDuet(tokens: List<GlideToken>): Boolean {
            val swipes = tokens.count { it is GlideToken.Swipe }
            return swipes >= 1 && tokens.size >= 2
        }
    }
}

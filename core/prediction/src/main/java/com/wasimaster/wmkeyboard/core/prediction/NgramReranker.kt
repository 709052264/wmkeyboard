package com.wasimaster.wmkeyboard.core.prediction

/**
 * The context reranker: a deterministic interpolated n-gram rescorer over the
 * engine's top candidates, built entirely from data the keyboard already has
 * — the user's learned bigrams, trigrams and skip-grams, the corpus pack, the
 * bundled seed-pair counts (which the strip's own ranking never read), and
 * the last few committed words as a topical recency bag. No network, no
 * model file.
 *
 * Where the engine's in-strip context boost is deliberately capped (a habit
 * may re-rank but never bury a frequent exact match), this pass — opt-in per
 * call — rescores the head of the list with the full interpolated
 * evidence. It returns null whenever no candidate has any
 * context evidence at all, which is the common case and means the engine's
 * order stands untouched; a reorder can therefore only ever be
 * evidence-driven.
 *
 * The scoring runs in Rust (`wm-engine`'s `rerank`), next to the lexicon and
 * the packs it reads, in one call per strip. This side does what needs the
 * JVM: lower-casing and folding the words ([WordKey]), the seed pairs, and
 * whether the previous word is one no dictionary knows, which lets the word
 * before it vouch through its own pairs instead.
 */
class NgramReranker(
    private val userLexicon: UserLexicon,
    private val seedBigrams: SeedBigrams,
    private val dictionaryFrequency: (String) -> Int,
    private val ngramPack: () -> NgramPack = { NgramPack.EMPTY },
    /**
     * The neural context model, read live like the pack: its ln P(candidate)
     * joins the evidence as a bounded term against the engine's first choice
     * (`rerank.rs`'s `LM_WEIGHT`/`LM_CAP`). Null leaves the n-gram stores
     * alone in charge, as before.
     */
    private val contextModel: () -> ContextModel? = { null },
    /** This user's record with the model, which scales its weight, read live; null keeps the shipped weight. */
    private val adapter: () -> ContextAdapter? = { null },
) : CandidateReranker {

    override fun rerank(context: RerankContext, candidates: List<String>): List<String>? {
        val prev = context.previousWord?.lowercase() ?: return null
        if (WordContext.isSentinel(prev) || candidates.isEmpty()) return null
        val prev2 = context.previousWord2?.lowercase()
        val prev3 = context.previousWord3?.lowercase()
        val recent = if (context.recentWords.isEmpty()) {
            emptySet()
        } else {
            context.recentWords.mapTo(HashSet()) { it.lowercase() }
        }
        // Skip-gram backoff: when the immediate previous word is one no store
        // has ever seen (a name, a typo), the word before it vouches through
        // its own adjacent pairs: "met Priya at" still knows "at" follows
        // "met". Only ever for an unknown previous word (#195).
        val skipContext = if (
            prev2 != null && dictionaryFrequency(prev) == 0 && !userLexicon.contains(prev)
        ) {
            prev2
        } else {
            null
        }
        val lowered = candidates.map { it.lowercase() }
        val lm = contextModel()?.scores(lowered)
        // Where the model's favourite is not the engine's first choice, the
        // word the user goes on to commit says which was right (ContextAdapter).
        val record = adapter()
        if (lm != null && record != null) {
            var best = -1
            for (i in lm.indices) if (!lm[i].isNaN() && (best < 0 || lm[i] > lm[best])) best = i
            record.disagreement = if (best > 0) candidates[best] to candidates[0] else null
        }
        val order = PredictionNative.rerank(
            userLexicon.nativeHandle,
            ngramPack().nativeHandles(),
            WordKey.of(prev),
            prev2?.let(WordKey::of),
            prev3?.let(WordKey::of),
            skipContext?.let(WordKey::of),
            Array(lowered.size) { WordKey.of(lowered[it]) },
            IntArray(lowered.size) { seedBigrams.count(prev, lowered[it]) },
            BooleanArray(lowered.size) { lowered[it] in recent },
            lm,
            if (lm != null && record != null) PredictionNative.LM_WEIGHT * record.weightFactor() else 0.0,
        ) ?: return null
        return order.map { candidates[it] }
    }
}

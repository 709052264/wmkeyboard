//! The context reranker: the Kotlin `NgramReranker` (core/prediction),
//! term for term. It reorders the head of the strip by the words before the
//! caret: the user's own trigrams, bigrams and skip-grams, the corpus pack's
//! trigrams and bigrams, the bundled seed pairs, and words from the current
//! conversation.
//!
//! The base of each candidate's score is its rank in the incoming order
//! (which already carries edit costs, touch likelihoods and every capped
//! boost); evidence can lift it a bounded number of places. Logarithms are
//! [`strict_math::log`], so the Kotlin original and this agree to the bit.

use crate::sort;
use crate::strict_math;
use crate::user_lexicon::UserLexicon;
use crate::wmng::Pack;

const RANK_STEP: f64 = 1.0;
const WEIGHT_USER_TRIGRAM: f64 = 1.2;
const CAP_USER_TRIGRAM: f64 = 2.5;
const WEIGHT_USER_BIGRAM: f64 = 0.8;
const CAP_USER_BIGRAM: f64 = 2.0;
const WEIGHT_USER_SKIP1: f64 = 0.5;
const CAP_USER_SKIP1: f64 = 1.2;
const WEIGHT_USER_SKIP2: f64 = 0.4;
const CAP_USER_SKIP2: f64 = 1.1;
const WEIGHT_PACK_TRIGRAM: f64 = 0.6;
const CAP_PACK_TRIGRAM: f64 = 1.5;
const WEIGHT_PACK_BIGRAM: f64 = 0.45;
const CAP_PACK_BIGRAM: f64 = 1.2;
const WEIGHT_SEED_BIGRAM: f64 = 0.4;
const CAP_SEED: f64 = 1.5;
const WEIGHT_RECENCY: f64 = 0.3;
const PACK_COUNT_SCALE: i32 = 50;
const WEIGHT_SKIP_USER: f64 = 0.5;
const CAP_SKIP_USER: f64 = 1.2;
const WEIGHT_SKIP_PACK: f64 = 0.3;
const CAP_SKIP_PACK: f64 = 0.8;

/// A word as both stores look it up: its key for the lexicon, and the
/// UTF-8 bytes of that key as the packs compare them (Kotlin's
/// `toByteArray`, a lone surrogate as `?`).
pub struct Word {
    pub key: String,
    pub bytes: Vec<u8>,
}

/// One rerank's context. `skip` is the word two back when the previous word
/// is one no store knows (the caller decides, from its dictionaries).
pub struct Context<'a> {
    pub prev: &'a Word,
    pub prev2: Option<&'a Word>,
    pub prev3: Option<&'a Word>,
    pub skip: Option<&'a Word>,
}

/// One candidate: its words, the bundled seed count of `prev candidate`,
/// and whether it was in the recent conversation.
pub struct Candidate {
    pub word: Word,
    pub seed: i32,
    pub recent: bool,
}

/// `term`: `min(weight * ln(1 + count), cap)`.
fn term(weight: f64, count: i32, cap: f64) -> f64 {
    let v = weight * strict_math::log(1.0 + count as f64);
    // Kotlin's coerceAtMost: the cap when above it, else the value (NaN stays).
    if v > cap {
        cap
    } else {
        v
    }
}

/// The largest count any pack gives, 0 with no pack: `NgramPack`'s merge.
fn pack_max<B: AsRef<[u8]>>(packs: &[&Pack<B>], count: impl Fn(&Pack<B>) -> i32) -> i32 {
    packs.iter().map(|p| count(p)).max().unwrap_or(0)
}

/// The new order, as indices into `candidates`, or `None` when no candidate
/// had any evidence (the caller keeps its order).
pub fn rerank<B: AsRef<[u8]>>(
    lexicon: &UserLexicon,
    packs: &[&Pack<B>],
    context: &Context,
    candidates: &[Candidate],
) -> Option<Vec<usize>> {
    if candidates.is_empty() {
        return None;
    }
    let prev = context.prev;
    let mut any_evidence = false;
    let mut scored: Vec<(usize, f64)> = Vec::with_capacity(candidates.len());
    for (index, c) in candidates.iter().enumerate() {
        let w = &c.word;
        let user3 = context.prev2.map_or(0, |p2| lexicon.trigram_count(&p2.key, &prev.key, &w.key));
        let user2 = lexicon.bigram_count(&prev.key, &w.key);
        let user_skip1 = context.prev2.map_or(0, |p2| lexicon.skip1gram_count(&p2.key, &w.key));
        let user_skip2 = context.prev3.map_or(0, |p3| lexicon.skip2gram_count(&p3.key, &w.key));
        let pack3 = match context.prev2 {
            None => 0,
            Some(p2) => pack_max(packs, |p| p.trigram_count_bytes(&p2.bytes, &prev.bytes, &w.bytes)),
        };
        let pack2 = pack_max(packs, |p| p.bigram_count_bytes(&prev.bytes, &w.bytes));
        let skip_user = context.skip.map_or(0, |s| lexicon.bigram_count(&s.key, &w.key));
        let skip_pack = match context.skip {
            None => 0,
            Some(s) => pack_max(packs, |p| p.bigram_count_bytes(&s.bytes, &w.bytes)),
        };
        let evidence = term(WEIGHT_USER_TRIGRAM, user3, CAP_USER_TRIGRAM)
            + term(WEIGHT_USER_BIGRAM, user2, CAP_USER_BIGRAM)
            + term(WEIGHT_USER_SKIP1, user_skip1, CAP_USER_SKIP1)
            + term(WEIGHT_USER_SKIP2, user_skip2, CAP_USER_SKIP2)
            + term(WEIGHT_PACK_TRIGRAM, pack3 / PACK_COUNT_SCALE, CAP_PACK_TRIGRAM)
            + term(WEIGHT_PACK_BIGRAM, pack2 / PACK_COUNT_SCALE, CAP_PACK_BIGRAM)
            + term(WEIGHT_SEED_BIGRAM, c.seed, CAP_SEED)
            + term(WEIGHT_SKIP_USER, skip_user, CAP_SKIP_USER)
            + term(WEIGHT_SKIP_PACK, skip_pack / PACK_COUNT_SCALE, CAP_SKIP_PACK)
            + WEIGHT_RECENCY * if c.recent { 1.0 } else { 0.0 };
        if evidence > 0.0 {
            any_evidence = true;
        }
        scored.push((index, -(index as f64) * RANK_STEP + evidence));
    }
    if !any_evidence {
        return None;
    }
    // Stable, highest first: equal scores keep the engine's order, as
    // Kotlin's sortedByDescending does.
    sort::by(&mut scored, |a, b| b.1.total_cmp(&a.1));
    Some(scored.into_iter().map(|(i, _)| i).collect())
}

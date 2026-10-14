//! Reverse-phonetic lookup from romanized Bengali to dictionary words. A port
//! of `BengaliPhoneticIndex.kt`.
//!
//! Banglish spelling is loose: আছি gets typed as "asi", "achi" or "achhi".
//! Both the romanized input and each Bengali dictionary word are folded to a
//! lenient canonical key where confusable sounds collapse (স/শ/ষ/ছ/চ → s,
//! ত/ট → t, ব/ভ → b, aspiration dropped, inherent vowels dropped). Words
//! sharing a key are phonetic siblings; the highest-frequency sibling is
//! offered first.
//!
//! Frequency alone is too blunt to order siblings, because the key throws
//! away two things the typist wrote down: aspiration (থাকে and তাকে share
//! "tk"; the typed h is a deliberate request, ranked up lopsidedly) and a
//! final ও (কথা against কথাও). Both are kept beside the key and used as
//! handicaps on frequency, never as filters.
//!
//! The layout is flat arrays, not a map of lists of objects: the index can
//! hold 451k words, and here a word costs its characters and a few integers.

use std::collections::{BinaryHeap, HashMap, HashSet};

use crate::phonetic_index::{PhoneticIndex, SortedWords};
use crate::unicode::is_letter;

/// Marks a key position whose consonant was written aspirated.
const ASPIRATED: u8 = b'h';
const PLAIN: u8 = b'.';
/// Key positions an entry's aspiration fits into as a mask.
const MASK_BITS: usize = 32;
/// Typing the h and not getting it: a deliberate request, refused.
const ASPIRATION_MISMATCH_TYPED: i64 = 20;
/// Left the h out and the word has one: ordinary casual spelling.
const ASPIRATION_MISMATCH_WORD: i64 = 2;
const FINAL_O_MISMATCH: i64 = 4;
/// Keeps the divided frequencies in integer arithmetic.
const SCALE: i64 = 1024;
/// Most fold keys `lookup_loose` tries for one input.
const LOOSE_VARIANTS: usize = 16;

/// Bengali consonants whose aspiration a typist genuinely signals with an h.
const ASPIRATED_LETTERS: [char; 6] = ['খ', 'ঘ', 'ঠ', 'ঢ', 'থ', 'ধ'];
/// Roman consonants where a following h is a real aspiration mark.
const ASPIRABLE_ROMAN: &str = "kgtd";

fn consonant_class(c: char) -> Option<u8> {
    Some(match c {
        'ক' | 'খ' => b'k',
        'গ' | 'ঘ' => b'g',
        'ঙ' | 'ঞ' | 'ং' => b'q',
        'চ' | 'ছ' | 'শ' | 'ষ' | 'স' => b's',
        'জ' | 'ঝ' | 'য' => b'j',
        'ট' | 'ঠ' | 'ত' | 'থ' | 'ৎ' => b't',
        'ড' | 'ঢ' | 'দ' | 'ধ' => b'd',
        'ণ' | 'ন' => b'n',
        'প' | 'ফ' => b'p',
        'ব' | 'ভ' => b'b',
        'ম' => b'm',
        'র' | '\u{9DC}' | '\u{9DD}' => b'r',
        'ল' => b'l',
        'হ' | 'ঃ' => b'h',
        '\u{9DF}' => b'y',
        _ => return None,
    })
}

fn vowel_class(c: char) -> Option<u8> {
    Some(match c {
        'অ' | 'ও' | '\u{9CB}' => b'o',
        'আ' | '\u{9BE}' => b'a',
        'ই' | 'ঈ' | '\u{9BF}' | '\u{9C0}' | 'ঐ' | '\u{9C8}' => b'i',
        'উ' | 'ঊ' | '\u{9C1}' | '\u{9C2}' | 'ঔ' | '\u{9CC}' => b'u',
        'এ' | '\u{9C7}' => b'e',
        'ঋ' | '\u{9C3}' => b'r',
        _ => return None,
    })
}

/// A canonical key plus, character for character, which of its positions
/// were written aspirated. Both are ASCII and built in lockstep.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Folded {
    pub key: String,
    pub aspiration: String,
}

/// `word` with its decomposed nukta pairs as precomposed code points, and
/// the corrupt U+0985 U+09BE pair as U+0986.
fn precomposed(word: &str) -> String {
    word.replace("\u{9A1}\u{9BC}", "\u{9DC}")
        .replace("\u{9A2}\u{9BC}", "\u{9DD}")
        .replace("\u{9AF}\u{9BC}", "\u{9DF}")
        .replace("\u{985}\u{9BE}", "\u{986}")
}

/// `listed` with `bundled`'s frequencies put back over it, when `listed`
/// has none of its own. A list with real frequencies is returned as it is;
/// `bundled` is only opened when it is needed.
pub fn with_bundled_ranking(
    listed: Vec<(String, i32)>,
    bundled: impl FnOnce() -> Vec<(String, i32)>,
) -> Vec<(String, i32)> {
    if listed.is_empty() {
        return listed;
    }
    let flat = listed[0].1;
    if listed.iter().any(|e| e.1 != flat) {
        return listed;
    }
    let ranked = bundled();
    if ranked.is_empty() {
        return listed;
    }
    let known: HashSet<String> = ranked.iter().map(|e| precomposed(&e.0)).collect();
    let mut out = ranked;
    out.extend(
        listed
            .into_iter()
            .filter(|e| !known.contains(&precomposed(&e.0))),
    );
    out
}

/// Folds a Bengali word to its canonical phonetic key.
pub fn fold_bengali(word: &str) -> String {
    fold_bengali_full(word).key
}

/// Whether a Bengali word ends in the standalone ও or its ো sign.
pub fn ends_with_o(word: &str) -> bool {
    matches!(word.chars().last(), Some('ও') | Some('\u{9CB}'))
}

/// Folds a Bengali word, keeping its aspiration pattern.
pub fn fold_bengali_full(word: &str) -> Folded {
    let normalized = precomposed(word);
    let mut key = Vec::new();
    let mut marks = Vec::new();
    for ch in normalized.chars() {
        if let Some(c) = consonant_class(ch) {
            key.push(c);
            marks.push(if ASPIRATED_LETTERS.contains(&ch) {
                ASPIRATED
            } else {
                PLAIN
            });
        } else if let Some(v) = vowel_class(ch) {
            key.push(v);
            marks.push(PLAIN);
        }
        // hasant, chandrabindu and anything unmapped fold away
    }
    drop_trailing_o(Folded {
        key: String::from_utf8(key).unwrap(),
        aspiration: String::from_utf8(marks).unwrap(),
    })
}

/// Folds romanized Bengali typing to the same canonical key.
pub fn fold_roman(input: &str) -> String {
    fold_roman_full(input).key
}

/// Folds romanized typing, keeping the aspiration the typist wrote.
pub fn fold_roman_full(input: &str) -> Folded {
    drop_trailing_o(collapse_inherent(&fold_roman_raw(input), &[]))
}

/// Every key `raw` folds to when each `o` between two consonants may be the
/// inherent vowel (dropped) or a written ো (kept), the all-dropped one first,
/// at most `cap` of them.
fn inherent_variants(raw: &Folded, cap: usize) -> Vec<Folded> {
    let key = raw.key.as_bytes();
    let is_vowel = |b: u8| b"aeiou".contains(&b);
    let medial: Vec<usize> = (0..key.len())
        .filter(|&i| {
            key[i] == b'o'
                && i > 0
                && !is_vowel(key[i - 1])
                && i + 1 < key.len()
                && !is_vowel(key[i + 1])
        })
        .collect();
    let mut out = Vec::new();
    let mut seen = HashSet::new();
    for kept in 0..=medial.len() {
        for subset in subsets(&medial, kept) {
            let folded = drop_trailing_o(collapse_inherent(raw, &subset));
            if seen.insert(folded.key.clone()) {
                out.push(folded);
            }
            if out.len() >= cap {
                return out;
            }
        }
    }
    out
}

/// Every `size`-element subset of `items`, in order.
fn subsets(items: &[usize], size: usize) -> Vec<Vec<usize>> {
    if size == 0 {
        return vec![Vec::new()];
    }
    if size > items.len() {
        return Vec::new();
    }
    let mut out = Vec::new();
    for i in 0..=items.len() - size {
        for rest in subsets(&items[i + 1..], size - 1) {
            let mut subset = vec![items[i]];
            subset.extend(rest);
            out.push(subset);
        }
    }
    out
}

/// `input` folded letter for letter, before the inherent vowels and a final o come off.
fn fold_roman_raw(input: &str) -> Folded {
    let lower: Vec<char> = input.to_lowercase().chars().collect();
    let mut key: Vec<u8> = Vec::with_capacity(lower.len());
    let mut marks: Vec<u8> = Vec::with_capacity(lower.len());
    let mut i = 0;
    while i < lower.len() {
        let ch = lower[i];
        let next = lower.get(i + 1).copied();
        if next == Some('h') && "kgctdpbs".contains(ch) {
            // Aspiration folds: kh→k, gh→g, ch→s, th→t, dh→d, ph→p, bh→b, sh→s
            key.push(if ch == 'c' { b's' } else { ch as u8 });
            marks.push(if ASPIRABLE_ROMAN.contains(ch) {
                ASPIRATED
            } else {
                PLAIN
            });
            i += 2;
            // chh → s as well
            if ch == 'c' && lower.get(i) == Some(&'h') {
                i += 1;
            }
            continue;
        }
        match ch {
            'c' => {
                key.push(b's');
                marks.push(PLAIN);
            }
            'v' => {
                key.push(b'b');
                marks.push(PLAIN);
            }
            'w' => {
                key.push(b'o');
                marks.push(PLAIN);
            }
            'z' => {
                key.push(b'j');
                marks.push(PLAIN);
            }
            'f' => {
                key.push(b'p');
                marks.push(PLAIN);
            }
            // "qq" is ঁ, which the Bengali side folds away; a lone q is ক.
            'q' if next == Some('q') => i += 1,
            'q' => {
                key.push(b'k');
                marks.push(PLAIN);
            }
            'x' => {
                key.extend_from_slice(b"ks");
                marks.extend_from_slice(&[PLAIN, PLAIN]);
            }
            'a' | 'e' | 'i' | 'o' | 'u' => {
                // Doubled vowels lengthen: ee→i (ঈ), oo→u (উ)
                if next == Some(ch) {
                    i += 1;
                    key.push(match ch {
                        'e' => b'i',
                        'o' => b'u',
                        c => c as u8,
                    });
                } else {
                    key.push(ch as u8);
                }
                marks.push(PLAIN);
            }
            c if is_letter(c) => {
                // A non-ASCII letter keeps its first UTF-8 byte's place in the
                // key, as the Kotlin original keeps its truncated code.
                let mut buf = [0u8; 4];
                key.push(c.encode_utf8(&mut buf).as_bytes()[0]);
                marks.push(PLAIN);
            }
            // punctuation and digits fold away
            _ => {}
        }
        i += 1;
    }
    Folded {
        key: String::from_utf8_lossy(&key).into_owned(),
        aspiration: String::from_utf8(marks).unwrap(),
    }
}

/// Romanized inherent vowels: "kori" carries its vowels explicitly, but the
/// Bengali fold of করি is "kri". Dropping medial "o" between consonants from
/// the roman side aligns the two.
fn collapse_inherent(folded: &Folded, keep: &[usize]) -> Folded {
    let key = folded.key.as_bytes();
    let marks = folded.aspiration.as_bytes();
    let is_vowel = |b: u8| b"aeiou".contains(&b);
    let mut out = Vec::with_capacity(key.len());
    let mut out_marks = Vec::with_capacity(key.len());
    for (index, &ch) in key.iter().enumerate() {
        let prev_is_consonant = index > 0 && !is_vowel(key[index - 1]);
        let next_is_consonant = index + 1 < key.len() && !is_vowel(key[index + 1]);
        if ch == b'o' && prev_is_consonant && next_is_consonant && !keep.contains(&index) {
            continue;
        }
        out.push(ch);
        out_marks.push(marks[index]);
    }
    Folded {
        key: String::from_utf8_lossy(&out).into_owned(),
        aspiration: String::from_utf8(out_marks).unwrap(),
    }
}

/// Word-final inherent/o-kar ambiguity: ভালো vs ভাল both end "balo".
fn drop_trailing_o(folded: Folded) -> Folded {
    if folded.key.len() > 1 && folded.key.ends_with('o') {
        Folded {
            key: folded.key[..folded.key.len() - 1].to_string(),
            aspiration: folded.aspiration[..folded.aspiration.len() - 1].to_string(),
        }
    } else {
        folded
    }
}

pub struct BengaliPhoneticIndex {
    /// Every indexed word's characters, back to back, in entry order.
    word_chars: Vec<char>,
    /// Where entry `i`'s word starts in `word_chars`; one extra slot for the end.
    word_start: Vec<usize>,
    frequencies: Vec<i32>,
    /// Bit `i` set when position `i` of the entry's key was written aspirated.
    aspiration_masks: Vec<u32>,
    long_aspiration: HashMap<usize, String>,
    ends_with_o: Vec<bool>,
    /// The distinct keys, sorted, back to back.
    key_bytes: Vec<u8>,
    key_start: Vec<usize>,
    /// Entry range of key `k`: `bucket_start[k]..bucket_start[k + 1]`.
    bucket_start: Vec<usize>,
    /// Entry indexes sorted by word, for `frequency_of`.
    by_word: Vec<usize>,
    max_frequency: i32,
}

impl BengaliPhoneticIndex {
    pub fn new(entries: Vec<(String, i32)>) -> BengaliPhoneticIndex {
        let mut words: Vec<String> = Vec::with_capacity(entries.len());
        let mut keys: Vec<String> = Vec::with_capacity(entries.len());
        let mut aspirations: Vec<String> = Vec::with_capacity(entries.len());
        let mut freqs: Vec<i32> = Vec::with_capacity(entries.len());
        for (word, frequency) in entries {
            let folded = fold_bengali_full(&word);
            if folded.key.is_empty() {
                continue;
            }
            words.push(word);
            keys.push(folded.key);
            aspirations.push(folded.aspiration);
            freqs.push(frequency);
        }
        // Sorted by key, then by frequency (highest first), then by the order
        // they were given in; each run of one key is a bucket.
        let mut order: Vec<usize> = (0..words.len()).collect();
        order.sort_by(|&a, &b| {
            keys[a]
                .cmp(&keys[b])
                .then(freqs[b].cmp(&freqs[a]))
                .then(a.cmp(&b))
        });
        let n = order.len();
        let mut word_chars: Vec<char> =
            Vec::with_capacity(words.iter().map(|w| w.chars().count()).sum());
        let mut word_start = Vec::with_capacity(n + 1);
        let mut frequencies = Vec::with_capacity(n);
        let mut aspiration_masks = Vec::with_capacity(n);
        let mut ends_with_o_v = Vec::with_capacity(n);
        let mut long_aspiration = HashMap::new();
        let mut distinct_keys: Vec<&str> = Vec::new();
        let mut bucket_start: Vec<usize> = Vec::new();
        for (position, &source) in order.iter().enumerate() {
            word_start.push(word_chars.len());
            word_chars.extend(words[source].chars());
            frequencies.push(freqs[source]);
            let aspiration = aspirations[source].as_bytes();
            let mut mask = 0u32;
            for (i, &m) in aspiration.iter().take(MASK_BITS).enumerate() {
                if m == ASPIRATED {
                    mask |= 1 << i;
                }
            }
            aspiration_masks.push(mask);
            if aspiration.len() > MASK_BITS {
                long_aspiration.insert(position, aspirations[source].clone());
            }
            ends_with_o_v.push(ends_with_o(&words[source]));
            let key = keys[source].as_str();
            if distinct_keys.last() != Some(&key) {
                distinct_keys.push(key);
                bucket_start.push(position);
            }
        }
        word_start.push(word_chars.len());
        bucket_start.push(n);
        let mut key_bytes = Vec::with_capacity(distinct_keys.iter().map(|k| k.len()).sum());
        let mut key_start = Vec::with_capacity(distinct_keys.len() + 1);
        for key in &distinct_keys {
            key_start.push(key_bytes.len());
            key_bytes.extend_from_slice(key.as_bytes());
        }
        key_start.push(key_bytes.len());
        let max_frequency = frequencies.iter().copied().max().unwrap_or(0);
        let mut index = BengaliPhoneticIndex {
            word_chars,
            word_start,
            frequencies,
            aspiration_masks,
            long_aspiration,
            ends_with_o: ends_with_o_v,
            key_bytes,
            key_start,
            bucket_start,
            by_word: Vec::new(),
            max_frequency,
        };
        let mut by_word: Vec<usize> = (0..n).collect();
        by_word.sort_by(|&a, &b| index.compare_words(a, b));
        index.by_word = by_word;
        index
    }

    fn word_slice(&self, position: usize) -> &[char] {
        &self.word_chars[self.word_start[position]..self.word_start[position + 1]]
    }

    fn word_at(&self, position: usize) -> String {
        self.word_slice(position).iter().collect()
    }

    /// Entries `a` and `b` in code-point order of their words.
    fn compare_words(&self, a: usize, b: usize) -> std::cmp::Ordering {
        self.word_slice(a).cmp(self.word_slice(b))
    }

    /// Entry `position`'s word against `word`, in code-point order.
    fn compare_word(&self, position: usize, word: &[char]) -> std::cmp::Ordering {
        self.word_slice(position).cmp(word)
    }

    /// The bucket for `key`, or `None`. Binary search over the sorted keys.
    fn bucket_of(&self, key: &str) -> Option<usize> {
        let key = key.as_bytes();
        let mut low: isize = 0;
        let mut high: isize = self.key_start.len() as isize - 2;
        while low <= high {
            let mid = ((low + high) >> 1) as usize;
            let candidate = &self.key_bytes[self.key_start[mid]..self.key_start[mid + 1]];
            match candidate.cmp(key) {
                std::cmp::Ordering::Less => low = mid as isize + 1,
                std::cmp::Ordering::Greater => high = mid as isize - 1,
                std::cmp::Ordering::Equal => return Some(mid),
            }
        }
        None
    }

    /// The bucket for `folded`'s key, best first, or empty.
    fn ranked(&self, folded: &Folded, typed_final_o: bool) -> Vec<String> {
        let Some(bucket) = self.bucket_of(&folded.key) else {
            return Vec::new();
        };
        let from = self.bucket_start[bucket];
        let until = self.bucket_start[bucket + 1];
        // One sibling is the overwhelmingly common case; skip the comparator.
        if until - from == 1 {
            return vec![self.word_at(from)];
        }
        let mut positions: Vec<usize> = (from..until).collect();
        positions.sort_by_key(|&i| {
            std::cmp::Reverse(
                self.frequencies[i] as i64 * SCALE
                    / self.handicap(i, &folded.aspiration, typed_final_o),
            )
        });
        positions.into_iter().map(|i| self.word_at(i)).collect()
    }

    /// Whether position `i` of entry `position`'s key was written aspirated.
    fn aspirated_at(&self, position: usize, i: usize) -> bool {
        if i < MASK_BITS {
            self.aspiration_masks[position] & (1 << i) != 0
        } else {
            self.long_aspiration
                .get(&position)
                .is_some_and(|a| a.as_bytes().get(i) == Some(&ASPIRATED))
        }
    }

    /// What to divide entry `position`'s frequency by for disagreeing with
    /// what was actually typed. 1 means it agrees on every count.
    fn handicap(&self, position: usize, typed: &str, typed_final_o: bool) -> i64 {
        let mut divisor = 1i64;
        if self.ends_with_o[position] != typed_final_o {
            divisor *= FINAL_O_MISMATCH;
        }
        for (i, &t) in typed.as_bytes().iter().enumerate() {
            let typed_aspirated = t == ASPIRATED;
            let word_aspirated = self.aspirated_at(position, i);
            if typed_aspirated == word_aspirated {
                continue;
            }
            divisor *= if typed_aspirated {
                ASPIRATION_MISMATCH_TYPED
            } else {
                ASPIRATION_MISMATCH_WORD
            };
        }
        divisor
    }

    /// Whether entry `position`'s word begins with `prefix`.
    fn starts_with(&self, position: usize, prefix: &[char]) -> bool {
        self.word_slice(position).starts_with(prefix)
    }
}

fn typed_final_o(input: &str) -> bool {
    input
        .chars()
        .last()
        .is_some_and(|c| matches!(c.to_ascii_lowercase(), 'o' | 'w'))
}

impl PhoneticIndex for BengaliPhoneticIndex {
    fn sorted_words(&self) -> Option<&dyn SortedWords> {
        Some(self)
    }

    fn lookup(&self, input: &str) -> Vec<String> {
        self.ranked(&fold_roman_full(input), typed_final_o(input))
    }

    /// `lookup`, then the words reached by reading a typed `o` between two
    /// consonants as ো rather than as the inherent vowel: "vodor" could not
    /// find ভোঁদড় otherwise, "dokan" not দোকান. Every way of keeping some of
    /// those vowels, the plain fold's words first.
    fn lookup_loose(&self, input: &str) -> Vec<String> {
        let final_o = typed_final_o(input);
        let raw = fold_roman_raw(input);
        let mut out: Vec<String> = Vec::new();
        for folded in inherent_variants(&raw, LOOSE_VARIANTS) {
            for word in self.ranked(&folded, final_o) {
                if !out.contains(&word) {
                    out.push(word);
                }
            }
        }
        out
    }

    fn frequency_of(&self, word: &str) -> i32 {
        let word: Vec<char> = word.chars().collect();
        let mut low: isize = 0;
        let mut high: isize = self.by_word.len() as isize - 1;
        while low <= high {
            let mid = ((low + high) >> 1) as usize;
            match self.compare_word(self.by_word[mid], &word) {
                std::cmp::Ordering::Less => low = mid as isize + 1,
                std::cmp::Ordering::Greater => high = mid as isize - 1,
                std::cmp::Ordering::Equal => {
                    // A word listed twice answers with its higher frequency.
                    let mut best = self.frequencies[self.by_word[mid]];
                    let mut i = mid as isize - 1;
                    while i >= 0 && self.compare_word(self.by_word[i as usize], &word).is_eq() {
                        best = best.max(self.frequencies[self.by_word[i as usize]]);
                        i -= 1;
                    }
                    let mut i = mid + 1;
                    while i < self.by_word.len()
                        && self.compare_word(self.by_word[i], &word).is_eq()
                    {
                        best = best.max(self.frequencies[self.by_word[i]]);
                        i += 1;
                    }
                    return best;
                }
            }
        }
        0
    }

    /// `by_word` is already in word order, so the words with `prefix` are
    /// one contiguous run of it: binary-search the run's start and keep the
    /// commonest `limit` while walking it.
    fn completions(&self, prefix: &str, limit: usize) -> Vec<String> {
        if prefix.is_empty() || limit == 0 {
            return Vec::new();
        }
        let prefix: Vec<char> = prefix.chars().collect();
        let mut low = 0;
        let mut high = self.by_word.len();
        while low < high {
            let mid = (low + high) >> 1;
            if self.compare_word(self.by_word[mid], &prefix).is_lt() {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        // Min-heap on frequency: the head is the weakest word kept so far.
        let mut best: BinaryHeap<std::cmp::Reverse<(i32, usize)>> =
            BinaryHeap::with_capacity(limit + 1);
        let mut i = low;
        while i < self.by_word.len() && self.starts_with(self.by_word[i], &prefix) {
            best.push(std::cmp::Reverse((
                self.frequencies[self.by_word[i]],
                self.by_word[i],
            )));
            if best.len() > limit {
                best.pop();
            }
            i += 1;
        }
        let mut out: Vec<String> = Vec::with_capacity(best.len());
        while let Some(std::cmp::Reverse((_, position))) = best.pop() {
            out.push(self.word_at(position));
        }
        out.reverse();
        // A word listed twice (bundled and imported) is offered once.
        let mut seen = HashSet::new();
        out.retain(|w| seen.insert(w.clone()));
        out
    }

    fn match_strength(&self, input: &str) -> i32 {
        let folded = fold_roman_full(input);
        let Some(bucket) = self.bucket_of(&folded.key) else {
            return 0;
        };
        let final_o = typed_final_o(input);
        (self.bucket_start[bucket]..self.bucket_start[bucket + 1])
            .map(|i| self.frequencies[i] as i64 / self.handicap(i, &folded.aspiration, final_o))
            .max()
            .unwrap_or(0) as i32
    }

    fn max_frequency(&self) -> i32 {
        self.max_frequency
    }

    fn is_empty(&self) -> bool {
        self.bucket_start.len() <= 1
    }
}

/// `by_word` as a word list, commonest first among the matches of a search.
impl SortedWords for BengaliPhoneticIndex {
    fn size(&self) -> usize {
        self.by_word.len()
    }

    fn length(&self, i: usize) -> usize {
        let e = self.by_word[i];
        self.word_start[e + 1] - self.word_start[e]
    }

    fn char_at(&self, i: usize, at: usize) -> char {
        self.word_chars[self.word_start[self.by_word[i]] + at]
    }

    fn word(&self, i: usize) -> String {
        self.word_at(self.by_word[i])
    }

    fn rank(&self, i: usize) -> i64 {
        -(self.frequencies[self.by_word[i]] as i64)
    }
}

#[cfg(test)]
mod tests {
    //! Mirrors `BengaliPhoneticIndexTest.kt`. Frequencies are the real ones
    //! from the bundled Bengali list.

    use super::*;

    fn entries(pairs: &[(&str, i32)]) -> Vec<(String, i32)> {
        pairs.iter().map(|(w, f)| (w.to_string(), *f)).collect()
    }

    fn index() -> BengaliPhoneticIndex {
        BengaliPhoneticIndex::new(entries(&[
            ("তাকে", 4850),
            ("থাকে", 1750),
            ("তাকা", 568),
            ("থাকা", 1582),
            ("টাকা", 1900),
            ("গুম", 422),
            ("ঘুম", 80),
            ("কবর", 900),
            ("খবর", 640),
            ("টিক", 229),
            ("ঠিক", 565),
            ("কথা", 286),
            ("কথাও", 993),
            ("আগে", 530),
            ("আগেও", 1216),
            ("ভাল", 1900),
            ("ভালো", 6500),
            ("আছি", 6900),
            ("আসি", 2300),
            ("চলে", 2100),
            ("ছলে", 40),
            ("হঠাৎ", 1252),
            ("হঠাত", 40),
            ("ভারত", 3900),
        ]))
    }

    fn top(input: &str) -> Option<String> {
        index().lookup(input).into_iter().next()
    }

    #[test]
    fn typed_aspiration_is_not_overridden_by_a_commoner_plain_word() {
        assert_eq!(Some("থাকে".to_string()), top("thake"));
        assert_eq!(Some("তাকে".to_string()), top("take"));
        assert_eq!(Some("ঘুম".to_string()), top("ghum"));
        assert_eq!(Some("গুম".to_string()), top("gum"));
        assert_eq!(Some("খবর".to_string()), top("khobor"));
        assert_eq!(Some("কবর".to_string()), top("kobor"));
    }

    #[test]
    fn omitted_aspiration_still_yields_to_frequency() {
        assert_eq!(Some("ঠিক".to_string()), top("tik"));
        assert_eq!(Some("ঠিক".to_string()), top("thik"));
        assert_eq!(Some("টাকা".to_string()), top("taka"));
    }

    #[test]
    fn a_final_o_is_part_of_the_word() {
        assert_eq!(Some("কথা".to_string()), top("kotha"));
        assert_eq!(Some("কথাও".to_string()), top("kothao"));
        assert_eq!(Some("আগে".to_string()), top("age"));
        assert_eq!(Some("আগেও".to_string()), top("ageo"));
    }

    #[test]
    fn spelling_variants_still_follow_frequency() {
        assert_eq!(Some("ভালো".to_string()), top("valo"));
        assert_eq!(Some("ভাল".to_string()), top("val"));
    }

    #[test]
    fn ch_and_ph_are_not_aspiration_claims() {
        assert_eq!(Some("চলে".to_string()), top("chole"));
        assert_eq!(Some("আছি".to_string()), top("achi"));
        assert_eq!(Some("আছি".to_string()), top("asi"));
    }

    #[test]
    fn khanda_ta_is_reachable_from_a_plain_t() {
        assert_eq!(Some("হঠাৎ".to_string()), top("hotat"));
        assert_eq!(Some("হঠাৎ".to_string()), top("hoThat"));
        assert_eq!(Some("ভারত".to_string()), top("bharot"));
    }

    #[test]
    fn q_reads_as_k() {
        assert_eq!(fold_roman("kobor"), fold_roman("qobor"));
        assert_eq!(Some("কবর".to_string()), top("qobor"));
        assert_eq!(fold_roman("cad"), fold_roman("caqqd"));
    }

    #[test]
    fn a_flat_list_takes_the_bundled_ranking() {
        let downloaded = entries(&[
            (
                "\u{99B}\u{9A1}\u{9BC}\u{9BE}\u{99B}\u{9A1}\u{9BC}\u{9BF}",
                1,
            ),
            ("সরাসরি", 1),
            ("স্বরলিপি", 1),
        ]);
        let bundled = entries(&[
            ("সরাসরি", 1252),
            ("\u{99B}\u{9DC}\u{9BE}\u{99B}\u{9DC}\u{9BF}", 40),
        ]);
        let merged = with_bundled_ranking(downloaded, || bundled);
        assert_eq!(
            "সরাসরি",
            BengaliPhoneticIndex::new(merged.clone()).lookup("sorasori")[0]
        );
        let words: HashSet<String> = merged
            .iter()
            .map(|e| e.0.replace("\u{9A1}\u{9BC}", "\u{9DC}"))
            .collect();
        assert_eq!(
            HashSet::from([
                "সরাসরি".to_string(),
                "\u{99B}\u{9DC}\u{9BE}\u{99B}\u{9DC}\u{9BF}".to_string(),
                "স্বরলিপি".to_string()
            ]),
            words
        );
        assert_eq!(3, merged.len());
    }

    #[test]
    fn a_ranked_list_keeps_its_own_frequencies() {
        let ranked = entries(&[
            ("সরাসরি", 9000),
            ("\u{99B}\u{9DC}\u{9BE}\u{99B}\u{9DC}\u{9BF}", 30),
        ]);
        let mut opened = false;
        let merged = with_bundled_ranking(ranked.clone(), || {
            opened = true;
            Vec::new()
        });
        assert_eq!(ranked, merged);
        assert!(!opened);
    }

    #[test]
    fn unknown_input_has_no_siblings() {
        assert_eq!(None, top("zzzq"));
    }

    #[test]
    fn loose_lookup_reads_a_medial_o_as_o_kar() {
        let words = BengaliPhoneticIndex::new(entries(&[
            ("বদর", 50),
            ("ভোঁদ\u{9DC}", 1),
            ("দোকান", 30),
            ("ভোট", 40),
            ("বট", 60),
        ]));
        assert!(!words.lookup("vodor").contains(&"ভোঁদ\u{9DC}".to_string()));
        assert_eq!(vec!["বদর", "ভোঁদ\u{9DC}"], words.lookup_loose("vodor"));
        assert_eq!(vec!["দোকান"], words.lookup_loose("dokan"));
        assert_eq!(vec!["বট", "ভোট"], words.lookup_loose("vot"));
    }

    #[test]
    fn completions_and_the_sorted_view() {
        let idx = index();
        assert_eq!(vec!["তাকে", "তাকা"], idx.completions("ত", 2));
        assert_eq!(vec!["থাকে", "থাকা"], idx.completions("থ", 5));
        assert!(idx.completions("", 5).is_empty());
        assert_eq!(4850, idx.frequency_of("তাকে"));
        assert_eq!(0, idx.frequency_of("নেই"));
        assert_eq!(6900, idx.max_frequency());
        assert!(idx.match_strength("thake") > 0);
        let sorted = idx.sorted_words().unwrap();
        assert_eq!(24, sorted.size());
        for i in 1..sorted.size() {
            assert!(sorted.word(i - 1) <= sorted.word(i));
            assert_eq!(sorted.word(i).chars().count(), sorted.length(i));
            assert_eq!(sorted.word(i).chars().next().unwrap(), sorted.char_at(i, 0));
        }
    }
}

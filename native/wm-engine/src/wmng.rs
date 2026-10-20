//! The `.wmng` corpus n-gram pack (version 1): bigrams and trigrams over a
//! vocabulary of word ids, where **an id is the word's rank in UTF-8 byte
//! order**. Everything cheap about the reader follows from that: a follower
//! run sorted by id is sorted alphabetically too, so a lookup binary-searches
//! the run by string and never consults the vocabulary for the candidate.
//!
//! [`NgramPackBuilder`] is the Kotlin builder, [`write`] its codec, [`Pack`]
//! the `MappedNgramPack` reader over bytes held anywhere.
//!
//! Layout, big-endian, each section padded to four bytes:
//! ```text
//! magic          u32   "WMNG"
//! version        u16   1
//! flags          u16   0
//! vocabCount     i32   V
//! bigramCount    i32   B
//! contextCount   i32   C   distinct (first, second) trigram contexts
//! trigramCount   i32   T
//! offsets        i32 × 9
//! reserved       i32   0
//! 0 vocabOffset     i32 × (V + 1)   byte offsets into section 1
//! 1 vocabBytes      u8              UTF-8, concatenated
//! 2 bgRunStart      i32 × (V + 1)   head id -> its slice of 3 and 4
//! 3 bgFollowerId    u24 × B         ascending within each run
//! 4 bgCount         u16 × B         minifloat, parallel to 3
//! 5 triCtxKey       u48 × C         (first << 24) | second, ascending
//! 6 triCtxRunStart  i32 × (C + 1)
//! 7 triFollowerId   u24 × T         ascending within each run
//! 8 triCount        u16 × T
//! ```
//! The two packed-integer sections carry one and two bytes of slack past
//! their last element, because the Kotlin reader takes a u24 as one `getInt`
//! and a u48 as one `getLong`; this reader takes exactly the bytes, but the
//! writer keeps the slack so the files are the same and the validation is
//! the same.

use crate::freq_codec;
use crate::wmdict::{be_i32, be_u16, be_u32, pad4, Writer};
use std::cmp::Ordering;
use std::collections::HashMap;

pub const MAGIC: u32 = 0x574D_4E47;
pub const VERSION: u16 = 1;
/// Ids are u24, so this many distinct words fit in one pack.
pub const MAX_VOCAB: usize = (1 << 24) - 1;
pub const HEADER_BYTES: usize = 4 + 2 + 2 + 4 + 4 + 4 + 4 + 9 * 4 + 4;
const SECTIONS: usize = 9;
const U24_TAIL: usize = 1;
const U48_TAIL: usize = 2;

/// The id-space form of a pack, ready to write. Only the builder makes
/// one: the reader rests on the invariants it establishes (vocabulary in
/// UTF-8 order, followers ascending within a run, context keys ascending).
pub struct NgramPackData {
    pub vocab: Vec<String>,
    pub bigram_run_start: Vec<u32>,
    pub bigram_follower: Vec<u32>,
    pub bigram_count: Vec<i32>,
    pub context_key: Vec<u64>,
    pub trigram_run_start: Vec<u32>,
    pub trigram_follower: Vec<u32>,
    pub trigram_count: Vec<i32>,
}

/// Accumulates n-grams in arrival order and lays them out in id space once,
/// at [`NgramPackBuilder::build`]: a corpus comes in count order, so real
/// ids cannot be handed out until every word has been seen.
#[derive(Default)]
pub struct NgramPackBuilder {
    ids: HashMap<String, u32>,
    words: Vec<String>,
    bigrams: Vec<[u32; 3]>,
    trigrams: Vec<[u32; 4]>,
}

impl NgramPackBuilder {
    pub fn new() -> NgramPackBuilder {
        NgramPackBuilder::default()
    }

    fn intern(&mut self, word: &str) -> u32 {
        if let Some(&id) = self.ids.get(word) {
            return id;
        }
        let id = self.words.len() as u32;
        self.words.push(word.to_string());
        self.ids.insert(word.to_string(), id);
        id
    }

    pub fn add_bigram(&mut self, first: &str, second: &str, count: i32) {
        let entry = [self.intern(first), self.intern(second), count as u32];
        self.bigrams.push(entry);
    }

    pub fn add_trigram(&mut self, first: &str, second: &str, third: &str, count: i32) {
        let entry = [
            self.intern(first),
            self.intern(second),
            self.intern(third),
            count as u32,
        ];
        self.trigrams.push(entry);
    }

    pub fn is_empty(&self) -> bool {
        self.bigrams.is_empty() && self.trigrams.is_empty()
    }

    /// Panics past [`MAX_VOCAB`] distinct words.
    pub fn build(self) -> NgramPackData {
        assert!(
            self.words.len() <= MAX_VOCAB,
            "{} distinct words exceeds the u24 id space",
            self.words.len()
        );
        // UTF-8 byte order, which is what `str` compares by.
        let mut order: Vec<usize> = (0..self.words.len()).collect();
        order.sort_by(|&a, &b| self.words[a].as_bytes().cmp(self.words[b].as_bytes()));
        let mut rank = vec![0u32; self.words.len()];
        for (position, &provisional) in order.iter().enumerate() {
            rank[provisional] = position as u32;
        }
        let vocab: Vec<String> = order.iter().map(|&i| self.words[i].clone()).collect();

        let mut bigrams = self.bigrams;
        bigrams.sort_by_key(|e| (rank[e[0] as usize], rank[e[1] as usize]));
        let mut bigram_run_start = vec![0u32; vocab.len() + 1];
        for e in &bigrams {
            bigram_run_start[rank[e[0] as usize] as usize + 1] += 1;
        }
        for i in 1..=vocab.len() {
            bigram_run_start[i] += bigram_run_start[i - 1];
        }
        let bigram_follower = bigrams.iter().map(|e| rank[e[1] as usize]).collect();
        let bigram_count = bigrams.iter().map(|e| e[2] as i32).collect();

        let key = |e: &[u32; 4]| ((rank[e[0] as usize] as u64) << 24) | rank[e[1] as usize] as u64;
        let mut trigrams = self.trigrams;
        trigrams.sort_by_key(|e| (key(e), rank[e[2] as usize]));
        let mut context_key = Vec::new();
        let mut trigram_run_start = Vec::new();
        let mut previous = None;
        for (i, e) in trigrams.iter().enumerate() {
            let k = key(e);
            if previous != Some(k) {
                context_key.push(k);
                trigram_run_start.push(i as u32);
                previous = Some(k);
            }
        }
        trigram_run_start.push(trigrams.len() as u32);
        let trigram_follower = trigrams.iter().map(|e| rank[e[2] as usize]).collect();
        let trigram_count = trigrams.iter().map(|e| e[3] as i32).collect();

        NgramPackData {
            vocab,
            bigram_run_start,
            bigram_follower,
            bigram_count,
            context_key,
            trigram_run_start,
            trigram_follower,
            trigram_count,
        }
    }
}

/// The file for `pack`.
pub fn write(pack: &NgramPackData) -> Vec<u8> {
    let vocab_count = pack.vocab.len();
    let bigram_count = pack.bigram_follower.len();
    let context_count = pack.context_key.len();
    let trigram_count = pack.trigram_follower.len();
    let vocab_bytes: usize = pack.vocab.iter().map(|w| w.len()).sum();

    let mut cursor = HEADER_BYTES;
    let mut offsets = [0u32; SECTIONS];
    let mut section = |size: usize| {
        let at = cursor;
        cursor += size;
        at as u32
    };
    offsets[0] = section((vocab_count + 1) * 4);
    offsets[1] = section(pad4(vocab_bytes));
    offsets[2] = section((vocab_count + 1) * 4);
    offsets[3] = section(pad4(bigram_count * 3 + U24_TAIL));
    offsets[4] = section(pad4(bigram_count * 2));
    offsets[5] = section(pad4(context_count * 6 + U48_TAIL));
    offsets[6] = section((context_count + 1) * 4);
    offsets[7] = section(pad4(trigram_count * 3 + U24_TAIL));
    offsets[8] = section(pad4(trigram_count * 2));

    let mut out = Writer::with_capacity(cursor);
    out.u32(MAGIC);
    out.u16(VERSION);
    out.u16(0);
    out.u32(vocab_count as u32);
    out.u32(bigram_count as u32);
    out.u32(context_count as u32);
    out.u32(trigram_count as u32);
    for offset in offsets {
        out.u32(offset);
    }
    out.u32(0);

    let mut running = 0u32;
    for word in &pack.vocab {
        out.u32(running);
        running += word.len() as u32;
    }
    out.u32(running);
    for word in &pack.vocab {
        out.bytes.extend_from_slice(word.as_bytes());
    }
    out.pad4();

    for &value in &pack.bigram_run_start {
        out.u32(value);
    }
    write_u24s(&mut out, &pack.bigram_follower, offsets[4] as usize);
    write_counts(&mut out, &pack.bigram_count);

    for &key in &pack.context_key {
        out.bytes.extend_from_slice(&key.to_be_bytes()[2..]);
    }
    out.pad_to(offsets[6] as usize);
    for &value in &pack.trigram_run_start {
        out.u32(value);
    }
    write_u24s(&mut out, &pack.trigram_follower, offsets[8] as usize);
    write_counts(&mut out, &pack.trigram_count);
    debug_assert_eq!(cursor, out.bytes.len());
    out.bytes
}

fn write_u24s(out: &mut Writer, values: &[u32], end: usize) {
    for &value in values {
        out.bytes.extend_from_slice(&value.to_be_bytes()[1..]);
    }
    out.pad_to(end);
}

fn write_counts(out: &mut Writer, values: &[i32]) {
    for &value in values {
        out.u16(freq_codec::encode(value));
    }
    out.pad4();
}

/// Bigrams plus trigrams in a pack, off its header alone; 0 for bytes that
/// are not a pack.
pub fn entry_count(bytes: &[u8]) -> u32 {
    if bytes.len() < 24 || be_u32(bytes, 0) != MAGIC {
        return 0;
    }
    (be_i32(bytes, 12).max(0) as u32).saturating_add(be_i32(bytes, 20).max(0) as u32)
}

/// A pack read in place.
pub struct Pack<B: AsRef<[u8]> = Vec<u8>> {
    bytes: B,
    vocab_count: usize,
    context_count: usize,
    vocab_offset_off: usize,
    vocab_bytes_off: usize,
    bigram_run_off: usize,
    bigram_follower_off: usize,
    bigram_count_off: usize,
    context_key_off: usize,
    trigram_run_off: usize,
    trigram_follower_off: usize,
    trigram_count_off: usize,
}

impl<B: AsRef<[u8]>> Pack<B> {
    /// `None` for bytes that are not a version-1 pack with every section
    /// present in full and both run tables closing on their counts.
    pub fn open(bytes: B) -> Option<Pack<B>> {
        let b = bytes.as_ref();
        if b.len() < HEADER_BYTES || be_u32(b, 0) != MAGIC || be_u16(b, 4) != VERSION {
            return None;
        }
        let vocab_count = be_i32(b, 8);
        let bigram_count = be_i32(b, 12);
        let context_count = be_i32(b, 16);
        let trigram_count = be_i32(b, 20);
        if vocab_count < 1 || vocab_count as usize > MAX_VOCAB {
            return None;
        }
        if bigram_count < 0 || context_count < 0 || trigram_count < 0 {
            return None;
        }
        let (vocab_count, bigram_count, context_count, trigram_count) = (
            vocab_count as usize,
            bigram_count as usize,
            context_count as usize,
            trigram_count as usize,
        );
        let mut offsets = [0usize; SECTIONS];
        for (i, offset) in offsets.iter_mut().enumerate() {
            let value = be_i32(b, 24 + i * 4);
            if value < 0 {
                return None;
            }
            *offset = value as usize;
        }
        if offsets[0] != HEADER_BYTES || offsets[0] + (vocab_count + 1) * 4 > b.len() {
            return None;
        }
        let vocab_bytes = be_i32(b, offsets[0] + vocab_count * 4);
        if vocab_bytes < 0 {
            return None;
        }
        let sizes = [
            (vocab_count + 1) * 4,
            pad4(vocab_bytes as usize),
            (vocab_count + 1) * 4,
            pad4(bigram_count * 3 + U24_TAIL),
            pad4(bigram_count * 2),
            pad4(context_count * 6 + U48_TAIL),
            (context_count + 1) * 4,
            pad4(trigram_count * 3 + U24_TAIL),
            pad4(trigram_count * 2),
        ];
        for i in 0..SECTIONS - 1 {
            if offsets[i + 1] < offsets[i] || offsets[i + 1] - offsets[i] < sizes[i] {
                return None;
            }
        }
        if offsets[8] + sizes[8] > b.len() {
            return None;
        }
        if be_u32(b, offsets[2] + vocab_count * 4) as usize != bigram_count {
            return None;
        }
        if be_u32(b, offsets[6] + context_count * 4) as usize != trigram_count {
            return None;
        }
        Some(Pack {
            vocab_count,
            context_count,
            vocab_offset_off: offsets[0],
            vocab_bytes_off: offsets[1],
            bigram_run_off: offsets[2],
            bigram_follower_off: offsets[3],
            bigram_count_off: offsets[4],
            context_key_off: offsets[5],
            trigram_run_off: offsets[6],
            trigram_follower_off: offsets[7],
            trigram_count_off: offsets[8],
            bytes,
        })
    }

    fn b(&self) -> &[u8] {
        self.bytes.as_ref()
    }

    pub fn vocab_count(&self) -> usize {
        self.vocab_count
    }

    pub fn bigram_count(&self, first: &str, second: &str) -> i32 {
        self.bigram_count_bytes(first.as_bytes(), second.as_bytes())
    }

    /// The `_bytes` lookups take the words as UTF-8 bytes, which is what the
    /// file compares; the JNI bridge encodes Kotlin's strings the way
    /// `String.toByteArray(UTF_8)` does (a lone surrogate as `?`), so a
    /// lookup finds what the Kotlin reader found.
    pub fn bigram_count_bytes(&self, first: &[u8], second: &[u8]) -> i32 {
        let (start, end) = self.bigram_run(first);
        if start >= end {
            return 0;
        }
        match self.find_follower(self.bigram_follower_off, start, end, second) {
            None => 0,
            Some(slot) => self.count(self.bigram_count_off, slot),
        }
    }

    pub fn trigram_count(&self, first: &str, second: &str, third: &str) -> i32 {
        self.trigram_count_bytes(first.as_bytes(), second.as_bytes(), third.as_bytes())
    }

    pub fn trigram_count_bytes(&self, first: &[u8], second: &[u8], third: &[u8]) -> i32 {
        let (start, end) = self.trigram_run(first, second);
        if start >= end {
            return 0;
        }
        match self.find_follower(self.trigram_follower_off, start, end, third) {
            None => 0,
            Some(slot) => self.count(self.trigram_count_off, slot),
        }
    }

    /// The `limit` likeliest words after `first`, best first, ties alphabetical.
    pub fn next_words(&self, first: &str, limit: usize) -> Vec<String> {
        self.next_words_bytes(first.as_bytes(), limit)
    }

    pub fn next_words_bytes(&self, first: &[u8], limit: usize) -> Vec<String> {
        let run = self.bigram_run(first);
        self.best_followers(run, self.bigram_follower_off, self.bigram_count_off, limit)
    }

    /// The `limit` likeliest words after `first second`.
    pub fn next_words_after(&self, first: &str, second: &str, limit: usize) -> Vec<String> {
        self.next_words_after_bytes(first.as_bytes(), second.as_bytes(), limit)
    }

    pub fn next_words_after_bytes(&self, first: &[u8], second: &[u8], limit: usize) -> Vec<String> {
        let run = self.trigram_run(first, second);
        self.best_followers(
            run,
            self.trigram_follower_off,
            self.trigram_count_off,
            limit,
        )
    }

    /// The word `id` stands for, or `None` when a corrupt file names a stranger.
    pub fn word(&self, id: usize) -> Option<String> {
        if id >= self.vocab_count {
            return None;
        }
        let (start, end) = self.vocab_span(id)?;
        String::from_utf8(
            self.b()[self.vocab_bytes_off + start..self.vocab_bytes_off + end].to_vec(),
        )
        .ok()
    }

    /// Rank of `word` in the vocabulary, or `None`.
    pub fn id_of(&self, word: &str) -> Option<usize> {
        self.id_of_bytes(word.as_bytes())
    }

    pub fn id_of_bytes(&self, query: &[u8]) -> Option<usize> {
        let mut lo = 0isize;
        let mut hi = self.vocab_count as isize - 1;
        while lo <= hi {
            let mid = ((lo + hi) >> 1) as usize;
            match self.compare_vocab(mid, query) {
                Ordering::Less => lo = mid as isize + 1,
                Ordering::Greater => hi = mid as isize - 1,
                Ordering::Equal => return Some(mid),
            }
        }
        None
    }

    fn vocab_span(&self, id: usize) -> Option<(usize, usize)> {
        let start = be_i32(self.b(), self.vocab_offset_off + id * 4);
        let end = be_i32(self.b(), self.vocab_offset_off + (id + 1) * 4);
        if start < 0 || end < start {
            return None;
        }
        let (start, end) = (start as usize, end as usize);
        if self.vocab_bytes_off + end > self.b().len() {
            return None;
        }
        Some((start, end))
    }

    fn bigram_run(&self, head: &[u8]) -> (usize, usize) {
        match self.id_of_bytes(head) {
            None => (0, 0),
            Some(id) => (
                be_u32(self.b(), self.bigram_run_off + id * 4) as usize,
                be_u32(self.b(), self.bigram_run_off + (id + 1) * 4) as usize,
            ),
        }
    }

    fn trigram_run(&self, first: &[u8], second: &[u8]) -> (usize, usize) {
        let (Some(first_id), Some(second_id)) = (self.id_of_bytes(first), self.id_of_bytes(second)) else {
            return (0, 0);
        };
        match self.context_rank(((first_id as u64) << 24) | second_id as u64) {
            None => (0, 0),
            Some(rank) => (
                be_u32(self.b(), self.trigram_run_off + rank * 4) as usize,
                be_u32(self.b(), self.trigram_run_off + (rank + 1) * 4) as usize,
            ),
        }
    }

    fn context_key(&self, rank: usize) -> u64 {
        let at = self.context_key_off + rank * 6;
        let b = self.b();
        let mut key = [0u8; 8];
        key[2..].copy_from_slice(&b[at..at + 6]);
        u64::from_be_bytes(key)
    }

    fn context_rank(&self, key: u64) -> Option<usize> {
        let mut lo = 0isize;
        let mut hi = self.context_count as isize - 1;
        while lo <= hi {
            let mid = ((lo + hi) >> 1) as usize;
            match self.context_key(mid).cmp(&key) {
                Ordering::Less => lo = mid as isize + 1,
                Ordering::Greater => hi = mid as isize - 1,
                Ordering::Equal => return Some(mid),
            }
        }
        None
    }

    /// Slot of `word` within `start..end`, by binary search on the string:
    /// sound only because ids are UTF-8 ranks.
    fn find_follower(
        &self,
        follower_off: usize,
        start: usize,
        end: usize,
        query: &[u8],
    ) -> Option<usize> {
        let mut lo = start as isize;
        let mut hi = end as isize - 1;
        while lo <= hi {
            let mid = ((lo + hi) >> 1) as usize;
            match self.compare_vocab(self.follower_id(follower_off, mid), query) {
                Ordering::Less => lo = mid as isize + 1,
                Ordering::Greater => hi = mid as isize - 1,
                Ordering::Equal => return Some(mid),
            }
        }
        None
    }

    /// The `limit` highest-counting followers of a run, best first, ties in
    /// slot order (alphabetical): a linear scan over the two-byte counts,
    /// which for the fattest English head is 17 KB and for the median one
    /// cache line, keeping a top-`limit` list as it goes.
    fn best_followers(
        &self,
        (start, end): (usize, usize),
        follower_off: usize,
        count_off: usize,
        limit: usize,
    ) -> Vec<String> {
        if limit == 0 || start >= end {
            return Vec::new();
        }
        let mut slots: Vec<usize> = Vec::with_capacity(limit);
        let mut codes: Vec<u16> = Vec::with_capacity(limit);
        for slot in start..end {
            // Raw codes order correctly undecoded: the minifloat is monotone.
            let code = be_u16(self.b(), count_off + slot * 2);
            if codes.len() == limit && code <= codes[limit - 1] {
                continue;
            }
            let mut at = if codes.len() < limit {
                codes.push(code);
                slots.push(slot);
                codes.len() - 1
            } else {
                limit - 1
            };
            while at > 0 && codes[at - 1] < code {
                codes[at] = codes[at - 1];
                slots[at] = slots[at - 1];
                at -= 1;
            }
            codes[at] = code;
            slots[at] = slot;
        }
        slots
            .iter()
            .filter_map(|&slot| self.word(self.follower_id(follower_off, slot)))
            .collect()
    }

    fn follower_id(&self, follower_off: usize, slot: usize) -> usize {
        let at = follower_off + slot * 3;
        let b = self.b();
        ((b[at] as usize) << 16) | ((b[at + 1] as usize) << 8) | b[at + 2] as usize
    }

    fn count(&self, count_off: usize, slot: usize) -> i32 {
        freq_codec::decode(be_u16(self.b(), count_off + slot * 2))
    }

    /// Stored word `id` against `query`, as unsigned bytes; an id past the
    /// vocabulary sorts first, as in Kotlin.
    fn compare_vocab(&self, id: usize, query: &[u8]) -> Ordering {
        if id >= self.vocab_count {
            return Ordering::Less;
        }
        match self.vocab_span(id) {
            None => Ordering::Less,
            Some((start, end)) => {
                self.b()[self.vocab_bytes_off + start..self.vocab_bytes_off + end].cmp(query)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn compile(build: impl FnOnce(&mut NgramPackBuilder)) -> Pack {
        let mut builder = NgramPackBuilder::new();
        build(&mut builder);
        Pack::open(write(&builder.build())).expect("the reader rejected the writer's output")
    }

    fn sample() -> Pack {
        compile(|b| {
            b.add_bigram("of", "the", 204351);
            b.add_bigram("of", "a", 50000);
            b.add_bigram("in", "the", 179968);
            b.add_trigram("one", "of", "the", 16821);
            b.add_trigram("one", "of", "us", 900);
        })
    }

    fn within(expected: i32, got: i32) -> bool {
        (expected - got).abs() as f64 <= expected as f64 * 0.001
    }

    fn words(list: &[&str]) -> Vec<String> {
        list.iter().map(|w| w.to_string()).collect()
    }

    #[test]
    fn counts_and_followers_round_trip() {
        let p = sample();
        assert!(within(204351, p.bigram_count("of", "the")));
        assert_eq!(0, p.bigram_count("of", "zzz"));
        assert_eq!(0, p.bigram_count("zzz", "the"));
        assert!(within(16821, p.trigram_count("one", "of", "the")));
        assert_eq!(0, p.trigram_count("one", "of", "zzz"));
        assert_eq!(0, p.trigram_count("zzz", "of", "the"));
        assert_eq!(words(&["the", "a"]), p.next_words("of", 5));
        assert_eq!(words(&["the", "us"]), p.next_words_after("one", "of", 5));
        assert!(p.next_words("zzz", 5).is_empty());
        assert!(p.next_words_after("zzz", "of", 5).is_empty());
        assert_eq!(
            5,
            entry_count(&write(&{
                let mut b = NgramPackBuilder::new();
                b.add_bigram("of", "the", 204351);
                b.add_bigram("of", "a", 50000);
                b.add_bigram("in", "the", 179968);
                b.add_trigram("one", "of", "the", 16821);
                b.add_trigram("one", "of", "us", 900);
                b.build()
            }))
        );
        assert_eq!(0, entry_count(b"junk"));
    }

    #[test]
    fn vocabulary_is_ranked_by_utf8_bytes_and_round_trips() {
        let list = ["of", "the", "a", "in", "zebra", "ápple", "ñu", "日本"];
        let m = compile(|b| {
            for w in list {
                b.add_bigram("head", w, 100);
            }
        });
        assert_eq!(list.len() + 1, m.vocab_count());
        let mut previous: Option<String> = None;
        for id in 0..list.len() + 1 {
            let word = m.word(id).unwrap();
            assert_eq!(Some(id), m.id_of(&word));
            if let Some(p) = &previous {
                assert!(
                    p.as_bytes() < word.as_bytes(),
                    "vocab out of UTF-8 order at {id}"
                );
            }
            previous = Some(word);
        }
        assert_eq!(None, m.id_of("notpresent"));
        assert_eq!(None, m.word(9999));
    }

    #[test]
    fn the_last_follower_in_each_section_is_readable() {
        let list: Vec<String> = (0..400).map(|i| format!("w{i:03}")).collect();
        let m = compile(|b| {
            for (i, w) in list.iter().enumerate() {
                b.add_bigram("head", w, i as i32 + 1);
                b.add_trigram("head", "two", w, i as i32 + 1);
            }
        });
        let last = list.last().unwrap();
        assert_eq!(400, m.bigram_count("head", last));
        assert_eq!(400, m.trigram_count("head", "two", last));
        assert_eq!(vec![last.clone()], m.next_words_after("head", "two", 1));
    }

    #[test]
    fn followers_come_back_best_first_with_alphabetical_ties() {
        let m = compile(|b| {
            b.add_bigram("x", "delta", 10);
            b.add_bigram("x", "alpha", 50);
            b.add_bigram("x", "charlie", 50);
            b.add_bigram("x", "bravo", 90);
        });
        assert_eq!(
            words(&["bravo", "alpha", "charlie", "delta"]),
            m.next_words("x", 10)
        );
        assert_eq!(words(&["bravo"]), m.next_words("x", 1));
        assert_eq!(words(&["bravo", "alpha"]), m.next_words("x", 2));
        assert!(m.next_words("x", 0).is_empty());
    }

    #[test]
    fn every_pair_survives_a_fuzz_round_trip() {
        // A small LCG, so the test needs no crate and repeats exactly.
        let mut state = 11u64;
        let mut next = |bound: u64| {
            state = state
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            ((state >> 33) % bound) as usize
        };
        let vocabulary: Vec<String> = (0..300).map(|i| format!("v{i}")).collect();
        let mut expected: HashMap<(usize, usize), i32> = HashMap::new();
        let mut builder = NgramPackBuilder::new();
        while expected.len() < 4000 {
            let key = (next(300), next(300));
            if expected.contains_key(&key) {
                continue;
            }
            let count = next(4_999_999) as i32 + 1;
            expected.insert(key, count);
            builder.add_bigram(&vocabulary[key.0], &vocabulary[key.1], count);
        }
        let m = Pack::open(write(&builder.build())).unwrap();
        for (&(a, b), &count) in &expected {
            assert!(
                within(count, m.bigram_count(&vocabulary[a], &vocabulary[b])),
                "{a} {b}"
            );
        }
        let mut misses = 0;
        for a in 0..300 {
            for b in 0..300 {
                if !expected.contains_key(&(a, b)) && misses < 500 {
                    misses += 1;
                    assert_eq!(0, m.bigram_count(&vocabulary[a], &vocabulary[b]));
                }
            }
        }
    }

    #[test]
    fn corrupt_and_truncated_packs_read_as_absent() {
        let mut b = NgramPackBuilder::new();
        b.add_bigram("of", "the", 7);
        let good = write(&b.build());
        assert!(Pack::open(good.clone()).is_some());
        let opens = |mutate: &dyn Fn(&mut Vec<u8>)| {
            let mut copy = good.clone();
            mutate(&mut copy);
            Pack::open(copy).is_some()
        };
        assert!(!opens(&|c| c[0] = 0), "bad magic");
        assert!(!opens(&|c| c[5] = 9), "bad version");
        assert!(!opens(&|c| c[8..12].fill(0)), "zero vocab");
        assert!(!opens(&|c| c[13] ^= 1), "bigram count off the run table");
        for cut in [4, 32, good.len() / 2, good.len() - 1] {
            assert!(
                Pack::open(good[..cut].to_vec()).is_none(),
                "truncated at {cut}"
            );
        }
    }

    #[test]
    fn the_counts_the_kotlin_test_pins() {
        let m = sample();
        for _ in 0..3 {
            assert_eq!(0, m.bigram_count("in", "a"));
            assert_eq!(204288, m.bigram_count("of", "the"));
            assert_eq!(179968, m.bigram_count("in", "the"));
            assert_eq!(50000, m.bigram_count("of", "a"));
            assert_eq!(0, m.bigram_count("zzz", "the"));
            assert_eq!(words(&["the"]), m.next_words("in", 1));
            assert_eq!(words(&["the", "a"]), m.next_words("of", 2));
            assert_eq!(16816, m.trigram_count("one", "of", "the"));
            assert_eq!(0, m.trigram_count("one", "in", "the"));
            assert_eq!(900, m.trigram_count("one", "of", "us"));
        }
    }

    #[test]
    fn a_pack_with_no_trigrams_and_a_duplicate_pair() {
        let m = compile(|b| {
            b.add_bigram("a", "b", 3);
            b.add_bigram("a", "b", 5);
        });
        assert!(m.bigram_count("a", "b") == 3 || m.bigram_count("a", "b") == 5);
        assert!(m.next_words_after("a", "b", 3).is_empty());
        assert_eq!(0, m.trigram_count("a", "b", "a"));
    }
}

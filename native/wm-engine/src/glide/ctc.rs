//! `CtcGlideBeam`: glide decoding off a neural model's emissions, a beam
//! search over the dictionary tries, time step by time step, that only ever
//! spells what a trie holds. The Kotlin class documents the scoring; this is
//! its port, step for step, so the two keep the same hypotheses in the same
//! order and score them to the same bit.

use std::collections::HashMap;

use super::loops::{is_joiner, is_non_word};
use super::{by_score, Candidate, Source};
use crate::fuzzy::Tier;
use crate::sort;
use crate::strict_math;

/// a..z, then blank.
pub const CLASSES: usize = 27;
pub const BLANK: usize = 26;

const NO_CHAR: u16 = 0;
/// Next-beam slots per kept hypothesis before it grows.
const EXPANSION: usize = 16;
const MIN_WORD_LENGTH: i32 = 2;
const MAX_WORD: usize = 64;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Scoring {
    pub length_norm: f32,
    pub length_bonus: f32,
    pub freq_weight: f32,
    pub prune_norm: f32,
    pub prune_bonus: f32,
    pub width: usize,
}

impl Default for Scoring {
    fn default() -> Self {
        Scoring {
            length_norm: 0.5949,
            length_bonus: 0.7271,
            freq_weight: 0.166,
            prune_norm: 0.1902,
            prune_bonus: 1.2727,
            width: 100,
        }
    }
}

/// Kotlin's `Float.pow(Float)`: the double `pow`, rounded back.
fn powf(x: f32, y: f32) -> f32 {
    strict_math::pow(x as f64, y as f64) as f32
}

#[derive(Clone, Copy, Default)]
struct Hyp {
    node: u32,
    parent: i64,
    label: u16,
    hop: u16,
    blank: bool,
    depth: i32,
    last: i32,
    score: f32,
    prune: f32,
}

/// `(node << 1 | blank)` to its index in the next beam: open addressing,
/// cleared by generation, as the Kotlin `SlotMap`.
#[derive(Default)]
struct SlotMap {
    keys: Vec<i64>,
    values: Vec<usize>,
    stamps: Vec<i32>,
    stamp: i32,
    mask: usize,
    load: usize,
}

impl SlotMap {
    fn clear(&mut self, expected: usize) {
        let mut size = 64;
        while size < expected * 2 {
            size <<= 1;
        }
        if size > self.keys.len() {
            self.keys = vec![0; size];
            self.values = vec![0; size];
            self.stamps = vec![0; size];
            self.mask = size - 1;
            self.stamp = 0;
        }
        self.stamp += 1;
        self.load = 0;
        if self.stamp == i32::MAX {
            self.stamps.fill(0);
            self.stamp = 1;
        }
    }

    fn hash(key: i64) -> usize {
        let mut x = key.wrapping_mul(-0x61c8864680b583ebi64);
        x ^= ((x as u64) >> 29) as i64;
        (x as i32) as u32 as usize
    }

    fn get(&self, key: i64) -> Option<usize> {
        let mut i = Self::hash(key) & self.mask;
        while self.stamps[i] == self.stamp {
            if self.keys[i] == key {
                return Some(self.values[i]);
            }
            i = (i + 1) & self.mask;
        }
        None
    }

    fn put(&mut self, key: i64, value: usize) {
        let mut i = Self::hash(key) & self.mask;
        while self.stamps[i] == self.stamp {
            if self.keys[i] == key {
                self.values[i] = value;
                return;
            }
            i = (i + 1) & self.mask;
        }
        let load = self.load;
        self.load += 1;
        if load * 2 > self.keys.len() {
            self.grow();
        }
        i = Self::hash(key) & self.mask;
        while self.stamps[i] == self.stamp {
            i = (i + 1) & self.mask;
        }
        self.keys[i] = key;
        self.values[i] = value;
        self.stamps[i] = self.stamp;
    }

    fn grow(&mut self) {
        let old_keys = std::mem::take(&mut self.keys);
        let old_values = std::mem::take(&mut self.values);
        let old_stamps = std::mem::take(&mut self.stamps);
        let live = self.stamp;
        let size = old_keys.len() * 2;
        self.keys = vec![0; size];
        self.values = vec![0; size];
        self.stamps = vec![0; size];
        self.mask = size - 1;
        self.stamp = 1;
        self.load = 0;
        for i in 0..old_keys.len() {
            if old_stamps[i] == live {
                self.put(old_keys[i], old_values[i]);
            }
        }
    }
}

/// The decoder and its pooled state, reused across decodes.
#[derive(Default)]
pub struct CtcDecoder {
    pub scoring: Scoring,
    cur: Vec<Hyp>,
    next: Vec<Hyp>,
    slots: SlotMap,
    /// How each surviving node was spelled from its parent: `(from, hop, label)`.
    spelled: HashMap<u32, (u32, u16, u16)>,
    children: Vec<(u16, u32)>,
    hop_children: Vec<(u16, u32)>,
    order: Vec<usize>,
}

impl CtcDecoder {
    pub fn new(scoring: Scoring) -> CtcDecoder {
        CtcDecoder { scoring, ..Default::default() }
    }

    /// Words for `log_probs` (`steps` rows of [`CLASSES`]) out of `sources`,
    /// best first, at most `limit`. Each source's `min_frequency` is its
    /// vocabulary cap, as the caller read it.
    pub fn decode(&mut self, log_probs: &[f32], steps: usize, sources: &[Source], limit: usize, width: usize) -> Vec<Candidate> {
        if limit == 0 || sources.is_empty() || steps == 0 {
            return Vec::new();
        }
        let mut best: HashMap<Vec<u16>, Candidate> = HashMap::new();
        for src in sources {
            self.search(src, log_probs, steps, width, &mut best);
        }
        let mut out: Vec<Candidate> = best.into_values().collect();
        sort::by(&mut out, by_score);
        out.truncate(limit);
        out
    }

    fn prune_key(&self, score: f32, depth: i32) -> f32 {
        score / powf(depth.max(1) as f32, self.scoring.prune_norm) + self.scoring.prune_bonus * depth as f32
    }

    fn search(&mut self, src: &Source, lp: &[f32], steps: usize, width: usize, best: &mut HashMap<Vec<u16>, Candidate>) {
        let walker = src.walker;
        let min_frequency = src.min_frequency;
        self.spelled.clear();
        self.cur.clear();
        let root = walker.root();
        let prune = self.prune_key(0.0, 0);
        self.cur.push(Hyp { node: root, parent: -1, label: NO_CHAR, hop: NO_CHAR, blank: false, depth: 0, last: -1, score: 0.0, prune });
        let mut children = std::mem::take(&mut self.children);
        let mut hop_children = std::mem::take(&mut self.hop_children);
        for t in 0..steps {
            let row = t * CLASSES;
            let blank = lp[row + BLANK];
            self.next.clear();
            self.slots.clear(width * EXPANSION);
            for h in 0..self.cur.len() {
                let cur = self.cur[h];
                let (node, score, depth) = (cur.node, cur.score, cur.depth);
                self.offer(node, cur.parent, cur.label, cur.hop, true, depth, cur.last, score + blank);
                let last = cur.last;
                if !cur.blank && last >= 0 {
                    self.offer(node, cur.parent, cur.label, cur.hop, false, depth, last, score + lp[row + last as usize]);
                }
                walker.children_into(node, &mut children);
                for &(label, child) in children.iter() {
                    if min_frequency > 0 && walker.max_subtree(child) < min_frequency {
                        continue;
                    }
                    let cls = class_of(label);
                    if cls >= 0 {
                        self.offer(child, node as i64, label, NO_CHAR, false, depth + 1, cls, score + lp[row + cls as usize]);
                    } else if depth > 0 && is_joiner(label as i32) {
                        walker.children_into(child, &mut hop_children);
                        for &(hop_label, grandchild) in hop_children.iter() {
                            let hop_cls = class_of(hop_label);
                            if hop_cls < 0 {
                                continue;
                            }
                            if min_frequency > 0 && walker.max_subtree(grandchild) < min_frequency {
                                continue;
                            }
                            self.offer(grandchild, node as i64, hop_label, label, false, depth + 1, hop_cls, score + lp[row + hop_cls as usize]);
                        }
                    }
                }
            }
            self.prune(width);
            if self.cur.is_empty() {
                break;
            }
        }
        self.children = children;
        self.hop_children = hop_children;
        if self.cur.is_empty() {
            return;
        }
        for h in 0..self.cur.len() {
            let cur = self.cur[h];
            if !walker.is_word(cur.node) || cur.depth < MIN_WORD_LENGTH {
                continue;
            }
            let frequency = walker.frequency(cur.node);
            if frequency < min_frequency {
                continue;
            }
            let Some(word) = self.word(cur.node) else { continue };
            if src.tier == Tier::Dictionary && is_non_word(&word) {
                continue;
            }
            let length = cur.depth as f32;
            let path = cur.score / powf(length, self.scoring.length_norm);
            let score = path
                + self.scoring.length_bonus * length
                + self.scoring.freq_weight * (src.log_weight + strict_math::ln1p_count(frequency)) as f32;
            let better = match best.get(&word) {
                None => true,
                Some(existing) => existing.score < score as f64,
            };
            if better {
                best.insert(
                    word.clone(),
                    Candidate { word, score: score as f64, shape_cost: -(path as f64), tier: src.tier, ahead: 0 },
                );
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn offer(&mut self, node: u32, parent: i64, label: u16, hop: u16, blank: bool, depth: i32, last: i32, score: f32) {
        let key = ((node as i64) << 1) | blank as i64;
        if let Some(at) = self.slots.get(key) {
            if self.next[at].score < score {
                let prune = self.prune_key(score, depth);
                let h = &mut self.next[at];
                h.parent = parent;
                h.label = label;
                h.hop = hop;
                h.depth = depth;
                h.last = last;
                h.score = score;
                h.prune = prune;
            }
            return;
        }
        self.slots.put(key, self.next.len());
        let prune = self.prune_key(score, depth);
        self.next.push(Hyp { node, parent, label, hop, blank, depth, last, score, prune });
    }

    fn prune(&mut self, width: usize) {
        let n = self.next.len();
        self.order.clear();
        self.order.extend(0..n);
        let keep = n.min(width);
        if n > width {
            self.select(n, width);
        }
        self.cur.clear();
        for k in 0..keep {
            let h = self.next[self.order[k]];
            self.cur.push(h);
            if h.parent >= 0 {
                self.spelled.entry(h.node).or_insert((h.parent as u32, h.hop, h.label));
            }
        }
    }

    /// The `k` highest pruning keys among the first `n` of `order` to its
    /// front: the Kotlin quickselect, swap for swap.
    fn select(&mut self, n: usize, k: usize) {
        let prune = |order: &[usize], next: &[Hyp], i: usize| next[order[i]].prune;
        let (mut lo, mut hi) = (0i64, n as i64 - 1);
        let k = k as i64;
        while lo < hi {
            let pivot = prune(&self.order, &self.next, ((lo + hi) as u64 >> 1) as usize);
            let (mut i, mut j) = (lo, hi);
            while i <= j {
                while prune(&self.order, &self.next, i as usize) > pivot {
                    i += 1;
                }
                while prune(&self.order, &self.next, j as usize) < pivot {
                    j -= 1;
                }
                if i <= j {
                    self.order.swap(i as usize, j as usize);
                    i += 1;
                    j -= 1;
                }
            }
            if k - 1 <= j {
                hi = j;
            } else if k - 1 >= i {
                lo = i;
            } else {
                return;
            }
        }
    }

    /// The word a surviving node spells, from the recorded steps.
    fn word(&self, node: u32) -> Option<Vec<u16>> {
        let mut out: Vec<u16> = Vec::new();
        let mut at = node;
        let mut guard = 0;
        while let Some(&(from, hop, label)) = self.spelled.get(&at) {
            out.push(label);
            if hop != 0 {
                out.push(hop);
            }
            at = from;
            guard += 1;
            if guard > MAX_WORD {
                return None;
            }
        }
        if out.is_empty() {
            return None;
        }
        out.reverse();
        Some(out)
    }
}

/// a..z for a letter of either case, -1 for anything the model has no class for.
fn class_of(label: u16) -> i32 {
    let c = if (b'A' as u16..=b'Z' as u16).contains(&label) { label + (b'a' - b'A') as u16 } else { label };
    if (b'a' as u16..=b'z' as u16).contains(&c) {
        (c - b'a' as u16) as i32
    } else {
        -1
    }
}

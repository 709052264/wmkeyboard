//! Trie-guided fuzzy search: corrections and completions in one best-first
//! walk. A port of the Kotlin `FuzzyBeamSearch` (core/prediction).
//!
//! Instead of generating every edit of the typed word and probing the
//! dictionary, the walk descends the trie itself, so only words the
//! dictionary holds are ever considered: two-edit corrections stay
//! affordable, a mid-word prefix is corrected while it is completed, and
//! substitutions come from the trie's own edge labels, in any script.
//!
//! Scoring is log-space: `score = sourceLogWeight + ln(1 + frequency) -
//! editCost`, the edit penalties being the negative logs of the legacy
//! multiplicative weights. A word reachable several ways keeps its best
//! score. The expansion order is admissible best-first: a state's bound is
//! `sourceLogWeight + ln(1 + maxSubtree(node)) - cost`, which only ever
//! overestimates, so pruning against the K-th best never loses a top-K word.
//!
//! Text is UTF-16 code units throughout, as the dictionaries store it and as
//! the Kotlin walk reads it. Touch scoring and ambiguous (T9) keys, which
//! only an on-screen keyboard has, are left out; the learned edit habits are
//! a parameter ([`EditHabits`]).

use crate::accents;
use crate::proximity::KeyProximity;
use crate::wmdict::Dict;
use std::collections::HashMap;

/// Node-level read access to a trie. Handles are plain integers; the root
/// is 0.
pub trait Walker {
    fn root(&self) -> u32 {
        0
    }
    fn child(&self, node: u32, label: u16) -> Option<u32>;
    /// Every `(label, child)` of `node` into `out`, cleared first.
    fn children_into(&self, node: u32, out: &mut Vec<(u16, u32)>);
    fn is_word(&self, node: u32) -> bool;
    fn frequency(&self, node: u32) -> i32;
    /// The highest word frequency anywhere under `node`, itself included.
    fn max_subtree(&self, node: u32) -> i32;
}

impl<B: AsRef<[u8]>> Walker for Dict<B> {
    fn child(&self, node: u32, label: u16) -> Option<u32> {
        Dict::child(self, node, label)
    }
    fn children_into(&self, node: u32, out: &mut Vec<(u16, u32)>) {
        Dict::children_into(self, node, out)
    }
    fn is_word(&self, node: u32) -> bool {
        Dict::is_word(self, node)
    }
    fn frequency(&self, node: u32) -> i32 {
        Dict::frequency(self, node)
    }
    fn max_subtree(&self, node: u32) -> i32 {
        Dict::max_subtree(self, node)
    }
}

/// How a learned slip habit shrinks an edit's price: 0 for none, up to 1.
/// The Kotlin `EditHabits`; [`NO_HABITS`] until correction memory is ported.
pub trait EditHabits {
    fn deletion(&self, _typed: u16) -> f64 {
        0.0
    }
    fn substitution(&self, _typed: u16, _meant: u16) -> f64 {
        0.0
    }
    fn insertion(&self, _meant: u16) -> f64 {
        0.0
    }
}

pub struct NoHabits;
impl EditHabits for NoHabits {}
pub const NO_HABITS: NoHabits = NoHabits;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Tier {
    Dictionary,
    User,
}

/// One weighted trie in a search.
pub struct WalkSource<'a> {
    pub walker: &'a dyn Walker,
    pub log_weight: f64,
    pub tier: Tier,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ScoredCandidate {
    pub word: String,
    pub score: f64,
    pub edit_cost: f64,
    pub edits: u32,
    /// Characters appended beyond the typed length by completion.
    pub completed_chars: u32,
    pub tier: Tier,
    /// Best score from dictionary-tier sources (-inf when none).
    pub dict_score: f64,
    /// Best score from user-tier sources (-inf when none).
    pub user_score: f64,
    /// Letters read as their accented form; not edits.
    pub accents: u32,
}

pub fn ln(x: f64) -> f64 {
    x.ln()
}

/// Two keys swapped across the hands: the ordinary racing-hands slip.
pub fn cost_transposition() -> f64 {
    -ln(0.9)
}
/// The same swap within one hand, rarer and a worse explanation.
pub fn cost_transposition_same_hand() -> f64 {
    -ln(0.7)
}
pub fn cost_sub_adjacent() -> f64 {
    -ln(0.9)
}
pub fn cost_deletion() -> f64 {
    -ln(0.7)
}
/// Deleting a char that doubles its neighbour ("helllo").
pub fn cost_delete_doubled() -> f64 {
    -ln(0.9)
}
pub fn cost_insert_adjacent() -> f64 {
    -ln(0.7)
}
pub fn cost_insert_far() -> f64 {
    -ln(0.25)
}
pub fn cost_sub_far() -> f64 {
    -ln(0.2)
}
/// A letter typed without its accent: priced like the adjacent slip, but
/// outside the edit budget.
pub fn cost_accent() -> f64 {
    -ln(0.9)
}
pub const COMPLETION_STEP: f64 = 0.0;
/// Per-character completion cost once the path holds an edit.
pub fn cost_completion_after_edit() -> f64 {
    -ln(0.25)
}
/// Two far substitutions can never survive; two adjacent slips can.
pub const MAX_EDIT_COST: f64 = 2.0;
/// The second edit's surcharge: a two-edit word ranks below any one-edit fix.
pub const SECOND_EDIT_SURCHARGE: f64 = 2.0;
/// Autocorrect wants a deeper list than the strip shows.
pub const AUTOCORRECT_K: usize = 8;
/// No learned habit prices an edit under the adjacent-slip tier.
pub fn habit_floor() -> f64 {
    cost_sub_adjacent()
}
/// Runaway-state backstop.
pub const MAX_POPS: usize = 4096;
const EPS: f64 = 1e-9;
/// The Persian half-space, U+200C.
const ZWNJ: u16 = 0x200C;
const NO_LABEL: u16 = 0;

pub fn default_max_edits(typed_len: usize) -> u32 {
    if typed_len >= 5 {
        2
    } else {
        1
    }
}

fn ln1p(v: i32) -> f64 {
    (1.0 + v as f64).ln()
}

/// Pooled state store and index heap: the search allocates nothing per
/// keystroke beyond emitted words when a workspace is reused.
#[derive(Default)]
pub struct Workspace {
    node: Vec<u32>,
    pos: Vec<u16>,
    cost: Vec<f64>,
    edit_cost: Vec<f64>,
    edits: Vec<u8>,
    comp: Vec<u8>,
    accents: Vec<u8>,
    parent: Vec<i32>,
    via: Vec<u16>,
    bound: Vec<f64>,
    heap: Vec<u32>,
    children: Vec<(u16, u32)>,
}

impl Workspace {
    pub fn new() -> Workspace {
        Workspace::default()
    }

    fn reset(&mut self) {
        self.node.clear();
        self.pos.clear();
        self.cost.clear();
        self.edit_cost.clear();
        self.edits.clear();
        self.comp.clear();
        self.accents.clear();
        self.parent.clear();
        self.via.clear();
        self.bound.clear();
        self.heap.clear();
    }

    #[allow(clippy::too_many_arguments)]
    fn record(
        &mut self,
        node: u32,
        pos: usize,
        cost: f64,
        edit: f64,
        edits: u32,
        comp: u32,
        accents: u32,
        parent: i32,
        via: u16,
    ) -> i32 {
        let id = self.node.len() as i32;
        self.node.push(node);
        self.pos.push(pos as u16);
        self.cost.push(cost);
        self.edit_cost.push(edit);
        self.edits.push(edits as u8);
        self.comp.push(comp.min(255) as u8);
        self.accents.push(accents.min(255) as u8);
        self.parent.push(parent);
        self.via.push(via);
        self.bound.push(0.0);
        id
    }

    #[allow(clippy::too_many_arguments)]
    fn state(
        &mut self,
        node: u32,
        pos: usize,
        cost: f64,
        edit: f64,
        edits: u32,
        comp: u32,
        accents: u32,
        parent: i32,
        via: u16,
        bound: f64,
    ) {
        let id = self.record(node, pos, cost, edit, edits, comp, accents, parent, via);
        self.bound[id as usize] = bound;
        // Max-heap on bound.
        self.heap.push(id as u32);
        let mut i = self.heap.len() - 1;
        while i > 0 {
            let up = (i - 1) / 2;
            if self.bound[self.heap[up] as usize] >= self.bound[self.heap[i] as usize] {
                break;
            }
            self.heap.swap(up, i);
            i = up;
        }
    }

    fn pop(&mut self) -> usize {
        let top = self.heap[0];
        let last = self.heap.pop().unwrap();
        if !self.heap.is_empty() {
            self.heap[0] = last;
            let mut i = 0;
            let n = self.heap.len();
            loop {
                let l = i * 2 + 1;
                if l >= n {
                    break;
                }
                let r = l + 1;
                let mut best = l;
                if r < n && self.bound[self.heap[r] as usize] > self.bound[self.heap[l] as usize] {
                    best = r;
                }
                if self.bound[self.heap[i] as usize] >= self.bound[self.heap[best] as usize] {
                    break;
                }
                self.heap.swap(i, best);
                i = best;
            }
        }
        top as usize
    }

    /// The word for state `s`, from its parent chain.
    fn materialize(&self, s: usize) -> String {
        let mut units = Vec::new();
        let mut cur = s as i32;
        while cur >= 0 {
            let label = self.via[cur as usize];
            if label != NO_LABEL {
                units.push(label);
            }
            cur = self.parent[cur as usize];
        }
        units.reverse();
        String::from_utf16_lossy(&units)
    }
}

fn find(children: &[(u16, u32)], label: u16) -> Option<u32> {
    children.iter().find(|(l, _)| *l == label).map(|(_, n)| *n)
}

fn discounted(base: f64, shrink: f64) -> f64 {
    if shrink <= 0.0 {
        base
    } else {
        habit_floor().max(base * (1.0 - shrink))
    }
}

/// The search: up to `max(limit * 2, AUTOCORRECT_K)` candidates, best first
/// (ties by the word).
#[allow(clippy::too_many_arguments)]
pub fn search(
    sources: &[WalkSource],
    typed: &str,
    proximity: &KeyProximity,
    limit: usize,
    ws: &mut Workspace,
    max_edits: Option<u32>,
    habits: &dyn EditHabits,
) -> Vec<ScoredCandidate> {
    let typed: Vec<u16> = typed.encode_utf16().collect();
    if typed.is_empty() || limit == 0 {
        return Vec::new();
    }
    let max_edits = max_edits.unwrap_or_else(|| default_max_edits(typed.len()));
    let k = (limit * 2).max(AUTOCORRECT_K);
    let mut results: HashMap<String, ScoredCandidate> = HashMap::new();
    let mut floor = f64::NEG_INFINITY;
    // Heaviest source first: its emissions raise the floor early.
    let mut ordered: Vec<&WalkSource> = sources.iter().collect();
    if ordered.len() > 1 {
        ordered.sort_by(|a, b| {
            let ka = a.log_weight + ln1p(a.walker.max_subtree(a.walker.root()));
            let kb = b.log_weight + ln1p(b.walker.max_subtree(b.walker.root()));
            kb.partial_cmp(&ka).unwrap_or(std::cmp::Ordering::Equal)
        });
    }
    for src in ordered {
        let root_bound = src.log_weight + ln1p(src.walker.max_subtree(src.walker.root()));
        if root_bound < floor - EPS {
            continue;
        }
        floor = search_one(
            src,
            &typed,
            proximity,
            max_edits,
            k,
            &mut results,
            floor,
            ws,
            habits,
        );
    }
    let mut out: Vec<ScoredCandidate> = results.into_values().collect();
    out.sort_by(|a, b| {
        b.score
            .partial_cmp(&a.score)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| a.word.cmp(&b.word))
    });
    out.truncate(k);
    out
}

#[allow(clippy::too_many_arguments)]
fn search_one(
    src: &WalkSource,
    typed: &[u16],
    proximity: &KeyProximity,
    max_edits: u32,
    k: usize,
    results: &mut HashMap<String, ScoredCandidate>,
    floor_in: f64,
    ws: &mut Workspace,
    habits: &dyn EditHabits,
) -> f64 {
    let mut floor = floor_in;
    let w = src.walker;
    let n = typed.len();
    ws.reset();
    let root = w.root();
    ws.state(
        root,
        0,
        0.0,
        0.0,
        0,
        0,
        0,
        -1,
        NO_LABEL,
        src.log_weight + ln1p(w.max_subtree(root)),
    );
    let mut pops = 0;
    let mut children = std::mem::take(&mut ws.children);
    macro_rules! push {
        ($node:expr, $pos:expr, $cost:expr, $edit:expr, $edits:expr, $comp:expr, $acc:expr, $parent:expr, $via:expr) => {{
            let node = $node;
            let cost = $cost;
            let bound = src.log_weight + ln1p(w.max_subtree(node)) - cost;
            if bound >= floor - EPS {
                ws.state(
                    node, $pos, cost, $edit, $edits, $comp, $acc, $parent, $via, bound,
                );
            }
        }};
    }
    while !ws.heap.is_empty() && pops < MAX_POPS {
        let s = ws.pop();
        if ws.bound[s] < floor - EPS {
            break;
        }
        pops += 1;
        let node = ws.node[s];
        let pos = ws.pos[s] as usize;
        let cost = ws.cost[s];
        let edit = ws.edit_cost[s];
        let edits = ws.edits[s] as u32;
        let comp = ws.comp[s] as u32;
        let acc = ws.accents[s] as u32;
        let parent = s as i32;

        if pos == n {
            if w.is_word(node) {
                let score = src.log_weight + ln1p(w.frequency(node)) - cost;
                if score > floor - EPS || results.len() < k {
                    emit(
                        ws.materialize(s),
                        score,
                        edit,
                        edits,
                        comp,
                        acc,
                        src.tier,
                        results,
                    );
                    if results.len() >= k {
                        floor = kth_best(results, k);
                    }
                }
            }
            // Completion: free on a clean prefix, charged after an edit.
            let step = if edits > 0 {
                cost_completion_after_edit()
            } else {
                COMPLETION_STEP
            };
            w.children_into(node, &mut children);
            for &(label, child) in &children {
                push!(
                    child,
                    n,
                    cost + step,
                    edit,
                    edits,
                    comp + 1,
                    acc,
                    parent,
                    label
                );
            }
            continue;
        }

        let expected = typed[pos];
        w.children_into(node, &mut children);
        // A half-space the word is spelled with and the typing left out.
        if expected != ZWNJ {
            if let Some(joined) = find(&children, ZWNJ) {
                push!(
                    joined,
                    pos,
                    cost + cost_accent(),
                    edit,
                    edits,
                    comp,
                    acc + 1,
                    parent,
                    ZWNJ
                );
            }
        }
        // The typed character itself.
        if let Some(matched) = find(&children, expected) {
            push!(
                matched,
                pos + 1,
                cost,
                edit,
                edits,
                comp,
                acc,
                parent,
                expected
            );
        }
        // The same key with its accent left off.
        for &(label, child) in &children {
            if accents::is_accent_of(label, expected) {
                push!(
                    child,
                    pos + 1,
                    cost + cost_accent(),
                    edit,
                    edits,
                    comp,
                    acc + 1,
                    parent,
                    label
                );
            }
        }

        if edits < max_edits && edit < MAX_EDIT_COST {
            let surcharge = if edits + 1 >= 2 {
                SECOND_EDIT_SURCHARGE
            } else {
                0.0
            };
            // Deletion: the typed character was an extra keypress.
            let doubled = (pos + 1 < n && typed[pos + 1] == expected)
                || (pos > 0 && typed[pos - 1] == expected);
            let del = discounted(
                if doubled {
                    cost_delete_doubled()
                } else {
                    cost_deletion()
                },
                habits.deletion(expected),
            );
            if edit + del <= MAX_EDIT_COST {
                push!(
                    node,
                    pos + 1,
                    cost + del + surcharge,
                    edit + del,
                    edits + 1,
                    comp,
                    acc,
                    parent,
                    NO_LABEL
                );
            }
            for &(label, child) in &children {
                let accented = accents::is_accent_of(label, expected);
                if label != expected && !accented {
                    let base = if proximity.are_adjacent(expected, label) {
                        cost_sub_adjacent()
                    } else {
                        cost_sub_far()
                    };
                    let sub = discounted(base, habits.substitution(expected, label));
                    if edit + sub <= MAX_EDIT_COST {
                        push!(
                            child,
                            pos + 1,
                            cost + sub + surcharge,
                            edit + sub,
                            edits + 1,
                            comp,
                            acc,
                            parent,
                            label
                        );
                    }
                }
                // Insertion: the word has `label` here and the typing missed it.
                let adjacent = proximity.are_adjacent(expected, label)
                    || (pos > 0
                        && (typed[pos - 1] == label
                            || proximity.are_adjacent(typed[pos - 1], label)));
                let ins = discounted(
                    if adjacent {
                        cost_insert_adjacent()
                    } else {
                        cost_insert_far()
                    },
                    habits.insertion(label),
                );
                if edit + ins <= MAX_EDIT_COST {
                    push!(
                        child,
                        pos,
                        cost + ins + surcharge,
                        edit + ins,
                        edits + 1,
                        comp,
                        acc,
                        parent,
                        label
                    );
                }
            }
            // Transposition of the next two typed characters, priced by hand.
            let transpose = if pos + 1 < n && proximity.same_hand(typed[pos], typed[pos + 1]) {
                cost_transposition_same_hand()
            } else {
                cost_transposition()
            };
            if pos + 1 < n && typed[pos] != typed[pos + 1] && edit + transpose <= MAX_EDIT_COST {
                if let Some(first) = find(&children, typed[pos + 1]) {
                    if let Some(second) = w.child(first, typed[pos]) {
                        let link = ws.record(
                            first,
                            pos + 1,
                            cost,
                            edit,
                            edits,
                            comp,
                            acc,
                            parent,
                            typed[pos + 1],
                        );
                        push!(
                            second,
                            pos + 2,
                            cost + transpose + surcharge,
                            edit + transpose,
                            edits + 1,
                            comp,
                            acc,
                            link,
                            typed[pos]
                        );
                    }
                }
            }
        }
    }
    ws.children = children;
    floor
}

#[allow(clippy::too_many_arguments)]
fn emit(
    word: String,
    score: f64,
    edit_cost: f64,
    edits: u32,
    completed: u32,
    accents: u32,
    tier: Tier,
    results: &mut HashMap<String, ScoredCandidate>,
) {
    let existing = results.get(&word);
    let dict_score =
        existing
            .map_or(f64::NEG_INFINITY, |e| e.dict_score)
            .max(if tier == Tier::Dictionary {
                score
            } else {
                f64::NEG_INFINITY
            });
    let user_score =
        existing
            .map_or(f64::NEG_INFINITY, |e| e.user_score)
            .max(if tier == Tier::User {
                score
            } else {
                f64::NEG_INFINITY
            });
    let next = match existing {
        Some(e) if score <= e.score => ScoredCandidate {
            dict_score,
            user_score,
            ..e.clone()
        },
        _ => ScoredCandidate {
            word: word.clone(),
            score,
            edit_cost,
            edits,
            completed_chars: completed,
            tier,
            dict_score,
            user_score,
            accents,
        },
    };
    results.insert(word, next);
}

fn kth_best(results: &HashMap<String, ScoredCandidate>, k: usize) -> f64 {
    if results.len() < k {
        return f64::NEG_INFINITY;
    }
    let mut scores: Vec<f64> = results.values().map(|c| c.score).collect();
    scores.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    scores[scores.len() - k]
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wmdict::{self, PackedTrie};

    fn dict(entries: &[(&str, i32)]) -> Dict {
        Dict::open(wmdict::write(&PackedTrie::of(entries))).unwrap()
    }

    fn run(
        sources: &[WalkSource],
        typed: &str,
        limit: usize,
        max_edits: Option<u32>,
    ) -> Vec<ScoredCandidate> {
        search(
            sources,
            typed,
            &KeyProximity::qwerty(false),
            limit,
            &mut Workspace::new(),
            max_edits,
            &NO_HABITS,
        )
    }

    fn src(d: &Dict, w: f64) -> WalkSource<'_> {
        WalkSource {
            walker: d,
            log_weight: w,
            tier: Tier::Dictionary,
        }
    }

    fn real() -> Vec<(String, i32)> {
        let text = std::fs::read_to_string(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../app/dictionaries-src/en.txt"
        ))
        .expect("the app's en.txt");
        crate::wordlist::parse(&text)
    }

    #[test]
    fn doubled_letter_deletion_costs_the_cheap_tier() {
        let d = dict(&[("hello", 100)]);
        let got = &run(&[src(&d, 0.0)], "helllo", 5, None)[0];
        assert_eq!("hello", got.word);
        assert!((got.edit_cost - cost_delete_doubled()).abs() < 1e-9);
    }

    #[test]
    fn missed_doubling_inserts_at_adjacent_cost() {
        let d = dict(&[("running", 100)]);
        let got = &run(&[src(&d, 0.0)], "runing", 5, None)[0];
        assert_eq!("running", got.word);
        assert!((got.edit_cost - cost_insert_adjacent()).abs() < 1e-9);
    }

    #[test]
    fn zero_edit_walk_matches_classic_completion() {
        let entries = real();
        let d = Dict::open(wmdict::write(&PackedTrie::of(&entries))).unwrap();
        for prefix in ["th", "he", "wo", "pre", "a"] {
            let classic: Vec<String> = d.complete(prefix, 10).into_iter().map(|s| s.word).collect();
            let beam: Vec<String> = run(&[src(&d, 0.0)], prefix, 10, Some(0))
                .into_iter()
                .map(|c| c.word)
                .filter(|w| w.starts_with(prefix))
                .collect();
            assert_eq!(
                classic,
                beam[..classic.len()].to_vec(),
                "completion parity for '{prefix}'"
            );
        }
    }

    /// The legacy Norvig edits-1 generator: candidate -> best weight.
    fn norvig(word: &str, p: &KeyProximity) -> HashMap<String, f64> {
        let w: Vec<char> = word.chars().collect();
        let u = |c: char| c as u16;
        let mut out: HashMap<String, f64> = HashMap::new();
        let mut put = |c: String, weight: f64| {
            if c != word {
                let e = out.entry(c).or_insert(weight);
                *e = e.max(weight);
            }
        };
        for i in 0..w.len() {
            let mut v = w.clone();
            v.remove(i);
            put(v.iter().collect(), 0.7);
        }
        for i in 0..w.len().saturating_sub(1) {
            let mut v = w.clone();
            v.swap(i, i + 1);
            put(v.iter().collect(), 0.9);
        }
        for i in 0..w.len() {
            for c in 'a'..='z' {
                if c == w[i] {
                    continue;
                }
                let mut v = w.clone();
                v[i] = c;
                put(
                    v.iter().collect(),
                    if p.are_adjacent(u(w[i]), u(c)) {
                        0.9
                    } else {
                        0.2
                    },
                );
            }
        }
        for i in 0..=w.len() {
            for c in 'a'..='z' {
                let near = (i > 0 && p.are_adjacent(u(w[i - 1]), u(c)))
                    || (i < w.len() && p.are_adjacent(u(w[i]), u(c)));
                let mut v = w.clone();
                v.insert(i, c);
                put(v.iter().collect(), if near { 0.7 } else { 0.25 });
            }
        }
        out
    }

    #[test]
    fn norvig_candidates_all_surface_with_matching_scores() {
        let entries = real();
        let d = Dict::open(wmdict::write(&PackedTrie::of(&entries))).unwrap();
        let q = KeyProximity::qwerty(false);
        for typed in ["helo", "wrold", "teh", "beleive", "recieve", "adress"] {
            let beam: HashMap<String, ScoredCandidate> = run(&[src(&d, 0.0)], typed, 400, Some(1))
                .into_iter()
                .map(|c| (c.word.clone(), c))
                .collect();
            for (cand, weight) in norvig(typed, &q) {
                let freq = d.frequency_of(&cand);
                if freq <= 0 || cand == typed {
                    continue;
                }
                let expected_cost = if cand.starts_with(typed) {
                    0.0
                } else {
                    -weight.ln()
                };
                let expected = (1.0 + freq as f64).ln() - expected_cost;
                let got = beam
                    .get(&cand)
                    .unwrap_or_else(|| panic!("'{typed}' -> '{cand}' missing"));
                assert!(
                    got.score >= expected - 1e-6,
                    "'{typed}' -> '{cand}' {} < {expected}",
                    got.score
                );
            }
        }
    }

    #[test]
    fn two_adjacent_slips_are_reachable() {
        let d = dict(&[("hello", 100)]);
        let r = run(&[src(&d, 0.0)], "gwllo", 5, None);
        assert_eq!(1, r.len());
        assert_eq!(("hello", 2), (r[0].word.as_str(), r[0].edits));
    }

    #[test]
    fn two_far_substitutions_are_not_reachable() {
        let d = dict(&[("hello", 100)]);
        assert!(run(&[src(&d, 0.0)], "hzlqo", 5, None).is_empty());
    }

    #[test]
    fn correction_and_completion_unify() {
        let d = dict(&[("the", 100), ("thermometer", 40)]);
        let words: Vec<String> = run(&[src(&d, 0.0)], "teh", 5, None)
            .into_iter()
            .map(|c| c.word)
            .collect();
        assert!(words.contains(&"the".to_string()) && words.contains(&"thermometer".to_string()));
    }

    #[test]
    fn non_ascii_corrections_come_from_trie_edges() {
        let d = dict(&[("привет", 50)]);
        let r = run(&[src(&d, 0.0)], "правет", 5, None);
        assert_eq!(
            vec!["привет".to_string()],
            r.into_iter().map(|c| c.word).collect::<Vec<_>>()
        );
    }

    #[test]
    fn source_order_does_not_change_results() {
        let a = dict(&[("hello", 70), ("help", 60)]);
        let b = dict(&[("helm", 40), ("hell", 30)]);
        let f: Vec<(String, f64)> = run(&[src(&a, 0.0), src(&b, 2f64.ln())], "helo", 8, None)
            .into_iter()
            .map(|c| (c.word, c.score))
            .collect();
        let r: Vec<(String, f64)> = run(&[src(&b, 2f64.ln()), src(&a, 0.0)], "helo", 8, None)
            .into_iter()
            .map(|c| (c.word, c.score))
            .collect();
        assert_eq!(f, r);
    }

    #[test]
    fn max_merge_across_sources_keeps_best_score_and_tier() {
        let d = dict(&[("hello", 10)]);
        let user = dict(&[("hello", 10)]);
        let r = run(
            &[
                src(&d, 0.0),
                WalkSource {
                    walker: &user,
                    log_weight: 500f64.ln(),
                    tier: Tier::User,
                },
            ],
            "hello",
            4,
            None,
        );
        let hello = r.iter().find(|c| c.word == "hello").unwrap();
        assert_eq!(Tier::User, hello.tier);
        assert!((hello.score - (500f64.ln() + 11f64.ln())).abs() < 1e-9);
    }

    #[test]
    fn accents_read_without_an_edit() {
        let d = dict(&[("już", 100)]);
        let got = &run(&[src(&d, 0.0)], "juz", 5, None)[0];
        assert_eq!(("już", 0, 1), (got.word.as_str(), got.edits, got.accents));
    }

    struct Habit;
    impl EditHabits for Habit {
        fn substitution(&self, typed: u16, meant: u16) -> f64 {
            if typed == 'x' as u16 && meant == 'u' as u16 {
                0.6
            } else {
                0.0
            }
        }
    }

    #[test]
    fn a_learned_habit_discounts_its_edit_but_never_below_the_floor() {
        let d = dict(&[("hullo", 100)]);
        let q = KeyProximity::qwerty(false);
        let plain = &run(&[src(&d, 0.0)], "hxllo", 5, None)[0];
        let learned = &search(
            &[src(&d, 0.0)],
            "hxllo",
            &q,
            5,
            &mut Workspace::new(),
            None,
            &Habit,
        )[0];
        assert!((plain.edit_cost - cost_sub_far()).abs() < 1e-9);
        assert!(learned.edit_cost < plain.edit_cost && learned.edit_cost >= habit_floor());
    }
}

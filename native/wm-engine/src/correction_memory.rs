//! What the user's own fixes have taught autocorrect: the Kotlin
//! `CorrectionMemory`, `EditOps` and `EditHabits` (core/prediction).
//!
//! A word typed, left standing, then repaired by hand teaches two things:
//! the pair (`teh` -> `the`, counted; fixed twice is a fix to make for the
//! user, once is one to offer) and the slip behind it, aligned letter by
//! letter and counted per layout, which [`LearnedHabits`] turns into cheaper
//! edits for the fuzzy walk.
//!
//! The file format is the phone's `learning/learned_corrections.json`:
//! `{"pairs":{"teh":[{"fixed","count","gen"}]},"habits":{"layout":{"sub:3e":2}},
//! "generation"}`.

use crate::fuzzy::EditHabits;
use serde_json::{json, Map, Value};
use std::collections::HashMap;

/// Fixes of the same spelling that make it a fix the user wants made.
pub const APPLY_AT: i32 = 2;
/// ...when the spelling is itself a word ("form"): the bar is higher, and
/// the context has to agree.
pub const APPLY_KNOWN_AT: i32 = 3;
/// A fix has to outnumber the next fix of the same spelling this many times.
const DOMINANCE: i32 = 2;
const MAX_FIXES_PER_TYPED: usize = 3;
const MAX_PAIRS: usize = 1000;
const MAX_COUNT: i32 = 100;
const EXPIRE_GENERATIONS: i64 = 180;
/// Habit counters halve past this many sightings on one layout.
const HABIT_WINDOW: i32 = 400;
const MIN_TYPED_LENGTH: usize = 3;
const MAX_WORD_LENGTH: usize = 32;
const MIN_HALF_LENGTH: usize = 2;
const MAX_EDITS: usize = 2;
const LONG_WORD_LENGTH: usize = 8;
const MAX_EDITS_LONG: usize = 3;
/// The most a habit may take off a base cost.
const MAX_SHRINK: f64 = 0.75;
/// Sightings at which a habit is worth half of [`MAX_SHRINK`].
const PIVOT: f64 = 2.0;
/// Fixes a layout has to have taught before any habit moves a cost.
const WARMUP: i32 = 8;

/// What a fix teaches: the pair alone (a real word swapped for another is a
/// choice, not a slip), or the pair and the slip behind it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    PairOnly,
    PairAndHabits,
}

/// A fix taught for one typed spelling, and how often.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Taught {
    pub fixed: String,
    pub count: i32,
}

#[derive(Clone, Debug)]
struct Stored {
    fixed: String,
    count: i32,
    gen: i64,
}

#[derive(Default)]
pub struct CorrectionMemory {
    pairs: HashMap<String, Vec<Stored>>,
    habits: HashMap<String, HashMap<String, i32>>,
    generation: i64,
    dirty: bool,
}

/// One step of an alignment of what was typed onto what was meant.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Op {
    Match(char),
    /// `typed` stood where `intended` was meant.
    Sub {
        typed: char,
        intended: char,
    },
    /// `intended` was meant and never typed.
    Ins(char),
    /// A stray keypress.
    Del(char),
    /// The pair was meant the other way round: `first` then `second`.
    Swap {
        first: char,
        second: char,
    },
}

fn swappable(t: &[char], m: &[char], i: usize, j: usize) -> bool {
    i > 1 && j > 1 && t[i - 1] == m[j - 2] && t[i - 2] == m[j - 1] && t[i - 1] != t[i - 2]
}

fn table(t: &[char], m: &[char]) -> Vec<Vec<usize>> {
    let (n, w) = (t.len(), m.len());
    let mut d = vec![vec![0usize; w + 1]; n + 1];
    for (i, row) in d.iter_mut().enumerate() {
        row[0] = i;
    }
    for (j, cell) in d[0].iter_mut().enumerate() {
        *cell = j;
    }
    for i in 1..=n {
        for j in 1..=w {
            let cost = usize::from(t[i - 1] != m[j - 1]);
            let mut best = (d[i - 1][j] + 1)
                .min(d[i][j - 1] + 1)
                .min(d[i - 1][j - 1] + cost);
            if swappable(t, m, i, j) {
                best = best.min(d[i - 2][j - 2] + 1);
            }
            d[i][j] = best;
        }
    }
    d
}

/// Edit distance, adjacent swaps counting one.
pub fn distance(typed: &str, intended: &str) -> usize {
    let t: Vec<char> = typed.chars().collect();
    let m: Vec<char> = intended.chars().collect();
    if t.is_empty() {
        return m.len();
    }
    if m.is_empty() {
        return t.len();
    }
    table(&t, &m)[t.len()][m.len()]
}

/// The cheapest alignment of `typed` onto `intended`, left to right, ties
/// broken match, swap, substitution, deletion, insertion.
pub fn align(typed: &str, intended: &str) -> Vec<Op> {
    let t: Vec<char> = typed.chars().collect();
    let m: Vec<char> = intended.chars().collect();
    let d = table(&t, &m);
    let (mut i, mut j) = (t.len(), m.len());
    let mut ops = Vec::new();
    while i > 0 || j > 0 {
        if i > 0 && j > 0 && t[i - 1] == m[j - 1] && d[i][j] == d[i - 1][j - 1] {
            ops.push(Op::Match(t[i - 1]));
            i -= 1;
            j -= 1;
        } else if swappable(&t, &m, i, j) && d[i][j] == d[i - 2][j - 2] + 1 {
            ops.push(Op::Swap {
                first: m[j - 2],
                second: m[j - 1],
            });
            i -= 2;
            j -= 2;
        } else if i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + 1 {
            ops.push(Op::Sub {
                typed: t[i - 1],
                intended: m[j - 1],
            });
            i -= 1;
            j -= 1;
        } else if i > 0 && d[i][j] == d[i - 1][j] + 1 {
            ops.push(Op::Del(t[i - 1]));
            i -= 1;
        } else {
            ops.push(Op::Ins(m[j - 1]));
            j -= 1;
        }
    }
    ops.reverse();
    ops
}

fn habit_key(op: Op) -> Option<String> {
    match op {
        Op::Match(_) => None,
        Op::Sub {
            typed,
            intended: ' ',
        } => Some(format!("space:{typed}")),
        Op::Sub { typed: ' ', .. } => None,
        Op::Sub { typed, intended } => Some(format!("sub:{typed}{intended}")),
        Op::Ins(' ') => Some("nospace".into()),
        Op::Ins(c) => Some(format!("ins:{c}")),
        Op::Del(' ') => None,
        Op::Del(c) => Some(format!("del:{c}")),
        Op::Swap { first, second } => Some(format!("swap:{first}{second}")),
    }
}

/// Whether a fix of `original` into `revised` is worth learning: a
/// word-sized typed token, a revised form the keyboard knows (or two known
/// halves), a slip's distance apart, and not a word committed early.
pub fn accepts(original: &str, revised: &str, known: &dyn Fn(&str) -> bool) -> bool {
    let o = original.trim().to_lowercase();
    let r = revised.trim().to_lowercase();
    let len = o.chars().count();
    if !(MIN_TYPED_LENGTH..=MAX_WORD_LENGTH).contains(&len) || r.is_empty() {
        return false;
    }
    if o == r || o.chars().any(char::is_whitespace) {
        return false;
    }
    let parts: Vec<&str> = r.split(' ').collect();
    if parts.len() > 2
        || parts
            .iter()
            .any(|p| p.chars().count() < MIN_HALF_LENGTH || !known(p))
    {
        return false;
    }
    if r.starts_with(&o) {
        return false;
    }
    let limit = if len >= LONG_WORD_LENGTH {
        MAX_EDITS_LONG
    } else {
        MAX_EDITS
    };
    distance(&o, &r) <= limit
}

fn shrink(count: i32) -> f64 {
    if count <= 0 {
        0.0
    } else {
        MAX_SHRINK * count as f64 / (count as f64 + PIVOT)
    }
}

/// The slips one hand makes on one layout, as shrinks of the walk's costs.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct LearnedHabits {
    subs: HashMap<(u16, u16), f64>,
    inserts: HashMap<u16, f64>,
    deletes: HashMap<u16, f64>,
}

impl LearnedHabits {
    pub fn is_empty(&self) -> bool {
        self.subs.is_empty() && self.inserts.is_empty() && self.deletes.is_empty()
    }
}

fn unit(c: char) -> Option<u16> {
    let mut buf = [0u16; 2];
    let units = c.encode_utf16(&mut buf);
    (units.len() == 1).then(|| units[0])
}

impl EditHabits for LearnedHabits {
    fn deletion(&self, typed: u16) -> f64 {
        self.deletes.get(&typed).copied().unwrap_or(0.0)
    }
    fn substitution(&self, typed: u16, meant: u16) -> f64 {
        self.subs.get(&(typed, meant)).copied().unwrap_or(0.0)
    }
    fn insertion(&self, meant: u16) -> f64 {
        self.inserts.get(&meant).copied().unwrap_or(0.0)
    }
}

impl CorrectionMemory {
    pub fn new() -> CorrectionMemory {
        CorrectionMemory::default()
    }

    pub fn is_dirty(&self) -> bool {
        self.dirty
    }

    /// The user turned `original` into `revised` by hand. Callers gate
    /// with [`accepts`] first.
    pub fn teach(&mut self, original: &str, revised: &str, layout: &str, kind: Kind) {
        let typed = original.trim().to_lowercase();
        let fixed = revised.trim().to_lowercase();
        if typed.is_empty() || fixed.is_empty() || typed == fixed {
            return;
        }
        let generation = self.generation;
        let list = self.pairs.entry(typed.clone()).or_default();
        match list.iter_mut().find(|s| s.fixed == fixed) {
            Some(s) => {
                s.count = (s.count + 1).min(MAX_COUNT);
                s.gen = generation;
            }
            None => list.push(Stored {
                fixed: fixed.clone(),
                count: 1,
                gen: generation,
            }),
        }
        list.sort_by_key(|s| std::cmp::Reverse(s.count));
        list.truncate(MAX_FIXES_PER_TYPED);
        if self.pairs.len() > MAX_PAIRS {
            if let Some(oldest) = self
                .pairs
                .iter()
                .min_by_key(|(_, l)| l.iter().map(|s| s.gen).max().unwrap_or(0))
                .map(|(k, _)| k.clone())
            {
                self.pairs.remove(&oldest);
            }
        }
        if kind == Kind::PairAndHabits && !layout.trim().is_empty() {
            let counts = self.habits.entry(layout.to_string()).or_default();
            for op in align(&typed, &fixed) {
                if let Some(k) = habit_key(op) {
                    let n = counts.entry(k).or_insert(0);
                    *n = (*n + 1).min(MAX_COUNT);
                }
            }
            if counts.values().sum::<i32>() >= HABIT_WINDOW {
                counts.retain(|_, n| {
                    *n /= 2;
                    *n > 0
                });
            }
        }
        self.dirty = true;
    }

    /// The user undid a taught fix: one count off.
    pub fn unteach(&mut self, typed: &str, fixed: &str) {
        let k = typed.to_lowercase();
        let f = fixed.to_lowercase();
        let Some(list) = self.pairs.get_mut(&k) else {
            return;
        };
        let Some(at) = list.iter().position(|s| s.fixed == f) else {
            return;
        };
        if list[at].count <= 1 {
            list.remove(at);
        } else {
            list[at].count -= 1;
            list[at].gen = self.generation;
        }
        if list.is_empty() {
            self.pairs.remove(&k);
        }
        self.dirty = true;
    }

    /// The fix taught for `typed`, when one clearly leads.
    pub fn fix_for(&self, typed: &str) -> Option<Taught> {
        let list = self.pairs.get(&typed.to_lowercase())?;
        let top = list.first()?;
        if let Some(runner) = list.get(1) {
            if top.count < runner.count * DOMINANCE {
                return None;
            }
        }
        Some(Taught {
            fixed: top.fixed.clone(),
            count: top.count,
        })
    }

    pub fn forget(&mut self, typed: &str) {
        if self.pairs.remove(&typed.to_lowercase()).is_some() {
            self.dirty = true;
        }
    }

    /// Every taught pair, most used first.
    pub fn pairs(&self) -> Vec<(String, Taught)> {
        let mut out: Vec<(String, Taught)> = self
            .pairs
            .iter()
            .flat_map(|(t, l)| {
                l.iter().map(move |s| {
                    (
                        t.clone(),
                        Taught {
                            fixed: s.fixed.clone(),
                            count: s.count,
                        },
                    )
                })
            })
            .collect();
        out.sort_by(|a, b| b.1.count.cmp(&a.1.count).then_with(|| a.0.cmp(&b.0)));
        out
    }

    /// The shrinks for `layout`, empty until it has taught enough slips.
    pub fn habits_for(&self, layout: &str) -> LearnedHabits {
        let mut out = LearnedHabits::default();
        let Some(counts) = self.habits.get(layout) else {
            return out;
        };
        if counts.values().sum::<i32>() < WARMUP {
            return out;
        }
        for (k, &n) in counts {
            let s = shrink(n);
            let Some((kind, arg)) = k.split_once(':') else {
                continue;
            };
            let cs: Vec<char> = arg.chars().collect();
            match (kind, cs.as_slice()) {
                ("sub", [a, b]) => {
                    if let (Some(a), Some(b)) = (unit(*a), unit(*b)) {
                        out.subs.insert((a, b), s);
                    }
                }
                ("ins", [c]) => {
                    if let Some(c) = unit(*c) {
                        out.inserts.insert(c, s);
                    }
                }
                ("del", [c]) => {
                    if let Some(c) = unit(*c) {
                        out.deletes.insert(c, s);
                    }
                }
                _ => {}
            }
        }
        out
    }

    pub fn clear(&mut self) {
        self.pairs.clear();
        self.habits.clear();
        self.dirty = true;
    }

    /// The file's text, ageing pairs untouched for a long time.
    pub fn to_json(&mut self) -> String {
        self.generation += 1;
        let generation = self.generation;
        self.pairs.retain(|_, list| {
            list.retain_mut(|s| {
                if generation - s.gen <= EXPIRE_GENERATIONS {
                    return true;
                }
                if s.count > 1 {
                    s.count -= 1;
                    s.gen = generation;
                    return true;
                }
                false
            });
            !list.is_empty()
        });
        self.dirty = false;
        let mut pairs = Map::new();
        let mut keys: Vec<&String> = self.pairs.keys().collect();
        keys.sort();
        for k in keys {
            let list: Vec<Value> = self.pairs[k]
                .iter()
                .map(|s| json!({"fixed": s.fixed, "count": s.count, "gen": s.gen}))
                .collect();
            pairs.insert(k.clone(), Value::Array(list));
        }
        let mut habits = Map::new();
        for (layout, counts) in &self.habits {
            let m: Map<String, Value> = counts.iter().map(|(k, n)| (k.clone(), json!(n))).collect();
            habits.insert(layout.clone(), Value::Object(m));
        }
        json!({"pairs": pairs, "habits": habits, "generation": self.generation}).to_string()
    }

    pub fn from_json(text: &str) -> CorrectionMemory {
        let mut out = CorrectionMemory::new();
        let Ok(v) = serde_json::from_str::<Value>(text) else {
            return out;
        };
        if let Some(pairs) = v.get("pairs").and_then(Value::as_object) {
            for (typed, list) in pairs {
                let mut clean: Vec<Stored> = list
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter_map(|s| {
                        let fixed = s.get("fixed")?.as_str()?.to_string();
                        let count = s.get("count")?.as_i64()? as i32;
                        (count > 0 && !fixed.is_empty()).then(|| Stored {
                            fixed,
                            count,
                            gen: s.get("gen").and_then(Value::as_i64).unwrap_or(0),
                        })
                    })
                    .collect();
                if !clean.is_empty() {
                    clean.sort_by_key(|s| std::cmp::Reverse(s.count));
                    out.pairs.insert(typed.clone(), clean);
                }
            }
        }
        if let Some(habits) = v.get("habits").and_then(Value::as_object) {
            for (layout, counts) in habits {
                let clean: HashMap<String, i32> = counts
                    .as_object()
                    .into_iter()
                    .flatten()
                    .filter_map(|(k, n)| Some((k.clone(), n.as_i64()? as i32)))
                    .filter(|(_, n)| *n > 0)
                    .collect();
                if !clean.is_empty() {
                    out.habits.insert(layout.clone(), clean);
                }
            }
        }
        out.generation = v.get("generation").and_then(Value::as_i64).unwrap_or(0);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn alignment_names_the_slip() {
        assert_eq!(
            vec![
                Op::Match('t'),
                Op::Swap {
                    first: 'h',
                    second: 'e'
                }
            ],
            align("teh", "the")
        );
        assert_eq!(1, distance("t3st", "test"));
        assert!(align("amibtomake", "ami tomake").contains(&Op::Sub {
            typed: 'b',
            intended: ' '
        }));
    }

    #[test]
    fn what_is_worth_learning() {
        let known = |w: &str| ["the", "from", "ami", "tomake"].contains(&w);
        assert!(accepts("teh", "the", &known));
        assert!(!accepts("th", "the", &known), "too short");
        assert!(!accepts("hel", "hello", &known), "unknown and a completion");
        assert!(!accepts("teh", "xyz", &known), "an unknown fix");
        assert!(accepts("amibtomake", "ami tomake", &known));
    }

    #[test]
    fn two_fixes_make_the_fix_and_habits_follow() {
        let mut m = CorrectionMemory::new();
        m.teach("teh", "the", "qwerty", Kind::PairAndHabits);
        assert_eq!(1, m.fix_for("TEH").unwrap().count);
        m.teach("teh", "the", "qwerty", Kind::PairAndHabits);
        m.teach("teh", "ten", "qwerty", Kind::PairAndHabits);
        assert_eq!("the", m.fix_for("teh").unwrap().fixed);
        m.teach("teh", "ten", "qwerty", Kind::PairAndHabits);
        assert_eq!(
            None,
            m.fix_for("teh"),
            "two fixes of one spelling, neither leads"
        );
        m.unteach("teh", "ten");
        m.unteach("teh", "ten");
        assert_eq!("the", m.fix_for("teh").unwrap().fixed);
        assert!(m.habits_for("qwerty").is_empty(), "not warmed up");
        for _ in 0..8 {
            m.teach("t3st", "test", "qwerty", Kind::PairAndHabits);
        }
        let h = m.habits_for("qwerty");
        assert!(h.substitution(u16::from(b'3'), u16::from(b'e')) > 0.5);
        assert_eq!(0.0, h.substitution(u16::from(b'e'), u16::from(b'3')));
        let text = m.to_json();
        let back = CorrectionMemory::from_json(&text);
        assert_eq!(back.pairs(), m.pairs());
        assert!(!back.habits_for("qwerty").is_empty());
    }
}

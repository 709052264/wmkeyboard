//! What the user picked per reading, what they wrote after what, and what
//! the candidate menu told the keyboard: the Kotlin `CjkUserHistory` and
//! `CjkLearning.rank`, without the file. [`UserHistory::to_json`] and
//! [`UserHistory::from_json`] read and write the phone's own file format, so
//! a history synced between the two opens on either.
//!
//! Namespaces are reading spaces, not languages: a pinyin `ni` and a Cangjie
//! `ni` have nothing to do with each other.

use super::lattice::WordAdjust;
use serde_json::{json, Map, Value};
use std::collections::{HashMap, HashSet};

/// Learning events between two aging passes: a week or two of typing.
pub const AGE_EVERY: i64 = 3000;

/// How far down the candidate menu can push a word, in steps.
const MAX_STEPS: i32 = 5;

/// Marks a pair's previous word among the touched readings.
const PAIR_MARK: &str = "\u{1}";

type Counts = HashMap<String, i32>;
type Nested = HashMap<String, HashMap<String, Counts>>;

/// What the held candidate's menu asks for.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CandidateAdjustment {
    Promote,
    Demote,
    Block,
    FirstCharacter,
    LastCharacter,
}

#[derive(Clone, Debug, Default)]
pub struct UserHistory {
    picks: Nested,
    blocked: HashMap<String, HashSet<String>>,
    bias: HashMap<String, Counts>,
    pairs: Nested,
    tick: i64,
    touched: HashMap<String, HashSet<String>>,
    /// Per namespace, the sorted readings of everything learned with two
    /// characters or more.
    phrase_readings: HashMap<String, Vec<String>>,
    adjustments: HashMap<String, WordAdjust>,
    /// Whether anything changed since the caller last saved.
    pub dirty: bool,
}

fn count_chars(s: &str) -> usize {
    s.chars().count()
}

impl UserHistory {
    pub fn new() -> UserHistory {
        UserHistory::default()
    }

    /// Records that `word` was chosen for `reading`.
    pub fn learn(&mut self, namespace: &str, reading: &str, word: &str) {
        if reading.is_empty() || word.is_empty() {
            return;
        }
        let counts = self
            .picks
            .entry(namespace.to_string())
            .or_default()
            .entry(reading.to_string())
            .or_default();
        *counts.entry(word.to_string()).or_insert(0) += 1;
        self.touched
            .entry(namespace.to_string())
            .or_default()
            .insert(reading.to_string());
        if count_chars(word) >= 2 {
            self.rebuild_phrase_readings();
        }
        self.ticked();
        self.dirty = true;
    }

    /// Records that `word` was written straight after `previous`.
    pub fn learn_pair(&mut self, namespace: &str, previous: &str, word: &str) {
        if previous.is_empty() || word.is_empty() {
            return;
        }
        let counts = self
            .pairs
            .entry(namespace.to_string())
            .or_default()
            .entry(previous.to_string())
            .or_default();
        *counts.entry(word.to_string()).or_insert(0) += 1;
        self.touched
            .entry(namespace.to_string())
            .or_default()
            .insert(format!("{PAIR_MARK}{previous}"));
        self.ticked();
        self.dirty = true;
    }

    /// What the user has written after `previous`, most often first.
    pub fn followers_of(&self, namespace: &str, previous: &str) -> Vec<(String, i32)> {
        let mut out: Vec<(String, i32)> = self
            .pairs
            .get(namespace)
            .and_then(|p| p.get(previous))
            .map(|m| m.iter().map(|(k, v)| (k.clone(), *v)).collect())
            .unwrap_or_default();
        out.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        out
    }

    /// Previous word → word → times, for the decoder.
    pub fn pairs_for(&self, namespace: &str) -> Option<&HashMap<String, Counts>> {
        self.pairs.get(namespace).filter(|p| !p.is_empty())
    }

    /// Whether a phrase of two characters or more learned in `namespace` is
    /// read starting with `prefix`.
    pub fn has_phrase_prefix(&self, namespace: &str, prefix: &str) -> bool {
        let Some(readings) = self.phrase_readings.get(namespace) else {
            return false;
        };
        match readings.binary_search_by(|r| r.as_str().cmp(prefix)) {
            Ok(_) => true,
            Err(i) => i < readings.len() && readings[i].starts_with(prefix),
        }
    }

    fn ticked(&mut self) {
        self.tick += 1;
        if self.tick % AGE_EVERY == 0 {
            self.age();
        }
    }

    /// Halves every count untouched since the last pass, dropping the ones
    /// that reach zero.
    fn age(&mut self) {
        let touched = std::mem::take(&mut self.touched);
        let none = HashSet::new();
        for (ns, by_reading) in self.picks.iter_mut() {
            let spared = touched.get(ns).unwrap_or(&none);
            halve(by_reading, |k| spared.contains(k));
        }
        for (ns, by_previous) in self.pairs.iter_mut() {
            let spared = touched.get(ns).unwrap_or(&none);
            halve(by_previous, |k| spared.contains(&format!("{PAIR_MARK}{k}")));
        }
        self.rebuild_phrase_readings();
    }

    fn rebuild_phrase_readings(&mut self) {
        self.phrase_readings = self
            .picks
            .iter()
            .map(|(ns, by_reading)| {
                let mut readings: Vec<String> = by_reading
                    .iter()
                    .filter(|(_, words)| words.keys().any(|w| count_chars(w) >= 2))
                    .map(|(r, _)| r.clone())
                    .collect();
                readings.sort();
                (ns.clone(), readings)
            })
            .collect();
    }

    /// How many times `word` has been chosen for `reading`.
    pub fn count_for(&self, namespace: &str, reading: &str, word: &str) -> i32 {
        self.picks
            .get(namespace)
            .and_then(|r| r.get(reading))
            .and_then(|w| w.get(word))
            .copied()
            .unwrap_or(0)
    }

    /// Every word chosen for `reading` and how often.
    pub fn picks_for(&self, namespace: &str, reading: &str) -> Option<&Counts> {
        self.picks
            .get(namespace)
            .and_then(|r| r.get(reading))
            .filter(|w| !w.is_empty())
    }

    /// What the candidate menu has said about words in `namespace`.
    pub fn adjust_for(&self, namespace: &str) -> Option<&WordAdjust> {
        self.adjustments.get(namespace)
    }

    /// Never offer these words in `namespace` again; their picks go too.
    pub fn block(&mut self, namespace: &str, words: &[&str]) {
        let words: HashSet<String> = words
            .iter()
            .filter(|w| !w.is_empty())
            .map(|w| w.to_string())
            .collect();
        if words.is_empty() {
            return;
        }
        self.blocked
            .entry(namespace.to_string())
            .or_default()
            .extend(words.iter().cloned());
        self.forget_picks(namespace, &words);
        self.changed();
    }

    /// One step lower, bounded at five; the words' picks go too.
    pub fn demote(&mut self, namespace: &str, words: &[&str]) {
        let words: HashSet<String> = words
            .iter()
            .filter(|w| !w.is_empty())
            .map(|w| w.to_string())
            .collect();
        if words.is_empty() {
            return;
        }
        let steps = self.bias.entry(namespace.to_string()).or_default();
        for w in &words {
            let s = steps.entry(w.clone()).or_insert(0);
            *s = (*s - 1).max(-MAX_STEPS);
        }
        self.forget_picks(namespace, &words);
        self.changed();
    }

    /// One step higher: any demotion taken back, and counted as picked once
    /// more under the first non-empty word (the one shown).
    pub fn promote(&mut self, namespace: &str, reading: &str, words: &[&str]) {
        let set: Vec<&str> = words.iter().copied().filter(|w| !w.is_empty()).collect();
        if set.is_empty() || reading.is_empty() {
            return;
        }
        if let Some(steps) = self.bias.get_mut(namespace) {
            for w in &set {
                steps.remove(*w);
            }
        }
        let counts = self
            .picks
            .entry(namespace.to_string())
            .or_default()
            .entry(reading.to_string())
            .or_default();
        *counts.entry(set[0].to_string()).or_insert(0) += 1;
        self.changed();
    }

    /// The candidate menu's answer for one candidate: `shown` as drawn,
    /// `source` as the decoder found it.
    pub fn adjust(
        &mut self,
        namespace: &str,
        reading: &str,
        shown: &str,
        source: &str,
        adjustment: CandidateAdjustment,
    ) {
        match adjustment {
            CandidateAdjustment::Promote => self.promote(namespace, reading, &[shown, source]),
            CandidateAdjustment::Demote => self.demote(namespace, &[shown, source]),
            CandidateAdjustment::Block => self.block(namespace, &[shown, source]),
            CandidateAdjustment::FirstCharacter | CandidateAdjustment::LastCharacter => {}
        }
    }

    fn forget_picks(&mut self, namespace: &str, words: &HashSet<String>) {
        if let Some(by_reading) = self.picks.get_mut(namespace) {
            for counts in by_reading.values_mut() {
                counts.retain(|w, _| !words.contains(w));
            }
            by_reading.retain(|_, c| !c.is_empty());
        }
        if let Some(by_previous) = self.pairs.get_mut(namespace) {
            by_previous.retain(|p, _| !words.contains(p));
            for counts in by_previous.values_mut() {
                counts.retain(|w, _| !words.contains(w));
            }
            by_previous.retain(|_, c| !c.is_empty());
        }
        self.rebuild_phrase_readings();
    }

    fn changed(&mut self) {
        self.dirty = true;
        self.rebuild_adjustments();
    }

    fn rebuild_adjustments(&mut self) {
        let mut out = HashMap::new();
        let names: HashSet<&String> = self.blocked.keys().chain(self.bias.keys()).collect();
        for ns in names {
            let a = WordAdjust {
                blocked: self.blocked.get(ns).cloned().unwrap_or_default(),
                bias: self
                    .bias
                    .get(ns)
                    .map(|b| {
                        b.iter()
                            .filter(|(_, v)| **v != 0)
                            .map(|(k, v)| (k.clone(), *v))
                            .collect()
                    })
                    .unwrap_or_default(),
            };
            if !a.blocked.is_empty() || !a.bias.is_empty() {
                out.insert(ns.clone(), a);
            }
        }
        self.adjustments = out;
    }

    /// True when nothing has ever been learned for `namespace`.
    pub fn is_empty_for(&self, namespace: &str) -> bool {
        self.picks.get(namespace).is_none_or(|p| p.is_empty())
    }

    pub fn clear(&mut self) {
        if self.picks.is_empty()
            && self.blocked.is_empty()
            && self.bias.is_empty()
            && self.pairs.is_empty()
        {
            return;
        }
        let dirty = true;
        *self = UserHistory::default();
        self.dirty = dirty;
    }

    pub fn clear_namespace(&mut self, namespace: &str) {
        let had = self.picks.remove(namespace).is_some()
            | self.blocked.remove(namespace).is_some()
            | self.bias.remove(namespace).is_some()
            | self.pairs.remove(namespace).is_some();
        if !had {
            return;
        }
        self.rebuild_phrase_readings();
        self.changed();
    }

    /// The phone's file: `{version, picks, blocked, bias, pairs, tick}`.
    pub fn to_json(&self) -> String {
        let nested = |n: &Nested| -> Value {
            let mut out = Map::new();
            for (ns, by) in n {
                let mut inner = Map::new();
                for (k, counts) in by {
                    inner.insert(k.clone(), json!(counts));
                }
                out.insert(ns.clone(), Value::Object(inner));
            }
            Value::Object(out)
        };
        let blocked: Map<String, Value> = self
            .blocked
            .iter()
            .map(|(ns, words)| {
                let mut w: Vec<&String> = words.iter().collect();
                w.sort();
                (ns.clone(), json!(w))
            })
            .collect();
        json!({
            "version": 1,
            "picks": nested(&self.picks),
            "blocked": blocked,
            "bias": json!(self.bias),
            "pairs": nested(&self.pairs),
            "tick": self.tick,
        })
        .to_string()
    }

    /// Reads [`UserHistory::to_json`]'s format; unknown keys are ignored and
    /// anything unreadable is an empty history.
    pub fn from_json(text: &str) -> UserHistory {
        let mut h = UserHistory::default();
        let Ok(Value::Object(root)) = serde_json::from_str::<Value>(text) else {
            return h;
        };
        let counts = |v: &Value| -> Counts {
            v.as_object()
                .map(|m| {
                    m.iter()
                        .filter_map(|(k, n)| Some((k.clone(), n.as_i64()? as i32)))
                        .collect()
                })
                .unwrap_or_default()
        };
        let nested = |v: Option<&Value>| -> Nested {
            let mut out = Nested::new();
            for (ns, by) in v.and_then(Value::as_object).into_iter().flatten() {
                let inner = by
                    .as_object()
                    .map(|m| m.iter().map(|(k, c)| (k.clone(), counts(c))).collect())
                    .unwrap_or_default();
                out.insert(ns.clone(), inner);
            }
            out
        };
        h.picks = nested(root.get("picks"));
        h.pairs = nested(root.get("pairs"));
        for (ns, words) in root
            .get("blocked")
            .and_then(Value::as_object)
            .into_iter()
            .flatten()
        {
            let set = words
                .as_array()
                .map(|a| {
                    a.iter()
                        .filter_map(|w| w.as_str().map(str::to_string))
                        .collect()
                })
                .unwrap_or_default();
            h.blocked.insert(ns.clone(), set);
        }
        for (ns, steps) in root
            .get("bias")
            .and_then(Value::as_object)
            .into_iter()
            .flatten()
        {
            h.bias.insert(ns.clone(), counts(steps));
        }
        h.tick = root.get("tick").and_then(Value::as_i64).unwrap_or(0);
        h.rebuild_adjustments();
        h.rebuild_phrase_readings();
        h
    }
}

fn halve(table: &mut HashMap<String, Counts>, spared: impl Fn(&str) -> bool) {
    for (key, counts) in table.iter_mut() {
        if spared(key) {
            continue;
        }
        counts.retain(|_, v| {
            *v /= 2;
            *v > 0
        });
    }
    table.retain(|_, c| !c.is_empty());
}

/// `items` with previously chosen candidates first, most chosen first, and
/// demoted ones after what they tie with; blocked ones dropped. Stable, and
/// within each `group` (the groups keep their order), so the decoder's order
/// survives underneath. `text`, `reading`, `source` read an item.
pub fn rank<T>(
    history: Option<&UserHistory>,
    namespace: &str,
    items: Vec<T>,
    text: impl Fn(&T) -> &str,
    reading: impl Fn(&T) -> &str,
    source: impl Fn(&T) -> &str,
    group: Option<&dyn Fn(&T) -> usize>,
) -> Vec<T> {
    let Some(history) = history else { return items };
    if items.is_empty() {
        return items;
    }
    let adjust = history.adjust_for(namespace);
    let kept: Vec<T> = match adjust {
        Some(a) if !a.blocked.is_empty() => items
            .into_iter()
            .filter(|it| !a.blocked.contains(text(it)) && !a.blocked.contains(source(it)))
            .collect(),
        _ => items,
    };
    if kept.len() < 2 {
        return kept;
    }
    let learned = !history.is_empty_for(namespace);
    let empty = HashMap::new();
    let bias = adjust.map(|a| &a.bias).unwrap_or(&empty);
    if !learned && bias.is_empty() {
        return kept;
    }
    let mut moved = false;
    let mut counts = vec![0; kept.len()];
    let mut lowered = vec![false; kept.len()];
    for (i, item) in kept.iter().enumerate() {
        if learned {
            counts[i] = history.count_for(namespace, reading(item), text(item));
        }
        let b = bias
            .get(source(item))
            .or_else(|| bias.get(text(item)))
            .copied()
            .unwrap_or(0);
        lowered[i] = b < 0;
        if counts[i] > 0 || lowered[i] {
            moved = true;
        }
    }
    if !moved {
        return kept;
    }
    let mut order: HashMap<usize, usize> = HashMap::new();
    let group_of: Vec<usize> = kept
        .iter()
        .map(|it| {
            let key = group.map(|g| g(it)).unwrap_or(0);
            let next = order.len();
            *order.entry(key).or_insert(next)
        })
        .collect();
    let mut idx: Vec<usize> = (0..kept.len()).collect();
    idx.sort_by_key(|&i| (group_of[i], -counts[i], lowered[i]));
    let mut slots: Vec<Option<T>> = kept.into_iter().map(Some).collect();
    idx.into_iter().map(|i| slots[i].take().unwrap()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn learns_ranks_blocks_and_round_trips() {
        let mut h = UserHistory::new();
        h.learn("pinyin", "hao", "号");
        h.learn("pinyin", "wodemama", "我的妈妈");
        h.learn_pair("pinyin", "我", "爱");
        assert!(h.has_phrase_prefix("pinyin", "wode"));
        assert!(!h.has_phrase_prefix("pinyin", "hao"));
        let items = vec!["好", "号", "豪"];
        let ranked = rank(
            Some(&h),
            "pinyin",
            items.clone(),
            |s| s,
            |_| "hao",
            |s| s,
            None,
        );
        assert_eq!(vec!["号", "好", "豪"], ranked);
        h.block("pinyin", &["号"]);
        let ranked = rank(Some(&h), "pinyin", items, |s| s, |_| "hao", |s| s, None);
        assert_eq!(vec!["好", "豪"], ranked);
        h.demote("pinyin", &["好"]);
        let back = UserHistory::from_json(&h.to_json());
        assert_eq!(-1, back.adjust_for("pinyin").unwrap().bias["好"]);
        assert_eq!(
            vec![("爱".to_string(), 1)],
            back.followers_of("pinyin", "我")
        );
        assert!(back.has_phrase_prefix("pinyin", "wo"));
    }
}

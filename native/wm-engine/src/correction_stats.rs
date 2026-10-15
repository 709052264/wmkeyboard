//! What autocorrect has learned about its own mistakes: the Kotlin
//! `CorrectionStats` (core/prediction). Per-pair revert penalties, and a
//! fired/reverted ratio that adapts the confidence gate.
//!
//! The file format is the phone's `learning/correction_stats.json`, so the
//! two can share one: `{"pairs":{"typed\u0000fixed":{"count","gen","kept"}},
//! "fired","reverted","generation"}`. Reading and writing the file is the
//! caller's business; [`CorrectionStats::to_json`] and
//! [`CorrectionStats::from_json`] are the text.

use serde_json::{json, Map, Value};
use std::collections::HashMap;

/// How hard the keyboard holds on to a correction the user undid.
///
/// `Off` forgets pair by pair; the global revert rate still counts. `Light`
/// handicaps an undone pair but never retires it. `Normal` holds a deliberate
/// undo for the run of typing that earned it, retires a pair undone twice,
/// and offers a retired pair again eventually. `Strict` takes the first undo
/// as final.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum UndoMemory {
    Off,
    Light,
    #[default]
    Normal,
    Strict,
}

impl UndoMemory {
    /// The phone's names: "OFF", "LIGHT", "NORMAL", "STRICT", any case.
    pub fn parse(name: &str) -> UndoMemory {
        match name.to_ascii_uppercase().as_str() {
            "OFF" => UndoMemory::Off,
            "LIGHT" => UndoMemory::Light,
            "STRICT" => UndoMemory::Strict,
            _ => UndoMemory::Normal,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Penalty {
    None,
    /// Rejected once, indirectly: still ranks, fights with a handicap.
    Penalized,
    /// Retired long ago: may be offered again, never applied.
    Probation,
    /// Undone on purpose: never a target, never a runner-up.
    Blocked,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct PairStat {
    count: i32,
    gen: i64,
    kept: i32,
}

/// Second persisted revert of the same pair blocks it outright.
const BLOCK_AT: i32 = 2;
/// Accepted uses of a rejected pair that buy back one rejection.
const KEEPS_TO_FORGIVE: i32 = 3;
const MAX_PAIRS: usize = 500;
const EXPIRE_GENERATIONS: i64 = 180;
/// Verdicts an in-process block survives.
const SESSION_BLOCK_SPAN: u64 = 20;
/// Saves a retired pair sits quiet before it may be offered again.
const REOFFER_GENERATIONS: i64 = 40;
/// Halving window for the fired/reverted counters.
const WINDOW: i32 = 200;
const MIN_SAMPLE: i32 = 20;
const TARGET_RATE: f64 = 0.03;
const RATE_GAIN: f64 = 20.0;
const MIN_MULTIPLIER: f64 = 0.85;
const MAX_MULTIPLIER: f64 = 2.5;

fn key(typed: &str, fixed: &str) -> String {
    format!("{}\u{0}{}", typed.to_lowercase(), fixed.to_lowercase())
}

#[derive(Default)]
pub struct CorrectionStats {
    pairs: HashMap<String, PairStat>,
    fired: i32,
    reverted: i32,
    generation: i64,
    /// Pairs reverted in this run, against the tick each was reverted at.
    session_rejected: HashMap<String, u64>,
    /// Verdicts delivered since the run started; only ages the blocks out.
    tick: u64,
    dirty: bool,
    pub memory: UndoMemory,
    /// A fixed multiplier instead of the revert rate's, for tests and for
    /// a caller that keeps its own.
    pub confidence_multiplier: Option<f64>,
}

impl CorrectionStats {
    pub fn new() -> CorrectionStats {
        CorrectionStats::default()
    }

    /// Whether something changed since the last [`to_json`](Self::to_json).
    pub fn is_dirty(&self) -> bool {
        self.dirty
    }

    fn bump_fired(&mut self) {
        self.fired += 1;
        self.tick += 1;
        if self.fired >= WINDOW {
            self.fired /= 2;
            self.reverted /= 2;
        }
        self.dirty = true;
    }

    /// The user undid the correction of `typed` into `fixed`. Only a
    /// deliberate undo (a backspace on the fix, the undo chip) earns the
    /// in-process block.
    pub fn record_revert(&mut self, typed: &str, fixed: &str, deliberate: bool) {
        self.reverted += 1;
        self.bump_fired();
        if self.memory == UndoMemory::Off {
            return;
        }
        let k = key(typed, fixed);
        if deliberate || self.memory == UndoMemory::Strict {
            self.session_rejected.insert(k.clone(), self.tick);
        }
        let bumped = self.pairs.get(&k).map_or(0, |p| p.count) + 1;
        let count = match self.memory {
            UndoMemory::Light => 1,
            UndoMemory::Strict => bumped.max(BLOCK_AT),
            _ => bumped,
        };
        self.pairs.insert(
            k,
            PairStat {
                count,
                gen: self.generation,
                kept: 0,
            },
        );
        if self.pairs.len() > MAX_PAIRS {
            if let Some(oldest) = self
                .pairs
                .iter()
                .min_by_key(|(_, p)| p.gen)
                .map(|(k, _)| k.clone())
            {
                self.pairs.remove(&oldest);
            }
        }
        self.dirty = true;
    }

    fn session_blocked(&mut self, k: &str) -> bool {
        let Some(&stamp) = self.session_rejected.get(k) else {
            return false;
        };
        if self.tick - stamp < SESSION_BLOCK_SPAN {
            return true;
        }
        self.session_rejected.remove(k);
        false
    }

    fn session_blocked_now(&self, k: &str) -> bool {
        self.session_rejected
            .get(k)
            .is_some_and(|&stamp| self.tick - stamp < SESSION_BLOCK_SPAN)
    }

    /// The user left the field: in-process blocks end with it.
    pub fn end_field_session(&mut self) {
        self.session_rejected.clear();
    }

    /// The user let the correction of `typed` into `fixed` stand.
    pub fn record_kept(&mut self, typed: &str, fixed: &str) {
        self.bump_fired();
        if self.memory == UndoMemory::Off {
            return;
        }
        let k = key(typed, fixed);
        if self.session_blocked(&k) {
            return;
        }
        let Some(existing) = self.pairs.get(&k).copied() else {
            return;
        };
        let kept = existing.kept + 1;
        if kept < KEEPS_TO_FORGIVE {
            self.pairs.insert(
                k,
                PairStat {
                    kept,
                    gen: self.generation,
                    ..existing
                },
            );
        } else if existing.count <= 1 {
            self.pairs.remove(&k);
        } else {
            self.pairs.insert(
                k,
                PairStat {
                    count: existing.count - 1,
                    gen: self.generation,
                    kept: 0,
                },
            );
        }
    }

    /// How strongly this exact correction is disfavored.
    pub fn penalty(&self, typed: &str, fixed: &str) -> Penalty {
        if self.memory == UndoMemory::Off {
            return Penalty::None;
        }
        let k = key(typed, fixed);
        if self.session_blocked_now(&k) {
            return Penalty::Blocked;
        }
        let Some(stat) = self.pairs.get(&k) else {
            return Penalty::None;
        };
        if stat.count < BLOCK_AT {
            Penalty::Penalized
        } else if self.memory != UndoMemory::Strict
            && self.generation - stat.gen > REOFFER_GENERATIONS
        {
            Penalty::Probation
        } else {
            Penalty::Blocked
        }
    }

    /// Sets a pair's standing directly: Blocked as a deliberate undo,
    /// Penalized as one indirect one, None to forget it.
    pub fn set(&mut self, typed: &str, fixed: &str, penalty: Penalty) {
        let k = key(typed, fixed);
        match penalty {
            Penalty::None => {
                self.pairs.remove(&k);
                self.session_rejected.remove(&k);
            }
            Penalty::Penalized => {
                self.pairs.insert(
                    k,
                    PairStat {
                        count: 1,
                        gen: self.generation,
                        kept: 0,
                    },
                );
            }
            Penalty::Probation => {
                self.pairs.insert(
                    k,
                    PairStat {
                        count: BLOCK_AT,
                        gen: self.generation - REOFFER_GENERATIONS - 1,
                        kept: 0,
                    },
                );
            }
            Penalty::Blocked => {
                self.session_rejected.insert(k.clone(), self.tick);
                self.pairs.insert(
                    k,
                    PairStat {
                        count: BLOCK_AT,
                        gen: self.generation,
                        kept: 0,
                    },
                );
            }
        }
        self.dirty = true;
    }

    /// The multiplier on the confidence setting from the recent revert rate;
    /// 1 until there is a real sample.
    pub fn multiplier(&self) -> f64 {
        if let Some(m) = self.confidence_multiplier {
            return m;
        }
        if self.fired < MIN_SAMPLE {
            return 1.0;
        }
        let rate = self.reverted as f64 / self.fired as f64;
        (1.0 + RATE_GAIN * (rate - TARGET_RATE)).clamp(MIN_MULTIPLIER, MAX_MULTIPLIER)
    }

    /// How many corrections reached a verdict, and how many were undone,
    /// in the current window.
    pub fn counts(&self) -> (i32, i32) {
        (self.fired, self.reverted)
    }

    pub fn clear(&mut self) {
        self.pairs.clear();
        self.session_rejected.clear();
        self.fired = 0;
        self.reverted = 0;
        self.dirty = true;
    }

    /// The file's text, ageing stale pairs as the phone does on each save.
    pub fn to_json(&mut self) -> String {
        self.generation += 1;
        let generation = self.generation;
        self.pairs.retain(|_, p| {
            if generation - p.gen > EXPIRE_GENERATIONS {
                if p.count <= 1 {
                    return false;
                }
                p.count -= 1;
                p.gen = generation;
            }
            true
        });
        self.dirty = false;
        let mut pairs = Map::new();
        let mut keys: Vec<&String> = self.pairs.keys().collect();
        keys.sort();
        for k in keys {
            let p = self.pairs[k];
            pairs.insert(
                k.clone(),
                json!({"count": p.count, "gen": p.gen, "kept": p.kept}),
            );
        }
        json!({
            "pairs": pairs,
            "fired": self.fired,
            "reverted": self.reverted,
            "generation": self.generation,
        })
        .to_string()
    }

    /// Reads the phone's file; anything unreadable is an empty store.
    pub fn from_json(text: &str) -> CorrectionStats {
        let mut out = CorrectionStats::new();
        let Ok(v) = serde_json::from_str::<Value>(text) else {
            return out;
        };
        let int = |v: &Value, k: &str| v.get(k).and_then(Value::as_i64).unwrap_or(0);
        if let Some(pairs) = v.get("pairs").and_then(Value::as_object) {
            for (k, p) in pairs {
                let count = int(p, "count") as i32;
                if count > 0 && k.contains('\u{0}') {
                    out.pairs.insert(
                        k.clone(),
                        PairStat {
                            count,
                            gen: int(p, "gen"),
                            kept: int(p, "kept") as i32,
                        },
                    );
                }
            }
        }
        out.fired = int(&v, "fired") as i32;
        out.reverted = int(&v, "reverted") as i32;
        out.generation = int(&v, "generation");
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_deliberate_undo_blocks_for_the_run_and_two_retire_the_pair() {
        let mut s = CorrectionStats::new();
        s.record_revert("teh", "ten", true);
        assert_eq!(Penalty::Blocked, s.penalty("teh", "ten"));
        for _ in 0..SESSION_BLOCK_SPAN {
            s.record_kept("x", "y");
        }
        assert_eq!(Penalty::Penalized, s.penalty("Teh", "TEN"));
        s.record_revert("teh", "ten", false);
        assert_eq!(Penalty::Blocked, s.penalty("teh", "ten"));
    }

    #[test]
    fn keeping_a_pair_three_times_forgives_one_undo() {
        let mut s = CorrectionStats::new();
        s.record_revert("cst", "cut", false);
        assert_eq!(Penalty::Penalized, s.penalty("cst", "cut"));
        for _ in 0..3 {
            s.record_kept("cst", "cut");
        }
        assert_eq!(Penalty::None, s.penalty("cst", "cut"));
    }

    #[test]
    fn the_levels() {
        let mut light = CorrectionStats {
            memory: UndoMemory::Light,
            ..CorrectionStats::new()
        };
        light.record_revert("a1", "b1", false);
        light.record_revert("a1", "b1", false);
        assert_eq!(Penalty::Penalized, light.penalty("a1", "b1"));
        let mut strict = CorrectionStats {
            memory: UndoMemory::Strict,
            ..CorrectionStats::new()
        };
        strict.record_revert("a1", "b1", false);
        for _ in 0..30 {
            strict.record_kept("x", "y");
        }
        assert_eq!(Penalty::Blocked, strict.penalty("a1", "b1"));
        let mut off = CorrectionStats {
            memory: UndoMemory::Off,
            ..CorrectionStats::new()
        };
        off.record_revert("a1", "b1", true);
        assert_eq!(Penalty::None, off.penalty("a1", "b1"));
        assert_eq!((1, 1), off.counts());
    }

    #[test]
    fn many_undos_tighten_the_gate() {
        let mut s = CorrectionStats::new();
        for i in 0..20 {
            if i % 4 == 0 {
                s.record_revert(&format!("w{i}"), "x", false);
            } else {
                s.record_kept("a", "b");
            }
        }
        assert!(s.multiplier() > 1.5, "{}", s.multiplier());
        let mut clean = CorrectionStats::new();
        for _ in 0..40 {
            clean.record_kept("a", "b");
        }
        assert!(clean.multiplier() < 1.0);
    }

    #[test]
    fn the_file_round_trips_in_the_phones_format() {
        let mut s = CorrectionStats::new();
        s.record_revert("teh", "ten", false);
        let text = s.to_json();
        assert!(text.contains("\"teh\\u0000ten\""), "{text}");
        let back = CorrectionStats::from_json(&text);
        assert_eq!(Penalty::Penalized, back.penalty("teh", "ten"));
        assert_eq!((1, 1), back.counts());
        assert_eq!(0, CorrectionStats::from_json("nonsense").counts().0);
    }
}

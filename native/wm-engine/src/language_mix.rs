//! Which language a field is being written in, and which one an app's fields
//! usually are: the Kotlin `FieldLanguageMix` and `AppLanguageMix`
//! (core/prediction). The field mix is fast and per field, from the words
//! already there and every word committed since; the app mix is a decayed
//! habit per app that seeds a fresh field before a word is typed.
//!
//! The app mix's file is the phone's `learning/app_language_mix.json`:
//! `{"apps":{"pkg":{"en":1.2}},"evidence":{"pkg":3.0}}`.

use serde_json::{json, Map, Value};
use std::collections::{HashMap, HashSet};

/// Calibrated strengths for [`crate::predict::Predictor::field_detection_shift`].
pub const FIELD_SHIFT_GENTLE: f64 = 1.4;
pub const FIELD_SHIFT_BALANCED: f64 = 2.6;
pub const FIELD_SHIFT_AGGRESSIVE: f64 = 4.0;

const FIELD_DECAY: f64 = 0.7;
const FLOOR: f64 = 0.01;
const MIN_EVIDENCE: f64 = 0.75;
const FULL_EVIDENCE: f64 = 2.0;
/// The most a prior may be worth, in typed words.
pub const MAX_PRIOR_WEIGHT: f64 = 1.5;

const APP_DECAY: f64 = 0.99;
const PRIOR_SAMPLE: f64 = 20.0;
const MIN_WORDS: f64 = 2.5;
const MAX_APPS: usize = 64;

/// Where a field usually starts: each language's share, and its weight.
#[derive(Clone, Debug, PartialEq)]
pub struct Prior {
    pub shares: HashMap<String, f64>,
    pub weight: f64,
}

/// Each language's share of a field's classified words, and how much of
/// the full shift the evidence so far justifies.
#[derive(Clone, Debug, PartialEq)]
pub struct Shares {
    shares: HashMap<String, f64>,
    pub ramp: f64,
}

impl Shares {
    pub fn share_of(&self, language: &str) -> f64 {
        self.shares.get(language).copied().unwrap_or(0.0)
    }
}

#[derive(Clone, Debug, Default)]
pub struct FieldLanguageMix {
    tally: HashMap<String, f64>,
    evidence: f64,
}

impl FieldLanguageMix {
    pub fn reset(&mut self) {
        self.tally.clear();
        self.evidence = 0.0;
    }

    /// Start from the app's habit, worth at most [`MAX_PRIOR_WEIGHT`] words.
    pub fn seed_prior(&mut self, prior: &Prior) {
        let weight = prior.weight.clamp(0.0, MAX_PRIOR_WEIGHT);
        if weight <= 0.0 {
            return;
        }
        for (lang, share) in &prior.shares {
            if !lang.is_empty() && *share > 0.0 {
                *self.tally.entry(lang.clone()).or_insert(0.0) += share * weight;
            }
        }
        self.evidence += weight;
    }

    /// One committed word, credited to every language that knows it.
    pub fn record(&mut self, owners: &HashSet<String>) {
        self.tally.retain(|_, v| {
            *v *= FIELD_DECAY;
            *v >= FLOOR
        });
        self.evidence *= FIELD_DECAY;
        if owners.is_empty() {
            return;
        }
        for lang in owners.iter().filter(|l| !l.is_empty()) {
            *self.tally.entry(lang.clone()).or_insert(0.0) += 1.0;
        }
        self.evidence += 1.0;
    }

    /// The shares, or None while there is too little to say.
    pub fn shares(&self) -> Option<Shares> {
        if self.evidence < MIN_EVIDENCE {
            return None;
        }
        let total: f64 = self.tally.values().sum();
        if total <= 0.0 {
            return None;
        }
        Some(Shares {
            shares: self
                .tally
                .iter()
                .map(|(k, v)| (k.clone(), v / total))
                .collect(),
            ramp: (self.evidence / FULL_EVIDENCE).min(1.0),
        })
    }
}

#[derive(Clone, Debug, Default)]
pub struct AppLanguageMix {
    apps: HashMap<String, HashMap<String, f64>>,
    evidence: HashMap<String, f64>,
    /// Least recently typed-in first.
    order: Vec<String>,
    dirty: bool,
}

impl AppLanguageMix {
    pub fn is_dirty(&self) -> bool {
        self.dirty
    }

    fn touch(&mut self, app: &str) {
        self.order.retain(|a| a != app);
        self.order.push(app.to_string());
        while self.order.len() > MAX_APPS {
            let old = self.order.remove(0);
            self.apps.remove(&old);
            self.evidence.remove(&old);
        }
    }

    /// One word committed in `app`, credited to `owners`.
    pub fn record(&mut self, app: &str, owners: &HashSet<String>) {
        if app.is_empty() || (!self.apps.contains_key(app) && owners.is_empty()) {
            return;
        }
        self.touch(app);
        let tally = self.apps.entry(app.to_string()).or_default();
        tally.retain(|_, v| {
            *v *= APP_DECAY;
            *v >= FLOOR
        });
        let words = self.evidence.entry(app.to_string()).or_insert(0.0);
        *words *= APP_DECAY;
        if !owners.is_empty() {
            for lang in owners.iter().filter(|l| !l.is_empty()) {
                *tally.entry(lang.clone()).or_insert(0.0) += 1.0;
            }
            *words += 1.0;
        }
        self.dirty = true;
    }

    /// How `app`'s fields usually start, or None with too little history.
    pub fn prior(&self, app: &str) -> Option<Prior> {
        let tally = self.apps.get(app)?;
        let words = *self.evidence.get(app)?;
        if words < MIN_WORDS {
            return None;
        }
        let total: f64 = tally.values().sum();
        if total <= 0.0 {
            return None;
        }
        Some(Prior {
            shares: tally.iter().map(|(k, v)| (k.clone(), v / total)).collect(),
            weight: MAX_PRIOR_WEIGHT * words / (words + PRIOR_SAMPLE),
        })
    }

    pub fn to_json(&mut self) -> String {
        self.dirty = false;
        let apps: Map<String, Value> = self
            .apps
            .iter()
            .map(|(a, t)| {
                (
                    a.clone(),
                    Value::Object(t.iter().map(|(l, v)| (l.clone(), json!(v))).collect()),
                )
            })
            .collect();
        let evidence: Map<String, Value> = self
            .evidence
            .iter()
            .map(|(a, v)| (a.clone(), json!(v)))
            .collect();
        json!({"apps": apps, "evidence": evidence}).to_string()
    }

    pub fn from_json(text: &str) -> AppLanguageMix {
        let mut out = AppLanguageMix::default();
        let Ok(v) = serde_json::from_str::<Value>(text) else {
            return out;
        };
        if let Some(apps) = v["apps"].as_object() {
            for (app, tally) in apps {
                let clean: HashMap<String, f64> = tally
                    .as_object()
                    .into_iter()
                    .flatten()
                    .filter_map(|(l, x)| Some((l.clone(), x.as_f64()?)))
                    .filter(|(_, x)| *x > 0.0 && x.is_finite())
                    .collect();
                if !clean.is_empty() {
                    out.apps.insert(app.clone(), clean);
                    out.order.push(app.clone());
                }
            }
        }
        if let Some(e) = v["evidence"].as_object() {
            for (app, x) in e {
                if let Some(x) = x.as_f64().filter(|x| *x > 0.0 && x.is_finite()) {
                    if out.apps.contains_key(app) {
                        out.evidence.insert(app.clone(), x);
                    }
                }
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn set(ids: &[&str]) -> HashSet<String> {
        ids.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn the_last_few_words_decide_the_field() {
        let mut m = FieldLanguageMix::default();
        assert!(m.shares().is_none());
        m.record(&set(&["bn"]));
        m.record(&set(&["bn"]));
        let s = m.shares().unwrap();
        assert_eq!(1.0, s.share_of("bn"));
        assert!(s.ramp < 1.0);
        for _ in 0..3 {
            m.record(&set(&["en"]));
        }
        assert!(m.shares().unwrap().share_of("en") > 0.7);
        m.record(&set(&["en", "bn"]));
        m.record(&HashSet::new());
        assert!(m.shares().is_some());
    }

    #[test]
    fn an_apps_habit_seeds_its_fields() {
        let mut a = AppLanguageMix::default();
        assert!(a.prior("chat").is_none());
        for _ in 0..4 {
            a.record("chat", &set(&["bn_rom"]));
        }
        a.record("chat", &set(&["en"]));
        let p = a.prior("chat").unwrap();
        assert!(p.shares["bn_rom"] > p.shares["en"]);
        assert!(p.weight < MAX_PRIOR_WEIGHT);
        let back = AppLanguageMix::from_json(&a.to_json());
        assert_eq!(a.prior("chat"), back.prior("chat"));
        let mut f = FieldLanguageMix::default();
        f.seed_prior(&p);
        assert!(f.shares().is_none() || f.shares().unwrap().share_of("bn_rom") > 0.5);
    }
}

//! Cantonese Jyutping: the syllable split with tone digits folded into the
//! syllable before them (`JyutpingSyllables`), and lazy pronunciation, 懶音
//! (`JyutpingFuzzy`).

use super::segment::{parse_inventory, segment, Seg};
use std::collections::HashSet;

/// The bundled toneless inventory.
pub const SYLLABLES: &str =
    include_str!("../../../../app/src/main/assets/dictionaries/jyutping_syllables.txt");

/// Longest toneless syllable (`gwaang`).
const MAX_SYLLABLE: usize = 6;

pub fn bundled_syllables() -> HashSet<String> {
    parse_inventory(SYLLABLES)
}

/// Splits `buffer`, folding a trailing tone digit 1–6 into its syllable's span.
pub fn segment_syllables(buffer: &str, inventory: &HashSet<String>) -> Vec<Seg> {
    segment(buffer, inventory, MAX_SYLLABLE, &|_| false, &|c| {
        ('1'..='6').contains(&c)
    })
}

const INITIAL_GROUPS: [&[&str]; 4] = [&["n", "l"], &["ng", ""], &["z", "j"], &["c", "ch"]];

/// Labialised initials collapsing before a rounded final only.
const LABIAL_MERGERS: [(&str, &str, &str); 2] = [("gw", "g", "o"), ("kw", "k", "o")];

const FINAL_GROUPS: [&[&str]; 18] = [
    &["aan", "aang"],
    &["an", "ang"],
    &["en", "eng"],
    &["in", "ing"],
    &["on", "ong"],
    &["un", "ung"],
    &["aam", "aan"],
    &["am", "an"],
    &["im", "in"],
    &["aak", "aat"],
    &["ak", "at"],
    &["ek", "et"],
    &["ik", "it"],
    &["ok", "ot"],
    &["uk", "ut"],
    &["aap", "aat"],
    &["ap", "at"],
    &["ip", "it"],
];

const INITIALS: [&str; 20] = [
    "ng", "gw", "kw", "ch", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "z", "c", "s",
    "j", "w",
];

fn push_unique(out: &mut Vec<String>, s: String) {
    if !out.contains(&s) {
        out.push(s);
    }
}

fn alts(part: &str, groups: &[&[&str]]) -> Vec<String> {
    let mut out = vec![part.to_string()];
    for group in groups {
        if group.contains(&part) {
            for m in *group {
                push_unique(&mut out, m.to_string());
            }
        }
    }
    out
}

/// Every lazy-pronunciation variant of `syllable` the inventory has, itself first.
pub fn lazy_expand(syllable: &str, valid: &HashSet<String>) -> Vec<String> {
    if syllable.is_empty() {
        return Vec::new();
    }
    let initial = INITIALS
        .iter()
        .find(|i| syllable.starts_with(*i))
        .copied()
        .unwrap_or("");
    let fin = &syllable[initial.len()..];
    let mut out = vec![syllable.to_string()];
    for i in alts(initial, &INITIAL_GROUPS) {
        for f in alts(fin, &FINAL_GROUPS) {
            let cand = format!("{i}{f}");
            if !cand.is_empty() && (cand == syllable || valid.contains(&cand)) {
                push_unique(&mut out, cand);
            }
        }
    }
    for (labial, plain, condition) in LABIAL_MERGERS {
        if !fin.starts_with(condition) {
            continue;
        }
        let cand = if initial == labial {
            format!("{plain}{fin}")
        } else if initial == plain {
            format!("{labial}{fin}")
        } else {
            continue;
        };
        if valid.contains(&cand) {
            push_unique(&mut out, cand);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tones_fold_and_lazy_mergers() {
        let v = bundled_syllables();
        let segs = segment_syllables("nei5hou2", &v);
        assert_eq!(
            vec![("nei", 4), ("hou", 4)],
            segs.iter()
                .map(|s| (s.syllable.as_str(), s.input_len))
                .collect::<Vec<_>>()
        );
        let lazy = lazy_expand("lei", &v);
        assert_eq!("lei", lazy[0]);
        assert!(lazy.contains(&"nei".to_string()));
        assert!(lazy_expand("gok", &v).contains(&"gwok".to_string()));
        assert!(!lazy_expand("kaa", &v).contains(&"kwaa".to_string()));
    }
}

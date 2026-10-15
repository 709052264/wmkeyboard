//! Splits a conversion buffer into leading units against a fixed inventory:
//! the Kotlin `SyllableSegmenter`. Backtracking, not greedy: a forward pass
//! finds which positions a complete split reaches, the furthest of them ends
//! the segmentable prefix, a reverse pass marks which positions still reach
//! it, and the walk takes the longest unit among the steps that do (greedy
//! `sanguo` would take `sang` and lose `uo`).
//!
//! Positions and lengths count characters. Separators the user typed are
//! folded into a unit's span: pinyin's `'` before a unit, Jyutping's tone
//! digits after one.

use std::collections::HashSet;

/// One unit and how many input characters it spanned, separators included.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Seg {
    pub syllable: String,
    pub input_len: usize,
}

pub fn segment(
    buffer: &str,
    inventory: &HashSet<String>,
    max_unit: usize,
    skip_before: &dyn Fn(char) -> bool,
    skip_after: &dyn Fn(char) -> bool,
) -> Vec<Seg> {
    if buffer.is_empty() || inventory.is_empty() {
        return Vec::new();
    }
    let s: Vec<char> = buffer.to_lowercase().chars().collect();
    let n = s.len();
    let unit = |a: usize, len: usize| -> String { s[a..a + len].iter().collect() };
    let advance = |from: usize, limit: usize| {
        let mut i = from;
        while i < limit && skip_before(s[i]) {
            i += 1;
        }
        i
    };
    let landing = |start: usize, len: usize, limit: usize| {
        let after = start + len;
        if after < limit && skip_after(s[after]) {
            after + 1
        } else {
            after
        }
    };
    let mut reach = vec![false; n + 1];
    reach[0] = true;
    for i in 0..n {
        if !reach[i] {
            continue;
        }
        let start = advance(i, n);
        if start >= n {
            continue;
        }
        for len in 1..=max_unit.min(n - start) {
            if inventory.contains(&unit(start, len)) {
                reach[landing(start, len, n)] = true;
            }
        }
    }
    let mut end = n;
    while end > 0 && !reach[end] {
        end -= 1;
    }
    if end == 0 {
        return Vec::new();
    }
    let mut to_end = vec![false; end + 1];
    to_end[end] = true;
    for i in (0..end).rev() {
        let start = advance(i, end);
        if start >= end {
            continue;
        }
        for len in 1..=max_unit.min(end - start) {
            if !inventory.contains(&unit(start, len)) {
                continue;
            }
            let next = landing(start, len, end);
            if next <= end && to_end[next] {
                to_end[i] = true;
                break;
            }
        }
    }
    let mut out = Vec::new();
    let mut i = 0;
    while i < end {
        let start = advance(i, end);
        if start >= end {
            break;
        }
        let mut chosen = None;
        for len in (1..=max_unit.min(end - start)).rev() {
            if !inventory.contains(&unit(start, len)) {
                continue;
            }
            let landed = landing(start, len, end);
            if landed <= end && to_end[landed] {
                chosen = Some((len, landed));
                break;
            }
        }
        let Some((len, next)) = chosen else { break };
        out.push(Seg {
            syllable: unit(start, len),
            input_len: next - i,
        });
        i = next;
    }
    out
}

/// One unit per line, `#` comments skipped.
pub fn parse_inventory(text: &str) -> HashSet<String> {
    text.lines()
        .map(|l| l.trim().to_lowercase())
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backtracks_where_greedy_drops_the_tail() {
        let inv: HashSet<String> = ["san", "sang", "guo", "ni", "hao", "xi", "an", "xian"]
            .iter()
            .map(|s| s.to_string())
            .collect();
        let no = |_: char| false;
        let segs = segment("sanguo", &inv, 6, &no, &no);
        assert_eq!(
            vec!["san", "guo"],
            segs.iter().map(|s| s.syllable.as_str()).collect::<Vec<_>>()
        );
        let ap = segment("xi'an", &inv, 6, &|c| c == '\'', &no);
        assert_eq!(
            vec![("xi", 2), ("an", 3)],
            ap.iter()
                .map(|s| (s.syllable.as_str(), s.input_len))
                .collect::<Vec<_>>()
        );
        assert_eq!(1, segment("nihx", &inv, 6, &no, &no).len());
    }
}

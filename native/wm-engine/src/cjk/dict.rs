//! A reading-to-word table for a conversion IME (Pinyin to Hanzi, kana to
//! kanji): the Kotlin `ConversionDictionary`. Each pack line is
//! `reading<TAB>word<TAB>freq`; a reading may repeat across lines.
//!
//! Rows are kept sorted by reading in two text arenas with offset columns,
//! no allocation per row, since the kana pack is a million rows. A lookup is
//! a binary search for the first row with the reading, then a walk over its
//! contiguous run; [`ConversionDictionary::prefix_range`] narrows a range by
//! a longer prefix with two more binary searches, which is what lets the
//! decoder prune a reading no word starts with.

use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};
use std::ops::Range;

#[derive(Clone, Debug, Default)]
pub struct ConversionDictionary {
    readings: String,
    reading_at: Vec<u32>,
    reading_len: Vec<u32>,
    words: String,
    word_at: Vec<u32>,
    word_len: Vec<u32>,
    /// Characters in each word, for the one-character-per-syllable test.
    word_chars: Vec<u16>,
    freqs: Vec<i32>,
    /// Every row's frequency summed: the denominator for log P(word).
    pub total_freq: i64,
}

/// One row to merge in through [`ConversionDictionary::plus`].
pub struct Row {
    pub reading: String,
    pub word: String,
    pub frequency: i32,
    /// Lifts the frequency of a word the table already lists.
    pub raises: bool,
}

impl ConversionDictionary {
    pub fn len(&self) -> usize {
        self.freqs.len()
    }

    pub fn is_empty(&self) -> bool {
        self.freqs.is_empty()
    }

    /// Parses the pack's TSV; malformed lines are skipped.
    pub fn parse(text: &str) -> ConversionDictionary {
        let mut rows: Vec<(&str, &str, i32)> = Vec::new();
        for raw in text.lines() {
            let line = raw.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let mut parts = line.splitn(3, '\t');
            let reading = parts.next().unwrap_or("").trim();
            let word = parts.next().unwrap_or("").trim();
            if reading.is_empty() || word.is_empty() {
                continue;
            }
            let freq = parts
                .next()
                .and_then(|f| f.trim().parse().ok())
                .unwrap_or(0);
            rows.push((reading, word, freq));
        }
        Self::build(rows)
    }

    fn build(mut rows: Vec<(&str, &str, i32)>) -> ConversionDictionary {
        // Stable: rows of one reading keep the order the pack gave them.
        rows.sort_by(|a, b| a.0.cmp(b.0));
        let mut d = ConversionDictionary::default();
        let rlen: usize = rows.iter().map(|r| r.0.len()).sum();
        let wlen: usize = rows.iter().map(|r| r.1.len()).sum();
        d.readings.reserve(rlen);
        d.words.reserve(wlen);
        for (reading, word, freq) in rows {
            d.reading_at.push(d.readings.len() as u32);
            d.reading_len.push(reading.len() as u32);
            d.readings.push_str(reading);
            d.word_at.push(d.words.len() as u32);
            d.word_len.push(word.len() as u32);
            d.word_chars
                .push(word.chars().count().min(u16::MAX as usize) as u16);
            d.words.push_str(word);
            d.freqs.push(freq);
            d.total_freq += freq as i64;
        }
        d
    }

    pub fn reading(&self, row: usize) -> &str {
        let a = self.reading_at[row] as usize;
        &self.readings[a..a + self.reading_len[row] as usize]
    }

    pub fn word(&self, row: usize) -> &str {
        let a = self.word_at[row] as usize;
        &self.words[a..a + self.word_len[row] as usize]
    }

    /// Characters in the word at `row`, without cutting it.
    pub fn word_length(&self, row: usize) -> usize {
        self.word_chars[row] as usize
    }

    pub fn frequency(&self, row: usize) -> i32 {
        self.freqs[row]
    }

    fn lower_bound(&self, key: &str) -> usize {
        let (mut lo, mut hi) = (0, self.len());
        while lo < hi {
            let mid = (lo + hi) / 2;
            if self.reading(mid) < key {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        lo
    }

    /// The rows whose reading is exactly `reading`.
    pub fn rows_for(&self, reading: &str) -> Range<usize> {
        if reading.is_empty() || self.is_empty() {
            return 0..0;
        }
        let lo = self.lower_bound(reading);
        let mut hi = lo;
        while hi < self.len() && self.reading(hi) == reading {
            hi += 1;
        }
        lo..hi
    }

    /// `row`'s reading against `prefix`, over the prefix's length only.
    fn compare_prefix(&self, row: usize, prefix: &str) -> Ordering {
        let r = self.reading(row).as_bytes();
        let p = prefix.as_bytes();
        let n = r.len().min(p.len());
        match r[..n].cmp(&p[..n]) {
            Ordering::Equal if r.len() < p.len() => Ordering::Less,
            Ordering::Equal => Ordering::Equal,
            other => other,
        }
    }

    /// The rows within `within` whose reading starts with `prefix`.
    pub fn prefix_range(&self, prefix: &str, within: Range<usize>) -> Range<usize> {
        let lo = within.start.min(self.len());
        let hi = within.end.min(self.len());
        if lo >= hi {
            return 0..0;
        }
        if prefix.is_empty() {
            return lo..hi;
        }
        let bound = |inclusive: bool, mut a: usize, mut b: usize| {
            while a < b {
                let mid = (a + b) / 2;
                let c = self.compare_prefix(mid, prefix);
                let go = if inclusive {
                    c != Ordering::Greater
                } else {
                    c == Ordering::Less
                };
                if go {
                    a = mid + 1;
                } else {
                    b = mid;
                }
            }
            a
        };
        let start = bound(false, lo, hi);
        let end = bound(true, start, hi);
        start..end
    }

    /// Words for `reading` exactly, commonest first.
    pub fn exact(&self, reading: &str) -> Vec<String> {
        let mut rows: Vec<usize> = self.rows_for(reading).collect();
        rows.sort_by_key(|&r| std::cmp::Reverse(self.freqs[r]));
        let mut seen = HashSet::new();
        rows.into_iter()
            .map(|r| self.word(r).to_string())
            .filter(|w| seen.insert(w.clone()))
            .collect()
    }

    /// The exact match first, then the longest prefixes', up to `limit`.
    pub fn candidates(&self, reading: &str, limit: usize) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        let mut p = reading.to_string();
        while !p.is_empty() && out.len() < limit {
            for w in self.exact(&p) {
                if !out.contains(&w) {
                    out.push(w);
                }
            }
            p.pop();
        }
        out.truncate(limit);
        out
    }

    /// The frequency below which `quantile` of the rows fall.
    pub fn frequency_quantile(&self, quantile: f64) -> i32 {
        if self.is_empty() {
            return 0;
        }
        let mut f = self.freqs.clone();
        f.sort_unstable();
        f[((f.len() - 1) as f64 * quantile.clamp(0.0, 1.0)) as usize]
    }

    /// This table with `extra` merged in (a user's imported words). A word
    /// already listed keeps its frequency unless the row raises it.
    pub fn plus(&self, extra: &[Row]) -> ConversionDictionary {
        if extra.is_empty() {
            return self.clone();
        }
        let mut raised: HashMap<usize, i32> = HashMap::new();
        let mut fresh: HashMap<(String, String), i32> = HashMap::new();
        for r in extra {
            if r.reading.is_empty() || r.word.is_empty() {
                continue;
            }
            if let Some(held) = self
                .rows_for(&r.reading)
                .find(|&row| self.word(row) == r.word)
            {
                if r.raises && r.frequency > *raised.get(&held).unwrap_or(&self.freqs[held]) {
                    raised.insert(held, r.frequency);
                }
                continue;
            }
            let e = fresh
                .entry((r.reading.clone(), r.word.clone()))
                .or_insert(r.frequency);
            *e = (*e).max(r.frequency);
        }
        if fresh.is_empty() && raised.is_empty() {
            return self.clone();
        }
        let mut rows: Vec<(&str, &str, i32)> = (0..self.len())
            .map(|i| {
                (
                    self.reading(i),
                    self.word(i),
                    *raised.get(&i).unwrap_or(&self.freqs[i]),
                )
            })
            .collect();
        for ((reading, word), f) in &fresh {
            rows.push((reading.as_str(), word.as_str(), *f));
        }
        Self::build(rows)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lookups_and_prefix_ranges() {
        let d = ConversionDictionary::parse(
            "ni\t你\t900\nni\t尼\t100\nnihao\t你好\t800\nhao\t好\t700\n# x\nbad\n",
        );
        assert_eq!(vec!["你", "尼"], d.exact("ni"));
        assert_eq!(4, d.len());
        let r = d.prefix_range("ni", 0..d.len());
        assert_eq!(3, r.len());
        assert_eq!(1, d.prefix_range("nih", r).len());
        assert!(d.prefix_range("nx", 0..d.len()).is_empty());
        assert_eq!(vec!["你好", "你", "尼"], d.candidates("nihao", 5));
        let more = d.plus(&[Row {
            reading: "ni".into(),
            word: "泥".into(),
            frequency: 50,
            raises: false,
        }]);
        assert_eq!(vec!["你", "尼", "泥"], more.exact("ni"));
        assert_eq!(2, d.word_length(d.rows_for("nihao").start));
    }
}

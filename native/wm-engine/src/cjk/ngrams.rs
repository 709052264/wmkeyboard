//! Word unigram and bigram counts for the conversion decoder: the Kotlin
//! `CjkNgrams`. The pack's TSV has two fields for a unigram and three for a
//! pair. Keys are 64-bit FNV-1a hashes in sorted columns; the words are kept
//! too, with each one's commonest followers, for next-word suggestions.

use std::collections::HashMap;

#[derive(Clone, Debug, Default)]
pub struct CjkNgrams {
    uni_keys: Vec<u64>,
    uni_counts: Vec<i32>,
    bi_keys: Vec<u64>,
    bi_counts: Vec<i32>,
    total: i64,
    words: Vec<String>,
    word_counts: Vec<i32>,
    succ_start: Vec<u32>,
    succ_words: Vec<u32>,
    succ_counts: Vec<i32>,
}

/// Successors kept per word.
pub const SUCCESSORS: usize = 8;
const LOG_BACKOFF: f64 = -0.916_290_731_874_155; // ln 0.4

fn hash(s: &str) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for c in s.encode_utf16() {
        h ^= c as u64;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}

fn pair_hash(a: &str, b: &str) -> u64 {
    let mut h = hash(a);
    h = h.wrapping_mul(0x100_0000_01b3);
    for c in b.encode_utf16() {
        h ^= c as u64;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}

fn sorted_merge(mut v: Vec<(u64, i32)>) -> (Vec<u64>, Vec<i32>) {
    // Stable: a key listed twice keeps its last count.
    v.sort_by_key(|e| e.0);
    let mut keys: Vec<u64> = Vec::with_capacity(v.len());
    let mut counts: Vec<i32> = Vec::with_capacity(v.len());
    for (k, c) in v {
        if keys.last() == Some(&k) {
            *counts.last_mut().unwrap() = c;
        } else {
            keys.push(k);
            counts.push(c);
        }
    }
    (keys, counts)
}

impl CjkNgrams {
    pub fn is_empty(&self) -> bool {
        self.uni_keys.is_empty() && self.bi_keys.is_empty()
    }

    pub fn parse(text: &str) -> CjkNgrams {
        let mut uni = Vec::new();
        let mut bi = Vec::new();
        let mut total = 0i64;
        let mut word_list: Vec<String> = Vec::new();
        let mut word_count: Vec<i32> = Vec::new();
        let mut index: HashMap<String, u32> = HashMap::new();
        let mut pairs: Vec<(u32, u32, i32)> = Vec::new();
        for raw in text.lines() {
            let line = raw.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let f: Vec<&str> = line.split('\t').collect();
            match f.len() {
                2 => {
                    let Ok(count) = f[1].trim().parse::<i32>() else {
                        continue;
                    };
                    if count <= 0 || f[0].is_empty() {
                        continue;
                    }
                    uni.push((hash(f[0]), count));
                    total += count as i64;
                    if !index.contains_key(f[0]) {
                        index.insert(f[0].to_string(), word_list.len() as u32);
                        word_list.push(f[0].to_string());
                        word_count.push(count);
                    }
                }
                3 => {
                    let Ok(count) = f[2].trim().parse::<i32>() else {
                        continue;
                    };
                    if count <= 0 || f[0].is_empty() || f[1].is_empty() {
                        continue;
                    }
                    bi.push((pair_hash(f[0], f[1]), count));
                    if let (Some(&a), Some(&b)) = (index.get(f[0]), index.get(f[1])) {
                        pairs.push((a, b, count));
                    }
                }
                _ => {}
            }
        }
        if uni.is_empty() && bi.is_empty() {
            return CjkNgrams::default();
        }
        let (uni_keys, uni_counts) = sorted_merge(uni);
        let (bi_keys, bi_counts) = sorted_merge(bi);
        // Words sorted, each with its commonest followers.
        let n = word_list.len();
        let mut order: Vec<u32> = (0..n as u32).collect();
        order.sort_by(|&a, &b| word_list[a as usize].cmp(&word_list[b as usize]));
        let mut rank = vec![0u32; n];
        for (sorted, &orig) in order.iter().enumerate() {
            rank[orig as usize] = sorted as u32;
        }
        let words: Vec<String> = order
            .iter()
            .map(|&o| word_list[o as usize].clone())
            .collect();
        let word_counts: Vec<i32> = order.iter().map(|&o| word_count[o as usize]).collect();
        let mut ranked: Vec<(u32, i32, u32)> = pairs
            .into_iter()
            .map(|(a, b, c)| (rank[a as usize], c, rank[b as usize]))
            .collect();
        ranked.sort_by(|x, y| x.0.cmp(&y.0).then(y.1.cmp(&x.1)).then(x.2.cmp(&y.2)));
        let mut succ_start = vec![0u32; n + 1];
        let mut succ_words = Vec::new();
        let mut succ_counts = Vec::new();
        let mut i = 0;
        for w in 0..n as u32 {
            succ_start[w as usize] = succ_words.len() as u32;
            let mut taken = 0;
            while i < ranked.len() && ranked[i].0 == w {
                if taken < SUCCESSORS {
                    succ_words.push(ranked[i].2);
                    succ_counts.push(ranked[i].1);
                    taken += 1;
                }
                i += 1;
            }
        }
        succ_start[n] = succ_words.len() as u32;
        CjkNgrams {
            uni_keys,
            uni_counts,
            bi_keys,
            bi_counts,
            total,
            words,
            word_counts,
            succ_start,
            succ_words,
            succ_counts,
        }
    }

    pub fn unigram(&self, word: &str) -> i32 {
        self.uni_keys
            .binary_search(&hash(word))
            .map_or(0, |i| self.uni_counts[i])
    }

    pub fn bigram(&self, previous: &str, word: &str) -> i32 {
        self.bi_keys
            .binary_search(&pair_hash(previous, word))
            .map_or(0, |i| self.bi_counts[i])
    }

    /// The words seen most often after `word`, commonest first.
    pub fn successors(&self, word: &str) -> Vec<(String, i32)> {
        let Ok(i) = self.words.binary_search_by(|w| w.as_str().cmp(word)) else {
            return Vec::new();
        };
        (self.succ_start[i] as usize..self.succ_start[i + 1] as usize)
            .map(|k| {
                (
                    self.words[self.succ_words[k] as usize].clone(),
                    self.succ_counts[k],
                )
            })
            .collect()
    }

    /// Longer words that start with `prefix`, commonest first.
    pub fn completions(&self, prefix: &str, limit: usize) -> Vec<(String, i32)> {
        if prefix.is_empty() || self.words.is_empty() {
            return Vec::new();
        }
        let mut i = match self.words.binary_search_by(|w| w.as_str().cmp(prefix)) {
            Ok(i) => i + 1,
            Err(i) => i,
        };
        let mut found = Vec::new();
        while i < self.words.len() && self.words[i].starts_with(prefix) {
            found.push((self.words[i].clone(), self.word_counts[i]));
            i += 1;
        }
        found.sort_by_key(|f| std::cmp::Reverse(f.1));
        found.truncate(limit);
        found
    }

    /// `ln P(word | previous)` by stupid backoff; with no pack, the
    /// conversion table's own frequency (a unigram model).
    pub fn log_probability(
        &self,
        previous: Option<&str>,
        word: &str,
        pack_freq: i32,
        pack_total: i64,
    ) -> f64 {
        if self.is_empty() {
            return ((pack_freq as f64) + 1.0).ln() - ((pack_total as f64) + 1.0).ln();
        }
        if let Some(prev) = previous {
            let pair = self.bigram(prev, word);
            if pair > 0 {
                let prior = self.unigram(prev);
                if prior > 0 {
                    return (pair as f64).ln() - (prior as f64).ln();
                }
            }
        }
        LOG_BACKOFF + ((self.unigram(word) as f64) + 1.0).ln() - ((self.total as f64) + 1.0).ln()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_followers_and_backoff() {
        let n = CjkNgrams::parse("我\t100\n喜欢\t50\n无聊\t5\n无\t10\n我\t喜欢\t30\n");
        assert_eq!(100, n.unigram("我"));
        assert_eq!(30, n.bigram("我", "喜欢"));
        assert_eq!(vec![("喜欢".to_string(), 30)], n.successors("我"));
        assert_eq!(vec![("无聊".to_string(), 5)], n.completions("无", 4));
        assert!(
            n.log_probability(Some("我"), "喜欢", 0, 0) > n.log_probability(None, "喜欢", 0, 0)
        );
        assert!(CjkNgrams::default()
            .log_probability(None, "x", 9, 99)
            .is_finite());
    }
}

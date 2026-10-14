//! Reverse-phonetic lookup from a romanized spelling to the dictionary words
//! it could stand for, for a layout whose keys are Latin and whose text is
//! not. A port of `PhoneticIndex.kt` and `SortedWords.kt`.
//!
//! Romanized spelling is loose in every language that has one, so an index
//! folds both sides, what was typed and each dictionary word, to a lenient
//! key where the confusable sounds collapse, and words sharing a key are
//! siblings. How the fold works is the language's own business; what the
//! suggestion engine needs from it is only this trait.

/// A word list in code-point order, read a character at a time so a search
/// can walk it as a tree of shared prefixes without building a string per
/// word. Words that share a prefix are one contiguous run, and within a run
/// the shorter word comes first.
pub trait SortedWords {
    fn size(&self) -> usize;
    fn length(&self, i: usize) -> usize;
    fn char_at(&self, i: usize, at: usize) -> char;
    fn word(&self, i: usize) -> String;
    /// Where word `i` goes among the matches of one search, lowest first:
    /// its place in its own list, or its frequency counted down.
    fn rank(&self, i: usize) -> i64;
}

pub trait PhoneticIndex {
    /// Every word of the list in code-point order, for a search that walks
    /// it as a tree of shared prefixes. `None` where the index keeps no such
    /// order.
    fn sorted_words(&self) -> Option<&dyn SortedWords> {
        None
    }

    /// Dictionary words phonetically matching the romanized `input`, best first.
    fn lookup(&self, input: &str) -> Vec<String>;

    /// `lookup`, widened to the readings a looser search would also allow.
    /// For the candidate list, which is meant to be long; the strip keeps
    /// `lookup`. The default is `lookup` itself.
    fn lookup_loose(&self, input: &str) -> Vec<String> {
        self.lookup(input)
    }

    /// Dictionary frequency of a native-script `word`, 0 when unknown.
    fn frequency_of(&self, word: &str) -> i32;

    /// The list's native-script words that begin with `prefix`, commonest
    /// first, at most `limit` of them. Empty by default.
    fn completions(&self, _prefix: &str, _limit: usize) -> Vec<String> {
        Vec::new()
    }

    /// How good `lookup`'s best answer for `input` is: that word's frequency,
    /// divided by whatever the fold's discarded detail disagreed with. 0 when
    /// nothing matches.
    fn match_strength(&self, input: &str) -> i32 {
        self.lookup(input)
            .first()
            .map_or(0, |w| self.frequency_of(w))
    }

    /// The largest frequency in the list, the scale `frequency_of` is read against.
    fn max_frequency(&self) -> i32 {
        0
    }

    /// Whether there is no word behind this index at all.
    fn is_empty(&self) -> bool;
}

/// No dictionary: every lookup misses, and the rules stand alone.
pub struct Empty;

impl PhoneticIndex for Empty {
    fn lookup(&self, _input: &str) -> Vec<String> {
        Vec::new()
    }

    fn frequency_of(&self, _word: &str) -> i32 {
        0
    }

    fn is_empty(&self) -> bool {
        true
    }
}

/// How many words a phonetic index files at most; see [`bounded_entries`].
pub const PHONETIC_INDEX_BUDGET: usize = 300_000;

/// At most `max` of `entries`, the ones a lookup is likeliest to want. Past
/// the budget the commonest words are kept; a list with no frequencies
/// (every word at the same count) keeps its shortest words instead, the
/// nearest thing to a frequency a flat list has, and never an alphabetical
/// slice.
pub fn bounded_entries(entries: Vec<(String, i32)>, max: usize) -> Vec<(String, i32)> {
    if entries.len() <= max {
        return entries;
    }
    let flat = entries.iter().all(|e| e.1 == entries[0].1);
    let mut ranked = entries;
    if flat {
        ranked.sort_by_key(|e| e.0.len());
    } else {
        ranked.sort_by_key(|e| std::cmp::Reverse(e.1));
    }
    ranked.truncate(max);
    ranked
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bounded_entries_keeps_the_commonest_or_the_shortest() {
        let ranked = vec![
            ("a".to_string(), 1),
            ("bb".to_string(), 9),
            ("ccc".to_string(), 5),
        ];
        assert_eq!(
            vec![("bb".to_string(), 9), ("ccc".to_string(), 5)],
            bounded_entries(ranked, 2)
        );
        let flat = vec![
            ("ccc".to_string(), 1),
            ("a".to_string(), 1),
            ("bb".to_string(), 1),
        ];
        assert_eq!(
            vec![("a".to_string(), 1), ("bb".to_string(), 1)],
            bounded_entries(flat, 2)
        );
        assert!(Empty.is_empty());
        assert!(Empty.lookup("x").is_empty());
    }
}

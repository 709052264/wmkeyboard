//! Assamese phonetic: roman letters in, Assamese script out, on the Avro
//! engine. A port of `AssamesePhonetic.kt`.
//!
//! Assamese is written in the Bengali script, and the way people romanize it
//! is the way they romanize Bangla, so Avro's rules read it well. Three
//! things are Assamese's own, fixed up on the way through:
//!
//! - ৰ, not র: every র Avro writes is ৰ.
//! - "x" is স ("xokolu" সকলো, "axom" অসম), read as s before the rules see it.
//! - ৱ, not ওয়: inside a word Avro's ওয় glide becomes ৱ ("suwali" ছোৱালী);
//!   opening one it stays, since ও there is a vowel.

use std::collections::HashMap;

use crate::avro;
use crate::bengali_graphemes::is_bengali;
use crate::bengali_index::BengaliPhoneticIndex;
use crate::phonetic_index::{bounded_entries, PhoneticIndex, PHONETIC_INDEX_BUDGET};

const BENGALI_RA: char = '\u{9B0}';
const ASSAMESE_RA: char = '\u{9F0}';
const ASSAMESE_WA: char = '\u{9F1}';
const BENGALI_BA: char = '\u{9AC}';

/// Transliterates roman Assamese, one word or free text, into the Assamese script.
pub fn transliterate(input: &str) -> String {
    let base =
        avro::transliterate(&input.replace('x', "s")).replace(BENGALI_RA, &ASSAMESE_RA.to_string());
    glide_wa(&base)
}

/// ও then য় (precomposed, or য with a nukta) after a letter of the word
/// becomes ৱ. The letter before is read from the text as it was, as the
/// regex lookbehind in the original does.
fn glide_wa(text: &str) -> String {
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '\u{993}' && i > 0 && is_bengali(chars[i - 1]) {
            if chars.get(i + 1) == Some(&'\u{9DF}') {
                out.push(ASSAMESE_WA);
                i += 2;
                continue;
            }
            if chars.get(i + 1) == Some(&'\u{9AF}') && chars.get(i + 2) == Some(&'\u{9BC}') {
                out.push(ASSAMESE_WA);
                i += 3;
                continue;
            }
        }
        out.push(chars[i]);
        i += 1;
    }
    out
}

/// Assamese text in the Bangla letters Avro and the Bengali index read: ৰ as
/// র and ৱ as ব.
pub fn to_bengali(text: &str) -> String {
    text.replace(ASSAMESE_RA, &BENGALI_RA.to_string())
        .replace(ASSAMESE_WA, &BENGALI_BA.to_string())
}

/// An Assamese letter or sign: the Bengali block, which holds ৰ and ৱ too.
pub fn is_assamese(c: char) -> bool {
    is_bengali(c)
}

/// Reverse-phonetic lookup for Assamese: the Bengali index over the list
/// spelled in Bangla letters ([`to_bengali`]), answering in the list's own
/// Assamese spelling.
pub struct AssamesePhoneticIndex {
    native: HashMap<String, String>,
    inner: BengaliPhoneticIndex,
}

impl AssamesePhoneticIndex {
    pub fn new(entries: Vec<(String, i32)>) -> AssamesePhoneticIndex {
        let kept = bounded_entries(entries, PHONETIC_INDEX_BUDGET);
        let mut native: HashMap<String, String> = HashMap::with_capacity(kept.len());
        let mut converted: Vec<(String, i32)> = Vec::with_capacity(kept.len());
        for (word, frequency) in kept {
            let bn = to_bengali(&word);
            native.entry(bn.clone()).or_insert_with(|| word.clone());
            converted.push((bn, frequency));
        }
        AssamesePhoneticIndex {
            native,
            inner: BengaliPhoneticIndex::new(converted),
        }
    }
}

impl PhoneticIndex for AssamesePhoneticIndex {
    fn lookup(&self, input: &str) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        for bn in self.inner.lookup(&input.replace('x', "s")) {
            if let Some(word) = self.native.get(&bn) {
                if !out.contains(word) {
                    out.push(word.clone());
                }
            }
        }
        out
    }

    fn frequency_of(&self, word: &str) -> i32 {
        self.inner.frequency_of(&to_bengali(word))
    }

    fn match_strength(&self, input: &str) -> i32 {
        self.inner.match_strength(&input.replace('x', "s"))
    }

    fn max_frequency(&self) -> i32 {
        self.inner.max_frequency()
    }

    fn is_empty(&self) -> bool {
        self.inner.is_empty()
    }
}

#[cfg(test)]
mod tests {
    //! Mirrors `AssamesePhoneticTest.kt`, the index test aside.

    use super::{to_bengali, transliterate as t};
    use crate::avro;

    #[test]
    fn never_the_bangla_ra() {
        for word in ["ami", "tumar", "ghor", "karon", "bhitor", "rati"] {
            assert!(!t(word).contains('\u{9B0}'), "{word}");
        }
        assert!(t("tumar").contains('\u{9F0}'));
    }

    #[test]
    fn x_is_s() {
        assert_eq!(
            avro::transliterate("sokolu").replace('\u{9B0}', "\u{9F0}"),
            t("xokolu")
        );
        assert!(t("axom").contains('\u{9B8}'));
    }

    #[test]
    fn a_w_glide_is_wa() {
        let glide = t("suwali");
        assert!(glide.contains('\u{9F1}'), "{glide}");
        assert!(!glide.contains("\u{993}\u{9DF}"), "{glide}");
        assert!(t("wasi").starts_with('\u{993}'));
    }

    #[test]
    fn to_bengali_is_the_way_into_the_bangla_index() {
        assert_eq!("\u{9B0}\u{9AC}", to_bengali("\u{9F0}\u{9F1}"));
    }

    #[test]
    fn the_index_answers_in_assamese_spelling() {
        use super::AssamesePhoneticIndex;
        use crate::phonetic_index::PhoneticIndex;
        let index = AssamesePhoneticIndex::new(vec![
            ("\u{986}\u{9AE}\u{9BE}\u{9F0}".to_string(), 100),
            ("\u{9AE}\u{987}".to_string(), 90),
        ]);
        assert_eq!(
            Some("\u{986}\u{9AE}\u{9BE}\u{9F0}".to_string()),
            index.lookup("amar").into_iter().next()
        );
        assert_eq!(100, index.frequency_of("\u{986}\u{9AE}\u{9BE}\u{9F0}"));
    }
}

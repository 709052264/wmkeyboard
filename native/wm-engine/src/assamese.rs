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

use crate::avro;
use crate::bengali_graphemes::is_bengali;

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
}

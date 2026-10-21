//! The key every prediction store files a word under, and which words the
//! keyboard may learn on its own: the Kotlin `WordKey.of`, `WordKey.surface`
//! and `WordContext.isLearnableWord`, computed the way Android's runtime
//! computes them.
//!
//! `WordKey.of` is `NFC(word.lowercase())`, and Kotlin's `lowercase()` is
//! `String.toLowerCase(Locale.ROOT)`. On ART that is libcore's
//! `CaseMapper.toLowerCase` (libart/src/main/java/java/lang/CaseMapper.java),
//! which is not ICU's full lowercase:
//!
//! - A string holding U+0130 (capital I with dot above) or any high surrogate
//!   goes whole to ICU4C's `u_strToLower` for the root locale: full mappings
//!   (U+0130 becomes `i` + U+0307) and Unicode's Final_Sigma context, which
//!   skips case-ignorable characters on both sides.
//! - Any other string is mapped one UTF-16 unit at a time with `u_tolower`
//!   (the simple mapping), except that Σ becomes ς when the unit before it is
//!   a cased letter and the unit after it is not, or there is none. "Cased"
//!   there is the general category (`u_islower`, `u_isupper`, `u_istitle`:
//!   Ll, Lu, Lt), and nothing is skipped, so "ΑΣ'" and "Σ̈" read differently
//!   from the ICU path. That is the runtime's rule, so it is this module's.
//!
//! `Locale.ROOT` means no Turkish, Azeri or Lithuanian rules: a dotted
//! capital I folds to `i` + U+0307 and a plain one to `i`, whatever the
//! phone's language.
//!
//! The case, normalisation and category tables are ICU4X's compiled data,
//! Unicode 17. A phone answers from its own ICU, Unicode 15.0 on Android 14,
//! so the two differ only for characters the phone's Unicode does not have:
//! the parity test (`WordKeyParityTest`) checks every code point against a
//! transcription of `CaseMapper` over ICU4J at the same Unicode version and
//! lists what an older ICU folds differently. A store folded on the phone and
//! re-folded here can disagree only on those, and a load only re-folds the
//! capital spellings it keeps (`user_lexicon`), which then go unremembered.
//!
//! Everything works on UTF-16 units, as the Kotlin does, so a lone surrogate
//! passes through untouched and counts as what it is (general category Cs:
//! not a letter, not a mark, not cased). The `&str` forms are for callers
//! that hold Rust strings.

use icu_casemap::CaseMapper;
use icu_locale_core::LanguageIdentifier;
use icu_normalizer::ComposingNormalizerBorrowed;
use icu_properties::props::GeneralCategory;
use icu_properties::CodePointMapData;

const CAPITAL_I_WITH_DOT: u16 = 0x0130;
const CAPITAL_SIGMA: u16 = 0x03A3;
const SMALL_FINAL_SIGMA: u16 = 0x03C2;
/// The combining grave accent: the first code point NFC can do anything with.
const FIRST_COMPOSING: u16 = 0x0300;

/// What may sit between two letters of one word (`WordContext.WORD_JOINERS`):
/// the apostrophes, the hyphen, ZWNJ and ZWJ.
pub const WORD_JOINERS: [u16; 8] = [0x27, 0x2019, 0x2018, 0x05F3, 0x055A, 0x2D, 0x200C, 0x200D];

fn is_high_surrogate(u: u16) -> bool {
    (0xD800..=0xDBFF).contains(&u)
}

fn is_surrogate(u: u16) -> bool {
    (0xD800..=0xDFFF).contains(&u)
}

/// The general category of one UTF-16 unit, a surrogate being Cs.
fn category(u: u16) -> GeneralCategory {
    CodePointMapData::<GeneralCategory>::new().get32(u as u32)
}

/// `Character.isLetter(char)`: general category L.
pub fn is_letter(u: u16) -> bool {
    matches!(
        category(u),
        GeneralCategory::UppercaseLetter
            | GeneralCategory::LowercaseLetter
            | GeneralCategory::TitlecaseLetter
            | GeneralCategory::ModifierLetter
            | GeneralCategory::OtherLetter
    )
}

/// `Character.isDigit(char)`: general category Nd.
pub fn is_digit(u: u16) -> bool {
    category(u) == GeneralCategory::DecimalNumber
}

/// `WordContext.isWordChar`: a letter, or a combining mark (Mn, Mc, Me),
/// which is part of the letter before it.
pub fn is_word_char(u: u16) -> bool {
    is_letter(u)
        || matches!(
            category(u),
            GeneralCategory::NonspacingMark | GeneralCategory::SpacingMark | GeneralCategory::EnclosingMark
        )
}

/// `u_islower || u_isupper || u_istitle`, the test `CaseMapper.isFinalSigma`
/// makes of its neighbours.
fn is_cased_letter(u: u16) -> bool {
    matches!(
        category(u),
        GeneralCategory::UppercaseLetter | GeneralCategory::LowercaseLetter | GeneralCategory::TitlecaseLetter
    )
}

/// `CaseMapper.isFinalSigma`: the unit before `index` is a cased letter and
/// the one after it, if any, is not.
fn is_final_sigma(s: &[u16], index: usize) -> bool {
    if index == 0 || !is_cased_letter(s[index - 1]) {
        return false;
    }
    index + 1 >= s.len() || !is_cased_letter(s[index + 1])
}

/// `Character.toLowerCase(char)`: ASCII directly, everything else through
/// the simple mapping (`u_tolower`); a lone low surrogate is itself.
fn simple_lowercase(u: u16) -> u16 {
    if u < 0x80 {
        return if (0x41..=0x5A).contains(&u) { u + 0x20 } else { u };
    }
    if is_surrogate(u) {
        return u;
    }
    let c = char::from_u32(u as u32).expect("a BMP unit that is not a surrogate");
    let lower = CaseMapper::new().simple_lowercase(c) as u32;
    // Java narrows the mapping back to a char; no BMP letter lowercases out
    // of the BMP, which the parity test checks.
    lower as u16
}

/// Splits `s` into its valid runs and its lone surrogates, in order: what
/// the ICU-backed paths below can hand ICU4X (which takes valid text only)
/// without changing the answer. A lone surrogate is a starter with no case,
/// no decomposition and no case-ignorable property, so neither the
/// Final_Sigma context nor composition reaches across it, and treating each
/// side as its own string gives what ICU4C gives the whole.
fn for_each_run(s: &[u16], mut run: impl FnMut(&str, &mut Vec<u16>), out: &mut Vec<u16>) {
    let mut text = String::new();
    let mut i = 0;
    while i < s.len() {
        let u = s[i];
        let pair = is_high_surrogate(u) && i + 1 < s.len() && (0xDC00..=0xDFFF).contains(&s[i + 1]);
        if pair {
            let c = 0x10000 + (((u as u32) - 0xD800) << 10) + ((s[i + 1] as u32) - 0xDC00);
            text.push(char::from_u32(c).expect("a surrogate pair is a scalar value"));
            i += 2;
        } else if is_surrogate(u) {
            if !text.is_empty() {
                run(&text, out);
                text.clear();
            }
            out.push(u);
            i += 1;
        } else {
            text.push(char::from_u32(u as u32).expect("a BMP unit that is not a surrogate"));
            i += 1;
        }
    }
    if !text.is_empty() {
        run(&text, out);
    }
}

/// `String.toLowerCase(Locale.ROOT)` on ART; see the module comment.
pub fn lowercase(s: &[u16]) -> Vec<u16> {
    if s.iter().any(|&u| u == CAPITAL_I_WITH_DOT || is_high_surrogate(u)) {
        let mut out = Vec::with_capacity(s.len() + 1);
        let root = LanguageIdentifier::UNKNOWN;
        for_each_run(
            s,
            |text, out| out.extend(CaseMapper::new().lowercase_to_string(text, &root).encode_utf16()),
            &mut out,
        );
        return out;
    }
    (0..s.len())
        .map(|i| {
            let u = s[i];
            if u == CAPITAL_SIGMA && is_final_sigma(s, i) {
                SMALL_FINAL_SIGMA
            } else {
                simple_lowercase(u)
            }
        })
        .collect()
}

/// `Nfc.normalize`: NFC, with the shortcut `WordKey` takes for a word made
/// only of characters below U+0300, where nothing composes.
pub fn nfc(s: &[u16]) -> Vec<u16> {
    if s.iter().all(|&u| u < FIRST_COMPOSING) {
        return s.to_vec();
    }
    let mut out = Vec::with_capacity(s.len());
    let nfc = ComposingNormalizerBorrowed::new_nfc();
    for_each_run(s, |text, out| out.extend(nfc.normalize(text).encode_utf16()), &mut out);
    out
}

/// `WordKey.of`: the spelling a word is stored and looked up under.
pub fn of(word: &[u16]) -> Vec<u16> {
    nfc(&lowercase(word))
}

/// `WordKey.surface`: the same normalisation without the case fold.
pub fn surface(word: &[u16]) -> Vec<u16> {
    nfc(word)
}

/// [`of`] for a Rust string.
pub fn of_str(word: &str) -> String {
    let units: Vec<u16> = word.encode_utf16().collect();
    String::from_utf16(&of(&units)).expect("folding valid text gives valid text")
}

/// [`surface`] for a Rust string.
pub fn surface_str(word: &str) -> String {
    let units: Vec<u16> = word.encode_utf16().collect();
    String::from_utf16(&surface(&units)).expect("normalising valid text gives valid text")
}

/// `WordContext.isLearnableWord`: letters and digits with at least one
/// letter, starting and ending on one of those, with single joiners
/// ([`WORD_JOINERS`]) allowed inside, never two in a row.
pub fn is_learnable(word: &[u16]) -> bool {
    let (Some(&first), Some(&last)) = (word.first(), word.last()) else {
        return false;
    };
    let letter_or_digit = |u: u16| is_word_char(u) || is_digit(u);
    if !letter_or_digit(first) || !letter_or_digit(last) {
        return false;
    }
    let mut after_joiner = false;
    let mut letters = 0;
    for &u in word {
        if is_word_char(u) {
            letters += 1;
        }
        after_joiner = if letter_or_digit(u) {
            false
        } else if WORD_JOINERS.contains(&u) && !after_joiner {
            true
        } else {
            return false;
        };
    }
    letters > 0
}

/// [`is_learnable`] for a Rust string.
pub fn is_learnable_str(word: &str) -> bool {
    let units: Vec<u16> = word.encode_utf16().collect();
    is_learnable(&units)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn u(s: &str) -> Vec<u16> {
        s.encode_utf16().collect()
    }

    fn of_s(s: &str) -> String {
        String::from_utf16(&of(&u(s))).unwrap()
    }

    #[test]
    fn ascii_and_latin_fold() {
        assert_eq!(of_s("Hello"), "hello");
        assert_eq!(of_s("ÉCOLE"), "école");
        assert_eq!(of_s("STRASSE"), "strasse");
        // ẞ has a simple lowercase; full lowercase would agree here.
        assert_eq!(of_s("ẞ"), "ß");
    }

    #[test]
    fn dotted_capital_i_takes_the_full_path() {
        assert_eq!(of(&u("İ")), vec![0x69, 0x307]);
        assert_eq!(of_s("İSTANBUL"), "i\u{307}stanbul");
        // No Turkish rules: a plain I is i.
        assert_eq!(of_s("ISPARTA"), "isparta");
    }

    #[test]
    fn final_sigma_the_runtime_way() {
        assert_eq!(of_s("ΟΔΟΣ"), "οδο\u{3C2}");
        assert_eq!(of_s("ΣΑΣ"), "σα\u{3C2}");
        // Alone: nothing cased before it.
        assert_eq!(of_s("Σ"), "σ");
        // ART does not skip the apostrophe: the unit before Σ is not cased.
        assert_eq!(of_s("Α'Σ"), "α'σ");
        // ...nor the mark after it, which is not a cased letter, so final.
        assert_eq!(of_s("ΑΣ\u{308}"), "ας\u{308}");
    }

    #[test]
    fn a_supplementary_letter_sends_the_whole_word_to_full_mapping() {
        // Osage capital A, with a Σ whose context ICU reads properly: the
        // mark after it is case-ignorable and a cased letter follows, so not
        // final; ART's own check would have said final.
        let word = u("𐒰ΑΣ\u{308}Α");
        let folded = of(&word);
        assert_eq!(String::from_utf16(&folded).unwrap(), "𐓘ασ\u{308}α");
    }

    #[test]
    fn lone_surrogates_pass_through() {
        let word = vec![0x41, 0xDC00, 0x42];
        assert_eq!(of(&word), vec![0x61, 0xDC00, 0x62]);
        let word = vec![0x41, 0xD800, 0x3A3];
        // A high surrogate: the full path, split at it.
        assert_eq!(of(&word), vec![0x61, 0xD800, 0x3C3]);
    }

    #[test]
    fn nfc_composes_and_keeps_exclusions_decomposed() {
        assert_eq!(of_s("e\u{301}"), "é");
        // য় (U+09DF) is a composition exclusion: NFC takes it apart.
        assert_eq!(of(&u("\u{9DF}")), vec![0x9AF, 0x9BC]);
        assert_eq!(surface_str("Boston"), "Boston");
    }

    #[test]
    fn learnable_words() {
        for w in ["don't", "well-known", "mp3", "b2b", "হয়েছে", "c'hoar", "об'єкт", "ไทย"] {
            assert!(is_learnable_str(w), "{w}");
        }
        for w in ["", "2024", "manager\"", "man\"ager", "a--b", "-ab", "ab'", "𐓘𐓘"] {
            assert!(!is_learnable_str(w), "{w}");
        }
    }
}

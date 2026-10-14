//! Urdu written back in Latin letters: کرنا → karna, مرد → mard, بچہ → bacha.
//! A port of `UrduRomanizer.kt`.
//!
//! The hardest of the three romanizers, because Urdu's script does not write
//! its short vowels at all. Going the other way they have to be invented:
//! this writes an "a" wherever a vowel is needed to make the word sayable,
//! which is right more often than any other single guess. Long vowels are
//! written the way chat spelling writes them: ا is "a", و is "o" ("w" where
//! it opens a word), ی is "i" ("y" opening one), ے is "e", آ is "aa".
//! Aspiration folds into the consonant before it, ں is "n", and the hamza
//! carriers are silent.

const DO_HE: char = '\u{6BE}';
const GOL_HE: char = '\u{6C1}';
const ALIF: char = '\u{627}';
const MADDA: char = '\u{622}';
const WAO: char = '\u{648}';
const YE: char = '\u{6CC}';
const BARI_YE: char = '\u{6D2}';
const ZWJ: char = '\u{200D}';
const ZWNJ: char = '\u{200C}';

fn consonant(c: char) -> Option<&'static str> {
    Some(match c {
        'ب' => "b",
        'پ' => "p",
        'ت' | 'ٹ' | 'ط' => "t",
        'ث' | 'س' | 'ص' => "s",
        'ج' => "j",
        'چ' => "ch",
        'ح' => "h",
        'خ' => "kh",
        'د' | 'ڈ' => "d",
        'ذ' | 'ز' | 'ض' | 'ظ' => "z",
        'ر' | 'ڑ' => "r",
        'ژ' => "zh",
        'ش' => "sh",
        'غ' => "gh",
        'ف' => "f",
        'ق' => "q",
        'ک' => "k",
        'گ' => "g",
        'ل' => "l",
        'م' => "m",
        'ن' | 'ں' => "n",
        GOL_HE => "h",
        'ع' => "a",
        _ => return None,
    })
}

/// The Arabic spellings of letters Urdu writes its own way, folded to Urdu's.
fn fold(c: char) -> Option<char> {
    Some(match c {
        '\u{64A}' | '\u{649}' => YE,
        '\u{643}' => 'ک',
        '\u{647}' | '\u{6C3}' | '\u{6C2}' | '\u{629}' | '\u{6C0}' | '\u{FEE8}' => GOL_HE,
        '\u{623}' | '\u{625}' | '\u{671}' => ALIF,
        '\u{6D3}' => BARI_YE,
        _ => return None,
    })
}

/// Urdu letters and the marks that spell a word with them: not its digits
/// (۰–۹ and ٠–٩), and not the full stop ۔ or the comma ،.
pub fn is_urdu(c: char) -> bool {
    let u = c as u32;
    (0x0621..=0x063A).contains(&u)
        || (0x0640..=0x065F).contains(&u)
        || u == 0x0670
        || (0x0671..=0x06D3).contains(&u)
        || u == 0x06D5
        || (0x06E5..=0x06EF).contains(&u)
        || (0x06FA..=0x06FF).contains(&u)
}

fn is_diacritic(c: char) -> bool {
    let u = c as u32;
    (0x064B..=0x0652).contains(&u) || u == 0x0670
}

/// The Arabic letter shapes folded to the Urdu ones the word lists use, the
/// vowel diacritics dropped, and the joiners with them.
pub fn normalize(text: &str) -> String {
    if !text
        .chars()
        .any(|c| fold(c).is_some() || is_diacritic(c) || c == ZWJ || c == ZWNJ)
    {
        return text.to_string();
    }
    text.chars()
        .filter(|&c| !(is_diacritic(c) || c == ZWJ || c == ZWNJ))
        .map(|c| fold(c).unwrap_or(c))
        .collect()
}

/// Whole text: every Urdu run becomes Latin, everything else passes through.
pub fn romanize(text: &str) -> String {
    let norm: Vec<char> = normalize(text).chars().collect();
    let mut out = String::with_capacity(norm.len() * 2);
    let mut i = 0;
    while i < norm.len() {
        let c = norm[i];
        if is_urdu(c) {
            let mut j = i;
            while j < norm.len() && is_urdu(norm[j]) {
                j += 1;
            }
            out.push_str(&word(&norm[i..j]));
            i = j;
        } else {
            match c {
                '\u{6D4}' => out.push('.'),
                '\u{60C}' => out.push(','),
                '\u{61F}' => out.push('?'),
                '\u{61B}' => out.push(';'),
                '\u{6F0}'..='\u{6F9}' => out.push((b'0' + (c as u32 - 0x6F0) as u8) as char),
                '\u{660}'..='\u{669}' => out.push((b'0' + (c as u32 - 0x660) as u8) as char),
                c => out.push(c),
            }
            i += 1;
        }
    }
    out
}

/// One Urdu word, without spaces.
pub fn romanize_word(word_text: &str) -> String {
    let norm: Vec<char> = normalize(word_text).chars().collect();
    word(&norm)
}

const VOWEL_LETTERS: [char; 8] = [
    ALIF, MADDA, WAO, YE, BARI_YE, '\u{626}', '\u{624}', '\u{621}',
];

fn word(w: &[char]) -> String {
    let mut out = String::with_capacity(w.len() * 2);
    let mut consonants = 0;
    for (i, &c) in w.iter().enumerate() {
        let opening = i == 0;
        let last = i == w.len() - 1;
        let next = w.get(i + 1).copied();
        if c == DO_HE {
            // Aspiration belongs to the consonant in front of it, which is
            // already written: دیکھ is "dekh", not "dekhh".
            if !out.is_empty() && !out.ends_with('h') {
                out.push('h');
            }
        } else if c == MADDA {
            out.push_str("aa");
            consonants = 0;
        } else if c == ALIF {
            // Opening the word it is whatever short vowel was meant, and "a"
            // is the likeliest; inside it is the long one.
            out.push('a');
            consonants = 0;
        } else if c == WAO {
            // A consonant where it opens a word or follows another vowel
            // (وہ is "wo", ہوا is "hua"), the vowel everywhere else.
            out.push_str(if opening { "w" } else { "o" });
            consonants = 0;
        } else if c == YE {
            out.push_str(if opening { "y" } else { "i" });
            consonants = 0;
        } else if c == BARI_YE {
            out.push('e');
            consonants = 0;
        } else if c == '\u{626}' || c == '\u{621}' {
        } else if c == '\u{624}' {
            out.push('o');
            consonants = 0;
        } else if c == GOL_HE && last && consonants > 0 {
            // A closing gol he is the "a" of a Perso-Arabic noun: بچہ is
            // "bacha", روزہ is "roza".
            out.push('a');
            consonants = 0;
        } else {
            let Some(roman) = consonant(c) else { continue };
            // Two consonants with no vowel of any kind between them: Urdu
            // left a short one out and it has to come back. Not before the
            // word's last letter, where Urdu closes on a cluster ("mard").
            let next_is_vowel = next.is_some_and(|n| VOWEL_LETTERS.contains(&n) || n == DO_HE);
            if consonants > 0 && !last && !next_is_vowel {
                out.push('a');
            }
            out.push_str(roman);
            consonants += 1;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    //! Mirrors `UrduRomanizerTest.kt`.

    use super::{is_urdu, normalize, romanize, romanize_word as r};

    #[test]
    fn urdu_letters_are_word_characters_and_its_digits_are_not() {
        assert!(is_urdu('ک'));
        assert!(is_urdu('\u{6BE}'));
        assert!(is_urdu('\u{6D2}'));
        assert!(is_urdu('ں'));
        assert!(!is_urdu('a'));
        assert!(!is_urdu('\u{6D4}'));
        assert!(!is_urdu('\u{60C}'));
        assert!(!is_urdu('\u{6F5}'));
        assert!(!is_urdu('\u{669}'));
    }

    #[test]
    fn arabic_letter_shapes_fold_onto_the_urdu_ones() {
        assert_eq!("کیا", normalize("\u{643}\u{64A}ا"));
        assert_eq!("ہے", normalize("\u{647}ے"));
        assert_eq!("کتاب", normalize("ک\u{650}تاب"));
        assert_eq!("اللہ", normalize("الل\u{651}ہ"));
        assert_eq!("کرنا", normalize("کرنا"));
    }

    #[test]
    fn the_vowel_urdu_left_out_comes_back() {
        assert_eq!("karna", r("کرنا"));
        assert_eq!("bacha", r("بچہ"));
        assert_eq!("roza", r("روزہ"));
        assert_eq!("mard", r("مرد"));
        assert_eq!("dost", r("دوست"));
    }

    #[test]
    fn aspiration_folds_into_the_consonant_before_it() {
        assert_eq!("dikh", r("دیکھ"));
        assert_eq!("bhi", r("بھی"));
    }

    #[test]
    fn the_nasalisation_is_an_n() {
        assert_eq!("hin", r("ہیں"));
    }

    #[test]
    fn whole_text_keeps_what_is_not_urdu() {
        assert_eq!("aap kise hin", romanize("آپ کیسے ہیں"));
        assert_eq!("kam.", romanize("کام\u{6D4}"));
        assert_eq!("5 kam", romanize("\u{6F5} کام"));
        assert_eq!("ok karna", romanize("ok کرنا"));
    }
}

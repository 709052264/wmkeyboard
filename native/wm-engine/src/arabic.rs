//! Arabic phonetic: Arabizi in, Arabic script out ("7abibi", "3arabi",
//! "el7amdulillah" → حبيبي, عربي, الحمدلله) on the walk shared with Urdu and
//! Persian. A port of `ArabicPhonetic.kt`.
//!
//! What is Arabic's own:
//!
//! - its letters: ي ك ه ة ى, never the Urdu or Persian shapes;
//! - digits for the letters roman lacks: 3 ع, 3' غ, 5 خ, 6 ط, 6' ظ, 7 ح,
//!   8 ق, 9 ص, 9' ض, and 2 is ق (the glottal stop Egyptians and Levantines
//!   mean), with the real hamza words in the spelling map;
//! - the article: "al", "el" or "il" before a consonant writes ال, and an
//!   opening vowel before a doubled sun letter is the article assimilated;
//! - "g" is ج (Egyptian), "ch" is ش (Levantine and Maghrebi);
//! - a closing "a" is ا, with ة and ى offered beside it; a closing "e" is ة.

use std::sync::OnceLock;

use crate::arabic_script::{Engine, Table, Vowel};

pub fn table() -> Table {
    let mut table = Table::new(
        '\u{64A}',
        None,
        vec![
            ("3'", "غ"),
            ("6'", "ظ"),
            ("9'", "ض"),
            ("2", "ق"),
            ("3", "ع"),
            ("5", "خ"),
            ("6", "ط"),
            ("7", "ح"),
            ("8", "ق"),
            ("9", "ص"),
            ("kh", "خ"),
            ("gh", "غ"),
            ("sh", "ش"),
            ("ch", "ش"),
            ("th", "ث"),
            ("dh", "ذ"),
            ("b", "ب"),
            ("p", "ب"),
            ("t", "ت"),
            ("T", "ط"),
            ("s", "س"),
            ("S", "ص"),
            ("j", "ج"),
            ("g", "ج"),
            ("h", "ه"),
            ("H", "ح"),
            ("x", "خ"),
            ("d", "د"),
            ("D", "ض"),
            ("z", "ز"),
            ("Z", "ظ"),
            ("r", "ر"),
            ("f", "ف"),
            ("v", "ف"),
            ("q", "ق"),
            ("k", "ك"),
            ("l", "ل"),
            ("m", "م"),
            ("n", "ن"),
            ("w", "و"),
            ("y", "ي"),
            ("c", "ك"),
            ("'", "ع"),
        ],
        vec![
            Vowel::new("aa", "آ", "ا", "ا"),
            Vowel::new("A", "آ", "ا", "ا"),
            Vowel::new("a", "أ", "", "ا"),
            Vowel::new("ee", "اي", "ي", "ي"),
            Vowel::new("ii", "اي", "ي", "ي"),
            Vowel::new("i", "ا", "", "ي"),
            Vowel::new("ai", "اي", "ي", "ي"),
            Vowel::new("ei", "اي", "ي", "ي"),
            Vowel::new("oo", "او", "و", "و"),
            Vowel::new("uu", "او", "و", "و"),
            Vowel::new("u", "أ", "", "و"),
            Vowel::new("au", "او", "و", "و"),
            Vowel::new("ou", "او", "و", "و"),
            Vowel::new("e", "ا", "", "ة"),
            Vowel::new("o", "أ", "", "و"),
        ],
    );
    table.digits = "2356789";
    table.final_a_variants = vec!["ة", "ى", ""];
    table.article = vec!["al", "el", "il"];
    table.sun = vec![
        "t", "th", "d", "dh", "r", "z", "s", "sh", "9", "9'", "6", "6'", "l",
    ];
    table.long_vowels = vec![("i", "ي"), ("e", "ي"), ("u", "و"), ("o", "و")];
    table
}

fn engine() -> &'static Engine {
    static ENGINE: OnceLock<Engine> = OnceLock::new();
    ENGINE.get_or_init(|| Engine::new(table()))
}

/// Transliterates Arabizi, one word or free text, into the Arabic script.
pub fn transliterate(input: &str) -> String {
    engine().transliterate(input)
}

/// Every reading of `input` worth offering, the literal one first.
pub fn variants(input: &str) -> Vec<String> {
    engine().variants(input)
}

#[cfg(test)]
mod tests {
    //! Mirrors `ArabicPhoneticTest.kt`.

    use super::{transliterate as t, variants};

    #[test]
    fn arabizi() {
        assert_eq!("\u{642}\u{644}\u{628}\u{64A}", t("2albi"));
        assert_eq!("\u{648}\u{642}\u{62A}", t("wa2t"));
        assert_eq!("\u{639}\u{631}\u{628}\u{64A}", t("3arabi"));
        assert_eq!("\u{634}\u{63A}\u{644}", t("sho3'l"));
        assert_eq!("\u{636}\u{639}\u{64A}\u{641}", t("9'a3eef"));
        assert_eq!("\u{62E}\u{628}\u{632}", t("5obz"));
        assert_eq!("\u{62D}\u{644}\u{648}", t("7elw"));
        assert_eq!("\u{642}\u{645}\u{631}", t("8amar"));
        assert_eq!("\u{638}\u{647}\u{631}", t("6'ohr"));
        assert_eq!("\u{634}\u{648}", t("chou"));
        assert_eq!("\u{62C}\u{62F}\u{64A}\u{62F}", t("gdeed"));
        assert_eq!("\u{643}\u{648}\u{64A}\u{633}", t("kwayyes"));
        assert_eq!("\u{645}\u{62F}\u{631}\u{633}\u{629}", t("madrase"));
        assert_eq!("\u{627}\u{644}\u{62E}\u{64A}\u{631}", t("el5eir"));
        assert_eq!("\u{627}\u{644}\u{64A}\u{648}\u{645}", t("elyoum"));
        assert_eq!("\u{623}\u{646}\u{627}", t("ana"));
        assert_eq!("\u{623}\u{62E}\u{62A}\u{64A}", t("ukhti"));
        assert_eq!("\u{627}\u{644}\u{633}\u{644}\u{645}", t("essalam"));
        assert_eq!(
            "\u{627}\u{644}\u{62D}\u{645}\u{62F}\u{644}\u{644}\u{647}",
            t("el7amdulillah")
        );
        assert_eq!("\u{643}\u{64A}\u{641}", t("kayf"));
        assert_eq!("2024", t("2024"));
        assert_eq!("\u{639}\u{644}\u{627} 2000", t("3ala 2000"));
    }

    #[test]
    fn a_number_stays_a_number() {
        assert_eq!("2024", t("2024"));
        assert_eq!("3 7", t("3 7"));
    }

    #[test]
    fn the_article() {
        assert!(t("elyoum").starts_with("\u{627}\u{644}"));
        assert!(t("essalam").starts_with("\u{627}\u{644}\u{633}"));
        assert_eq!("\u{623}\u{644}\u{64A}", t("ali"));
    }

    #[test]
    fn the_closing_a_is_offered_as_ta_marbuta_and_alif_maqsura() {
        assert_eq!(
            vec![
                "\u{633}\u{639}\u{627}",
                "\u{633}\u{627}\u{639}\u{627}",
                "\u{633}\u{639}\u{629}",
                "\u{633}\u{639}\u{649}",
                "\u{633}\u{639}",
                "\u{633}\u{627}\u{639}\u{629}",
                "\u{633}\u{627}\u{639}\u{649}",
                "\u{633}\u{627}\u{639}",
            ],
            variants("sa3a"),
        );
    }
}

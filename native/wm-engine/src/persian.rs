//! Persian phonetic: Finglish in, Persian script out ("salam khoobi?" →
//! سلام خوبی؟) on the walk shared with Urdu and Arabic. A port of
//! `PersianPhonetic.kt`.
//!
//! What is Persian's own:
//!
//! - its letters: ی ک ه, never the Arabic ي ك or the Urdu ہ; no aspirates,
//!   no retroflexes. "kh" and "x" are خ, "ch" چ, "zh" ژ;
//! - short a, e and o are not written inside a word; a closing "e" is the
//!   silent ه; "i" is always long, ی;
//! - "gh" is ق and "Gh" is غ; "ci" and "ce" are سی and س;
//! - a vowel after a vowel sits on a plain ی or و, not a hamza seat;
//! - the verb prefixes می‌ and نمی‌ are their own unit, joined to the stem by
//!   a ZWNJ. A stem shorter than two tokens keeps its "mi" (میز).

use std::sync::OnceLock;

use crate::arabic_script::{Engine, Table, Vowel};

pub fn table() -> Table {
    let mut table = Table::new(
        '\u{6CC}',
        None,
        vec![
            ("kho", "خو"),
            ("kh", "خ"),
            ("x", "خ"),
            ("gh", "ق"),
            ("Gh", "غ"),
            ("q", "ق"),
            ("ch", "چ"),
            ("sh", "ش"),
            ("zh", "ژ"),
            ("b", "ب"),
            ("p", "پ"),
            ("t", "ت"),
            ("T", "ط"),
            ("s", "س"),
            ("S", "ص"),
            ("j", "ج"),
            ("h", "ه"),
            ("H", "ح"),
            ("d", "د"),
            ("z", "ز"),
            ("Z", "ض"),
            ("r", "ر"),
            ("f", "ف"),
            ("k", "ک"),
            ("g", "گ"),
            ("l", "ل"),
            ("m", "م"),
            ("n", "ن"),
            ("v", "و"),
            ("w", "و"),
            ("y", "ی"),
            ("ci", "سی"),
            ("ce", "س"),
            ("c", "ک"),
            ("'", "ع"),
        ],
        vec![
            Vowel::new("aa", "آ", "ا", "ا"),
            Vowel::new("A", "آ", "ا", "ا"),
            Vowel::new("a", "ا", "", "ا"),
            Vowel::new("ee", "ای", "ی", "ی"),
            Vowel::new("ii", "ای", "ی", "ی"),
            Vowel::new("i", "ای", "ی", "ی"),
            Vowel::new("iy", "ای", "ی", "ی"),
            Vowel::new("ei", "ای", "ی", "ی"),
            Vowel::new("ey", "ای", "ی", "ی"),
            Vowel::new("ai", "ای", "ای", "ایی"),
            Vowel::new("aei", "ای", "ای", "ایی"),
            Vowel::new("oo", "او", "و", "و"),
            Vowel::new("uu", "او", "و", "و"),
            Vowel::new("u", "او", "و", "و"),
            Vowel::new("ou", "او", "و", "و"),
            Vowel::new("ow", "او", "و", "و"),
            Vowel::new("au", "او", "و", "و"),
            Vowel::new("e", "ا", "", "ه"),
            Vowel::new("o", "ا", "", "و"),
        ],
    );
    table.hamza_ye = '\u{6CC}';
    table.hamza_wao = '\u{648}';
    table.prefixes = vec![("nemi", "نمی\u{200C}"), ("mi", "می\u{200C}")];
    table.prefix_min_stem = 2;
    table
}

fn engine() -> &'static Engine {
    static ENGINE: OnceLock<Engine> = OnceLock::new();
    ENGINE.get_or_init(|| Engine::new(table()))
}

/// Transliterates Finglish, one word or free text, into the Persian script.
pub fn transliterate(input: &str) -> String {
    engine().transliterate(input)
}

/// Every reading of `input` worth offering, the literal one first.
pub fn variants(input: &str) -> Vec<String> {
    engine().variants(input)
}

#[cfg(test)]
mod tests {
    //! Mirrors `PersianPhoneticTest.kt`.

    use super::{transliterate as t, variants};

    #[test]
    fn finglish() {
        assert_eq!("\u{62E}\u{648}\u{628}\u{647}", t("khoobe"));
        assert_eq!("\u{62F}\u{6CC}\u{6AF}\u{647}", t("dige"));
        assert_eq!("\u{62E}\u{6CC}\u{644}\u{6CC}", t("kheili"));
        assert_eq!("\u{6A9}\u{62C}\u{627}\u{6CC}\u{6CC}", t("kojai"));
        assert_eq!("\u{686}\u{6CC}\u{647}", t("chiye"));
        assert_eq!("\u{645}\u{631}\u{633}\u{6CC}", t("merci"));
        assert_eq!("\u{633}\u{6CC}\u{646}\u{645}\u{627}", t("cinema"));
        assert_eq!("\u{62E}\u{648}\u{62F}\u{645}", t("khodam"));
        assert_eq!("\u{62E}\u{648}\u{634}\u{6AF}\u{644}", t("khoshgel"));
        assert_eq!("\u{642}\u{634}\u{646}\u{6AF}", t("ghashang"));
        assert_eq!("\u{63A}\u{632}\u{627}", t("Ghaza"));
        assert_eq!("\u{627}\u{645}\u{631}\u{648}\u{632}", t("emrooz"));
        assert_eq!("\u{627}\u{648}\u{646}", t("oon"));
        assert_eq!("\u{628}\u{686}\u{647}", t("bache"));
        assert_eq!("\u{647}\u{645}\u{6CC}\u{634}\u{647}", t("hamishe"));
        assert_eq!("\u{645}\u{6CC}\u{200C}\u{6A9}\u{646}\u{645}", t("mikonam"));
        assert_eq!(
            "\u{646}\u{645}\u{6CC}\u{200C}\u{62A}\u{648}\u{646}\u{645}",
            t("nemitoonam")
        );
        assert_eq!("\u{645}\u{6CC}\u{200C}\u{627}\u{62F}", t("miad"));
        assert_eq!("\u{645}\u{6CC}\u{632}", t("miz"));
        assert_eq!(
            "\u{633}\u{644}\u{645} \u{62E}\u{648}\u{628}\u{6CC}",
            t("salam khoobi")
        );
    }

    #[test]
    fn the_verb_prefix_is_its_own_unit() {
        assert_eq!("\u{645}\u{6CC}\u{200C}\u{6A9}\u{646}\u{645}", t("mikonam"));
        assert_eq!("\u{645}\u{6CC}\u{632}", t("miz"));
        assert_eq!(
            vec![
                "\u{645}\u{6CC}\u{200C}\u{648}\u{647}",
                "\u{645}\u{6CC}\u{648}\u{647}"
            ],
            variants("mive"),
        );
    }

    #[test]
    fn persian_letters_not_arabic_or_urdu() {
        let out = t("ketab ke khane hast");
        assert!(
            !out.chars()
                .any(|c| c == '\u{64A}' || c == '\u{643}' || c == '\u{6C1}'),
            "{out}"
        );
    }

    #[test]
    fn the_long_a_is_a_variant() {
        assert_eq!(
            vec!["\u{6A9}\u{62A}\u{628}", "\u{6A9}\u{62A}\u{627}\u{628}"],
            variants("ketab")
        );
    }
}

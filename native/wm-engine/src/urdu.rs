//! Rule-based phonetic transliteration from roman Urdu to the Urdu script:
//! "aap kaise hain" → آپ کیسے ہیں. A port of `UrduPhonetic.kt`; the rules
//! the table encodes, in short:
//!
//! 1. Short vowels are not written: "din" is دن, "kitab" is کتاب.
//! 2. A word-final vowel is long: final "a" is ا, "i" is ی, "u" and "o" are
//!    و, "e" and "ai" are ے.
//! 3. A word-initial vowel needs a carrier: a / i / u open on ا, "aa" on آ,
//!    e / ai on ای, o / au / oo on او.
//! 4. A vowel running into a vowel takes a hamza: کوئی, جاؤ.
//! 5. Aspiration is do-chashmi he (بھ تھ کھ...), retroflexes are capitals
//!    (T D R → ٹ ڈ ڑ).
//! 6. "kh" opening a word is خ, not کھ; "Kh" is خ anywhere; "gh" stays گھ
//!    and "Gh" is غ.
//! 7. A doubled consonant is written once.
//!
//! What the rules cannot know (a lone inner "a", ت against ٹ, ن against ں)
//! is the spelling map's and the word list's job; [`variants`] is the
//! fallback for someone with neither.

use std::sync::OnceLock;

use crate::arabic_script::{Engine, Table, Vowel, ALIF, MADDA, WAO};

const YE: char = '\u{6CC}';
const BARI_YE: char = '\u{6D2}';
const HAMZA_YE: char = '\u{626}';
const KHE: char = '\u{62E}';

/// Urdu's letters, read by the walk every Arabic-script language shares.
pub fn table() -> Table {
    // ے takes a hamza after a vowel (گئے, آئے), as ی does.
    let mut table = Table::new(
        YE,
        Some(BARI_YE),
        vec![
            // Aspirated digraphs. "nh", "mh", "lh" and "rh" are deliberately
            // absent: نھ is vanishingly rare next to the "nh" of "nhi"
            // (نہیں), where the h is the gol he of a whole syllable.
            ("bh", "بھ"),
            ("ph", "پھ"),
            ("th", "تھ"),
            ("Th", "ٹھ"),
            ("jh", "جھ"),
            ("chh", "چھ"),
            // "accha" doubles the c the way "pakka" doubles the k, and what
            // it means is the aspirate: اچھا, which is the word.
            ("cchh", "چھ"),
            ("cch", "چھ"),
            ("ch", "چ"),
            ("dh", "دھ"),
            ("Dh", "ڈھ"),
            ("kh", "کھ"),
            ("Kh", "خ"),
            ("gh", "گھ"),
            ("Gh", "غ"),
            ("Rh", "ڑھ"),
            ("sh", "ش"),
            ("zh", "ژ"),
            ("b", "ب"),
            ("p", "پ"),
            ("t", "ت"),
            ("T", "ٹ"),
            ("s", "س"),
            ("S", "ص"),
            ("j", "ج"),
            ("h", "ہ"),
            ("H", "ح"),
            ("d", "د"),
            ("D", "ڈ"),
            ("z", "ز"),
            ("Z", "ض"),
            ("r", "ر"),
            ("R", "ڑ"),
            ("f", "ف"),
            ("q", "ق"),
            ("k", "ک"),
            ("g", "گ"),
            ("l", "ل"),
            ("m", "م"),
            ("n", "ن"),
            // The nasalisation the rules never guess at (جان against ہیں),
            // typed deliberately.
            ("N", "ں"),
            ("v", "و"),
            ("w", "و"),
            ("y", "ی"),
            ("x", "کس"),
            ("c", "ک"),
            // The ain of عرض and دعا: no roman letter stands for it, so the
            // apostrophe does.
            ("'", "ع"),
        ],
        vec![
            Vowel::new("aa", "آ", "ا", "ا"),
            Vowel::new("A", "آ", "ا", "ا"),
            Vowel::new("a", "ا", "", "ا"),
            Vowel::new("ee", "ای", "ی", "ی"),
            Vowel::new("ii", "ای", "ی", "ی"),
            Vowel::new("I", "ای", "ی", "ی"),
            Vowel::new("ai", "ای", "ی", "ے"),
            Vowel::new("i", "ا", "", "ی"),
            Vowel::new("oo", "او", "و", "و"),
            Vowel::new("uu", "او", "و", "و"),
            Vowel::new("U", "او", "و", "و"),
            // "hua" is ہوا and "hui" ہوئی: a short u running into another
            // vowel is the long و after all, because there is nothing else
            // for the second vowel to hang off.
            Vowel::new("ua", "اوا", "وا", "وا"),
            Vowel::new("ui", "اوئی", "وئی", "وئی"),
            Vowel::new("u", "ا", "", "و"),
            Vowel::new("au", "او", "و", "و"),
            Vowel::new("ou", "او", "و", "و"),
            Vowel::new("ay", "ای", "ی", "ے"),
            Vowel::new("ey", "ای", "ی", "ے"),
            Vowel::new("e", "ای", "ی", "ے"),
            Vowel::new("o", "او", "و", "و"),
        ],
    );
    // Rule 6.
    table.kh_initial = Some(KHE);
    table
}

fn engine() -> &'static Engine {
    static ENGINE: OnceLock<Engine> = OnceLock::new();
    ENGINE.get_or_init(|| Engine::new(table()))
}

/// Transliterates roman Urdu, one word or free text, into the Urdu script.
pub fn transliterate(input: &str) -> String {
    engine().transliterate(input)
}

/// Every reading of `input` worth offering, the literal one first. Only the
/// "a" moves: whether a lone "a" inside a word is a long ا or nothing at all.
pub fn variants(input: &str) -> Vec<String> {
    engine().variants(input)
}

#[allow(dead_code)]
const _USED: (char, char, char, char) = (ALIF, MADDA, WAO, HAMZA_YE);

#[cfg(test)]
mod tests {
    //! Mirrors `UrduPhoneticTest.kt`.

    use super::{transliterate as t, variants};

    #[test]
    fn whole_sentence() {
        assert_eq!("آپ کیسے ہو?", t("aap kaise ho?"));
    }

    #[test]
    fn short_vowels_are_not_written() {
        assert_eq!("دن", t("din"));
        assert_eq!("کل", t("kal"));
        assert_eq!("تم", t("tum"));
        assert_eq!("ہم", t("hum"));
        assert_eq!("مرد", t("mard"));
        assert_eq!("سچ", t("sach"));
        assert_eq!("دوست", t("dost"));
    }

    #[test]
    fn a_word_final_vowel_is_long() {
        assert_eq!("کا", t("ka"));
        assert_eq!("کی", t("ki"));
        assert_eq!("کے", t("ke"));
        assert_eq!("ہے", t("hai"));
        assert_eq!("تو", t("to"));
        assert_eq!("کرنا", t("karna"));
        assert_eq!("میرا", t("mera"));
        assert_eq!("کیسے", t("kaise"));
    }

    #[test]
    fn a_word_initial_vowel_takes_its_carrier() {
        assert_eq!("اب", t("ab"));
        assert_eq!("آپ", t("aap"));
        assert_eq!("ایک", t("ek"));
        assert_eq!("اور", t("aur"));
        assert_eq!("آج", t("aaj"));
    }

    #[test]
    fn a_vowel_on_a_vowel_takes_a_hamza() {
        assert_eq!("کوئی", t("koi"));
        assert_eq!("ہوا", t("hua"));
        assert_eq!("جاؤ", t("jaao"));
        assert_eq!("آؤ", t("aao"));
    }

    #[test]
    fn a_consonantal_waw_or_ye_carries_nothing() {
        assert_eq!("یہ", t("yeh"));
        assert_eq!("وہ", t("woh"));
        assert_eq!("کیا", t("kya"));
        assert_eq!("لیا", t("liya"));
        assert_eq!("نیا", t("naya"));
    }

    #[test]
    fn aspiration_is_do_chashmi_he() {
        assert_eq!("دیکھ", t("dekh"));
        assert_eq!("لکھ", t("likh"));
        assert_eq!("پھر", t("phir"));
        assert_eq!("گھر", t("ghar"));
        assert_eq!("تھیک", t("theek"));
        assert_eq!("جھوت", t("jhoot"));
        assert_eq!("سمجھ", t("samajh"));
        assert_eq!("چھوتا", t("chhota"));
    }

    #[test]
    fn capitals_spell_the_retroflexes_and_the_arabic_letters() {
        assert_eq!("ٹمتر", t("Tamatar"));
        assert_eq!("ڈڈا", t("DaDa"));
        assert_eq!("کڑا", t("kaRa"));
        assert_eq!("ٹھیک", t("Theek"));
        assert_eq!("خبر", t("Khabar"));
        assert_eq!("غلٹ", t("GhalaT"));
        assert_eq!("صبح", t("SubH"));
        assert_eq!("ضرور", t("Zaroor"));
    }

    #[test]
    fn kh_opening_a_word_is_khe() {
        assert_eq!("خش", t("khush"));
        assert_eq!("خبر", t("khabar"));
        assert_eq!("دیکھو", t("dekho"));
        assert_eq!("انکھ", t("ankh"));
        assert_eq!("آخر", t("aaKhir"));
    }

    #[test]
    fn a_doubled_consonant_is_written_once() {
        assert_eq!("سنا", t("sunna"));
        assert_eq!("بنا", t("banna"));
        assert_eq!("اس", t("iss"));
        assert_eq!("پکا", t("pakka"));
        assert_eq!("اچھا", t("accha"));
        assert_eq!("اچا", t("acha"));
    }

    #[test]
    fn capitals_that_spell_nothing_read_as_their_lowercase_selves() {
        assert_eq!(t("kitab"), t("Kitab"));
        assert_eq!(t("mera"), t("Mera"));
        assert_eq!(t("log"), t("Log"));
    }

    #[test]
    fn the_long_a_is_offered_as_a_variant() {
        assert_eq!("کتب", t("kitab"));
        assert!(variants("kitab").iter().any(|v| v == "کتاب"));
        assert_eq!("پنی", t("pani"));
        assert!(variants("pani").iter().any(|v| v == "پانی"));
        assert!(variants("insan").iter().any(|v| v == "انسان"));
        assert_eq!(t("kitab"), variants("kitab")[0]);
        assert_eq!(vec!["دوست".to_string()], variants("dost"));
    }

    #[test]
    fn non_latin_passes_through() {
        assert_eq!("٭ 123 ٭", t("٭ 123 ٭"));
        assert_eq!("کام 24/7", t("kaam 24/7"));
    }

    #[test]
    fn apostrophe_is_ain() {
        assert_eq!("سعت", t("sa'at"));
    }
}

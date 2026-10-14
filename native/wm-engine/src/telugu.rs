//! Telugu phonetic typing on the Indic engine: the Explicit model, short and
//! long e and o, and a virama on a consonant that closes the word. A port of
//! `TeluguPhonetic.kt`; the hook's rules:
//!
//! 0. "ru" straight after a consonant is the vowel ృ, except in -rudu.
//! 1. e and o after a consonant are long in an open syllable and at the end,
//!    short before a cluster or a nasal, long before an r- or s- cluster.
//! 2. m after a vowel is the anusvara when it closes the word or comes
//!    before a consonant other than m, y or r.
//! 3. A word-final t or d after a vowel is retroflex (not -lēdu).
//! 4. n+t before i, u, e or a long vowel is ṇṭ.
//! 5. sh+t is ష్ట.
//! 6. The adverb suffix "ga" is long.
//! 7. The polite "-andi" is ండి.

use std::sync::OnceLock;

use crate::indic::{Engine, Model, Profile, Rule};
use crate::indic_script::IndicScript;

const SSA: &str = "\u{937}";
/// Rule 1: the consonants that open a cluster a long e or o still sits before.
const LONG_BEFORE_CLUSTER: &[&str] = &["r", "s"];
/// Rule 2: the consonants an m stays a consonant before.
const M_KEPT_BEFORE: &[&str] = &["m", "y", "r"];
/// Rule 3: the long and short e that make a closing "du" the negative -lēdu.
const E_VOWELS: &[&str] = &["e", "E"];
/// Rule 4: the vowels after which n+t is ṇṭ.
const NT_RETROFLEX: &[&str] = &["i", "I", "u", "U", "uu", "oo", "e", "E", "ae", "aa", "A"];

/// Rule 2's anusvara, standing in for the "m" it replaces.
fn anusvara() -> Rule {
    Rule::sign("m", "\u{902}")
}

/// The retroflex reading of a plain t or d: ट, ड.
fn retroflex(m: &str) -> Option<&'static str> {
    match m {
        "t" => Some("\u{91F}"),
        "d" => Some("\u{921}"),
        _ => None,
    }
}

/// Rule 1: the long vowel a short e or o becomes.
fn long_of(m: &str) -> Option<&'static str> {
    match m {
        "e" => Some("E"),
        "o" => Some("O"),
        _ => None,
    }
}

pub fn profile() -> Profile {
    let mut p = Profile::new("te", IndicScript::Telugu, Model::Explicit);
    p.dravidian_vowels = true;
    p.extra_consonants = vec![
        ("L", "\u{933}"),
        ("R", "\u{931}"),
        ("chh", "\u{91A}\u{94D}\u{91A}"),
        ("thh", "\u{925}"),
        ("dhh", "\u{927}"),
    ];
    p.override_ = vec![("th", "\u{924}"), ("dh", "\u{926}")];
    p.final_virama = true;
    p.word_hook = Some(hook);
    p
}

pub fn engine() -> &'static Engine {
    static ENGINE: OnceLock<Engine> = OnceLock::new();
    ENGINE.get_or_init(|| Engine::new(profile()))
}

pub fn transliterate(input: &str) -> String {
    engine().transliterate(input)
}

pub fn variants(input: &str) -> Vec<String> {
    engine().variants(input)
}

fn hook(engine: &Engine, tokens: Vec<Rule>) -> Vec<Rule> {
    let mut t = vocalic_r(engine, &tokens);
    let n = t.len();
    let vowels = t.iter().filter(|r| r.is_vowel()).count();
    for i in 0..n {
        let is_vowel = |j: isize| j >= 0 && (j as usize) < n && t[j as usize].is_vowel();
        let is_consonant = |j: isize| j >= 0 && (j as usize) < n && t[j as usize].is_consonant();
        let m = t[i].m.clone();
        let i_ = i as isize;
        // 1. e/o long in an open syllable, at the end, or before an r- or s- cluster.
        if long_of(&m).is_some()
            && is_consonant(i_ - 1)
            && (i == n - 1
                || (is_consonant(i_ + 1) && is_vowel(i_ + 2))
                || (is_consonant(i_ + 1)
                    && is_consonant(i_ + 2)
                    && LONG_BEFORE_CLUSTER.contains(&t[i + 1].m.as_str())))
        {
            t[i] = engine.rule(long_of(&m).unwrap());
        }
        // 2. m after a vowel, closing the word or before most consonants: ం.
        else if m == "m"
            && is_vowel(i_ - 1)
            && (i == n - 1
                || (is_consonant(i_ + 1) && !M_KEPT_BEFORE.contains(&t[i + 1].m.as_str())))
        {
            t[i] = anusvara();
        }
        // 3. A closing t/d before u, after a vowel, is retroflex, but not -lēdu.
        // 4. n+t before i/u/e or a long vowel is ṇṭ.
        else if (retroflex(&m).is_some()
            && is_vowel(i_ - 1)
            && i + 2 == n
            && t[i + 1].m == "u"
            && !(m == "d" && E_VOWELS.contains(&t[i - 1].m.as_str())))
            || (m == "t"
                && i >= 1
                && t[i - 1].m == "n"
                && is_vowel(i_ + 1)
                && NT_RETROFLEX.contains(&t[i + 1].m.as_str()))
        {
            t[i] = Rule::consonant(&m, retroflex(&m).unwrap());
        }
        // 5. sh+t is ష్ట.
        else if m == "sh" && i + 1 < n && t[i + 1].m == "t" {
            t[i] = Rule::consonant("sh", SSA);
            t[i + 1] = Rule::consonant("t", retroflex("t").unwrap());
        }
        // 6. The adverb suffix "ga" is long.
        else if m == "g" && i + 2 == n && t[i + 1].is_schwa() && vowels >= 2 {
            t[i + 1] = engine.rule("aa");
        }
        // 7. "-andi" is ండి, not after m and not with fewer than three vowels.
        else if m == "d"
            && i + 2 == n
            && t[i + 1].m == "i"
            && i >= 3
            && t[i - 1].m == "n"
            && t[i - 2].is_schwa()
            && vowels >= 3
            && t[i - 3].m != "m"
        {
            t[i] = Rule::consonant(&m, retroflex(&m).unwrap());
        }
    }
    t
}

/// Rule 0: an "r" then "u" straight after a single consonant becomes the
/// vowel ృ, except in a closing "-rudu" (the agent noun keeps ్రు). Reads
/// the original tokens for every test and returns a fresh list.
fn vocalic_r(engine: &Engine, tokens: &[Rule]) -> Vec<Rule> {
    let n = tokens.len();
    let mut out = Vec::with_capacity(n);
    let mut k = 0;
    while k < n {
        if k >= 1
            && tokens[k - 1].is_consonant()
            && (k < 2 || !tokens[k - 2].is_consonant())
            && tokens[k].m == "r"
            && k + 1 < n
            && tokens[k + 1].m == "u"
            && !(k + 3 + 1 == n && tokens[k + 2].m == "d")
        {
            out.push(engine.rule("Ru"));
            k += 2;
            continue;
        }
        out.push(tokens[k].clone());
        k += 1;
    }
    out
}

#[cfg(test)]
mod tests {
    //! Mirrors `TeluguPhoneticTest.kt`.

    use super::{transliterate as t, variants};

    #[test]
    fn plain_words() {
        assert_eq!("\u{C05}\u{C2E}\u{C4D}\u{C2E}", t("amma"));
        assert_eq!("\u{C2E}\u{C40}\u{C30}\u{C41}", t("meeru"));
        assert_eq!("\u{C0E}\u{C32}", t("ela"));
        assert_eq!("\u{C2E}\u{C40}\u{C30}\u{C41} \u{C0E}\u{C32} \u{C09}\u{C28}\u{C4D}\u{C28}\u{C30}\u{C41}?", t("meeru ela unnaru?"));
    }

    #[test]
    fn chat_spellings() {
        assert_eq!(
            "\u{C24}\u{C2E}\u{C4D}\u{C2E}\u{C41}\u{C21}\u{C41}",
            t("thammudu")
        );
        assert_eq!("\u{C07}\u{C26}\u{C3F}", t("idhi"));
        assert_eq!("\u{C15}\u{C25}", t("kathha"));
        assert_eq!(
            "\u{C35}\u{C1A}\u{C4D}\u{C1A}\u{C3F}\u{C02}\u{C26}\u{C3F}",
            t("vachhindi")
        );
    }

    #[test]
    fn ru_after_a_consonant_is_vocalic_r() {
        assert_eq!("\u{C15}\u{C43}\u{C36}\u{C3F}", t("krushi"));
        assert_eq!(
            "\u{C05}\u{C2D}\u{C3F}\u{C35}\u{C43}\u{C26}\u{C4D}\u{C26}\u{C3F}",
            t("abhivruddhi")
        );
        assert_eq!(
            "\u{C1A}\u{C02}\u{C26}\u{C4D}\u{C30}\u{C41}\u{C21}\u{C41}",
            t("chandrudu")
        );
        assert_eq!(
            "\u{C2E}\u{C3F}\u{C24}\u{C4D}\u{C30}\u{C41}\u{C21}\u{C41}",
            t("mitrudu")
        );
    }

    #[test]
    fn e_and_o_are_long_in_an_open_syllable() {
        assert_eq!("\u{C28}\u{C47}\u{C28}\u{C41}", t("nenu"));
        assert_eq!("\u{C32}\u{C4B}", t("lo"));
        assert_eq!("\u{C1A}\u{C46}\u{C2A}\u{C4D}\u{C2A}\u{C41}", t("cheppu"));
        assert_eq!("\u{C15}\u{C4A}\u{C02}\u{C1A}\u{C46}\u{C02}", t("konchem"));
        assert_eq!(
            "\u{C1A}\u{C47}\u{C38}\u{C4D}\u{C24}\u{C41}\u{C28}\u{C4D}\u{C28}",
            t("chesthunna")
        );
        assert_eq!("\u{C1A}\u{C47}\u{C30}\u{C4D}\u{C2A}\u{C41}", t("cherpu"));
        assert_eq!("\u{C2A}\u{C4B}\u{C38}\u{C4D}\u{C24}\u{C4D}", t("post"));
    }

    #[test]
    fn m_after_a_vowel_is_the_anusvara() {
        assert_eq!("\u{C2E}\u{C28}\u{C02}", t("manam"));
        assert_eq!(
            "\u{C38}\u{C02}\u{C35}\u{C24}\u{C4D}\u{C38}\u{C30}\u{C02}",
            t("samvatsaram")
        );
        assert_eq!(
            "\u{C38}\u{C2E}\u{C4D}\u{C30}\u{C3E}\u{C1C}\u{C4D}\u{C2F}\u{C02}",
            t("samraajyam")
        );
    }

    #[test]
    fn a_closing_t_or_d_is_retroflex() {
        assert_eq!("\u{C35}\u{C3E}\u{C21}\u{C41}", t("vaadu"));
        assert_eq!(
            "\u{C0E}\u{C2A}\u{C4D}\u{C2A}\u{C41}\u{C21}\u{C41}",
            t("eppudu")
        );
        assert_eq!("\u{C32}\u{C47}\u{C26}\u{C41}", t("ledu"));
    }

    #[test]
    fn nt_before_i_u_e_is_retroflex() {
        assert_eq!("\u{C07}\u{C02}\u{C1F}\u{C3F}", t("inti"));
        assert_eq!("\u{C05}\u{C02}\u{C1F}\u{C47}", t("ante"));
        assert_eq!("\u{C05}\u{C02}\u{C24}", t("antha"));
    }

    #[test]
    fn sht_is_retroflex() {
        assert_eq!("\u{C07}\u{C37}\u{C4D}\u{C1F}\u{C02}", t("ishtam"));
        assert_eq!("\u{C26}\u{C43}\u{C37}\u{C4D}\u{C1F}\u{C3F}", t("drushti"));
    }

    #[test]
    fn the_adverb_suffix_ga_is_long() {
        assert_eq!("\u{C24}\u{C4D}\u{C35}\u{C30}\u{C17}\u{C3E}", t("twaraga"));
        assert_eq!("\u{C2C}\u{C17}\u{C3E}", t("baga"));
        assert_eq!("\u{C17}", t("ga"));
    }

    #[test]
    fn andi_is_retroflex() {
        assert_eq!(
            "\u{C1A}\u{C46}\u{C2A}\u{C4D}\u{C2A}\u{C02}\u{C21}\u{C3F}",
            t("cheppandi")
        );
        assert_eq!("\u{C1A}\u{C41}\u{C26}\u{C02}\u{C21}\u{C3F}", t("chudandi"));
        assert_eq!("\u{C30}\u{C02}\u{C26}\u{C3F}", t("randi"));
        assert_eq!(
            "\u{C15}\u{C4A}\u{C02}\u{C24}\u{C2E}\u{C02}\u{C26}\u{C3F}",
            t("konthamandi")
        );
    }

    #[test]
    fn variants_offer_the_closing_a_long() {
        assert_eq!(
            vec![
                "\u{C05}\u{C2E}\u{C4D}\u{C2E}",
                "\u{C05}\u{C2E}\u{C4D}\u{C2E}\u{C3E}"
            ],
            variants("amma")
        );
    }
}

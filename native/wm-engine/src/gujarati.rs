//! Gujarati phonetic: roman letters in, Gujarati script out, on the Indic
//! engine's North model. A port of `GujaratiPhonetic.kt`; the hook's rules:
//!
//! 1. A final "-u" is the neuter -ું ("hu" હું, "karvu" કરવું); "-au" is -ાઉં.
//! 2. The locative is always nasal: "-ma", "-mathi" are -માં, -માંથી.
//! 3. The oblique infinitive is "-vaa-": "karvanu" કરવાનું.
//! 4. "vv" before the infinitive suffix is two syllables: "aavvu" આવવું.
//! 5. A one-syllable "Cai" is C + ઈ ("gai" ગઈ); a longer word keeps ઐ.
//! 6. "i" after a vowel, or before -ne, -sh, -shu, -e, -ye, is ઈ/ી; a closing
//!    "-iye" is -ઈએ.
//! 7. A Sanskrit "-tra", "-dra", "-shra" noun ends in the consonant.

use std::sync::OnceLock;

use crate::indic::hooks::rest_lower;
use crate::indic::{Engine, Model, Profile, Rule};
use crate::indic_script::IndicScript;

const LOCATIVE: &[&str] = &["ma", "maa", "mathi", "maathi"];
const OBLIQUE_INF: &[&str] = &[
    "vanu", "vani", "vano", "vana", "vama", "vamathi", "vathi", "vaa",
];
const INFINITIVE_ONLY: &[&str] = &["vu", "va", "vi"];
const AI_SUFFIX: &[&str] = &["", "sh", "shu", "ne", "e", "ye"];
const I_SUFFIX: &[&str] = &["ne", "sh", "shu", "e", "ye"];
/// त द श ष: the consonants a closing "-ra" leans on in rule 7.
const TRA_HEADS: &[&str] = &["\u{924}", "\u{926}", "\u{936}", "\u{937}"];
const RA: &str = "\u{930}";

/// ઉં / ું: the neuter ending (rule 1).
fn u_nasal() -> Rule {
    Rule::vowel("u", "\u{909}\u{902}", "\u{941}\u{902}")
}
/// આં / ાં: the locative's vowel (rule 2).
fn aa_nasal_ma() -> Rule {
    Rule::vowel("a", "\u{906}\u{902}", "\u{93E}\u{902}")
}
/// ઈ / ી, matched as "i" (rules 5 and 6).
fn i_long() -> Rule {
    Rule::vowel("i", "\u{908}", "\u{940}")
}
/// The inherent vowel with no long form, so it is never lengthened.
fn schwa_flat() -> Rule {
    Rule::vowel("a", "\u{905}", "")
}
/// એ / ે, standing for the "ye" after ઈ (rule 6).
fn e_full() -> Rule {
    Rule::vowel("ye", "\u{90F}", "\u{947}")
}

pub fn profile() -> Profile {
    let mut p = Profile::new("gu", IndicScript::Gujarati, Model::North);
    p.extra_consonants = vec![("L", "\u{933}"), ("R", "\u{921}"), ("Rh", "\u{922}")];
    p.override_ = vec![("z", "\u{91D}"), ("x", "\u{915}\u{94D}\u{937}")];
    p.endings = vec![
        "ma", "maa", "maathi", "mate", "mathi", "na", "ne", "ni", "no", "nu", "par", "sathe",
        "thi", "va", "vama", "vamathi", "vana", "vani", "vano", "vanu", "vathi", "vi", "vu",
    ];
    p.soft_endings = vec!["she", "shu", "su", "ta", "te", "ti", "to", "tu"];
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
    let mut t = tokens;
    let aa = engine.rule("aa");
    let vowel_before = |t: &[Rule], index: usize| t[..index].iter().any(Rule::is_vowel);
    let infinitive = |rest: &str| INFINITIVE_ONLY.contains(&rest) || OBLIQUE_INF.contains(&rest);

    // 4. "aav" + "vu": keep the two v's apart. The list grows as it goes, so
    //    the bound is re-read each pass.
    let mut at = 1;
    while at < t.len() {
        if t[at].m == "v"
            && t[at - 1].m == "v"
            && infinitive(&rest_lower(&t, at))
            && vowel_before(&t, at - 1)
        {
            t.insert(at, schwa_flat());
            at += 1;
        }
        at += 1;
    }
    for k in 1..t.len().saturating_sub(1) {
        let after = rest_lower(&t, k);
        // 3. The oblique infinitive.
        if t[k].m == "v"
            && OBLIQUE_INF.contains(&after.as_str())
            && vowel_before(&t, k)
            && t[k + 1].is_schwa()
        {
            t[k + 1] = aa.clone();
        }
        // 2. The locative.
        if t[k].m == "m"
            && LOCATIVE.contains(&after.as_str())
            && vowel_before(&t, k)
            && t[k - 1].m != "m"
            && (t[k + 1].m == "a" || t[k + 1].m == "aa")
        {
            t[k + 1] = aa_nasal_ma();
        }
    }
    // 5. "gai", "thai", "jaish".
    for k in 0..t.len() {
        if t[k].m == "ai"
            && !vowel_before(&t, k)
            && AI_SUFFIX.contains(&rest_lower(&t, k + 1).as_str())
        {
            t.remove(k);
            t.insert(k, schwa_flat());
            t.insert(k + 1, i_long());
            break;
        }
    }
    // 6. A long i after a vowel or before a verbal suffix.
    for k in 0..t.len() {
        if t[k].m == "i"
            && k > 0
            && (t[k - 1].is_vowel()
                || (I_SUFFIX.contains(&rest_lower(&t, k + 1).as_str()) && vowel_before(&t, k)))
        {
            t[k] = i_long();
        }
    }
    // 6b. "-iye" is ઈએ.
    let n = t.len();
    if n >= 3 && t[n - 3] == i_long() && t[n - 2].m == "y" && t[n - 1].m == "e" {
        t.pop();
        let last = t.len() - 1;
        t[last] = e_full();
    }
    // 1. The neuter -ું, the first person -ાઉં.
    if let Some(last) = t.last().cloned() {
        let idx = t.len() - 1;
        if last.m == "u" {
            t[idx] = u_nasal();
        } else if last.m == "au" && t.len() > 1 {
            t[idx] = aa.clone();
            t.push(u_nasal());
        }
    }
    // 7. "-tra", "-dra", "-shra".
    let n = t.len();
    if n >= 4
        && t[n - 1].is_schwa()
        && t[n - 1].m == "a"
        && t[n - 2].full == RA
        && TRA_HEADS.contains(&t[n - 3].full.as_str())
        && t[n - 3].is_consonant()
    {
        t[n - 1] = schwa_flat();
    }
    t
}

#[cfg(test)]
mod tests {
    //! Mirrors `GujaratiPhoneticTest.kt`.

    use super::{transliterate as t, variants};

    #[test]
    fn plain_words() {
        assert_eq!("\u{A95}\u{AC7}\u{AAE}", t("kem"));
        assert_eq!("\u{AA4}\u{AAE}\u{AC7}", t("tame"));
        assert_eq!("\u{AA8}\u{AAE}\u{AB8}\u{ACD}\u{AA4}\u{AC7}", t("namaste"));
        assert_eq!("\u{A95}\u{ABE}\u{AAE}", t("kaam"));
        assert_eq!("\u{A9D}\u{A98}\u{AA6}\u{ACB}", t("zaghdo"));
        assert_eq!("\u{AB2}\u{A95}\u{ACD}\u{AB7}\u{AAE}\u{AC0}", t("laxmi"));
        assert_eq!("\u{AAA}\u{AB3}\u{ABE}", t("paLa"));
    }

    #[test]
    fn final_u_is_the_neuter_nasal() {
        assert_eq!("\u{AB9}\u{AC1}\u{A82}", t("hu"));
        assert_eq!("\u{A95}\u{AB0}\u{AB5}\u{AC1}\u{A82}", t("karvu"));
        assert_eq!("\u{A95}\u{AB0}\u{ACD}\u{AAF}\u{AC1}\u{A82}", t("karyu"));
        assert_eq!("\u{AB9}\u{AA4}\u{AC1}\u{A82}", t("hatu"));
        assert_eq!("\u{A9C}\u{ABE}\u{A89}\u{A82}", t("jau"));
    }

    #[test]
    fn the_locative_is_nasal() {
        assert_eq!("\u{A98}\u{AB0}\u{AAE}\u{ABE}\u{A82}", t("gharma"));
        assert_eq!("\u{A98}\u{AB0}\u{AAE}\u{ABE}\u{A82}", t("gharmaa"));
        assert_eq!(
            "\u{A98}\u{AB0}\u{AAE}\u{ABE}\u{A82}\u{AA5}\u{AC0}",
            t("gharmathi")
        );
        assert_eq!(
            "\u{AB6}\u{AB9}\u{AC7}\u{AB0}\u{AAE}\u{ABE}\u{A82}",
            t("shaherma")
        );
    }

    #[test]
    fn the_oblique_infinitive_is_long() {
        assert_eq!(
            "\u{A95}\u{AB0}\u{AB5}\u{ABE}\u{AA8}\u{AC1}\u{A82}",
            t("karvanu")
        );
        assert_eq!(
            "\u{A95}\u{AB0}\u{AB5}\u{ABE}\u{AAE}\u{ABE}\u{A82}",
            t("karvama")
        );
        assert_eq!(
            "\u{A9C}\u{AAE}\u{AB5}\u{ABE}\u{AA8}\u{AC1}\u{A82}",
            t("jamvanu")
        );
    }

    #[test]
    fn doubled_v_before_the_infinitive_is_two_syllables() {
        assert_eq!("\u{A86}\u{AB5}\u{AB5}\u{AC1}\u{A82}", t("aavvu"));
        assert_eq!(
            "\u{A86}\u{AB5}\u{AB5}\u{ABE}\u{AA8}\u{AC1}\u{A82}",
            t("aavvanu")
        );
    }

    #[test]
    fn one_syllable_ai_is_a_plus_long_i() {
        assert_eq!("\u{A97}\u{A88}", t("gai"));
        assert_eq!("\u{AA5}\u{A88}", t("thai"));
        assert_eq!("\u{A9C}\u{A88}\u{AB6}", t("jaish"));
        assert_eq!("\u{AB2}\u{A88}\u{AA8}\u{AC7}", t("laine"));
        assert_eq!("\u{AAA}\u{AC8}\u{AB8}\u{ABE}", t("paisa"));
    }

    #[test]
    fn i_is_long_after_a_vowel_or_before_a_verbal_suffix() {
        assert_eq!("\u{A9C}\u{ACB}\u{A88}\u{A8F}", t("joie"));
        assert_eq!("\u{A95}\u{AB0}\u{AC0}\u{AA8}\u{AC7}", t("karine"));
        assert_eq!("\u{A95}\u{AB0}\u{AC0}\u{AB6}", t("karish"));
        assert_eq!("\u{AAE}\u{AB2}\u{AC0}\u{A8F}", t("malie"));
        assert_eq!("\u{A9C}\u{ACB}\u{A88}\u{A8F}", t("joiye"));
        assert_eq!("\u{A95}\u{AB0}\u{AC0}\u{A8F}", t("kariye"));
        assert_eq!("\u{A9C}\u{A88}\u{A8F}", t("jaiye"));
    }

    #[test]
    fn a_sanskrit_tra_noun_ends_in_the_consonant() {
        assert_eq!("\u{AAE}\u{ABF}\u{AA4}\u{ACD}\u{AB0}", t("mitra"));
        assert_eq!(
            "\u{AA8}\u{AB0}\u{AC7}\u{A82}\u{AA6}\u{ACD}\u{AB0}",
            t("narendra")
        );
        assert_eq!(
            "\u{AB8}\u{ACC}\u{AB0}\u{AB6}\u{ACD}\u{AA4}\u{ACD}\u{AB0}",
            t("saurashtra")
        );
    }

    #[test]
    fn variants_offer_the_other_readings() {
        assert_eq!(
            vec![
                "\u{A95}\u{AB0}\u{AB5}\u{AC1}\u{A82}",
                "\u{A95}\u{ABE}\u{AB0}\u{AB5}\u{AC1}\u{A82}",
                "\u{A95}\u{AB0}\u{ACD}\u{AB5}\u{AC1}\u{A82}",
            ],
            variants("karvu"),
        );
    }
}

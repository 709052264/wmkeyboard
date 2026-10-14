//! Punjabi phonetic typing, into Gurmukhi: Hindi's schwa-dropping model,
//! with the script converter turning conjuncts into the addak or pairin and
//! the anusvara into a tippi or a bindi. A port of `PunjabiPhonetic.kt`;
//! the hook's rules:
//!
//! 1. "oh" and "eh" opening a word are ਉਹ and ਇਹ.
//! 2. "eh" after a consonant is ਅ + ਹਿ (kehnda ਕਹਿੰਦਾ); ਿਹਾ before a final "a".
//! 3. Gurmukhi spells the y-glides with vowel carriers, not ਯ.
//! 4. "au" before "n" and then a d or a final vowel is ਾਉ.
//! 5. "n" before a final -a, -i or -e is ਣ in the places the list says.
//! 6. A word-final "n" is the nasal after o, e, ai and aa, ਾਂ in a plural,
//!    ਣ in hun and kaun.
//! 7. A final "nu" is the dative ਨੂੰ.

use std::sync::OnceLock;

use crate::indic::hooks::rest;
use crate::indic::{Engine, Kind, Model, Profile, Rule};
use crate::indic_script::IndicScript;

const ANUSVARA: &str = "\u{902}";
/// ड़्ह, which Gurmukhi writes ੜ੍ਹ: parh ਪੜ੍ਹ.
const RH: &str = "\u{921}\u{93C}\u{94D}\u{939}";
const SA: &str = "\u{938}";
const DA: &str = "\u{926}";

const LONG: &[&str] = &[
    "aa", "A", "ee", "ii", "I", "oo", "uu", "U", "e", "o", "ai", "au", "ou",
];
const FINAL_V: &[&str] = &["a", "aa", "i", "ee", "e"];
/// The tails besides a lone final vowel that rule 5 reads ਣ before.
const NNA_TAILS: &[&str] = &["an", "aan", "eeaa", "eeaan"];
/// Rule 5 keeps ਨ after ਰ ੜ ੜ੍ਹ ਣ ਨ ਹ, and after the dentals, ਵ and ਸ਼.
const NO_NNA_AFTER: &[&str] = &[
    "\u{930}",
    "\u{921}\u{93C}",
    RH,
    "\u{923}",
    "\u{928}",
    "\u{939}",
    "\u{924}",
    "\u{925}",
    "\u{926}",
    "\u{927}",
    "\u{935}",
    "\u{936}",
];
const A_OR_AA: &[&str] = &["a", "aa"];
const I_OR_E: &[&str] = &["i", "ee", "e"];
const PLURAL: &[&str] = &["an", "aan"];
const NASAL_AFTER: &[&str] = &["o", "e", "ai", "aa", "A"];

/// इ ि, never lengthened at the end of a word, as the base "i" would be.
fn i_short() -> Rule {
    Rule::vowel("i", "\u{907}", "\u{93F}")
}
fn u_short() -> Rule {
    Rule::vowel("u", "\u{909}", "\u{941}")
}
/// ई written in full even after a consonant: gyi ਗਈ.
fn full_ee() -> Rule {
    Rule::vowel("ee", "\u{908}", "\u{908}")
}
/// ए likewise: gye ਗਏ.
fn full_e() -> Rule {
    Rule::vowel("e", "\u{90F}", "\u{90F}")
}
/// ण, which Gurmukhi writes ਣ.
fn nna() -> Rule {
    Rule::consonant("n", "\u{923}")
}
/// A final "n" read as the nasal: ਂ or ੰ once converted.
fn nasal() -> Rule {
    Rule::sign("n", ANUSVARA)
}

pub fn profile() -> Profile {
    let mut p = Profile::new("pa", IndicScript::Gurmukhi, Model::North);
    p.extra_consonants = vec![
        ("L", "\u{933}"),
        ("R", "\u{921}\u{93C}"),
        ("Rh", RH),
        ("rh", RH),
    ];
    p.endings = vec!["naal", "vich", "wala", "wale", "wali"];
    p.soft_endings = vec!["ke", "ta", "te", "ti"];
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
    if t.is_empty() {
        return t;
    }
    let schwa = engine.rule("a");
    let long_a = engine.rule("aa");
    let long_i = engine.rule("ee");
    let long_u = engine.rule("oo");
    let is_v = |r: &Rule| r.is_vowel();
    let is_c = |r: &Rule| r.is_consonant();

    // 1. An opening "oh" or "eh".
    if t.len() >= 2 && (t[0].m == "e" || t[0].m == "o") && t[1].m == "h" {
        t[0] = if t[0].m == "e" { i_short() } else { u_short() };
    }

    // 2. A consonant and "eh".
    let mut i = 1;
    while i + 1 < t.len() {
        if t[i].m == "e" && is_c(&t[i - 1]) && t[i + 1].m == "h" {
            let next = t.get(i + 2).cloned();
            match next {
                None => {
                    t[i] = schwa.clone();
                    t.insert(i + 2, i_short());
                }
                Some(ref next) if is_c(next) => {
                    t[i] = schwa.clone();
                    t.insert(i + 2, i_short());
                }
                Some(ref next) if next.m == "a" && i + 3 == t.len() - 1 && is_c(&t[i + 3]) => {
                    t[i] = schwa.clone();
                    t[i + 2] = i_short();
                }
                Some(ref next) if A_OR_AA.contains(&next.m.as_str()) && i + 2 == t.len() - 1 => {
                    t[i] = i_short();
                }
                _ => {}
            }
        }
        i += 1;
    }

    // 3. The y-glides.
    i = 1;
    while i + 1 < t.len() {
        if t[i].m != "y" || !is_v(&t[i + 1]) {
            i += 1;
            continue;
        }
        let prev = t[i - 1].clone();
        let next = t[i + 1].clone();
        if is_c(&prev) {
            if A_OR_AA.contains(&next.m.as_str()) {
                t[i] = i_short();
                t[i + 1] = long_a.clone();
            } else if I_OR_E.contains(&next.m.as_str()) {
                t[i + 1] = if next.m == "e" { full_e() } else { full_ee() };
                t.remove(i);
                continue;
            }
        } else if I_OR_E.contains(&prev.m.as_str()) && i >= 2 && is_c(&t[i - 2]) {
            if A_OR_AA.contains(&next.m.as_str()) {
                let first = !t[..i - 1].iter().any(is_v);
                let plural = PLURAL.contains(&rest(&t, i + 1).as_str());
                t[i - 1] = if prev.m == "e" || (prev.m == "i" && first && !plural) {
                    i_short()
                } else {
                    long_i.clone()
                };
                t[i + 1] = long_a.clone();
                t.remove(i);
                continue;
            }
        } else if is_v(&prev) {
            if A_OR_AA.contains(&next.m.as_str()) {
                if prev.is_schwa() {
                    t[i - 1] = long_a.clone();
                }
                t[i] = i_short();
                t[i + 1] = long_a.clone();
            } else if I_OR_E.contains(&next.m.as_str()) {
                t.remove(i);
                continue;
            }
        }
        i += 1;
    }

    // 4. "au", then "n", then a d or a final vowel.
    i = 0;
    while i + 2 < t.len() {
        if t[i].m == "au" && t[i + 1].m == "n" {
            let tail = rest(&t, i + 2);
            let final_v = FINAL_V.contains(&tail.as_str());
            if t[i + 2].full == DA || final_v {
                t.remove(i);
                t.insert(i, long_a.clone());
                t.insert(i + 1, u_short());
                if final_v {
                    t[i + 2] = nna();
                }
                i += 2;
            }
        }
        i += 1;
    }

    // 5. "n" before a final vowel is ਣ.
    for k in 1..t.len().saturating_sub(1) {
        if t[k].m != "n" || t[k] == nna() {
            continue;
        }
        let tail = rest(&t, k + 1);
        if !FINAL_V.contains(&tail.as_str()) && !NNA_TAILS.contains(&tail.as_str()) {
            continue;
        }
        let prev = &t[k - 1];
        let ok = match prev.kind {
            Kind::Vowel => {
                LONG.contains(&prev.m.as_str())
                    || (prev.is_schwa() && k >= 2)
                    || (*prev == i_short() && k >= 2 && t[k - 2].m == "h")
            }
            Kind::Consonant => {
                !NO_NNA_AFTER.contains(&prev.full.as_str())
                    && prev.m != "y"
                    && !(prev.full == SA && tail == "e")
            }
            Kind::Sign => false,
        };
        if ok {
            t[k] = nna();
        }
    }

    // 6. A word-final "n".
    let n = t.len();
    if n >= 2 && t[n - 1].m == "n" && is_v(&t[n - 2]) {
        let v = t[n - 2].clone();
        let vowels = t.iter().filter(|r| is_v(r)).count();
        if NASAL_AFTER.contains(&v.m.as_str()) {
            t[n - 1] = nasal();
        } else if v.is_schwa() && vowels >= 2 {
            t[n - 2] = long_a.clone();
            t[n - 1] = nasal();
        } else if v.m == "u" && n >= 3 {
            t[n - 1] = if is_v(&t[n - 3]) { nasal() } else { nna() };
        } else if v.m == "au" {
            t[n - 1] = nna();
        }
    }

    // 7. A final "nu" is the dative ਨੂੰ.
    let n = t.len();
    if n >= 2 && t[n - 1].m == "u" && t[n - 2].m == "n" {
        t[n - 1] = long_u;
        t.push(nasal());
    }
    t
}

#[cfg(test)]
mod tests {
    //! Mirrors `PunjabiPhoneticTest.kt`.

    use super::{transliterate as t, variants};

    #[test]
    fn plain_words_follow_the_north_model() {
        assert_eq!("\u{A18}\u{A30}", t("ghar"));
        assert_eq!("\u{A2A}\u{A71}\u{A15}\u{A3E}", t("pakka"));
        assert_eq!("\u{A15}\u{A3F}\u{A70}\u{A28}\u{A3E}", t("kinna"));
        assert_eq!("\u{A15}\u{A70}\u{A2E}", t("kamm"));
        assert_eq!("\u{A2A}\u{A5C}\u{A4D}\u{A39}", t("parh"));
        assert_eq!(
            "\u{A38}\u{A24} \u{A38}\u{A4D}\u{A30}\u{A40} \u{A05}\u{A15}\u{A32} \u{A1C}\u{A40}",
            t("sat sri akal ji")
        );
    }

    #[test]
    fn opening_oh_and_eh_are_u_and_i() {
        assert_eq!("\u{A09}\u{A39}\u{A26}\u{A3E}", t("ohda"));
        assert_eq!("\u{A07}\u{A39}\u{A28}\u{A3E}", t("ehna"));
        assert_eq!("\u{A07}\u{A39}\u{A4B}", t("eho"));
    }

    #[test]
    fn eh_after_a_consonant_is_a_plus_ha_sihari() {
        assert_eq!("\u{A15}\u{A39}\u{A3F}\u{A70}\u{A26}\u{A3E}", t("kehnda"));
        assert_eq!("\u{A36}\u{A39}\u{A3F}\u{A30}", t("shehar"));
        assert_eq!("\u{A15}\u{A39}\u{A3F}", t("keh"));
        assert_eq!("\u{A30}\u{A3F}\u{A39}\u{A3E}", t("reha"));
    }

    #[test]
    fn y_glides_are_vowel_carriers() {
        assert_eq!("\u{A2A}\u{A3F}\u{A06}\u{A30}", t("pyar"));
        assert_eq!("\u{A26}\u{A47}\u{A16}\u{A3F}\u{A06}", t("dekhya"));
        assert_eq!("\u{A17}\u{A08}", t("gyi"));
        assert_eq!("\u{A2A}\u{A0F}", t("pye"));
        assert_eq!("\u{A26}\u{A41}\u{A28}\u{A40}\u{A06}", t("duniya"));
        assert_eq!("\u{A15}\u{A41}\u{A5C}\u{A40}\u{A06}\u{A02}", t("kuRiyan"));
        assert_eq!("\u{A2A}\u{A3F}\u{A06}\u{A30}", t("piyar"));
        assert_eq!("\u{A39}\u{A4B}\u{A07}\u{A06}", t("hoya"));
        assert_eq!("\u{A17}\u{A08}", t("gayi"));
        assert_eq!("\u{A09}\u{A26}\u{A2F}\u{A4B}\u{A17}", t("udyog"));
    }

    #[test]
    fn au_before_n_is_kanna_aunkar() {
        assert_eq!("\u{A06}\u{A09}\u{A02}\u{A26}\u{A3E}", t("aunda"));
        assert_eq!("\u{A32}\u{A3E}\u{A09}\u{A23}\u{A3E}", t("launa"));
    }

    #[test]
    fn n_before_a_final_vowel_is_nna() {
        assert_eq!("\u{A1C}\u{A3E}\u{A23}\u{A3E}", t("jaana"));
        assert_eq!("\u{A26}\u{A47}\u{A16}\u{A23}\u{A3E}", t("dekhna"));
        assert_eq!("\u{A15}\u{A39}\u{A3F}\u{A23}\u{A3E}", t("kehna"));
        assert_eq!("\u{A15}\u{A30}\u{A28}\u{A3E}", t("karna"));
        assert_eq!("\u{A09}\u{A38}\u{A28}\u{A47}", t("usne"));
    }

    #[test]
    fn word_final_n() {
        assert_eq!("\u{A24}\u{A4B}\u{A02}", t("ton"));
        assert_eq!("\u{A39}\u{A3E}\u{A02}", t("haan"));
        assert_eq!("\u{A17}\u{A71}\u{A32}\u{A3E}\u{A02}", t("gallan"));
        assert_eq!("\u{A39}\u{A41}\u{A23}", t("hun"));
        assert_eq!("\u{A15}\u{A3F}\u{A09}\u{A02}", t("kiun"));
        assert_eq!("\u{A15}\u{A4C}\u{A23}", t("kaun"));
    }

    #[test]
    fn final_nu_is_the_dative() {
        assert_eq!("\u{A2E}\u{A48}\u{A28}\u{A42}\u{A70}", t("mainu"));
        assert_eq!("\u{A09}\u{A39}\u{A28}\u{A42}\u{A70}", t("ohnu"));
    }

    #[test]
    fn variants_offer_the_other_readings() {
        assert_eq!(
            vec![
                "\u{A15}\u{A30}\u{A28}\u{A3E}",
                "\u{A15}\u{A3E}\u{A30}\u{A28}\u{A3E}",
                "\u{A15}\u{A30}\u{A28}"
            ],
            variants("karna"),
        );
    }
}

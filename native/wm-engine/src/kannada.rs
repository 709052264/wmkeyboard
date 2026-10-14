//! Kannada phonetic: roman letters in, Kannada script out, on the Indic
//! engine's Explicit model. A port of `KannadaPhonetic.kt`; the hook's
//! rules, in the order they run (later ones read what earlier ones wrote,
//! and every replacement keeps the roman spelling it replaced):
//!
//! 1. "ye" opening a word is ಎ/ಏ.
//! 2. "ow" before a consonant is ಔ.
//! 3. "sht" is ಷ್ಟ, "shth" ಷ್ಠ.
//! 4. "agi" is ಆಗಿ everywhere, "agu" ಆಗು past the first syllable, not at the end.
//! 5. The plural "-galu" is ಗಳು.
//! 6. A final "ona" past the first syllable is the hortative -ಓಣ.
//! 7. Past the first syllable, o is long ಓ unless a geminate or nasal cluster follows.
//! 8. In a two-syllable word, e before a single consonant is long ಏ.
//! 9. l after a short o is ಳ.
//! 10. A single d between vowels before i is ಡ (not after a short u or e),
//!     and before any vowel after a typed long ee/oo.
//! 11. A final "dhu"/"dhe" is ದು/ದೆ.
//! 12. The spoken first person -ತೀನಿ / -ದ್ದೀನಿ has the long ī.

use std::sync::OnceLock;

use crate::indic::hooks::ends_with;
use crate::indic::{Engine, Model, Profile, Rule};
use crate::indic_script::IndicScript;

/// Typed long vowels, after which a single d is ಡ before any vowel (rule 10).
const TYPED_LONG: &[&str] = &["ee", "ii", "oo", "uu"];

fn aa() -> Rule {
    Rule::vowel("aa", "\u{906}", "\u{93E}")
}
fn long_e() -> Rule {
    Rule::vowel("E", "\u{90F}", "\u{947}")
}
fn long_o() -> Rule {
    Rule::vowel("O", "\u{913}", "\u{94B}")
}
fn au() -> Rule {
    Rule::vowel("au", "\u{914}", "\u{94C}")
}
fn long_i() -> Rule {
    Rule::vowel("ee", "\u{908}", "\u{940}")
}
fn ssa() -> Rule {
    Rule::consonant("sh", "\u{937}")
}
fn tta() -> Rule {
    Rule::consonant("t", "\u{91F}")
}
fn ttha() -> Rule {
    Rule::consonant("th", "\u{920}")
}
fn lla() -> Rule {
    Rule::consonant("l", "\u{933}")
}
fn nna() -> Rule {
    Rule::consonant("n", "\u{923}")
}
fn dda() -> Rule {
    Rule::consonant("d", "\u{921}")
}
fn da() -> Rule {
    Rule::consonant("dh", "\u{926}")
}

pub fn profile() -> Profile {
    let mut p = Profile::new("kn", IndicScript::Kannada, Model::Explicit);
    p.dravidian_vowels = true;
    p.extra_consonants = vec![("L", "\u{933}"), ("R", "\u{931}"), ("th", "\u{924}")];
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

fn hook(_engine: &Engine, tokens: Vec<Rule>) -> Vec<Rule> {
    let mut t = tokens;
    let is_c = |r: &Rule| r.is_consonant();
    let is_v = |r: &Rule| r.is_vowel();
    // 1. "ye" opening a word is ಎ/ಏ.
    if t.len() >= 3 && t[0].m == "y" && (t[1].m == "e" || t[1].m == "E") {
        t.remove(0);
    }
    // 2. "ow" before a consonant is ಔ. The list shrinks as it goes, so the
    //    bound is re-read every step.
    let mut i = 0;
    while i + 2 < t.len() {
        if t[i].m == "o" && (t[i + 1].m == "w" || t[i + 1].m == "v") && is_c(&t[i + 2]) {
            t.remove(i + 1);
            t[i] = au();
        }
        i += 1;
    }
    let n = t.len();
    // 3. "sht" is ಷ್ಟ, "shth" ಷ್ಠ.
    for k in 0..n.saturating_sub(1) {
        if t[k].m == "sh" && (t[k + 1].m == "t" || t[k + 1].m == "th") {
            t[k] = ssa();
            t[k + 1] = if t[k + 1].m == "t" { tta() } else { ttha() };
        }
    }
    // Vowel positions, for the syllable-based rules below.
    let vows: Vec<usize> = (0..n).filter(|&i| is_v(&t[i])).collect();
    // 4. "agi" is ಆಗಿ everywhere, "agu" ಆಗು past the first syllable and
    //    short of the end.
    for (k, &v) in vows.iter().enumerate() {
        if t[v].m == "a"
            && v + 2 < n
            && t[v + 1].m == "g"
            && (t[v + 2].m == "i" || t[v + 2].m == "u")
            && (t[v + 2].m == "i" || (k > 0 && v + 3 < n))
        {
            t[v] = aa();
        }
    }
    // 5. The plural "-galu" is ಗಳು.
    if n >= 5 && ends_with(&t, &["g", "a", "l", "u"]) {
        t[n - 2] = lla();
    }
    // 6. A final "ona" past the first syllable is the hortative -ಓಣ.
    if vows.len() >= 3 && ends_with(&t, &["o", "n", "a"]) {
        t[n - 2] = nna();
    }
    // 7. Past the first syllable, o is long ಓ, unless a geminate or a nasal
    //    cluster follows.
    for (k, &v) in vows.iter().enumerate() {
        if k == 0 || t[v].m != "o" {
            continue;
        }
        if let (Some(a), Some(b)) = (t.get(v + 1), t.get(v + 2)) {
            if is_c(a) && is_c(b) && (a.full == b.full || a.m == "n" || a.m == "m" || a.m == "N") {
                continue;
            }
        }
        t[v] = long_o();
    }
    // 8. In a two-syllable word, e before a single consonant is long ಏ.
    if vows.len() == 2 && vows[1] == n - 1 {
        let e = vows[0];
        if t[e].m == "e" && vows[1] == e + 2 && is_c(&t[e + 1]) {
            t[e] = long_e();
        }
    }
    // 9. l after a short o is ಳ, single or doubled.
    for k in 1..n {
        if t[k].m == "l" && (t[k - 1].m == "o" || t[k - 1].m == "l") {
            let o = if t[k - 1].m == "o" {
                k as isize - 1
            } else {
                k as isize - 2
            };
            if o >= 0 {
                let o = o as usize;
                if t[o].m == "o" && (o == k - 1 || t[o + 1].m == "l") {
                    t[k] = lla();
                }
            }
        }
    }
    // 10. A single d between vowels is ಡ before i (not after a short u or
    //     e), and before any vowel after a typed long ee/oo.
    for k in 1..n.saturating_sub(1) {
        if t[k].m != "d" || !is_v(&t[k - 1]) || !is_v(&t[k + 1]) {
            continue;
        }
        let pv = t[k - 1].m.to_lowercase();
        if TYPED_LONG.contains(&pv.as_str()) || (t[k + 1].m == "i" && pv != "u" && pv != "e") {
            t[k] = dda();
        }
    }
    // 11. A final "dhu"/"dhe" is ದು/ದೆ.
    if n >= 3 && t[n - 2].m == "dh" && (t[n - 1].m == "u" || t[n - 1].m == "e") {
        t[n - 2] = da();
    }
    // 12. The spoken first person -ತೀನಿ / -ದ್ದೀನಿ.
    if n >= 5
        && ends_with(&t, &["i", "n", "i"])
        && (t[n - 4].m == "t" || t[n - 4].m == "th" || t[n - 4].m == "d")
        && is_c(&t[n - 5])
    {
        t[n - 3] = long_i();
    }
    t
}

#[cfg(test)]
mod tests {
    //! Mirrors `KannadaPhoneticTest.kt`.

    use super::{transliterate as t, variants};

    #[test]
    fn plain_words() {
        assert_eq!("\u{CA8}\u{CA8}\u{CCD}\u{CA8}", t("nanna"));
        assert_eq!("\u{C85}\u{CAE}\u{CCD}\u{CAE}", t("amma"));
        assert_eq!("\u{CA8}\u{CAE}\u{CB8}\u{CCD}\u{C95}\u{CB0}", t("namaskara"));
        assert_eq!("\u{CA4}\u{CC1}\u{C82}\u{CAC}", t("thumba"));
        assert_eq!("\u{CAE}\u{CBE}\u{CA4}\u{CC1}", t("maathu"));
    }

    #[test]
    fn ye_opening_a_word_is_e() {
        assert_eq!("\u{C8F}\u{CA8}\u{CC1}", t("yenu"));
        assert_eq!("\u{C8E}\u{CB2}\u{CCD}\u{CB2}\u{CBF}", t("yelli"));
        assert_eq!("\u{CAF}\u{CC6}", t("ye"));
    }

    #[test]
    fn ow_before_a_consonant_is_au() {
        assert_eq!("\u{CB9}\u{CCC}\u{CA6}\u{CC1}", t("howdu"));
        assert_eq!("\u{C97}\u{CCC}\u{CA6}", t("gowda"));
    }

    #[test]
    fn sh_before_a_retroflex_is_ssa() {
        assert_eq!("\u{C95}\u{CB7}\u{CCD}\u{C9F}", t("kashta"));
        assert_eq!("\u{CA8}\u{CBF}\u{CB7}\u{CCD}\u{CA0}", t("nishtha"));
    }

    #[test]
    fn the_aagu_verb() {
        assert_eq!(
            "\u{C9A}\u{CC6}\u{CA8}\u{CCD}\u{CA8}\u{CBE}\u{C97}\u{CBF}",
            t("chennagi")
        );
        assert_eq!(
            "\u{CAE}\u{CBE}\u{CA6}\u{CB2}\u{CBE}\u{C97}\u{CC1}\u{CA4}\u{CCD}\u{CA4}\u{CC6}",
            t("maadalagutte")
        );
        assert_eq!(
            "\u{C85}\u{C97}\u{CC1}\u{CA4}\u{CCD}\u{CA4}\u{CC6}",
            t("agutte")
        );
        assert_eq!("\u{CAE}\u{CB2}\u{C97}\u{CC1}", t("malagu"));
    }

    #[test]
    fn the_plural_galu() {
        assert_eq!("\u{CAE}\u{CA8}\u{CC6}\u{C97}\u{CB3}\u{CC1}", t("manegalu"));
        assert_eq!(
            "\u{CB9}\u{CC6}\u{CA8}\u{CCD}\u{CA8}\u{CC1}\u{C97}\u{CB3}\u{CC1}",
            t("hennugalu")
        );
    }

    #[test]
    fn the_hortative_ona() {
        assert_eq!("\u{CB9}\u{CCA}\u{C97}\u{CCB}\u{CA3}", t("hogona"));
        assert_eq!("\u{CAE}\u{CA6}\u{CCB}\u{CA3}", t("madona"));
    }

    #[test]
    fn o_past_the_first_syllable_is_long() {
        assert_eq!("\u{CB8}\u{C82}\u{CA4}\u{CCB}\u{CB6}", t("santosha"));
        assert_eq!(
            "\u{CB6}\u{CC1}\u{CAD}\u{CCB}\u{CA6}\u{CAF}",
            t("shubhodaya")
        );
        assert_eq!(
            "\u{CA4}\u{CC6}\u{C97}\u{CC6}\u{CA6}\u{CC1}\u{C95}\u{CCA}\u{C82}\u{CA6}\u{CC1}",
            t("tegedukondu")
        );
    }

    #[test]
    fn e_in_a_two_syllable_word_is_long() {
        assert_eq!("\u{CAC}\u{CC7}\u{C95}\u{CC1}", t("beku"));
        assert_eq!("\u{CAE}\u{CC7}\u{CB2}\u{CC6}", t("mele"));
        assert_eq!("\u{C8F}\u{CA8}\u{CC1}", t("enu"));
        assert_eq!("\u{C8F}\u{CB6}\u{CC1}", t("yeshu"));
        assert_eq!("\u{CB9}\u{CC6}\u{CB8}\u{CB0}\u{CC1}", t("hesaru"));
    }

    #[test]
    fn l_after_a_short_o_is_lla() {
        assert_eq!("\u{C92}\u{CB3}\u{C97}\u{CC6}", t("olage"));
        assert_eq!("\u{C92}\u{CB3}\u{CCD}\u{CB3}\u{CC6}", t("olle"));
        assert_eq!(
            "\u{CAE}\u{CA1}\u{CBF}\u{C95}\u{CCA}\u{CB3}\u{CCD}\u{CB3}\u{CBF}",
            t("madikolli")
        );
    }

    #[test]
    fn d_between_vowels_is_dda() {
        assert_eq!("\u{C85}\u{C82}\u{C97}\u{CA1}\u{CBF}", t("angadi"));
        assert_eq!("\u{CA8}\u{CC0}\u{CA1}\u{CC1}", t("needu"));
        assert_eq!(
            "\u{CAE}\u{CBE}\u{CA6}\u{CC1}\u{CA6}\u{CBF}\u{CB2}\u{CCD}\u{CB2}",
            t("maadudilla")
        );
        assert_eq!(
            "\u{CAA}\u{CA6}\u{CC6}\u{CA6}\u{CBF}\u{CA6}\u{CC6}",
            t("padedide")
        );
    }

    #[test]
    fn a_final_dhu_is_da() {
        assert_eq!("\u{C87}\u{CA6}\u{CC1}", t("idhu"));
        assert_eq!("\u{C85}\u{CA6}\u{CC1}", t("adhu"));
    }

    #[test]
    fn the_spoken_first_person() {
        assert_eq!(
            "\u{CAC}\u{CB0}\u{CCD}\u{CA4}\u{CC0}\u{CA8}\u{CBF}",
            t("bartini")
        );
        assert_eq!(
            "\u{C87}\u{CA6}\u{CCD}\u{CA6}\u{CC0}\u{CA8}\u{CBF}",
            t("iddini")
        );
    }

    #[test]
    fn variants_offer_the_long_closing_a() {
        assert_eq!(
            vec![
                "\u{C85}\u{CAE}\u{CCD}\u{CAE}",
                "\u{C85}\u{CAE}\u{CCD}\u{CAE}\u{CBE}"
            ],
            variants("amma")
        );
    }
}

//! Odia phonetic typing: roman letters in, Odia script out, on the Indic
//! engine's Explicit model. A port of `OdiaPhonetic.kt`; the hook's rules:
//!
//! 1. ai and au are two vowels, a long aa and then i or u (bhai ଭାଇ).
//! 2. A vowel and then "a" is ଆ, not ଅ (oRia ଓଡ଼ିଆ).
//! 3. Consonant + "ru" is the vowel sign ୃ (krushna କୃଷ୍ଣ).
//! 4. "sh" before t, th or n is the retroflex cluster ଷ୍ଟ ଷ୍ଠ ଷ୍ଣ.
//! 5. A nasal takes the place of the stop after it as a conjunct; before s,
//!    sh, r or l it is the anusvara; "nh" before a vowel is ହ୍ନ.
//! 6. "ch" between a vowel and a closing i, u, e or a, or before "ant", is ଛ.
//! 7. A word closing in -iba, -ila or -ela has a long final aa.
//! 8. v is ବ, except right after a consonant, where it is the subjoined ୱ.

use std::sync::OnceLock;

use crate::indic::hooks::rest_lower;
use crate::indic::{Engine, Kind, Model, Profile, Rule};
use crate::indic_script::IndicScript;

const NA: &str = "\u{928}";
const NGA: &str = "\u{919}";
const NYA: &str = "\u{91E}";
const NNA: &str = "\u{923}";
const HA: &str = "\u{939}";
const CA: &str = "\u{91A}";
const CHA: &str = "\u{91B}";
const SHA: &str = "\u{936}";
const SSA: &str = "\u{937}";
const RA: &str = "\u{930}";
const LA: &str = "\u{932}";
const BA: &str = "\u{92C}";
const VA: &str = "\u{935}";
const ANUSVARA: &str = "\u{902}";

const VELAR: &[&str] = &["\u{915}", "\u{916}", "\u{917}", "\u{918}"];
const PALATAL: &[&str] = &["\u{91A}", "\u{91B}", "\u{91C}", "\u{91D}"];
const RETRO: &[&str] = &["\u{91F}", "\u{920}", "\u{921}", "\u{922}"];
const TO_ANUSVARA: &[&str] = &["\u{938}", "\u{936}", "\u{937}", "\u{930}", "\u{932}"];
/// Rule 6: what may close a word after the ଛ.
const CHA_CLOSINGS: &[&str] = &["i", "u", "e", "a"];
/// Rule 7: the case endings -iba keeps its long aa before.
const IBA_TAILS: &[&str] = &["", "ku", "re", "ra"];

/// Rule 4: त थ न after ष become ट ठ ण.
fn sh_retroflex(full: &str) -> Option<&'static str> {
    Some(match full {
        "\u{924}" => "\u{91F}",
        "\u{925}" => "\u{920}",
        "\u{928}" => "\u{923}",
        _ => return None,
    })
}

/// Rule 3: the vowel ऋ, typed "ru".
fn vocalic_ru() -> Rule {
    Rule::vowel("ru", "\u{90B}", "\u{943}")
}

pub fn profile() -> Profile {
    let mut p = Profile::new("or", IndicScript::Oriya, Model::Explicit);
    p.extra_consonants = vec![
        ("L", "\u{933}"),
        ("R", "\u{921}\u{93C}"),
        ("Rh", "\u{922}\u{93C}"),
    ];
    p.override_ = vec![("z", "\u{91C}")];
    p.anusvara = false;
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

fn hook(engine: &Engine, input: Vec<Rule>) -> Vec<Rule> {
    let aa = engine.rule("aa");
    let i = engine.rule("i");
    let u = engine.rule("u");

    // 1: ai / au split into aa + i / aa + u.
    let mut split: Vec<Rule> = Vec::with_capacity(input.len() + 2);
    for t in input {
        match t.m.to_lowercase().as_str() {
            "ai" => {
                split.push(aa.clone());
                split.push(i.clone());
            }
            "au" => {
                split.push(aa.clone());
                split.push(u.clone());
            }
            _ => split.push(t),
        }
    }

    // 2, 3 and 8, in one walk; rule 3 consumes the "u" after it.
    let mut pending: Vec<Option<Rule>> = split.into_iter().map(Some).collect();
    let mut tokens: Vec<Rule> = Vec::with_capacity(pending.len());
    for k in 0..pending.len() {
        let Some(mut t) = pending[k].clone() else {
            continue;
        };
        let prev_kind = tokens.last().map(|r| r.kind);
        let next = pending.get(k + 1).cloned().flatten();
        if t.is_schwa() && prev_kind == Some(Kind::Vowel) {
            t = aa.clone(); // 2
        }
        let ru = t.full == RA && next.is_some_and(|n| n.m == "u");
        if ru && prev_kind == Some(Kind::Consonant) {
            // 3
            tokens.push(vocalic_ru());
            pending[k + 1] = None;
            continue;
        }
        if t.m == "v" {
            // 8
            t = Rule::consonant(
                "v",
                if prev_kind == Some(Kind::Consonant) {
                    VA
                } else {
                    BA
                },
            );
        }
        tokens.push(t);
    }

    let n = tokens.len();
    // 4: ष before ट ठ ण.
    for k in 0..n.saturating_sub(1) {
        if tokens[k].full != SHA || tokens[k].m != "sh" {
            continue;
        }
        let Some(retroflex) = sh_retroflex(&tokens[k + 1].full) else {
            continue;
        };
        tokens[k] = Rule::consonant("sh", SSA);
        tokens[k + 1] = Rule::consonant(&tokens[k + 1].m.clone(), retroflex);
    }
    // 5: a nasal after a vowel and before a consonant.
    for k in 1..n.saturating_sub(1) {
        let t = tokens[k].clone();
        let next = tokens[k + 1].clone();
        if t.full != NA || !tokens[k - 1].is_vowel() || !next.is_consonant() {
            continue;
        }
        let full = next.full.as_str();
        if VELAR.contains(&full) {
            tokens[k] = Rule::consonant("n", NGA);
        } else if PALATAL.contains(&full) {
            tokens[k] = Rule::consonant("n", NYA);
        } else if RETRO.contains(&full) {
            tokens[k] = Rule::consonant("n", NNA);
        } else if TO_ANUSVARA.contains(&full) {
            tokens[k] = Rule::sign("n", ANUSVARA);
        } else if full == HA && k + 2 < n && tokens[k + 2].is_vowel() {
            // nh: ହ୍ନ
            tokens[k] = next;
            tokens[k + 1] = t;
        }
    }
    // 6: ଛ in the copula and the verb endings.
    for k in 1..n.saturating_sub(1) {
        if tokens[k].full != CA || !tokens[k - 1].is_vowel() {
            continue;
        }
        let tail = rest_lower(&tokens, k + 1);
        if CHA_CLOSINGS.contains(&tail.as_str()) || tail.starts_with("ant") {
            tokens[k] = Rule::consonant("ch", CHA);
        }
    }
    // 7: the long aa of -iba, -ila, -ela.
    for k in 2..n {
        if !tokens[k].is_schwa() {
            continue;
        }
        let c = tokens[k - 1].full.clone();
        let v = tokens[k - 2].m.clone();
        let tail = rest_lower(&tokens, k + 1);
        if (c == BA && v == "i" && IBA_TAILS.contains(&tail.as_str()))
            || (c == LA && (v == "i" || v == "e") && tail.is_empty())
        {
            tokens[k] = aa.clone();
        }
    }
    tokens
}

#[cfg(test)]
mod tests {
    //! Mirrors `OdiaPhoneticTest.kt`.

    use super::{transliterate as t, variants};

    #[test]
    fn plain_words_and_sentences() {
        assert_eq!("\u{B2D}\u{B32}", t("bhala"));
        assert_eq!("\u{B18}\u{B30}", t("ghara"));
        assert_eq!("\u{B2E}\u{B4B}", t("mo"));
        assert_eq!(
            "\u{B2E}\u{B41} \u{B2D}\u{B32} \u{B05}\u{B1B}\u{B3F}",
            t("mu bhala achi")
        );
        assert_eq!("\u{B2C}\u{B5C}", t("baRa"));
    }

    #[test]
    fn ai_and_au_are_two_vowels() {
        assert_eq!("\u{B2D}\u{B3E}\u{B07}", t("bhai"));
        assert_eq!("\u{B2A}\u{B3E}\u{B07}", t("pai"));
        assert_eq!("\u{B16}\u{B3E}\u{B07}\u{B2C}\u{B3E}", t("khaiba"));
        assert_eq!("\u{B1C}\u{B3E}\u{B09}\u{B1B}\u{B3F}", t("jauchi"));
    }

    #[test]
    fn a_vowel_then_a_is_long_aa() {
        assert_eq!("\u{B13}\u{B5C}\u{B3F}\u{B06}", t("oRia"));
        assert_eq!("\u{B28}\u{B41}\u{B06}", t("nua"));
        assert_eq!("\u{B15}\u{B3E}\u{B07}\u{B06}", t("kaia"));
    }

    #[test]
    fn consonant_ru_is_the_vowel_sign() {
        assert_eq!("\u{B15}\u{B43}\u{B37}\u{B4D}\u{B23}", t("krushna"));
        assert_eq!("\u{B2A}\u{B43}\u{B25}\u{B3F}\u{B2C}\u{B3F}", t("pruthibi"));
    }

    #[test]
    fn sh_before_t_th_n_is_retroflex() {
        assert_eq!("\u{B15}\u{B37}\u{B4D}\u{B1F}", t("kashta"));
        assert_eq!("\u{B26}\u{B43}\u{B37}\u{B4D}\u{B1F}\u{B3F}", t("drushti"));
        assert_eq!("\u{B2C}\u{B3F}\u{B37}\u{B4D}\u{B23}\u{B41}", t("bishnu"));
    }

    #[test]
    fn a_nasal_takes_the_place_of_its_stop() {
        assert_eq!("\u{B32}\u{B19}\u{B4D}\u{B15}", t("lanka"));
        assert_eq!("\u{B2A}\u{B1E}\u{B4D}\u{B1A}", t("pancha"));
        assert_eq!("\u{B18}\u{B23}\u{B4D}\u{B1F}", t("ghanTa"));
        assert_eq!("\u{B38}\u{B02}\u{B38}\u{B30}", t("sansara"));
        assert_eq!("\u{B05}\u{B02}\u{B36}", t("ansha"));
        assert_eq!("\u{B1C}\u{B39}\u{B4D}\u{B28}", t("janha"));
        assert_eq!("\u{B1A}\u{B3F}\u{B39}\u{B4D}\u{B28}", t("chinha"));
    }

    #[test]
    fn ch_in_the_copula_and_endings_is_chha() {
        assert_eq!("\u{B05}\u{B1B}\u{B3F}", t("achi"));
        assert_eq!("\u{B15}\u{B30}\u{B41}\u{B1B}\u{B3F}", t("karuchi"));
        assert_eq!("\u{B05}\u{B1B}\u{B28}\u{B4D}\u{B24}\u{B3F}", t("achanti"));
        assert_eq!("\u{B17}\u{B1B}", t("gacha"));
        assert_eq!("\u{B1A}\u{B32}", t("chala"));
    }

    #[test]
    fn iba_ila_ela_close_on_a_long_aa() {
        assert_eq!("\u{B15}\u{B30}\u{B3F}\u{B2C}\u{B3E}", t("kariba"));
        assert_eq!("\u{B25}\u{B3F}\u{B32}\u{B3E}", t("thila"));
        assert_eq!("\u{B39}\u{B47}\u{B32}\u{B3E}", t("hela"));
        assert_eq!(
            "\u{B15}\u{B30}\u{B3F}\u{B2C}\u{B3E}\u{B15}\u{B41}",
            t("karibaku")
        );
    }

    #[test]
    fn v_is_ba_except_after_a_consonant() {
        assert_eq!("\u{B38}\u{B4D}\u{B71}\u{B30}", t("svara"));
        assert_eq!("\u{B2C}\u{B47}\u{B26}", t("veda"));
        assert_eq!("\u{B71}\u{B30}\u{B3F}", t("wari"));
    }

    #[test]
    fn variants_offer_the_long_as() {
        assert_eq!(
            vec![
                "\u{B18}\u{B30}",
                "\u{B18}\u{B30}\u{B3E}",
                "\u{B18}\u{B3E}\u{B30}"
            ],
            variants("ghara")
        );
        assert_eq!(
            vec![
                "\u{B15}\u{B30}\u{B3F}\u{B2C}\u{B3E}",
                "\u{B15}\u{B3E}\u{B30}\u{B3F}\u{B2C}\u{B3E}"
            ],
            variants("kariba"),
        );
    }
}

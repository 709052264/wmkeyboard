//! Malayalam's profile for the Indic engine: Mozhi's conventions, bent
//! toward how people type Malayalam in chats ("Manglish"). A port of
//! `MalayalamPhonetic.kt`. The nasal clusters are whole tokens of their own
//! (nt ന്റ, nth ന്ത, nd ണ്ട, ng ങ്ങ, nk ങ്ക, nch ഞ്ച, sht ഷ്ട, tth ത്ത); the
//! hook's rules:
//!
//! 1. "m" closing a word, or before s, sh, h, bh, r or v, is the anusvara;
//!    "mb" is മ്പ.
//! 2. "d" between vowels, or closing a word after one, is ട; elsewhere ദ.
//! 3. "sh" after u, o, ee or r is ഷ.
//! 4. "ch" between vowels is the geminate ച്ച.
//! 5. "nj" after a vowel is the geminate ഞ്ഞ.
//! 6. "l" after the inherent vowel, closing a word or before a case ending,
//!    is ള; a closing "-ol" is ോൾ.
//! 7. "n" in a closing "-anam" is ണ.
//! 8. r, l and L closing a syllable inside a word are chillus.
//! 9. A closing "u" is the half-u ്, with the past-tense exceptions.

use std::sync::OnceLock;

use crate::indic::hooks::rest_lower;
use crate::indic::{Engine, Model, Profile, Rule};
use crate::indic_script::IndicScript;

const TTA: &str = "\u{91F}";
const DA: &str = "\u{926}";
const PA: &str = "\u{92A}";
const SSA: &str = "\u{937}";
const NNA: &str = "\u{923}";
const LLA: &str = "\u{933}";
const RA: &str = "\u{930}";
const LA: &str = "\u{932}";
const CCA: &str = "\u{91A}\u{94D}\u{91A}";
const NYNY: &str = "\u{91E}\u{94D}\u{91E}";
const NN: &str = "\u{928}\u{94D}\u{928}";
const LONG_O: &str = "\u{913}";
const LONG_O_SIGN: &str = "\u{94B}";
const ANUSVARA: &str = "\u{902}";
const VIRAMA: &str = "\u{94D}";
const YA_LETTER: char = '\u{92F}';
const PA_LETTER: char = '\u{92A}';

/// An "m" before one of these is the anusvara (rule 1): स श ष ह भ र व.
const M_ANUSVARA_BEFORE: &[&str] = &[
    "\u{938}", "\u{936}", "\u{937}", "\u{939}", "\u{92D}", "\u{930}", "\u{935}",
];
/// The vowels after which "sh" is ഷ (rule 3).
const SH_AFTER: &[&str] = &["u", "uu", "oo", "o", "ee", "ii", "ru", "rri"];
/// What may follow the "-al" of the plural or a pronoun and keep it ള (rule 6).
const L_CASE_ENDINGS: &[&str] = &[
    "", "ude", "kku", "kk", "e", "il", "ilum", "ode", "odu", "aanu",
];
/// न ण र ल ळ: a virama after one of these would read as a chillu (rule 9).
const CHILLU_LETTERS: &[&str] = &["\u{928}", "\u{923}", "\u{930}", "\u{932}", "\u{933}"];
/// The closing clusters that keep a full ു (rule 9): the past tense and "-unnu".
const U_KEEPS: &[&str] = &[
    "\u{928}\u{94D}\u{928}",
    "\u{91A}\u{94D}\u{91A}",
    "\u{91A}",
    "\u{91E}\u{94D}\u{91E}",
    "\u{91E}",
    "\u{92F}\u{94D}\u{924}",
];

/// र ल ळ to ർ ൽ ൾ, written straight into the output (rule 8).
fn chillu_of(full: &str) -> Option<&'static str> {
    match full {
        "\u{930}" => Some("\u{D7C}"),
        "\u{932}" => Some("\u{D7D}"),
        "\u{933}" => Some("\u{D7E}"),
        _ => None,
    }
}

pub fn profile() -> Profile {
    let mut p = Profile::new("ml", IndicScript::Malayalam, Model::Explicit);
    p.dravidian_vowels = true;
    p.extra_consonants = vec![
        ("zh", "\u{934}"),
        ("L", "\u{933}"),
        ("R", "\u{931}"),
        ("rr", "\u{931}"),
        ("nj", "\u{91E}"),
        ("ng", "\u{919}\u{94D}\u{919}"),
        ("nk", "\u{919}\u{94D}\u{915}"),
        ("nt", "\u{928}\u{94D}\u{931}"),
        ("nth", "\u{928}\u{94D}\u{924}"),
        ("nd", "\u{923}\u{94D}\u{91F}"),
        ("ndh", "\u{928}\u{94D}\u{926}"),
        ("nch", "\u{91E}\u{94D}\u{91A}"),
        ("sht", "\u{937}\u{94D}\u{91F}"),
        ("tth", "\u{924}\u{94D}\u{924}"),
    ];
    p.override_ = vec![
        ("t", "\u{91F}"),
        ("th", "\u{924}"),
        ("T", "\u{920}"),
        ("Th", "\u{925}"),
        ("d", "\u{921}"),
        ("dh", "\u{926}"),
        ("D", "\u{921}"),
        ("Dh", "\u{922}"),
    ];
    p.final_virama = true;
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

fn hook(_engine: &Engine, tokens: Vec<Rule>) -> Vec<Rule> {
    let mut out = tokens;
    let n = out.len();

    for i in 0..n {
        let is_vowel = |j: isize| j >= 0 && (j as usize) < n && out[j as usize].is_vowel();
        let is_consonant = |j: isize| j >= 0 && (j as usize) < n && out[j as usize].is_consonant();
        let t = out[i].clone();
        if !t.is_consonant() {
            continue;
        }
        let i_ = i as isize;
        // 1. A closing "m", or one before s/sh/h/bh/r/v, is the anusvara; "mb" is മ്പ.
        if t.m == "b" && i >= 1 && out[i - 1].m == "m" {
            out[i] = Rule::consonant("b", PA);
            continue;
        }
        if t.m == "m" && is_vowel(i_ - 1) {
            if i == n - 1
                || (is_consonant(i_ + 1) && M_ANUSVARA_BEFORE.contains(&out[i + 1].full.as_str()))
            {
                out[i] = Rule::sign("m", ANUSVARA);
            }
            continue;
        }
        // 2. "d" between vowels, or closing a word after one, is ട; anywhere else ദ.
        if t.m == "d" {
            let intervocalic = is_vowel(i_ - 1) && (i == n - 1 || is_vowel(i_ + 1));
            out[i] = Rule::consonant("d", if intervocalic { TTA } else { DA });
            continue;
        }
        // 3. "sh" after u, o, ee or r is ഷ.
        let after_ruki = i >= 1
            && ((out[i - 1].is_vowel()
                && SH_AFTER.contains(&out[i - 1].m.to_lowercase().as_str()))
                || out[i - 1].full == RA);
        if t.m == "sh" && after_ruki {
            out[i] = Rule::consonant("sh", SSA);
            continue;
        }
        // 4. "ch" between vowels is ച്ച.
        if t.m == "ch" && is_vowel(i_ - 1) && is_vowel(i_ + 1) {
            out[i] = Rule::consonant("ch", CCA);
            continue;
        }
        // 5. "nj" after a vowel is ഞ്ഞ.
        if t.m == "nj" && is_vowel(i_ - 1) {
            out[i] = Rule::consonant("nj", NYNY);
            continue;
        }
        // 6. "l" after the inherent vowel, closing a word or before a case
        //    ending, is ള; the "-ol" closing a word is ോൾ.
        if t.m == "l" && is_vowel(i_ - 1) && i >= 2 {
            let rest = rest_lower(&out, i + 1);
            if out[i - 1].is_schwa() && L_CASE_ENDINGS.contains(&rest.as_str()) {
                out[i] = Rule::consonant("l", LLA);
            } else if out[i - 1].m == "o" && i == n - 1 {
                out[i - 1] = Rule::vowel("o", LONG_O, LONG_O_SIGN);
                out[i] = Rule::consonant("l", LLA);
            }
            continue;
        }
        // 7. "n" in a closing "-anam" is ണ.
        if t.m == "n"
            && i + 3 == n
            && i >= 1
            && out[i - 1].is_schwa()
            && out[i + 1].is_schwa()
            && out[i + 2].m == "m"
        {
            out[i] = Rule::consonant("n", NNA);
            continue;
        }
    }

    // 8. r, l and L closing a syllable inside a word are chillus, except
    //    before യ, before the same letter, and ല before പ.
    for i in 1..n.saturating_sub(1) {
        let t = out[i].clone();
        let Some(chillu) = chillu_of(&t.full) else {
            continue;
        };
        if t.is_consonant() && out[i - 1].is_vowel() && out[i + 1].is_consonant() {
            let next = out[i + 1].full.chars().next().unwrap();
            let own = t.full.chars().next().unwrap();
            if next != own && next != YA_LETTER && !(t.full == LA && next == PA_LETTER) {
                out[i] = Rule::sign(&t.m, chillu);
            }
        }
    }

    // 9. A closing "u" is the half-u ്, except after U_KEEPS or a chillu letter.
    if n >= 2 && out[n - 1].m == "u" && out[n - 2].is_consonant() {
        let mut j = n as isize - 2;
        let mut cluster: Vec<String> = Vec::new();
        while j >= 0 && out[j as usize].is_consonant() {
            cluster.insert(0, out[j as usize].full.clone());
            j -= 1;
        }
        let joined = cluster.join(VIRAMA);
        if j >= 0
            && !U_KEEPS.contains(&joined.as_str())
            && !CHILLU_LETTERS.contains(&cluster.last().unwrap().as_str())
            && !joined.ends_with(NN)
        {
            out[n - 1] = Rule::vowel("u", VIRAMA, VIRAMA);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    //! Mirrors `MalayalamPhoneticTest.kt`.

    use super::{transliterate as t, variants};

    #[test]
    fn every_vowel_is_typed_and_a_closing_consonant_is_a_chillu() {
        assert_eq!("\u{D05}\u{D2E}\u{D4D}\u{D2E}", t("amma"));
        assert_eq!("\u{D1E}\u{D3E}\u{D7B}", t("njaan"));
    }

    #[test]
    fn nasal_clusters_are_whole_tokens() {
        assert_eq!("\u{D0E}\u{D28}\u{D4D}\u{D31}\u{D46}", t("ente"));
        assert_eq!("\u{D0E}\u{D28}\u{D4D}\u{D24}\u{D4D}", t("enthu"));
        assert_eq!("\u{D09}\u{D23}\u{D4D}\u{D1F}\u{D4D}", t("undu"));
        assert_eq!("\u{D0E}\u{D19}\u{D4D}\u{D19}\u{D28}\u{D46}", t("engane"));
        assert_eq!(
            "\u{D2A}\u{D41}\u{D30}\u{D24}\u{D4D}\u{D24}\u{D4D}",
            t("puratthu")
        );
    }

    #[test]
    fn a_closing_m_is_the_anusvara_and_mb_is_mpa() {
        assert_eq!("\u{D38}\u{D41}\u{D16}\u{D02}", t("sukham"));
        assert_eq!("\u{D38}\u{D02}\u{D38}\u{D3E}\u{D30}\u{D02}", t("samsaaram"));
        assert_eq!("\u{D38}\u{D02}\u{D2D}\u{D35}\u{D02}", t("sambhavam"));
        assert_eq!("\u{D05}\u{D2E}\u{D4D}\u{D2A}\u{D32}\u{D02}", t("ambalam"));
    }

    #[test]
    fn d_between_vowels_is_tta() {
        assert_eq!("\u{D07}\u{D35}\u{D3F}\u{D1F}\u{D46}", t("ivide"));
        assert_eq!("\u{D35}\u{D40}\u{D1F}\u{D4D}", t("veedu"));
        assert_eq!("\u{D26}\u{D3F}\u{D35}\u{D38}\u{D02}", t("divasam"));
        assert_eq!(
            "\u{D05}\u{D26}\u{D4D}\u{D26}\u{D47}\u{D39}\u{D02}",
            t("addEham")
        );
    }

    #[test]
    fn sh_after_ruki_is_ssa() {
        assert_eq!(
            "\u{D38}\u{D28}\u{D4D}\u{D24}\u{D4B}\u{D37}\u{D02}",
            t("santhOsham")
        );
        assert_eq!("\u{D35}\u{D7C}\u{D37}\u{D02}", t("varsham"));
        assert_eq!("\u{D36}\u{D30}\u{D3F}", t("shari"));
    }

    #[test]
    fn ch_between_vowels_is_doubled() {
        assert_eq!(
            "\u{D15}\u{D41}\u{D31}\u{D1A}\u{D4D}\u{D1A}\u{D41}",
            t("kuRachu")
        );
        assert_eq!(
            "\u{D35}\u{D3F}\u{D33}\u{D3F}\u{D1A}\u{D4D}\u{D1A}\u{D41}",
            t("viLichu")
        );
    }

    #[test]
    fn nj_after_a_vowel_is_doubled() {
        assert_eq!("\u{D2A}\u{D31}\u{D1E}\u{D4D}\u{D1E}\u{D41}", t("paRanju"));
    }

    #[test]
    fn l_of_the_plural_and_pronouns_is_lla() {
        assert_eq!("\u{D28}\u{D3F}\u{D19}\u{D4D}\u{D19}\u{D7E}", t("ningal"));
        assert_eq!(
            "\u{D28}\u{D3F}\u{D19}\u{D4D}\u{D19}\u{D33}\u{D41}\u{D1F}\u{D46}",
            t("ningalude")
        );
        assert_eq!(
            "\u{D05}\u{D35}\u{D7E}\u{D15}\u{D4D}\u{D15}\u{D4D}",
            t("avalkku")
        );
        assert_eq!(
            "\u{D15}\u{D41}\u{D1F}\u{D4D}\u{D1F}\u{D3F}\u{D15}\u{D33}\u{D46}",
            t("kuttikale")
        );
        assert_eq!("\u{D07}\u{D2A}\u{D4D}\u{D2A}\u{D4B}\u{D7E}", t("ippol"));
    }

    #[test]
    fn n_in_a_closing_anam_is_nna() {
        assert_eq!("\u{D2A}\u{D23}\u{D02}", t("panam"));
        assert_eq!("\u{D15}\u{D3E}\u{D30}\u{D23}\u{D02}", t("kaaranam"));
        assert_eq!("\u{D2A}\u{D31}\u{D2F}\u{D23}\u{D02}", t("paRayanam"));
    }

    #[test]
    fn r_and_l_closing_a_syllable_are_chillus() {
        assert_eq!("\u{D36}\u{D7C}\u{D15}\u{D4D}\u{D15}\u{D30}", t("sharkkara"));
        assert_eq!("\u{D15}\u{D3E}\u{D30}\u{D4D}\u{D2F}\u{D02}", t("kaaryam"));
        assert_eq!("\u{D35}\u{D46}\u{D32}\u{D4D}\u{D32}\u{D02}", t("vellam"));
        assert_eq!("\u{D15}\u{D32}\u{D4D}\u{D2A}\u{D28}", t("kalpana"));
    }

    #[test]
    fn a_closing_u_is_the_half_u() {
        assert_eq!("\u{D05}\u{D24}\u{D4D}", t("athu"));
        assert_eq!(
            "\u{D0E}\u{D28}\u{D3F}\u{D15}\u{D4D}\u{D15}\u{D4D}",
            t("enikku")
        );
        assert_eq!("\u{D35}\u{D28}\u{D4D}\u{D28}\u{D41}", t("vannu"));
        assert_eq!("\u{D1A}\u{D46}\u{D2F}\u{D4D}\u{D24}\u{D41}", t("cheythu"));
        assert_eq!("\u{D12}\u{D30}\u{D41}", t("oru"));
    }

    #[test]
    fn whole_sentence() {
        assert_eq!("\u{D1E}\u{D3E}\u{D7B} \u{D07}\u{D35}\u{D3F}\u{D1F}\u{D46} \u{D09}\u{D23}\u{D4D}\u{D1F}\u{D4D}.", t("njaan ivide undu."));
    }

    #[test]
    fn the_last_inner_a_is_offered_long() {
        assert_eq!(
            vec!["\u{D05}\u{D35}\u{D7B}", "\u{D05}\u{D35}\u{D3E}\u{D7B}"],
            variants("avan")
        );
    }
}

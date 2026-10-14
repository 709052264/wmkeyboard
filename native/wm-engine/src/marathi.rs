//! Marathi phonetic: roman letters in, Devanagari out, on the Indic engine's
//! North model. A port of `MarathiPhonetic.kt`; the hook's rules, in the
//! order they run:
//!
//! 1. Glued postpositions keep their own spelling ("sathi" साठी, "mule" मुळे).
//! 2. i or u right after another vowel is long.
//! 3. sh before t or th is retroflex; 3b "shtr", "vh", "lh" as one letter;
//!    3c the prefix maha- is महा-; 3d the present plural -tat is -तात.
//! 4. The absolutive "-un" is -ऊन.
//! 5. A closing "au" is आऊ.
//! 6. The future participle is -णार.
//! 7. The plural oblique -आं- before a case ending.
//! 8. The oblique -या- before a case ending.
//! 9. The noun oblique -आ- before the genitive.
//! 10. A closing "Cra" or "shta" keeps the inherent vowel.

use std::sync::OnceLock;

use crate::indic::hooks::{has_vowel, rest_lower};
use crate::indic::{Engine, Kind, Model, Profile, Rule};
use crate::indic_script::IndicScript;

const A: &str = "\u{905}";
const AA: &str = "\u{906}";
const II: &str = "\u{908}";
const U: &str = "\u{909}";
const UU: &str = "\u{90A}";
const E: &str = "\u{90F}";
const AA_SIGN: &str = "\u{93E}";
const II_SIGN: &str = "\u{940}";
const U_SIGN: &str = "\u{941}";
const UU_SIGN: &str = "\u{942}";
const E_SIGN: &str = "\u{947}";
const KA: &str = "\u{915}";
const TTA: &str = "\u{91F}";
const TTHA: &str = "\u{920}";
const DDA: &str = "\u{921}";
const NNA: &str = "\u{923}";
const TA: &str = "\u{924}";
const THA: &str = "\u{925}";
const NA: &str = "\u{928}";
const PA: &str = "\u{92A}";
const MA: &str = "\u{92E}";
const YA: &str = "\u{92F}";
const RA: &str = "\u{930}";
const LA: &str = "\u{932}";
const LLA: &str = "\u{933}";
const VA: &str = "\u{935}";
const SHA: &str = "\u{936}";
const SSA: &str = "\u{937}";
const SA: &str = "\u{938}";
const HA: &str = "\u{939}";
const VIRAMA: &str = "\u{94D}";
const ANUSVARA: &str = "\u{902}";
const DHYA: &str = "\u{927}\u{94D}\u{92F}";
const SHTRA: &str = "\u{937}\u{94D}\u{91F}\u{94D}\u{930}";
const VHA: &str = "\u{935}\u{94D}\u{939}";
const LHA: &str = "\u{932}\u{94D}\u{939}";

const ENDINGS: &[&str] = &[
    "cha", "che", "chi", "chya", "hun", "kade", "kadun", "la", "madhe", "madhun", "madhye", "mule",
    "na", "ne", "ni", "nu", "paryant", "pasun", "sathi", "tun", "un", "var", "varun",
];
const SOFT_ENDINGS: &[&str] = &[
    "le", "li", "lo", "naar", "nar", "ta", "tat", "te", "tes", "to", "tos",
];

/// Rule 1: each postposition's roman letters and what each token becomes.
const POSTPOSITIONS: &[(&str, &[&str])] = &[
    ("madhe", &[MA, "", DHYA, E_SIGN]),
    ("madhye", &[MA, "", DHYA, E_SIGN]),
    ("sathi", &[SA, AA_SIGN, TTHA, II_SIGN]),
    ("kade", &[KA, "", DDA, E_SIGN]),
    ("kadun", &[KA, "", DDA, UU_SIGN, NA]),
    ("pasun", &[PA, AA_SIGN, SA, UU_SIGN, NA]),
    ("mule", &[MA, U_SIGN, LLA, E_SIGN]),
];

/// Rule 7: the plural oblique's tails after the "a"; "V" is any vowel.
const PLURAL_TAILS: &[&[&str]] = &[&["n", "ch", "V"], &["n", "ch", "y", "a"], &["n", "n", "V"]];
/// Rule 9: the genitive's tails after the consonant and its "a".
const GENITIVE_TAILS: &[&[&str]] = &[&["ch", "V"], &["ch", "y", "a"]];
/// Rule 8: what may follow the oblique -या-, besides the endings.
const OBLIQUE_EXTRA: &[&str] = &[
    "t", "ch", "nchi", "ncha", "nche", "nchya", "nni", "nna", "nno",
];
/// Rule 6: the vowels that may follow -णार.
const FINAL_AFTER_NAR: &[&str] = &["a", "i", "e"];

pub fn profile() -> Profile {
    let mut p = Profile::new("mr", IndicScript::Devanagari, Model::North);
    p.extra_consonants = vec![
        ("L", "\u{933}"),
        ("R", "\u{921}"),
        ("Rh", "\u{922}"),
        ("zh", "\u{91D}"),
    ];
    p.override_ = vec![("z", "\u{91D}")];
    p.endings = ENDINGS.to_vec();
    p.soft_endings = SOFT_ENDINGS.to_vec();
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

/// A vowel sign's independent form.
fn vowel_full(sign: &str) -> &'static str {
    match sign {
        "" => A,
        AA_SIGN => AA,
        II_SIGN => II,
        U_SIGN => U,
        UU_SIGN => UU,
        E_SIGN => E,
        _ => panic!("no independent form for sign {sign:?}"),
    }
}

/// A vowel rule with the typed letters `m` and the sign `sign`.
fn vowel(m: &str, sign: &str) -> Rule {
    Rule::vowel(m, vowel_full(sign), sign)
}

/// The long twin of a short a/i/u token, keeping the typed letters.
fn lengthened(token: &Rule) -> Rule {
    match token.m.to_lowercase().as_str() {
        "a" => vowel(&token.m, AA_SIGN),
        "i" => vowel(&token.m, II_SIGN),
        "u" => vowel(&token.m, UU_SIGN),
        _ => token.clone(),
    }
}

/// A tail pattern's slot: "V" is any vowel, anything else the typed letters.
fn fits(token: &Rule, want: &str) -> bool {
    (want == "V" && token.is_vowel()) || token.m == want
}

fn hook(engine: &Engine, tokens: Vec<Rule>) -> Vec<Rule> {
    let mut t = tokens;
    let mut n = t.len();

    // 1. Glued postpositions keep their own spelling.
    for &(suffix, spell) in POSTPOSITIONS {
        if n < spell.len() {
            continue;
        }
        let from = n - spell.len();
        if rest_lower(&t, from) != suffix || !has_vowel(&t[..from]) {
            continue;
        }
        // A capital M or H (anusvara, visarga) reads as "m"/"h" here, and has
        // no vowel sign to become.
        if t[from..].iter().any(|r| r.kind == Kind::Sign) {
            continue;
        }
        for (k, &s) in spell.iter().enumerate() {
            let token = &t[from + k];
            t[from + k] = if token.is_consonant() {
                Rule::consonant(&token.m, s)
            } else {
                vowel(&token.m, s)
            };
        }
        break;
    }

    for i in 0..n {
        let token = t[i].clone();
        let prev = if i > 0 { Some(t[i - 1].clone()) } else { None };
        // 2. i/u right after another vowel is long.
        if token.is_vowel()
            && (token.m == "i" || token.m == "u")
            && prev.as_ref().is_some_and(Rule::is_vowel)
        {
            t[i] = lengthened(&token);
        }
        // 3. sh before t/th is retroflex.
        if token.is_consonant()
            && (token.full == TA || token.full == THA)
            && prev.as_ref().is_some_and(|p| p.full == SHA)
        {
            t[i - 1] = Rule::consonant(&prev.unwrap().m, SSA);
            t[i] = Rule::consonant(&token.m, if token.full == TA { TTA } else { TTHA });
        }
    }

    // 3b. Clusters the generic table does not join, written as one letter.
    let mut out: Vec<Rule> = Vec::with_capacity(t.len());
    for token in t {
        if let Some(p) = out.last().cloned() {
            if p.is_consonant() && token.is_consonant() {
                if p.full == TTA
                    && token.full == RA
                    && out.len() >= 2
                    && out[out.len() - 2].full == SSA
                {
                    let sh = out[out.len() - 2].clone();
                    out.pop();
                    let last = out.len() - 1;
                    out[last] = Rule::consonant(&format!("{}{}{}", sh.m, p.m, token.m), SHTRA);
                    continue;
                }
                let pair = if p.full == VA && token.full == HA {
                    Some(VHA)
                } else if p.full == LA && token.full == HA {
                    Some(LHA)
                } else {
                    None
                };
                if let Some(pair) = pair {
                    let last = out.len() - 1;
                    out[last] = Rule::consonant(&format!("{}{}", p.m, token.m), pair);
                    continue;
                }
            }
        }
        out.push(token);
    }
    t = out;
    n = t.len();

    // 3c. The prefix maha- is महा-.
    if n > 4
        && t[0].m == "m"
        && t[1].m == "a"
        && t[2].m == "h"
        && t[3].m == "a"
        && t[4..].iter().any(Rule::is_consonant)
    {
        t[3] = vowel("a", AA_SIGN);
    }

    // 3d. The present plural -tat is -तात.
    if n > 3
        && t[n - 3].m == "t"
        && t[n - 2].m == "a"
        && t[n - 1].m == "t"
        && has_vowel(&t[..n - 3])
    {
        t[n - 2] = vowel("a", AA_SIGN);
    }

    // 4. The absolutive / ablative "-un" is -ऊन.
    if n >= 3 && t[n - 1].full == NA && t[n - 2].m == "u" && has_vowel(&t[..n - 2]) {
        t[n - 2] = lengthened(&t[n - 2]);
    }

    // 5. A closing "au" is आऊ.
    let au = if n >= 1 && t[n - 1].m == "au" {
        Some(n - 1)
    } else if n >= 2 && t[n - 2].m == "au" && t[n - 1].full == NA {
        Some(n - 2)
    } else {
        None
    };
    if let Some(au) = au {
        if au > 0 && t[au - 1].is_consonant() {
            t.remove(au);
            t.insert(au, vowel("a", AA_SIGN));
            t.insert(au + 1, vowel("u", UU_SIGN));
            n = t.len();
        }
    }

    // 6. The future participle -णार; a vowel after it (karnara) shifts it one
    //    token in from the end.
    let nar = if n >= 5 && t[n - 1].is_vowel() && FINAL_AFTER_NAR.contains(&t[n - 1].m.as_str()) {
        4
    } else {
        3
    };
    if n > nar
        && t[n - nar + 2].full == RA
        && (t[n - nar + 1].m == "a" || t[n - nar + 1].m == "aa")
        && t[n - nar].full == NA
    {
        let before = &t[..n - nar];
        let stem_end = before[before.len() - 1].clone();
        let consonant_stem = stem_end.is_consonant() && has_vowel(before);
        let open_stem = stem_end.is_vowel()
            && before.len() >= 2
            && before[before.len() - 2].is_consonant()
            && before.iter().filter(|r| r.is_vowel()).count() == 1;
        if (consonant_stem || open_stem) && (nar == 3 || stem_end.is_consonant()) {
            // No verb root ends in a schwa: janar जाणार.
            if stem_end.is_schwa() {
                t[n - nar - 1] = vowel(&stem_end.m, AA_SIGN);
            }
            t[n - nar] = Rule::consonant(&t[n - nar].m.clone(), NNA);
            t[n - nar + 1] = vowel(&t[n - nar + 1].m.clone(), AA_SIGN);
        }
    }

    // 7. The plural oblique -आं- before a case ending.
    for tail in PLURAL_TAILS {
        let m = tail.len() + 1;
        if n < m + 1 {
            continue;
        }
        let head = t[n - m].clone();
        if !head.is_schwa() {
            continue;
        }
        if tail
            .iter()
            .enumerate()
            .all(|(j, want)| fits(&t[n - m + 1 + j], want))
        {
            let stem = &t[..n - m];
            if has_vowel(stem) || (stem.len() >= 2 && stem.iter().all(Rule::is_consonant)) {
                t[n - m] = vowel(&head.m, AA_SIGN);
                t[n - m + 1] = Rule::sign("n", ANUSVARA);
            }
            break;
        }
    }

    // 8. The oblique -या- before a case ending.
    for i in 2..t.len().saturating_sub(1) {
        if t[i].is_schwa() && t[i - 1].full == YA && t[i - 2].is_consonant() {
            let rest = rest_lower(&t, i + 1);
            let oblique = engine.profile.endings.contains(&rest.as_str())
                || OBLIQUE_EXTRA.contains(&rest.as_str())
                || engine
                    .profile
                    .endings
                    .iter()
                    .any(|e| e.len() >= 3 && rest.starts_with(e));
            if oblique {
                t[i] = vowel(&t[i].m.clone(), AA_SIGN);
            }
        }
    }

    // 9. The noun oblique -आ- before the genitive.
    for tail in GENITIVE_TAILS {
        let m = tail.len() + 2;
        if t.len() < m + 1 {
            continue;
        }
        let from = t.len() - m;
        let consonant = t[from].clone();
        let schwa = t[from + 1].clone();
        if consonant.is_consonant()
            && schwa.is_schwa()
            && has_vowel(&t[..from])
            && tail
                .iter()
                .enumerate()
                .all(|(j, want)| fits(&t[from + 2 + j], want))
        {
            t[from + 1] = vowel(&schwa.m, AA_SIGN);
        }
    }

    // 10. A closing "Cra" or "shta" keeps the inherent vowel.
    let size = t.len();
    if size >= 3 && t[size - 1].m == "a" {
        let closing = &t[size - 2];
        let third = &t[size - 3];
        let keeps = closing.full.ends_with(&format!("{VIRAMA}{RA}"))
            || ((closing.full == TTA || closing.full == TTHA) && third.full == SSA)
            || (size >= 4 && closing.full == RA && third.is_consonant() && third.full != RA);
        if keeps {
            t[size - 1] = Rule::vowel("a", A, "");
        }
    }
    t
}

#[cfg(test)]
mod tests {
    //! Mirrors `MarathiPhoneticTest.kt`.

    use super::{transliterate as t, variants};

    #[test]
    fn plain_words() {
        assert_eq!("\u{906}\u{939}\u{947}", t("aahe"));
        assert_eq!("\u{92E}\u{940}", t("mi"));
        assert_eq!("\u{915}\u{93E}\u{92F}", t("kaay"));
    }

    #[test]
    fn marathi_letters() {
        assert_eq!("\u{915}\u{933}\u{93E}", t("kaLa"));
        assert_eq!("\u{91D}\u{932}\u{93E}", t("zala"));
        assert_eq!("\u{918}\u{921}\u{940}", t("ghaRi"));
    }

    #[test]
    fn glued_postpositions_keep_their_spelling() {
        assert_eq!(
            "\u{918}\u{930}\u{938}\u{93E}\u{920}\u{940}",
            t("gharasathi")
        );
        assert_eq!(
            "\u{918}\u{930}\u{915}\u{921}\u{942}\u{928}",
            t("gharakadun")
        );
        assert_eq!(
            "\u{918}\u{930}\u{92A}\u{93E}\u{938}\u{942}\u{928}",
            t("gharapasun")
        );
        assert_eq!(
            "\u{918}\u{930}\u{92E}\u{927}\u{94D}\u{92F}\u{947}",
            t("gharamadhe")
        );
    }

    #[test]
    fn i_or_u_after_a_vowel_is_long() {
        assert_eq!("\u{939}\u{94B}\u{908}\u{932}", t("hoil"));
        assert_eq!("\u{92F}\u{947}\u{90A}\u{928}", t("yeun"));
    }

    #[test]
    fn sh_before_t_is_retroflex() {
        assert_eq!("\u{917}\u{94B}\u{937}\u{94D}\u{91F}", t("goshta"));
        assert_eq!(
            "\u{936}\u{94D}\u{930}\u{947}\u{937}\u{94D}\u{920}",
            t("shreshtha")
        );
    }

    #[test]
    fn clusters_written_as_one_letter() {
        assert_eq!(
            "\u{92E}\u{939}\u{93E}\u{930}\u{937}\u{94D}\u{91F}\u{94D}\u{930}",
            t("maharashtra")
        );
        assert_eq!("\u{915}\u{947}\u{935}\u{94D}\u{939}\u{93E}", t("kevha"));
        assert_eq!("\u{928}\u{935}\u{94D}\u{939}\u{924}\u{93E}", t("navhta"));
        assert_eq!("\u{91C}\u{93F}\u{932}\u{94D}\u{939}\u{93E}", t("jilha"));
    }

    #[test]
    fn maha_prefix_and_tat_plural() {
        assert_eq!("\u{92E}\u{939}\u{93E}\u{917}", t("mahag"));
        assert_eq!("\u{915}\u{930}\u{924}\u{93E}\u{924}", t("kartat"));
    }

    #[test]
    fn absolutive_un_is_long() {
        assert_eq!("\u{915}\u{930}\u{942}\u{928}", t("karun"));
        assert_eq!("\u{92C}\u{918}\u{942}\u{928}", t("baghun"));
    }

    #[test]
    fn closing_au_is_aa_u() {
        assert_eq!("\u{92D}\u{93E}\u{90A}", t("bhau"));
        assert_eq!("\u{91C}\u{93E}\u{90A}\u{928}", t("jaun"));
    }

    #[test]
    fn future_participle_is_nnaar() {
        assert_eq!("\u{915}\u{930}\u{923}\u{93E}\u{930}", t("karnar"));
        assert_eq!("\u{91C}\u{93E}\u{923}\u{93E}\u{930}", t("janar"));
        assert_eq!("\u{92F}\u{947}\u{923}\u{93E}\u{930}", t("yenar"));
        assert_eq!("\u{915}\u{930}\u{923}\u{93E}\u{930}\u{93E}", t("karnara"));
    }

    #[test]
    fn plural_oblique() {
        assert_eq!(
            "\u{924}\u{94D}\u{92F}\u{93E}\u{902}\u{91A}\u{940}",
            t("tyanchi")
        );
        assert_eq!(
            "\u{932}\u{94B}\u{915}\u{93E}\u{902}\u{928}\u{93E}",
            t("lokanna")
        );
        assert_eq!(
            "\u{92E}\u{93F}\u{924}\u{94D}\u{930}\u{93E}\u{902}\u{928}\u{94B}",
            t("mitranno")
        );
        assert_eq!(
            "\u{924}\u{94D}\u{92F}\u{93E}\u{902}\u{91A}\u{94D}\u{92F}\u{93E}",
            t("tyanchya")
        );
    }

    #[test]
    fn oblique_yaa() {
        assert_eq!("\u{924}\u{94D}\u{92F}\u{93E}\u{932}\u{93E}", t("tyala"));
        assert_eq!(
            "\u{924}\u{94D}\u{92F}\u{93E}\u{91A}\u{94D}\u{92F}\u{93E}",
            t("tyachya")
        );
        assert_eq!(
            "\u{924}\u{94D}\u{92F}\u{93E}\u{92E}\u{941}\u{933}\u{947}",
            t("tyamule")
        );
    }

    #[test]
    fn noun_oblique_before_genitive() {
        assert_eq!(
            "\u{918}\u{930}\u{93E}\u{91A}\u{94D}\u{92F}\u{93E}",
            t("gharachya")
        );
        assert_eq!("\u{926}\u{947}\u{935}\u{93E}\u{91A}\u{93E}", t("devacha"));
    }

    #[test]
    fn closing_cluster_keeps_inherent_vowel() {
        assert_eq!("\u{92E}\u{93F}\u{924}\u{94D}\u{930}", t("mitra"));
        assert_eq!("\u{926}\u{941}\u{938}\u{930}", t("dusra"));
    }

    #[test]
    fn variants_offer_the_other_readings() {
        assert_eq!(
            vec![
                "\u{930}\u{924}\u{94D}\u{930}",
                "\u{930}\u{93E}\u{924}\u{94D}\u{930}",
                "\u{930}\u{924}\u{930}"
            ],
            variants("ratra"),
        );
    }
}

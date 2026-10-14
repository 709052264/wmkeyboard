//! Tamil phonetic typing on the Indic engine, on the chat ("Tanglish")
//! conventions people actually type. A port of `TamilPhonetic.kt`; the
//! hook's rules:
//!
//! 1. "ndr" is ன்ற (nandri நன்றி).
//! 2. A vowel, "tr" or "ttr", a vowel is ற்ற (kaatru காற்று).
//! 3. An r after n or l is ற.
//! 4. A lone k, ch, t or p between vowels is doubled; 4b after ர or ழ too.
//! 5. A word-final e or o is long.
//! 6. The verb endings -en and -om are long in a word of two syllables.
//! 7. The plural -gal / -kkal is கள்.
//! 8. The case ending -oda has a long o.
//! 9. The formal present-tense marker -kir- / -gir- is கிற.
//! 10. The spoken present-tense marker -ur- before a personal ending is உற.
//! 11. An "i" after e, aa, o is the glide ய்.
//! 12. The obligative -anum after a verb stem is ணும்.
//! 13. "tth" is த்த.

use std::sync::OnceLock;

use crate::indic::hooks::rest;
use crate::indic::{Engine, Kind, Model, Profile, Rule};
use crate::indic_script::IndicScript;

/// Rule 4: the stops whose unvoiced letter between vowels means the geminate.
const GEMINATE: &[&str] = &["k", "ch", "c", "t", "p"];
/// Rule 10: what may follow the -ur- marker, all the rest of the word.
const PRESENT_ENDINGS: &[&str] = &[
    "en", "En", "aen", "om", "Om", "aan", "aanga", "aa", "a", "iya", "inga", "eenga", "aal",
    "aanunga",
];
/// Rule 11: the vowels after which "i" is the glide.
const GLIDE_BEFORE_I: &[&str] = &["e", "E", "ae", "aa", "A", "o", "O", "oo"];
/// Rule 9: the vowel that may follow -kir-.
const AFTER_KIR: &[&str] = &["e", "E", "ae", "aa", "o", "O"];
/// Rule 12: the consonants a verb stem ends in before the obligative -anum.
const VERB_STEM_END: &[&str] = &["k", "g", "ch", "s", "d", "t", "r", "l", "y", "p", "ng"];
/// Rule 4b: the stops doubled after ர or ழ.
const AFTER_LIQUID: &[&str] = &["k", "ch", "c", "th", "p"];
/// Rule 7: the vowels after which -kal is not a plural.
const A_VOWELS: &[&str] = &["a", "aa", "A"];

/// ன, written straight, past the engine's choice between ந and ன.
fn alveolar_n() -> Rule {
    Rule::consonant("n", "\u{929}")
}
/// ற.
fn rra() -> Rule {
    Rule::consonant("R", "\u{931}")
}
/// ள.
fn lla() -> Rule {
    Rule::consonant("L", "\u{933}")
}

/// Rules 5 and 6: the long rule for a short e or o.
fn long_of(m: &str) -> Option<&'static str> {
    match m {
        "e" => Some("E"),
        "o" => Some("O"),
        _ => None,
    }
}

pub fn profile() -> Profile {
    let mut p = Profile::new("ta", IndicScript::Tamil, Model::Explicit);
    p.dravidian_vowels = true;
    p.extra_consonants = vec![
        ("zh", "\u{934}"),
        ("L", "\u{933}"),
        ("R", "\u{931}"),
        ("rr", "\u{931}"),
        ("nj", "\u{91E}\u{94D}\u{91A}"),
        ("S", "\u{938}"),
    ];
    p.override_ = vec![("s", "\u{91A}"), ("t", "\u{91F}"), ("d", "\u{921}")];
    p.final_virama = true;
    p.tamil_n = true;
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

fn hook(engine: &Engine, t: Vec<Rule>) -> Vec<Rule> {
    let n = t.len();
    let vowel = |i: isize| -> bool { i >= 0 && (i as usize) < n && t[i as usize].is_vowel() };
    let m_at = |i: usize| -> Option<&str> { t.get(i).map(|r| r.m.as_str()) };

    let mut out: Vec<Rule> = Vec::with_capacity(n + 4);
    let mut i = 0;
    while i < n {
        let m = t[i].m.as_str();
        let next = m_at(i + 1);
        let next2 = m_at(i + 2);
        let written = out.last().map(|r| r.m.clone());
        let written = written.as_deref();
        // 11. "ei" is ெய் (sei செய், seiya செய்ய, nei நெய்).
        if m == "i" && written.is_some_and(|w| GLIDE_BEFORE_I.contains(&w)) {
            out.push(engine.rule("y"));
            i += 1;
            continue;
        }
        // 13. "tth" is த்த: the "t" is dropped for a second "th", and the "th"
        // itself is still read on the next step.
        if m == "t" && next == Some("th") {
            out.push(t[i + 1].clone());
            i += 1;
            continue;
        }
        // 1. "ndr" is ன்ற.
        if m == "n" && next == Some("d") && next2 == Some("r") && i > 0 {
            out.push(alveolar_n());
            out.push(rra());
            i += 3;
            continue;
        }
        // 2. "tr" / "ttr" between vowels is ற்ற.
        if m == "t" && vowel(i as isize - 1) {
            if next == Some("r") && vowel(i as isize + 2) {
                out.push(rra());
                out.push(rra());
                i += 2;
                continue;
            }
            if next == Some("t") && next2 == Some("r") && vowel(i as isize + 3) {
                out.push(rra());
                out.push(rra());
                i += 3;
                continue;
            }
        }
        // 3. "nr" / "lr" is ன்ற / ல்ற.
        if m == "r" && (written == Some("n") || written == Some("l")) {
            out.push(rra());
            i += 1;
            continue;
        }
        // 9. The formal present tense -kir- / -gir-.
        if m == "r"
            && out.len() >= 3
            && written == Some("i")
            && (out[out.len() - 2].m == "k" || out[out.len() - 2].m == "g")
            && out[out.len() - 3].kind != Kind::Sign
            && out[..out.len() - 1].iter().any(Rule::is_vowel)
            && (next.is_some_and(|nx| AFTER_KIR.contains(&nx))
                || (next == Some("a") && (next2 == Some("th") || next2 == Some("dh"))))
        {
            out.push(rra());
            i += 1;
            continue;
        }
        // 10. The spoken present tense -ur- before a personal ending.
        if m == "r"
            && written == Some("u")
            && out.iter().filter(|r| r.is_vowel()).count() >= 2
            && PRESENT_ENDINGS.contains(&rest(&t, i + 1).as_str())
        {
            out.push(rra());
            i += 1;
            continue;
        }
        // 4b. A stop after ர or ழ is doubled.
        if AFTER_LIQUID.contains(&m)
            && i > 0
            && (t[i - 1].m == "r" || t[i - 1].m == "zh")
            && vowel(i as isize - 2)
            && vowel(i as isize + 1)
        {
            out.push(t[i].clone());
            out.push(t[i].clone());
            i += 1;
            continue;
        }
        // 4. A lone unvoiced stop between vowels is the geminate.
        if GEMINATE.contains(&m) && vowel(i as isize - 1) && vowel(i as isize + 1) {
            out.push(t[i].clone());
            out.push(t[i].clone());
            i += 1;
            continue;
        }
        out.push(t[i].clone());
        i += 1;
    }

    let size = out.len();
    let vowels = out.iter().filter(|r| r.is_vowel()).count();
    // 5. A final e / o is long.
    if size >= 2 && out[size - 2].is_consonant() {
        if let Some(long) = long_of(&out[size - 1].m) {
            out[size - 1] = engine.rule(long);
        }
    }
    // 6. The verb endings -en / -om are long.
    if size >= 3 && vowels >= 2 && out[size - 2].is_vowel() {
        let v = out[size - 2].m.clone();
        let c = out[size - 1].m.clone();
        if (v == "e" && c == "n") || (v == "o" && c == "m") {
            out[size - 2] = engine.rule(long_of(&v).unwrap());
        }
    }
    // 8. The case ending -oda has a long o.
    if size >= 4
        && vowels >= 3
        && out[size - 3].m == "o"
        && out[size - 2].m == "d"
        && out[size - 1].m == "a"
    {
        out[size - 3] = engine.rule("O");
    }
    // 12. The obligative -anum after a verb stem is ணும்.
    if size >= 5
        && vowels >= 3
        && out[size - 4].m == "a"
        && out[size - 3].m == "n"
        && out[size - 2].m == "u"
        && out[size - 1].m == "m"
        && VERB_STEM_END.contains(&out[size - 5].m.as_str())
    {
        out[size - 3] = engine.rule("N");
    }
    // 7. The plural -gal / -kkal is கள், closing the word or before a case
    // ending that opens on a vowel other than a.
    for j in 2..size {
        let plural = out[j].m == "l"
            && out[j - 1].m == "a"
            && (out[j - 2].m == "g" || (out[j - 2].m == "k" && j >= 3 && out[j - 3].m == "k"));
        let closes =
            j == size - 1 || (out[j + 1].is_vowel() && !A_VOWELS.contains(&out[j + 1].m.as_str()));
        if plural && closes {
            out[j] = lla();
        }
    }
    out
}

#[cfg(test)]
mod tests {
    //! Mirrors `TamilPhoneticTest.kt`.

    use super::{transliterate as t, variants};

    #[test]
    fn plain_words_and_sentences() {
        assert_eq!(
            "\u{BB5}\u{BA9}\u{B95}\u{BCD}\u{B95}\u{BAE}\u{BCD}",
            t("vanakkam")
        );
        assert_eq!("\u{B85}\u{BB5}\u{BA9}\u{BCD}", t("avan"));
        assert_eq!("\u{BA8}\u{BBE}\u{BA9}\u{BCD}", t("naan"));
        assert_eq!(
            "\u{BA8}\u{BBE}\u{BA9}\u{BCD} \u{BB5}\u{BA8}\u{BCD}\u{BA4}\u{BC7}\u{BA9}\u{BCD}!",
            t("naan vanthen!")
        );
    }

    #[test]
    fn ndr_is_alveolar_n_ra() {
        assert_eq!("\u{BA8}\u{BA9}\u{BCD}\u{BB1}\u{BBF}", t("nandri"));
        assert_eq!("\u{B87}\u{BA9}\u{BCD}\u{BB1}\u{BC1}", t("indru"));
    }

    #[test]
    fn tr_between_vowels_is_doubled_ra() {
        assert_eq!("\u{B95}\u{BBE}\u{BB1}\u{BCD}\u{BB1}\u{BC1}", t("kaatru"));
        assert_eq!("\u{B95}\u{BBE}\u{BB1}\u{BCD}\u{BB1}\u{BC1}", t("kaattru"));
        assert_eq!("\u{B9F}\u{BCD}\u{BB0}\u{BC8}\u{BA9}\u{BCD}", t("train"));
    }

    #[test]
    fn r_after_n_or_l_is_ra() {
        assert_eq!(
            "\u{B9A}\u{BCA}\u{BB2}\u{BCD}\u{BB1}\u{BC7}\u{BA9}\u{BCD}",
            t("solren")
        );
        assert_eq!("\u{B8E}\u{BA9}\u{BCD}\u{BB1}\u{BC1}", t("enru"));
    }

    #[test]
    fn lone_stop_between_vowels_is_doubled() {
        assert_eq!(
            "\u{B87}\u{BB0}\u{BC1}\u{B95}\u{BCD}\u{B95}\u{BC1}",
            t("iruku")
        );
        assert_eq!("\u{BAA}\u{BCA}\u{B9A}\u{BCD}\u{B9A}\u{BC1}", t("pochu"));
        assert_eq!("\u{BB5}\u{BC0}\u{B9F}\u{BCD}\u{B9F}\u{BC1}", t("veetu"));
    }

    #[test]
    fn stop_after_ra_or_zha_is_doubled() {
        assert_eq!(
            "\u{BB5}\u{BBE}\u{BB4}\u{BCD}\u{B95}\u{BCD}\u{B95}\u{BC8}",
            t("vaazhkai")
        );
        assert_eq!(
            "\u{BAA}\u{BBE}\u{BB0}\u{BCD}\u{BA4}\u{BCD}\u{BA4}\u{BC7}\u{BA9}\u{BCD}",
            t("paarthen")
        );
    }

    #[test]
    fn final_e_or_o_is_long() {
        assert_eq!("\u{BA4}\u{BBE}\u{BA9}\u{BC7}", t("thaane"));
        assert_eq!("\u{BAA}\u{BCB}", t("po"));
        assert_eq!("\u{B87}\u{BAA}\u{BCD}\u{BAA}\u{BCB}", t("ipo"));
    }

    #[test]
    fn verb_endings_en_and_om_are_long() {
        assert_eq!(
            "\u{BB5}\u{BA8}\u{BCD}\u{BA4}\u{BC7}\u{BA9}\u{BCD}",
            t("vanthen")
        );
        assert_eq!("\u{BAA}\u{BCA}\u{BB5}\u{BCB}\u{BAE}\u{BCD}", t("povom"));
    }

    #[test]
    fn plural_is_kal() {
        assert_eq!(
            "\u{BA8}\u{BC0}\u{B99}\u{BCD}\u{B95}\u{BB3}\u{BCD}",
            t("neengal")
        );
        assert_eq!("\u{BAE}\u{B95}\u{BCD}\u{B95}\u{BB3}\u{BCD}", t("makkal"));
        assert_eq!(
            "\u{B89}\u{B99}\u{BCD}\u{B95}\u{BB3}\u{BC1}\u{B95}\u{BCD}\u{B95}\u{BC1}",
            t("ungalukku")
        );
        assert_eq!("\u{B95}\u{BB2}\u{BC8}", t("kalai"));
    }

    #[test]
    fn case_ending_oda_is_long() {
        assert_eq!("\u{B8E}\u{BA9}\u{BCD}\u{BA9}\u{BCB}\u{B9F}", t("ennoda"));
    }

    #[test]
    fn formal_present_kir_is_ra() {
        assert_eq!(
            "\u{B87}\u{BB0}\u{BC1}\u{B95}\u{BCD}\u{B95}\u{BBF}\u{BB1}\u{BC7}\u{BA9}\u{BCD}",
            t("irukkiren")
        );
        assert_eq!(
            "\u{B87}\u{BB0}\u{BC1}\u{B95}\u{BCD}\u{B95}\u{BBF}\u{BB1}\u{BA4}\u{BC1}",
            t("irukkirathu")
        );
        assert_eq!("\u{B95}\u{BBF}\u{BB0}\u{BA9}\u{BCD}", t("kiran"));
    }

    #[test]
    fn spoken_present_ur_is_ra() {
        assert_eq!(
            "\u{BAA}\u{BC6}\u{B9A}\u{BC1}\u{BB1}\u{BC7}\u{BA9}\u{BCD}",
            t("pesuren")
        );
        assert_eq!(
            "\u{BAA}\u{BA9}\u{BCD}\u{BA9}\u{BC1}\u{BB1}\u{BBE}\u{B99}\u{BCD}\u{B95}",
            t("pannuraanga")
        );
    }

    #[test]
    fn i_after_a_vowel_is_the_glide() {
        assert_eq!("\u{B9A}\u{BC6}\u{BAF}\u{BCD}", t("sei"));
        assert_eq!("\u{B9A}\u{BC6}\u{BAF}\u{BCD}\u{BAF}", t("seiya"));
        assert_eq!("\u{BAA}\u{BCA}\u{BAF}\u{BCD}", t("poi"));
    }

    #[test]
    fn obligative_anum_is_na() {
        assert_eq!(
            "\u{B9A}\u{BCA}\u{BB2}\u{BCD}\u{BB2}\u{BA3}\u{BC1}\u{BAE}\u{BCD}",
            t("sollanum")
        );
        assert_eq!(
            "\u{BAA}\u{BBE}\u{B95}\u{BCD}\u{B95}\u{BA3}\u{BC1}\u{BAE}\u{BCD}",
            t("paakanum")
        );
        assert_eq!("\u{B85}\u{BB5}\u{BA9}\u{BC1}\u{BAE}\u{BCD}", t("avanum"));
    }

    #[test]
    fn tth_is_dental_tha() {
        assert_eq!("\u{BB0}\u{BA4}\u{BCD}\u{BA4}\u{BAE}\u{BCD}", t("rattham"));
        assert_eq!("\u{BAA}\u{BA4}\u{BCD}\u{BA4}\u{BC1}", t("patthu"));
    }

    #[test]
    fn closing_a_is_offered_long() {
        assert_eq!(
            vec![
                "\u{B85}\u{BAE}\u{BCD}\u{BAE}",
                "\u{B85}\u{BAE}\u{BCD}\u{BAE}\u{BBE}"
            ],
            variants("amma")
        );
    }
}

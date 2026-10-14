//! Rule-based phonetic transliteration from romanized Hindi ("Hinglish") to
//! Devanagari: "kaise ho" → कैसे हो. A port of `HindiPhonetic.kt`, whose
//! comment carries the full reasoning; the rules in short:
//!
//! 1. The inherent vowel is "a": silent after a consonant, अ at a word start.
//!    "aa" or "A" is आ / ा.
//! 2. A word-final vowel is long: "karna" → करना, "bhi" → भी, "tu" → तू.
//! 3. An "a" before "o" or "u" is long ("jao" → जाओ), and so is the one in an
//!    "ai" that closes a word of more than one syllable ("safai" → सफाई, but
//!    "hai" → है).
//! 4. A nasal before the stop it belongs to is an anusvara: "hindi" → हिंदी.
//! 5. Consonants split unless the table joins them (`joins`).
//! 6. A grammatical ending never joins its stem (`guarded`): "karna",
//!    "sakta", "uska", "mujhse".
//! 7. Capitals spell the retroflex row and the long vowels; a capital that
//!    spells nothing reads as its lowercase self.
//!
//! Only runs of Latin letters are read; everything else passes through.
//! Dental against retroflex, and every other distinction the spelling does
//! not carry, is the dictionary's job, not this transliterator's.

use std::collections::HashMap;
use std::sync::OnceLock;

const VIRAMA: char = '\u{94D}';
const ANUSVARA: char = '\u{902}';
const AA: &str = "आ";
const AA_MATRA: &str = "\u{93E}";

// The nukta letters have no code point NFC keeps, so the pair is the spelling.
const ZA: &str = "\u{91C}\u{93C}";
const RRA: &str = "\u{921}\u{93C}";
const RRHA: &str = "\u{922}\u{93C}";

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Kind {
    Consonant,
    Vowel,
    Sign,
}

struct Rule {
    matches: Vec<char>,
    /// A consonant, or a vowel's independent form.
    full: &'static str,
    /// The vowel-sign form used after a consonant; `None` means not a vowel.
    /// Empty for the inherent "a", which has none.
    matra: Option<&'static str>,
    kind: Kind,
    /// What a short vowel becomes at the end of a word (rule 2), as an index
    /// into `Tables::rules`.
    long: Option<usize>,
}

impl Rule {
    fn is_schwa(&self) -> bool {
        self.matra == Some("")
    }
}

/// How the undecided parts of a spelling are read.
#[derive(Clone, Copy)]
struct Reading {
    /// Whether a grammatical ending is kept off its stem (rule 6).
    guards: bool,
    /// Whether every typed cluster is a conjunct, table or not.
    join_all: bool,
    /// Whether nothing joins past the start of the word, and a nasal stays a letter.
    join_none: bool,
    /// Whether a word-final short vowel is lengthened (rule 2).
    long_final: bool,
    /// Whether the last "a" inside the word is read as आ.
    long_last: bool,
}

const LIKELY: Reading = Reading {
    guards: true,
    join_all: false,
    join_none: false,
    long_final: true,
    long_last: false,
};
/// The one guess the rules cannot make: whether a lone "a" inside a word is
/// the inherent vowel or a long one nobody doubled ("nazar" against "kitab").
const LONG_LAST: Reading = Reading {
    long_last: true,
    ..LIKELY
};
/// Every cluster a conjunct: the reading a scheme like ITRANS would make.
const TIGHT: Reading = Reading {
    guards: false,
    join_all: true,
    ..LIKELY
};
/// Every consonant its own syllable.
const LOOSE: Reading = Reading {
    join_none: true,
    ..LIKELY
};
/// The final vowel as short as it was typed: तत्सम words, and कि.
const SHORT_FINAL: Reading = Reading {
    long_final: false,
    ..LIKELY
};

struct Tables {
    rules: Vec<Rule>,
    /// Indices into `rules` by first character, each bucket longest first.
    by_first: HashMap<char, Vec<usize>>,
    long_a: usize,
    diphthong_ai: usize,
}

fn tables() -> &'static Tables {
    static TABLES: OnceLock<Tables> = OnceLock::new();
    TABLES.get_or_init(build_tables)
}

fn build_tables() -> Tables {
    // (match, full, matra, kind, long-match)
    type Spec = (
        &'static str,
        &'static str,
        Option<&'static str>,
        Kind,
        Option<&'static str>,
    );
    let v = |m, full, matra| -> Spec { (m, full, Some(matra), Kind::Vowel, None) };
    let vl = |m, full, matra, long| -> Spec { (m, full, Some(matra), Kind::Vowel, Some(long)) };
    let c = |m, full| -> Spec { (m, full, None, Kind::Consonant, None) };
    let specs: Vec<Spec> = vec![
        v("aa", AA, AA_MATRA),
        v("A", AA, AA_MATRA),
        vl("a", "अ", "", "aa"),
        v("ee", "ई", "\u{940}"),
        v("ii", "ई", "\u{940}"),
        v("I", "ई", "\u{940}"),
        vl("i", "इ", "\u{93F}", "ee"),
        v("oo", "ऊ", "\u{942}"),
        v("uu", "ऊ", "\u{942}"),
        v("U", "ऊ", "\u{942}"),
        vl("u", "उ", "\u{941}", "oo"),
        v("e", "ए", "\u{947}"),
        v("ai", "ऐ", "\u{948}"),
        v("o", "ओ", "\u{94B}"),
        v("au", "औ", "\u{94C}"),
        v("ou", "औ", "\u{94C}"),
        v("RRi", "ऋ", "\u{943}"),
        c("kh", "ख"),
        c("gh", "घ"),
        c("chh", "छ"),
        c("ch", "च"),
        c("jh", "झ"),
        c("Th", "ठ"),
        c("Dh", "ढ"),
        c("th", "थ"),
        c("dh", "ध"),
        c("ph", "फ"),
        c("bh", "भ"),
        c("sh", "श"),
        c("Sh", "ष"),
        c("Rh", RRHA),
        // One key each, but two letters: the join is part of the letter.
        c("ksh", "क्ष"),
        c("kSh", "क्ष"),
        c("gy", "ज्ञ"),
        c("jn", "ज्ञ"),
        c("x", "क्स"),
        c("k", "क"),
        c("g", "ग"),
        c("c", "च"),
        c("j", "ज"),
        c("T", "ट"),
        c("D", "ड"),
        c("N", "ण"),
        c("t", "त"),
        c("d", "द"),
        c("n", "न"),
        c("p", "प"),
        // "fir" is how फिर gets typed, and फ is how the word lists spell the
        // loans too, so "f" carries no nukta.
        c("f", "फ"),
        c("b", "ब"),
        c("m", "म"),
        c("y", "य"),
        c("r", "र"),
        c("R", RRA),
        c("l", "ल"),
        c("v", "व"),
        c("w", "व"),
        c("s", "स"),
        c("h", "ह"),
        c("z", ZA),
        c("q", "क"),
        ("M", "\u{902}", None, Kind::Sign, None),
        ("H", "\u{903}", None, Kind::Sign, None),
    ];
    // Longest first; stable, so equal lengths keep the order above.
    let mut order: Vec<usize> = (0..specs.len()).collect();
    order.sort_by_key(|&i| std::cmp::Reverse(specs[i].0.chars().count()));
    let index_of = |m: &str| order.iter().position(|&i| specs[i].0 == m).unwrap();
    let rules: Vec<Rule> = order
        .iter()
        .map(|&i| {
            let (m, full, matra, kind, long) = specs[i];
            Rule {
                matches: m.chars().collect(),
                full,
                matra,
                kind,
                long: long.map(index_of),
            }
        })
        .collect();
    let mut by_first: HashMap<char, Vec<usize>> = HashMap::new();
    for (i, rule) in rules.iter().enumerate() {
        by_first.entry(rule.matches[0]).or_default().push(i);
    }
    Tables {
        long_a: index_of("aa"),
        diphthong_ai: index_of("ai"),
        rules,
        by_first,
    }
}

impl Tables {
    fn exact_rule_at(&self, input: &[char], at: usize) -> Option<usize> {
        self.by_first
            .get(&input[at])?
            .iter()
            .copied()
            .find(|&i| input[at..].starts_with(&self.rules[i].matches))
    }

    /// `exact_rule_at`, falling back to the lowercase reading of a capital
    /// that spells nothing on its own. The whole tail is lowered so a
    /// two-letter rule still matches across it ("KH" is ख).
    fn rule_at(&self, input: &[char], at: usize) -> Option<usize> {
        if let Some(rule) = self.exact_rule_at(input, at) {
            return Some(rule);
        }
        if !input[at].is_ascii_uppercase() {
            return None;
        }
        let lowered: Vec<char> = input[at..].iter().flat_map(|c| c.to_lowercase()).collect();
        self.exact_rule_at(&lowered, 0)
    }

    fn tokenize(&self, word: &[char]) -> Vec<usize> {
        let mut tokens = Vec::with_capacity(word.len());
        let mut i = 0;
        while i < word.len() {
            // Every Latin letter has a rule, if only through the lowercase
            // fallback, so the walk always advances.
            let rule = self
                .rule_at(word, i)
                .expect("every Latin letter has a Hindi rule");
            tokens.push(rule);
            i += self.rules[rule].matches.len();
        }
        tokens
    }

    fn word(&self, word: &[char], reading: Reading, out: &mut String) {
        let tokens = self.tokenize(word);
        let rule = |i: usize| &self.rules[tokens[i]];
        let n = tokens.len();
        // The last inherent "a" that is inside the word rather than closing it.
        let last_inner = if reading.long_last && n >= 2 {
            (1..n - 1).rev().find(|&i| rule(i).is_schwa())
        } else {
            None
        };
        let mut prev: Option<Kind> = None;
        for index in 0..n {
            let token = rule(index);
            let last = index == n - 1;
            match token.kind {
                Kind::Vowel => {
                    // Rule 3's second half: the ाई that closes a noun.
                    if last
                        && tokens[index] == self.diphthong_ai
                        && (0..index).any(|i| rule(i).kind == Kind::Vowel)
                    {
                        out.push_str(if prev == Some(Kind::Consonant) {
                            AA_MATRA
                        } else {
                            AA
                        });
                        out.push('ई');
                        prev = Some(Kind::Vowel);
                        continue;
                    }
                    let next = tokens.get(index + 1).map(|&i| &self.rules[i]);
                    let chosen = if last && reading.long_final {
                        token.long.map_or(token, |i| &self.rules[i])
                    } else if Some(index) == last_inner {
                        &self.rules[self.long_a]
                    } else if token.is_schwa()
                        && next.is_some_and(|next| {
                            let c = next.matches[0].to_ascii_lowercase();
                            c == 'o' || c == 'u'
                        })
                    {
                        // Rule 3. Only the inherent vowel, and only before the
                        // back vowels: "gae" is गए, so "e" proves nothing.
                        &self.rules[self.long_a]
                    } else {
                        token
                    };
                    out.push_str(if prev == Some(Kind::Consonant) {
                        chosen.matra.unwrap()
                    } else {
                        chosen.full
                    });
                }
                Kind::Consonant => {
                    if self.nasalises(&tokens, index, reading) {
                        out.push(ANUSVARA);
                        prev = Some(Kind::Sign);
                        continue;
                    }
                    if prev == Some(Kind::Consonant) && self.joins(&tokens, index, reading) {
                        out.push(VIRAMA);
                    }
                    out.push_str(token.full);
                }
                Kind::Sign => out.push_str(token.full),
            }
            prev = Some(token.kind);
        }
    }

    /// Rule 4: whether the nasal at `index` is written as an anusvara.
    fn nasalises(&self, tokens: &[usize], index: usize, reading: Reading) -> bool {
        if reading.join_none {
            return false;
        }
        let Some(&next) = tokens.get(index + 1) else {
            return false;
        };
        let next = &self.rules[next];
        if next.kind != Kind::Consonant {
            return false;
        }
        // Hangs off a vowel, so there has to be one: "nk" opening a word is
        // two letters, not a sign with nothing to sit on.
        if index == 0 || self.rules[tokens[index - 1]].kind != Kind::Vowel {
            return false;
        }
        let homorganic = match self.rules[tokens[index]].full {
            "न" => AFTER_N.contains(&next.full),
            "म" => AFTER_M.contains(&next.full),
            _ => false,
        };
        // "unka" is उन + का, and the न there is a whole syllable.
        homorganic && !(reading.guards && self.guarded(tokens, index + 1))
    }

    /// Rule 6: whether the rest of the word from `index` is an ending its
    /// stem stays clear of.
    fn guarded(&self, tokens: &[usize], index: usize) -> bool {
        // A stem has a vowel in it. Without one this is a cluster opening the
        // word ("sna…"), which is the one place a join is certain.
        if !tokens[..index]
            .iter()
            .any(|&i| self.rules[i].kind == Kind::Vowel)
        {
            return false;
        }
        let rest: String = tokens[index..]
            .iter()
            .flat_map(|&i| self.rules[i].matches.iter())
            .flat_map(|c| c.to_lowercase())
            .collect();
        if ENDINGS.contains(&rest.as_str()) {
            return true;
        }
        SOFT_ENDINGS.contains(&rest.as_str())
            && !SIBILANTS.contains(&self.rules[tokens[index - 1]].full)
    }

    /// Rule 5: whether the consonant at `index` forms a conjunct with the one
    /// before it. Every line is a judgement about which of two readings is
    /// commoner, and the first thing to revisit when a word comes out wrong.
    fn joins(&self, tokens: &[usize], index: usize, reading: Reading) -> bool {
        let before = self.rules[tokens[index - 1]].full;
        let here = self.rules[tokens[index]].full;
        // Opening the word: there is no vowel for a schwa to have been dropped
        // after. True of every reading.
        if tokens[..index]
            .iter()
            .all(|&i| self.rules[i].kind == Kind::Consonant)
        {
            return true;
        }
        if reading.join_none {
            return false;
        }
        let doubled = before == here || aspirate(before) == Some(here);
        // A doubled consonant is a conjunct even where an ending begins with
        // it ("pakka", "kutta"), except "n" and "s", where the doubling *is*
        // the ending meeting its stem: "sunna" is सुन + ना, "usse" is उस + से.
        if doubled && here != "न" && here != "स" {
            return true;
        }
        if reading.guards && self.guarded(tokens, index) {
            return false;
        }
        if reading.join_all {
            return true;
        }
        let last = index == tokens.len() - 1;
        // What follows this consonant, when that is a single vowel and then
        // the end of the word, or nothing at all.
        let closing = last
            || (index == tokens.len() - 2 && self.rules[tokens[index + 1]].kind == Kind::Vowel);
        if doubled || here == "य" {
            true
        } else if here == "र" {
            BEFORE_R.contains(&before)
        } else if here == "व" {
            BEFORE_V.contains(&before)
        } else if SIBILANTS.contains(&before) {
            AFTER_S.contains(&here)
        } else if before == "र" {
            // Reph, where the cluster closes the word (गर्मी, शर्मा, धर्म). With
            // more word to come it is as often a dropped schwa (सरकार, बरसात).
            closing
        } else if here == "ह" {
            before == "न" || before == "म"
        } else {
            // Closing the word with no vowel typed between them: someone who
            // meant "nazar" wrote the "a".
            last
        }
    }
}

/// The stops each nasal is homorganic with, near enough: what it turns into ं before.
const AFTER_N: &[&str] = &[
    "क", "ख", "ग", "घ", "च", "छ", "ज", "झ", "ट", "ठ", "ड", "ढ", "त", "थ", "द", "ध", "स", "श", "ष",
    ZA,
];
const AFTER_M: &[&str] = &["प", "फ", "ब", "भ"];

/// Endings that are words of their own, or as good as: the postpositions chat
/// spelling glues on, the infinitive, and "-wala".
const ENDINGS: &[&str] = &[
    "ka", "ki", "ke", "ko", "se", "me", "mein", "men", "na", "ne", "ni", "wala", "wali", "wale",
    "vala", "vali", "vale",
];
/// The participle and the conjunctive. Guarded like `ENDINGS`, except after a
/// sibilant: "dosti", "rasta" and "namaskar" are far commoner than the
/// handful of verbs whose stem ends in "s".
const SOFT_ENDINGS: &[&str] = &["ta", "te", "ti", "kar"];
const SIBILANTS: &[&str] = &["स", "श", "ष"];
const BEFORE_R: &[&str] = &["त", "थ", "द", "ध", "श"];
const BEFORE_V: &[&str] = &["स", "श", "त", "द", "ध", "ज"];
const AFTER_S: &[&str] = &["त", "थ", "ट", "ठ", "प", "क", "म"];

/// Unaspirated → aspirated: "cchh", "tth", "ddh" double a consonant just as "kk" does.
fn aspirate(c: &str) -> Option<&'static str> {
    Some(match c {
        "क" => "ख",
        "ग" => "घ",
        "च" => "छ",
        "ज" => "झ",
        "ट" => "ठ",
        "ड" => "ढ",
        "त" => "थ",
        "द" => "ध",
        "प" => "फ",
        "ब" => "भ",
        _ => return None,
    })
}

fn render(input: &str, reading: Reading) -> String {
    let t = tables();
    let input: Vec<char> = input.chars().collect();
    let mut out = String::with_capacity(input.len() * 3);
    let mut i = 0;
    while i < input.len() {
        if input[i].is_ascii_alphabetic() {
            let mut j = i;
            while j < input.len() && input[j].is_ascii_alphabetic() {
                j += 1;
            }
            t.word(&input[i..j], reading, &mut out);
            i = j;
        } else {
            out.push(input[i]);
            i += 1;
        }
    }
    out
}

/// Transliterates romanized Hindi, one word or free text, into Devanagari.
pub fn transliterate(input: &str) -> String {
    render(input, LIKELY)
}

/// Every reading of `input` worth offering, the likely one first: the same
/// spelling with its last "a" long, with every cluster joined, with none
/// joined, and with its final vowel left short. Never the same reading twice.
pub fn variants(input: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::with_capacity(5);
    for reading in [LIKELY, LONG_LAST, TIGHT, LOOSE, SHORT_FINAL] {
        let rendered = render(input, reading);
        if !out.contains(&rendered) {
            out.push(rendered);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    //! Mirrors `HindiPhoneticTest.kt`.

    use super::{transliterate as t, variants};

    // Nukta letters are the decomposed pair, as the engine writes them.
    const ZA: &str = "\u{91C}\u{93C}";
    const RRHA: &str = "\u{922}\u{93C}";

    #[test]
    fn the_issues_own_example() {
        assert_eq!("कैसे हो", t("kaise ho"));
    }

    #[test]
    fn simple_words() {
        assert_eq!("तुम", t("tum"));
        assert_eq!("आप", t("aap"));
        assert_eq!("नाम", t("naam"));
        assert_eq!("बहुत", t("bahut"));
        assert_eq!("मुझे", t("mujhe"));
        assert_eq!("और", t("aur"));
        assert_eq!("है", t("hai"));
        assert_eq!("कोई", t("koi"));
    }

    #[test]
    fn inherent_vowel() {
        assert_eq!("कमल", t("kamal"));
        assert_eq!("अब", t("ab"));
        assert_eq!("अगर", t("agar"));
    }

    #[test]
    fn a_final_vowel_is_long() {
        assert_eq!("मेरा", t("mera"));
        assert_eq!("कहा", t("kaha"));
        assert_eq!("गया", t("gaya"));
        assert_eq!("किया", t("kiya"));
        assert_eq!("हुआ", t("hua"));
        assert_eq!("भी", t("bhi"));
        assert_eq!("तू", t("tu"));
    }

    #[test]
    fn an_a_is_long_before_a_back_vowel() {
        assert_eq!("जाओ", t("jao"));
        assert_eq!("खाओ", t("khao"));
    }

    #[test]
    fn a_closing_ai_is_the_noun_ending() {
        assert_eq!("सफाई", t("safai"));
        assert_eq!("कमाई", t("kamai"));
        assert_eq!("है", t("hai"));
    }

    #[test]
    fn nasal_before_its_stop_is_an_anusvara() {
        assert_eq!("हिंदी", t("hindi"));
        assert_eq!("लंबा", t("lamba"));
        assert_eq!("रंग", t("rang"));
        assert_eq!(format!("{ZA}िंदगी"), t("zindagi"));
    }

    #[test]
    fn grammatical_endings_stay_off_their_stem() {
        assert_eq!("करना", t("karna"));
        assert_eq!("सकता", t("sakta"));
        assert_eq!("सुनता", t("sunta"));
        assert_eq!("उसका", t("uska"));
        assert_eq!("उनका", t("unka"));
        assert_eq!("मुझसे", t("mujhse"));
        assert_eq!("देखकर", t("dekhkar"));
        assert_eq!("गलती", t("galti"));
        assert_eq!("सुनना", t("sunna"));
    }

    #[test]
    fn clusters_the_table_joins() {
        assert_eq!("नमस्ते", t("namaste"));
        assert_eq!("दोस्त", t("dost"));
        assert_eq!("दोस्ती", t("dosti"));
        assert_eq!("क्या", t("kya"));
        assert_eq!("पक्का", t("pakka"));
        assert_eq!("बच्चा", t("baccha"));
        assert_eq!("धर्म", t("dharm"));
        assert_eq!("गर्मी", t("garmi"));
        assert_eq!("शब्द", t("shabd"));
        assert_eq!("किस्मत", t("kismat"));
        assert_eq!("विद्या", t("vidya"));
        assert_eq!("तुम्हे", t("tumhe"));
        assert_eq!("उन्होने", t("unhone"));
    }

    #[test]
    fn clusters_it_leaves_apart() {
        assert_eq!("कमरा", t("kamra"));
        assert_eq!("लदकी", t("ladki"));
    }

    #[test]
    fn capitals_spell_the_retroflex_row() {
        assert_eq!("रोटी", t("roTi"));
        assert_eq!(format!("प{RRHA}ना"), t("paRhna"));
    }

    #[test]
    fn capitals_that_spell_nothing_read_as_lowercase() {
        assert_eq!("ख", t("KH"));
        assert_eq!(t("bahut"), t("Bahut"));
    }

    #[test]
    fn only_latin_runs_are_read() {
        assert_eq!("तुम कैसे हो? 123", t("tum kaise ho? 123"));
        assert_eq!("क्या क्या", t("kya क्या"));
    }

    #[test]
    fn variants_offer_the_other_readings() {
        let karna = variants("karna");
        assert_eq!("करना", karna[0]);
        assert!(karna.iter().any(|v| v == "कर्ना"), "{karna:?}");
        assert!(variants("pyar").iter().any(|v| v == "प्यार"));
        assert!(variants("sarkar").iter().any(|v| v == "सरकार"));
        assert!(variants("mitra").iter().any(|v| v == "मित्र"));
        let mut dedup = karna.clone();
        dedup.dedup();
        assert_eq!(karna.len(), dedup.len());
        assert_eq!(vec!["हो".to_string()], variants("ho"));
    }
}

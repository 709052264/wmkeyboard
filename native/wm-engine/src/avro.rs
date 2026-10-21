//! Rule-based Avro-style phonetic transliteration from romanized Bengali
//! ("banglish") to Bengali script. A port of `AvroPhonetic.kt`; the Kotlin
//! file's own comment is the full rule list, summarised here.
//!
//! The engine is a greedy longest-match transliterator over a rule table,
//! with context sensitivity:
//!
//! 1. Vowels render as independent letters at a word boundary or after
//!    another vowel, and as vowel signs (kar) after a consonant.
//! 2. Consecutive consonants conjunct only where Bangla has that conjunct
//!    (`JOINS`, desktop Avro's own table). র-ফলা, য-ফলা and ব-ফলা join any
//!    consonant, and ন before a velar or palatal takes that nasal.
//! 3. Lowercase "o" is the inherent vowel: silent after a consonant, ও after
//!    a vowel that wrote something, অ anywhere else. Never ো: that is what
//!    the capital "O" is for.
//! 4. "rr" spells reph, except in "rri" (ঋ) or when no consonant follows.
//! 5. An "a" right after a rendered vowel glides with য়.
//! 6. A backquote breaks the cluster it lands in the middle of, and turns a
//!    ত it lands after into ৎ.
//! 7. A word-final hasant collapses the ত before it into ৎ.
//! 8. "Z" is য-ফলা spelled outright; "w" is ব-ফলা after a consonant and ও
//!    elsewhere, gliding into a vowel after it through য়.
//! 9. ং, ঃ and ঁ are signs, not consonants: they join nothing.
//! 10. A capital that spells nothing of its own reads as its lowercase self.
//! 11. At a word start "y" is ইয় and "x" এক্স; "ee" is ঈ/ী; "aZ" is অ্যা.
//!
//! Dictionary-level corrections ("asi" → আছি rather than আসি) are the
//! suggestion engine's job, not this transliterator's.

use std::collections::{HashMap, HashSet};
use std::sync::OnceLock;

use crate::sort;
use crate::unicode::{is_letter, is_mark};

const HASANT: char = '\u{9CD}';
const TA: char = 'ত';
const KHANDA_TA: char = 'ৎ';

/// Breaks a cluster without writing anything: "k`s" → কস, not ক্স.
const BREAKER: char = '`';

/// Holds র and য-ফলা apart, so "ry" draws র‍্য (র‍্যাব) and not the reph of র্য.
const ZWJ: char = '\u{200D}';

/// Precomposed য় (U+09DF): what the engine writes, as the dictionaries spell it.
const ANTASTYA_YA: char = '\u{9DF}';

/// Roman letters after which an "r" is a plain র, never র-ফলা.
const NO_R_PHOLA_AFTER: &str = "ryYwWxZ";

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Kind {
    Consonant,
    Vowel,
    /// ং / ঃ / ঁ: written like a consonant but joined like nothing. The
    /// syllable it closes is still a syllable, so an inherent "o" after it
    /// stays silent where after a digit or full stop it would be a fresh অ.
    Sign,
    Other,
}

struct Rule {
    /// The Avro phonetic token this rule fires on.
    matches: Vec<char>,
    /// Independent form (word start or after a vowel).
    full: &'static str,
    /// Vowel-sign form used after a consonant; `None` means not a vowel.
    kar: Option<&'static str>,
    kind: Kind,
}

struct Tables {
    /// Rules bucketed by first character, each bucket longest match first.
    by_first: HashMap<char, Vec<Rule>>,
    /// Consonant pairs that conjunct when typed back to back, as
    /// (first letter, second letter). Desktop Avro's own conjunct table.
    joins: HashSet<(char, char)>,
    /// ন takes the nasal of the class it joins: "nk" → ঙ্ক, "nc" → ঞ্চ.
    nasal_before: HashMap<char, char>,
}

fn tables() -> &'static Tables {
    static TABLES: OnceLock<Tables> = OnceLock::new();
    TABLES.get_or_init(build_tables)
}

fn build_tables() -> Tables {
    let v = |m: &str, full: &'static str, kar: &'static str| Rule {
        matches: m.chars().collect(),
        full,
        kar: Some(kar),
        kind: Kind::Vowel,
    };
    let c = |m: &str, full: &'static str| Rule {
        matches: m.chars().collect(),
        full,
        kar: None,
        kind: Kind::Consonant,
    };
    let k = |m: &str, full: &'static str, kind: Kind| Rule {
        matches: m.chars().collect(),
        full,
        kar: None,
        kind,
    };
    let mut rules = vec![
        // Vowels.
        v("OI", "ঐ", "ৈ"),
        v("OU", "ঔ", "ৌ"),
        v("rri", "ঋ", "ৃ"),
        v("a", "আ", "া"),
        v("A", "আ", "া"),
        v("i", "ই", "ি"),
        v("I", "ঈ", "ী"),
        v("ee", "ঈ", "ী"),
        v("u", "উ", "ু"),
        v("U", "ঊ", "ূ"),
        v("e", "এ", "ে"),
        v("E", "এ", "ে"),
        v("O", "ও", "ো"),
        // "o" is handled in transliterate().
        // Fixed conjuncts spelled by digraph.
        c("kkh", "ক্ষ"),
        c("kx", "ক্ষ"),
        c("gg", "জ্ঞ"),
        c("nj", "ঞ্জ"),
        // Aspirated and two-letter consonants.
        c("kh", "খ"),
        c("gh", "ঘ"),
        c("Ng", "ঙ"),
        c("ch", "ছ"),
        c("jh", "ঝ"),
        c("NG", "ঞ"),
        c("Th", "ঠ"),
        c("Dh", "ঢ"),
        c("th", "থ"),
        c("dh", "ধ"),
        c("ph", "ফ"),
        c("bh", "ভ"),
        c("sh", "শ"),
        c("Sh", "ষ"),
        c("Rh", "\u{9DD}"),
        // Signs, not consonants; the Ridmik spellings beside Avro's own.
        k("ng", "ং", Kind::Sign),
        k("TH", "ৎ", Kind::Other),
        k("HH", "ঃ", Kind::Sign),
        k("qq", "ঁ", Kind::Sign),
        k("cb", "ঁ", Kind::Sign),
        // Single consonants.
        c("k", "ক"),
        c("q", "ক"),
        c("g", "গ"),
        c("c", "চ"),
        c("j", "জ"),
        c("T", "ট"),
        c("D", "ড"),
        c("N", "ণ"),
        c("t", "ত"),
        c("d", "দ"),
        c("n", "ন"),
        c("p", "প"),
        c("f", "ফ"),
        c("b", "ব"),
        c("v", "ভ"),
        c("m", "ম"),
        c("z", "য"),
        c("r", "র"),
        c("l", "ল"),
        c("S", "শ"),
        c("s", "স"),
        c("h", "হ"),
        // Desktop Avro's capital J, the ja with a nukta under it: no
        // precomposed code point exists, so the pair is the spelling.
        c("J", "\u{99C}\u{9BC}"),
        c("R", "\u{9DC}"),
        // "y"/"Y"/"Z" and "w" are context-sensitive, handled in transliterate().
        c("x", "ক্স"),
        // Signs and digits.
        k(":", "ঃ", Kind::Sign),
        k("^", "ঁ", Kind::Sign),
        k(",,", "\u{9CD}", Kind::Other),
        k("...", "...", Kind::Other),
        k("..", ".", Kind::Other),
        k(".", "।", Kind::Other),
        k("$", "৳", Kind::Other),
    ];
    const DIGITS: [&str; 10] = ["০", "১", "২", "৩", "৪", "৫", "৬", "৭", "৮", "৯"];
    for (i, digit) in DIGITS.iter().enumerate() {
        rules.push(k(
            &((b'0' + i as u8) as char).to_string(),
            digit,
            Kind::Other,
        ));
    }
    // Longest first; stable, so equal lengths keep the order above. The
    // buckets inherit that order, so the first prefix hit in a bucket is the
    // greedy longest match.
    sort::by_key(&mut rules, |rule| std::cmp::Reverse(rule.matches.len()));
    let mut by_first: HashMap<char, Vec<Rule>> = HashMap::new();
    for rule in rules {
        by_first.entry(rule.matches[0]).or_default().push(rule);
    }

    const JOINS: &[&str] = &[
        "কক",
        "কট",
        "কত",
        "কল",
        "কষ",
        "কস",
        "গণ",
        "গধ",
        "গন",
        "গম",
        "গল",
        "ঘন",
        "ঙক",
        "ঙখ",
        "ঙগ",
        "ঙঘ",
        "ঙম",
        "ঙষ",
        "চচ",
        "চছ",
        "চঞ",
        "জজ",
        "জঝ",
        "জঞ",
        "ঞচ",
        "ঞছ",
        "ঞজ",
        "ঞঝ",
        "টট",
        "টম",
        "ডড",
        "ণট",
        "ণঠ",
        "ণড",
        "ণঢ",
        "ণণ",
        "ণন",
        "ণম",
        "তত",
        "তথ",
        "তন",
        "তম",
        "দগ",
        "দঘ",
        "দদ",
        "দধ",
        "দভ",
        "দম",
        "ধন",
        "ধম",
        "নট",
        "নঠ",
        "নড",
        "নত",
        "নথ",
        "নদ",
        "নধ",
        "নন",
        "নম",
        "নস",
        "পট",
        "পত",
        "পন",
        "পপ",
        "পল",
        "পস",
        "ফল",
        "বজ",
        "বদ",
        "বধ",
        "বব",
        "বল",
        "ভল",
        "মথ",
        "মন",
        "মপ",
        "মফ",
        "মব",
        "মভ",
        "মম",
        "মল",
        "লক",
        "লগ",
        "লট",
        "লড",
        "লধ",
        "লপ",
        "লব",
        "লভ",
        "লম",
        "লল",
        "শচ",
        "শছ",
        "শত",
        "শন",
        "শম",
        "শল",
        "ষক",
        "ষট",
        "ষঠ",
        "ষণ",
        "ষপ",
        "ষফ",
        "ষম",
        "সক",
        "সখ",
        "সট",
        "সত",
        "সথ",
        "সন",
        "সপ",
        "সফ",
        "সম",
        "সল",
        "হণ",
        "হন",
        "হম",
        "হল",
        "\u{9DC}গ",
    ];
    let joins = JOINS
        .iter()
        .map(|pair| {
            let mut chars = pair.chars();
            (chars.next().unwrap(), chars.next().unwrap())
        })
        .collect();
    let nasal_before = [
        ('ক', 'ঙ'),
        ('খ', 'ঙ'),
        ('চ', 'ঞ'),
        ('ছ', 'ঞ'),
        ('জ', 'ঞ'),
        ('ঝ', 'ঞ'),
    ]
    .into_iter()
    .collect();
    Tables {
        by_first,
        joins,
        nasal_before,
    }
}

impl Tables {
    /// Longest rule whose match is a prefix of `input` at `at`.
    fn exact_rule_at(&self, input: &[char], at: usize) -> Option<&Rule> {
        self.by_first
            .get(&input[at])?
            .iter()
            .find(|rule| input[at..].starts_with(&rule.matches))
    }

    /// `exact_rule_at`, falling back to the lowercase reading of a capital
    /// that spells nothing on its own. The whole tail is lowered rather than
    /// the one character, so a two-letter rule still matches across it ("KH"
    /// is খ, not ক্হ); the match length is the same either way.
    fn rule_at(&self, input: &[char], at: usize) -> Option<&Rule> {
        if at >= input.len() {
            return None;
        }
        if let Some(rule) = self.exact_rule_at(input, at) {
            return Some(rule);
        }
        if !input[at].is_ascii_uppercase() {
            return None;
        }
        let lowered: Vec<char> = input[at..].iter().flat_map(|c| c.to_lowercase()).collect();
        self.exact_rule_at(&lowered, 0)
    }

    /// Whether the consonant `next` starts with conjuncts onto the consonant
    /// `out` ends with; `roman_before` is the roman letter typed just before
    /// it. A ন about to join a velar or palatal becomes that class's nasal
    /// first, which is the only edit this makes to `out`.
    fn joins(&self, out: &mut [char], next: &str, roman_before: Option<char>) -> bool {
        let head = next.chars().next().unwrap();
        if head == 'র' {
            return roman_before.is_none_or(|c| !NO_R_PHOLA_AFTER.contains(c));
        }
        let Some(&last) = out.last() else {
            return false;
        };
        if last == 'ন' {
            if let Some(&nasal) = self.nasal_before.get(&head) {
                *out.last_mut().unwrap() = nasal;
                return true;
            }
        }
        self.joins.contains(&(last, head))
    }
}

/// Nothing Bengali before `out`'s end: a new word, where Avro spells "y" ইয়
/// and "x" এক্স.
fn at_word_start(out: &[char]) -> bool {
    match out.last() {
        None => true,
        Some(&last) => !is_letter(last) && !is_mark(last),
    }
}

fn starts_with(input: &[char], at: usize, text: &str) -> bool {
    text.chars()
        .enumerate()
        .all(|(i, c)| input.get(at + i) == Some(&c))
}

fn push_str(out: &mut Vec<char>, text: &str) {
    out.extend(text.chars());
}

/// Transliterates one romanized word (or free text) into Bengali script.
pub fn transliterate(input: &str) -> String {
    let t = tables();
    let input: Vec<char> = input.chars().collect();
    let mut out: Vec<char> = Vec::with_capacity(input.len() * 2);
    let mut prev = Kind::Other;
    // Whether the last vowel rendered a visible glyph (kar or independent
    // letter). An "a" right after such a vowel glides with য়; only the
    // silent inherent "o" leaves আ independent ("kuroan" → কুরআন).
    let mut prev_vowel_glyph = false;
    // Set by a backquote, cleared by whatever it precedes: the one join that
    // does not happen. Only the join; a kar after the break still attaches.
    let mut break_join = false;
    // Set by "rr" before a consonant: the reph joins whatever follows, pair
    // list or not, since spelling it out was the whole request.
    let mut reph = false;
    let mut i = 0;
    while i < input.len() {
        let ch = input[i];
        if ch == BREAKER {
            // A ত whose join has just been taken away is ৎ: "hoThat`" is হঠাৎ.
            // Both the plain ত and the explicit-hasant "t,," spelling collapse.
            let n = out.len();
            if n >= 2 && out[n - 2] == TA && out[n - 1] == HASANT {
                out.truncate(n - 2);
                out.push(KHANDA_TA);
                prev = Kind::Other;
            } else if out.last() == Some(&TA) && prev == Kind::Consonant {
                out.truncate(n - 1);
                out.push(KHANDA_TA);
                // ৎ carries its own hasant, so nothing conjuncts onto it and
                // no kar can sit on it either.
                prev = Kind::Other;
            } else {
                break_join = true;
            }
            prev_vowel_glyph = false;
            i += 1;
            continue;
        }
        // "aZ" is অ্যা, the vowel of অ্যাপ, and "oZ" the অ্য it is built on.
        // Only where a vowel would stand on its own: after a consonant the
        // "a" is a kar and "Z" its ordinary য-ফলা.
        if prev != Kind::Consonant
            && (starts_with(&input, i, "aZ")
                || starts_with(&input, i, "AZ")
                || starts_with(&input, i, "oZ"))
        {
            let bare = ch == 'o';
            push_str(&mut out, if bare { "অ্য" } else { "অ্যা" });
            // অ্য still wants its vowel, so it reads as a consonant.
            prev = if bare { Kind::Consonant } else { Kind::Vowel };
            prev_vowel_glyph = !bare;
            break_join = false;
            reph = false;
            i += 2;
            continue;
        }
        // Inherent vowel: no glyph after a consonant (or after a sign, which
        // closes a consonant's syllable), অ anywhere else. Never ো.
        if ch == 'o' && !starts_with(&input, i, "oo") {
            let carried = prev == Kind::Consonant || prev == Kind::Sign;
            // After a vowel that wrote something, "o" is ও: "amio" → আমিও.
            if !carried {
                out.push(if prev == Kind::Vowel && prev_vowel_glyph {
                    'ও'
                } else {
                    'অ'
                });
            }
            prev_vowel_glyph = !carried;
            prev = Kind::Vowel;
            i += 1;
            continue;
        }
        if starts_with(&input, i, "oo") {
            push_str(
                &mut out,
                if prev == Kind::Consonant {
                    "ু"
                } else {
                    "উ"
                },
            );
            prev = Kind::Vowel;
            prev_vowel_glyph = true;
            i += 2;
            continue;
        }

        // "a" right after a rendered vowel glides with য়: "kiamot" → কিয়ামত.
        // After an inherent (silent) vowel the independent আ survives, and a
        // capital "A" always stays explicit আ.
        if ch == 'a' && prev == Kind::Vowel && prev_vowel_glyph {
            out.push(ANTASTYA_YA);
            out.push('\u{9BE}');
            prev = Kind::Vowel;
            prev_vowel_glyph = true;
            i += 1;
            continue;
        }

        // "y": jofola (্য) after a consonant and য় elsewhere. "Y" is always
        // য় and never joins the running cluster. "Z" is the mirror of "Y":
        // always jofola.
        if ch == 'y' || ch == 'Y' || ch == 'Z' {
            if ch == 'Z' || (ch == 'y' && prev == Kind::Consonant && !break_join) {
                // A plain র (not reph, not itself a ফলা) keeps its shape with a
                // joiner: "rZab" → র‍্যাব. Without it the pair would draw as
                // reph over য.
                let n = out.len();
                if !reph && out.last() == Some(&'র') && (n < 2 || out[n - 2] != HASANT) {
                    out.push(ZWJ);
                }
                out.push(HASANT);
                out.push('য');
            } else if ch == 'y' && prev == Kind::Other && at_word_start(&out) {
                // Avro opens a word's "y" with ই: "yes" → ইয়েস.
                out.push('ই');
                out.push(ANTASTYA_YA);
            } else {
                out.push(ANTASTYA_YA);
            }
            prev = Kind::Consonant;
            prev_vowel_glyph = false;
            break_join = false;
            reph = false;
            i += 1;
            continue;
        }

        // "w" is ব-ফলা after a consonant and ও everywhere else, where the "a"
        // after it glides through the rule above ("wasi" → ওয়াসি).
        if ch == 'w' || ch == 'W' {
            let vowel_next = t.rule_at(&input, i + 1).map(|r| r.kind) == Some(Kind::Vowel)
                || input.get(i + 1) == Some(&'o');
            if prev == Kind::Consonant && !break_join {
                out.push(HASANT);
                out.push('ব');
                prev = Kind::Consonant;
                prev_vowel_glyph = false;
            } else if vowel_next {
                // ও glides into the vowel after it through য়, which then
                // carries that vowel as a kar: "we" → ওয়ে, "sawal" → সাওয়াল.
                out.push('ও');
                out.push(ANTASTYA_YA);
                prev = Kind::Consonant;
                prev_vowel_glyph = false;
            } else {
                out.push('ও');
                prev = Kind::Vowel;
                prev_vowel_glyph = true;
            }
            break_join = false;
            reph = false;
            i += 1;
            continue;
        }

        // "hs" is the hasant, the spelling Ridmik users know. Only where a
        // hasant can go at all, on a consonant, so the h and s of "ahsan" are
        // still ordinary letters.
        if starts_with(&input, i, "hs") && prev == Kind::Consonant && !break_join {
            out.push(HASANT);
            prev = Kind::Other;
            prev_vowel_glyph = false;
            reph = false;
            i += 2;
            continue;
        }

        // "ng" is ং, which hangs off the syllable in front of it and joins
        // nothing. A vowel after it is a new syllable, and only a consonant
        // can carry one, so there it is ঙ্গ instead: "songe" → সঙ্গে.
        if starts_with(&input, i, "ng")
            && (t.rule_at(&input, i + 2).map(|r| r.kind) == Some(Kind::Vowel)
                || input.get(i + 2) == Some(&'o'))
        {
            let before = if i > 0 { Some(input[i - 1]) } else { None };
            if prev == Kind::Consonant && !break_join && (reph || t.joins(&mut out, "ঙ", before))
            {
                out.push(HASANT);
            }
            push_str(&mut out, "ঙ্গ");
            prev = Kind::Consonant;
            prev_vowel_glyph = false;
            break_join = false;
            reph = false;
            i += 2;
            continue;
        }

        // "rr" is reph: a single র that conjuncts with the following consonant
        // ("borrno" → বর্ন). "rri" (ঋ) has priority, and with no consonant
        // following, "rr" falls through to the plain r-rule.
        if starts_with(&input, i, "rr") && !starts_with(&input, i, "rri") {
            let next = t.rule_at(&input, i + 2);
            let joins_consonant =
                next.map(|r| r.kind) == Some(Kind::Consonant) || input.get(i + 2) == Some(&'y');
            if joins_consonant {
                let before = if i > 0 { Some(input[i - 1]) } else { None };
                if prev == Kind::Consonant && !break_join && t.joins(&mut out, "র", before) {
                    out.push(HASANT);
                }
                out.push('র');
                prev = Kind::Consonant;
                prev_vowel_glyph = false;
                break_join = false;
                reph = true;
                i += 2;
                continue;
            }
        }

        let Some(rule) = t.rule_at(&input, i) else {
            out.push(ch);
            prev = Kind::Other;
            prev_vowel_glyph = false;
            break_join = false;
            reph = false;
            i += 1;
            continue;
        };
        match rule.kind {
            Kind::Vowel => {
                push_str(
                    &mut out,
                    if prev == Kind::Consonant {
                        rule.kar.unwrap()
                    } else {
                        rule.full
                    },
                );
                prev_vowel_glyph = true;
            }
            Kind::Consonant => {
                let before = if i > 0 { Some(input[i - 1]) } else { None };
                if prev == Kind::Consonant
                    && !break_join
                    && (reph || t.joins(&mut out, rule.full, before))
                {
                    out.push(HASANT);
                } else if rule.matches == ['x'] && prev == Kind::Other && at_word_start(&out) {
                    out.push('এ'); // "x" opens a word as এক্স: "xray" → এক্সরে
                }
                push_str(&mut out, rule.full);
                prev_vowel_glyph = false;
            }
            // A sign joins nothing, so no hasant goes in front of it, and none
            // goes after it either: `Kind::Sign` on `prev` is what stops the
            // next consonant conjuncting onto ং or ঁ.
            Kind::Sign | Kind::Other => {
                push_str(&mut out, rule.full);
                prev_vowel_glyph = false;
            }
        }
        prev = rule.kind;
        break_join = false;
        reph = false;
        i += rule.matches.len();
    }
    collapse_final_khanda_ta(&mut out);
    out.into_iter().collect()
}

/// Turns every word-final ত্ into ৎ, which is the whole of what khanda-ta
/// is. Word-final because that is the only place the sequence can be meant:
/// a hasant anywhere else is a join whose next consonant has not arrived yet.
fn collapse_final_khanda_ta(out: &mut Vec<char>) {
    let mut at = 0;
    while at + 1 < out.len() {
        if out[at] == TA && out[at + 1] == HASANT {
            let after = at + 2;
            if after >= out.len() || !is_letter(out[after]) {
                out[at] = KHANDA_TA;
                out.remove(at + 1);
            }
        }
        at += 1;
    }
}

#[cfg(test)]
mod tests {
    //! Mirrors `AvroPhoneticTest.kt` case for case.

    use super::transliterate as t;

    #[test]
    fn simple_words() {
        assert_eq!("আমি", t("ami"));
        assert_eq!("তুমি", t("tumi"));
        assert_eq!("ভাল", t("valo"));
        assert_eq!("ভাল", t("bhalo"));
        assert_eq!("আছি", t("achi"));
        assert_eq!("আসি", t("asi"));
    }

    #[test]
    fn sentence() {
        assert_eq!("আমি ভালো আছি", t("ami valO achi"));
    }

    #[test]
    fn inherent_vowel() {
        assert_eq!("করি", t("kori"));
        assert_eq!("মন", t("mon"));
        assert_eq!("কেমন", t("kemon"));
        assert_eq!("অনেক", t("onek"));
    }

    #[test]
    fn aspirated_consonants() {
        assert_eq!("খাই", t("khai"));
        assert_eq!("ঘর", t("ghor"));
        assert_eq!("ছবি", t("chobi"));
        assert_eq!("ধন", t("dhon"));
    }

    #[test]
    fn conjuncts() {
        assert_eq!("ক্ক", t("kk"));
        assert_eq!("স্কুল", t("skul"));
        assert_eq!("বন্ধু", t("bondhu"));
        assert_eq!("শক্তি", t("shokti"));
    }

    #[test]
    fn consonants_outside_the_conjunct_list_stay_apart() {
        assert_eq!("বলছি", t("bolchi"));
        assert_eq!("করব", t("korbo"));
        assert_eq!("একদম", t("ekdom"));
        assert_eq!("দেখতে", t("dekhte"));
        assert_eq!("আজকে", t("ajke"));
        assert_eq!("প্রথম", t("prothom"));
        assert_eq!("ক্যা", t("kya"));
        assert_eq!("দ্বীপ", t("dwIp"));
    }

    #[test]
    fn n_takes_the_nasal_of_its_class() {
        assert_eq!("অঙ্ক", t("onko"));
        assert_eq!("শঙ্খ", t("shonkh"));
        assert_eq!("অঞ্চল", t("oncol"));
        assert_eq!("পঞ্ছ", t("poncho"));
    }

    #[test]
    fn fixed_conjunct_kkh() {
        assert_eq!("ক্ষ", t("kkh"));
        assert_eq!("ক্ষমা", t("kkhoma"));
        assert_eq!("লক্ষ", t("lokkh"));
    }

    #[test]
    fn reph_via_double_r() {
        assert_eq!("বর্ন", t("borrno"));
        assert_eq!("মর্ম", t("morrmo"));
        assert_eq!("ধর্ম", t("dhorrmo"));
        assert_eq!("আদর্শ", t("adorrsho"));
        assert_eq!("নির্ভর", t("nirrvor"));
        assert_eq!("কার্য", t("karryo"));
        assert_eq!("ধরম", t("dhormo"));
        assert_eq!("করলে", t("korle"));
        assert_eq!("ক্র", t("kr"));
        assert_eq!("ঋণ", t("rriN"));
        assert_eq!("কৃপা", t("krripa"));
        assert_eq!("বরর", t("borr"));
    }

    #[test]
    fn a_after_kar_glides_with_antastya_yo() {
        assert_eq!("কি\u{9DF}ামত", t("kiamot"));
        assert_eq!("পি\u{9DF}ানো", t("pianO"));
        assert_eq!("দে\u{9DF}া", t("dea"));
        assert_eq!("মা\u{9DF}া", t("maa"));
        assert_eq!("ও\u{9DF}াসি", t("Oasi"));
        assert_eq!("ও\u{9DF}াসি", t("wasi"));
        assert_eq!("খাও\u{9DF}া", t("khaOa"));
        assert_eq!("কুরআন", t("kuroan"));
        assert_eq!("কিআমত", t("kiAmot"));
        assert_eq!("আমি", t("ami"));
    }

    #[test]
    fn only_capital_o_writes_o_kar() {
        assert_eq!("স্বপ্ন", t("swopno"));
        assert_eq!("কষ্ট", t("koShTo"));
        assert_eq!("সত্য", t("sotyo"));
        assert_eq!("ভাল", t("valo"));
        assert_eq!("কাল", t("kalo"));
        assert_eq!("ভালো", t("valO"));
        assert_eq!("কালো", t("kalO"));
        assert_eq!("দুঃখ", t("du:kho"));
    }

    #[test]
    fn anusvara_joins_nothing() {
        assert_eq!("বাংলা", t("bangla"));
        assert_eq!("পংক্তি", t("pongkti"));
        assert_eq!("আকাংখা", t("akangkha"));
        assert_eq!("অংক", t("ongko"));
        assert_eq!("সঙ্গে", t("songe"));
        assert_eq!("জঙ্গল", t("jongol"));
        assert_eq!("বাঙ্গালি", t("bangali"));
        assert_eq!("বাঙালি", t("baNgali"));
        assert_eq!("চাঁদ", t("ca^d"));
        assert_eq!("দুঃখ", t("du:kh"));
    }

    #[test]
    fn ba_fola_via_w() {
        assert_eq!("স্বাস্থ্য", t("swasthyo"));
        assert_eq!("স্বাধীনতা", t("swadhInota"));
        assert_eq!("ও\u{9DF}াসি", t("wasi"));
        assert_eq!("খাও\u{9DF}া", t("khaOa"));
    }

    #[test]
    fn n_before_j_is_nio() {
        assert_eq!("পাঞ্জাবি", t("panjabi"));
        assert_eq!("অঞ্জন", t("onjon"));
    }

    #[test]
    fn capitals_that_spell_nothing_read_as_lowercase() {
        assert_eq!("বাংলা", t("Bangla"));
        assert_eq!("কেমন", t("Kemon"));
        assert_eq!("ছল", t("Cholo"));
        assert_eq!("ভাল", t("Valo"));
        assert_eq!("খাবার", t("KHabar"));
        assert_eq!("টাকা", t("Taka"));
        assert_eq!("নদী", t("nodI"));
        assert_eq!("ভালো", t("valO"));
        assert_eq!("ওকে!", t("OkE!"));
    }

    #[test]
    fn ridmik_spellings() {
        assert_eq!("হঠাৎ", t("hoThaTH"));
        assert_eq!("দুঃখ", t("duHHkho"));
        assert_eq!("চাঁদ", t("caqqd"));
        assert_eq!("চাঁদ", t("cacbd"));
        assert_eq!("জ্ঞান", t("ggan"));
        assert_eq!("ন্ব", t("nhsb"));
        assert_eq!("আহসান", t("ahsan"));
    }

    #[test]
    fn cluster_breaks_only_with_inherent_vowel() {
        assert_eq!("কলকাতা", t("kolokata"));
    }

    #[test]
    fn vowel_signs() {
        assert_eq!("বই", t("boi"));
        assert_eq!("নদী", t("nodI"));
        assert_eq!("দুই", t("dui"));
    }

    #[test]
    fn retroflex() {
        assert_eq!("টাকা", t("Taka"));
        assert_eq!("ডাল", t("Dal"));
        assert_eq!("ব\u{9DC}", t("boR"));
    }

    #[test]
    fn digits_and_punctuation() {
        assert_eq!("১২৩", t("123"));
        assert_eq!("।", t("."));
        assert_eq!(".", t(".."));
        assert_eq!("৳", t("$"));
    }

    #[test]
    fn anusvara_and_chandrabindu() {
        assert_eq!("রং", t("rong"));
        assert_eq!("চাঁদ", t("ca^d"));
        assert_eq!("দুঃখ", t("du:kh"));
    }

    #[test]
    fn jofola_and_antastya_yo() {
        assert_eq!("শ্যাম", t("shyam"));
        assert_eq!("ত্যাগ", t("tyag"));
        assert_eq!("ব্যাপার", t("byapar"));
        assert_eq!("মে\u{9DF}ে", t("meye"));
        assert_eq!("নি\u{9DF}ে", t("niye"));
        assert_eq!("হ\u{9DF}", t("hoy"));
        assert_eq!("ই\u{9DF}", t("y"));
        assert_eq!("ই\u{9DF}েস", t("yes"));
        assert_eq!("ক\u{9DF}া", t("kYa"));
    }

    #[test]
    fn backquote_breaks_the_cluster() {
        assert_eq!("ক্স", t("ks"));
        assert_eq!("কস", t("k`s"));
        assert_eq!("বন্ধু", t("bondhu"));
        assert_eq!("বনধু", t("bon`dhu"));
        assert_eq!("কি", t("k`i"));
        assert_eq!("ক\u{9DF}া", t("k`ya"));
    }

    #[test]
    fn khanda_ta() {
        assert_eq!("হঠাৎ", t("hoThat`"));
        assert_eq!("হঠাৎ", t("hoThat,,"));
        assert_eq!("হঠাৎ আমি", t("hoThat,, ami"));
        assert_eq!("হঠাৎ।", t("hoThat,,."));
        assert_eq!("সত্য", t("sotyo"));
        assert_eq!("ত্ব", t("t,,b"));
        assert_eq!("ক্", t("k,,"));
    }

    #[test]
    fn jofola_via_capital_z() {
        assert_eq!("্য", t("Z"));
        assert_eq!("প্যা", t("poZa"));
        assert_eq!("সত্য", t("sotZo"));
        assert_eq!("সত্য", t("sotyo"));
        assert_eq!("আ\u{9DF}", t("ay"));
        assert_eq!("অ্যা", t("aZ"));
        assert_eq!("অ্যাপ", t("aZp"));
    }

    #[test]
    fn q_is_ka() {
        assert_eq!("কলম", t("qolom"));
        assert_eq!("কলম", t("Qolom"));
        assert_eq!("বাক্য", t("baqyo"));
        assert_eq!("চাঁদ", t("caqqd"));
    }

    #[test]
    fn o_after_a_vowel_is_o() {
        assert_eq!("আমিও", t("amio"));
        assert_eq!("খাও\u{9DF}া", t("khaoa"));
        assert_eq!("দিও", t("dio"));
        assert_eq!("ভাল", t("bhalo"));
    }

    #[test]
    fn double_e_is_long_i() {
        assert_eq!("কী", t("kee"));
        assert_eq!("ঈদ", t("eed"));
    }

    #[test]
    fn w_glides_into_the_vowel_after_it() {
        assert_eq!("ও\u{9DF}ে", t("we"));
        assert_eq!("সাও\u{9DF}াল", t("sawal"));
    }

    #[test]
    fn x_opens_a_word_with_e() {
        assert_eq!("এক্সরে", t("xre"));
        assert_eq!("বাক্স", t("bax"));
        assert_eq!("ক্ষ", t("kx"));
    }

    #[test]
    fn plain_r_before_jofola_keeps_its_shape() {
        assert_eq!("র\u{200D}্যাব", t("rZab"));
        assert_eq!("কার্য", t("karryo"));
    }

    #[test]
    fn ellipsis_stays_dots() {
        assert_eq!("...", t("..."));
    }

    #[test]
    fn mixed_text_passes_through() {
        assert_eq!("ওকে!", t("OkE!"));
    }

    #[test]
    fn capital_j_is_ja_with_a_nukta() {
        assert_eq!("\u{99C}\u{9BC}", t("J"));
    }
}

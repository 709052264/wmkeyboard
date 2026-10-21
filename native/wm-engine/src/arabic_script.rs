//! The walk shared by every roman-to-Arabic-script transliterator here
//! ([`crate::urdu`], [`crate::persian`], [`crate::arabic`]), each of which is
//! only a [`Table`]: its own letters, its own consonants and vowels. A port
//! of `ArabicScriptPhonetic.kt`.
//!
//! What the three have in common is what the script does, not what the
//! languages do:
//!
//! - short vowels are not written inside a word, because the script marks
//!   them with diacritics nobody types; a long vowel is a letter (ا و ی);
//! - a word-initial vowel takes its carrier (ا, آ) and a word-final one is
//!   always a letter, because a word cannot end on a mark;
//! - a vowel letter after a vowel letter takes a hamza carrier (ئ before the
//!   ye, ؤ for a و after ا);
//! - a doubled consonant is written once; the shadda is not typed and the
//!   word lists do not carry it;
//! - a ی or و that is a consonant ("yeh", "woh") and the vowel after it are
//!   one letter, not two.
//!
//! Only runs of roman letters (and, for Arabizi, the digits that stand for
//! letters) are read; everything else passes through. A run of digits on its
//! own stays a number even where digits spell letters: "2024" is a year,
//! "3ala" is على.

use std::collections::{HashMap, HashSet};

use crate::sort;

pub const ALIF: char = '\u{627}';
pub const MADDA: char = '\u{622}';
pub const WAO: char = '\u{648}';
pub const HAMZA_YE: char = '\u{626}';
pub const HAMZA_WAO: char = '\u{624}';

/// A vowel's spelling where it opens, sits inside and closes a word.
pub struct Vowel {
    pub matches: &'static str,
    pub initial: &'static str,
    pub medial: &'static str,
    pub final_: &'static str,
}

impl Vowel {
    pub const fn new(
        matches: &'static str,
        initial: &'static str,
        medial: &'static str,
        final_: &'static str,
    ) -> Vowel {
        Vowel {
            matches,
            initial,
            medial,
            final_,
        }
    }
}

/// One language's letters.
pub struct Table {
    /// The ye the language writes: Urdu and Persian ی, Arabic ي.
    pub ye: char,
    /// A letter that spells a closing "e" and takes a hamza after a vowel, as
    /// Urdu's ے does (گئے); `None` where there is none.
    pub final_e: Option<char>,
    /// Roman token to what it writes.
    pub consonants: Vec<(&'static str, &'static str)>,
    pub vowels: Vec<Vowel>,
    /// What "kh" writes when it opens a word, where that differs from what it
    /// writes elsewhere (Urdu: خ, against کھ inside a word).
    pub kh_initial: Option<char>,
    /// The digits that spell letters (Arabizi's 2 3 5 6 7 8 9), so they
    /// belong to the word they sit in.
    pub digits: &'static str,
    /// Other spellings of a closing "a" the strip offers: Arabic's ة and ى.
    pub final_a_variants: Vec<&'static str>,
    /// What a vowel leaning on a vowel is carried by before a ye: ئ in Urdu
    /// and Arabic, a plain ی in Persian (کجایی, پاییز).
    pub hamza_ye: char,
    /// The same before a و after ا: ؤ, or Persian's plain و.
    pub hamza_wao: char,
    /// Word-initial prefixes written as a unit of their own, joined to the
    /// stem by a ZWNJ: Persian's می‌ and نمی‌. Only split off a stem of at
    /// least `prefix_min_stem` tokens, so "miz" stays میز.
    pub prefixes: Vec<(&'static str, &'static str)>,
    pub prefix_min_stem: usize,
    /// Spellings of the definite article ("al", "el", "il") that write
    /// `article_out` when a consonant follows.
    pub article: Vec<&'static str>,
    pub article_out: &'static str,
    /// The letters whose doubling after an opening vowel is the article
    /// assimilated ("essalam" is السلام).
    pub sun: Vec<&'static str>,
    /// Inner short vowels the strip may also offer long, and as what.
    pub long_vowels: Vec<(&'static str, &'static str)>,
}

impl Table {
    /// A table with the defaults every field but the first four has.
    pub fn new(
        ye: char,
        final_e: Option<char>,
        consonants: Vec<(&'static str, &'static str)>,
        vowels: Vec<Vowel>,
    ) -> Table {
        Table {
            ye,
            final_e,
            consonants,
            vowels,
            kh_initial: None,
            digits: "",
            final_a_variants: Vec::new(),
            hamza_ye: HAMZA_YE,
            hamza_wao: HAMZA_WAO,
            prefixes: Vec::new(),
            prefix_min_stem: 2,
            article: Vec::new(),
            article_out: "\u{627}\u{644}",
            sun: Vec::new(),
            long_vowels: Vec::new(),
        }
    }
}

struct Rule {
    matches: Vec<char>,
    out: String,
    forms: Option<usize>,
}

/// One token of a word: a table rule, or a character no rule reads.
#[derive(Clone, Copy)]
enum Tok<'a> {
    Rule(&'a Rule),
    Literal(char),
}

impl Tok<'_> {
    fn is_vowel(self) -> bool {
        matches!(self, Tok::Rule(rule) if rule.forms.is_some())
    }

    fn matches(self, text: &str) -> bool {
        match self {
            Tok::Rule(rule) => rule.matches.iter().copied().eq(text.chars()),
            Tok::Literal(c) => {
                let mut chars = text.chars();
                chars.next() == Some(c) && chars.next().is_none()
            }
        }
    }

    fn match_text(self) -> String {
        match self {
            Tok::Rule(rule) => rule.matches.iter().collect(),
            Tok::Literal(c) => c.to_string(),
        }
    }
}

/// Which inner "a"s are read as a long ا.
#[derive(Clone, Copy, PartialEq, Eq)]
enum LongA {
    None,
    Last,
    First,
    All,
}

/// One way of reading a word; `variants` walks through several.
#[derive(Clone, Copy)]
struct Options {
    long_a: LongA,
    final_a: Option<&'static str>,
    long_inner: bool,
    prefix: bool,
}

const LITERAL: Options = Options {
    long_a: LongA::None,
    final_a: None,
    long_inner: false,
    prefix: true,
};

pub struct Engine {
    table: Table,
    rules: Vec<Rule>,
    by_first: HashMap<char, Vec<usize>>,
    /// The letters a following vowel cannot simply sit next to.
    vowel_letters: HashSet<char>,
}

impl Engine {
    pub fn new(table: Table) -> Engine {
        let mut rules: Vec<Rule> = table
            .consonants
            .iter()
            .map(|&(m, out)| Rule {
                matches: m.chars().collect(),
                out: out.to_string(),
                forms: None,
            })
            .chain(table.vowels.iter().enumerate().map(|(i, v)| Rule {
                matches: v.matches.chars().collect(),
                out: String::new(),
                forms: Some(i),
            }))
            .collect();
        sort::by_key(&mut rules, |rule| std::cmp::Reverse(rule.matches.len()));
        let mut by_first: HashMap<char, Vec<usize>> = HashMap::new();
        for (i, rule) in rules.iter().enumerate() {
            by_first.entry(rule.matches[0]).or_default().push(i);
        }
        let mut vowel_letters: HashSet<char> =
            [ALIF, MADDA, WAO, table.ye, table.hamza_ye, table.hamza_wao]
                .into_iter()
                .collect();
        if let Some(e) = table.final_e {
            vowel_letters.insert(e);
        }
        Engine {
            table,
            rules,
            by_first,
            vowel_letters,
        }
    }

    /// Transliterates roman text, one word or a sentence, into the script.
    pub fn transliterate(&self, input: &str) -> String {
        self.render(input, LITERAL)
    }

    /// Every reading of `input` worth offering, the literal one first: the
    /// spelling with its last, every or first inner "a" long; then the
    /// table's other closing spellings of an "a", alone and with the long-a
    /// readings; then the inner vowels read long; and last, for a table with
    /// prefixes, the readings that do not split one off (میوه, not می‌وه).
    pub fn variants(&self, input: &str) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        let mut add = |rendered: String| {
            if !out.contains(&rendered) {
                out.push(rendered);
            }
        };
        let readings = [LongA::None, LongA::Last, LongA::All, LongA::First];
        for long_a in readings {
            add(self.render(input, Options { long_a, ..LITERAL }));
        }
        for &closing in &self.table.final_a_variants {
            add(self.render(
                input,
                Options {
                    final_a: Some(closing),
                    ..LITERAL
                },
            ));
        }
        for long_a in [LongA::Last, LongA::All] {
            for &closing in &self.table.final_a_variants {
                add(self.render(
                    input,
                    Options {
                        long_a,
                        final_a: Some(closing),
                        ..LITERAL
                    },
                ));
            }
        }
        if !self.table.long_vowels.is_empty() {
            add(self.render(
                input,
                Options {
                    long_inner: true,
                    ..LITERAL
                },
            ));
            if let Some(&closing) = self.table.final_a_variants.first() {
                add(self.render(
                    input,
                    Options {
                        long_inner: true,
                        final_a: Some(closing),
                        ..LITERAL
                    },
                ));
            }
        }
        if !self.table.prefixes.is_empty() {
            for long_a in readings {
                add(self.render(
                    input,
                    Options {
                        long_a,
                        prefix: false,
                        ..LITERAL
                    },
                ));
            }
        }
        out
    }

    fn is_word_char(&self, c: char) -> bool {
        c.is_ascii_alphabetic() || c == '\'' || self.table.digits.contains(c)
    }

    fn render(&self, input: &str, options: Options) -> String {
        let input: Vec<char> = input.chars().collect();
        let mut out = String::with_capacity(input.len() * 2);
        let mut i = 0;
        while i < input.len() {
            if self.is_word_char(input[i]) {
                let mut j = i;
                while j < input.len() && self.is_word_char(input[j]) {
                    j += 1;
                }
                let run = &input[i..j];
                // A number is a number, even where digits spell letters.
                if run.iter().all(|c| c.is_ascii_digit()) {
                    out.extend(run.iter());
                } else {
                    self.word(run, options, &mut out);
                }
                i = j;
            } else {
                out.push(input[i]);
                i += 1;
            }
        }
        out
    }

    fn exact_rule_at(&self, input: &[char], at: usize) -> Option<&Rule> {
        self.by_first
            .get(&input[at])?
            .iter()
            .map(|&i| &self.rules[i])
            .find(|rule| input[at..].starts_with(&rule.matches))
    }

    /// `exact_rule_at`, falling back to the lowercase reading of a capital
    /// that spells nothing of its own.
    fn rule_at(&self, input: &[char], at: usize) -> Option<&Rule> {
        if let Some(rule) = self.exact_rule_at(input, at) {
            return Some(rule);
        }
        if !input[at].is_ascii_uppercase() {
            return None;
        }
        let lowered: Vec<char> = input[at..].iter().flat_map(|c| c.to_lowercase()).collect();
        self.exact_rule_at(&lowered, 0)
    }

    fn tokenize(&self, word: &[char]) -> Vec<Tok<'_>> {
        let mut tokens = Vec::with_capacity(word.len());
        let mut i = 0;
        while i < word.len() {
            match self.rule_at(word, i) {
                // A character no rule reads still has to advance the walk.
                None => {
                    tokens.push(Tok::Literal(word[i]));
                    i += 1;
                }
                Some(rule) => {
                    tokens.push(Tok::Rule(rule));
                    i += rule.matches.len();
                }
            }
        }
        tokens
    }

    /// The prefix `word` opens with and the stem after it, when a table
    /// prefix leaves a stem long enough to be a word of its own.
    fn split_prefix(&self, word: &[char]) -> Option<(&'static str, Vec<char>)> {
        let lower: String = word.iter().flat_map(|c| c.to_lowercase()).collect();
        for &(roman, native) in &self.table.prefixes {
            if !lower.starts_with(roman) {
                continue;
            }
            let stem = word[roman.chars().count()..].to_vec();
            if !stem.is_empty() && self.tokenize(&stem).len() >= self.table.prefix_min_stem {
                return Some((native, stem));
            }
        }
        None
    }

    /// The article `word` opens with and the rest of it: "al", "el" or "il"
    /// before a consonant, or an opening vowel and a doubled sun letter
    /// ("essalam", the article assimilated to the s).
    fn split_article(&self, word: &[char]) -> Option<(&'static str, Vec<char>)> {
        let lower: Vec<char> = word.iter().flat_map(|c| c.to_lowercase()).collect();
        let starts = |at: usize, text: &str| {
            text.chars()
                .enumerate()
                .all(|(i, c)| lower.get(at + i) == Some(&c))
        };
        for &spelling in &self.table.article {
            let n = spelling.chars().count();
            if starts(0, spelling) && word.len() > n + 1 {
                if let Some(next) = self.rule_at(word, n) {
                    if next.forms.is_none() {
                        return Some((self.table.article_out, word[n..].to_vec()));
                    }
                }
            }
        }
        if !self.table.sun.is_empty() && word.len() > 4 && matches!(lower[0], 'a' | 'e' | 'i') {
            for &letter in &self.table.sun {
                let n = letter.chars().count();
                if starts(1, letter) && starts(1 + n, letter) && word.len() > 1 + 2 * n + 1 {
                    return Some((self.table.article_out, word[1 + n..].to_vec()));
                }
            }
        }
        None
    }

    fn word(&self, whole: &[char], options: Options, out: &mut String) {
        let start = out.len();
        if options.prefix {
            if let Some((prefix, stem)) = self.split_prefix(whole) {
                out.push_str(prefix);
                // The stem is a word of its own after the prefix's ZWNJ.
                self.word(
                    &stem,
                    Options {
                        prefix: false,
                        ..options
                    },
                    out,
                );
                return;
            }
        }
        let word: Vec<char> = match self.split_article(whole) {
            Some((article, rest)) => {
                out.push_str(article);
                rest
            }
            None => whole.to_vec(),
        };
        let tokens = self.tokenize(&word);
        let last_index = tokens.len().saturating_sub(1);
        let inner: Vec<usize> = (0..tokens.len())
            .filter(|&i| tokens[i].matches("a") && i != 0 && i != last_index)
            .collect();
        let long: Vec<usize> = match options.long_a {
            _ if inner.is_empty() => Vec::new(),
            LongA::All => inner.clone(),
            LongA::Last => vec![*inner.last().unwrap()],
            LongA::First => vec![inner[0]],
            LongA::None => Vec::new(),
        };
        let long_inner: Vec<usize> = if options.long_inner {
            (0..tokens.len())
                .filter(|&i| {
                    i != 0
                        && i != last_index
                        && tokens[i].is_vowel()
                        && self
                            .table
                            .long_vowels
                            .iter()
                            .any(|(m, _)| tokens[i].matches(m))
                })
                .collect()
        } else {
            Vec::new()
        };
        let mut prev_was_vowel = false;
        for (index, token) in tokens.iter().copied().enumerate() {
            let rule = match token {
                Tok::Rule(rule) if rule.forms.is_some() => rule,
                _ => {
                    let text: String = match token {
                        Tok::Rule(rule) => rule.out.clone(),
                        Tok::Literal(c) => c.to_string(),
                    };
                    // The shadda nobody types: one letter, not two.
                    if !prev_was_vowel && out[start..].ends_with(&text) {
                        continue;
                    }
                    match self.table.kh_initial {
                        Some(kh) if out.len() == start && token.matches("kh") => out.push(kh),
                        _ => out.push_str(&text),
                    }
                    prev_was_vowel = false;
                    continue;
                }
            };
            let forms = &self.table.vowels[rule.forms.unwrap()];
            let opening = out.len() == start;
            let mut form: String = if opening {
                forms.initial.to_string()
            } else if index == last_index {
                match options.final_a {
                    Some(final_a) if token.matches("a") => final_a.to_string(),
                    _ => forms.final_.to_string(),
                }
            } else if long.contains(&index) {
                ALIF.to_string()
            } else if long_inner.contains(&index) {
                let m = token.match_text();
                self.table
                    .long_vowels
                    .iter()
                    .find(|(k, _)| *k == m)
                    .map(|(_, v)| v.to_string())
                    .unwrap()
            } else {
                forms.medial.to_string()
            };
            if !form.is_empty() && !opening {
                let last = out.chars().last();
                // A vowel cannot lean on another vowel, so it takes a hamza
                // carrier. Only after a vowel: the ی of "yar" and the و of
                // "wada" are consonants, and nothing hangs off them.
                if prev_was_vowel && last.is_some_and(|c| self.vowel_letters.contains(&c)) {
                    let first = form.chars().next().unwrap();
                    if first == WAO && (last == Some(ALIF) || last == Some(MADDA)) {
                        form = format!("{}{}", self.table.hamza_wao, &form[WAO.len_utf8()..]);
                    } else if first == self.table.ye || Some(first) == self.table.final_e {
                        form = format!("{}{}", self.table.hamza_ye, form);
                    }
                }
                // One letter, not two: the consonant and the vowel written
                // with the same letter ("yeh", "woh").
                let mut chars = form.chars();
                if let (Some(only), None) = (chars.next(), chars.next()) {
                    if last == Some(only) {
                        form.clear();
                    }
                }
            }
            out.push_str(&form);
            prev_was_vowel = true;
        }
    }
}

//! Burmese in Zawgyi: telling it from Unicode, and converting between them.
//!
//! Zawgyi is the font encoding most Burmese text was written in before
//! Unicode took hold, and much of it still is. It uses the code points of the
//! Myanmar block, but assigns glyphs rather than letters to them and stores
//! text in visual order: ေ goes in front of its consonant, medial ra is one of
//! several code points depending on its width. The same string is readable in
//! one encoding and garbage in the other, so a keyboard has to know which one
//! a field holds before it reads it or writes into it.
//!
//! A port of google/myanmar-tools v1.2.0 (Apache-2.0), the library Android
//! and Facebook use for this:
//!
//! - [`zawgyi_probability`]: its detector, a pair of Markov chains over the
//!   Myanmar code points (`data/myanmar/zawgyiUnicodeModel.dat`, the model
//!   file of that release, read as its Java client reads it).
//! - [`to_unicode`], [`to_zawgyi`], [`normalize_zawgyi`]: its converters,
//!   the rule phases its Java client compiles from CLDR's
//!   `my-t-my-s0-zawgyi` transform, in the generated [`crate::zawgyi_rules`],
//!   run by the same procedure as that client's `Phase.runPhase`.
//!
//! Both are held to the Java client's results on the upstream test corpus
//! (`data/myanmar/tests`). The rule patterns use a small part of Java's regex
//! language (literals, classes, groups, `*`, `+`, `$`), which [`Pattern`]
//! matches with Java's semantics rather than pulling in a regex crate.

use std::sync::OnceLock;

use crate::zawgyi_rules::{self, Rule};

/// The detector's verdict a keyboard acts on without being asked: the text is
/// Zawgyi with almost no doubt. Upstream's "difficult" Unicode sentences that
/// also spell something in Zawgyi score up to 0.99973, while Zawgyi of ten
/// letters or more scores above 0.9999999 on the UDHR, so the bar sits
/// between them.
pub const CONFIDENT: f64 = 0.99999;

/// Below this many Myanmar letters a probability says little either way: a
/// syllable or two can read as both encodings.
pub const MIN_LETTERS: usize = 4;

// ---- Detector ----------------------------------------------------------------

/// Probability that `text` is Zawgyi, given that it is Zawgyi or Unicode:
/// near 1 is Zawgyi, near 0 Unicode, 0.5 a toss-up. Negative infinity when
/// `text` holds no Myanmar code point at all (digits count as none).
pub fn zawgyi_probability(text: &str) -> f64 {
    model().predict(text)
}

/// Whether `text` is confidently Zawgyi: [`CONFIDENT`] or above, with at
/// least [`MIN_LETTERS`] Myanmar letters to go on, and with something for
/// [`to_unicode`] to change (text that reads the same in both encodings is
/// not worth converting). What the keyboard asks before offering to convert
/// pasted text or a field's contents.
pub fn is_confidently_zawgyi(text: &str) -> bool {
    myanmar_letters(text) >= MIN_LETTERS && zawgyi_probability(text) >= CONFIDENT && to_unicode(text) != text
}

/// Whether `text` holds a code point of the Myanmar block (U+1000–U+109F).
pub fn has_myanmar(text: &str) -> bool {
    text.chars().any(is_myanmar)
}

fn is_myanmar(c: char) -> bool {
    ('\u{1000}'..='\u{109F}').contains(&c)
}

/// Myanmar code points other than the digits, which the detector skips too.
fn myanmar_letters(text: &str) -> usize {
    text.chars().filter(|&c| is_myanmar(c) && !('\u{1040}'..='\u{1049}').contains(&c)).count()
}

const STD: (u32, u32) = (0x1000, 0x103F);
const AFT: (u32, u32) = (0x104A, 0x109F);
const EXA: (u32, u32) = (0xAA60, 0xAA7F);
const EXB: (u32, u32) = (0xA9E0, 0xA9FF);
const SPC: (u32, u32) = (0x2000, 0x200B);

const fn span((a, b): (u32, u32)) -> u32 {
    b - a + 1
}

const STD_OFFSET: u32 = 1;
const AFT_OFFSET: u32 = STD_OFFSET + span(STD);
const EXA_OFFSET: u32 = AFT_OFFSET + span(AFT);
const EXB_OFFSET: u32 = EXA_OFFSET + span(EXA);
const SPC_OFFSET: u32 = EXB_OFFSET + span(EXB);
const END_OFFSET: u32 = SPC_OFFSET + span(SPC);

/// Which code points the model has states for (upstream's "SSV").
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum StateSet {
    /// Myanmar, Extended-A, Extended-B and the spaces U+2000–U+200B.
    WithSpaces,
    /// The same without the spaces.
    WithoutSpaces,
}

struct Model {
    states: StateSet,
    size: usize,
    /// Row-major `size × size`: log P(Zawgyi) − log P(Unicode) per transition.
    deltas: Vec<f32>,
}

impl Model {
    fn state(&self, c: char) -> usize {
        let cp = c as u32;
        let within = |(a, b): (u32, u32)| (a..=b).contains(&cp);
        let index = if within(STD) {
            cp - STD.0 + STD_OFFSET
        } else if within(AFT) {
            cp - AFT.0 + AFT_OFFSET
        } else if within(EXA) {
            cp - EXA.0 + EXA_OFFSET
        } else if within(EXB) {
            cp - EXB.0 + EXB_OFFSET
        } else if self.states == StateSet::WithSpaces && within(SPC) {
            cp - SPC.0 + SPC_OFFSET
        } else {
            0
        };
        index as usize
    }

    /// `ZawgyiUnicodeMarkovModel.predict`: every transition between states,
    /// the edges of the text included, except non-Myanmar to non-Myanmar.
    fn predict(&self, text: &str) -> f64 {
        let mut previous = 0usize;
        let mut total = 0f64;
        let mut seen = false;
        for current in text.chars().map(|c| self.state(c)).chain(std::iter::once(0)) {
            if previous != 0 || current != 0 {
                total += f64::from(self.deltas[previous * self.size + current]);
                seen = true;
            }
            previous = current;
        }
        if !seen {
            return f64::NEG_INFINITY;
        }
        1.0 / (1.0 + total.exp())
    }

    /// The model file as the Java client's `DataInputStream` reads it: big-
    /// endian, a "UZMODEL " header and version, then a "BMARKOV " block of
    /// sparse rows, each a fallback value and its exceptions.
    fn read(bytes: &[u8]) -> Model {
        let mut r = Reader { bytes, at: 0 };
        assert_eq!(r.u64(), 0x555A_4D4F_4445_4C20, "not a Zawgyi model");
        let states = match r.u32() {
            1 => StateSet::WithSpaces,
            2 => match r.u32() {
                0 => StateSet::WithSpaces,
                1 => StateSet::WithoutSpaces,
                other => panic!("unknown state set {other}"),
            },
            other => panic!("unknown model version {other}"),
        };
        assert_eq!(r.u64(), 0x424D_4152_4B4F_5620, "not a binary Markov block");
        assert_eq!(r.u32(), 0, "unknown Markov block version");
        let size = r.u16() as usize;
        let expected = if states == StateSet::WithSpaces { END_OFFSET } else { SPC_OFFSET };
        assert_eq!(size, expected as usize, "model size does not match its state set");
        let mut deltas = vec![0f32; size * size];
        for from in 0..size {
            let mut entries = r.u16();
            let fallback = if entries == 0 { 0.0 } else { r.f32() };
            let mut next: i32 = -1;
            for to in 0..size {
                if entries > 0 && next < to as i32 {
                    next = i32::from(r.u16());
                    entries -= 1;
                }
                deltas[from * size + to] = if next == to as i32 { r.f32() } else { fallback };
            }
        }
        assert_eq!(r.at, bytes.len(), "trailing bytes after the model");
        Model { states, size, deltas }
    }
}

struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl Reader<'_> {
    fn take<const N: usize>(&mut self) -> [u8; N] {
        let out = self.bytes[self.at..self.at + N].try_into().unwrap();
        self.at += N;
        out
    }
    fn u16(&mut self) -> u16 {
        u16::from_be_bytes(self.take())
    }
    fn u32(&mut self) -> u32 {
        u32::from_be_bytes(self.take())
    }
    fn u64(&mut self) -> u64 {
        u64::from_be_bytes(self.take())
    }
    fn f32(&mut self) -> f32 {
        f32::from_be_bytes(self.take())
    }
}

fn model() -> &'static Model {
    static MODEL: OnceLock<Model> = OnceLock::new();
    MODEL.get_or_init(|| Model::read(include_bytes!("../data/myanmar/zawgyiUnicodeModel.dat")))
}

// ---- Converters ----------------------------------------------------------------

/// Zawgyi text as Unicode. Anything that is not Myanmar passes through,
/// though the rules do drop spaces and joiners that sit in front of a
/// combining sign, as upstream does.
pub fn to_unicode(text: &str) -> String {
    converter(&Z2U, zawgyi_rules::Z2U).convert(text)
}

/// Unicode Burmese as Zawgyi.
pub fn to_zawgyi(text: &str) -> String {
    converter(&U2Z, zawgyi_rules::U2Z).convert(text)
}

/// Zawgyi in one spelling of its many: the code points of a glyph that has
/// several put in one order, so two Zawgyi strings that look alike compare
/// equal. Upstream's tests compare converted Zawgyi this way.
pub fn normalize_zawgyi(text: &str) -> String {
    converter(&ZNORM, zawgyi_rules::ZNORM).convert(text)
}

/// Converts whichever way `text` needs: to Unicode when the detector leans
/// Zawgyi, to Zawgyi otherwise. The selection action, which the user asks for
/// on text they can see, so the toss-up line is enough.
pub fn convert_either_way(text: &str) -> String {
    if zawgyi_probability(text) > 0.5 {
        to_unicode(text)
    } else {
        to_zawgyi(text)
    }
}

static Z2U: OnceLock<Transliterator> = OnceLock::new();
static U2Z: OnceLock<Transliterator> = OnceLock::new();
static ZNORM: OnceLock<Transliterator> = OnceLock::new();

fn converter(cell: &'static OnceLock<Transliterator>, rules: &'static [&'static [Rule]]) -> &'static Transliterator {
    cell.get_or_init(|| Transliterator::new(rules))
}

struct CompiledRule {
    pattern: Pattern,
    substitution: Vec<Piece>,
    on_start: bool,
    revisit: bool,
}

enum Piece {
    Text(char),
    Group(usize),
}

struct Transliterator {
    phases: Vec<Vec<CompiledRule>>,
}

impl Transliterator {
    fn new(phases: &'static [&'static [Rule]]) -> Transliterator {
        let phases = phases
            .iter()
            .map(|rules| {
                rules
                    .iter()
                    .map(|&(pattern, substitution, on_start, revisit)| CompiledRule {
                        pattern: Pattern::compile(pattern),
                        substitution: parse_substitution(substitution),
                        on_start,
                        revisit,
                    })
                    .collect()
            })
            .collect();
        Transliterator { phases }
    }

    fn convert(&self, text: &str) -> String {
        let mut chars: Vec<char> = text.chars().collect();
        for phase in &self.phases {
            chars = run_phase(phase, chars);
        }
        chars.into_iter().collect()
    }
}

/// `Phase.runPhase` in the Java client, step for step. At each position every
/// rule of the phase is tried in order, each on what the ones before it left:
/// a rule that matches writes its substitution out and moves on past it, or,
/// with `revisit`, puts it back in front of the rest for the rules after it
/// to see. A position no rule matched gives up its first character.
fn run_phase(rules: &[CompiledRule], input: Vec<char>) -> Vec<char> {
    let mut out = Vec::with_capacity(input.len());
    let mut mid = input;
    let mut at = 0usize;
    let mut start = true;
    let mut groups = Vec::new();
    while at < mid.len() {
        let mut found = false;
        for rule in rules {
            if rule.on_start && !start {
                continue;
            }
            let Some(len) = rule.pattern.match_start(&mid[at..], &mut groups) else { continue };
            found = true;
            let rest = &mid[at..];
            let mut replaced = Vec::new();
            for piece in &rule.substitution {
                match *piece {
                    Piece::Text(c) => replaced.push(c),
                    Piece::Group(n) => {
                        if let Some(Some((a, b))) = groups.get(n) {
                            replaced.extend_from_slice(&rest[*a..*b]);
                        }
                    }
                }
            }
            if rule.revisit {
                replaced.extend_from_slice(&rest[len..]);
                mid = replaced;
                at = 0;
            } else {
                out.extend_from_slice(&replaced);
                at += len;
            }
        }
        if !found {
            out.push(mid[at]);
            at += 1;
        }
        start = false;
    }
    out
}

fn parse_substitution(s: &str) -> Vec<Piece> {
    let mut out = Vec::new();
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '$' {
            let digit = chars.next().and_then(|d| d.to_digit(10)).expect("$ without a group number");
            out.push(Piece::Group(digit as usize));
        } else {
            out.push(Piece::Text(c));
        }
    }
    out
}

// ---- Patterns ------------------------------------------------------------------

/// A regex in the part of Java's syntax the rules use, matched only at the
/// start of the text (`Rule` prepends `^`), with Java's greedy backtracking
/// and its groups: a group inside a repeat holds its last iteration, and one
/// that never took part holds nothing.
struct Pattern {
    items: Vec<Item>,
    groups: usize,
}

struct Item {
    node: Node,
    min: usize,
    max: usize,
}

enum Node {
    Char(char),
    Class { ranges: Vec<(char, char)>, negated: bool },
    Group { index: usize, items: Vec<Item> },
    /// Java's `$` without MULTILINE: the end of the text, or in front of one
    /// line terminator that ends it.
    End,
}

impl Node {
    /// Whether this node always takes exactly one character, tested alone.
    fn single(&self) -> bool {
        matches!(self, Node::Char(_) | Node::Class { .. })
    }

    fn accepts(&self, c: char) -> bool {
        match self {
            Node::Char(want) => *want == c,
            Node::Class { ranges, negated } => ranges.iter().any(|&(a, b)| a <= c && c <= b) != *negated,
            _ => false,
        }
    }
}

type Groups = Vec<Option<(usize, usize)>>;

impl Pattern {
    fn compile(source: &str) -> Pattern {
        let chars: Vec<char> = source.chars().collect();
        let mut at = 0;
        let mut groups = 0;
        let items = parse_sequence(&chars, &mut at, &mut groups);
        assert_eq!(at, chars.len(), "unbalanced pattern {source:?}");
        Pattern { items, groups }
    }

    /// The length of the match at the start of `text`, with the groups it
    /// captured (index 0 is the whole match), or None.
    fn match_start(&self, text: &[char], groups: &mut Groups) -> Option<usize> {
        groups.clear();
        groups.resize(self.groups + 1, None);
        let mut end = None;
        let matched = Matcher { text }.sequence(&self.items, 0, 0, groups, &mut |at, _| {
            end = Some(at);
            true
        });
        if !matched {
            return None;
        }
        let end = end?;
        groups[0] = Some((0, end));
        Some(end)
    }
}

fn parse_sequence(chars: &[char], at: &mut usize, groups: &mut usize) -> Vec<Item> {
    let mut items = Vec::new();
    while *at < chars.len() && chars[*at] != ')' {
        let node = match chars[*at] {
            '(' => {
                *at += 1;
                *groups += 1;
                let index = *groups;
                let inner = parse_sequence(chars, at, groups);
                assert_eq!(chars.get(*at), Some(&')'), "unclosed group");
                *at += 1;
                Node::Group { index, items: inner }
            }
            '[' => {
                *at += 1;
                let negated = chars.get(*at) == Some(&'^');
                if negated {
                    *at += 1;
                }
                let mut ranges = Vec::new();
                let mut first = true;
                while first || chars[*at] != ']' {
                    first = false;
                    let a = chars[*at];
                    *at += 1;
                    if chars[*at] == '-' && chars[*at + 1] != ']' {
                        let b = chars[*at + 1];
                        *at += 2;
                        ranges.push((a, b));
                    } else {
                        ranges.push((a, a));
                    }
                }
                *at += 1;
                Node::Class { ranges, negated }
            }
            '$' => {
                *at += 1;
                Node::End
            }
            c => {
                assert!(!matches!(c, '*' | '+' | '^' | ']'), "unexpected {c:?} in a pattern");
                *at += 1;
                Node::Char(c)
            }
        };
        let (min, max) = match chars.get(*at) {
            Some('*') => (0, usize::MAX),
            Some('+') => (1, usize::MAX),
            _ => (1, 1),
        };
        if max != 1 {
            *at += 1;
        }
        items.push(Item { node, min, max });
    }
    items
}

struct Matcher<'t> {
    text: &'t [char],
}

type Next<'n> = &'n mut dyn FnMut(usize, &mut Groups) -> bool;

impl Matcher<'_> {
    /// Matches `items[i..]` from `at`, then hands the end to `next`; true as
    /// soon as `next` accepts. Alternatives are tried in Java's order.
    fn sequence(&self, items: &[Item], i: usize, at: usize, groups: &mut Groups, next: Next) -> bool {
        let Some(item) = items.get(i) else { return next(at, groups) };
        if item.node.single() {
            // A repeated character test: count how far it reaches, then give
            // back one at a time, longest first. Iterative, so a long run of
            // one sign costs no stack.
            let reach = self.text[at..].iter().take(item.max).take_while(|&&c| item.node.accepts(c)).count();
            if reach < item.min {
                return false;
            }
            return (item.min..=reach).rev().any(|n| self.sequence(items, i + 1, at + n, groups, next));
        }
        if let Node::Group { index, items: inner } = &item.node {
            if let [only] = inner.as_slice() {
                if only.node.single() && only.min == 1 && only.max == 1 {
                    // `([…])+`: the same, with the group on the last character.
                    let reach = self.text[at..].iter().take(item.max).take_while(|&&c| only.node.accepts(c)).count();
                    if reach < item.min {
                        return false;
                    }
                    let saved = groups[*index];
                    for n in (item.min..=reach).rev() {
                        groups[*index] = if n == 0 { saved } else { Some((at + n - 1, at + n)) };
                        if self.sequence(items, i + 1, at + n, groups, next) {
                            return true;
                        }
                    }
                    groups[*index] = saved;
                    return false;
                }
            }
        }
        self.repeat(items, i, 0, at, groups, next)
    }

    /// The general repeat: one more of `items[i]` if allowed (greedy), else
    /// the rest of the sequence.
    fn repeat(&self, items: &[Item], i: usize, count: usize, at: usize, groups: &mut Groups, next: Next) -> bool {
        let item = &items[i];
        if count < item.max {
            let more = self.one(&item.node, at, groups, &mut |end, groups| {
                // An iteration that took nothing would repeat forever. Only a
                // repeat can loop: a single group or `$` may match empty.
                if end == at && item.max > 1 {
                    return false;
                }
                self.repeat(items, i, count + 1, end, groups, next)
            });
            if more {
                return true;
            }
        }
        count >= item.min && self.sequence(items, i + 1, at, groups, next)
    }

    fn one(&self, node: &Node, at: usize, groups: &mut Groups, next: Next) -> bool {
        match node {
            Node::Char(_) | Node::Class { .. } => {
                self.text.get(at).is_some_and(|&c| node.accepts(c)) && next(at + 1, groups)
            }
            Node::End => self.at_end(at) && next(at, groups),
            Node::Group { index, items } => {
                let index = *index;
                let saved = groups[index];
                let matched = self.sequence(items, 0, at, groups, &mut |end, groups| {
                    let before = groups[index];
                    groups[index] = Some((at, end));
                    if next(end, groups) {
                        return true;
                    }
                    groups[index] = before;
                    false
                });
                if !matched {
                    groups[index] = saved;
                }
                matched
            }
        }
    }

    fn at_end(&self, at: usize) -> bool {
        let rest = &self.text[at..];
        match rest {
            [] => true,
            ['\r', '\n'] => true,
            ['\n'] => !(at > 0 && self.text[at - 1] == '\r'),
            [c] => matches!(c, '\r' | '\u{85}' | '\u{2028}' | '\u{2029}'),
            _ => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lines(text: &str) -> impl Iterator<Item = &str> {
        text.lines().filter(|l| !l.starts_with('#'))
    }

    fn hex(s: &str) -> String {
        s.chars().map(|c| format!("{:04X}", c as u32)).collect::<Vec<_>>().join(" ")
    }

    #[test]
    fn model_reads_whole() {
        let m = model();
        assert_eq!(m.states, StateSet::WithSpaces);
        assert_eq!(m.size, END_OFFSET as usize);
    }

    /// ZawgyiDetectorTest.testCompatibility: every line of upstream's
    /// compatibility table, to 1e-6.
    #[test]
    fn detector_matches_compatibility_table() {
        let table = include_str!("../data/myanmar/tests/compatibility.tsv");
        let mut checked = 0;
        for line in table.lines() {
            let (expected, input) = line.split_once('\t').unwrap();
            let actual = zawgyi_probability(input);
            if expected == "-Infinity" {
                assert_eq!(actual, f64::NEG_INFINITY, "{line}");
            } else {
                let expected: f64 = expected.parse().unwrap();
                assert!((actual - expected).abs() < 1e-6, "{line}: got {actual}");
            }
            checked += 1;
        }
        assert_eq!(checked, 50);
    }

    fn named(name: &str) -> &'static str {
        lines(include_str!("../data/myanmar/tests/detector_named.tsv"))
            .map(|l| l.split_once('\t').unwrap())
            .find(|(n, _)| *n == name)
            .unwrap()
            .1
    }

    fn difficult() -> Vec<(f64, &'static str)> {
        lines(include_str!("../data/myanmar/tests/detector_difficult.tsv"))
            .map(|l| l.split_once('\t').unwrap())
            .map(|(score, text)| (score.parse().unwrap(), text))
            .collect()
    }

    /// The rest of ZawgyiDetectorTest.
    #[test]
    fn detector_upstream_cases() {
        assert_eq!(zawgyi_probability("hello world"), f64::NEG_INFINITY);
        assert_eq!(zawgyi_probability(named("allASCII")), f64::NEG_INFINITY);
        assert!(zawgyi_probability(named("mixedUnicode")) < 0.01);
        assert!(zawgyi_probability(named("mixedZawgyi")) > 0.99);
        assert!(zawgyi_probability(named("strongUnicode")) < 0.001);
        assert!(zawgyi_probability(named("strongZawgyi")) > 0.999);
        // Digits are not Myanmar to the detector.
        assert_eq!(zawgyi_probability("\u{1049}\u{1046}.\u{1040} kHz"), f64::NEG_INFINITY);
        let dagon = named("mixedUnicode").split(' ').nth(2).unwrap();
        assert_eq!(zawgyi_probability(&format!("\u{1042}\u{1044}\u{1040}\u{1049} {dagon}")), zawgyi_probability(&format!(" {dagon}")));
    }

    /// ZawgyiDetectorTest.testDifficult, scores to 0.005.
    #[test]
    fn detector_difficult_cases() {
        let cases = difficult();
        assert_eq!(cases.len(), 8);
        for (expected, input) in cases {
            let actual = zawgyi_probability(input);
            assert!((actual - expected).abs() < 0.005, "{input}: got {actual}");
        }
    }

    /// The bar the keyboard acts on: clear Zawgyi passes, Unicode never does,
    /// and the short or ambiguous strings upstream lists as difficult stay out.
    #[test]
    fn confidence_threshold() {
        assert!(is_confidently_zawgyi(named("strongZawgyi")));
        assert!(is_confidently_zawgyi(named("mixedZawgyi")));
        assert!(!is_confidently_zawgyi(named("strongUnicode")));
        assert!(!is_confidently_zawgyi(named("mixedUnicode")));
        assert!(!is_confidently_zawgyi("hello world"));
        // Upstream's difficult strings: Unicode that also spells something in
        // Zawgyi and scores 0.995, strings that read the same in both, and
        // real Zawgyi with too little in it to act on unasked. None of them
        // is acted on, except the one upstream scores 1.0, which is Zawgyi.
        for (score, input) in difficult() {
            assert_eq!(is_confidently_zawgyi(input), score == 1.0, "{input}");
        }
        // Every Unicode line of the UDHR scores below the bar, and every
        // Zawgyi line of it with enough letters clears it.
        let unicode = include_str!("../data/myanmar/tests/udhr_mya_unicode_src.txt");
        let zawgyi = include_str!("../data/myanmar/tests/udhr_mya_zawgyi_out.txt");
        let (mut zawgyi_lines, mut caught) = (0, 0);
        for (u, z) in unicode.lines().zip(zawgyi.lines()) {
            assert!(!is_confidently_zawgyi(u), "Unicode line read as Zawgyi: {u}");
            if myanmar_letters(z) >= 40 && to_unicode(z) != z {
                zawgyi_lines += 1;
                if is_confidently_zawgyi(z) {
                    caught += 1;
                }
            }
        }
        assert!(zawgyi_lines > 50, "{zawgyi_lines}");
        assert_eq!(caught, zawgyi_lines, "long Zawgyi lines missed");
    }

    #[test]
    fn has_myanmar_spans_the_block() {
        assert!(has_myanmar("abc က"));
        assert!(has_myanmar("႟"));
        assert!(!has_myanmar("abc"));
    }

    /// TransliterateTest.sanity.
    #[test]
    fn non_myanmar_passes_through() {
        for s in ["hello world", "", "123", ".!@|"] {
            assert_eq!(to_unicode(s), s);
            assert_eq!(to_zawgyi(s), s);
            assert_eq!(normalize_zawgyi(s), s);
        }
        assert_eq!(to_unicode("\u{1011}\u{1039}"), "\u{1011}\u{103A}");
        assert_eq!(to_zawgyi("\u{1011}\u{103A}"), "\u{1011}\u{1039}");
    }

    fn pairs() -> Vec<(&'static str, &'static str)> {
        lines(include_str!("../data/myanmar/tests/convert_pairs.tsv"))
            .map(|l| l.split_once('\t').unwrap())
            .collect()
    }

    /// TransliterateTest.z2UTests.
    #[test]
    fn zawgyi_to_unicode_pairs() {
        let pairs = pairs();
        assert_eq!(pairs.len(), 122);
        for (i, (z, u)) in pairs.into_iter().enumerate() {
            let actual = to_unicode(z);
            assert_eq!(actual, u, "Z2U case {i}\n  input  {}\n  expect {}\n  actual {}", hex(z), hex(u), hex(&actual));
        }
    }

    /// TransliterateTest.u2ZTests: compared after normalising, as upstream does.
    #[test]
    fn unicode_to_zawgyi_pairs() {
        for (i, (z, u)) in pairs().into_iter().enumerate() {
            let actual = to_zawgyi(u);
            assert_eq!(
                normalize_zawgyi(&actual),
                normalize_zawgyi(z),
                "U2Z case {i}\n  input  {}\n  expect {}\n  actual {}",
                hex(u),
                hex(z),
                hex(&actual),
            );
        }
    }

    /// TransliterateTest.zNormTests.
    #[test]
    fn normalize_pairs() {
        for (i, (z, n)) in lines(include_str!("../data/myanmar/tests/normalize_pairs.tsv"))
            .map(|l| l.split_once('\t').unwrap())
            .enumerate()
        {
            assert_eq!(normalize_zawgyi(z), n, "normalize case {i}");
        }
    }

    /// TransliterateTest.z2UCompatibilityTest: the Myanmar government corpus.
    #[test]
    fn zawgyi_to_unicode_corpus() {
        let src = include_str!("../data/myanmar/tests/mmgov_zawgyi_src.txt");
        let gold = include_str!("../data/myanmar/tests/mmgov_unicode_out.txt");
        let mut n = 0;
        for (i, (z, u)) in src.lines().zip(gold.lines()).enumerate() {
            assert_eq!(to_unicode(z), u, "mmgov line {i}");
            n += 1;
        }
        assert_eq!(n, gold.lines().count());
    }

    /// TransliterateTest.u2ZCompatibilityTest: the UDHR, compared normalised.
    #[test]
    fn unicode_to_zawgyi_corpus() {
        let src = include_str!("../data/myanmar/tests/udhr_mya_unicode_src.txt");
        let gold = include_str!("../data/myanmar/tests/udhr_mya_zawgyi_out.txt");
        for (i, (u, z)) in src.lines().zip(gold.lines()).enumerate() {
            assert_eq!(normalize_zawgyi(&to_zawgyi(u)), normalize_zawgyi(z), "udhr line {i}");
        }
    }

    /// What the keyboard's Zawgyi output relies on: Unicode it typed comes
    /// back as the same Unicode once it has been through Zawgyi. The one
    /// difference allowed is the order of the dot below and the asat, which
    /// the UDHR text writes in Unicode 5.1's order (asat first) and the
    /// converter writes in canonical order (U+1037 has the lower combining
    /// class); the two are the same text to every renderer.
    #[test]
    fn unicode_round_trips_through_zawgyi() {
        let canonical = |s: &str| s.replace("\u{103A}\u{1037}", "\u{1037}\u{103A}");
        let src = include_str!("../data/myanmar/tests/udhr_mya_unicode_src.txt");
        let mut total = 0;
        let mut differ = Vec::new();
        for u in src.lines().filter(|l| has_myanmar(l)) {
            total += 1;
            if to_unicode(&to_zawgyi(u)) != canonical(u) {
                differ.push(u);
            }
        }
        assert!(total > 80, "{total}");
        // Two upstream limits, and nothing else. Zawgyi writers type the
        // digit four for ၎ (U+104E), so a ၄ standing alone (an article
        // number) comes back as that letter. And the medial ra Zawgyi writes
        // in front of ဖ in မှဖြစ် is read back onto the မ before it.
        let numbered = |u: &str| u.trim_start().starts_with("\u{1021}\u{1015}\u{102D}\u{102F}\u{1012}\u{103A} ");
        let ha_then_ra = |u: &str| u.contains("\u{1019}\u{103E}\u{1016}\u{103C}");
        assert!(differ.iter().all(|u| numbered(u) || ha_then_ra(u)), "{differ:#?}");
        assert!(differ.len() <= 2, "{differ:#?}");
        for word in ["မြန်မာ", "ကျေးဇူးတင်ပါတယ်", "မင်္ဂလာပါ", "နေကောင်းလား", "ပြည်ထောင်စု"] {
            assert_eq!(to_unicode(&to_zawgyi(word)), word, "{word}");
        }
    }

    #[test]
    fn typed_words_in_zawgyi() {
        // ေ and medial ra go in front of the consonant in Zawgyi.
        assert_eq!(to_zawgyi("နေ"), "\u{1031}\u{1014}");
        assert_eq!(to_zawgyi("မြန်မာ"), "\u{103B}\u{1019}\u{1014}\u{1039}\u{1019}\u{102C}");
        assert_eq!(to_unicode("\u{103B}\u{1019}\u{1014}\u{1039}\u{1019}\u{102C}"), "မြန်မာ");
    }

    #[test]
    fn either_way() {
        let z = "အျပည္ျပည္ဆိုင္ရာ လူ႔အခြင့္အေရး ေၾကညာစာတမ္း";
        let u = to_unicode(z);
        assert_eq!(convert_either_way(z), u);
        assert_eq!(normalize_zawgyi(&convert_either_way(&u)), normalize_zawgyi(&to_zawgyi(&u)));
    }

    #[test]
    fn dollar_is_javas() {
        let p = Pattern::compile("a$");
        let mut g = Vec::new();
        let t = |s: &str| s.chars().collect::<Vec<_>>();
        assert_eq!(p.match_start(&t("a"), &mut g), Some(1));
        assert_eq!(p.match_start(&t("a\n"), &mut g), Some(1));
        assert_eq!(p.match_start(&t("a\r\n"), &mut g), Some(1));
        assert_eq!(p.match_start(&t("ab"), &mut g), None);
        assert_eq!(p.match_start(&t("a\n\n"), &mut g), None);
    }

    #[test]
    fn groups_backtrack_and_repeat() {
        let t = |s: &str| s.chars().collect::<Vec<_>>();
        let mut g = Vec::new();
        let p = Pattern::compile("([ab])+c");
        assert_eq!(p.match_start(&t("abac"), &mut g), Some(4));
        assert_eq!(g[1], Some((2, 3)));
        let p = Pattern::compile("(a*)(a)");
        assert_eq!(p.match_start(&t("aaa"), &mut g), Some(3));
        assert_eq!(g[1], Some((0, 2)));
        assert_eq!(g[2], Some((2, 3)));
        let p = Pattern::compile("([^0-9])x");
        assert_eq!(p.match_start(&t("\nx"), &mut g), Some(2));
    }
}

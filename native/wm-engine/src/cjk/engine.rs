//! The three conversion composers over one shared state: the Kotlin
//! `PinyinComposer`, `JyutpingComposer` and `JapaneseComposer`, with the
//! globals they read (`CjkDictionaries`, `CjkConfig`, `CjkLearning`,
//! `CjkContext`) as fields of [`Cjk`].
//!
//! Every length here counts characters of the buffer. A candidate's
//! `consumed` is how many the caller deletes from the front of the buffer
//! when it commits; what is left converts on its own.

use super::dict::ConversionDictionary;
use super::han::{is_han, HanLevels, HanVariant};
use super::history::{rank, CandidateAdjustment, UserHistory};
use super::jyutping;
use super::kana;
use super::lattice::{self, Arc, Graph, Input, Opts, UserWords};
use super::ngrams::CjkNgrams;
use super::pinyin::{self, DoubleTable, JianpinIndex, Kind, Now, PinyinTables};
use super::segment::Seg;
use std::collections::{HashMap, HashSet};

pub const NS_PINYIN: &str = "pinyin";
pub const NS_JYUTPING: &str = "jyutping";
pub const NS_JAPANESE: &str = "ja_kana";

/// The conversion method a buffer is typed in.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Method {
    Pinyin,
    Jyutping,
    Japanese,
}

/// One entry of the candidate list.
#[derive(Clone, Debug, PartialEq)]
pub struct Candidate {
    /// What to write: the decoder's word, in Traditional when that is on.
    pub text: String,
    /// Buffer characters it covers.
    pub consumed: usize,
    pub reading: String,
    /// What the decoder found, which `text` may be the Traditional form of.
    pub source: String,
    /// A date, time or numeral shortcut: never learned or adjusted.
    pub special: bool,
}

/// Pinyin's switches (the phone's `CjkConfig`).
#[derive(Clone, Debug)]
pub struct PinyinConfig {
    pub fuzzy: bool,
    /// Which of [`pinyin::FUZZY_PAIRS`] `fuzzy` applies.
    pub fuzzy_pairs: HashSet<String>,
    pub jianpin: bool,
    /// The Double Pinyin scheme's table; `None` types full pinyin.
    pub double: Option<DoubleTable>,
    pub shortcuts: bool,
    pub prediction: bool,
}

impl Default for PinyinConfig {
    fn default() -> Self {
        PinyinConfig {
            fuzzy: false,
            fuzzy_pairs: pinyin::all_fuzzy_pairs(),
            jianpin: true,
            double: None,
            shortcuts: true,
            prediction: true,
        }
    }
}

/// The stroke table read backwards: character → its shortest stroke code.
#[derive(Clone, Debug, Default)]
pub struct StrokeCodes(HashMap<String, String>);

impl StrokeCodes {
    /// `code<TAB>hanzi<TAB>freq` lines, codes of the digits 1–5 only.
    pub fn parse(text: &str) -> StrokeCodes {
        let mut rows: Vec<(&str, &str)> = Vec::new();
        for raw in text.lines() {
            let line = raw.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let mut parts = line.split('\t');
            let (Some(code), Some(word)) = (parts.next(), parts.next()) else {
                continue;
            };
            let (code, word) = (code.trim(), word.trim());
            if code.is_empty() || word.is_empty() || !code.chars().all(|c| ('1'..='5').contains(&c))
            {
                continue;
            }
            rows.push((code, word));
        }
        rows.sort_by(|a, b| a.0.cmp(b.0));
        let mut out: HashMap<String, String> = HashMap::new();
        for (code, word) in rows {
            match out.get(word) {
                Some(current) if current.len() <= code.len() => {}
                _ => {
                    out.insert(word.to_string(), code.to_string());
                }
            }
        }
        StrokeCodes(out)
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    pub fn code_of(&self, word: &str) -> Option<&str> {
        self.0.get(word).map(String::as_str)
    }
}

const LIMIT: usize = 12;
const LOOKUP_LIMIT: usize = 128;
const MAX_LATTICE_UNITS: usize = 24;
const FUZZY_VARIANTS: usize = 2;
const STITCH_CANDIDATES: usize = 3;
const MIN_STITCH_UNITS: usize = 3;
const STROKE_MARK: char = '`';
const STROKE_GLYPHS: [char; 5] = ['一', '丨', '丿', '丶', '乙'];
const PHRASE_SYLLABLES: usize = 4;
const PHRASE_SINGLES: usize = 6;
const PREDICT_CONTEXT: usize = 4;
const LAZY_VARIANTS: usize = 2;
const LOOSE_PENALTY: f64 = -0.035;
/// Longest stretch of context kept.
pub const CONTEXT_KEEP: usize = 8;
const CONTEXT_WORD: usize = 4;

fn fuzzy_penalty() -> f64 {
    0.15f64.ln()
}
fn partial_penalty() -> f64 {
    0.15f64.ln()
}
fn typo_penalty() -> f64 {
    0.05f64.ln()
}
fn swap_penalty() -> f64 {
    0.1f64.ln()
}
fn extra_penalty() -> f64 {
    0.03f64.ln()
}
fn alias_penalty() -> f64 {
    0.6f64.ln()
}
fn completion_cost() -> f64 {
    0.5f64.ln()
}
fn lazy_penalty() -> f64 {
    0.15f64.ln()
}

/// The run of Han characters `text` ends with, at most [`CONTEXT_KEEP`].
pub fn han_tail(text: &str) -> String {
    let tail: Vec<char> = text
        .chars()
        .rev()
        .take_while(|c| is_han(*c))
        .take(CONTEXT_KEEP)
        .collect();
    tail.into_iter().rev().collect()
}

/// The longest suffix of `text`, four characters at most, `known` recognises.
pub fn previous_word(text: &str, known: impl Fn(&str) -> bool) -> Option<String> {
    let chars: Vec<char> = text.chars().collect();
    for len in (1..=CONTEXT_WORD.min(chars.len())).rev() {
        let suffix: String = chars[chars.len() - len..].iter().collect();
        if known(&suffix) {
            return Some(suffix);
        }
    }
    None
}

fn char_len(s: &str) -> usize {
    s.chars().count()
}

fn drop_chars(s: &str, n: usize) -> String {
    s.chars().skip(n).collect()
}

/// One buffer committed a pick at a time: what is left, and what the picks
/// so far wrote and read.
#[derive(Clone, Debug)]
struct Run {
    rest: String,
    parts: Vec<String>,
    reading: String,
}

struct UnitOptions {
    readings: Vec<String>,
    costs: Vec<f64>,
    partial_from: usize,
}

/// Everything the composers read, and what they remember between calls.
#[derive(Default)]
pub struct Cjk {
    pub pinyin_tables: PinyinTables,
    pub pinyin_dict: ConversionDictionary,
    /// Mandarin word pairs, for everything converting through `pinyin_dict`.
    pub pinyin_ngrams: CjkNgrams,
    pub stroke: StrokeCodes,
    pub jyutping_syllables: HashSet<String>,
    pub jyutping_dict: ConversionDictionary,
    pub japanese_dict: ConversionDictionary,
    /// Word pairs for Japanese and Cantonese.
    pub ngrams: CjkNgrams,
    pub levels: HanLevels,
    pub variant: HanVariant,
    pub history: UserHistory,
    /// Whether picks may be recorded (learning on, not incognito).
    pub learning: bool,
    pub pinyin: PinyinConfig,
    /// Cantonese lazy pronunciation (懶音).
    pub lazy_jyutping: bool,
    /// Read a plain kana typed as kana as its small and voiced forms too.
    pub loose_kana_marks: bool,
    /// The local time `rq`, `sj` and `xq` answer with; none offers nothing.
    pub now: Option<Now>,
    before: String,
    run: Option<Run>,
    generation: u64,
    cache: Option<(Method, String, u64, Vec<Candidate>)>,
}

impl Cjk {
    /// The bundled syllable inventories, Han levels and conversion tables;
    /// the dictionaries and word pairs are the caller's to load.
    pub fn bundled() -> Cjk {
        Cjk {
            pinyin_tables: PinyinTables::bundled(),
            jyutping_syllables: jyutping::bundled_syllables(),
            levels: HanLevels::bundled(),
            variant: HanVariant::bundled(),
            learning: true,
            ..Cjk::default()
        }
    }

    /// Drops the cached ranking: call after changing any public field.
    pub fn invalidate(&mut self) {
        self.generation += 1;
    }

    fn namespace(method: Method) -> &'static str {
        match method {
            Method::Pinyin => NS_PINYIN,
            Method::Jyutping => NS_JYUTPING,
            Method::Japanese => NS_JAPANESE,
        }
    }

    // --- Context -----------------------------------------------------------

    /// The text before the caret when a buffer starts.
    pub fn set_context(&mut self, before: &str) {
        self.before = han_tail(before);
        self.invalidate();
    }

    /// A conversion just committed, as the context for what comes next.
    pub fn committed(&mut self, text: &str) {
        self.before = han_tail(&format!("{}{text}", self.before));
        self.invalidate();
    }

    pub fn context(&self) -> &str {
        &self.before
    }

    fn context_word(&self) -> Option<String> {
        if self.before.is_empty() {
            return None;
        }
        let pairs = self.history.pairs_for(NS_PINYIN);
        if self.pinyin_ngrams.is_empty() && pairs.is_none() {
            return None;
        }
        previous_word(&self.before, |w| {
            self.pinyin_ngrams.unigram(w) > 0 || pairs.is_some_and(|p| p.contains_key(w))
        })
    }

    // --- What the composing region shows -----------------------------------

    pub fn compose_buffer(&self, method: Method, buffer: &str) -> String {
        match method {
            Method::Pinyin => {
                if let Some(cut) = buffer.find(STROKE_MARK) {
                    let strokes: String = stroke_digits(&buffer[cut + 1..])
                        .chars()
                        .map(|d| STROKE_GLYPHS[d as usize - '1' as usize])
                        .collect();
                    return format!("{} {strokes}", self.compose_buffer(method, &buffer[..cut]));
                }
                match &self.pinyin.double {
                    Some(t) => pinyin::double_translate(
                        &buffer.to_lowercase(),
                        t,
                        &self.pinyin_tables.valid,
                    ),
                    None => buffer.to_lowercase(),
                }
            }
            Method::Jyutping => buffer.to_lowercase(),
            Method::Japanese => kana::to_hiragana(buffer),
        }
    }

    /// What Enter writes: the keys as pressed for Pinyin, else the reading.
    pub fn typed_reading(&self, method: Method, buffer: &str) -> String {
        match method {
            Method::Pinyin => buffer.to_string(),
            _ => self.compose_buffer(method, buffer),
        }
    }

    /// Whether a character other than a letter or `'` goes into the buffer.
    pub fn buffers_char(&self, method: Method, c: char, composing: &str) -> bool {
        match method {
            Method::Pinyin => {
                if c == STROKE_MARK {
                    return !composing.is_empty()
                        && !composing.contains(STROKE_MARK)
                        && !pinyin::is_number(composing);
                }
                self.pinyin.shortcuts && pinyin::continues_number(composing, c)
            }
            // Tone digits continue a syllable; they never start one.
            Method::Jyutping => c.is_ascii_digit() && !composing.is_empty(),
            // A hyphen inside a word is the long-vowel mark.
            Method::Japanese => c == '-' && !composing.is_empty(),
        }
    }

    /// What is left of the buffer once a pick consumed its front.
    pub fn remainder_after_commit(&self, method: Method, rest: &str) -> String {
        match (method, rest.find(STROKE_MARK)) {
            (Method::Pinyin, Some(cut)) => rest[..cut].to_string(),
            _ => rest.to_string(),
        }
    }

    // --- Candidates ---------------------------------------------------------

    pub fn candidates(&mut self, method: Method, buffer: &str, limit: usize) -> Vec<Candidate> {
        let mut out = self.ranked(method, buffer);
        out.truncate(limit);
        out
    }

    /// The strip's twelve.
    pub fn strip(&mut self, method: Method, buffer: &str) -> Vec<Candidate> {
        self.candidates(method, buffer, LIMIT)
    }

    /// Buffer characters the candidate at `index` covers; the whole buffer
    /// when there is none.
    pub fn consumed_for_index(&mut self, method: Method, buffer: &str, index: usize) -> usize {
        let len = char_len(buffer);
        self.ranked(method, buffer)
            .get(index)
            .map(|c| c.consumed)
            .unwrap_or(len)
    }

    fn ranked(&mut self, method: Method, buffer: &str) -> Vec<Candidate> {
        match method {
            Method::Pinyin => self.ranked_pinyin(&buffer.to_lowercase()),
            Method::Jyutping => {
                let b = buffer.to_lowercase();
                self.cached(method, &b, |c| c.rank_jyutping(&b))
            }
            Method::Japanese => self.cached(method, buffer, |c| c.rank_japanese(buffer)),
        }
    }

    fn cached(
        &mut self,
        method: Method,
        key: &str,
        compute: impl FnOnce(&Cjk) -> Vec<Candidate>,
    ) -> Vec<Candidate> {
        if key.is_empty() {
            return Vec::new();
        }
        if let Some((m, k, g, v)) = &self.cache {
            if *m == method && k == key && *g == self.generation {
                return v.clone();
            }
        }
        let value = compute(self);
        self.cache = Some((method, key.to_string(), self.generation, value.clone()));
        value
    }

    /// Teaches the history the pick at `index`.
    pub fn learn_choice(&mut self, method: Method, buffer: &str, index: usize) {
        let ns = Self::namespace(method);
        let Some(cand) = self.ranked(method, buffer).get(index).cloned() else {
            return;
        };
        if method != Method::Pinyin {
            self.learn(ns, &cand.reading, &cand.text);
            return;
        }
        let b = buffer.to_lowercase();
        if cand.special {
            self.run = None;
            return;
        }
        self.learn(ns, &cand.reading, &cand.source);
        let current = self.run.take().filter(|r| r.rest == b);
        let previous = current
            .as_ref()
            .and_then(|r| r.parts.last().cloned())
            .or_else(|| self.context_word());
        if let Some(previous) = previous {
            self.learn_pair(ns, &previous, &cand.source);
        }
        let rest =
            self.remainder_after_commit(method, &drop_chars(&b, cand.consumed.min(char_len(&b))));
        let mut parts = current
            .as_ref()
            .map(|r| r.parts.clone())
            .unwrap_or_default();
        parts.push(cand.source.clone());
        let reading = format!(
            "{}{}",
            current.map(|r| r.reading).unwrap_or_default(),
            cand.reading
        );
        if !rest.is_empty() {
            self.run = Some(Run {
                rest,
                parts,
                reading,
            });
            return;
        }
        self.learn_phrase(&parts, &reading);
    }

    fn learn(&mut self, ns: &str, reading: &str, word: &str) {
        if self.learning {
            self.history.learn(ns, reading, word);
            self.invalidate();
        }
    }

    fn learn_pair(&mut self, ns: &str, previous: &str, word: &str) {
        if self.learning {
            self.history.learn_pair(ns, previous, word);
            self.invalidate();
        }
    }

    /// A buffer committed in two picks or more, learned as one word, unless
    /// the word pairs already join every pick to the next.
    fn learn_phrase(&mut self, parts: &[String], reading: &str) {
        if parts.len() < 2 {
            return;
        }
        let text: String = parts.concat();
        let singles = parts.iter().all(|p| char_len(p) == 1);
        if char_len(&text)
            > if singles {
                PHRASE_SINGLES
            } else {
                PHRASE_SYLLABLES
            }
        {
            return;
        }
        let pairs = &self.pinyin_ngrams;
        if !pairs.is_empty() && parts.windows(2).all(|w| pairs.bigram(&w[0], &w[1]) > 0) {
            return;
        }
        self.learn(NS_PINYIN, reading, &text);
    }

    /// The candidate menu's answer for the candidate at `index`.
    pub fn adjust_choice(
        &mut self,
        method: Method,
        buffer: &str,
        index: usize,
        adjustment: CandidateAdjustment,
    ) {
        let Some(cand) = self.ranked(method, buffer).get(index).cloned() else {
            return;
        };
        if cand.special {
            return;
        }
        let source = if method == Method::Pinyin {
            &cand.source
        } else {
            &cand.text
        };
        self.history.adjust(
            Self::namespace(method),
            &cand.reading,
            &cand.text,
            source,
            adjustment,
        );
        self.invalidate();
    }

    // --- Pinyin ------------------------------------------------------------

    fn jianpin(&self) -> Option<&JianpinIndex> {
        (self.pinyin.jianpin && self.pinyin.double.is_none()).then_some(&self.pinyin_tables.jianpin)
    }

    fn ranked_pinyin(&mut self, buffer: &str) -> Vec<Candidate> {
        if buffer.is_empty() {
            return Vec::new();
        }
        let len = char_len(buffer);
        if self.pinyin.shortcuts && pinyin::is_number(buffer) {
            return pinyin::numbers(buffer)
                .into_iter()
                .map(|n| special(n, len, buffer))
                .collect();
        }
        if let Some(cut) = buffer.find(STROKE_MARK) {
            return self.filtered(buffer, cut);
        }
        let context = self.context_word();
        let key = match &context {
            None => buffer.to_string(),
            Some(c) => format!("{buffer}\u{0}{c}"),
        };
        let mut base = self.cached(Method::Pinyin, &key, |c| {
            c.rank_pinyin(buffer, context.as_deref())
        });
        let extra = match (self.pinyin.shortcuts, self.now) {
            (true, Some(now)) => pinyin::expand_shortcut(buffer, now),
            _ => Vec::new(),
        };
        if extra.is_empty() {
            return base;
        }
        // Behind the reading's own best candidate: `sj` is also 时间.
        let at = base.len().min(1);
        let tail = base.split_off(at);
        base.extend(extra.into_iter().map(|e| special(e, len, buffer)));
        base.extend(tail);
        base
    }

    /// The reading before the backtick narrowed to the candidates whose first
    /// character starts with the strokes after it.
    fn filtered(&mut self, buffer: &str, cut: usize) -> Vec<Candidate> {
        let reading = &buffer[..cut];
        if reading.is_empty() {
            return Vec::new();
        }
        let strokes = stroke_digits(&buffer[cut + 1..]);
        let all = self.ranked_pinyin(reading);
        let first = |t: &str| t.chars().next().map(|c| c.to_string()).unwrap_or_default();
        let reading_len = char_len(reading);
        let buffer_len = char_len(buffer);
        all.into_iter()
            .filter(|c| {
                strokes.is_empty()
                    || self.stroke.is_empty()
                    || self
                        .stroke
                        .code_of(&first(&c.source))
                        .or_else(|| self.stroke.code_of(&first(&c.text)))
                        .is_some_and(|code| code.starts_with(&strokes))
            })
            .map(|mut c| {
                if c.consumed >= reading_len {
                    c.consumed = buffer_len;
                }
                c
            })
            .collect()
    }

    fn unit_options(&self, typed: &str, jianpin: Option<&JianpinIndex>) -> UnitOptions {
        let valid = &self.pinyin_tables.valid;
        let exact = jianpin.is_none() || valid.contains(typed);
        let mut readings = Vec::new();
        let mut costs = Vec::new();
        if exact {
            readings.push(typed.to_string());
            costs.push(0.0);
            if self.pinyin.fuzzy {
                for v in pinyin::fuzzy_expand(typed, valid, &self.pinyin.fuzzy_pairs)
                    .into_iter()
                    .filter(|v| v != typed)
                    .take(FUZZY_VARIANTS)
                {
                    readings.push(v);
                    costs.push(fuzzy_penalty());
                }
            }
        }
        let partial_from = readings.len();
        if let Some(j) = jianpin {
            for s in j.expansions.get(typed).into_iter().flatten() {
                if s != typed {
                    readings.push(s.clone());
                    costs.push(partial_penalty());
                }
            }
        }
        UnitOptions {
            readings,
            costs,
            partial_from,
        }
    }

    fn arcs_for(
        &self,
        unit: &str,
        to: usize,
        kind: Kind,
        jianpin: Option<&JianpinIndex>,
    ) -> Vec<Arc> {
        let repair = |penalty: f64| {
            let mut a = Arc::new(to, unit, penalty);
            a.guess = true;
            a.correction = true;
            vec![a]
        };
        match kind {
            Kind::Unit => {
                let o = self.unit_options(unit, jianpin);
                (0..o.readings.len())
                    .map(|i| {
                        let mut a = Arc::new(to, o.readings[i].clone(), o.costs[i]);
                        a.guess = i > 0 && i < o.partial_from;
                        a
                    })
                    .collect()
            }
            Kind::Digraph => jianpin
                .and_then(|j| j.expansions.get(unit))
                .into_iter()
                .flatten()
                .map(|s| Arc::new(to, s.clone(), 2.0 * partial_penalty()))
                .collect(),
            Kind::Typo => repair(typo_penalty()),
            Kind::Swap => repair(swap_penalty()),
            Kind::Extra => repair(extra_penalty()),
            Kind::Alias => {
                let o = self.unit_options(unit, None);
                (0..o.readings.len())
                    .map(|i| {
                        let mut a =
                            Arc::new(to, o.readings[i].clone(), o.costs[i] + alias_penalty());
                        a.guess = i > 0;
                        a
                    })
                    .collect()
            }
            Kind::Partial => vec![Arc::new(to, unit, partial_penalty())],
        }
    }

    /// One unit per syllable for Double Pinyin, where every split is fixed.
    fn pinyin_input(&self, segs: &[Seg]) -> Input {
        let lens: Vec<usize> = segs.iter().map(|s| s.input_len).collect();
        let units: Vec<UnitOptions> = segs
            .iter()
            .map(|s| self.unit_options(&s.syllable, None))
            .collect();
        let mut input = Input::options(units.iter().map(|u| u.readings.clone()).collect(), &lens);
        input.penalties = units.iter().map(|u| u.costs.clone()).collect();
        input.partial_from = units.iter().map(|u| u.partial_from).collect();
        input
    }

    fn rank_pinyin(&self, buffer: &str, context: Option<&str>) -> Vec<Candidate> {
        let len = char_len(buffer);
        let jianpin = self.jianpin();
        let (graph, stitch): (Option<Graph>, usize) = match &self.pinyin.double {
            Some(table) => {
                let segs = pinyin::double_segments(buffer, table, &self.pinyin_tables.valid);
                let graph = (!segs.is_empty() && segs.len() <= MAX_LATTICE_UNITS)
                    .then(|| self.pinyin_input(&segs).graph());
                (graph, 0)
            }
            None => {
                let arcs_for =
                    |unit: &str, to: usize, kind: Kind| self.arcs_for(unit, to, kind, jianpin);
                match pinyin::build_graph(buffer, &self.pinyin_tables, jianpin, &arcs_for) {
                    None => (None, 0),
                    Some(built) => {
                        let stitch = if built.units >= MIN_STITCH_UNITS
                            && built.abbreviated
                            && self.pinyin_ngrams.is_empty()
                        {
                            STITCH_CANDIDATES
                        } else {
                            0
                        };
                        (
                            (built.units <= MAX_LATTICE_UNITS).then_some(built.graph),
                            stitch,
                        )
                    }
                }
            }
        };
        let Some(graph) = graph else {
            return self.prefix_fallback(&self.pinyin_dict, buffer, len);
        };
        let penalty = |w: &str| self.levels.penalty(w);
        let opts = Opts {
            limit: LOOKUP_LIMIT,
            stitch,
            coverage_order: true,
            word_penalty: Some(&penalty),
            ..Opts::default()
        };
        let learned = |r: &str| self.history.picks_for(NS_PINYIN, r);
        let has_prefix = |p: &str| self.history.has_phrase_prefix(NS_PINYIN, p);
        let user = UserWords {
            has_prefix: &has_prefix,
            pairs: self.history.pairs_for(NS_PINYIN),
        };
        let decoded = lattice::decode(
            &graph,
            &self.pinyin_dict,
            &self.pinyin_ngrams,
            &opts,
            &learned,
            self.history.adjust_for(NS_PINYIN),
            context,
            &user,
        );
        let cands = self.traditional(decoded);
        let group = |c: &Candidate| c.consumed;
        rank(
            Some(&self.history),
            NS_PINYIN,
            cands,
            |c| &c.source,
            |c| &c.reading,
            |c| &c.text,
            Some(&group),
        )
    }

    /// Nothing segments yet: the dictionary's own prefix matching, which
    /// cannot offer a prefix commit but always answers.
    fn prefix_fallback(
        &self,
        dict: &ConversionDictionary,
        buffer: &str,
        len: usize,
    ) -> Vec<Candidate> {
        let mut out: Vec<Candidate> = Vec::new();
        for word in dict.candidates(buffer, LOOKUP_LIMIT) {
            for form in self.variant.traditional_forms(&word) {
                if !out.iter().any(|c| c.text == form) {
                    out.push(Candidate {
                        text: form,
                        consumed: len,
                        reading: buffer.to_string(),
                        source: word.clone(),
                        special: false,
                    });
                }
            }
        }
        out
    }

    /// Decoded candidates in every Traditional form, de-duplicated after the
    /// conversion, which can merge two Simplified words onto one form.
    fn traditional(&self, decoded: Vec<lattice::Cand>) -> Vec<Candidate> {
        let mut out: Vec<Candidate> = Vec::new();
        for c in decoded {
            for form in self.variant.traditional_forms(&c.text) {
                if !out.iter().any(|o| o.text == form) {
                    out.push(Candidate {
                        text: form,
                        consumed: c.consumed,
                        reading: c.reading.clone(),
                        source: c.text.clone(),
                        special: false,
                    });
                }
            }
        }
        out
    }

    /// What usually follows `context` (联想): the user's own habits, then
    /// the word pairs' followers of each of its last four endings, and the
    /// rest of a longer word one starts.
    pub fn predictions(&self, context: &str, limit: usize) -> Vec<String> {
        if !self.pinyin.prediction {
            return Vec::new();
        }
        let tail = han_tail(context);
        if tail.is_empty() {
            return Vec::new();
        }
        let blocked = self.history.adjust_for(NS_PINYIN).map(|a| &a.blocked);
        let mut scores: HashMap<String, f64> = HashMap::new();
        let mut offer = |word: String, score: f64| {
            if word.is_empty() || blocked.is_some_and(|b| b.contains(&word)) {
                return;
            }
            let e = scores.entry(word).or_insert(f64::NEG_INFINITY);
            if score > *e {
                *e = score;
            }
        };
        let chars: Vec<char> = tail.chars().collect();
        for len in (1..=PREDICT_CONTEXT.min(chars.len())).rev() {
            let ending: String = chars[chars.len() - len..].iter().collect();
            for (word, n) in self.history.followers_of(NS_PINYIN, &ending) {
                offer(word, n as f64);
            }
            let prior = self.pinyin_ngrams.unigram(&ending);
            if prior <= 0 {
                continue;
            }
            for (word, n) in self.pinyin_ngrams.successors(&ending) {
                offer(word, (n as f64 / prior as f64).ln());
            }
            for (word, n) in self.pinyin_ngrams.completions(&ending, limit) {
                let rest = word[ending.len()..].to_string();
                offer(
                    rest,
                    (n as f64 / prior.max(n) as f64).ln() + completion_cost(),
                );
            }
        }
        let mut ranked: Vec<(String, f64)> = scores.into_iter().collect();
        ranked.sort_by(|a, b| b.1.total_cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        let mut out: Vec<String> = Vec::new();
        for (word, _) in ranked.into_iter().take(limit) {
            let form = self.variant.traditional_forms(&word).swap_remove(0);
            if !out.contains(&form) {
                out.push(form);
            }
        }
        out
    }

    /// Records that `picked` was taken from [`Cjk::predictions`].
    pub fn learn_prediction(&mut self, context: &str, picked: &str) {
        let tail = han_tail(context);
        let pairs = self.history.pairs_for(NS_PINYIN);
        let previous = previous_word(&tail, |w| {
            self.pinyin_ngrams.unigram(w) > 0 || pairs.is_some_and(|p| p.contains_key(w))
        })
        .or_else(|| tail.chars().last().map(|c| c.to_string()));
        if let Some(previous) = previous {
            self.learn_pair(NS_PINYIN, &previous, picked);
        }
    }

    // --- Jyutping ----------------------------------------------------------

    fn rank_jyutping(&self, buffer: &str) -> Vec<Candidate> {
        let segs = jyutping::segment_syllables(buffer, &self.jyutping_syllables);
        if segs.is_empty() {
            return self.prefix_fallback(&self.jyutping_dict, buffer, char_len(buffer));
        }
        let lens: Vec<usize> = segs.iter().map(|s| s.input_len).collect();
        let options: Vec<Vec<String>> = segs
            .iter()
            .map(|s| {
                let mut o = vec![s.syllable.clone()];
                if self.lazy_jyutping {
                    o.extend(
                        jyutping::lazy_expand(&s.syllable, &self.jyutping_syllables)
                            .into_iter()
                            .filter(|v| *v != s.syllable)
                            .take(LAZY_VARIANTS),
                    );
                }
                o
            })
            .collect();
        let mut input = Input::options(options, &lens);
        for unit in &mut input.penalties {
            for p in unit.iter_mut().skip(1) {
                *p = lazy_penalty();
            }
        }
        let no = |_: &str| false;
        let none = |_: &str| None;
        let decoded = lattice::decode_input(
            &input,
            &self.jyutping_dict,
            &self.ngrams,
            &Opts {
                limit: LOOKUP_LIMIT,
                ..Opts::default()
            },
            &none,
            self.history.adjust_for(NS_JYUTPING),
            None,
            &UserWords {
                has_prefix: &no,
                pairs: None,
            },
        );
        let cands = self.traditional(decoded);
        rank(
            Some(&self.history),
            NS_JYUTPING,
            cands,
            |c| &c.text,
            |c| &c.reading,
            |c| &c.text,
            None,
        )
    }

    // --- Japanese ----------------------------------------------------------

    fn rank_japanese(&self, buffer: &str) -> Vec<Candidate> {
        let spans = kana::transduce(buffer);
        if spans.is_empty() {
            return Vec::new();
        }
        let whole: String = spans.iter().map(|s| s.kana.as_str()).collect();
        let romaji: usize = spans.iter().map(|s| s.romaji_len).sum();
        let mut out: Vec<Candidate> = Vec::new();
        let push = |out: &mut Vec<Candidate>, text: String, consumed: usize, reading: String| {
            if !out.iter().any(|c| c.text == text) {
                out.push(Candidate {
                    source: text.clone(),
                    text,
                    consumed,
                    reading,
                    special: false,
                });
            }
        };
        if spans.len() <= MAX_LATTICE_UNITS {
            let input = self.japanese_input(buffer, &spans);
            // A missing mark can sit on any kana of a word, so the guess cap
            // is lifted and the dictionary prune bounds the cost.
            let loose = input.options.iter().any(|o| o.len() > 1);
            let no = |_: &str| false;
            let none = |_: &str| None;
            let decoded = lattice::decode_input(
                &input,
                &self.japanese_dict,
                &self.ngrams,
                &Opts {
                    limit: LOOKUP_LIMIT,
                    char_per_unit: false,
                    max_ambiguous_span: if loose { usize::MAX } else { 2 },
                    ..Opts::default()
                },
                &none,
                self.history.adjust_for(NS_JAPANESE),
                None,
                &UserWords {
                    has_prefix: &no,
                    pairs: None,
                },
            );
            for c in decoded {
                push(&mut out, c.text, c.consumed, c.reading);
            }
        }
        // The plain kana forms of the whole buffer, always there.
        push(&mut out, whole.clone(), romaji, whole.clone());
        let kata = kana::to_katakana(&whole);
        if kata != whole {
            push(&mut out, kata.clone(), romaji, whole.clone());
        }
        let half = kana::to_half_width_katakana(&kata);
        if half != kata {
            push(&mut out, half, romaji, whole.clone());
        }
        rank(
            Some(&self.history),
            NS_JAPANESE,
            out,
            |c| &c.text,
            |c| &c.reading,
            |c| &c.text,
            None,
        )
    }

    /// One unit per transducer step; with loose marks, a plain kana typed as
    /// kana also offers its small and voiced forms.
    fn japanese_input(&self, buffer: &str, spans: &[kana::KanaSpan]) -> Input {
        let lens: Vec<usize> = spans.iter().map(|s| s.romaji_len).collect();
        if !self.loose_kana_marks {
            return Input::options(spans.iter().map(|s| vec![s.kana.clone()]).collect(), &lens);
        }
        let chars: Vec<char> = buffer.chars().collect();
        let mut options = Vec::with_capacity(spans.len());
        let mut pos = 0;
        for span in spans {
            let k: Vec<char> = span.kana.chars().collect();
            let typed_as_kana =
                span.romaji_len == 1 && k.len() == 1 && chars.get(pos) == Some(&k[0]);
            let marked = if typed_as_kana {
                kana::marked_forms(k[0])
            } else {
                ""
            };
            let mut o = vec![span.kana.clone()];
            o.extend(marked.chars().map(|c| c.to_string()));
            options.push(o);
            pos += span.romaji_len;
        }
        let mut input = Input::options(options, &lens);
        for unit in &mut input.penalties {
            for p in unit.iter_mut().skip(1) {
                *p = LOOSE_PENALTY;
            }
        }
        input
    }
}

fn special(text: String, consumed: usize, buffer: &str) -> Candidate {
    Candidate {
        source: text.clone(),
        text,
        consumed,
        reading: buffer.to_string(),
        special: true,
    }
}

/// The stroke digits a filter's letters spell: 横 h, 竖 s, 撇 p, 点 d or n, 折 z.
fn stroke_digits(letters: &str) -> String {
    letters
        .to_lowercase()
        .chars()
        .filter_map(|c| match c {
            'h' => Some('1'),
            's' => Some('2'),
            'p' => Some('3'),
            'd' | 'n' => Some('4'),
            'z' => Some('5'),
            '1'..='5' => Some(c),
            _ => None,
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn engine() -> Cjk {
        let mut c = Cjk::bundled();
        c.pinyin_dict = ConversionDictionary::parse(
            "ni\t你\t900\nni\t尼\t300\nhao\t好\t800\nhao\t号\t400\nnihao\t你好\t700\nzhong\t中\t800\nguo\t国\t700\nzhongguo\t中国\t900\nxian\t先\t500\nxian\t西安\t400\nxi\t西\t300\nan\t安\t300\nwo\t我\t990\nmen\t们\t600\nwomen\t我们\t900\n",
        );
        c.jyutping_dict =
            ConversionDictionary::parse("nei\t你\t900\nhou\t好\t800\nneihou\t你好\t700\n");
        c.japanese_dict = ConversionDictionary::parse(
            "にほん\t日本\t900\nに\t二\t500\nがっこう\t学校\t800\nかつこう\t葛洪\t790\n",
        );
        c
    }

    #[test]
    fn pinyin_whole_buffer_then_prefixes() {
        let mut c = engine();
        let s = c.strip(Method::Pinyin, "nihao");
        assert_eq!("你好", s[0].text);
        assert_eq!(5, s[0].consumed);
        let i = s.iter().position(|x| x.text == "你").unwrap();
        assert_eq!(2, s[i].consumed);
        // Jianpin.
        assert_eq!("中国", c.strip(Method::Pinyin, "zg")[0].text);
        assert_eq!("我们", c.strip(Method::Pinyin, "wm")[0].text);
        // Both splits of xian.
        let x = c.strip(Method::Pinyin, "xian");
        assert!(x.iter().any(|c| c.text == "先") && x.iter().any(|c| c.text == "西安"));
        // An unfinished last syllable.
        assert!(c.strip(Method::Pinyin, "zhongguo")[0].text == "中国");
        assert!(c
            .strip(Method::Pinyin, "zhongg")
            .iter()
            .any(|c| c.text == "中国"));
    }

    #[test]
    fn pinyin_learns_picks_and_phrases() {
        let mut c = engine();
        let s = c.strip(Method::Pinyin, "hao");
        let hao = s.iter().position(|x| x.text == "号").unwrap();
        c.learn_choice(Method::Pinyin, "hao", hao);
        assert_eq!("号", c.strip(Method::Pinyin, "hao")[0].text);
        // 你 then 号, two picks: learned as the phrase 你号.
        let s = c.strip(Method::Pinyin, "nihao");
        let ni = s.iter().position(|x| x.text == "你").unwrap();
        c.learn_choice(Method::Pinyin, "nihao", ni);
        let s = c.strip(Method::Pinyin, "hao");
        let hao = s.iter().position(|x| x.text == "号").unwrap();
        c.learn_choice(Method::Pinyin, "hao", hao);
        assert!(c.history.count_for(NS_PINYIN, "nihao", "你号") > 0);
        // Blocking takes a word out.
        c.adjust_choice(Method::Pinyin, "hao", 0, CandidateAdjustment::Block);
        assert!(!c
            .strip(Method::Pinyin, "hao")
            .iter()
            .any(|x| x.text == "号"));
    }

    #[test]
    fn pinyin_shortcuts_strokes_and_traditional() {
        let mut c = engine();
        assert_eq!("一百二十三", c.strip(Method::Pinyin, "v123")[0].text);
        c.now = Some(Now {
            year: 2026,
            month: 10,
            day: 16,
            hour: 9,
            minute: 0,
            weekday: 5,
        });
        c.invalidate();
        assert_eq!("2026年10月16日", c.strip(Method::Pinyin, "rq")[0].text);
        c.stroke = StrokeCodes::parse("3525\t你\t1\n5132\t尼\t1\n");
        let f = c.strip(Method::Pinyin, "ni`p");
        assert_eq!(
            vec!["你"],
            f.iter().map(|x| x.text.as_str()).collect::<Vec<_>>()
        );
        assert_eq!(4, f[0].consumed);
        assert_eq!("ni 丿", c.compose_buffer(Method::Pinyin, "ni`p"));
        c.variant.enabled = true;
        c.invalidate();
        assert_eq!("國", c.strip(Method::Pinyin, "guo")[0].text);
        assert_eq!("国", c.strip(Method::Pinyin, "guo")[0].source);
    }

    #[test]
    fn double_pinyin_and_context() {
        let mut c = engine();
        c.pinyin.double = pinyin::DoubleScheme::Xiaohe.table();
        c.invalidate();
        assert_eq!("nihao", c.compose_buffer(Method::Pinyin, "nihc"));
        assert_eq!("你好", c.strip(Method::Pinyin, "nihc")[0].text);
        c.committed("我们");
        assert_eq!("我们", c.context());
        c.set_context("hello 你好");
        assert_eq!("你好", c.context());
    }

    #[test]
    fn jyutping_and_japanese() {
        let mut c = engine();
        let s = c.strip(Method::Jyutping, "nei5hou2");
        assert_eq!("你好", s[0].text);
        assert_eq!(8, s[0].consumed);
        c.lazy_jyutping = true;
        c.invalidate();
        assert!(c
            .strip(Method::Jyutping, "lei")
            .iter()
            .any(|x| x.text == "你"));
        let j = c.strip(Method::Japanese, "nihon");
        assert_eq!("日本", j[0].text);
        assert!(j.iter().any(|x| x.text == "ニホン"));
        assert!(j.iter().any(|x| x.text == "ﾆﾎﾝ"));
        assert_eq!("にほん", c.compose_buffer(Method::Japanese, "nihon"));
        // Kana typed as kana, marks left off.
        c.loose_kana_marks = true;
        c.invalidate();
        assert!(c
            .strip(Method::Japanese, "かつこう")
            .iter()
            .any(|x| x.text == "学校"));
    }
}

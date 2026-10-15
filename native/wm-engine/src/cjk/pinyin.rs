//! Full and Double Pinyin as the decoder reads them: the syllable inventory
//! and the spellings derived from it (`PinyinSyllables`, `PinyinSpellings`),
//! Jianpin's abbreviations, Fuzzy Pinyin, the graph of every split worth
//! decoding (`PinyinGraph`), the typed shortcuts (`PinyinShortcuts`) and the
//! Double Pinyin schemes with fcitx's `sp.dat` format (`DoublePinyin`,
//! `DoublePinyinProfile`).

use super::lattice::{Arc, Graph};
use super::segment::{parse_inventory, segment, Seg};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};

/// The bundled inventory, one toneless syllable per line.
pub const SYLLABLES: &str =
    include_str!("../../../../app/src/main/assets/dictionaries/pinyin_syllables.txt");

/// Longest real toneless syllable (`zhuang`).
const MAX_SYLLABLE: usize = 6;

/// Splits `buffer` into leading syllables, `'` folded into the next one.
pub fn segment_syllables(buffer: &str, inventory: &HashSet<String>) -> Vec<Seg> {
    segment(buffer, inventory, MAX_SYLLABLE, &|c| c == '\'', &|_| false)
}

/// Everything the composer derives from the inventory, built once.
#[derive(Clone, Debug, Default)]
pub struct PinyinTables {
    pub valid: HashSet<String>,
    /// Another spelling of a syllable → the syllable (`lue` → lve, `bagn` → bang).
    pub aliases: HashMap<String, String>,
    /// An unfinished syllable → the syllables it can finish as.
    pub completions: HashMap<String, Vec<String>>,
    pub jianpin: JianpinIndex,
}

impl PinyinTables {
    pub fn bundled() -> PinyinTables {
        PinyinTables::new(parse_inventory(SYLLABLES))
    }

    pub fn new(valid: HashSet<String>) -> PinyinTables {
        if valid.is_empty() {
            return PinyinTables::default();
        }
        let (aliases, completions) = spellings(&valid);
        let jianpin = JianpinIndex::build(&valid);
        PinyinTables {
            valid,
            aliases,
            completions,
            jianpin,
        }
    }
}

const VOWELS: &str = "aeiouv";
const MAX_PARTIAL: usize = 5;
const MAX_COMPLETIONS: usize = 8;

fn is_jqxy(c: char) -> bool {
    matches!(c, 'j' | 'q' | 'x' | 'y')
}

fn initial_of(s: &str) -> &str {
    if s.len() >= 2 && matches!(&s[..2], "zh" | "ch" | "sh") {
        &s[..2]
    } else if s.chars().next().is_some_and(|c| !VOWELS.contains(c)) {
        &s[..1]
    } else {
        ""
    }
}

/// `PinyinSpellings.build`: aliases and completions from the inventory.
#[allow(clippy::type_complexity)]
fn spellings(valid: &HashSet<String>) -> (HashMap<String, String>, HashMap<String, Vec<String>>) {
    let mut sorted: Vec<&String> = valid.iter().collect();
    sorted.sort();
    let mut aliases: HashMap<String, String> = HashMap::new();
    let mut alias = |spelling: String, syllable: &str| {
        if spelling != syllable && !valid.contains(&spelling) && !aliases.contains_key(&spelling) {
            aliases.insert(spelling, syllable.to_string());
        }
    };
    for s in &sorted {
        let initial = initial_of(s);
        let fin = &s[initial.len()..];
        if fin.contains('v') {
            alias(format!("{initial}{}", fin.replace('v', "u")), s);
        }
        if initial.len() == 1 && initial.chars().next().is_some_and(is_jqxy) && fin.starts_with('u')
        {
            alias(format!("{initial}v{}", &fin[1..]), s);
        }
        if let Some(head) = fin.strip_suffix("iu") {
            alias(format!("{initial}{head}iou"), s);
        }
        if fin == "ui" {
            alias(format!("{initial}uei"), s);
        }
        if fin == "un" && initial.chars().next().is_some_and(|c| !is_jqxy(c)) {
            alias(format!("{initial}uen"), s);
        }
        if fin.ends_with("ng") {
            alias(format!("{}gn", &s[..s.len() - 2]), s);
        }
        if fin.ends_with("ong") {
            alias(s[..s.len() - 1].to_string(), s);
        }
    }
    let mut completions: HashMap<String, Vec<String>> = HashMap::new();
    for s in &sorted {
        for len in 2..=MAX_PARTIAL.min(s.len().saturating_sub(1)) {
            let prefix = &s[..len];
            if valid.contains(prefix)
                || aliases.contains_key(prefix)
                || !prefix.chars().any(|c| VOWELS.contains(c))
            {
                continue;
            }
            let list = completions.entry(prefix.to_string()).or_default();
            if list.len() < MAX_COMPLETIONS {
                list.push(s.to_string());
            }
        }
    }
    (aliases, completions)
}

/// The digraph initials: expansions of their own, never segmenter units.
pub const DIGRAPHS: [&str; 3] = ["zh", "ch", "sh"];

/// Jianpin (简拼): a bare initial as a unit, standing for every syllable it
/// starts (`z` covers `zh…` too).
#[derive(Clone, Debug, Default)]
pub struct JianpinIndex {
    pub expansions: HashMap<String, Vec<String>>,
    /// Every full syllable plus every single-letter abbreviation.
    pub units: HashSet<String>,
}

impl JianpinIndex {
    pub fn build(inventory: &HashSet<String>) -> JianpinIndex {
        let mut syllables: Vec<String> = inventory
            .iter()
            .map(|s| s.to_lowercase())
            .filter(|s| !s.is_empty())
            .collect();
        syllables.sort();
        let mut letters: Vec<String> = Vec::new();
        for s in &syllables {
            let l = s[..1].to_string();
            if !letters.contains(&l) {
                letters.push(l);
            }
        }
        let mut keys = letters.clone();
        for d in DIGRAPHS {
            if syllables.iter().any(|s| s.starts_with(d)) {
                keys.push(d.to_string());
            }
        }
        let expansions = keys
            .into_iter()
            .map(|k| {
                let list = syllables
                    .iter()
                    .filter(|s| s.starts_with(&k))
                    .cloned()
                    .collect();
                (k, list)
            })
            .collect();
        let mut units: HashSet<String> = syllables.into_iter().collect();
        units.extend(letters);
        JianpinIndex { expansions, units }
    }

    pub fn is_empty(&self) -> bool {
        self.units.is_empty()
    }
}

/// Fuzzy Pinyin's groups, in the settings screen's order: `(id, a, b, initial)`.
/// The ids are persisted.
pub const FUZZY_PAIRS: [(&str, &str, &str, bool); 11] = [
    ("zh-z", "zh", "z", true),
    ("ch-c", "ch", "c", true),
    ("sh-s", "sh", "s", true),
    ("n-l", "n", "l", true),
    ("r-l", "r", "l", true),
    ("f-h", "f", "h", true),
    ("an-ang", "an", "ang", false),
    ("en-eng", "en", "eng", false),
    ("in-ing", "in", "ing", false),
    ("ian-iang", "ian", "iang", false),
    ("uan-uang", "uan", "uang", false),
];

/// Every group: what "Fuzzy Pinyin is on" means with nothing else chosen.
pub fn all_fuzzy_pairs() -> HashSet<String> {
    FUZZY_PAIRS.iter().map(|p| p.0.to_string()).collect()
}

const FUZZY_INITIALS: [&str; 23] = [
    "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "r",
    "z", "c", "s", "y", "w",
];

fn push_unique(out: &mut Vec<String>, s: String) {
    if !out.contains(&s) {
        out.push(s);
    }
}

fn fuzzy_alts(part: &str, initial: bool, enabled: &HashSet<String>) -> Vec<String> {
    let mut out = vec![part.to_string()];
    for (id, a, b, is_initial) in FUZZY_PAIRS {
        if is_initial != initial || !enabled.contains(id) {
            continue;
        }
        if part == a || part == b {
            push_unique(&mut out, a.to_string());
            push_unique(&mut out, b.to_string());
        }
    }
    out
}

/// Every valid fuzzy variant of `syllable`, itself first.
pub fn fuzzy_expand(
    syllable: &str,
    valid: &HashSet<String>,
    enabled: &HashSet<String>,
) -> Vec<String> {
    if syllable.is_empty() {
        return Vec::new();
    }
    let initial = FUZZY_INITIALS
        .iter()
        .find(|i| syllable.starts_with(*i))
        .copied()
        .unwrap_or("");
    let fin = &syllable[initial.len()..];
    let mut out = vec![syllable.to_string()];
    for i in fuzzy_alts(initial, true, enabled) {
        for f in fuzzy_alts(fin, false, enabled) {
            let cand = format!("{i}{f}");
            if cand == syllable || valid.contains(&cand) {
                push_unique(&mut out, cand);
            }
        }
    }
    out
}

/// What a span of the buffer is, for the composer turning it into arcs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Unit,
    Digraph,
    Typo,
    Swap,
    Extra,
    Alias,
    Partial,
}

/// The graph, the cheapest split's unit count, and whether that split
/// abbreviates anything.
pub struct Built {
    pub graph: Graph,
    pub units: usize,
    pub abbreviated: bool,
}

const MIN_TYPO: usize = 3;
const TYPO_ABBREVIATIONS: usize = 2;
const INF: usize = usize::MAX / 4;
const INITIAL: usize = 1 << 16;

#[derive(Clone)]
struct Span {
    from: usize,
    start: usize,
    to: usize,
    unit: String,
    cost: usize,
}

/// Letters a finger lands on instead, on QWERTY.
fn neighbours(c: char) -> &'static str {
    match c {
        'q' => "wa",
        'w' => "qeas",
        'e' => "wrsd",
        'r' => "etdf",
        't' => "ryfg",
        'y' => "tugh",
        'u' => "yihj",
        'i' => "uojk",
        'o' => "ipkl",
        'p' => "ol",
        'a' => "qwsz",
        's' => "adwezx",
        'd' => "sferxc",
        'f' => "dgrtcv",
        'g' => "fhtyvb",
        'h' => "gjyubn",
        'j' => "hkuinm",
        'k' => "jliom",
        'l' => "kop",
        'z' => "asx",
        'x' => "zcsd",
        'c' => "xvdf",
        'v' => "cbfg",
        'b' => "vngh",
        'n' => "bmhj",
        'm' => "njk",
        _ => "",
    }
}

/// Full pinyin as a graph: every split with as few abbreviations as the
/// buffer allows, then as few syllables, plus the apostrophe splits, the
/// unfinished last syllable, digraphs over two initials and one-key repairs.
/// `arcs_for` turns one span into arcs landing on node `to`.
pub fn build_graph(
    buffer: &str,
    tables: &PinyinTables,
    jianpin: Option<&JianpinIndex>,
    arcs_for: &dyn Fn(&str, usize, Kind) -> Vec<Arc>,
) -> Option<Built> {
    let valid = &tables.valid;
    if buffer.is_empty() || valid.is_empty() {
        return None;
    }
    let s: Vec<char> = buffer.to_lowercase().chars().collect();
    let n = s.len();
    let sub = |a: usize, b: usize| -> String { s[a..b].iter().collect() };
    let units = jianpin
        .map(|j| &j.units)
        .filter(|u| !u.is_empty())
        .unwrap_or(valid);
    let aliases = &tables.aliases;

    let from: Vec<Vec<Span>> = (0..=n)
        .map(|i| {
            let mut start = i;
            while start < n && s[start] == '\'' {
                start += 1;
            }
            let mut out = Vec::new();
            for len in (1..=MAX_SYLLABLE.min(n - start)).rev() {
                let unit = sub(start, start + len);
                if units.contains(&unit) {
                    let cost = if valid.contains(&unit) {
                        1
                    } else {
                        INITIAL + 1
                    };
                    out.push(Span {
                        from: i,
                        start,
                        to: start + len,
                        unit,
                        cost,
                    });
                } else if aliases.contains_key(&unit) {
                    out.push(Span {
                        from: i,
                        start,
                        to: start + len,
                        unit,
                        cost: 1,
                    });
                }
            }
            out
        })
        .collect();

    let mut best = vec![INF; n + 1];
    best[0] = 0;
    for i in 0..n {
        if best[i] == INF {
            continue;
        }
        for sp in &from[i] {
            best[sp.to] = best[sp.to].min(best[i] + sp.cost);
        }
    }
    let mut end = n;
    while end > 0 && best[end] == INF {
        end -= 1;
    }
    if end == 0 {
        return None;
    }
    let mut to_end = vec![INF; end + 1];
    to_end[end] = 0;
    for i in (0..end).rev() {
        for sp in &from[i] {
            if sp.to <= end && to_end[sp.to] < INF {
                to_end[i] = to_end[i].min(sp.cost + to_end[sp.to]);
            }
        }
    }
    let total = to_end[0];
    let mut kept: Vec<Span> = Vec::new();
    for i in 0..end {
        if best[i] == INF {
            continue;
        }
        for sp in &from[i] {
            if sp.to <= end && to_end[sp.to] < INF && best[i] + sp.cost + to_end[sp.to] == total {
                kept.push(sp.clone());
            }
        }
    }
    if kept.is_empty() {
        return None;
    }

    // A syllable that also reads as two, split where an apostrophe would go.
    let mut splits = Vec::new();
    for sp in &kept {
        if sp.cost != 1 || sp.to - sp.start < 2 || !valid.contains(&sp.unit) {
            continue;
        }
        for k in sp.start + 1..sp.to {
            let head = sub(sp.start, k);
            let tail = sub(k, sp.to);
            if tail.starts_with(['a', 'e']) && valid.contains(&head) && valid.contains(&tail) {
                splits.push(Span {
                    from: sp.from,
                    start: sp.start,
                    to: k,
                    unit: head,
                    cost: 1,
                });
                splits.push(Span {
                    from: k,
                    start: k,
                    to: sp.to,
                    unit: tail,
                    cost: 1,
                });
            }
        }
    }
    kept.extend(splits);

    // The last syllable, still being typed.
    let mut partial_from: Vec<usize> = Vec::new();
    for p in kept.iter().map(|sp| sp.from).chain(std::iter::once(end)) {
        if !partial_from.contains(&p) {
            partial_from.push(p);
        }
    }
    let mut partials = Vec::new();
    for i in partial_from {
        if best[i] == INF {
            continue;
        }
        let mut start = i;
        while start < n && s[start] == '\'' {
            start += 1;
        }
        let tail = sub(start, n);
        if tail.chars().count() >= 2
            && !tail.contains('\'')
            && tables.completions.contains_key(&tail)
        {
            partials.push(Span {
                from: i,
                start,
                to: n,
                unit: tail,
                cost: INITIAL + 1,
            });
        }
    }

    let mut positions: BTreeSet<usize> = BTreeSet::new();
    positions.insert(0);
    positions.insert(end);
    for sp in &kept {
        positions.insert(sp.from);
        positions.insert(sp.to);
    }
    if !partials.is_empty() {
        positions.insert(n);
    }
    let nodes: Vec<usize> = positions.into_iter().collect();
    let index: HashMap<usize, usize> = nodes.iter().enumerate().map(|(k, &p)| (p, k)).collect();
    let mut arcs: Vec<Vec<Arc>> = vec![Vec::new(); nodes.len()];

    for sp in &kept {
        let (kind, unit) = if units.contains(&sp.unit) {
            (Kind::Unit, sp.unit.as_str())
        } else {
            (Kind::Alias, aliases[&sp.unit].as_str())
        };
        arcs[index[&sp.from]].extend(arcs_for(unit, index[&sp.to], kind));
    }
    for sp in &partials {
        for syllable in &tables.completions[&sp.unit] {
            arcs[index[&sp.from]].extend(arcs_for(syllable, index[&n], Kind::Partial));
        }
    }

    // `z` then `h`, both bare and back to back, also read as zh….
    if jianpin.is_some() {
        let single: Vec<&Span> = kept
            .iter()
            .filter(|sp| sp.to - sp.start == 1 && sp.cost > INITIAL)
            .collect();
        let starting_at: HashMap<usize, &Span> = single.iter().map(|sp| (sp.start, *sp)).collect();
        for a in &single {
            let Some(b) = starting_at.get(&a.to) else {
                continue;
            };
            if b.from != b.start {
                continue;
            }
            let digraph = format!("{}{}", a.unit, b.unit);
            if !DIGRAPHS.contains(&digraph.as_str()) {
                continue;
            }
            arcs[index[&a.from]].extend(arcs_for(&digraph, index[&b.to], Kind::Digraph));
        }
    }

    // A stretch needing two or more bare initials that one slip of a finger
    // would make a whole syllable of.
    for &i in &nodes {
        for &j in &nodes {
            if j <= i || j > end || best[i] == INF || best[j] == INF {
                continue;
            }
            if (best[j] - best[i]) / INITIAL < TYPO_ABBREVIATIONS {
                continue;
            }
            let mut start = i;
            while start < j && s[start] == '\'' {
                start += 1;
            }
            let window: Vec<char> = s[start..j].to_vec();
            let w: String = window.iter().collect();
            if !(MIN_TYPO..=MAX_SYLLABLE + 1).contains(&window.len()) || window.contains(&'\'') {
                continue;
            }
            if valid.contains(&w) || aliases.contains_key(&w) {
                continue;
            }
            let to = index[&j];
            let mut seen: HashSet<String> = HashSet::new();
            let mut offer = |fixed: String, kind: Kind, arcs: &mut Vec<Vec<Arc>>| {
                if valid.contains(&fixed) && seen.insert(fixed.clone()) {
                    arcs[index[&i]].extend(arcs_for(&fixed, to, kind));
                }
            };
            let join =
                |parts: &[&[char]]| -> String { parts.iter().flat_map(|p| p.iter()).collect() };
            for p in 0..window.len() {
                for c in neighbours(window[p]).chars() {
                    offer(
                        join(&[&window[..p], &[c], &window[p + 1..]]),
                        Kind::Typo,
                        &mut arcs,
                    );
                }
            }
            for p in 0..window.len() - 1 {
                offer(
                    join(&[&window[..p], &[window[p + 1], window[p]], &window[p + 2..]]),
                    Kind::Swap,
                    &mut arcs,
                );
            }
            for p in 0..window.len() {
                offer(
                    join(&[&window[..p], &window[p + 1..]]),
                    Kind::Extra,
                    &mut arcs,
                );
            }
            if window.len() < MAX_SYLLABLE {
                for p in 1..=window.len() {
                    for c in 'a'..='z' {
                        offer(
                            join(&[&window[..p], &[c], &window[p..]]),
                            Kind::Extra,
                            &mut arcs,
                        );
                    }
                }
            }
        }
    }

    Some(Built {
        graph: Graph {
            consumed: nodes,
            arcs,
        },
        units: total % INITIAL,
        abbreviated: total >= INITIAL,
    })
}

// --- Shortcuts: rq/sj/xq and `v` numbers ------------------------------------

const LOWER_DIGITS: [char; 10] = ['零', '一', '二', '三', '四', '五', '六', '七', '八', '九'];
const UPPER_DIGITS: [char; 10] = ['零', '壹', '贰', '叁', '肆', '伍', '陆', '柒', '捌', '玖'];
const SPOKEN_DIGITS: [char; 10] = ['〇', '一', '二', '三', '四', '五', '六', '七', '八', '九'];
const LOWER_UNITS: [&str; 4] = ["", "十", "百", "千"];
const UPPER_UNITS: [&str; 4] = ["", "拾", "佰", "仟"];
const GROUPS: [&str; 4] = ["", "万", "亿", "万亿"];
const WEEKDAYS: [&str; 7] = ["日", "一", "二", "三", "四", "五", "六"];
const MAX_DIGITS: usize = 16;

/// The local date and time `rq`, `sj` and `xq` answer with.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Now {
    pub year: i32,
    pub month: u32,
    pub day: u32,
    pub hour: u32,
    pub minute: u32,
    /// 0 for Sunday.
    pub weekday: u32,
}

/// Whether `buffer` is a `v` number, or a `v` waiting for its digits.
pub fn is_number(buffer: &str) -> bool {
    let b: Vec<char> = buffer.chars().collect();
    b.len() >= 2
        && b[0] == 'v'
        && b[1..].iter().all(|c| c.is_ascii_digit() || *c == '.')
        && b.iter().filter(|c| **c == '.').count() <= 1
        && b[1] != '.'
}

/// Whether `c` goes on into `buffer` as part of a `v` number.
pub fn continues_number(buffer: &str, c: char) -> bool {
    if !buffer.starts_with('v') || !buffer[1..].chars().all(|c| c.is_ascii_digit() || c == '.') {
        return false;
    }
    c.is_ascii_digit() || c == '.' && buffer.len() >= 2 && !buffer.contains('.')
}

fn digit(c: char) -> usize {
    c as usize - '0' as usize
}

/// The numerals for an [`is_number`] buffer.
pub fn numbers(buffer: &str) -> Vec<String> {
    if !is_number(buffer) {
        return Vec::new();
    }
    let number = buffer[1..].trim_end_matches('.');
    let (integer, fraction) = number.split_once('.').unwrap_or((number, ""));
    if integer.len() > MAX_DIGITS {
        return vec![number.to_string()];
    }
    let with_fraction = |whole: String, digits: &[char; 10]| {
        if fraction.is_empty() {
            whole
        } else {
            whole
                + "点"
                + &fraction
                    .chars()
                    .map(|c| digits[digit(c)])
                    .collect::<String>()
        }
    };
    let mut out = vec![
        with_fraction(lower(integer), &LOWER_DIGITS),
        with_fraction(spell(integer, &UPPER_DIGITS, &UPPER_UNITS), &UPPER_DIGITS),
        number
            .chars()
            .map(|c| {
                if c == '.' {
                    '点'
                } else {
                    SPOKEN_DIGITS[digit(c)]
                }
            })
            .collect(),
    ];
    if fraction.is_empty() {
        out.push(spell(integer, &UPPER_DIGITS, &UPPER_UNITS) + "元整");
    }
    out.push(number.to_string());
    let mut distinct = Vec::new();
    for s in out {
        push_unique(&mut distinct, s);
    }
    distinct
}

/// The date, time or weekday a two-letter buffer asks for.
pub fn expand_shortcut(buffer: &str, now: Now) -> Vec<String> {
    let Now {
        year,
        month,
        day,
        hour,
        minute,
        weekday,
    } = now;
    let weekday = WEEKDAYS[(weekday % 7) as usize];
    match buffer {
        "rq" => vec![
            format!("{year}年{month}月{day}日"),
            year.to_string()
                .chars()
                .map(|c| SPOKEN_DIGITS[digit(c)])
                .collect::<String>()
                + "年"
                + &lower(&month.to_string())
                + "月"
                + &lower(&day.to_string())
                + "日",
            format!("{year:04}-{month:02}-{day:02}"),
            format!("{month}月{day}日"),
        ],
        "sj" => {
            let half = match hour {
                0..=5 => "凌晨",
                6..=11 => "上午",
                12 => "中午",
                13..=17 => "下午",
                _ => "晚上",
            };
            let twelve = if hour % 12 == 0 { 12 } else { hour % 12 };
            vec![
                format!("{hour:02}:{minute:02}"),
                format!("{hour}点{minute}分"),
                format!("{half}{twelve}点{minute}分"),
            ]
        }
        "xq" => vec![
            format!("星期{weekday}"),
            format!("周{weekday}"),
            format!("礼拜{weekday}"),
        ],
        _ => Vec::new(),
    }
}

/// `integer` in everyday numerals: 十二, not 一十二.
pub fn lower(integer: &str) -> String {
    let spelled = spell(integer, &LOWER_DIGITS, &LOWER_UNITS);
    match spelled.strip_prefix('一') {
        Some(rest) if rest.starts_with('十') => rest.to_string(),
        _ => spelled,
    }
}

fn spell(integer: &str, digits: &[char; 10], units: &[&str; 4]) -> String {
    let n = integer.trim_start_matches('0');
    if n.is_empty() {
        return digits[0].to_string();
    }
    let rev: Vec<char> = n.chars().rev().collect();
    let groups: Vec<Vec<char>> = rev
        .chunks(4)
        .map(|c| {
            let mut g: Vec<char> = c.iter().rev().copied().collect();
            while g.len() < 4 {
                g.insert(0, '0');
            }
            g
        })
        .collect();
    let mut out = String::new();
    let mut gap = false;
    for gi in (0..groups.len()).rev() {
        let g = &groups[gi];
        if g.iter().all(|c| *c == '0') {
            if !out.is_empty() {
                gap = true;
            }
            continue;
        }
        if !out.is_empty() && (gap || g[0] == '0') {
            out.push(digits[0]);
        }
        gap = false;
        let (mut started, mut zero) = (false, false);
        for (k, &c) in g.iter().enumerate() {
            let d = digit(c);
            if d == 0 {
                if started {
                    zero = true;
                }
                continue;
            }
            if zero {
                out.push(digits[0]);
            }
            zero = false;
            started = true;
            out.push(digits[d]);
            out.push_str(units[3 - k]);
        }
        out.push_str(GROUPS[gi]);
    }
    out
}

// --- Double Pinyin -----------------------------------------------------------

/// A scheme's key maps: first key → initial, final key → candidate finals,
/// the keys that lead a zero-initial syllable, and whole-syllable codes.
#[derive(Clone, Debug, PartialEq)]
pub struct DoubleTable {
    pub initials: BTreeMap<char, String>,
    pub finals: BTreeMap<char, Vec<String>>,
    pub zero_leads: BTreeSet<char>,
    pub codes: BTreeMap<String, String>,
}

/// The shipped schemes, by the phone's persisted names.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DoubleScheme {
    Off,
    Microsoft,
    Sogou,
    Xiaohe,
    Ziranma,
    PinyinPP,
    Custom,
}

impl DoubleScheme {
    pub fn parse(name: &str) -> DoubleScheme {
        match name {
            "MICROSOFT" => DoubleScheme::Microsoft,
            "SOGOU" => DoubleScheme::Sogou,
            "XIAOHE" => DoubleScheme::Xiaohe,
            "ZIRANMA" => DoubleScheme::Ziranma,
            "PINYINPP" => DoubleScheme::PinyinPP,
            "CUSTOM" => DoubleScheme::Custom,
            _ => DoubleScheme::Off,
        }
    }

    /// The scheme's table; `Custom` and `Off` have none here.
    pub fn table(self) -> Option<DoubleTable> {
        let finals = match self {
            DoubleScheme::Xiaohe => XIAOHE,
            DoubleScheme::Microsoft | DoubleScheme::Sogou => MICROSOFT,
            DoubleScheme::Ziranma => ZIRANMA,
            DoubleScheme::PinyinPP => PINYINPP,
            DoubleScheme::Off | DoubleScheme::Custom => return None,
        };
        let (zh, ch, sh) = if self == DoubleScheme::PinyinPP {
            ('v', 'u', 'i')
        } else {
            ('v', 'i', 'u')
        };
        let mut initials: BTreeMap<char, String> = "bpmfdtnlgkhjqxrzcsyw"
            .chars()
            .map(|c| (c, c.to_string()))
            .collect();
        initials.insert(zh, "zh".into());
        initials.insert(ch, "ch".into());
        initials.insert(sh, "sh".into());
        Some(DoubleTable {
            initials,
            finals: finals
                .iter()
                .map(|(k, v)| (*k, v.split(' ').map(str::to_string).collect()))
                .collect(),
            zero_leads: default_zero_leads(),
            codes: BTreeMap::new(),
        })
    }
}

pub fn default_zero_leads() -> BTreeSet<char> {
    ['a', 'e', 'o'].into_iter().collect()
}

type Finals = [(char, &'static str); 26];

const XIAOHE: Finals = [
    ('a', "a"),
    ('b', "in"),
    ('c', "ao"),
    ('d', "ai"),
    ('e', "e"),
    ('f', "en"),
    ('g', "eng"),
    ('h', "ang"),
    ('i', "i"),
    ('j', "an"),
    ('k', "ing uai"),
    ('l', "iang uang"),
    ('m', "ian"),
    ('n', "iao"),
    ('o', "uo o"),
    ('p', "ie"),
    ('q', "iu"),
    ('r', "uan er"),
    ('s', "ong iong"),
    ('t', "ue ve"),
    ('u', "u"),
    ('v', "ui v"),
    ('w', "ei"),
    ('x', "ia ua"),
    ('y', "un"),
    ('z', "ou"),
];

const MICROSOFT: Finals = [
    ('a', "a"),
    ('b', "ou"),
    ('c', "iao"),
    ('d', "iang uang"),
    ('e', "e"),
    ('f', "en"),
    ('g', "eng"),
    ('h', "ang"),
    ('i', "i"),
    ('j', "an"),
    ('k', "ao"),
    ('l', "ai"),
    ('m', "ian"),
    ('n', "in"),
    ('o', "uo o"),
    ('p', "un"),
    ('q', "iu"),
    ('r', "uan er"),
    ('s', "ong iong"),
    ('t', "ue ve"),
    ('u', "u"),
    ('v', "ui v"),
    ('w', "ia ua"),
    ('x', "ie"),
    ('y', "uai ing"),
    ('z', "ei"),
];

const ZIRANMA: Finals = MICROSOFT;

const PINYINPP: Finals = [
    ('a', "a"),
    ('b', "ia ua"),
    ('c', "uan"),
    ('d', "ao"),
    ('e', "e"),
    ('f', "an"),
    ('g', "ang"),
    ('h', "iang uang"),
    ('i', "i"),
    ('j', "ian"),
    ('k', "iao"),
    ('l', "in"),
    ('m', "ie"),
    ('n', "iu"),
    ('o', "uo o"),
    ('p', "ou"),
    ('q', "ing"),
    ('r', "en"),
    ('t', "eng"),
    ('u', "u"),
    ('v', "ui v"),
    ('w', "ei"),
    ('x', "uai ue ve"),
    ('y', "ong iong"),
    ('z', "un"),
    ('s', "ai"),
];

fn skip_separators(s: &[char], from: usize) -> usize {
    let mut i = from;
    while i < s.len() && s[i] == '\'' {
        i += 1;
    }
    i
}

fn syllable_for(c1: char, c2: char, table: &DoubleTable, valid: &HashSet<String>) -> String {
    if let Some(code) = table.codes.get(&format!("{c1}{c2}")) {
        return code.clone();
    }
    let own = [c2.to_string()];
    let finals: &[String] = table.finals.get(&c2).map(Vec::as_slice).unwrap_or(&own);
    let mut cands: Vec<String> = Vec::new();
    if let Some(init) = table.initials.get(&c1) {
        for f in finals {
            push_unique(&mut cands, format!("{init}{f}"));
        }
    }
    if table.zero_leads.contains(&c1) {
        for f in finals {
            push_unique(&mut cands, f.clone());
        }
    }
    if let Some(v) = cands.into_iter().find(|c| valid.contains(c)) {
        return v;
    }
    let init = table
        .initials
        .get(&c1)
        .cloned()
        .unwrap_or_else(|| c1.to_string());
    init + finals
        .first()
        .map(String::as_str)
        .unwrap_or(&c2.to_string())
}

/// The syllables of a key buffer, two keys each; a dangling key is left.
pub fn double_segments(buffer: &str, table: &DoubleTable, valid: &HashSet<String>) -> Vec<Seg> {
    let s: Vec<char> = buffer.to_lowercase().chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < s.len() {
        let a = skip_separators(&s, i);
        if a + 1 >= s.len() {
            break;
        }
        out.push(Seg {
            syllable: syllable_for(s[a], s[a + 1], table, valid),
            input_len: a + 2 - i,
        });
        i = a + 2;
    }
    out
}

/// Full pinyin for a key buffer, for the composing region.
pub fn double_translate(buffer: &str, table: &DoubleTable, valid: &HashSet<String>) -> String {
    let s: Vec<char> = buffer.to_lowercase().chars().collect();
    let mut out = String::new();
    let mut i = 0;
    while i < s.len() {
        let a = skip_separators(&s, i);
        if a + 1 >= s.len() {
            i = a;
            break;
        }
        out.push_str(&syllable_for(s[a], s[a + 1], table, valid));
        i = a + 2;
    }
    if i < s.len() {
        out.push(s[i]);
    }
    out
}

// --- The user's own scheme, as fcitx's sp.dat --------------------------------

/// What is wrong with one line, which the scheme then skips.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Problem {
    NotPinyin,
    NotALetter,
    NotAMapping,
}

#[derive(Clone, Debug, PartialEq)]
pub struct LineProblem {
    pub line: usize,
    pub problem: Problem,
    pub text: String,
}

#[derive(Clone, Debug)]
pub struct ParsedScheme {
    pub name: Option<String>,
    pub table: DoubleTable,
    pub problems: Vec<LineProblem>,
    pub unmapped: Vec<String>,
}

pub const NAME_PREFIX: &str = "方案名称=";

pub const PROFILE_INITIALS: [&str; 23] = [
    "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "zh", "ch", "sh", "r",
    "z", "c", "s", "y", "w",
];

pub const PROFILE_FINALS: [&str; 34] = [
    "a", "o", "e", "i", "u", "v", "ai", "ei", "ui", "ao", "ou", "iu", "ie", "ue", "ve", "er", "an",
    "en", "in", "un", "ang", "eng", "ing", "ong", "ia", "iao", "ian", "iang", "iong", "ua", "uo",
    "uai", "uan", "uang",
];

const ZERO_INITIAL: [&str; 12] = [
    "a", "o", "e", "ai", "ei", "ao", "ou", "er", "an", "en", "ang", "eng",
];

/// The three comment lines every scheme in the editor opens with.
pub const HEADER: &str = "# Double Pinyin scheme, in fcitx's sp.dat format.\n\
# pinyin=key places an initial or a final; pinyin=two keys spells a whole syllable.\n\
# =keys names the keys that start a syllable with no initial.\n";

fn is_final(s: &str) -> bool {
    PROFILE_FINALS.contains(&s)
}

fn is_initial(s: &str) -> bool {
    PROFILE_INITIALS.contains(&s)
}

fn is_syllable_shape(p: &str) -> bool {
    if is_final(p) {
        return true;
    }
    (1..=2).rev().any(|cut| {
        p.len() > cut && p.is_char_boundary(cut) && is_initial(&p[..cut]) && is_final(&p[cut..])
    })
}

/// Reads a scheme in fcitx's `sp.dat` format; only the first scheme of a
/// multi-scheme file.
pub fn parse_scheme(text: &str) -> ParsedScheme {
    let mut name: Option<String> = None;
    let mut initials: BTreeMap<char, String> = PROFILE_INITIALS
        .iter()
        .filter(|i| i.len() == 1)
        .map(|i| (i.chars().next().unwrap(), i.to_string()))
        .collect();
    let mut finals: BTreeMap<char, Vec<String>> = BTreeMap::new();
    let mut codes: BTreeMap<String, String> = BTreeMap::new();
    let mut leads: Option<BTreeSet<char>> = None;
    let mut star = false;
    let mut problems = Vec::new();
    // fcitx's sp.dat lines in file order of the finals, for `*`.
    let mut final_order: Vec<char> = Vec::new();

    for (index, raw) in text.lines().enumerate() {
        let line = raw.trim().trim_start_matches('\u{feff}');
        if line.is_empty() || line.starts_with('#') || line.starts_with('[') {
            continue;
        }
        if let Some(rest) = line.strip_prefix(NAME_PREFIX) {
            if name.is_some() {
                break;
            }
            name = Some(rest.trim().to_string()).filter(|n| !n.is_empty());
            continue;
        }
        let problem = |p: Problem| LineProblem {
            line: index + 1,
            problem: p,
            text: line.to_string(),
        };
        let Some((pinyin, keys)) = line.split_once('=') else {
            problems.push(problem(Problem::NotAMapping));
            continue;
        };
        let pinyin = pinyin.trim().to_lowercase().replace('ü', "v");
        let keys = keys.trim().to_lowercase();
        if pinyin.is_empty() {
            let set = leads.get_or_insert_with(BTreeSet::new);
            for c in keys.chars() {
                if c == '*' {
                    star = true;
                } else if c.is_ascii_lowercase() {
                    set.insert(c);
                } else if !c.is_whitespace() {
                    problems.push(problem(Problem::NotALetter));
                    break;
                }
            }
            continue;
        }
        let kc: Vec<char> = keys.chars().collect();
        if kc.is_empty() || kc.len() > 2 {
            problems.push(problem(Problem::NotAMapping));
            continue;
        }
        if kc.iter().any(|c| !c.is_ascii_lowercase()) {
            problems.push(problem(Problem::NotALetter));
            continue;
        }
        if kc.len() == 2 {
            if is_syllable_shape(&pinyin) {
                codes.insert(keys, pinyin);
            } else {
                problems.push(problem(Problem::NotPinyin));
            }
            continue;
        }
        let key = kc[0];
        if is_final(&pinyin) {
            if !final_order.contains(&key) {
                final_order.push(key);
            }
            let list = finals.entry(key).or_default();
            let spellings: Vec<&str> = if pinyin == "ue" || pinyin == "ve" {
                vec!["ue", "ve"]
            } else {
                vec![pinyin.as_str()]
            };
            for sp in spellings {
                if !list.iter().any(|f| f == sp) {
                    list.push(sp.to_string());
                }
            }
        } else if is_initial(&pinyin) {
            initials.insert(key, pinyin);
        } else {
            problems.push(problem(Problem::NotPinyin));
        }
    }

    if star {
        for fin in ZERO_INITIAL {
            let code = match fin.len() {
                1 => Some(format!("{fin}{fin}")),
                2 => Some(fin.to_string()),
                _ => final_order
                    .iter()
                    .find(|k| finals[k].iter().any(|f| f == fin))
                    .map(|k| format!("{}{k}", &fin[..1])),
            };
            if let Some(code) = code {
                codes.entry(code).or_insert_with(|| fin.to_string());
            }
        }
    }
    let table = DoubleTable {
        initials,
        finals,
        zero_leads: leads.unwrap_or_else(|| {
            if star {
                BTreeSet::new()
            } else {
                default_zero_leads()
            }
        }),
        codes,
    };
    let unmapped = unmapped(&table);
    ParsedScheme {
        name,
        table,
        problems,
        unmapped,
    }
}

/// `table` as text [`parse_scheme`] reads back to the same table.
pub fn write_scheme(table: &DoubleTable, name: &str) -> String {
    let mut out = String::from(HEADER);
    out.push_str(NAME_PREFIX);
    out.push_str(name);
    out.push('\n');
    out.push_str("[声母]\n");
    let mut initials: Vec<(&char, &String)> = table.initials.iter().collect();
    initials.sort_by_key(|(_, v)| {
        PROFILE_INITIALS
            .iter()
            .position(|i| i == v)
            .map(|p| p as i64)
            .unwrap_or(-1)
    });
    for (key, initial) in initials {
        if initial.len() == 1 && initial.starts_with(*key) {
            continue;
        }
        out.push_str(&format!("{initial}={key}\n"));
    }
    out.push_str("[韵母]\n");
    for (key, list) in &table.finals {
        for fin in list {
            if fin == "ve" && list.iter().any(|f| f == "ue") {
                continue;
            }
            out.push_str(&format!("{fin}={key}\n"));
        }
    }
    out.push_str("[零声母]\n=");
    out.extend(table.zero_leads.iter());
    out.push('\n');
    for (code, syllable) in &table.codes {
        out.push_str(&format!("{syllable}={code}\n"));
    }
    out
}

/// `text` opening with [`HEADER`].
pub fn with_header(text: &str) -> String {
    if text.starts_with(HEADER) {
        text.to_string()
    } else {
        format!("{HEADER}{text}")
    }
}

/// Whether `text` holds nothing but comments and blank lines.
pub fn scheme_is_empty(text: &str) -> bool {
    text.lines()
        .all(|l| l.trim().is_empty() || l.trim_start().starts_with('#'))
}

/// What no key of `table` types: initials first, then finals.
pub fn unmapped(table: &DoubleTable) -> Vec<String> {
    let initials: HashSet<&String> = table.initials.values().collect();
    let mut finals: HashSet<String> = table.finals.values().flatten().cloned().collect();
    for c in 'a'..='z' {
        if !table.finals.contains_key(&c) {
            finals.insert(c.to_string());
        }
    }
    let mut out = Vec::new();
    for i in PROFILE_INITIALS {
        if !initials.contains(&i.to_string()) {
            out.push(i.to_string());
        }
    }
    for f in PROFILE_FINALS {
        if f == "ve" {
            continue;
        }
        if !finals.contains(f) && !(f == "ue" && finals.contains("ve")) {
            out.push(f.to_string());
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spellings_jianpin_and_fuzzy() {
        let t = PinyinTables::bundled();
        assert_eq!(Some(&"lve".to_string()), t.aliases.get("lue"));
        assert_eq!(Some(&"bang".to_string()), t.aliases.get("bagn"));
        assert!(t.completions["qio"].contains(&"qiong".to_string()));
        assert!(t.jianpin.expansions["z"].contains(&"zhong".to_string()));
        let all = all_fuzzy_pairs();
        let f = fuzzy_expand("si", &t.valid, &all);
        assert_eq!("si", f[0]);
        assert!(f.contains(&"shi".to_string()));
    }

    #[test]
    fn graph_keeps_both_splits() {
        let t = PinyinTables::bundled();
        let arcs = |u: &str, to: usize, _: Kind| vec![Arc::new(to, u, 0.0)];
        let b = build_graph("xian", &t, None, &arcs).unwrap();
        let readings: Vec<&str> = b.graph.arcs[0].iter().map(|a| a.reading.as_str()).collect();
        assert!(readings.contains(&"xian") && readings.contains(&"xi"));
        assert_eq!(1, b.units);
        let b = build_graph("zhg", &t, Some(&t.jianpin), &arcs).unwrap();
        assert!(b.abbreviated);
        assert!(b.graph.arcs[0].iter().any(|a| a.reading == "zh"));
    }

    #[test]
    fn shortcuts_and_numbers() {
        assert_eq!("一千二百三十四点五", numbers("v1234.5")[0]);
        assert!(numbers("v1234").contains(&"壹仟贰佰叁拾肆元整".to_string()));
        assert_eq!("十二", lower("12"));
        assert_eq!("一万零一", lower("10001"));
        let now = Now {
            year: 2026,
            month: 10,
            day: 16,
            hour: 14,
            minute: 5,
            weekday: 5,
        };
        assert_eq!("2026年10月16日", expand_shortcut("rq", now)[0]);
        assert_eq!("下午2点5分", expand_shortcut("sj", now)[2]);
        assert_eq!("星期五", expand_shortcut("xq", now)[0]);
        assert!(is_number("v1") && !is_number("v.") && continues_number("v1", '.'));
    }

    #[test]
    fn double_pinyin_and_profiles() {
        let t = PinyinTables::bundled();
        let x = DoubleScheme::Xiaohe.table().unwrap();
        assert_eq!("nihao", double_translate("nihc", &x, &t.valid));
        assert_eq!("zhongguo", double_translate("vsgo", &x, &t.valid));
        let parsed = parse_scheme(&write_scheme(&x, "Xiaohe"));
        assert_eq!(Some("Xiaohe".to_string()), parsed.name);
        assert_eq!(x, parsed.table);
        assert!(parsed.problems.is_empty());
        let custom = parse_scheme("zh=v\nang=ah\nfoo=1\n");
        assert_eq!(Some(&"ang".to_string()), custom.table.codes.get("ah"));
        assert_eq!(Problem::NotALetter, custom.problems[0].problem);
    }
}

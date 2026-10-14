//! Reverse-phonetic lookup from roman Urdu to word-list words, and the fold
//! every Arabic-script language shares. A port of `UrduPhoneticIndex.kt` and
//! `ArabicScriptPhoneticIndex.kt`.
//!
//! The fold is more brutal than in either Indic index, because Urdu's script
//! does not write short vowels: the key is the word's consonant skeleton,
//! and every vowel moves into the detail mask beside it, where it ranks
//! siblings instead of choosing between them. ت ٹ ط are all `t`, aspiration
//! is detail, و and ی are consonants only where they open a word, and ع, ء,
//! ئ and the diacritics fold away. A word is also filed with a closing ہ
//! read as the "a" of a Perso-Arabic noun and with its ں left off.
//!
//! Three things decide the order of siblings, measured over the flat Urdu
//! list: where a typed vowel sits (a vowel at either end must find one
//! written), which letter of a key the word used (a capital asks for the
//! variant letter), and the shorter word last.

use std::collections::HashMap;

use crate::phonetic_index::{bounded_entries, PhoneticIndex, PHONETIC_INDEX_BUDGET};
use crate::urdu_romanizer;

const SCALE: i64 = 1024;
const MAX_HANDICAP: i64 = 1 << 20;
const TYPED_NOT_FOUND: i64 = 20;
const FOUND_NOT_TYPED: i64 = 2;
/// A single letter typed for a vowel written long, inside the word.
const SHORT_FOR_LONG: i64 = 3;
/// The word writes a vowel where nothing at all was typed ("ktab").
const VOWEL_NOT_HINTED: i64 = 8;
/// One of the two spelled a vowel with two letters and the other did not.
const VOWEL_PAIR: i64 = 2;
/// Filed with its closing ہ or its ں undone.
const RELAXED: i64 = 3;

// One detail byte per key char, plus a last one for whatever vowel closes
// the word: which letter of the key, whether it was aspirated, whether the
// vowel in front of it was spelled with two letters, and which vowel that was.
const DETAIL_BASE: u8 = b'0';
const ASPIRATED: u32 = 1;
const VARIANT: u32 = 2;
const PAIR: u32 = 4;
const VOWEL_SHIFT: u32 = 3;
const VOWEL_MASK: u32 = 15;
const VOWEL_NONE: u32 = 0;
/// A vowel the script would not write: the zabar of دن, the zer of کتاب.
const VOWEL_SHORT: u32 = 1;
const VOWEL_A: u32 = 2;
const VOWEL_I: u32 = 3;
const VOWEL_U: u32 = 4;
const VOWEL_E: u32 = 5;
const VOWEL_O: u32 = 6;

const DO_HE: char = '\u{6BE}';
const GOL_HE: char = '\u{6C1}';
const NUN_GHUNNA: char = '\u{6BA}';
const ALIF: char = '\u{627}';
const MADDA: char = '\u{622}';
const WAO: char = '\u{648}';
const YE: char = '\u{6CC}';
const BARI_YE: char = '\u{6D2}';
const HAMZA_WAO: char = '\u{624}';

/// A canonical key and, byte for byte, the detail the fold threw away.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Folded {
    pub key: String,
    pub detail: Vec<u8>,
}

#[derive(Clone)]
enum Slot {
    /// `variant`: the key's other letter (ٹ for `t`, ق for `k`), which roman
    /// spelling does not distinguish. `relaxable`: a closing ہ or a ں.
    Consonant {
        key: char,
        aspirated: bool,
        variant: bool,
        relaxable: bool,
    },
    Vowel(u32),
}

impl Slot {
    fn consonant(key: char) -> Slot {
        Slot::Consonant {
            key,
            aspirated: false,
            variant: false,
            relaxable: false,
        }
    }

    fn cons(key: char, aspirated: bool, variant: bool) -> Slot {
        Slot::Consonant {
            key,
            aspirated,
            variant,
            relaxable: false,
        }
    }
}

/// The slot lists a word is filed under: itself, and the same word with a
/// relaxable slot read the other way. At most two are relaxed.
fn relaxations(slots: &[Slot]) -> Vec<(bool, Vec<Slot>)> {
    let relaxable: Vec<usize> = slots
        .iter()
        .enumerate()
        .filter(|(_, s)| {
            matches!(
                s,
                Slot::Consonant {
                    relaxable: true,
                    ..
                }
            )
        })
        .map(|(i, _)| i)
        .take(2)
        .collect();
    if relaxable.is_empty() {
        return vec![(false, slots.to_vec())];
    }
    let mut out = Vec::with_capacity(1 << relaxable.len());
    for mask in 0..(1u32 << relaxable.len()) {
        let drop: Vec<usize> = relaxable
            .iter()
            .enumerate()
            .filter(|(i, _)| (mask >> i) & 1 == 1)
            .map(|(_, &idx)| idx)
            .collect();
        let mut variant: Vec<Slot> = slots
            .iter()
            .enumerate()
            .filter(|(i, _)| !drop.contains(i))
            .map(|(_, s)| s.clone())
            .collect();
        // A dropped closing ہ still said its vowel: بچہ is "bacha".
        if !drop.is_empty() {
            variant.push(Slot::Vowel(VOWEL_SHORT));
        }
        out.push((mask != 0, variant));
    }
    out
}

fn fold(slots: &[Slot]) -> Folded {
    let mut key = String::with_capacity(slots.len());
    let mut detail: Vec<u8> = Vec::with_capacity(slots.len() + 1);
    let mut vowel = VOWEL_NONE;
    let mut pair = false;
    // The base is ADDED and the fields OR-ed together: '0' is 0b110000, which
    // overlaps the vowel bits, so or-ing it in would swallow them.
    let bits = |extra: u32, pair: bool, vowel: u32| -> u8 {
        let fields = extra | (if pair { PAIR } else { 0 }) | (vowel << VOWEL_SHIFT);
        DETAIL_BASE + fields as u8
    };
    for slot in slots {
        match slot {
            Slot::Vowel(cls) => {
                if vowel == VOWEL_NONE {
                    vowel = *cls;
                } else if *cls != VOWEL_NONE {
                    pair = true;
                }
            }
            Slot::Consonant {
                key: k,
                aspirated,
                variant,
                ..
            } => {
                let doubled = key.ends_with(*k) && vowel == VOWEL_NONE;
                if doubled {
                    // One letter: Urdu doubles with a shadda nobody types, so
                    // "acha" and "accha" are one word.
                    if *aspirated {
                        let last = detail.last_mut().unwrap();
                        *last |= ASPIRATED as u8;
                    }
                } else {
                    key.push(*k);
                    detail.push(bits(
                        (if *aspirated { ASPIRATED } else { 0 })
                            | (if *variant { VARIANT } else { 0 }),
                        pair,
                        vowel,
                    ));
                }
                vowel = VOWEL_NONE;
                pair = false;
            }
        }
    }
    detail.push(bits(0, pair, vowel));
    Folded { key, detail }
}

/// The consonants, folded: key letter, whether it counts as aspirated, and
/// whether it is the key's variant letter.
fn urdu_consonant(c: char) -> Option<(char, bool, bool)> {
    Some(match c {
        'ب' => ('b', false, false),
        'پ' => ('p', false, false),
        // ف and پھ are one sound to roman spelling, which writes "f" for
        // either and "ph" for either.
        'ف' => ('p', true, true),
        'ت' => ('t', false, false),
        'ٹ' | 'ط' => ('t', false, true),
        'د' => ('d', false, false),
        'ڈ' => ('d', false, true),
        'س' => ('s', false, false),
        'ص' | 'ث' => ('s', false, true),
        'ش' => ('s', true, false),
        'ز' => ('z', false, false),
        'ذ' | 'ض' | 'ظ' => ('z', false, true),
        'ژ' => ('z', true, false),
        'ج' => ('j', false, false),
        'چ' => ('c', false, false),
        'ک' => ('k', false, false),
        'ق' => ('k', false, true),
        'خ' => ('k', true, false),
        'گ' => ('g', false, false),
        'غ' => ('g', true, false),
        'ل' => ('l', false, false),
        'م' => ('m', false, false),
        'ن' => ('n', false, false),
        'ر' => ('r', false, false),
        'ڑ' => ('r', false, true),
        'ح' => ('h', false, true),
        _ => return None,
    })
}

fn urdu_slots(raw: &str) -> Vec<Slot> {
    let word: Vec<char> = urdu_romanizer::normalize(raw).chars().collect();
    let mut slots: Vec<Slot> = Vec::with_capacity(word.len());
    let mut i = 0;
    while i < word.len() {
        let c = word[i];
        let aspirated = word.get(i + 1) == Some(&DO_HE);
        if let Some((key, asp, variant)) = urdu_consonant(c) {
            slots.push(Slot::cons(key, asp || aspirated, variant));
            if aspirated {
                i += 1;
            }
        } else if c == WAO && i == 0 {
            // Opening the word these are consonants (وہ, یار) and inside it
            // the long vowels, which is how the roman side reads "w" and "y".
            slots.push(Slot::consonant('v'));
        } else if c == YE && i == 0 {
            slots.push(Slot::consonant('y'));
        } else if c == WAO || c == HAMZA_WAO {
            slots.push(Slot::Vowel(VOWEL_O));
        } else if c == YE {
            slots.push(Slot::Vowel(VOWEL_I));
        } else if c == BARI_YE {
            slots.push(Slot::Vowel(VOWEL_E));
        } else if c == ALIF || c == MADDA {
            slots.push(Slot::Vowel(VOWEL_A));
        } else if c == GOL_HE {
            // A gol he closing the word is as often the "a" of بچہ as it is
            // an h, so it is filed both ways.
            slots.push(Slot::Consonant {
                key: 'h',
                aspirated,
                variant: false,
                relaxable: i == word.len() - 1,
            });
        } else if c == NUN_GHUNNA {
            slots.push(Slot::Consonant {
                key: 'n',
                aspirated: false,
                variant: false,
                relaxable: true,
            });
        }
        // ع, ء, ئ and the diacritics are orthography, not sound.
        i += 1;
    }
    slots
}

/// Consonants an "h" after them aspirates, ڑھ of "parhna" included.
const ASPIRABLE: &str = "bptdjkgr";
/// Capitals that name the variant letter: ٹ ڈ ڑ ص ض ح.
const VARIANT_CAPITALS: &str = "TDRSZH";

fn roman_slots(input: &str) -> Vec<Slot> {
    let chars: Vec<char> = input.chars().collect();
    let mut slots: Vec<Slot> = Vec::with_capacity(chars.len());
    let mut i = 0;
    while i < chars.len() {
        let raw = chars[i];
        let c = raw.to_ascii_lowercase();
        let next = chars.get(i + 1).map(|n| n.to_ascii_lowercase());
        let doubled = next == Some(c);
        let variant = VARIANT_CAPITALS.contains(raw);
        match c {
            'a' => match next {
                // "hai" is ہے and "kaise" کیسے: the pair spells one vowel.
                Some('i') | Some('y') => {
                    slots.push(Slot::Vowel(VOWEL_E));
                    i += 1;
                }
                // "aur" is اور: two vowel letters, which the pair bit records.
                Some('u') => {
                    slots.push(Slot::Vowel(VOWEL_O));
                    slots.push(Slot::Vowel(VOWEL_O));
                    i += 1;
                }
                _ => {
                    slots.push(Slot::Vowel(if doubled || raw == 'A' {
                        VOWEL_A
                    } else {
                        VOWEL_SHORT
                    }));
                    if doubled {
                        i += 1;
                    }
                }
            },
            'i' => {
                slots.push(Slot::Vowel(if doubled || raw == 'I' {
                    VOWEL_I
                } else {
                    VOWEL_SHORT
                }));
                if doubled {
                    i += 1;
                }
            }
            'e' => match next {
                Some('e') => {
                    slots.push(Slot::Vowel(VOWEL_I));
                    i += 1;
                }
                // "mein" is میں: the i spells the vowel, it is not a second one.
                Some('i') | Some('y') => {
                    slots.push(Slot::Vowel(VOWEL_E));
                    i += 1;
                }
                _ => slots.push(Slot::Vowel(VOWEL_E)),
            },
            'u' => {
                slots.push(Slot::Vowel(if doubled || raw == 'U' {
                    VOWEL_U
                } else {
                    VOWEL_SHORT
                }));
                if doubled {
                    i += 1;
                }
            }
            'o' => {
                slots.push(Slot::Vowel(VOWEL_O));
                if doubled {
                    i += 1;
                }
            }
            'y' => slots.push(if slots.is_empty() {
                Slot::consonant('y')
            } else {
                Slot::Vowel(VOWEL_I)
            }),
            'w' | 'v' => slots.push(if slots.is_empty() {
                Slot::consonant('v')
            } else {
                Slot::Vowel(VOWEL_O)
            }),
            'c' if next == Some('h') => {
                let aspirate = chars.get(i + 2).map(|n| n.to_ascii_lowercase()) == Some('h');
                slots.push(Slot::cons('c', aspirate, false));
                i += if aspirate { 2 } else { 1 };
            }
            's' if next == Some('h') => {
                slots.push(Slot::cons('s', true, variant));
                i += 1;
            }
            'z' if next == Some('h') => {
                slots.push(Slot::cons('z', true, variant));
                i += 1;
            }
            _ if next == Some('h') && ASPIRABLE.contains(c) => {
                slots.push(Slot::cons(c, true, variant));
                i += 1;
            }
            'f' => slots.push(Slot::cons('p', true, true)),
            'q' => slots.push(Slot::cons('k', false, true)),
            'x' => {
                slots.push(Slot::consonant('k'));
                slots.push(Slot::consonant('s'));
            }
            'a'..='z' => slots.push(Slot::cons(c, false, variant)),
            // digits, punctuation, the apostrophe standing for ع, and any
            // other script: nothing the key is made of
            _ => {}
        }
        i += 1;
    }
    slots
}

/// Folds an Urdu word to its canonical phonetic key.
pub fn fold_urdu(word: &str) -> String {
    fold(&urdu_slots(word)).key
}

/// Folds roman Urdu typing to the same canonical key.
pub fn fold_roman(input: &str) -> Folded {
    fold(&roman_slots(input))
}

struct Entry {
    word: String,
    frequency: i32,
    detail: Vec<u8>,
    /// Filed with a closing ہ or a ں undone, which costs it a little.
    relaxed: bool,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Position {
    Initial,
    Inner,
    Final,
}

pub struct UrduPhoneticIndex {
    by_key: HashMap<String, Vec<Entry>>,
    freq_by_word: HashMap<String, i32>,
    max_frequency: i32,
}

impl UrduPhoneticIndex {
    pub fn new(entries: Vec<(String, i32)>) -> UrduPhoneticIndex {
        let mut by_key: HashMap<String, Vec<Entry>> = HashMap::new();
        let mut freq_by_word: HashMap<String, i32> = HashMap::new();
        for (word, frequency) in entries {
            // Compiled and scraped lists both carry tokens that are not words:
            // ur_full spells its compounds with an underscore (آؤ_بھگت).
            if !word.chars().all(urdu_romanizer::is_urdu) {
                continue;
            }
            let slots = urdu_slots(&word);
            if slots.is_empty() {
                continue;
            }
            let mut seen: Vec<String> = Vec::with_capacity(4);
            for (relaxed, variant) in relaxations(&slots) {
                let folded = fold(&variant);
                if folded.key.is_empty() || seen.contains(&folded.key) {
                    continue;
                }
                seen.push(folded.key.clone());
                by_key.entry(folded.key).or_default().push(Entry {
                    word: word.clone(),
                    frequency,
                    detail: folded.detail,
                    relaxed,
                });
            }
            let f = freq_by_word.entry(word).or_insert(frequency);
            *f = (*f).max(frequency);
        }
        let max_frequency = freq_by_word.values().copied().max().unwrap_or(0);
        UrduPhoneticIndex {
            by_key,
            freq_by_word,
            max_frequency,
        }
    }

    fn handicap(entry: &Entry, typed: &Folded) -> i64 {
        let mut divisor = if entry.relaxed { RELAXED } else { 1 };
        // Same key means the same number of slots, so the masks line up.
        if entry.detail.len() != typed.detail.len() {
            return MAX_HANDICAP;
        }
        let last = typed.detail.len() - 1;
        let key: Vec<char> = typed.key.chars().collect();
        for i in 0..typed.detail.len() {
            let position = if i == 0 {
                Position::Initial
            } else if i == last {
                Position::Final
            } else {
                Position::Inner
            };
            divisor *= mismatch(
                (typed.detail[i] - DETAIL_BASE) as u32,
                (entry.detail[i] - DETAIL_BASE) as u32,
                position,
                key.get(i).copied(),
            );
            if divisor > MAX_HANDICAP {
                return MAX_HANDICAP;
            }
        }
        divisor
    }
}

fn mismatch(typed: u32, word: u32, position: Position, key: Option<char>) -> i64 {
    let mut divisor = 1i64;
    let typed_aspirated = typed & ASPIRATED != 0;
    // "ch" is how چھ gets written by anyone used to English, so that one key
    // does not count aspiration at all.
    if key != Some('c') && typed_aspirated != (word & ASPIRATED != 0) {
        divisor *= if typed_aspirated {
            TYPED_NOT_FOUND
        } else {
            FOUND_NOT_TYPED
        };
    }
    let typed_variant = typed & VARIANT != 0;
    if typed_variant != (word & VARIANT != 0) {
        divisor *= if typed_variant {
            TYPED_NOT_FOUND
        } else {
            FOUND_NOT_TYPED
        };
    }
    if (typed & PAIR) != (word & PAIR) {
        divisor *= VOWEL_PAIR;
    }
    divisor *= vowel_mismatch(
        (typed >> VOWEL_SHIFT) & VOWEL_MASK,
        (word >> VOWEL_SHIFT) & VOWEL_MASK,
        position,
    );
    divisor
}

fn vowel_mismatch(typed: u32, word: u32, position: Position) -> i64 {
    if typed == word {
        return 1;
    }
    // ی is the vowel of both دیکھ and میں, و of both ہو and خوب.
    if compatible(typed, word) {
        return 1;
    }
    // A vowel a relaxation synthesised stands for whatever was written.
    if word == VOWEL_SHORT {
        return 1;
    }
    if position == Position::Inner {
        return if typed == VOWEL_NONE {
            VOWEL_NOT_HINTED
        } else if word == VOWEL_NONE {
            if typed == VOWEL_SHORT {
                1
            } else {
                TYPED_NOT_FOUND
            }
        } else if typed == VOWEL_SHORT {
            SHORT_FOR_LONG
        } else {
            TYPED_NOT_FOUND
        };
    }
    // Opening or closing the word, where Urdu writes the vowel as a letter.
    if typed == VOWEL_NONE {
        VOWEL_NOT_HINTED
    } else if word == VOWEL_NONE {
        TYPED_NOT_FOUND
    } else if typed == VOWEL_SHORT {
        // "ki" is کی and "ap" آپ: one letter for a long vowel is the norm.
        1
    } else {
        TYPED_NOT_FOUND
    }
}

fn compatible(a: u32, b: u32) -> bool {
    (a == VOWEL_I && b == VOWEL_E)
        || (a == VOWEL_E && b == VOWEL_I)
        || (a == VOWEL_O && b == VOWEL_U)
        || (a == VOWEL_U && b == VOWEL_O)
}

impl PhoneticIndex for UrduPhoneticIndex {
    fn lookup(&self, input: &str) -> Vec<String> {
        let typed = fold(&roman_slots(input));
        let bucket: &[Entry] = self
            .by_key
            .get(&typed.key)
            .map(Vec::as_slice)
            .unwrap_or(&[]);
        if bucket.len() <= 1 {
            return bucket.iter().map(|e| e.word.clone()).collect();
        }
        let mut sorted: Vec<&Entry> = bucket.iter().collect();
        sorted.sort_by(|a, b| {
            let sa = a.frequency as i64 * SCALE / Self::handicap(a, &typed);
            let sb = b.frequency as i64 * SCALE / Self::handicap(b, &typed);
            sb.cmp(&sa)
                .then(a.word.chars().count().cmp(&b.word.chars().count()))
                .then(a.word.cmp(&b.word))
        });
        let mut out: Vec<String> = Vec::new();
        for e in sorted {
            if !out.contains(&e.word) {
                out.push(e.word.clone());
            }
        }
        out
    }

    fn frequency_of(&self, word: &str) -> i32 {
        self.freq_by_word.get(word).copied().unwrap_or(0)
    }

    fn match_strength(&self, input: &str) -> i32 {
        let typed = fold(&roman_slots(input));
        self.by_key
            .get(&typed.key)
            .and_then(|b| {
                b.iter()
                    .map(|e| e.frequency as i64 / Self::handicap(e, &typed))
                    .max()
            })
            .unwrap_or(0) as i32
    }

    fn max_frequency(&self) -> i32 {
        self.max_frequency
    }

    fn is_empty(&self) -> bool {
        self.by_key.is_empty()
    }
}

/// Reverse-phonetic lookup for Persian and Arabic: the Urdu index's fold,
/// which fits every language the Arabic script writes, with `readings`: the
/// other spellings a typed word may stand for, looked up too and interleaved
/// after the typed one.
pub struct ArabicScriptPhoneticIndex {
    inner: UrduPhoneticIndex,
    readings: fn(&str) -> Vec<String>,
}

impl ArabicScriptPhoneticIndex {
    pub fn new(
        entries: Vec<(String, i32)>,
        readings: fn(&str) -> Vec<String>,
    ) -> ArabicScriptPhoneticIndex {
        ArabicScriptPhoneticIndex {
            inner: UrduPhoneticIndex::new(bounded_entries(entries, PHONETIC_INDEX_BUDGET)),
            readings,
        }
    }
}

/// The one reading a spelling has by default.
pub fn single_reading(input: &str) -> Vec<String> {
    vec![input.to_string()]
}

/// Persian: "gh" is ق as readily as غ.
pub fn persian_readings(input: &str) -> Vec<String> {
    if input.contains("gh") {
        vec![input.to_string(), input.replace("gh", "q")]
    } else {
        vec![input.to_string()]
    }
}

/// Arabic: the Arabizi digits as the letters the fold reads, and Egyptian
/// "g" as ج beside the ق and غ it otherwise is.
pub fn arabic_readings(input: &str) -> Vec<String> {
    let plain = arabizi(input);
    if plain.contains('g') && !plain.contains("gh") {
        vec![plain.clone(), plain.replace('g', "j")]
    } else {
        vec![plain]
    }
}

/// Arabizi digits spelled as the roman letters the fold keys on.
pub fn arabizi(input: &str) -> String {
    if !input.chars().any(|c| c.is_ascii_digit()) {
        return input.to_string();
    }
    let chars: Vec<char> = input.chars().collect();
    let mut out = String::with_capacity(input.len() + 2);
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        let marked = chars.get(i + 1) == Some(&'\'');
        let letters = match c {
            '2' => Some("'"),
            '3' => Some(if marked { "gh" } else { "'" }),
            '5' => Some("kh"),
            '6' => Some(if marked { "Z" } else { "T" }),
            '7' => Some("H"),
            '8' => Some("q"),
            // ض and ظ are both the fold's variant z.
            '9' => Some(if marked { "Z" } else { "S" }),
            _ => None,
        };
        match letters {
            None => out.push(c),
            Some(letters) => {
                out.push_str(letters);
                if marked && "369".contains(c) {
                    i += 1;
                }
            }
        }
        i += 1;
    }
    out
}

impl PhoneticIndex for ArabicScriptPhoneticIndex {
    fn lookup(&self, input: &str) -> Vec<String> {
        let all: Vec<Vec<String>> = (self.readings)(input)
            .iter()
            .map(|r| self.inner.lookup(r))
            .collect();
        if all.len() == 1 {
            return all.into_iter().next().unwrap();
        }
        // Interleaved, so the typed spelling's best leads and each other
        // reading's best follows close behind rather than after a long tail.
        let longest = all.iter().map(Vec::len).max().unwrap_or(0);
        let mut out: Vec<String> = Vec::new();
        for rank in 0..longest {
            for list in &all {
                if let Some(word) = list.get(rank) {
                    if !out.contains(word) {
                        out.push(word.clone());
                    }
                }
            }
        }
        out
    }

    fn frequency_of(&self, word: &str) -> i32 {
        self.inner.frequency_of(word)
    }

    fn match_strength(&self, input: &str) -> i32 {
        (self.readings)(input)
            .iter()
            .map(|r| self.inner.match_strength(r))
            .max()
            .unwrap_or(0)
    }

    fn max_frequency(&self) -> i32 {
        self.inner.max_frequency()
    }

    fn is_empty(&self) -> bool {
        self.inner.is_empty()
    }
}

#[cfg(test)]
mod tests {
    //! Mirrors `UrduPhoneticIndexTest.kt` and `ArabicScriptPhoneticIndexTest.kt`.

    use super::*;

    fn flat(words: &[&str]) -> Vec<(String, i32)> {
        words.iter().map(|w| (w.to_string(), 1)).collect()
    }

    fn index() -> UrduPhoneticIndex {
        UrduPhoneticIndex::new(flat(&[
            "کرنا",
            "کم",
            "کام",
            "کتاب",
            "کتب",
            "کتابیں",
            "دن",
            "دین",
            "روٹی",
            "روتی",
            "بڑا",
            "برا",
            "پانی",
            "پنی",
            "ہے",
            "ہیں",
            "بچہ",
            "بچا",
            "نہیں",
            "نہی",
            "خوش",
            "دوست",
            "مرد",
            "کوئی",
            "کیا",
            "میں",
            "ٹھیک",
            "تھیک",
            "سڑک",
            "سرک",
            "گھر",
            "غار",
            "خبر",
            "صبح",
            "ضرور",
            "زرور",
        ]))
    }

    fn first(input: &str) -> Option<String> {
        index().lookup(input).into_iter().next()
    }

    fn s(v: &str) -> Option<String> {
        Some(v.to_string())
    }

    #[test]
    fn the_key_is_the_consonant_skeleton() {
        assert_eq!(fold_urdu("کتاب"), fold_roman("kitab").key);
        assert_eq!(fold_urdu("کتاب"), fold_roman("ktab").key);
        assert_eq!(fold_urdu("کرنا"), fold_roman("karna").key);
        assert_eq!(fold_urdu("کیا"), fold_roman("kya").key);
        assert_eq!(fold_urdu("دعا"), fold_roman("dua").key);
        assert_eq!("ktb", fold_urdu("کتاب"));
    }

    #[test]
    fn short_vowels_find_the_words_that_write_them() {
        assert_eq!(s("کرنا"), first("karna"));
        assert_eq!(s("کتب"), first("kitab"));
        assert!(index().lookup("kitab").contains(&"کتاب".to_string()));
        assert_eq!(s("دوست"), first("dost"));
        assert_eq!(s("مرد"), first("mard"));
        assert!(index().lookup("ktab").contains(&"کتاب".to_string()));
    }

    #[test]
    fn a_doubled_vowel_asks_for_the_long_one() {
        assert_eq!(s("کم"), first("kam"));
        assert_eq!(s("کام"), first("kaam"));
        assert_eq!(s("دن"), first("din"));
        assert_eq!(s("دین"), first("deen"));
    }

    #[test]
    fn the_retroflex_row_is_folded_and_the_capital_asks_for_it() {
        assert!(index().lookup("roti").contains(&"روٹی".to_string()));
        assert!(index().lookup("bara").contains(&"بڑا".to_string()));
        assert_eq!(s("تھیک"), first("theek"));
        assert_eq!(s("ٹھیک"), first("Theek"));
        assert_eq!(s("سرک"), first("sarak"));
        assert_eq!(s("سڑک"), first("saRak"));
    }

    #[test]
    fn the_arabic_letters_are_siblings_of_the_urdu_ones() {
        assert_eq!(s("خوش"), first("khush"));
        assert_eq!(s("خبر"), first("khabar"));
        assert_eq!(s("صبح"), first("subah"));
        assert!(index().lookup("zaroor").contains(&"ضرور".to_string()));
        assert_eq!(s("ضرور"), first("Zaroor"));
        assert_eq!(s("گھر"), first("ghar"));
    }

    #[test]
    fn nasalisation_and_a_closing_he_are_filed_both_ways() {
        assert_eq!(s("ہیں"), first("hain"));
        assert_eq!(s("میں"), first("mein"));
        assert!(index().lookup("hai").contains(&"ہیں".to_string()));
        assert_eq!(s("ہے"), first("hai"));
        assert!(index().lookup("bacha").contains(&"بچہ".to_string()));
        assert!(index().lookup("nahi").contains(&"نہیں".to_string()));
    }

    #[test]
    fn a_vowel_only_word_is_reached_through_its_carrier() {
        assert_eq!(s("کوئی"), first("koi"));
        assert_eq!(s("کیا"), first("kya"));
    }

    #[test]
    fn frequency_still_leads_where_the_list_has_it() {
        let ranked =
            UrduPhoneticIndex::new(vec![("کام".to_string(), 9000), ("کم".to_string(), 100)]);
        assert_eq!(s("کام"), ranked.lookup("kam").into_iter().next());
        assert_eq!(9000, ranked.frequency_of("کام"));
        assert_eq!(9000, ranked.max_frequency());
    }

    #[test]
    fn underscored_compounds_are_not_words() {
        let with_compound = UrduPhoneticIndex::new(flat(&["آؤ_بھگت", "بھگت"]));
        assert_eq!(vec!["بھگت"], with_compound.lookup("bhagat"));
    }

    #[test]
    fn an_empty_list_knows_it_is_empty() {
        let empty = UrduPhoneticIndex::new(Vec::new());
        assert!(empty.is_empty());
        assert!(empty.lookup("kitab").is_empty());
        assert_eq!(0, empty.frequency_of("کتاب"));
        assert!(!index().is_empty());
    }

    #[test]
    fn arabizi_digits_are_the_letters_the_fold_reads() {
        assert_eq!("H'b'b'", arabizi("7'b'b'"));
        assert_eq!("Habibi", arabizi("7abibi"));
        assert_eq!("'arabi", arabizi("3arabi"));
        assert_eq!("shoghl", arabizi("sho3'l"));
        assert_eq!("Za'eef", arabizi("9'a3eef"));
        assert_eq!("khobz", arabizi("5obz"));
        assert_eq!("Tayyib", arabizi("6ayyib"));
        assert_eq!("qalbi", arabizi("8albi"));
        assert_eq!("Sabah", arabizi("9abah"));
        assert_eq!("salam", arabizi("salam"));
    }

    #[test]
    fn persian_gh_is_also_qaf() {
        assert_eq!(vec!["ghashang", "qashang"], persian_readings("ghashang"));
        assert_eq!(vec!["salam"], persian_readings("salam"));
    }

    #[test]
    fn egyptian_g_is_also_jim() {
        assert_eq!(vec!["gamil", "jamil"], arabic_readings("gamil"));
        assert_eq!(vec!["ghali"], arabic_readings("ghali"));
    }

    #[test]
    fn arabic_words_fold_like_urdu_ones() {
        let index = ArabicScriptPhoneticIndex::new(
            vec![
                ("\u{62D}\u{628}\u{64A}\u{628}\u{64A}".to_string(), 500),
                ("\u{643}\u{62A}\u{627}\u{628}".to_string(), 400),
            ],
            arabic_readings,
        );
        assert_eq!(
            s("\u{62D}\u{628}\u{64A}\u{628}\u{64A}"),
            index.lookup("7abibi").into_iter().next()
        );
        assert_eq!(
            s("\u{643}\u{62A}\u{627}\u{628}"),
            index.lookup("kitab").into_iter().next()
        );
    }

    #[test]
    fn persian_qaf_is_reached_through_gh() {
        let index = ArabicScriptPhoneticIndex::new(
            vec![("\u{642}\u{634}\u{646}\u{6AF}".to_string(), 300)],
            persian_readings,
        );
        assert!(index
            .lookup("ghashang")
            .contains(&"\u{642}\u{634}\u{646}\u{6AF}".to_string()));
        assert!(index.match_strength("ghashang") > 0);
        assert_eq!(vec!["x"], single_reading("x"));
    }
}

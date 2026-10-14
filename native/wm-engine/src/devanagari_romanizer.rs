//! Hindi written back in Latin letters, the way people spell it in a chat:
//! करना → karna, नमस्ते → namaste, लड़की → ladki. A port of
//! `DevanagariRomanizer.kt`.
//!
//! The inverse of the Hindi engine, roughly, and not by inverting its rules:
//! this has to choose a spelling, and the choice that matters is which
//! inherent vowels to write. Two rules cover nearly all of it:
//!
//! - the inherent vowel that closes a word is silent (कमल is kamal), unless
//!   the word is that one syllable (न is na) or ends in a conjunct that
//!   cannot be said without it (मित्र is mitra);
//! - inside a word it is silent between two syllables that are both voiced,
//!   decided from the end backwards, so no two in a row are ever dropped.
//!
//! Length is written the way chat spelling writes it: doubled inside a word
//! (naam, theek), single at the end (mera, ladki).

const VIRAMA: char = '\u{94D}';
const NUKTA: char = '\u{93C}';
const ANUSVARA: char = '\u{902}';
const CANDRABINDU: char = '\u{901}';
const VISARGA: char = '\u{903}';
const ZWJ: char = '\u{200D}';
const ZWNJ: char = '\u{200C}';

fn consonant(c: char) -> Option<&'static str> {
    Some(match c {
        'क' => "k",
        'ख' => "kh",
        'ग' => "g",
        'घ' => "gh",
        'ङ' => "n",
        'च' => "ch",
        'छ' => "chh",
        'ज' => "j",
        'झ' => "jh",
        'ञ' => "n",
        'ट' => "t",
        'ठ' => "th",
        'ड' => "d",
        'ढ' => "dh",
        'ण' => "n",
        'त' => "t",
        'थ' => "th",
        'द' => "d",
        'ध' => "dh",
        'न' => "n",
        'प' => "p",
        'फ' => "ph",
        'ब' => "b",
        'भ' => "bh",
        'म' => "m",
        'य' => "y",
        'र' => "r",
        'ल' | 'ळ' => "l",
        'व' => "v",
        'श' | 'ष' => "sh",
        'स' => "s",
        'ह' => "h",
        _ => return None,
    })
}

/// What the nukta turns a letter into, where that is a different sound.
fn with_nukta(c: char) -> Option<&'static str> {
    match c {
        'ज' => Some("z"),
        'फ' => Some("f"),
        'क' => Some("q"),
        _ => None,
    }
}

/// Precomposed nukta letters (U+0958–U+095F) as base + nukta, which is what NFC makes of them.
const NUKTA_BASES: [char; 8] = ['क', 'ख', 'ग', 'ज', 'ड', 'ढ', 'फ', 'य'];

fn vowel(c: char) -> Option<&'static str> {
    Some(match c {
        'अ' => "a",
        'आ' => "aa",
        'इ' => "i",
        'ई' => "ee",
        'उ' => "u",
        'ऊ' => "oo",
        'ऋ' => "ri",
        'ए' => "e",
        'ऐ' => "ai",
        'ओ' => "o",
        'औ' => "au",
        _ => return None,
    })
}

fn matra(c: char) -> Option<&'static str> {
    Some(match c {
        '\u{93E}' => "aa",
        '\u{93F}' => "i",
        '\u{940}' => "ee",
        '\u{941}' => "u",
        '\u{942}' => "oo",
        '\u{943}' => "ri",
        '\u{947}' => "e",
        '\u{948}' => "ai",
        '\u{94B}' => "o",
        '\u{94C}' => "au",
        '\u{949}' => "o",
        '\u{945}' => "e",
        _ => return None,
    })
}

/// A long vowel written single where it closes the word.
fn short_at_end(v: &str) -> Option<&'static str> {
    match v {
        "aa" => Some("a"),
        "ee" => Some("i"),
        "oo" => Some("u"),
        _ => None,
    }
}

/// Conjuncts a word cannot end on without the vowel being said: मित्र, सत्य, तत्व.
const NEEDS_FINAL_A: [char; 3] = ['य', 'र', 'व'];
const LABIALS: [char; 5] = ['प', 'फ', 'ब', 'भ', 'म'];
const FRONT_MATRAS: [char; 5] = ['\u{93F}', '\u{940}', '\u{947}', '\u{948}', '\u{943}'];

pub fn is_devanagari(c: char) -> bool {
    let u = c as u32;
    (0x0900..=0x0963).contains(&u) || (0x0970..=0x097F).contains(&u)
}

/// Precomposed nukta letters taken apart, and the joiners dropped.
pub fn normalize(text: &str) -> String {
    let needs = text
        .chars()
        .any(|c| ('\u{958}'..='\u{95F}').contains(&c) || c == ZWJ || c == ZWNJ);
    if !needs {
        return text.to_string();
    }
    let mut out = String::with_capacity(text.len() + 4);
    for c in text.chars() {
        if ('\u{958}'..='\u{95F}').contains(&c) {
            out.push(NUKTA_BASES[(c as u32 - 0x958) as usize]);
            out.push(NUKTA);
        } else if c == ZWJ || c == ZWNJ {
        } else {
            out.push(c);
        }
    }
    out
}

/// Whole text: every Devanagari run becomes Latin, everything else passes through.
pub fn romanize(text: &str) -> String {
    let norm: Vec<char> = normalize(text).chars().collect();
    let mut out = String::with_capacity(norm.len() * 2);
    let mut i = 0;
    while i < norm.len() {
        let c = norm[i];
        if is_devanagari(c) {
            let mut j = i;
            while j < norm.len() && is_devanagari(norm[j]) {
                j += 1;
            }
            out.push_str(&word(&norm[i..j]));
            i = j;
        } else {
            match c {
                '\u{964}' => out.push('.'),
                '\u{965}' => out.push_str(".."),
                '\u{966}'..='\u{96F}' => out.push((b'0' + (c as u32 - 0x966) as u8) as char),
                c => out.push(c),
            }
            i += 1;
        }
    }
    out
}

/// One Hindi word, without spaces.
pub fn romanize_word(word_text: &str) -> String {
    let norm: Vec<char> = normalize(word_text).chars().collect();
    word(&norm)
}

/// One syllable as the script writes it: a consonant cluster and its vowel,
/// or an independent vowel on its own (`consonants` empty).
struct Akshara {
    consonants: Vec<char>,
    nukta: Vec<bool>,
    /// The vowel written, or `None` for the inherent one.
    vowel: Option<&'static str>,
    /// Set by `drop_schwas`: the inherent vowel is not said.
    silent: bool,
    nasal: bool,
    visarga: bool,
    front_vowel: bool,
}

impl Akshara {
    fn inherent(&self) -> bool {
        self.vowel.is_none() && !self.consonants.is_empty()
    }

    fn voiced(&self) -> bool {
        self.vowel.is_some() || (self.inherent() && !self.silent)
    }
}

fn word(w: &[char]) -> String {
    let mut aksharas = parse(w);
    drop_schwas(&mut aksharas);
    let mut out = String::with_capacity(w.len() * 2);
    for index in 0..aksharas.len() {
        let a = &aksharas[index];
        let last = index == aksharas.len() - 1;
        for (k, &c) in a.consonants.iter().enumerate() {
            out.push_str(consonant_text(a, k, c, last));
        }
        let vowel = match a.vowel {
            Some(v) => {
                if last && !a.nasal {
                    short_at_end(v).unwrap_or(v)
                } else {
                    v
                }
            }
            None if a.inherent() && !a.silent => "a",
            None => "",
        };
        out.push_str(vowel);
        if a.nasal {
            // Nasalisation on a closing long ई is the one nobody writes: nahi.
            if last && a.vowel == Some("ee") {
                out.pop();
                out.pop();
                out.push('i');
            } else if last && a.vowel == Some("e") {
                // में is "mein" to everyone; "men" reads as English.
                out.push_str("in");
            } else {
                let next = aksharas
                    .get(index + 1)
                    .and_then(|n| n.consonants.first().copied());
                out.push(if next.is_some_and(|n| LABIALS.contains(&n)) {
                    'm'
                } else {
                    'n'
                });
            }
        }
        if a.visarga {
            out.push('h');
        }
    }
    out
}

fn consonant_text(a: &Akshara, k: usize, c: char, last_akshara: bool) -> &'static str {
    if a.nukta[k] {
        if let Some(text) = with_nukta(c) {
            return text;
        }
    }
    // क्ष and ज्ञ are said, and so spelled, as one thing.
    if c == 'ष' && k > 0 && a.consonants[k - 1] == 'क' {
        return "sh";
    }
    if c == 'ज' && a.consonants.get(k + 1) == Some(&'ञ') {
        return "g";
    }
    if c == 'ञ' && k > 0 && a.consonants[k - 1] == 'ज' {
        return "y";
    }
    // A doubled affricate is "cch", not "chch": baccha, acchha.
    if c == 'च' && matches!(a.consonants.get(k + 1), Some('च') | Some('छ')) {
        return "c";
    }
    if c == 'व' {
        // "w" is how chat spelling writes it (wo, sawal, vishwas), except
        // before a front vowel and at the end of a word.
        let closes_cluster = k == a.consonants.len() - 1;
        let closing = last_akshara && closes_cluster && a.vowel.is_none();
        return if closing || (closes_cluster && a.front_vowel) {
            "v"
        } else {
            "w"
        };
    }
    consonant(c).unwrap()
}

fn parse(w: &[char]) -> Vec<Akshara> {
    let mut out: Vec<Akshara> = Vec::with_capacity(w.len());
    let mut i = 0;
    while i < w.len() {
        let c = w[i];
        if consonant(c).is_some() {
            let mut cluster = Vec::with_capacity(3);
            let mut nukta = Vec::with_capacity(3);
            let mut j = i;
            while j < w.len() && consonant(w[j]).is_some() {
                cluster.push(w[j]);
                j += 1;
                let dotted = j < w.len() && w[j] == NUKTA;
                if dotted {
                    j += 1;
                }
                nukta.push(dotted);
                if j < w.len() && w[j] == VIRAMA && j + 1 < w.len() && consonant(w[j + 1]).is_some()
                {
                    j += 1;
                } else {
                    break;
                }
            }
            let dangling = j < w.len() && w[j] == VIRAMA;
            if dangling {
                j += 1;
            }
            let matra_char = w.get(j).copied().filter(|&m| matra(m).is_some());
            if matra_char.is_some() {
                j += 1;
            }
            out.push(Akshara {
                consonants: cluster,
                nukta,
                vowel: matra_char.and_then(matra),
                // A written virama with nothing after it says so outright.
                silent: dangling,
                nasal: false,
                visarga: false,
                front_vowel: matra_char.is_some_and(|m| FRONT_MATRAS.contains(&m)),
            });
            i = j;
        } else if let Some(v) = vowel(c) {
            out.push(Akshara {
                consonants: Vec::new(),
                nukta: Vec::new(),
                vowel: Some(v),
                silent: false,
                nasal: false,
                visarga: false,
                front_vowel: false,
            });
            i += 1;
        } else if c == ANUSVARA || c == CANDRABINDU {
            if let Some(last) = out.last_mut() {
                last.nasal = true;
            }
            i += 1;
        } else if c == VISARGA {
            if let Some(last) = out.last_mut() {
                last.visarga = true;
            }
            i += 1;
        } else {
            // stray matras, nukta, om, avagraha: nothing to say
            i += 1;
        }
    }
    out
}

fn drop_schwas(aksharas: &mut [Akshara]) {
    let n = aksharas.len();
    if n < 2 {
        return;
    }
    {
        let last = &mut aksharas[n - 1];
        if last.inherent() && !last.nasal {
            let cluster = &last.consonants;
            let keep = cluster.len() == 1 || !NEEDS_FINAL_A.contains(cluster.last().unwrap());
            last.silent = last.silent || keep;
        }
    }
    for i in (1..n - 1).rev() {
        let here = &aksharas[i];
        if !here.inherent() || here.silent || here.nasal || here.consonants.len() != 1 {
            continue;
        }
        let before = &aksharas[i - 1];
        let after = &aksharas[i + 1];
        // Voiced on both sides, and the next syllable opens on one consonant:
        // dropping this vowel must not stack three of them. A nasalised
        // syllable before it counts as closed for the same reason.
        if before.voiced() && !before.nasal && after.voiced() && after.consonants.len() == 1 {
            aksharas[i].silent = true;
        }
    }
}

#[cfg(test)]
mod tests {
    //! Mirrors `DevanagariRomanizerTest.kt`.

    use super::{is_devanagari, normalize, romanize, romanize_word as r};

    #[test]
    fn the_closing_inherent_vowel_is_silent() {
        assert_eq!("kamal", r("कमल"));
        assert_eq!("agar", r("अगर"));
        assert_eq!("dost", r("दोस्त"));
        assert_eq!("dharm", r("धर्म"));
        assert_eq!("shabd", r("शब्द"));
    }

    #[test]
    fn except_where_the_word_cannot_be_said_without_it() {
        assert_eq!("na", r("न"));
        assert_eq!("mitra", r("मित्र"));
        assert_eq!("satya", r("सत्य"));
    }

    #[test]
    fn an_inner_one_is_silent_between_two_voiced_syllables() {
        assert_eq!("karna", r("करना"));
        assert_eq!("ladka", r("ल\u{921}\u{93C}का"));
        assert_eq!("sarkaar", r("सरकार"));
        assert_eq!("aadmi", r("आदमी"));
        assert_eq!("janta", r("जनता"));
        assert_eq!("samajhna", r("समझना"));
        assert_eq!("dhadkan", r("ध\u{921}\u{93C}कन"));
    }

    #[test]
    fn a_conjunct_or_a_nasal_on_either_side_keeps_it() {
        assert_eq!("namaste", r("नमस्ते"));
        assert_eq!("zindagi", r("\u{91C}\u{93C}िंदगी"));
    }

    #[test]
    fn length_is_doubled_inside_a_word_and_single_at_its_end() {
        assert_eq!("naam", r("नाम"));
        assert_eq!("theek", r("ठीक"));
        assert_eq!("door", r("दूर"));
        assert_eq!("mera", r("मेरा"));
        assert_eq!("ladki", r("ल\u{921}\u{93C}की"));
        assert_eq!("tu", r("तू"));
        assert_eq!("paani", r("पानी"));
    }

    #[test]
    fn nasalisation() {
        assert_eq!("hindi", r("हिंदी"));
        assert_eq!("lamba", r("लंबा"));
        assert_eq!("hain", r("हैं"));
        assert_eq!("mein", r("में"));
        assert_eq!("main", r("मैं"));
        assert_eq!("haan", r("हाँ"));
        assert_eq!("kyon", r("क्यों"));
        assert_eq!("hoon", r("हूँ"));
        assert_eq!("nahi", r("नहीं"));
    }

    #[test]
    fn letters_said_differently_than_written() {
        assert_eq!("kshama", r("क्षमा"));
        assert_eq!("gyaan", r("ज्ञान"));
        assert_eq!("kripa", r("कृपा"));
        assert_eq!("baccha", r("बच्चा"));
        assert_eq!("fon", r("\u{92B}\u{93C}ोन"));
        assert_eq!("phir", r("फिर"));
    }

    #[test]
    fn va_is_w_except_before_a_front_vowel_and_at_the_end() {
        assert_eq!("wo", r("वो"));
        assert_eq!("sawaal", r("सवाल"));
        assert_eq!("vishwaas", r("विश्वास"));
        assert_eq!("dev", r("देव"));
    }

    #[test]
    fn precomposed_nukta_letters_are_the_same_word() {
        assert_eq!(r("\u{95B}िंदगी"), r("\u{91C}\u{93C}िंदगी"));
        assert_eq!("\u{91C}\u{93C}", normalize("\u{95B}"));
    }

    #[test]
    fn whole_text_keeps_everything_else() {
        assert_eq!(
            "main theek hoon. tum kaise ho? 2024 OK",
            romanize("मैं ठीक हूँ। तुम कैसे हो? २०२४ OK")
        );
    }

    #[test]
    fn what_counts_as_a_word_character() {
        assert!(is_devanagari('क'));
        assert!(is_devanagari('\u{93E}'));
        assert!(!is_devanagari('\u{964}'));
        assert!(!is_devanagari('\u{968}'));
        assert!(!is_devanagari('ক'));
    }
}

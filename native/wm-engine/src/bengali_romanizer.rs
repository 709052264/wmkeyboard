//! Bengali script written back in Latin letters, the way people spell it in
//! a chat: তোমার → tomar, ধন্যবাদ → dhonnobad, কী → ki. A port of
//! `BengaliRomanizer.kt`.
//!
//! The inverse of the Avro engine, roughly, but not by inverting its rules:
//! Avro reads a spelling somebody chose, while this has to choose one, and
//! the choices are pronunciation rules. Every consonant carries an inherent
//! "o" unless something silences it: a vowel sign, a hasanta, the end of the
//! word (কাল is kal) or a following consonant that has its own vowel (বলছি is
//! bolchhi). A cluster keeps it (কষ্ট is koshto), and so do ভ হ ড় ঢ় at the end
//! of a word. য-ফলা and ব-ফলা double the consonant they sit under except at
//! the start of a word, where they are y and w.
//!
//! Heuristic by nature: a converter puts a dictionary of known spellings in
//! front of this and lets the rules catch the rest. Output is always lower
//! case; anything that is not Bengali passes through untouched.

use crate::bengali_graphemes::is_bengali;

const HASANTA: char = '\u{9CD}';
const NUKTA: char = '\u{9BC}';
const CANDRABINDU: char = '\u{981}';
const ANUSVARA: char = '\u{982}';
const VISARGA: char = '\u{983}';
const ZWJ: char = '\u{200D}';
const ZWNJ: char = '\u{200C}';

fn consonant(c: char) -> Option<&'static str> {
    Some(match c {
        'ক' => "k",
        'খ' => "kh",
        'গ' => "g",
        'ঘ' => "gh",
        'ঙ' => "ng",
        'চ' => "ch",
        'ছ' => "chh",
        'জ' => "j",
        'ঝ' => "jh",
        'ঞ' => "n",
        'ট' => "t",
        'ঠ' => "th",
        'ড' => "d",
        'ঢ' => "dh",
        'ণ' => "n",
        'ত' => "t",
        'থ' => "th",
        'দ' => "d",
        'ধ' => "dh",
        'ন' => "n",
        'প' => "p",
        'ফ' => "ph",
        'ব' => "b",
        'ভ' => "bh",
        'ম' => "m",
        'য' => "j",
        'র' => "r",
        'ল' => "l",
        'শ' => "sh",
        'ষ' => "sh",
        'স' => "s",
        'হ' => "h",
        '\u{9DC}' => "r",
        '\u{9DD}' => "rh",
        '\u{9DF}' => "y",
        'ৎ' => "t",
        '\u{9F0}' => "r",
        '\u{9F1}' => "w",
        _ => return None,
    })
}

fn vowel(c: char) -> Option<&'static str> {
    Some(match c {
        'অ' => "o",
        'আ' => "a",
        'ই' | 'ঈ' => "i",
        'উ' | 'ঊ' => "u",
        'ঋ' => "ri",
        '\u{98C}' => "li",
        'এ' => "e",
        'ঐ' => "oi",
        'ও' => "o",
        'ঔ' => "ou",
        _ => return None,
    })
}

fn matra(c: char) -> Option<&'static str> {
    Some(match c {
        '\u{9BE}' => "a",
        '\u{9BF}' | '\u{9C0}' => "i",
        '\u{9C1}' | '\u{9C2}' => "u",
        '\u{9C3}' | '\u{9C4}' => "ri",
        '\u{9E2}' | '\u{9E3}' => "li",
        '\u{9C7}' => "e",
        '\u{9C8}' => "oi",
        '\u{9CB}' => "o",
        '\u{9CC}' => "ou",
        '\u{9D7}' => "u",
        _ => return None,
    })
}

/// Consonants that keep their inherent vowel at the end of a word.
const KEEP_FINAL_O: [char; 4] = ['ভ', 'হ', '\u{9DC}', '\u{9DD}'];
/// A ব-ফলা under one of these is a real b (অম্বর ombor), not a doubling.
const B_STAYS_B: [char; 6] = ['ম', 'ল', 'ন', 'ণ', 'ড', 'ঙ'];

/// Decomposed nukta letters, two-part vowels and the অ+া misspelling folded
/// to one code point each.
pub fn normalize(text: &str) -> String {
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        let next = chars.get(i + 1).copied();
        if c == ZWJ || c == ZWNJ {
            i += 1;
        } else if next == Some(NUKTA) && c == 'ড' {
            out.push('\u{9DC}');
            i += 2;
        } else if next == Some(NUKTA) && c == 'ঢ' {
            out.push('\u{9DD}');
            i += 2;
        } else if next == Some(NUKTA) && c == 'য' {
            out.push('\u{9DF}');
            i += 2;
        } else if next == Some(NUKTA) {
            out.push(c);
            i += 2;
        } else if c == '\u{9C7}' && next == Some('\u{9BE}') {
            out.push('\u{9CB}');
            i += 2;
        } else if c == '\u{9C7}' && next == Some('\u{9D7}') {
            out.push('\u{9CC}');
            i += 2;
        } else if c == 'অ' && next == Some('\u{9BE}') {
            out.push('আ');
            i += 2;
        } else if c == NUKTA {
            i += 1;
        } else {
            out.push(c);
            i += 1;
        }
    }
    out
}

/// Whole text: every Bengali run becomes Latin, everything else passes through.
pub fn romanize(text: &str) -> String {
    let norm: Vec<char> = normalize(text).chars().collect();
    let mut out = String::with_capacity(norm.len() * 2);
    let mut i = 0;
    while i < norm.len() {
        if is_bengali(norm[i]) {
            let mut j = i;
            while j < norm.len() && is_bengali(norm[j]) {
                j += 1;
            }
            out.push_str(&word(&norm[i..j]));
            i = j;
        } else {
            // The danda is Devanagari punctuation shared by Bengali, so it
            // sits outside the run but still wants a full stop.
            match norm[i] {
                '\u{964}' => out.push('.'),
                '\u{965}' => out.push_str(".."),
                c => out.push(c),
            }
            i += 1;
        }
    }
    out
}

/// One Bengali word, without spaces.
pub fn romanize_word(word_text: &str) -> String {
    let norm: Vec<char> = normalize(word_text).chars().collect();
    word(&norm)
}

struct Cluster {
    text: String,
    y_phala: bool,
    geminated: bool,
    dead: bool,
}

fn word(w: &[char]) -> String {
    let n = w.len();
    let mut out = String::with_capacity(n * 2);
    let mut geminate_next = false;
    let mut i = 0;
    while i < n {
        let c = w[i];
        if consonant(c).is_some() {
            let mut cluster_chars: Vec<char> = vec![c];
            let mut j = i + 1;
            while j + 1 < n && w[j] == HASANTA && consonant(w[j + 1]).is_some() {
                cluster_chars.push(w[j + 1]);
                j += 2;
            }
            let dangling = j < n && w[j] == HASANTA;
            if dangling {
                j += 1;
            }
            let initial = out.is_empty();
            let roman = cluster(&cluster_chars, initial);
            let mut text = roman.text.clone();
            if geminate_next {
                let first = text.chars().next().unwrap();
                text.insert(0, first);
                geminate_next = false;
            }
            out.push_str(&text);
            let next = w.get(j).copied();
            if let Some(m) = next.and_then(matra) {
                out.push_str(m);
                j += 1;
            } else if dangling || roman.dead {
            } else {
                let strong =
                    cluster_chars.len() > 1 || roman.geminated || text.len() > roman.text.len();
                let keep = match next {
                    None => strong || KEEP_FINAL_O.contains(cluster_chars.last().unwrap()),
                    Some(nx) if consonant(nx).is_some() => {
                        strong
                            || initial
                            || nx == 'হ'
                            || w.get(j + 1).copied().and_then(matra).is_none()
                    }
                    Some(_) => true,
                };
                if keep {
                    out.push_str(if roman.y_phala { "a" } else { "o" });
                }
            }
            i = j;
        } else if let Some(v) = vowel(c) {
            out.push_str(v);
            i += 1;
        } else if let Some(m) = matra(c) {
            out.push_str(m);
            i += 1;
        } else if c == ANUSVARA {
            out.push_str("ng");
            i += 1;
        } else if c == CANDRABINDU {
            out.push('n');
            i += 1;
        } else if c == VISARGA {
            if i == n - 1 {
                out.push('h');
            } else {
                geminate_next = true;
            }
            i += 1;
        } else if c == HASANTA {
            i += 1;
        } else if ('\u{9E6}'..='\u{9EF}').contains(&c) {
            out.push((b'0' + (c as u32 - 0x9E6) as u8) as char);
            i += 1;
        } else if c == '\u{964}' {
            out.push('.');
            i += 1;
        } else if c == '\u{965}' {
            out.push_str("..");
            i += 1;
        } else {
            out.push(c);
            i += 1;
        }
    }
    out
}

/// The Latin for one consonant cluster. `initial` is whether it opens the
/// word, which is where য-ফলা and ব-ফলা are glides rather than doublings.
fn cluster(chars: &[char], initial: bool) -> Cluster {
    let mk = |text: String, y_phala: bool, geminated: bool, dead: bool| Cluster {
        text,
        y_phala,
        geminated,
        dead,
    };
    if chars.len() == 1 {
        return mk(
            consonant(chars[0]).unwrap().to_string(),
            false,
            false,
            chars[0] == 'ৎ',
        );
    }
    if chars.len() == 2 && chars[0] == 'ক' && chars[1] == 'ষ' {
        return mk(
            if initial { "kh" } else { "kkh" }.to_string(),
            false,
            !initial,
            false,
        );
    }
    if chars.len() == 2 && chars[0] == 'জ' && chars[1] == 'ঞ' {
        return mk("gg".to_string(), false, true, false);
    }
    if chars.len() == 2 && chars[0] == 'ঙ' && chars[1] == 'গ' {
        return mk("ng".to_string(), false, false, false);
    }
    let last = *chars.last().unwrap();
    let base = &chars[..chars.len() - 1];
    if last == 'য' {
        if initial {
            return mk(format!("{}y", plain(base)), true, false, false);
        }
        if *base.last().unwrap() == 'র' {
            return mk(
                format!("{}rj", plain(&base[..base.len() - 1])),
                false,
                true,
                false,
            );
        }
        return mk(doubled(base), false, true, false);
    }
    if last == 'ব' && base[0] != 'ব' {
        if initial {
            return mk(format!("{}w", plain(base)), false, false, false);
        }
        if B_STAYS_B.contains(base.last().unwrap()) {
            return mk(format!("{}b", plain(base)), false, false, false);
        }
        return mk(doubled(base), false, true, false);
    }
    mk(plain(chars), false, false, false)
}

fn plain(chars: &[char]) -> String {
    chars.iter().map(|&c| consonant(c).unwrap()).collect()
}

/// `chars` with the last consonant's first Latin letter doubled: sh → ssh, n → nn.
fn doubled(chars: &[char]) -> String {
    let last_roman = consonant(*chars.last().unwrap()).unwrap();
    format!(
        "{}{}{}",
        plain(&chars[..chars.len() - 1]),
        &last_roman[..1],
        last_roman
    )
}

#[cfg(test)]
mod tests {
    //! Mirrors `BengaliRomanizerTest.kt`.

    use super::{normalize, romanize};

    fn check(pairs: &[(&str, &str)]) {
        for (bengali, latin) in pairs {
            assert_eq!(*latin, romanize(bengali), "{bengali}");
        }
    }

    #[test]
    fn everyday_words() {
        check(&[
            ("কাল", "kal"),
            ("ভালো", "bhalo"),
            ("আমার", "amar"),
            ("তোমার", "tomar"),
            ("কী", "ki"),
            ("কি", "ki"),
            ("বাংলা", "bangla"),
            ("ধন্যবাদ", "dhonnobad"),
            ("শুভ", "shubho"),
            ("রাত", "rat"),
            ("কষ্ট", "koshto"),
            ("স্কুল", "skul"),
            ("বন্ধু", "bondhu"),
            ("বৃষ্টি", "brishti"),
            ("প\u{9DC}া", "pora"),
        ]);
    }

    #[test]
    fn sibilants_and_affricates() {
        check(&[
            ("সব", "sob"),
            ("শুধু", "shudhu"),
            ("ষাট", "shat"),
            ("চা", "cha"),
            ("ছবি", "chhobi"),
            ("আছি", "achhi"),
        ]);
    }

    #[test]
    fn inherent_vowel() {
        check(&[
            ("মন", "mon"),
            ("কলম", "kolom"),
            ("একটা", "ekta"),
            ("বলছি", "bolchhi"),
            ("কলকাতা", "kolkata"),
            ("কেমন", "kemon"),
            ("অনেক", "onek"),
            ("প্রথম", "prothom"),
            ("মানুষ", "manush"),
            ("বাংলাদেশ", "bangladesh"),
            ("ভাল", "bhal"),
            ("দেহ", "deho"),
            ("ব\u{9DC}", "boro"),
        ]);
    }

    #[test]
    fn clusters() {
        check(&[
            ("ধর্ম", "dhormo"),
            ("কর্ম", "kormo"),
            ("স্বপ্ন", "swopno"),
            ("সত্য", "sotto"),
            ("অন্য", "onno"),
            ("সমস্যা", "somossa"),
            ("ব্যাপার", "byapar"),
            ("ব্যবহার", "byabohar"),
            ("বিশ্ব", "bissho"),
            ("ক্ষমা", "khoma"),
            ("লক্ষ", "lokkho"),
            ("জ্ঞান", "ggan"),
            ("বঙ্গ", "bongo"),
            ("পাঞ্জাবি", "panjabi"),
            ("স্বাগতম", "swagotom"),
            ("হঠাৎ", "hothat"),
        ]);
    }

    #[test]
    fn vowels_and_glides() {
        check(&[
            ("হ\u{9DF}", "hoy"),
            ("মে\u{9DF}ে", "meye"),
            ("ভাই", "bhai"),
            ("বই", "boi"),
            ("দুই", "dui"),
            ("খাও\u{9DF}া", "khaoya"),
            ("ওকে!", "oke!"),
        ]);
    }

    #[test]
    fn signs() {
        check(&[
            ("চাঁদ", "chand"),
            ("রং", "rong"),
            ("দুঃখ", "dukkho"),
            ("কিংবা", "kingba"),
        ]);
    }

    #[test]
    fn digits_punctuation_and_passthrough() {
        check(&[
            ("২০২৪", "2024"),
            ("আমি ২টা বাজে আসি।", "ami 2ta baje asi."),
            ("hello আমি", "hello ami"),
            ("", ""),
            ("abc 123", "abc 123"),
        ]);
    }

    #[test]
    fn decomposed_nukta_reads_like_precomposed() {
        let decomposed = "প\u{9A1}\u{9BC}া";
        let precomposed = "প\u{9DC}া";
        assert_eq!("pora", romanize(decomposed));
        assert_eq!("pora", romanize(precomposed));
        assert_eq!("y", romanize("\u{9AF}\u{9BC}"));
        assert_eq!(precomposed, normalize(decomposed));
    }
}

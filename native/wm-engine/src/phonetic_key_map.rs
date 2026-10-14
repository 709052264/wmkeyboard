//! The key map a phonetic layout's settings page draws: which roman keys
//! type which letter. Reference only, nothing reads it to transliterate. It
//! lives beside the engines so the test can run every entry through the
//! engine it describes, which is what stops the two drifting apart. A port
//! of `PhoneticKeyMap.kt`.

/// When an entry only holds in some places.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Where {
    AfterConsonant,
    Elsewhere,
    BeforeConsonant,
    BeforeVowel,
    AfterT,
    BetweenConsonants,
    WordStart,
}

pub struct Entry {
    /// Every spelling that types `text`, the usual one first.
    pub keys: &'static [&'static str],
    /// What the keys write.
    pub text: &'static str,
    /// A vowel's form after a consonant; empty for the inherent vowel, which
    /// writes nothing there; `None` for anything that is not a vowel.
    pub sign: Option<&'static str>,
    /// The context `text` needs, when the same keys write something else elsewhere.
    pub where_: Option<Where>,
    /// A word that shows the entry at work, and its spelling in the script,
    /// exactly as the rules alone write it.
    pub example: Option<(&'static str, &'static str)>,
}

pub struct KeyMap {
    pub vowels: &'static [Entry],
    pub consonants: &'static [Entry],
    pub signs: &'static [Entry],
}

const fn e(keys: &'static [&'static str], text: &'static str) -> Entry {
    Entry {
        keys,
        text,
        sign: None,
        where_: None,
        example: None,
    }
}

const fn v(keys: &'static [&'static str], text: &'static str, sign: &'static str) -> Entry {
    Entry {
        keys,
        text,
        sign: Some(sign),
        where_: None,
        example: None,
    }
}

const fn s(
    keys: &'static [&'static str],
    text: &'static str,
    where_: Option<Where>,
    example: (&'static str, &'static str),
) -> Entry {
    Entry {
        keys,
        text,
        sign: None,
        where_,
        example: Some(example),
    }
}

/// The map for `lang_id`'s phonetic layout, or `None` when it has none.
pub fn for_language(lang_id: &str) -> Option<&'static KeyMap> {
    match lang_id {
        "bn" => Some(&AVRO),
        "hi" => Some(&HINDI),
        _ => None,
    }
}

/// The Avro engine's map. ড় ঢ় য় are escapes because the engine writes
/// their precomposed code points.
pub static AVRO: KeyMap = KeyMap {
    vowels: &[
        v(&["o"], "অ", ""),
        v(&["a", "A"], "আ", "\u{9BE}"),
        v(&["i"], "ই", "\u{9BF}"),
        v(&["I", "ee"], "ঈ", "\u{9C0}"),
        v(&["u", "oo"], "উ", "\u{9C1}"),
        v(&["U"], "ঊ", "\u{9C2}"),
        v(&["rri"], "ঋ", "\u{9C3}"),
        v(&["e", "E"], "এ", "\u{9C7}"),
        v(&["OI"], "ঐ", "\u{9C8}"),
        v(&["O"], "ও", "\u{9CB}"),
        v(&["OU"], "ঔ", "\u{9CC}"),
    ],
    consonants: &[
        e(&["k", "q"], "ক"),
        e(&["kh"], "খ"),
        e(&["g"], "গ"),
        e(&["gh"], "ঘ"),
        e(&["Ng"], "ঙ"),
        e(&["c"], "চ"),
        e(&["ch"], "ছ"),
        e(&["j"], "জ"),
        e(&["jh"], "ঝ"),
        e(&["NG"], "ঞ"),
        e(&["T"], "ট"),
        e(&["Th"], "ঠ"),
        e(&["D"], "ড"),
        e(&["Dh"], "ঢ"),
        e(&["N"], "ণ"),
        e(&["t"], "ত"),
        e(&["th"], "থ"),
        e(&["d"], "দ"),
        e(&["dh"], "ধ"),
        e(&["n"], "ন"),
        e(&["p"], "প"),
        e(&["ph", "f"], "ফ"),
        e(&["b"], "ব"),
        e(&["bh", "v"], "ভ"),
        e(&["m"], "ম"),
        e(&["z"], "য"),
        e(&["r"], "র"),
        e(&["l"], "ল"),
        e(&["sh", "S"], "শ"),
        e(&["Sh"], "ষ"),
        e(&["s"], "স"),
        e(&["h"], "হ"),
        e(&["R"], "\u{9DC}"),
        e(&["Rh"], "\u{9DD}"),
        e(&["Y"], "\u{9DF}"),
        e(&["J"], "\u{99C}\u{9BC}"),
        e(&["kkh", "kx"], "ক্ষ"),
        e(&["gg"], "জ্ঞ"),
        e(&["nj"], "ঞ্জ"),
    ],
    signs: &[
        s(
            &["y"],
            "\u{9CD}য",
            Some(Where::AfterConsonant),
            ("shyam", "শ্যাম"),
        ),
        s(
            &["y"],
            "ই\u{9DF}",
            Some(Where::WordStart),
            ("yes", "ই\u{9DF}েস"),
        ),
        s(
            &["y"],
            "\u{9DF}",
            Some(Where::Elsewhere),
            ("meye", "মে\u{9DF}ে"),
        ),
        s(
            &["w"],
            "\u{9CD}ব",
            Some(Where::AfterConsonant),
            ("swasthyo", "স্বাস্থ্য"),
        ),
        s(&["w"], "ও", Some(Where::Elsewhere), ("wasi", "ও\u{9DF}াসি")),
        s(
            &["rr"],
            "র\u{9CD}",
            Some(Where::BeforeConsonant),
            ("dhorrmo", "ধর্ম"),
        ),
        s(&["x"], "এক্স", Some(Where::WordStart), ("xre", "এক্সরে")),
        s(&["x"], "ক্স", Some(Where::Elsewhere), ("bax", "বাক্স")),
        s(&["aZ", "AZ"], "অ্যা", None, ("aZp", "অ্যাপ")),
        s(&["ng"], "ং", None, ("bangla", "বাংলা")),
        s(&["ng"], "ঙ্গ", Some(Where::BeforeVowel), ("songe", "সঙ্গে")),
        s(&["^", "qq", "cb"], "ঁ", None, ("ca^d", "চাঁদ")),
        s(&[":", "HH"], "ঃ", None, ("du:kho", "দুঃখ")),
        s(
            &[",,", "hs"],
            "\u{9CD}",
            Some(Where::AfterConsonant),
            ("m,,", "ম\u{9CD}"),
        ),
        s(&["TH"], "ৎ", None, ("hoThaTH", "হঠাৎ")),
        s(&["`"], "ৎ", Some(Where::AfterT), ("hoThat`", "হঠাৎ")),
        s(&["`"], "", Some(Where::BetweenConsonants), ("k`s", "কস")),
        e(&["."], "।"),
        e(&["$"], "৳"),
    ],
};

/// The Hindi engine's map.
pub static HINDI: KeyMap = KeyMap {
    vowels: &[
        v(&["a"], "अ", ""),
        v(&["aa", "A"], "आ", "\u{93E}"),
        v(&["i"], "इ", "\u{93F}"),
        v(&["ee", "ii", "I"], "ई", "\u{940}"),
        v(&["u"], "उ", "\u{941}"),
        v(&["oo", "uu", "U"], "ऊ", "\u{942}"),
        v(&["e"], "ए", "\u{947}"),
        v(&["ai"], "ऐ", "\u{948}"),
        v(&["o"], "ओ", "\u{94B}"),
        v(&["au", "ou"], "औ", "\u{94C}"),
        v(&["RRi"], "ऋ", "\u{943}"),
    ],
    consonants: &[
        e(&["k", "q"], "क"),
        e(&["kh"], "ख"),
        e(&["g"], "ग"),
        e(&["gh"], "घ"),
        e(&["ch", "c"], "च"),
        e(&["chh"], "छ"),
        e(&["j"], "ज"),
        e(&["jh"], "झ"),
        e(&["T"], "ट"),
        e(&["Th"], "ठ"),
        e(&["D"], "ड"),
        e(&["Dh"], "ढ"),
        e(&["N"], "ण"),
        e(&["t"], "त"),
        e(&["th"], "थ"),
        e(&["d"], "द"),
        e(&["dh"], "ध"),
        e(&["n"], "न"),
        e(&["p"], "प"),
        e(&["ph", "f"], "फ"),
        e(&["b"], "ब"),
        e(&["bh"], "भ"),
        e(&["m"], "म"),
        e(&["y"], "य"),
        e(&["r"], "र"),
        e(&["l"], "ल"),
        e(&["v", "w"], "व"),
        e(&["sh"], "श"),
        e(&["Sh"], "ष"),
        e(&["s"], "स"),
        e(&["h"], "ह"),
        e(&["z"], "ज\u{93C}"),
        e(&["R"], "ड\u{93C}"),
        e(&["Rh"], "ढ\u{93C}"),
        e(&["ksh"], "क्ष"),
        e(&["gy", "jn"], "ज्ञ"),
        e(&["x"], "क्स"),
    ],
    signs: &[
        s(&["M"], "\u{902}", None, ("saMsaar", "संसार")),
        s(
            &["n", "m"],
            "\u{902}",
            Some(Where::BeforeConsonant),
            ("hindi", "हिंदी"),
        ),
        s(&["H"], "\u{903}", None, ("duHkh", "दुःख")),
    ],
};

#[cfg(test)]
mod tests {
    //! Mirrors `PhoneticKeyMapTest.kt`: every line of the key maps, run
    //! through the engine it describes.

    use super::{for_language, KeyMap, AVRO, HINDI};
    use crate::{avro, hindi};

    fn check(map: &KeyMap, consonant: &str, t: fn(&str) -> String) {
        for entry in map.vowels {
            for key in entry.keys {
                // Followed by a consonant, because Hindi lengthens a word-final
                // short vowel and the map shows the vowel itself.
                assert_eq!(
                    format!("{}{}", entry.text, t(consonant)),
                    t(&format!("{key}{consonant}")),
                    "vowel {key}"
                );
                assert_eq!(
                    format!("{}{}{}", t(consonant), entry.sign.unwrap(), t(consonant)),
                    t(&format!("{consonant}{key}{consonant}")),
                    "vowel sign {key}"
                );
            }
        }
        for entry in map.consonants {
            for key in entry.keys {
                assert_eq!(entry.text, t(key), "consonant {key}");
            }
        }
        for entry in map.signs {
            match entry.example {
                Some((roman, script)) => assert_eq!(script, t(roman), "example {roman}"),
                None => {
                    for key in entry.keys {
                        assert_eq!(entry.text, t(key), "sign {key}");
                    }
                }
            }
        }
    }

    #[test]
    fn avro_map_matches_avro() {
        check(&AVRO, "k", avro::transliterate);
    }

    #[test]
    fn hindi_map_matches_hindi_phonetic() {
        check(&HINDI, "p", hindi::transliterate);
    }

    #[test]
    fn every_spelling_of_an_example_works() {
        // The example only spells one of an entry's keys; the rest must write
        // the same word.
        for entry in AVRO.signs {
            let Some((roman, script)) = entry.example else {
                continue;
            };
            let first = entry.keys[0];
            if !roman.contains(first) {
                continue;
            }
            for key in &entry.keys[1..] {
                assert_eq!(
                    script,
                    avro::transliterate(&roman.replace(first, key)),
                    "{:?} as {key}",
                    entry.keys
                );
            }
        }
    }

    #[test]
    fn maps_by_language() {
        assert!(for_language("bn").is_some());
        assert!(for_language("hi").is_some());
        assert!(for_language("ta").is_none());
    }
}

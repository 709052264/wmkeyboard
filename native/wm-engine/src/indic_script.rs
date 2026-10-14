//! The Indic scripts the phonetic engine writes, and the move between each
//! of them and Devanagari. A port of `IndicScripts.kt`.
//!
//! Every Unicode block from Devanagari to Malayalam is laid out in parallel
//! (the ISCII heritage): क is U+0915, ক U+0995, ਕ U+0A15, ક U+0A95, କ U+0B15,
//! க U+0B95, క U+0C15, ಕ U+0C95, ക U+0D15, the same letter 0x80 apart each
//! time. So the phonetic engine spells every word in Devanagari, and
//! [`IndicScript::from_devanagari`] shifts it into the target block and then
//! applies what that script does differently: Tamil's missing voiced and
//! aspirated letters, Malayalam's chillus, Gurmukhi's addak and tippi, Odia's
//! ୟ and ୱ. [`IndicScript::to_devanagari`] goes the other way, so the word
//! lists of all of them can be folded by one Devanagari index and romanized
//! by one romanizer.

const DEV_START: u32 = 0x0900;
const DEV_END: u32 = 0x097F;
const VIRAMA: char = '\u{94D}';
const ANUSVARA: char = '\u{902}';
const NUKTA: char = '\u{93C}';
const DANDA: char = '\u{964}';
const DOUBLE_DANDA: char = '\u{965}';
const LLA: char = '\u{933}';
const LLLA: char = '\u{934}';
const SHORT_E: char = '\u{90E}';
const LONG_E: char = '\u{90F}';
const SHORT_O: char = '\u{912}';
const LONG_O: char = '\u{913}';
const SHORT_E_SIGN: char = '\u{946}';
const LONG_E_SIGN: char = '\u{947}';
const SHORT_O_SIGN: char = '\u{94A}';
const LONG_O_SIGN: char = '\u{94B}';
const ADDAK: char = '\u{A71}';
const TIPPI: char = '\u{A70}';
const BINDI: char = '\u{A02}';
const GURMUKHI_VIRAMA: char = '\u{A4D}';
const TAMIL_ALVEOLAR_N: char = '\u{929}';

/// ਰ and ਹ: the two letters Gurmukhi still subjoins.
const PAIRIN: [char; 2] = ['\u{930}', '\u{939}'];
/// ਨ ਮ ਣ: doubled, a tippi or bindi rather than an addak.
const NASALS: [char; 3] = ['\u{928}', '\u{92E}', '\u{923}'];
/// What a tippi follows: the inherent vowel, ਿ, ੁ, ੂ (ਤੂੰ, ਨੂੰ), ਅ and ਇ.
/// Everything else takes a bindi, ਉ included (ਕਿਉਂ, ਆਉਂਦਾ).
const SHORT_NASAL_VOWELS: [char; 5] = ['\u{93F}', '\u{941}', '\u{942}', '\u{905}', '\u{907}'];

fn is_consonant(c: char) -> bool {
    ('\u{915}'..='\u{939}').contains(&c)
}

fn in_devanagari(c: char) -> bool {
    (DEV_START..=DEV_END).contains(&(c as u32))
}

fn aspirate(c: char) -> Option<char> {
    Some(match c {
        'क' => 'ख',
        'ग' => 'घ',
        'च' => 'छ',
        'ज' => 'झ',
        'ट' => 'ठ',
        'ड' => 'ढ',
        'त' => 'थ',
        'द' => 'ध',
        'प' => 'फ',
        'ब' => 'भ',
        _ => return None,
    })
}

fn tamil_fold(c: char) -> Option<&'static str> {
    Some(match c {
        'ख' | 'ग' | 'घ' => "क",
        'छ' => "च",
        'झ' => "ज",
        'ठ' | 'ड' | 'ढ' => "ट",
        'थ' | 'द' | 'ध' => "त",
        'फ' | 'ब' | 'भ' => "प",
        'श' => "ष",
        'ऋ' => "रु",
        '\u{943}' => "\u{94D}रु",
        _ => return None,
    })
}

fn tamil_nasal_before(c: Option<char>) -> Option<char> {
    Some(match c? {
        'क' => 'ङ',
        'च' => 'ञ',
        'ट' => 'ण',
        'त' => 'न',
        'प' | 'म' => 'म',
        _ => return None,
    })
}

fn malayalam_chillu(c: char) -> Option<char> {
    Some(match c {
        'ण' => '\u{D7A}',
        'न' => '\u{D7B}',
        'र' => '\u{D7C}',
        'ल' => '\u{D7D}',
        'ळ' => '\u{D7E}',
        _ => return None,
    })
}

fn malayalam_back(c: char) -> Option<&'static str> {
    Some(match c {
        '\u{D7A}' => "ण्",
        '\u{D7B}' => "न्",
        '\u{D7C}' | '\u{D4E}' => "र्",
        '\u{D7D}' => "ल्",
        '\u{D7E}' => "ळ्",
        '\u{D7F}' => "क्",
        _ => return None,
    })
}

fn oriya_back(c: char) -> Option<&'static str> {
    Some(match c {
        '\u{B5F}' => "य",
        '\u{B71}' => "व",
        '\u{B5C}' => "\u{921}\u{93C}",
        '\u{B5D}' => "\u{922}\u{93C}",
        _ => return None,
    })
}

#[derive(Clone, Copy, PartialEq, Eq, Debug, Hash)]
pub enum IndicScript {
    Devanagari,
    Bengali,
    Gurmukhi,
    Gujarati,
    Oriya,
    Tamil,
    Telugu,
    Kannada,
    Malayalam,
}

impl IndicScript {
    pub const ALL: [IndicScript; 9] = [
        IndicScript::Devanagari,
        IndicScript::Bengali,
        IndicScript::Gurmukhi,
        IndicScript::Gujarati,
        IndicScript::Oriya,
        IndicScript::Tamil,
        IndicScript::Telugu,
        IndicScript::Kannada,
        IndicScript::Malayalam,
    ];

    fn offset(self) -> u32 {
        match self {
            IndicScript::Devanagari => 0x000,
            IndicScript::Bengali => 0x080,
            IndicScript::Gurmukhi => 0x100,
            IndicScript::Gujarati => 0x180,
            IndicScript::Oriya => 0x200,
            IndicScript::Tamil => 0x280,
            IndicScript::Telugu => 0x300,
            IndicScript::Kannada => 0x380,
            IndicScript::Malayalam => 0x400,
        }
    }

    /// Whether `c` is in this script's Unicode block.
    pub fn in_block(self, c: char) -> bool {
        (DEV_START + self.offset()..=DEV_END + self.offset()).contains(&(c as u32))
    }

    /// A letter or sign of this script: not its digits, not the dandas.
    pub fn is_native(self, c: char) -> bool {
        if !self.in_block(c) {
            return false;
        }
        let code = c as u32 - self.offset();
        !(0x0966..=0x096F).contains(&code) && code != 0x0964 && code != 0x0965
    }

    fn shift_char(self, c: char) -> char {
        if in_devanagari(c) && c != DANDA && c != DOUBLE_DANDA {
            char::from_u32(c as u32 + self.offset()).unwrap_or(c)
        } else {
            c
        }
    }

    fn shift(self, dev: &str) -> String {
        if self.offset() == 0 {
            return dev.to_string();
        }
        dev.chars().map(|c| self.shift_char(c)).collect()
    }

    /// Devanagari, as the phonetic engine spells it, moved into this script.
    pub fn from_devanagari(self, dev: &str) -> String {
        match self {
            IndicScript::Devanagari => dev.to_string(),
            IndicScript::Tamil => self.to_tamil(dev),
            IndicScript::Malayalam => self.to_malayalam(dev),
            IndicScript::Gurmukhi => self.to_gurmukhi(dev),
            IndicScript::Oriya => self.to_oriya(dev),
            IndicScript::Gujarati => self.shift(&fold_short_eo(dev)),
            // ऴ has no letter in either: it is ळ there.
            IndicScript::Telugu | IndicScript::Kannada => {
                let nukta = char::from_u32(NUKTA as u32 + self.offset()).unwrap();
                self.shift(&dev.replace(LLLA, &LLA.to_string()))
                    .chars()
                    .filter(|&c| c != nukta)
                    .collect()
            }
            IndicScript::Bengali => self.shift(dev),
        }
    }

    /// Text in this script spelled in Devanagari, letter for letter, with
    /// this script's own conventions undone: a chillu becomes its consonant
    /// and a virama, an addak the consonant written twice, a tippi an
    /// anusvara.
    pub fn to_devanagari(self, text: &str) -> String {
        if self == IndicScript::Devanagari {
            return text.to_string();
        }
        let chars: Vec<char> = text.chars().collect();
        let mut out = String::with_capacity(text.len() + 4);
        for (i, &c) in chars.iter().enumerate() {
            let special = match self {
                IndicScript::Gurmukhi => self.gurmukhi_to_devanagari(&chars, i, &mut out),
                IndicScript::Oriya => oriya_back(c).map(|s| out.push_str(s)).is_some(),
                IndicScript::Malayalam => malayalam_back(c).map(|s| out.push_str(s)).is_some(),
                IndicScript::Kannada if c == '\u{CDE}' => {
                    out.push(LLLA);
                    true
                }
                _ => false,
            };
            if !special {
                out.push(if self.in_block(c) {
                    char::from_u32(c as u32 - self.offset()).unwrap_or(c)
                } else {
                    c
                });
            }
        }
        out
    }

    /// Tamil has no voiced or aspirated stops of its own, so those fold onto
    /// the unvoiced letter, which Tamil reads by position; it writes no
    /// anusvara, so a nasal is spelled out homorganic with what follows it;
    /// and it has no nukta.
    fn to_tamil(self, dev: &str) -> String {
        let mut folded = String::with_capacity(dev.len());
        for c in dev.chars() {
            if c == NUKTA {
                continue;
            }
            match tamil_fold(c) {
                Some(s) => folded.push_str(s),
                None => folded.push(c),
            }
        }
        let folded: Vec<char> = folded.chars().collect();
        let mut out = String::with_capacity(dev.len() + 8);
        for (i, &c) in folded.iter().enumerate() {
            if c == ANUSVARA {
                out.push(
                    tamil_nasal_before(folded.get(i + 1).copied()).unwrap_or(TAMIL_ALVEOLAR_N),
                );
                out.push(VIRAMA);
            } else {
                out.push(c);
            }
        }
        self.shift(&out)
    }

    /// A consonant closing a word becomes its chillu, where Malayalam has one.
    fn to_malayalam(self, dev: &str) -> String {
        let chars: Vec<char> = dev.chars().collect();
        let mut out = String::with_capacity(dev.len());
        let mut i = 0;
        while i < chars.len() {
            let c = chars[i];
            let after = chars.get(i + 2).copied();
            if let Some(chillu) = malayalam_chillu(c) {
                if chars.get(i + 1) == Some(&VIRAMA) && after.is_none_or(|a| !in_devanagari(a)) {
                    out.push(chillu);
                    i += 2;
                    continue;
                }
            }
            if c != NUKTA {
                out.push(self.shift_char(c));
            }
            i += 1;
        }
        out
    }

    /// Gurmukhi writes no conjuncts beyond the subjoined ra and ha (pairin): a
    /// doubled consonant is the addak before it, any other cluster loses its
    /// virama, and a word-final virama is not written. The anusvara is a tippi
    /// after a short vowel or a bare consonant and a bindi after a long one.
    fn to_gurmukhi(self, dev_in: &str) -> String {
        let dev: Vec<char> = dev_in
            .replace("\u{921}\u{93C}", "\u{A5C}")
            .replace("\u{922}\u{93C}", "\u{A5C}")
            .chars()
            .collect();
        let mut out = String::with_capacity(dev_in.len());
        let short_before = |i: usize| -> bool {
            i >= 1 && {
                let before = dev[i - 1];
                is_consonant(before) || SHORT_NASAL_VOWELS.contains(&before)
            }
        };
        for i in 0..dev.len() {
            let c = dev[i];
            let next = dev.get(i + 1).copied();
            if c == VIRAMA {
                let prev = if i >= 1 { Some(dev[i - 1]) } else { None };
                match next {
                    Some(n) if is_consonant(n) => {
                        if Some(n) == prev && prev.is_some_and(|p| NASALS.contains(&p)) {
                            // A doubled nasal is a tippi (a bindi after a long
                            // vowel), not an addak: ਕੰਮ, ਕਿੰਨਾ.
                            out.pop();
                            out.push(if short_before(i - 1) { TIPPI } else { BINDI });
                        } else if Some(n) == prev || prev.and_then(aspirate) == Some(n) {
                            // ਪੱਕਾ is ਪ ੱ ਕ ਾ: the addak, and one ਕ.
                            out.pop();
                            out.push(ADDAK);
                        } else if PAIRIN.contains(&n) {
                            out.push(GURMUKHI_VIRAMA);
                        }
                    }
                    _ => {}
                }
            } else if c == ANUSVARA {
                out.push(if short_before(i) { TIPPI } else { BINDI });
            } else {
                out.push(match c {
                    'ष' => '\u{A36}',
                    SHORT_E | LONG_E => '\u{A0F}',
                    SHORT_O | LONG_O => '\u{A13}',
                    SHORT_E_SIGN => '\u{A47}',
                    SHORT_O_SIGN => '\u{A4B}',
                    _ => self.shift_char(c),
                });
            }
        }
        out
    }

    /// Odia writes ଯ where a word opens (said "j" there) and ୟ elsewhere; w is ୱ.
    fn to_oriya(self, dev: &str) -> String {
        let chars: Vec<char> = dev.chars().collect();
        let mut out = String::with_capacity(dev.len());
        for (i, &c) in chars.iter().enumerate() {
            match c {
                'य' => {
                    let opening = i == 0 || !in_devanagari(chars[i - 1]);
                    out.push(if opening { '\u{B2F}' } else { '\u{B5F}' });
                }
                'व' => out.push('\u{B71}'),
                SHORT_E => out.push('\u{B0F}'),
                SHORT_O => out.push('\u{B13}'),
                SHORT_E_SIGN => out.push('\u{B47}'),
                SHORT_O_SIGN => out.push('\u{B4B}'),
                _ => out.push(self.shift_char(c)),
            }
        }
        out.replace("\u{B21}\u{B3C}", "\u{B5C}")
            .replace("\u{B22}\u{B3C}", "\u{B5D}")
    }

    /// Gurmukhi's own signs, read back; true when `text` at `i` was one of them.
    fn gurmukhi_to_devanagari(self, text: &[char], i: usize, out: &mut String) -> bool {
        match text[i] {
            TIPPI | BINDI => out.push(ANUSVARA),
            ADDAK => {
                // The letter it doubles comes next: write it, and a virama, now.
                let Some(&next) = text.get(i + 1) else {
                    return true;
                };
                if self.in_block(next) {
                    out.push(char::from_u32(next as u32 - self.offset()).unwrap());
                    out.push(VIRAMA);
                }
            }
            '\u{A59}' => out.push_str("ख\u{93C}"),
            '\u{A5A}' => out.push_str("ग\u{93C}"),
            '\u{A5B}' => out.push_str("ज\u{93C}"),
            '\u{A5C}' => out.push_str("ड\u{93C}"),
            '\u{A5E}' => out.push_str("फ\u{93C}"),
            c => return c == '\u{A3C}', // a separate nukta: dropped
        }
        true
    }
}

fn fold_short_eo(dev: &str) -> String {
    dev.chars()
        .map(|c| match c {
            SHORT_E => LONG_E,
            SHORT_O => LONG_O,
            SHORT_E_SIGN => LONG_E_SIGN,
            SHORT_O_SIGN => LONG_O_SIGN,
            c => c,
        })
        .collect()
}

#[cfg(test)]
mod tests {
    //! Mirrors `IndicScriptsTest.kt`.

    use super::IndicScript::*;

    #[test]
    fn tamil_folds_voiced_stops_and_spells_the_anusvara_out() {
        assert_eq!("கங்கா", Tamil.from_devanagari("गंगा"));
        assert_eq!("ஹிந்தீ", Tamil.from_devanagari("हिंदी"));
    }

    #[test]
    fn malayalam_closes_on_a_chillu() {
        assert_eq!("അവൻ", Malayalam.from_devanagari("अवन्"));
        assert_eq!("ന്ന", Malayalam.from_devanagari("न्न"));
        assert_eq!("अवन्", Malayalam.to_devanagari("അവൻ"));
    }

    #[test]
    fn gurmukhi_writes_addak_tippi_and_no_conjuncts() {
        assert_eq!("ਪੱਕਾ", Gurmukhi.from_devanagari("पक्का"));
        assert_eq!("ਕੰਮ", Gurmukhi.from_devanagari("कम्म"));
        assert_eq!("ਨੂੰ", Gurmukhi.from_devanagari("नूं"));
        assert_eq!("ਸਵਾਲ", Gurmukhi.from_devanagari("स्वाल"));
        assert_eq!("पक्का", Gurmukhi.to_devanagari("ਪੱਕਾ"));
        assert_eq!("कंम", Gurmukhi.to_devanagari("ਕੰਮ"));
    }

    #[test]
    fn odia_writes_ya_by_position() {
        assert_eq!("ଯୋଗ", Oriya.from_devanagari("योग"));
        assert_eq!("ଜୟ", Oriya.from_devanagari("जय"));
        assert_eq!("जय", Oriya.to_devanagari("ଜୟ"));
    }

    #[test]
    fn the_others_are_a_shift() {
        assert_eq!("કેમ", Gujarati.from_devanagari("केम"));
        assert_eq!("నేను", Telugu.from_devanagari("नेनु"));
        assert_eq!("ನಾನು", Kannada.from_devanagari("नानु"));
        assert_eq!("नानु", Kannada.to_devanagari("ನಾನು"));
        assert_eq!("abc 12", Telugu.from_devanagari("abc 12"));
    }

    #[test]
    fn native_means_letters_not_digits_or_dandas() {
        assert!(Tamil.is_native('க'));
        assert!(!Tamil.is_native('௧'));
        assert!(!Tamil.is_native('क'));
        assert!(!Devanagari.is_native('।'));
    }
}

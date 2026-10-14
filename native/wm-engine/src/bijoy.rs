//! Unicode Bengali to "ANSI" Bengali: the encoding Bijoy Bayanno and the
//! SutonnyMJ family of fonts used before Unicode, where a Bengali glyph sits
//! on a Latin code point and the text only reads as Bengali in one of those
//! fonts. আমার সোনার বাংলা is `Avgvi †mvbvi evsjv`. A port of `BijoyAnsi.kt`.
//!
//! A port of okkhor52.com's converters, one per [`Version`]: the same three
//! steps in the same order, with the tables in [`crate::bijoy_tables`]
//! generated from the converters' own scripts.
//!
//! 1. The two-part vowel signs ো and ৌ are split into ে plus their second half.
//! 2. The text is put in visual order: a vowel sign drawn to the left of its
//!    consonant cluster (ি ে ৈ) moves in front of the whole cluster, and a
//!    reph (র্) moves behind the cluster it sits on.
//! 3. Clusters become glyphs, longest first, and a list of fix-ups picks the
//!    glyph variant each context wants.
//!
//! The fix-ups read the character in front of the text too (`before`),
//! because a word's first glyph depends on it: after a space, ে is `†`,
//! after a letter it is `‡`.

use std::sync::OnceLock;

use crate::bijoy_tables as tables;

/// The ANSI encoding to write; each is a different font's layout.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Hash)]
pub enum Version {
    V1,
    V2,
    V3,
}

impl Version {
    /// The most common one, and what the converters open on.
    pub const DEFAULT: Version = Version::V2;

    pub fn number(self) -> u32 {
        match self {
            Version::V1 => 1,
            Version::V2 => 2,
            Version::V3 => 3,
        }
    }

    /// The version numbered `number`, or the default for one this build does not know.
    pub fn of(number: u32) -> Version {
        match number {
            1 => Version::V1,
            2 => Version::V2,
            3 => Version::V3,
            _ => Version::DEFAULT,
        }
    }
}

struct Tables {
    pre: Vec<(&'static str, &'static str)>,
    map: Vec<(&'static str, &'static str)>,
    fix: Vec<(&'static str, &'static str)>,
    lead: Vec<(&'static str, &'static str)>,
}

fn pairs(flat: &'static [&'static str]) -> Vec<(&'static str, &'static str)> {
    flat.chunks(2).map(|pair| (pair[0], pair[1])).collect()
}

fn tables(version: Version) -> &'static Tables {
    static TABLES: OnceLock<[Tables; 3]> = OnceLock::new();
    let all = TABLES.get_or_init(|| {
        [
            Tables {
                pre: pairs(tables::PRE_V1),
                map: pairs(tables::MAP_V1),
                fix: pairs(tables::FIX_V1),
                lead: pairs(tables::LEAD_V1),
            },
            Tables {
                pre: pairs(tables::PRE_V2),
                map: pairs(tables::MAP_V2),
                fix: pairs(tables::FIX_V2),
                lead: pairs(tables::LEAD_V2),
            },
            Tables {
                pre: pairs(tables::PRE_V3),
                map: pairs(tables::MAP_V3),
                fix: pairs(tables::FIX_V3),
                lead: pairs(tables::LEAD_V3),
            },
        ]
    });
    &all[(version.number() - 1) as usize]
}

/// Whether `c` is one of the characters `needs_conversion` looks for: a
/// Bengali letter, sign or digit, the danda, or a curly quote (the fonts
/// keep glyphs of their own on those code points, so a quote has to move too).
pub fn converts(c: char) -> bool {
    ('\u{980}'..='\u{9FF}').contains(&c)
        || c == '\u{964}'
        || c == '\u{965}'
        || ('\u{2018}'..='\u{2019}').contains(&c)
        || ('\u{201C}'..='\u{201D}').contains(&c)
}

/// Whether `text` holds anything the converter changes.
pub fn needs_conversion(text: &str) -> bool {
    text.chars().any(converts)
}

/// `text` in ANSI `version`. `before` is what sits in front of it in the
/// field, of which only the last character is read; empty means `text`
/// starts the field. Text with nothing Bengali in it comes back as it was.
pub fn convert(text: &str, version: Version, before: &str) -> String {
    if !needs_conversion(text) {
        return text.to_string();
    }
    let t = tables(version);
    let mut s = text.to_string();
    for &(from, to) in &t.pre {
        s = s.replace(from, to);
    }
    s = rearrange(&s);
    for &(from, to) in &t.map {
        s = s.replace(from, to);
    }
    if before.is_empty() {
        s = fix(&s, t);
        // Only at the very start, as the converters do it: they look at the
        // first character of the whole text, and replace the first match.
        let first = s.chars().next();
        if let Some(&(from, to)) = t.lead.iter().find(|(from, _)| from.chars().next() == first) {
            s = s.replacen(from, to, 1);
        }
        return s;
    }
    let context = before.chars().last().unwrap().to_string();
    let fixed = fix(&format!("{context}{s}"), t);
    // A fix-up that rewrote the character in front as well cannot be kept:
    // that character is already in the field. The text is then fixed alone.
    match fixed.strip_prefix(context.as_str()) {
        Some(rest) => rest.to_string(),
        None => fix(&s, t),
    }
}

fn fix(text: &str, t: &Tables) -> String {
    let mut s = text.to_string();
    for &(from, to) in &t.fix {
        s = s.replace(from, to);
    }
    s
}

const HASANT: char = '\u{9CD}';
const RA: char = '\u{9B0}';

fn is_pre_kar(c: Option<char>) -> bool {
    matches!(c, Some('\u{9BF}') | Some('\u{9C7}') | Some('\u{9C8}'))
}

fn is_hasant(c: Option<char>) -> bool {
    c == Some(HASANT)
}

/// The converters' consonant test, the three nasal and visarga signs included.
fn is_consonant(c: Option<char>) -> bool {
    match c {
        Some(c) => {
            ('\u{981}'..='\u{983}').contains(&c)
                || ('\u{995}'..='\u{9A8}').contains(&c)
                || ('\u{9AA}'..='\u{9B0}').contains(&c)
                || c == '\u{9B2}'
                || ('\u{9B6}'..='\u{9B9}').contains(&c)
                || c == '\u{9CE}'
                || c == '\u{9DF}'
        }
        None => false,
    }
}

/// Visual order, step 2 of `convert`. A line-for-line port of the
/// converters' `ReArrangeUnicodeText`, JavaScript string semantics included:
/// a read past either end is no character, and a substring's bounds are
/// clamped rather than thrown on.
pub fn rearrange(text: &str) -> String {
    let mut s: Vec<char> = text.chars().collect();
    let mut barrier: isize = 0;
    let mut i: isize = 0;
    while (i as usize) < s.len() {
        let at = |s: &Vec<char>, k: isize| -> Option<char> {
            if k < 0 {
                None
            } else {
                s.get(k as usize).copied()
            }
        };
        let sub = |s: &Vec<char>, from: isize, to: isize| -> Vec<char> {
            let len = s.len() as isize;
            let a = from.clamp(0, len) as usize;
            let b = to.clamp(0, len) as usize;
            if a <= b {
                s[a..b].to_vec()
            } else {
                s[b..a].to_vec()
            }
        };
        let len = s.len() as isize;
        if is_pre_kar(at(&s, i)) {
            let mut j: isize = 1;
            while is_consonant(at(&s, i - j)) {
                if i - j < 0 || i - j <= barrier {
                    break;
                }
                if is_hasant(at(&s, i - j - 1)) {
                    j += 2;
                } else {
                    break;
                }
            }
            let mut next = sub(&s, 0, i - j);
            next.push(s[i as usize]);
            next.extend(sub(&s, i - j, i));
            next.extend(sub(&s, i + 1, len));
            s = next;
            barrier = i + 1;
            i += 1;
            continue;
        }
        if i < len - 1
            && is_hasant(at(&s, i))
            && at(&s, i - 1) == Some(RA)
            && !is_hasant(at(&s, i - 2))
        {
            let mut j: isize = 1;
            let mut kar: isize = 0;
            loop {
                if is_consonant(at(&s, i + j)) && is_hasant(at(&s, i + j + 1)) {
                    j += 2;
                } else {
                    if is_consonant(at(&s, i + j)) && is_pre_kar(at(&s, i + j + 1)) {
                        kar = 1;
                    }
                    break;
                }
            }
            let mut next = sub(&s, 0, i - 1);
            next.extend(sub(&s, i + j + 1, i + j + kar + 1));
            next.extend(sub(&s, i + 1, i + j + 1));
            next.push(s[(i - 1) as usize]);
            next.push(s[i as usize]);
            next.extend(sub(&s, i + j + kar + 1, len));
            s = next;
            i += j + kar;
            barrier = i + 1;
            i += 1;
            continue;
        }
        i += 1;
    }
    s.into_iter().collect()
}

#[cfg(test)]
mod tests {
    //! Mirrors `BijoyAnsiTest.kt`: the expected strings are what okkhor52.com's
    //! scripts return for each sentence, one per version.

    use super::{convert, needs_conversion, Version};

    struct Case {
        unicode: &'static str,
        v1: &'static str,
        v2: &'static str,
        v3: &'static str,
    }

    const CASES: &[Case] = &[
        Case { unicode: "\u{986}\u{9AE}\u{9BE}\u{9B0} \u{9B8}\u{9CB}\u{9A8}\u{9BE}\u{9B0} \u{9AC}\u{9BE}\u{982}\u{9B2}\u{9BE}", v1: "Avgvi \u{2020}mvbvi evsjv", v2: "Avgvi \u{2020}mvbvi evsjv", v3: "xy\u{203A}y\u{EE}\u{FB} \u{F6}\u{A2}y\u{2DC}y\u{EE}\u{FB} \u{EE}y\u{201A}\u{153}y" },
        Case { unicode: "\u{986}\u{9AE}\u{9BF} \u{9A4}\u{9CB}\u{9AE}\u{9BE}\u{9AF}\u{9BC} \u{9AD}\u{9BE}\u{9B2}\u{9CB}\u{9AC}\u{9BE}\u{9B8}\u{9BF}\u{964}", v1: "Avwg \u{2020}Zvgvq fv\u{2021}jvevwm|", v2: "Avwg \u{2020}Zvgvq fv\u{2021}jvevwm|", v3: "xy!\u{203A} \u{F6}\u{201C}\u{FE}y\u{203A}y\u{EB}\u{FB} \u{A6}\u{FE}y\u{F6}\u{EC}\u{153}y\u{EE}y!\u{A2}\u{D0}" },
        Case { unicode: "\u{99A}\u{9BF}\u{9B0}\u{9A6}\u{9BF}\u{9A8} \u{9A4}\u{9CB}\u{9AE}\u{9BE}\u{9B0} \u{986}\u{995}\u{9BE}\u{9B6}, \u{9A4}\u{9CB}\u{9AE}\u{9BE}\u{9B0} \u{9AC}\u{9BE}\u{9A4}\u{9BE}\u{9B8}", v1: "wPiw`b \u{2020}Zvgvi AvKvk, \u{2020}Zvgvi evZvm", v2: "wPiw`b \u{2020}Zvgvi AvKvk, \u{2020}Zvgvi evZvm", v3: "!\u{2030}\u{FE}\u{EE}\u{FB}!\u{201D}\u{2DC} \u{F6}\u{201C}\u{FE}y\u{203A}y\u{EE}\u{FB} xy\u{201E}\u{FE}y\u{178}\u{2013} \u{F6}\u{201C}\u{FE}y\u{203A}y\u{EE}\u{FB} \u{EE}y\u{201C}\u{FE}y\u{A2}" },
        Case { unicode: "\u{995}\u{9B0}\u{9CD}\u{9AE} \u{9A7}\u{9B0}\u{9CD}\u{9AE} \u{9AE}\u{9B0}\u{9CD}\u{9AE}\u{9B0} \u{9B8}\u{9C2}\u{9B0}\u{9CD}\u{9AF}", v1: "Kg\u{A9} ag\u{A9} gg\u{A9}i m~h\u{A9}", v2: "Kg\u{A9} ag\u{A9} gg\u{A9}i m~h\u{A9}", v3: "\u{201E}\u{FE}\u{203A}\u{C5} \u{2022}\u{203A}\u{C5} \u{203A}\u{203A}\u{C5}\u{EE}\u{FB} \u{A2})\u{EB}\u{C5}" },
        Case { unicode: "\u{9B0}\u{9BE}\u{9B7}\u{9CD}\u{99F}\u{9CD}\u{9B0} \u{995}\u{9C3}\u{9B7}\u{9CD}\u{9A3} \u{995}\u{9CD}\u{9B7}\u{9AE}\u{9BE} \u{9B2}\u{995}\u{9CD}\u{9B7}\u{9CD}\u{9AE}\u{9C0}", v1: "iv\u{F3}\u{AA} K\u{2026}\u{F2} \u{B6}gv j\u{B2}x", v2: "iv\u{F3}\u{AA} K\u{2026}\u{F2} \u{FF}gv j\u{B2}x", v3: "\u{EE}\u{FB}y\u{DC}T\u{C9} \u{201E},\u{FE}\u{A1}\u{152} \u{C7}\u{FE}\u{203A}y \u{153}\"#" },
        Case { unicode: "\u{9AC}\u{9BF}\u{9A6}\u{9CD}\u{9AF}\u{9BE}\u{9B2}\u{9AF}\u{9BC} \u{9AA}\u{9CD}\u{9B0}\u{9A7}\u{9BE}\u{9A8} \u{9B6}\u{9BF}\u{995}\u{9CD}\u{9B7}\u{995}", v1: "we`\u{A8}vjq c\u{D6}avb wk\u{B6}K", v2: "we`\u{A8}vjq c\u{D6}avb wk\u{FF}K", v3: "!\u{EE}\u{201D}\u{C4}y\u{153}\u{EB}\u{FB} \u{B2}\u{CC}\u{2022}y\u{2DC} !\u{178}\u{C7}\u{FE}\u{201E}\u{FE}" },
        Case { unicode: "\u{995}\u{9CC}\u{9B6}\u{9B2} \u{9AE}\u{9CC}\u{9AE}\u{9BE}\u{99B}\u{9BF} \u{9AA}\u{9CC}\u{981}\u{99B}\u{9C7}", v1: "\u{2020}K\u{160}kj \u{2020}g\u{160}gvwQ \u{2020}c\u{160}u\u{2021}Q", v2: "\u{2020}K\u{160}kj \u{2020}g\u{160}gvwQ \u{2020}c\u{160}u\u{2021}Q", v3: "\u{F6}\u{201E}\u{FE}\u{EF}\u{178}\u{153} \u{F6}\u{203A}\u{EF}\u{203A}y!\u{160}\u{E9} \u{F6}\u{FE}\u{2122}\u{EF}\u{A4}\u{F6}\u{EC}\u{160}\u{E9}" },
        Case { unicode: "\u{9B9}\u{9A0}\u{9BE}\u{9CE} \u{9AC}\u{9C3}\u{9B7}\u{9CD}\u{99F}\u{9BF} \u{98F}\u{9B2}\u{9CB}", v1: "nVvr e\u{201E}w\u{F3} G\u{2021}jv", v2: "nVvr e\u{201E}w\u{F3} G\u{2021}jv", v3: "\u{A3}\u{E0}\u{FE}y\u{EA} \u{EE},!\u{DC}T ~\u{F6}\u{EC}\u{153}y" },
        Case { unicode: "\u{9A1}\u{9BC}\u{9A2}\u{9BC}\u{9AF}\u{9BC} \u{9AA}\u{9A1}\u{9BC}\u{9BE} \u{9AA}\u{9BE}\u{9B9}\u{9BE}\u{9A1}\u{9BC} \u{986}\u{9B7}\u{9BE}\u{9A2}\u{9BC} \u{9A8}\u{9AF}\u{9BC}\u{9A8}", v1: "opq cov cvnvo Avlvp bqb", v2: "opq cov cvnvo Avlvp bqb", v3: "v\u{FE}\u{FC}\u{2018}\u{FE}\u{FC}\u{EB}\u{FB} \u{FE}\u{2122}v\u{FE}\u{FC}y \u{FE}\u{2122}y\u{A3}yv\u{FE}\u{FC} xy\u{A1}\u{EC}y\u{2018}\u{FE}\u{FC} \u{2DC}\u{EB}\u{FB}\u{2DC}" },
        Case { unicode: "\u{98F}\u{995}\u{9C1}\u{9B6}\u{9C7} \u{9AB}\u{9C7}\u{9AC}\u{9CD}\u{9B0}\u{9C1}\u{9AF}\u{9BC}\u{9BE}\u{9B0}\u{9BF} \u{9E7}\u{9EF}\u{9EB}\u{9E8}", v1: "GKz\u{2021}k \u{2020}de\u{AA}\u{201C}qvwi 1952", v2: "GKz\u{2021}k \u{2020}de\u{AA}\u{E6}qvwi 1952", v3: "~\u{201E}%\u{FE}\u{F6}\u{EC}\u{178} \u{F6}\u{161}\u{FE}\u{EE}\u{CA}&\u{EB}\u{FB}y!\u{EE}\u{FB} 1952" },
        Case { unicode: "\u{201C}\u{9AC}\u{9BE}\u{982}\u{9B2}\u{9BE}\u{201D} \u{986}\u{9AE}\u{9BE}\u{9B0} \u{2018}\u{9AE}\u{9BE}\u{9A4}\u{9C3}\u{9AD}\u{9BE}\u{9B7}\u{9BE}\u{2019}", v1: "\u{D2}evsjv\u{D3} Avgvi \u{D4}gvZ\u{2026}fvlv\u{D5}", v2: "\u{D2}evsjv\u{D3} Avgvi \u{D4}gvZ\u{2026}fvlv\u{D5}", v3: "\u{201C}\u{EE}y\u{201A}\u{153}y\u{201D} xy\u{203A}y\u{EE}\u{FB} \u{F2}\u{203A}y\u{201C},\u{FE}\u{A6}\u{FE}y\u{A1}\u{EC}y\u{F3}" },
        Case { unicode: "\u{989}\u{99A}\u{9CD}\u{99B}\u{9CD}\u{9AC}\u{9BE}\u{9B8} \u{9B8}\u{9CD}\u{9AC}\u{9BE}\u{9B8}\u{9CD}\u{9A5}\u{9CD}\u{9AF} \u{9B8}\u{9BE}\u{9A8}\u{9CD}\u{9A4}\u{9CD}\u{9AC}\u{9A8}\u{9BE}", v1: "D\u{201D}Q\u{A1}vm \u{AF}^v\u{AF}\u{2019}\u{A8} mv\u{161}\u{2014}\u{A1}bv", v2: "D\u{201D}Q\u{A1}vm \u{AF}^v\u{AF}\u{2019}\u{A8} mv\u{161}\u{CD}\u{A1}bv", v3: "v\u{FE}zF\u{160}\u{B4}\u{E9}y\u{A2} \u{DF}\u{FE}\u{BA}y\u{DF}\u{FE}i\u{C4} \u{A2}ygs\u{FE}\u{2DC}y" },
        Case { unicode: "\u{99C}\u{9CD}\u{99E}\u{9BE}\u{9A8} \u{9AC}\u{9BF}\u{99C}\u{9CD}\u{99E}\u{9BE}\u{9A8} \u{9B8}\u{982}\u{99C}\u{9CD}\u{99E}\u{9BE}", v1: "\u{C1}vb we\u{C1}vb ms\u{C1}v", v2: "\u{C1}vb we\u{C1}vb ms\u{C1}v", v3: "K\u{FE}\u{E9}y\u{2DC} !\u{EE}K\u{FE}\u{E9}y\u{2DC} \u{A2}\u{201A}K\u{FE}\u{E9}y" },
        Case { unicode: "\u{98B}\u{9A4}\u{9C1} \u{98B}\u{9B7}\u{9BF} \u{997}\u{9C3}\u{9B9} \u{9B9}\u{9C3}\u{9A6}\u{9AF}\u{9BC} \u{9A4}\u{9C3}\u{9A3}", v1: "FZz Fwl M\u{201E}n \u{FC}`q Z\u{2026}Y", v2: "FZz Fwl M\u{201E}n \u{FC}`q Z\u{2026}Y", v3: "}\u{201C}%\u{FE} }!\u{A1}\u{EC} \u{2020},\u{A3} \u{A3}*\u{201D}\u{EB}\u{FB} \u{201C},\u{FE}\u{2019}" },
        Case { unicode: "\u{9B6}\u{9C1}\u{9AD} \u{997}\u{9C1}\u{9B0}\u{9C1} \u{9B0}\u{9C2}\u{9AA} \u{9B0}\u{9C1}\u{9AA}\u{9BE} \u{9B9}\u{9C1}\u{995}\u{9C1}\u{9AE}", v1: "\u{EF}f \u{B8}i\u{201C} i\u{192}c i\u{201C}cv \u{FB}Kzg", v2: "\u{EF}f \u{B8}i\u{E6} i\u{192}c i\u{E6}cv \u{FB}Kzg", v3: "\u{D6}\u{A6}\u{FE} =\u{EE}\u{FB}& \u{EE}\u{FB}*\u{FE}\u{2122} \u{EE}\u{FB}&\u{FE}\u{2122}y \u{FD}\u{201E}%\u{FE}\u{203A}" },
        Case { unicode: "\u{99A}\u{9BE}\u{981}\u{9A6} \u{995}\u{9BE}\u{981}\u{9A6}\u{9C7} \u{9AC}\u{9BE}\u{981}\u{9B6}\u{9BF}", v1: "Pvu` Kvu\u{2021}` evuwk", v2: "Pvu` Kvu\u{2021}` evuwk", v3: "\u{2030}\u{FE}y\u{A4}\u{201D} \u{201E}\u{FE}y\u{A4}\u{F6}\u{EC}\u{201D} \u{EE}y\u{A4}!\u{178}" },
        Case { unicode: "\u{9AF}\u{9C1}\u{995}\u{9CD}\u{9A4}\u{9B0}\u{9BE}\u{9B7}\u{9CD}\u{99F}\u{9CD}\u{9B0} \u{9B8}\u{9AE}\u{9CD}\u{9AA}\u{9CD}\u{9B0}\u{9A6}\u{9BE}\u{9AF}\u{9BC} \u{989}\u{99C}\u{9CD}\u{99C}\u{9CD}\u{9AC}\u{9B2}", v1: "hy\u{B3}iv\u{F3}\u{AA} m\u{A4}\u{FA}\u{D6}`vq D\u{BE}\u{A1}j", v2: "hy\u{B3}iv\u{F3}\u{AA} m\u{A4}\u{FA}\u{D6}`vq D\u{BE}\u{A1}j", v3: "\u{EB}%_\u{AB}\u{EE}\u{FB}y\u{DC}T\u{C9} \u{A2}\u{C1}\u{B1}\u{201D}y\u{EB}\u{FB} v\u{FE}zI\u{B5}\u{153}" },
        Case { unicode: "\u{995}\u{9BE}\u{9B0}\u{9CD}\u{9AF}\u{995}\u{9B0} \u{9AA}\u{9B0}\u{9CD}\u{9AF}\u{99F}\u{9A8} \u{986}\u{9B0}\u{9CD}\u{9AF}", v1: "Kvh\u{A9}Ki ch\u{A9}Ub Avh\u{A9}", v2: "Kvh\u{A9}Ki ch\u{A9}Ub Avh\u{A9}", v3: "\u{201E}\u{FE}y\u{EB}\u{C5}\u{201E}\u{FE}\u{EE}\u{FB} \u{FE}\u{2122}\u{EB}\u{C5}\u{DD}\u{FE}\u{2DC} xy\u{EB}\u{C5}" },
        Case { unicode: "\u{995}\u{9CD}\u{9AF}\u{9BE}\u{9AE}\u{9CD}\u{9AA} \u{9B0}\u{200C}\u{9CD}\u{9AF}\u{9BE}\u{9AC} \u{99F}\u{9CD}\u{9B0}\u{9C7}\u{9A8} \u{995}\u{9CD}\u{9B2}\u{9BE}\u{9B8}", v1: "K\u{A8}v\u{A4}\u{FA} i\u{A8}ve \u{2020}U\u{AA}b K\u{AC}vm", v2: "K\u{A8}v\u{A4}\u{FA} i\u{A8}ve \u{2020}U\u{AA}b K\u{AC}vm", v3: "\u{201E}\u{FE}\u{C4}y\u{C1}\u{2122} \u{EE}\u{FB}\u{C4}y\u{EE} \u{F6}\u{DD}\u{FE}\u{C6}\u{2DC} \u{201E}\u{CF}\u{FE}y\u{A2}" },
        Case { unicode: "\u{9A6}\u{9C1}\u{983}\u{996} \u{9A8}\u{9BF}\u{983}\u{9B6}\u{9CD}\u{9AC}\u{9BE}\u{9B8} \u{9F3}\u{9E7}\u{9E6}\u{9E6}", v1: "`ytL wbtk\u{A6}vm $100", v2: "`ytL wbtk\u{A6}vm $100", v3: "\u{201D}%\u{192}\u{2026} !\u{2DC}\u{192}\u{D9}\u{BB}y\u{A2} \u{9F3}100" },
        Case { unicode: "\u{995}\u{9C7}\u{9A8} \u{995}\u{9C8} \u{995}\u{9CB}\u{9A5}\u{9BE}\u{9AF}\u{9BC} \u{995}\u{9CC}\u{9A4}\u{9C1}\u{995}", v1: "\u{2020}Kb \u{2C6}K \u{2020}Kv_vq \u{2020}K\u{160}ZzK", v2: "\u{2020}Kb \u{2C6}K \u{2020}Kv_vq \u{2020}K\u{160}ZzK", v3: "\u{F6}\u{201E}\u{FE}\u{2DC} \u{F7}\u{201E}\u{FE} \u{F6}\u{201E}\u{FE}y\u{ED}y\u{EB}\u{FB} \u{F6}\u{201E}\u{FE}\u{EF}\u{201C}%\u{FE}\u{201E}\u{FE}" },
        Case { unicode: "\u{9B8}\u{9CD}\u{995}\u{9C1}\u{9B2} \u{9B8}\u{9CD}\u{99F}\u{9C7}\u{9B6}\u{9A8} \u{9B8}\u{9CD}\u{9A4}\u{9CD}\u{9B0}\u{9C0} \u{9A8}\u{9CD}\u{9AF}\u{9BE}\u{9AF}\u{9BC}", v1: "\u{AF}\u{2039}zj \u{2020}\u{F7}kb \u{AF}\u{BF}x b\u{A8}vq", v2: "\u{AF}\u{2039}zj \u{2020}\u{F7}kb \u{AF}\u{BF}x b\u{A8}vq", v3: "\u{DF}\u{FE}%\u{F1}\u{153} \u{F6}\u{DE}\u{DD}\u{FE}\u{178}\u{2DC} \u{DF}\u{FE}f# \u{2DC}\u{C4}y\u{EB}\u{FB}" },
        Case { unicode: "\u{999}\u{9CD}\u{997} \u{9AC}\u{999}\u{9CD}\u{997} \u{9B8}\u{999}\u{9CD}\u{998} \u{985}\u{999}\u{9CD}\u{995} \u{9B6}\u{999}\u{9CD}\u{996}", v1: "\u{BD} e\u{BD} m\u{2022}N A\u{BC} k\u{2022}L", v2: "\u{BD} e\u{BD} m\u{2022}N A\u{BC} k\u{2022}L", v3: "D \u{EE}D \u{A2}A\u{2021} xB\u{FE} \u{178}C" },
        Case { unicode: "\u{9A6}\u{9CD}\u{9AC}\u{9BF}\u{9A4}\u{9C0}\u{9AF}\u{9BC} \u{9A4}\u{9CD}\u{9B0}\u{9BF}\u{9B6} \u{9AE}\u{9C1}\u{995}\u{9CD}\u{9A4}\u{9BF}", v1: "w\u{D8}Zxq w\u{CE}k gyw\u{B3}", v2: "w\u{D8}Zxq w\u{CE}k gyw\u{B3}", v3: "!m\u{201C}\u{FE}#\u{EB}\u{FB} !e\u{178} \u{203A}%!_\u{AB}" },
        Case { unicode: "\u{9A8}\u{9A4}\u{9C1}\u{9A8} \u{9AC}\u{99B}\u{9B0} \u{9B6}\u{9C1}\u{9AD} \u{9B9}\u{9CB}\u{995}\u{965}", v1: "bZzb eQi \u{EF}f \u{2020}nvK\\", v2: "bZzb eQi \u{EF}f \u{2020}nvK\\", v3: "\u{2DC}\u{201C}%\u{FE}\u{2DC} \u{EE}\u{160}\u{E9}\u{EE}\u{FB} \u{D6}\u{A6}\u{FE} \u{F6}\u{A3}y\u{201E}\u{FE}\u{965}" },
        Case { unicode: "\u{9AB}\u{9C1}\u{9B2} \u{9AB}\u{9B2} \u{997}\u{9C1}\u{9B2}\u{9CD}\u{9AE} \u{9B6}\u{9C1}\u{9B2}\u{9CD}\u{995}", v1: "dzj dj \u{B8}j\u{A5} \u{EF}\u{E9}", v2: "dzj dj \u{B8}j\u{A5} \u{EF}\u{E9}", v3: "\u{161}%\u{FE}\u{153} \u{161}\u{FE}\u{153} =\u{CD}\u{C3} \u{D6}\u{CD}\u{F1}" },
    ];

    #[test]
    fn matches_the_converters_for_every_version() {
        for c in CASES {
            assert_eq!(
                c.v1,
                convert(c.unicode, Version::V1, ""),
                "{} V1",
                c.unicode
            );
            assert_eq!(
                c.v2,
                convert(c.unicode, Version::V2, ""),
                "{} V2",
                c.unicode
            );
            assert_eq!(
                c.v3,
                convert(c.unicode, Version::V3, ""),
                "{} V3",
                c.unicode
            );
        }
    }

    #[test]
    fn the_example_sentence() {
        assert_eq!("Av", convert("\u{986}", Version::DEFAULT, ""));
        assert_eq!(
            "Avgvi \u{2020}mvbvi evsjv",
            convert("\u{986}\u{9AE}\u{9BE}\u{9B0} \u{9B8}\u{9CB}\u{9A8}\u{9BE}\u{9B0} \u{9AC}\u{9BE}\u{982}\u{9B2}\u{9BE}", Version::DEFAULT, "")
        );
    }

    /// The keyboard converts a word at a time, as each one is committed,
    /// with what is already in the field in front of it. That has to come
    /// out the same as converting the sentence whole.
    #[test]
    fn word_by_word_matches_whole_sentence() {
        for c in CASES {
            for (version, whole) in [
                (Version::V1, c.v1),
                (Version::V2, c.v2),
                (Version::V3, c.v3),
            ] {
                let mut field = String::new();
                for (index, word) in c.unicode.split(' ').enumerate() {
                    if index > 0 {
                        let space = convert(" ", version, &field);
                        field.push_str(&space);
                    }
                    let converted = convert(word, version, &field);
                    field.push_str(&converted);
                }
                assert_eq!(whole, field, "{} {version:?}", c.unicode);
            }
        }
    }

    #[test]
    fn the_word_after_a_space_takes_the_word_start_glyph() {
        assert_eq!("\u{2020}K", convert("\u{995}\u{9C7}", Version::V2, " "));
        assert_eq!("\u{2021}K", convert("\u{995}\u{9C7}", Version::V2, "v"));
        assert_eq!("\u{2020}K", convert("\u{995}\u{9C7}", Version::V2, ""));
    }

    #[test]
    fn text_with_nothing_bengali_is_left_alone() {
        assert_eq!(
            "hello, world 123",
            convert("hello, world 123", Version::DEFAULT, "")
        );
        assert!(!needs_conversion("hello"));
        assert!(needs_conversion("\u{964}"));
    }

    #[test]
    fn unknown_version_number_falls_back_to_the_default() {
        assert_eq!(Version::V2, Version::of(9));
        assert_eq!(Version::V3, Version::of(3));
    }
}

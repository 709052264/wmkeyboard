//! Romaji ↔ kana (Hepburn/wāpuro, longest match), the 小゛゜ rings, and
//! katakana in both widths: the Kotlin `Kana`.

use std::collections::HashMap;
use std::sync::OnceLock;

/// One transduction step: the kana it produced and the romaji it ate.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct KanaSpan {
    pub kana: String,
    pub romaji_len: usize,
}

const TABLE: &[(&str, &str)] = &[
    ("a", "あ"),
    ("i", "い"),
    ("u", "う"),
    ("e", "え"),
    ("o", "お"),
    ("ka", "か"),
    ("ki", "き"),
    ("ku", "く"),
    ("ke", "け"),
    ("ko", "こ"),
    ("ga", "が"),
    ("gi", "ぎ"),
    ("gu", "ぐ"),
    ("ge", "げ"),
    ("go", "ご"),
    ("sa", "さ"),
    ("shi", "し"),
    ("si", "し"),
    ("su", "す"),
    ("se", "せ"),
    ("so", "そ"),
    ("za", "ざ"),
    ("ji", "じ"),
    ("zi", "じ"),
    ("zu", "ず"),
    ("ze", "ぜ"),
    ("zo", "ぞ"),
    ("ta", "た"),
    ("chi", "ち"),
    ("ti", "ち"),
    ("tsu", "つ"),
    ("tu", "つ"),
    ("te", "て"),
    ("to", "と"),
    ("da", "だ"),
    ("di", "ぢ"),
    ("du", "づ"),
    ("de", "で"),
    ("do", "ど"),
    ("na", "な"),
    ("ni", "に"),
    ("nu", "ぬ"),
    ("ne", "ね"),
    ("no", "の"),
    ("ha", "は"),
    ("hi", "ひ"),
    ("fu", "ふ"),
    ("hu", "ふ"),
    ("he", "へ"),
    ("ho", "ほ"),
    ("ba", "ば"),
    ("bi", "び"),
    ("bu", "ぶ"),
    ("be", "べ"),
    ("bo", "ぼ"),
    ("pa", "ぱ"),
    ("pi", "ぴ"),
    ("pu", "ぷ"),
    ("pe", "ぺ"),
    ("po", "ぽ"),
    ("ma", "ま"),
    ("mi", "み"),
    ("mu", "む"),
    ("me", "め"),
    ("mo", "も"),
    ("ya", "や"),
    ("yu", "ゆ"),
    ("yo", "よ"),
    ("ra", "ら"),
    ("ri", "り"),
    ("ru", "る"),
    ("re", "れ"),
    ("ro", "ろ"),
    ("wa", "わ"),
    ("wo", "を"),
    ("wi", "うぃ"),
    ("we", "うぇ"),
    ("vu", "ゔ"),
    ("kya", "きゃ"),
    ("kyu", "きゅ"),
    ("kyo", "きょ"),
    ("gya", "ぎゃ"),
    ("gyu", "ぎゅ"),
    ("gyo", "ぎょ"),
    ("sha", "しゃ"),
    ("shu", "しゅ"),
    ("sho", "しょ"),
    ("sya", "しゃ"),
    ("syu", "しゅ"),
    ("syo", "しょ"),
    ("ja", "じゃ"),
    ("ju", "じゅ"),
    ("jo", "じょ"),
    ("jya", "じゃ"),
    ("jyu", "じゅ"),
    ("jyo", "じょ"),
    ("zya", "じゃ"),
    ("zyu", "じゅ"),
    ("zyo", "じょ"),
    ("cha", "ちゃ"),
    ("chu", "ちゅ"),
    ("cho", "ちょ"),
    ("tya", "ちゃ"),
    ("tyu", "ちゅ"),
    ("tyo", "ちょ"),
    ("nya", "にゃ"),
    ("nyu", "にゅ"),
    ("nyo", "にょ"),
    ("hya", "ひゃ"),
    ("hyu", "ひゅ"),
    ("hyo", "ひょ"),
    ("bya", "びゃ"),
    ("byu", "びゅ"),
    ("byo", "びょ"),
    ("pya", "ぴゃ"),
    ("pyu", "ぴゅ"),
    ("pyo", "ぴょ"),
    ("mya", "みゃ"),
    ("myu", "みゅ"),
    ("myo", "みょ"),
    ("rya", "りゃ"),
    ("ryu", "りゅ"),
    ("ryo", "りょ"),
    ("fa", "ふぁ"),
    ("fi", "ふぃ"),
    ("fe", "ふぇ"),
    ("fo", "ふぉ"),
    ("tsa", "つぁ"),
    ("tsi", "つぃ"),
    ("tse", "つぇ"),
    ("tso", "つぉ"),
    ("che", "ちぇ"),
    ("she", "しぇ"),
    ("je", "じぇ"),
    ("xa", "ぁ"),
    ("xi", "ぃ"),
    ("xu", "ぅ"),
    ("xe", "ぇ"),
    ("xo", "ぉ"),
    ("la", "ぁ"),
    ("li", "ぃ"),
    ("lu", "ぅ"),
    ("le", "ぇ"),
    ("lo", "ぉ"),
    ("xtu", "っ"),
    ("ltu", "っ"),
    ("xya", "ゃ"),
    ("xyu", "ゅ"),
    ("xyo", "ょ"),
    ("-", "ー"),
];

fn table() -> &'static HashMap<&'static str, &'static str> {
    static T: OnceLock<HashMap<&str, &str>> = OnceLock::new();
    T.get_or_init(|| TABLE.iter().copied().collect())
}

/// The 小゛゜ rings, in hiragana: char i advances to char i + 1.
const VARIANT_CYCLES: [&str; 29] = [
    "あぁ",
    "いぃ",
    "うぅゔ",
    "えぇ",
    "おぉ",
    "かが",
    "きぎ",
    "くぐ",
    "けげ",
    "こご",
    "さざ",
    "しじ",
    "すず",
    "せぜ",
    "そぞ",
    "ただ",
    "ちぢ",
    "つっづ",
    "てで",
    "とど",
    "はばぱ",
    "ひびぴ",
    "ふぶぷ",
    "へべぺ",
    "ほぼぽ",
    "やゃ",
    "ゆゅ",
    "よょ",
    "わゎ",
];

/// Transduces romaji into kana units, each with the romaji it consumed; the
/// lengths always sum to the input's.
pub fn transduce(romaji: &str) -> Vec<KanaSpan> {
    let s: Vec<char> = romaji.to_lowercase().chars().collect();
    let mut out = Vec::new();
    let span = |kana: &str, len: usize| KanaSpan {
        kana: kana.to_string(),
        romaji_len: len,
    };
    let vowel_y = |c: Option<&char>| c.is_some_and(|c| "aeiouy".contains(*c));
    let mut i = 0;
    while i < s.len() {
        let c = s[i];
        // Sokuon: a doubled consonant becomes っ.
        if i + 1 < s.len() && c == s[i + 1] && !"aeioun".contains(c) && c.is_ascii_lowercase() {
            out.push(span("っ", 1));
            i += 1;
            continue;
        }
        if c == 'n' {
            if s.get(i + 1) == Some(&'\'') {
                out.push(span("ん", 2));
                i += 2;
                continue;
            }
            if s.get(i + 1) == Some(&'n') {
                if vowel_y(s.get(i + 2)) {
                    out.push(span("ん", 1));
                    i += 1;
                } else {
                    out.push(span("ん", 2));
                    i += 2;
                }
                continue;
            }
            if !vowel_y(s.get(i + 1)) {
                out.push(span("ん", 1));
                i += 1;
                continue;
            }
        }
        let mut matched = false;
        for len in (1..=3).rev() {
            if i + len <= s.len() {
                let key: String = s[i..i + len].iter().collect();
                if let Some(v) = table().get(key.as_str()) {
                    out.push(span(v, len));
                    i += len;
                    matched = true;
                    break;
                }
            }
        }
        if !matched {
            out.push(span(&c.to_string(), 1));
            i += 1;
        }
    }
    out
}

pub fn to_hiragana(romaji: &str) -> String {
    transduce(romaji).into_iter().map(|s| s.kana).collect()
}

/// Hiragana → katakana (a fixed 0x60 offset).
pub fn to_katakana(hiragana: &str) -> String {
    hiragana
        .chars()
        .map(|c| match c as u32 {
            0x3041..=0x3096 => char::from_u32(c as u32 + 0x60).unwrap_or(c),
            _ => c,
        })
        .collect()
}

/// The next form of a kana in the 小゛゜ ring, in either script.
pub fn cycle_variant(kana: char) -> char {
    for ring in VARIANT_CYCLES {
        for r in [ring.to_string(), to_katakana(ring)] {
            let chars: Vec<char> = r.chars().collect();
            if let Some(i) = chars.iter().position(|&c| c == kana) {
                return chars[(i + 1) % chars.len()];
            }
        }
    }
    kana
}

/// The marked forms of a plain hiragana kana (か → "が", は → "ばぱ").
pub fn marked_forms(kana: char) -> &'static str {
    VARIANT_CYCLES
        .iter()
        .find(|ring| ring.starts_with(kana))
        .map(|ring| &ring[kana.len_utf8()..])
        .unwrap_or("")
}

const HALF: &[(char, char)] = &[
    ('ア', 'ｱ'),
    ('イ', 'ｲ'),
    ('ウ', 'ｳ'),
    ('エ', 'ｴ'),
    ('オ', 'ｵ'),
    ('カ', 'ｶ'),
    ('キ', 'ｷ'),
    ('ク', 'ｸ'),
    ('ケ', 'ｹ'),
    ('コ', 'ｺ'),
    ('サ', 'ｻ'),
    ('シ', 'ｼ'),
    ('ス', 'ｽ'),
    ('セ', 'ｾ'),
    ('ソ', 'ｿ'),
    ('タ', 'ﾀ'),
    ('チ', 'ﾁ'),
    ('ツ', 'ﾂ'),
    ('テ', 'ﾃ'),
    ('ト', 'ﾄ'),
    ('ナ', 'ﾅ'),
    ('ニ', 'ﾆ'),
    ('ヌ', 'ﾇ'),
    ('ネ', 'ﾈ'),
    ('ノ', 'ﾉ'),
    ('ハ', 'ﾊ'),
    ('ヒ', 'ﾋ'),
    ('フ', 'ﾌ'),
    ('ヘ', 'ﾍ'),
    ('ホ', 'ﾎ'),
    ('マ', 'ﾏ'),
    ('ミ', 'ﾐ'),
    ('ム', 'ﾑ'),
    ('メ', 'ﾒ'),
    ('モ', 'ﾓ'),
    ('ヤ', 'ﾔ'),
    ('ユ', 'ﾕ'),
    ('ヨ', 'ﾖ'),
    ('ラ', 'ﾗ'),
    ('リ', 'ﾘ'),
    ('ル', 'ﾙ'),
    ('レ', 'ﾚ'),
    ('ロ', 'ﾛ'),
    ('ワ', 'ﾜ'),
    ('ヲ', 'ｦ'),
    ('ン', 'ﾝ'),
    ('ァ', 'ｧ'),
    ('ィ', 'ｨ'),
    ('ゥ', 'ｩ'),
    ('ェ', 'ｪ'),
    ('ォ', 'ｫ'),
    ('ッ', 'ｯ'),
    ('ャ', 'ｬ'),
    ('ュ', 'ｭ'),
    ('ョ', 'ｮ'),
    ('ー', 'ｰ'),
    ('、', '､'),
    ('。', '｡'),
    ('・', '･'),
];

/// A voiced or semi-voiced katakana as its base and mark, as NFD has it.
fn decompose(c: char) -> Option<(char, char)> {
    const DAKUTEN: char = '\u{3099}';
    const HANDAKUTEN: char = '\u{309A}';
    let base = |d: u32| char::from_u32(c as u32 - d);
    match c {
        'ガ' | 'ギ' | 'グ' | 'ゲ' | 'ゴ' | 'ザ' | 'ジ' | 'ズ' | 'ゼ' | 'ゾ' | 'ダ' | 'ヂ'
        | 'ヅ' | 'デ' | 'ド' | 'バ' | 'ビ' | 'ブ' | 'ベ' | 'ボ' => {
            base(1).map(|b| (b, DAKUTEN))
        }
        'パ' | 'ピ' | 'プ' | 'ペ' | 'ポ' => base(2).map(|b| (b, HANDAKUTEN)),
        'ヴ' => Some(('ウ', DAKUTEN)),
        'ヷ' => Some(('ワ', DAKUTEN)),
        'ヺ' => Some(('ヲ', DAKUTEN)),
        _ => None,
    }
}

/// Full-width katakana → half-width (ガ → ｶﾞ); anything without a
/// half-width form passes through.
pub fn to_half_width_katakana(text: &str) -> String {
    let half = |c: char| {
        HALF.iter()
            .find(|(f, _)| *f == c)
            .map(|(_, h)| *h)
            .unwrap_or(c)
    };
    let mut out = String::new();
    for c in text.chars() {
        let (base, mark) = match decompose(c) {
            Some((b, m)) => (b, Some(m)),
            None => (c, None),
        };
        match base {
            '\u{3099}' => out.push('ﾞ'),
            '\u{309A}' => out.push('ﾟ'),
            b => out.push(half(b)),
        }
        match mark {
            Some('\u{3099}') => out.push('ﾞ'),
            Some(_) => out.push('ﾟ'),
            None => {}
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn romaji_to_kana_and_back_out() {
        assert_eq!("にほん", to_hiragana("nihon"));
        assert_eq!("おんな", to_hiragana("onna"));
        assert_eq!("がっこう", to_hiragana("gakkou"));
        assert_eq!("かーど", to_hiragana("ka-do"));
        let spans = transduce("konnichiha");
        assert_eq!(10, spans.iter().map(|s| s.romaji_len).sum::<usize>());
        assert_eq!("ガッコウ", to_katakana("がっこう"));
        assert_eq!("ｶﾞｯｺｳ", to_half_width_katakana("ガッコウ"));
        assert_eq!("ﾊﾟﾝ", to_half_width_katakana("パン"));
        assert_eq!("ばぱ", marked_forms('は'));
        assert_eq!('づ', cycle_variant('っ'));
        assert_eq!('バ', cycle_variant('ハ'));
    }
}

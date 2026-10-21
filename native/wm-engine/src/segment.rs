//! Words in Thai, Lao, Khmer and Burmese, which are written without spaces
//! between them: ICU4X's word segmenter with its LSTM models.
//!
//! Prediction and learning read a word as a run of letters between spaces,
//! which in these four scripts is a phrase or a whole sentence. This finds
//! the words inside such a run, so they can be learned one at a time and
//! paired, and the word before the caret can be the context for the next.
//!
//! LSTM rather than dictionaries for size: the four models are 310 KB of
//! compiled data against 1.77 MB for the four dictionaries, and the
//! dictionary constructor would also bring the Chinese and Japanese one
//! (another 3.8 MB on arm64). Chinese and Japanese are not segmented here:
//! the LSTM constructor has no model for them and leaves a run of Han or
//! kana as one piece. Everything outside the four scripts follows UAX #29's
//! word rules, so Latin words, numbers and spaces come back as they would
//! from any word iterator.
//!
//! Offsets are UTF-16 units, as Kotlin indexes its strings.

use icu_segmenter::options::WordBreakInvariantOptions;
use icu_segmenter::WordSegmenter;

/// The words of `text`, as `[start, end)` ranges in UTF-16 units, in order:
/// the word-like segments (letters or numbers). Spaces, punctuation and
/// symbols between them are left out.
pub fn words(text: &[u16]) -> Vec<(usize, usize)> {
    let segmenter = WordSegmenter::new_lstm(WordBreakInvariantOptions::default());
    let mut out = Vec::new();
    let mut breaks = segmenter.segment_utf16(text);
    let mut start = match breaks.next() {
        Some(at) => at,
        None => return out,
    };
    while let Some(end) = breaks.next() {
        if breaks.is_word_like() {
            out.push((start, end));
        }
        start = end;
    }
    out
}

/// [`words`] for a Rust string, as the words themselves.
pub fn words_str(text: &str) -> Vec<String> {
    let units: Vec<u16> = text.encode_utf16().collect();
    words(&units)
        .into_iter()
        .map(|(s, e)| String::from_utf16_lossy(&units[s..e]))
        .collect()
}

/// Whether `u` is a letter or sign of one of the four scripts this
/// segments: the Thai, Lao, Khmer and Myanmar blocks with their extensions.
pub fn is_segmented_script(u: u32) -> bool {
    matches!(
        u,
        0x0E01..=0x0E3A
            | 0x0E40..=0x0E4E
            | 0x0E81..=0x0EDF
            | 0x1000..=0x103F
            | 0x1050..=0x108F
            | 0x109A..=0x109D
            | 0x1780..=0x17D3
            | 0x17DC..=0x17DD
            | 0x19E0..=0x19FF
            | 0xA9E0..=0xA9EF
            | 0xA9FA..=0xA9FE
            | 0xAA60..=0xAA7F
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn thai_splits_into_words() {
        // ICU4X's own example: every two weeks.
        assert_eq!(words_str("ทุกสองสัปดาห์"), ["ทุก", "สอง", "สัปดาห์"]);
    }

    #[test]
    fn spaces_punctuation_and_latin_follow_the_word_rules() {
        assert_eq!(words_str("Hello, world! 42"), ["Hello", "world", "42"]);
        assert_eq!(words_str("don't"), ["don't"]);
        assert!(words_str("").is_empty());
        assert!(words_str(" , ").is_empty());
    }

    #[test]
    fn the_other_three_scripts_split_too() {
        for text in [
            "ສະບາຍດີປະເທດລາວ",         // Lao: hello, Laos
            "ខ្ញុំស្រលាញ់ប្រទេសកម្ពុជា", // Khmer: I love Cambodia
            "မြန်မာနိုင်ငံသည်လှပသည်",     // Burmese: Myanmar is beautiful
        ] {
            let parts = words_str(text);
            assert!(parts.len() > 1, "{text}: {parts:?}");
            assert_eq!(parts.concat(), text);
        }
    }

    #[test]
    fn utf16_offsets() {
        let text: Vec<u16> = "a ไทย 𐓘".encode_utf16().collect();
        let got = words(&text);
        assert_eq!(got.first(), Some(&(0, 1)));
        // The Osage letter is two units.
        assert_eq!(got.last(), Some(&(text.len() - 2, text.len())));
    }

    #[test]
    fn script_ranges() {
        for c in ['ก', 'ั', 'ສ', 'က', 'ျ', 'ក', '្'] {
            assert!(is_segmented_script(c as u32), "{c}");
        }
        for c in ['a', '฿', '๑', '中', 'ক', ' '] {
            assert!(!is_segmented_script(c as u32), "{c}");
        }
    }
}

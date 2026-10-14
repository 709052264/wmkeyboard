//! The two character classes the engines ask about and `std` has no table
//! for: letters and combining marks, in the sense of Java's
//! `Character.isLetter` and the `Mn`/`Mc` general categories the Kotlin
//! originals test. Exact for the Indic blocks and the combining-diacritic
//! blocks, which is every script these engines write; approximate elsewhere.

/// A combining mark: Indic vowel signs, viramas, nuktas, candrabindu,
/// anusvara and visarga, and the general combining-diacritic blocks.
pub fn is_mark(c: char) -> bool {
    let u = c as u32;
    match u {
        0x0300..=0x036F | 0x1AB0..=0x1AFF | 0x1DC0..=0x1DFF | 0x20D0..=0x20FF | 0xFE20..=0xFE2F => {
            true
        }
        // Devanagari through Malayalam share one layout per 128-code-point
        // block: signs at the top, nukta and the vowel signs after the
        // avagraha, the Vedic and length marks, then the two vocalic signs.
        0x0900..=0x0DFF => matches!(
            u & 0x7F,
            0x00..=0x03 | 0x3A..=0x3C | 0x3E..=0x4F | 0x51..=0x57 | 0x62..=0x63
        ),
        _ => false,
    }
}

/// A letter as Java counts them: alphabetic and not a mark. Rust's
/// `is_alphabetic` says yes to every Indic vowel sign, which Java's
/// `isLetter` does not, and the engines rely on that difference.
pub fn is_letter(c: char) -> bool {
    c.is_alphabetic() && !is_mark(c)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bengali_signs_are_marks_and_letters_are_letters() {
        for c in [
            '\u{9BE}', '\u{9CD}', '\u{9BC}', '\u{981}', '\u{982}', '\u{983}', '\u{9D7}',
        ] {
            assert!(is_mark(c), "{c:?}");
            assert!(!is_letter(c), "{c:?}");
        }
        for c in ['ক', 'অ', '\u{9DC}', '\u{9BD}', 'a', 'Z'] {
            assert!(!is_mark(c), "{c:?}");
            assert!(is_letter(c), "{c:?}");
        }
        for c in ['1', '।', ' ', '\u{200D}', '৳'] {
            assert!(!is_mark(c), "{c:?}");
            assert!(!is_letter(c), "{c:?}");
        }
    }
}

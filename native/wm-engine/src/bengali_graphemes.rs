//! Bengali grapheme-cluster helpers for editing. A port of
//! `BengaliGraphemes.kt`.
//!
//! Backspace normally removes one code unit, which visually shatters a
//! conjunct (জ্ঞ → জ্ + delete → জ). With the conjunct-aware option the whole
//! cluster, consonant(s) joined by hasant plus any vowel sign and modifiers,
//! is removed as a single unit.

const HASANT: char = '\u{9CD}';
const ZWJ: char = '\u{200D}';
const ZWNJ: char = '\u{200C}';
const NUKTA: char = '\u{9BC}';

/// Bengali vowel sign (kar) → independent vowel letter.
pub fn kar_to_vowel(kar: char) -> Option<&'static str> {
    Some(match kar {
        '\u{9BE}' => "আ",
        '\u{9BF}' => "ই",
        '\u{9C0}' => "ঈ",
        '\u{9C1}' => "উ",
        '\u{9C2}' => "ঊ",
        '\u{9C3}' => "ঋ",
        '\u{9C7}' => "এ",
        '\u{9C8}' => "ঐ",
        '\u{9CB}' => "ও",
        '\u{9CC}' => "ঔ",
        _ => return None,
    })
}

/// Kar → য়-glide form used after another vowel, where a bare আ or এ is
/// invalid: কা + আ must give কায়া (never কাআ), দি + এ gives দিয়ে. Only া and
/// ে take the glide; the other vowels are written independently after a
/// vowel (খাই, বউ, খাও).
pub fn kar_to_glide(kar: char) -> Option<&'static str> {
    match kar {
        '\u{9BE}' => Some("\u{9DF}\u{9BE}"),
        '\u{9C7}' => Some("\u{9DF}\u{9C7}"),
        _ => None,
    }
}

/// True for characters a kar can attach to: consonants, nukta, hasant.
pub fn kar_attaches_to(c: char) -> bool {
    ('\u{995}'..='\u{9B9}').contains(&c)
        || matches!(c, '\u{9DC}' | '\u{9DD}' | '\u{9DF}')
        || c == NUKTA
        || c == HASANT
}

/// What a vowel key should produce given the character before the cursor.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum VowelKeyForm {
    Independent,
    Kar,
    Glide,
}

/// Chooses the vowel-key form from the preceding character: kar after
/// anything it can attach to, the য়-glide after a vowel (sign or
/// independent, including trailing modifiers like ঁ), and the independent
/// letter at a word start or after non-Bengali text.
pub fn vowel_form_after(previous: Option<char>) -> VowelKeyForm {
    match previous {
        None => VowelKeyForm::Independent,
        Some(c) if kar_attaches_to(c) => VowelKeyForm::Kar,
        Some(c) if is_vowel_sign(c) || ('\u{985}'..='\u{994}').contains(&c) || is_modifier(c) => {
            VowelKeyForm::Glide
        }
        Some(_) => VowelKeyForm::Independent,
    }
}

/// Output of a vowel key whose base (kar) form is `kar`, in the given
/// `form`. Falls back to the independent letter for vowels that have no
/// glide form.
pub fn vowel_key_text(kar: char, form: VowelKeyForm) -> Option<String> {
    let independent = kar_to_vowel(kar)?;
    Some(match form {
        VowelKeyForm::Kar => kar.to_string(),
        VowelKeyForm::Glide => kar_to_glide(kar).unwrap_or(independent).to_string(),
        VowelKeyForm::Independent => independent.to_string(),
    })
}

/// Anything in the Bengali block: letters, signs, digits and punctuation.
pub fn is_bengali(c: char) -> bool {
    ('\u{980}'..='\u{9FF}').contains(&c)
}

fn is_consonant(c: char) -> bool {
    ('\u{995}'..='\u{9B9}').contains(&c)
        || ('\u{9DC}'..='\u{9DF}').contains(&c)
        || matches!(c, '\u{9CE}' | '\u{9F0}' | '\u{9F1}')
}

fn is_vowel_sign(c: char) -> bool {
    ('\u{9BE}'..='\u{9C4}').contains(&c)
        || ('\u{9C7}'..='\u{9C8}').contains(&c)
        || ('\u{9CB}'..='\u{9CC}').contains(&c)
        || c == '\u{9D7}'
        || ('\u{9E2}'..='\u{9E3}').contains(&c)
}

/// Candrabindu, anusvara, visarga.
fn is_modifier(c: char) -> bool {
    ('\u{981}'..='\u{983}').contains(&c)
}

/// How many UTF-16 units to remove from the end of `text` to delete the last
/// Bengali cluster as one unit. 0 when `text` is empty; one character (two
/// units for a non-BMP one) for non-Bengali endings. UTF-16 units because
/// that is what an Android `InputConnection` deletes by; a desktop caller
/// over `char`s counts the same for everything in the Bengali block.
pub fn cluster_delete_length(text: &str) -> usize {
    let chars: Vec<char> = text.chars().collect();
    let Some(&last) = chars.last() else {
        return 0;
    };
    let units = |from: usize| chars[from..].iter().map(|c| c.len_utf16()).sum::<usize>();
    if !is_bengali(last) && last != ZWJ && last != ZWNJ {
        return last.len_utf16();
    }
    // A bare trailing hasant deletes alone: typing an explicit hasant then
    // backspace should undo just the hasant.
    if last == HASANT {
        return 1;
    }
    let mut i = chars.len() as isize - 1;
    let at = |i: isize| chars[i as usize];
    // Trailing modifiers, vowel signs, nukta and joiners.
    while i >= 0 && {
        let c = at(i);
        is_vowel_sign(c) || is_modifier(c) || c == NUKTA || c == ZWJ || c == ZWNJ
    } {
        i -= 1;
    }
    if i < 0 {
        return units(0);
    }
    if !is_consonant(at(i)) {
        // Independent vowel, digit or sign: it plus any trailing marks.
        return units(i as usize);
    }
    i -= 1; // the base consonant
            // Walk back over (consonant + hasant [+ joiner] [+ nukta]) pairs.
    while i >= 0 {
        let mut j = i;
        if at(j) == ZWJ || at(j) == ZWNJ {
            j -= 1;
        }
        if j < 0 || at(j) != HASANT {
            break;
        }
        j -= 1;
        if j >= 0 && at(j) == NUKTA {
            j -= 1;
        }
        if j < 0 || !is_consonant(at(j)) {
            break;
        }
        i = j - 1;
    }
    units((i + 1) as usize)
}

#[cfg(test)]
mod tests {
    //! Mirrors `BengaliGraphemesTest.kt`.

    use super::*;

    fn len(text: &str) -> usize {
        cluster_delete_length(text)
    }

    #[test]
    fn cluster_lengths() {
        assert_eq!(0, len(""));
        assert_eq!(1, len("hello"));
        assert_eq!(2, len("hi😀"));
        assert_eq!(1, len("আমি ক"));
        assert_eq!(2, len("আমার কি"));
        assert_eq!(3, len("ক্ষ"));
        assert_eq!(4, len("জ্ঞা"));
        assert_eq!(6, len("স্ত্রী"));
        assert_eq!(1, len("ক্"));
        assert_eq!(3, len("বাং"));
        assert_eq!(1, len("কথা আ"));
        assert_eq!(3, len("ব্য"));
        assert_eq!(2, len("আছি"));
    }

    #[test]
    fn vowel_key_forms() {
        use VowelKeyForm::*;
        assert_eq!(Independent, vowel_form_after(None));
        assert_eq!(Independent, vowel_form_after(Some(' ')));
        assert_eq!(Kar, vowel_form_after(Some('ক')));
        assert_eq!(Kar, vowel_form_after(Some('\u{9DF}')));
        assert_eq!(Glide, vowel_form_after(Some('\u{9BE}')));
        assert_eq!(Glide, vowel_form_after(Some('আ')));
        assert_eq!(Glide, vowel_form_after(Some('\u{9C7}')));
        assert_eq!(Glide, vowel_form_after(Some('\u{981}')));

        assert_eq!(Some("\u{9BE}".to_string()), vowel_key_text('\u{9BE}', Kar));
        assert_eq!(
            Some("\u{9DF}\u{9BE}".to_string()),
            vowel_key_text('\u{9BE}', Glide)
        );
        assert_eq!(
            Some("আ".to_string()),
            vowel_key_text('\u{9BE}', Independent)
        );
        assert_eq!(
            Some("\u{9DF}\u{9C7}".to_string()),
            vowel_key_text('\u{9C7}', Glide)
        );
        assert_eq!(Some("ই".to_string()), vowel_key_text('\u{9BF}', Glide));
        assert_eq!(Some("উ".to_string()), vowel_key_text('\u{9C1}', Glide));
        assert_eq!(None, vowel_key_text('ক', Glide));
    }

    #[test]
    fn kar_helpers() {
        assert_eq!(Some("আ"), kar_to_vowel('\u{9BE}'));
        assert_eq!(Some("ই"), kar_to_vowel('\u{9BF}'));
        assert_eq!(Some("ও"), kar_to_vowel('\u{9CB}'));
        assert!(kar_attaches_to('ক'));
        assert!(kar_attaches_to('\u{9DF}'));
        assert!(kar_attaches_to('\u{9CD}'));
        assert!(!kar_attaches_to('আ'));
        assert!(!kar_attaches_to(' '));
        assert!(!kar_attaches_to('\u{9BE}'));
    }
}

//! Contractions typed without their apostrophe: the Kotlin `Apostrophes`
//! (core/prediction). arent -> aren't, im -> I'm, German gehts -> geht's.
//!
//! The repair table leaves out every apostrophe-less form that is a word of
//! its own (its, were, well, id, hell...); those are only ever offered, from
//! a second table, never committed over what was typed.

const CONTRACTIONS: &[(&str, &str)] = &[
    ("aint", "ain't"),
    ("arent", "aren't"),
    ("isnt", "isn't"),
    ("wasnt", "wasn't"),
    ("werent", "weren't"),
    ("cant", "can't"),
    ("couldnt", "couldn't"),
    ("didnt", "didn't"),
    ("doesnt", "doesn't"),
    ("dont", "don't"),
    ("hadnt", "hadn't"),
    ("hasnt", "hasn't"),
    ("havent", "haven't"),
    ("mightnt", "mightn't"),
    ("mustnt", "mustn't"),
    ("neednt", "needn't"),
    ("shant", "shan't"),
    ("shouldnt", "shouldn't"),
    ("wont", "won't"),
    ("wouldnt", "wouldn't"),
    ("couldve", "could've"),
    ("mightve", "might've"),
    ("mustve", "must've"),
    ("shouldve", "should've"),
    ("wouldve", "would've"),
    ("im", "I'm"),
    ("ive", "I've"),
    ("ill", "I'll"),
    ("i'm", "I'm"),
    ("i've", "I've"),
    ("i'll", "I'll"),
    ("i'd", "I'd"),
    ("hed", "he'd"),
    ("hes", "he's"),
    ("shes", "she's"),
    ("itd", "it'd"),
    ("itll", "it'll"),
    ("thatd", "that'd"),
    ("thatll", "that'll"),
    ("thats", "that's"),
    ("theyd", "they'd"),
    ("theyll", "they'll"),
    ("theyre", "they're"),
    ("theyve", "they've"),
    ("weve", "we've"),
    ("youd", "you'd"),
    ("youll", "you'll"),
    ("youre", "you're"),
    ("youve", "you've"),
    ("yall", "y'all"),
    ("whatd", "what'd"),
    ("whatll", "what'll"),
    ("whatre", "what're"),
    ("whats", "what's"),
    ("whatve", "what've"),
    ("whens", "when's"),
    ("whered", "where'd"),
    ("wheres", "where's"),
    ("whod", "who'd"),
    ("wholl", "who'll"),
    ("whos", "who's"),
    ("whove", "who've"),
    ("whyd", "why'd"),
    ("whys", "why's"),
    ("howd", "how'd"),
    ("hows", "how's"),
    ("heres", "here's"),
    ("theres", "there's"),
    ("cmon", "c'mon"),
    ("maam", "ma'am"),
    ("oclock", "o'clock"),
    ("i", "I"),
];

/// The forms that are words of their own: offered, never applied.
const DECLARED: &[(&str, &str)] = &[
    ("id", "I'd"),
    ("its", "it's"),
    ("hell", "he'll"),
    ("lets", "let's"),
    ("shed", "she'd"),
    ("shell", "she'll"),
    ("wed", "we'd"),
    ("well", "we'll"),
    ("were", "we're"),
];

/// German's *es* worn down to *'s* on the verb before it (#518).
const GERMAN_VERBS: &[&str] = &[
    "bin", "bleibt", "braucht", "fehlt", "gab", "gefällt", "geht", "gibt", "ging", "hab", "hat",
    "hilft", "hört", "ist", "kann", "klappt", "kommt", "kriegt", "läuft", "lohnt", "mach", "macht",
    "mag", "nimmt", "passt", "regnet", "reicht", "sag", "sieht", "soll", "steht", "stimmt", "tut",
    "versuch", "war", "wär", "will", "wird", "zeig",
];

fn base(language: &str) -> &str {
    language.split(['-', '_']).next().unwrap_or(language)
}

/// Whether `language` has a table: English and German.
pub fn serves_language(language: &str) -> bool {
    matches!(base(language), "en" | "de")
}

fn lookup(table: &[(&str, &str)], lower: &str) -> Option<String> {
    table
        .iter()
        .find(|(k, _)| *k == lower)
        .map(|(_, v)| v.to_string())
}

fn german(lower: &str) -> Option<String> {
    let verb = lower.strip_suffix('s')?;
    GERMAN_VERBS.contains(&verb).then(|| format!("{verb}'s"))
}

fn with_case(word: &str, fixed: String) -> Option<String> {
    let letters: Vec<char> = word.chars().filter(|c| c.is_alphabetic()).collect();
    let out = if word.chars().count() > 1
        && !letters.is_empty()
        && letters.iter().all(|c| c.is_uppercase())
    {
        fixed.to_uppercase()
    } else if word.chars().next().is_some_and(char::is_uppercase) {
        let mut cs = fixed.chars();
        match cs.next() {
            Some(f) => f.to_uppercase().chain(cs).collect(),
            None => fixed,
        }
    } else {
        fixed
    };
    (out != word).then_some(out)
}

/// The repaired word in `language`, or None. The typed case is kept:
/// Dont -> Don't, ARENT -> AREN'T; im -> I'm stays capital.
pub fn fix(word: &str, language: &str) -> Option<String> {
    let lower = word.to_lowercase();
    let fixed = match base(language) {
        "en" => lookup(CONTRACTIONS, &lower),
        "de" => german(&lower),
        _ => None,
    }?;
    with_case(word, fixed)
}

/// The contraction a word of its own may have been typed for, for the strip
/// only: were -> we're. English only.
pub fn offer(word: &str, language: &str) -> Option<String> {
    if base(language) != "en" {
        return None;
    }
    with_case(word, lookup(DECLARED, &word.to_lowercase())?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn repairs_keep_the_typed_case() {
        assert_eq!(Some("don't".into()), fix("dont", "en"));
        assert_eq!(Some("Don't".into()), fix("Dont", "en"));
        assert_eq!(Some("AREN'T".into()), fix("ARENT", "en"));
        assert_eq!(Some("I'm".into()), fix("im", "en-US"));
        assert_eq!(None, fix("its", "en"));
        assert_eq!(Some("it's".into()), offer("its", "en"));
        assert_eq!(Some("geht's".into()), fix("gehts", "de"));
        assert_eq!(None, fix("nichts", "de"));
        assert_eq!(None, fix("dont", "fr"));
        assert_eq!(None, fix("I", "en"));
    }
}

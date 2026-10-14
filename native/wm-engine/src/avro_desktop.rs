//! Desktop Avro's own readings, out of ibus-avro's own data, so every word
//! desktop Avro offers for a spelling can be offered here too.
//!
//! The data is the Android app's `assets/avro/` (Mozilla Public License 1.1,
//! as OmicronLab published it; see `NOTICE.txt` there), compiled in:
//!
//! - `phonetic.json`: Avro's transliteration grammar, which gives the reading
//!   a candidate list ends with and the one it is sorted against.
//! - `regex.json`: the looser grammar Avro turns a spelling into a regular
//!   expression with, to find every dictionary word the spelling could be.
//! - `suffix.json`: the endings read off a word to find its base.
//! - `autocorrect.json`: Avro's fixed spellings, the list's first entry.
//! - `search.json`: which tables a romanized first letter searches, and the
//!   first letters each table holds.
//!
//! What is here is the grammar and the lookups. The dictionary walk itself
//! (Avro's word tables and the keyboard's own list, searched with the
//! expression) belongs with the prediction port, since it needs the word
//! list machinery; the Kotlin reader is `core/prediction/.../AvroDesktop.kt`.

use std::collections::HashMap;
use std::sync::OnceLock;

use serde_json::Value;

/// The compiled-in data, parsed once.
pub fn desktop() -> &'static Desktop {
    static DESKTOP: OnceLock<Desktop> = OnceLock::new();
    DESKTOP.get_or_init(|| {
        Desktop::load(
            include_str!("../../../app/src/main/assets/avro/phonetic.json"),
            include_str!("../../../app/src/main/assets/avro/regex.json"),
            include_str!("../../../app/src/main/assets/avro/suffix.json"),
            include_str!("../../../app/src/main/assets/avro/autocorrect.json"),
            include_str!("../../../app/src/main/assets/avro/search.json"),
        )
        .expect("bundled Avro data parses")
    })
}

pub struct Desktop {
    phonetic: Grammar,
    regex: Grammar,
    suffixes: HashMap<String, String>,
    autocorrect: HashMap<String, String>,
    /// Which of Avro's tables a romanized first letter searches.
    letters: HashMap<char, Vec<String>>,
    /// First letters of each table's words, for finding them in another list.
    initials: HashMap<String, Vec<char>>,
}

/// Avro's fixed spelling for something typed.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Autocorrect {
    pub text: String,
    /// An entry mapped to itself (the smileys) is typed as it is.
    pub exact: bool,
}

/// Punctuation Avro peels off either end of a word before reading it.
pub const PADDING: &str = "-]~!@#%&*()_=+[{}'\";<>/?|.,`:";

const AVRO_VOWELS: &str = "অআইঈউঊঋএঐওঔঌৡািীুূৃেৈোৌ";
const AVRO_KARS: &str = "ািীুূৃেৈোৌৄ";

impl Desktop {
    /// Reads the five JSON files.
    pub fn load(
        phonetic: &str,
        regex: &str,
        suffix: &str,
        autocorrect: &str,
        search: &str,
    ) -> Result<Desktop, String> {
        fn json(text: &str) -> Result<Value, String> {
            serde_json::from_str(text).map_err(|e| e.to_string())
        }
        fn string_map(value: &Value, key: &str) -> Result<HashMap<String, String>, String> {
            let object = value
                .get(key)
                .and_then(Value::as_object)
                .ok_or_else(|| format!("missing object {key}"))?;
            Ok(object
                .iter()
                .filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string())))
                .collect())
        }
        let search = json(search)?;
        let letters = search
            .get("letters")
            .and_then(Value::as_object)
            .ok_or("missing letters")?
            .iter()
            .filter_map(|(letter, names)| {
                let names = names
                    .as_array()?
                    .iter()
                    .filter_map(Value::as_str)
                    .map(str::to_string)
                    .collect();
                Some((letter.chars().next()?, names))
            })
            .collect();
        let initials = search
            .get("initials")
            .and_then(Value::as_object)
            .ok_or("missing initials")?
            .iter()
            .filter_map(|(name, chars)| Some((name.clone(), chars.as_str()?.chars().collect())))
            .collect();
        Ok(Desktop {
            phonetic: Grammar::of(&json(phonetic)?, false)?,
            regex: Grammar::of(&json(regex)?, true)?,
            suffixes: string_map(&json(suffix)?, "suffixes")?,
            autocorrect: string_map(&json(autocorrect)?, "words")?,
            letters,
            initials,
        })
    }

    /// Avro's reading of `roman`, letter for letter.
    pub fn transliterate(&self, roman: &str) -> String {
        self.phonetic.parse(roman)
    }

    /// The anchored regular expression Avro searches its dictionary with for
    /// `roman`, as source text; the caller compiles it with whatever engine
    /// holds the word list.
    pub fn regex_source(&self, roman: &str) -> String {
        format!("^{}$", self.regex.parse(roman))
    }

    /// Avro's fixed spelling for `word`, if it has one: looked up as typed,
    /// then by the case-fixed `middle` (the word without its padding).
    pub fn autocorrect(&self, word: &str, middle: &str) -> Option<Autocorrect> {
        if let Some(value) = self.autocorrect.get(word) {
            return Some(if value == word {
                Autocorrect {
                    text: word.to_string(),
                    exact: true,
                }
            } else {
                Autocorrect {
                    text: self.phonetic.parse(value),
                    exact: false,
                }
            });
        }
        self.autocorrect
            .get(&self.phonetic.case_fixed(middle))
            .map(|value| Autocorrect {
                text: self.phonetic.parse(value),
                exact: false,
            })
    }

    /// The Bengali ending Avro reads a romanized `ending` as, if it is one.
    pub fn suffix(&self, ending: &str) -> Option<&str> {
        self.suffixes.get(ending).map(String::as_str)
    }

    /// The names of Avro's tables a word starting with `letter` lives in.
    pub fn tables_for(&self, letter: char) -> &[String] {
        self.letters.get(&letter).map(Vec::as_slice).unwrap_or(&[])
    }

    /// The first letters of the words in Avro's table `name`.
    pub fn initials_of(&self, name: &str) -> &[char] {
        self.initials.get(name).map(Vec::as_slice).unwrap_or(&[])
    }
}

/// `base` with `suffix` after it, joined as Avro joins them: a য় between a
/// vowel and a vowel sign, ৎ opened back to ত and ং to ঙ before an ending.
pub fn join_suffix(base: &str, suffix: &str) -> String {
    let word = precomposed(base);
    let Some(last) = word.chars().last() else {
        return suffix.to_string();
    };
    let kar_next = suffix.chars().next().is_some_and(|c| AVRO_KARS.contains(c));
    if AVRO_VOWELS.contains(last) && kar_next {
        format!("{word}\u{9DF}{suffix}")
    } else if last == 'ৎ' {
        format!("{}ত{suffix}", &word[..word.len() - last.len_utf8()])
    } else if last == 'ং' {
        format!("{}ঙ{suffix}", &word[..word.len() - last.len_utf8()])
    } else {
        format!("{word}{suffix}")
    }
}

/// The decomposed nukta pairs as the precomposed letters Avro's data is
/// written in.
pub fn precomposed(word: &str) -> String {
    if !word.contains('\u{9BC}') {
        return word.to_string();
    }
    word.replace("\u{9A1}\u{9BC}", "\u{9DC}")
        .replace("\u{9A2}\u{9BC}", "\u{9DD}")
        .replace("\u{9AF}\u{9BC}", "\u{9DF}")
}

/// Edit distance with adjacent transpositions, as Avro sorts its list by:
/// the optimal-string-alignment form, over characters.
pub fn damerau_levenshtein(a: &str, b: &str) -> usize {
    let a: Vec<char> = a.chars().collect();
    let b: Vec<char> = b.chars().collect();
    if a.is_empty() {
        return b.len();
    }
    if b.is_empty() {
        return a.len();
    }
    let mut d = vec![vec![0usize; b.len() + 1]; a.len() + 1];
    for (i, row) in d.iter_mut().enumerate() {
        row[0] = i;
    }
    for (j, cell) in d[0].iter_mut().enumerate() {
        *cell = j;
    }
    for i in 1..=a.len() {
        for j in 1..=b.len() {
            let cost = usize::from(a[i - 1] != b[j - 1]);
            let mut v = (d[i - 1][j] + 1)
                .min(d[i][j - 1] + 1)
                .min(d[i - 1][j - 1] + cost);
            if i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] {
                v = v.min(d[i - 2][j - 2] + cost);
            }
            d[i][j] = v;
        }
    }
    d[a.len()][b.len()]
}

/// One of Avro's two grammars: patterns tried longest first at each position,
/// each with rules on what may stand before or after it. In regex mode every
/// replacement is a fragment of a regular expression, followed by the
/// optional ফলা, hasant and sign Avro allows after any letter.
struct Grammar {
    by_first: HashMap<char, Vec<Entry>>,
    vowel: Vec<char>,
    consonant: Vec<char>,
    case_sensitive: Vec<char>,
    ignore: Vec<char>,
    regex_mode: bool,
}

struct Entry {
    find: Vec<char>,
    replace: String,
    rules: Vec<Rule>,
}

struct Rule {
    matches: Vec<Match>,
    replace: String,
}

struct Match {
    suffix: bool,
    scope: String,
    negative: bool,
    value: Vec<char>,
}

const REGEX_TAIL: &str = "(্[যবম])?(্?)([ঃঁ]?)";

fn lower(c: char) -> char {
    c.to_lowercase().next().unwrap_or(c)
}

impl Grammar {
    fn of(json: &Value, regex_mode: bool) -> Result<Grammar, String> {
        let text = |name: &str| -> Vec<char> {
            json.get(name)
                .and_then(Value::as_str)
                .unwrap_or("")
                .chars()
                .collect()
        };
        let patterns = json
            .get("patterns")
            .and_then(Value::as_array)
            .ok_or("missing patterns")?;
        let mut by_first: HashMap<char, Vec<Entry>> = HashMap::new();
        for p in patterns {
            let find: Vec<char> = p
                .get("find")
                .and_then(Value::as_str)
                .unwrap_or("")
                .chars()
                .collect();
            let Some(&first) = find.first() else { continue };
            let replace = p
                .get("replace")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string();
            let rules = p
                .get("rules")
                .and_then(Value::as_array)
                .map(|rules| {
                    rules
                        .iter()
                        .map(|r| {
                            let matches = r
                                .get("matches")
                                .and_then(Value::as_array)
                                .map(|ms| {
                                    ms.iter()
                                        .map(|m| {
                                            let scope = m
                                                .get("scope")
                                                .and_then(Value::as_str)
                                                .unwrap_or("");
                                            Match {
                                                suffix: m.get("type").and_then(Value::as_str)
                                                    == Some("suffix"),
                                                scope: scope.trim_start_matches('!').to_string(),
                                                negative: scope.starts_with('!'),
                                                value: m
                                                    .get("value")
                                                    .and_then(Value::as_str)
                                                    .unwrap_or("")
                                                    .chars()
                                                    .collect(),
                                            }
                                        })
                                        .collect()
                                })
                                .unwrap_or_default();
                            Rule {
                                matches,
                                replace: r
                                    .get("replace")
                                    .and_then(Value::as_str)
                                    .unwrap_or("")
                                    .to_string(),
                            }
                        })
                        .collect()
                })
                .unwrap_or_default();
            by_first.entry(first).or_default().push(Entry {
                find,
                replace,
                rules,
            });
        }
        Ok(Grammar {
            by_first,
            vowel: text("vowel"),
            consonant: text("consonant"),
            case_sensitive: text("casesensitive"),
            ignore: text("ignore"),
            regex_mode,
        })
    }

    /// `input` as Avro reads it before applying the patterns.
    fn case_fixed(&self, input: &str) -> String {
        let mut out = String::with_capacity(input.len());
        for c in input.chars() {
            let lc = lower(c);
            if self.regex_mode && self.ignore.contains(&lc) {
                continue;
            }
            if self.regex_mode || !self.case_sensitive.contains(&lc) {
                out.push(lc);
            } else {
                out.push(c);
            }
        }
        out
    }

    fn parse(&self, input: &str) -> String {
        let fixed: Vec<char> = self.case_fixed(input).chars().collect();
        let mut out = String::new();
        let mut cur = 0;
        while cur < fixed.len() {
            let start = cur;
            let mut replaced: Option<&str> = None;
            let mut end = cur + 1;
            for entry in self
                .by_first
                .get(&fixed[cur])
                .map(Vec::as_slice)
                .unwrap_or(&[])
            {
                end = cur + entry.find.len();
                if end > fixed.len() || fixed[start..end] != entry.find[..] {
                    continue;
                }
                replaced = Some(
                    entry
                        .rules
                        .iter()
                        .find(|rule| {
                            rule.matches
                                .iter()
                                .all(|m| self.holds(m, &fixed, start, end))
                        })
                        .map_or(entry.replace.as_str(), |rule| rule.replace.as_str()),
                );
                break;
            }
            match replaced {
                None => {
                    out.push(fixed[cur]);
                    cur += 1;
                }
                Some(text) => {
                    out.push_str(text);
                    if self.regex_mode {
                        out.push_str(REGEX_TAIL);
                    }
                    cur = end;
                }
            }
        }
        out
    }

    fn holds(&self, m: &Match, fixed: &[char], start: usize, end: usize) -> bool {
        let len = fixed.len() as i64;
        let chk: i64 = if m.suffix {
            end as i64
        } else {
            start as i64 - 1
        };
        let result = match m.scope.as_str() {
            "punctuation" => {
                (chk < 0 && !m.suffix)
                    || (chk >= len && m.suffix)
                    || self.is_punctuation(fixed, chk)
            }
            "vowel" => {
                ((chk >= 0 && !m.suffix) || (chk < len && m.suffix)) && self.is_vowel(fixed, chk)
            }
            "consonant" => {
                ((chk >= 0 && !m.suffix) || (chk < len && m.suffix))
                    && self.is_consonant(fixed, chk)
            }
            "exact" => {
                let value = m.value.len() as i64;
                let s = if m.suffix {
                    end as i64
                } else {
                    start as i64 - value
                };
                let e = if m.suffix {
                    end as i64 + value
                } else {
                    start as i64
                };
                // Avro's own bound, `end < length`, kept as it is: an exact
                // match reaching the last letter does not count there either.
                let hit = s >= 0 && e < len && fixed[s as usize..e as usize] == m.value[..];
                return hit ^ m.negative;
            }
            _ => true,
        };
        result ^ m.negative
    }

    // Out of range is "" in Avro's JavaScript, which every indexOf finds.
    fn is_vowel(&self, s: &[char], i: i64) -> bool {
        !(0..s.len() as i64).contains(&i) || self.vowel.contains(&lower(s[i as usize]))
    }

    fn is_consonant(&self, s: &[char], i: i64) -> bool {
        !(0..s.len() as i64).contains(&i) || self.consonant.contains(&lower(s[i as usize]))
    }

    fn is_punctuation(&self, s: &[char], i: i64) -> bool {
        !(self.is_vowel(s, i) || self.is_consonant(s, i))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn phonetic_reading_is_avros() {
        let d = desktop();
        assert_eq!(precomposed("আমার"), precomposed(&d.transliterate("amar")));
        assert_eq!(precomposed("ভালো"), precomposed(&d.transliterate("bhalO")));
        assert_eq!("ঐ", d.transliterate("OI"));
        assert_eq!("কৈ", d.transliterate("kOI"));
    }

    #[test]
    fn regex_grammar_anchors_and_appends_the_tail() {
        let source = desktop().regex_source("ki");
        assert!(source.starts_with('^') && source.ends_with('$'), "{source}");
        assert!(source.contains(REGEX_TAIL), "{source}");
        // Ignored punctuation never reaches the expression.
        assert_eq!(desktop().regex_source("k.i"), desktop().regex_source("ki"));
    }

    #[test]
    fn autocorrect_reads_exact_entries_as_typed_and_the_rest_through_the_grammar() {
        let d = desktop();
        let smiley = d.autocorrect("#:-S", "#:-S").unwrap();
        assert!(smiley.exact);
        assert_eq!("#:-S", smiley.text);
        let and = d.autocorrect("&", "&").unwrap();
        assert!(!and.exact);
        assert_eq!("ও", and.text);
        assert_eq!(None, d.autocorrect("zzzzqq", "zzzzqq"));
    }

    #[test]
    fn suffixes_and_tables_are_read() {
        let d = desktop();
        assert_eq!(Some("ে"), d.suffix("e"));
        assert_eq!(Some("\u{9DF}"), d.suffix("y").map(precomposed).as_deref());
        assert!(!d.tables_for('a').is_empty());
        assert!(!d.initials_of("aa").is_empty());
        assert!(d.tables_for('1').is_empty());
    }

    #[test]
    fn suffixes_join_the_way_avro_joins_them() {
        assert_eq!("বন্ধুদের", join_suffix("বন্ধু", "দের"));
        assert_eq!("মা\u{9DF}ের", join_suffix("মা", "ের"));
        assert_eq!("হঠাতে", join_suffix("হঠাৎ", "ে"));
        assert_eq!("রঙে", join_suffix("রং", "ে"));
        assert_eq!("ে", join_suffix("", "ে"));
    }

    #[test]
    fn precomposed_folds_the_three_nukta_letters() {
        assert_eq!(
            "\u{9DC}\u{9DD}\u{9DF}",
            precomposed("\u{9A1}\u{9BC}\u{9A2}\u{9BC}\u{9AF}\u{9BC}")
        );
        assert_eq!("কথা", precomposed("কথা"));
    }

    #[test]
    fn edit_distance_counts_transpositions_once() {
        assert_eq!(0, damerau_levenshtein("", ""));
        assert_eq!(3, damerau_levenshtein("", "abc"));
        assert_eq!(3, damerau_levenshtein("kitten", "sitting"));
        assert_eq!(1, damerau_levenshtein("ab", "ba"));
        assert_eq!(1, damerau_levenshtein("আমার", "আমরা"));
    }
}

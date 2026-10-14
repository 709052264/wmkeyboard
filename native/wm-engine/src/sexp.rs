//! The S-expressions an m17n input-method file is written in: `;;` comments,
//! `"strings"` with backslash escapes, bare atoms and parenthesised lists.
//!
//! A port of `Sexp` in `core/language/.../transliteration/Khipro.kt`; the two
//! must read the same files the same way.

/// One S-expression.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Sexp {
    Atom(String),
    Node(Vec<Sexp>),
}

impl Sexp {
    /// Every top-level expression in `text`, in order.
    pub fn parse_all(text: &str) -> Result<Vec<Sexp>, String> {
        let mut reader = Reader {
            chars: text.chars().collect(),
            at: 0,
        };
        let mut out = Vec::new();
        while reader.skip_space() {
            out.push(reader.read()?);
        }
        Ok(out)
    }

    /// The atom's text, or `None` for a list.
    pub fn atom(&self) -> Option<&str> {
        match self {
            Sexp::Atom(text) => Some(text),
            Sexp::Node(_) => None,
        }
    }

    /// A list's items; an atom has none.
    pub fn items(&self) -> &[Sexp] {
        match self {
            Sexp::Atom(_) => &[],
            Sexp::Node(items) => items,
        }
    }

    /// A list's first item when it is an atom: `(map ...)` gives `map`.
    pub fn head(&self) -> Option<&str> {
        self.items().first().and_then(Sexp::atom)
    }
}

struct Reader {
    chars: Vec<char>,
    at: usize,
}

impl Reader {
    /// Skips blanks and `;;` comments; false at the end of the text.
    fn skip_space(&mut self) -> bool {
        while self.at < self.chars.len() {
            let c = self.chars[self.at];
            if c.is_whitespace() {
                self.at += 1;
            } else if c == ';' && self.chars.get(self.at + 1) == Some(&';') {
                while self.at < self.chars.len() && self.chars[self.at] != '\n' {
                    self.at += 1;
                }
            } else {
                return true;
            }
        }
        false
    }

    fn read(&mut self) -> Result<Sexp, String> {
        if !self.skip_space() {
            return Err("Unexpected end of m17n text".to_string());
        }
        match self.chars[self.at] {
            '(' => {
                self.at += 1;
                let mut items = Vec::new();
                while self.skip_space() && self.chars[self.at] != ')' {
                    items.push(self.read()?);
                }
                if self.at >= self.chars.len() {
                    return Err("Unclosed list in m17n text".to_string());
                }
                self.at += 1;
                Ok(Sexp::Node(items))
            }
            '"' => self.read_string().map(Sexp::Atom),
            _ => {
                let start = self.at;
                while self.at < self.chars.len() {
                    let c = self.chars[self.at];
                    if c.is_whitespace() || c == '(' || c == ')' {
                        break;
                    }
                    self.at += 1;
                }
                Ok(Sexp::Atom(self.chars[start..self.at].iter().collect()))
            }
        }
    }

    fn read_string(&mut self) -> Result<String, String> {
        self.at += 1;
        let mut out = String::new();
        while self.at < self.chars.len() && self.chars[self.at] != '"' {
            let c = self.chars[self.at];
            if c == '\\' && self.at + 1 < self.chars.len() {
                out.push(match self.chars[self.at + 1] {
                    'n' => '\n',
                    't' => '\t',
                    'r' => '\r',
                    next => next,
                });
                self.at += 2;
            } else {
                out.push(c);
                self.at += 1;
            }
        }
        if self.at >= self.chars.len() {
            return Err("Unterminated string in m17n text".to_string());
        }
        self.at += 1;
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_atoms_strings_lists_and_skips_comments() {
        let parsed =
            Sexp::parse_all(";; a comment\n(map (k \"a\\nb\" (insert ?x)) ; lone)\n").unwrap();
        assert_eq!(parsed.len(), 1);
        let map = &parsed[0];
        assert_eq!(map.head(), Some("map"));
        let entry = &map.items()[1];
        assert_eq!(entry.head(), Some("k"));
        assert_eq!(entry.items()[1].atom(), Some("a\nb"));
        assert_eq!(entry.items()[2].head(), Some("insert"));
        // A single `;` is an atom, not a comment.
        assert_eq!(map.items()[2].atom(), Some(";"));
        assert_eq!(map.items()[3].atom(), Some("lone"));
    }

    #[test]
    fn unclosed_list_is_an_error() {
        assert!(Sexp::parse_all("(a (b)").is_err());
        assert!(Sexp::parse_all("\"open").is_err());
    }
}

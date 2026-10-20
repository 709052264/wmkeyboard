//! Physical adjacency on a keyboard layout, for weighting typo corrections:
//! a neighbouring key is a likely slip, a distant one probably a different
//! word. A port of the Kotlin `KeyProximity` (core/prediction): neighbours
//! are the keys either side in the row plus the three straddling the same
//! column in the rows above and below; hands split by column against the
//! widest row.

use std::collections::HashMap;

pub const UNKNOWN: i8 = -1;
pub const LEFT: i8 = 0;
pub const RIGHT: i8 = 1;

#[derive(Clone, Debug)]
pub struct KeyProximity {
    neighbors: HashMap<u16, Vec<u16>>,
    hands: HashMap<u16, i8>,
}

impl PartialEq for KeyProximity {
    fn eq(&self, other: &Self) -> bool {
        self.neighbors == other.neighbors
    }
}

impl KeyProximity {
    /// From the layout's letter rows, one string per row.
    pub fn of_rows<S: AsRef<str>>(rows: &[S]) -> KeyProximity {
        let rows: Vec<Vec<u16>> = rows
            .iter()
            .map(|r| r.as_ref().encode_utf16().collect())
            .collect();
        KeyProximity::of_rows_units(&rows)
    }

    /// From rows of UTF-16 units, as the Kotlin model reads its rows.
    pub fn of_rows_units(rows: &[Vec<u16>]) -> KeyProximity {
        let width = rows.iter().map(Vec::len).max().unwrap_or(0);
        let split = width.div_ceil(2);
        let mut hands = HashMap::new();
        let mut neighbors = HashMap::new();
        for (r, row) in rows.iter().enumerate() {
            for (i, &key) in row.iter().enumerate() {
                hands
                    .entry(key)
                    .or_insert(if i < split { LEFT } else { RIGHT });
                let mut adj = Vec::new();
                if i > 0 {
                    adj.push(row[i - 1]);
                }
                if i + 1 < row.len() {
                    adj.push(row[i + 1]);
                }
                let above = if r > 0 { rows.get(r - 1) } else { None };
                for other in [above, rows.get(r + 1)].into_iter().flatten() {
                    // i - 1 ..= i + 1, the column before the first key being none.
                    for j in i.saturating_sub(1)..=i + 1 {
                        if let Some(&c) = other.get(j) {
                            adj.push(c);
                        }
                    }
                }
                neighbors.insert(key, adj);
            }
        }
        KeyProximity { neighbors, hands }
    }

    /// QWERTY's letter rows, with the number row on top when asked (a
    /// physical keyboard always has one).
    pub fn qwerty(number_row: bool) -> KeyProximity {
        let mut rows = vec!["qwertyuiop", "asdfghjkl", "zxcvbnm"];
        if number_row {
            rows.insert(0, "1234567890");
        }
        KeyProximity::of_rows(&rows)
    }

    pub fn are_adjacent(&self, a: u16, b: u16) -> bool {
        self.neighbors.get(&a).is_some_and(|n| n.contains(&b))
    }

    pub fn hand_of(&self, c: u16) -> i8 {
        self.hands.get(&c).copied().unwrap_or(UNKNOWN)
    }

    /// Whether `a` and `b` are typed by the same hand; false when either is
    /// not on this layout.
    pub fn same_hand(&self, a: u16, b: u16) -> bool {
        let ha = self.hand_of(a);
        ha != UNKNOWN && ha == self.hand_of(b)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn u(c: char) -> u16 {
        c as u16
    }

    #[test]
    fn qwerty_neighbours_and_hands() {
        let q = KeyProximity::qwerty(false);
        assert!(q.are_adjacent(u('q'), u('w')));
        assert!(q.are_adjacent(u('a'), u('s')));
        assert!(q.are_adjacent(u('g'), u('h')));
        assert!(q.are_adjacent(u('e'), u('d')));
        assert!(!q.are_adjacent(u('q'), u('p')));
        assert!(!q.are_adjacent(u(','), u('z')));
        assert!(q.same_hand(u('a'), u('s')));
        assert!(!q.same_hand(u('a'), u('l')));
        // b sits left of the split against the widest row.
        assert_eq!(LEFT, q.hand_of(u('b')));
        assert!(!q.same_hand(u('a'), u('ж')));
    }

    #[test]
    fn number_row_digits_neighbour_the_top_row() {
        let p = KeyProximity::qwerty(true);
        assert!(p.are_adjacent(u('3'), u('e')));
        assert!(p.are_adjacent(u('7'), u('i')));
        assert!(p.are_adjacent(u('8'), u('i')));
        assert!(p.are_adjacent(u('9'), u('o')));
        assert!(p.are_adjacent(u('e'), u('3')));
        assert!(!p.are_adjacent(u('3'), u('s')));
        assert!(!KeyProximity::qwerty(false).are_adjacent(u('3'), u('e')));
        assert!(KeyProximity::qwerty(false) != p);
    }

    #[test]
    fn dvorak_keeps_its_punctuation_columns() {
        let d = KeyProximity::of_rows(&["',.pyfgcrl", "aoeuidhtns", "qjkxbmwvz"]);
        assert!(d.are_adjacent(u('\''), u(',')));
        assert!(d.are_adjacent(u('a'), u('\'')));
    }
}

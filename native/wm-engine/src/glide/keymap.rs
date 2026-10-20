//! `GlideKeyMap`: key centres in key widths and the code points each key
//! types, as the Kotlin map built them (the caller passes its arrays).

/// Java's `Character.toLowerCase(int)`: the simple mapping, one code point
/// to one. Rust's `char::to_lowercase` is the full mapping; the two differ
/// only where the full one is longer, which in Unicode's unconditional
/// rules is U+0130 alone.
pub fn java_to_lower(cp: i32) -> i32 {
    if cp == 0x130 {
        return 'i' as i32;
    }
    let Some(c) = u32::try_from(cp).ok().and_then(char::from_u32) else {
        return cp;
    };
    let mut lower = c.to_lowercase();
    match (lower.next(), lower.next()) {
        (Some(l), None) => l as i32,
        _ => cp,
    }
}

/// Java's `Character.toUpperCase(int)`, likewise the simple mapping.
pub fn java_to_upper(cp: i32) -> i32 {
    let Some(c) = u32::try_from(cp).ok().and_then(char::from_u32) else {
        return cp;
    };
    let mut upper = c.to_uppercase();
    match (upper.next(), upper.next()) {
        (Some(u), None) => u as i32,
        _ => cp,
    }
}

pub struct KeyMap {
    pub key_x: Vec<f32>,
    pub key_y: Vec<f32>,
    code_points: Vec<i32>,
    key_of: Vec<i32>,
    distances: Vec<f32>,
}

impl KeyMap {
    /// From the Kotlin map's arrays: centres, and the sorted code points with
    /// the key each types.
    pub fn new(key_x: Vec<f32>, key_y: Vec<f32>, code_points: Vec<i32>, key_of: Vec<i32>) -> KeyMap {
        let n = key_x.len();
        let mut distances = vec![0f32; n * n];
        for a in 0..n {
            for b in 0..n {
                let dx = key_x[a] - key_x[b];
                let dy = key_y[a] - key_y[b];
                distances[a * n + b] = (dx * dx + dy * dy).sqrt();
            }
        }
        KeyMap { key_x, key_y, code_points, key_of, distances }
    }

    pub fn key_count(&self) -> usize {
        self.key_x.len()
    }

    pub fn distance(&self, a: usize, b: usize) -> f32 {
        self.distances[a * self.key_x.len() + b]
    }

    /// The key typing `cp` (case-folded), or -1.
    pub fn key_index(&self, cp: i32) -> i32 {
        let folded = java_to_lower(cp);
        match self.code_points.binary_search(&folded) {
            Ok(i) => self.key_of[i],
            Err(_) => -1,
        }
    }
}

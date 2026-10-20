//! `LoopScan` and `GlideJoiners`: the loops and wiggles a stroke draws on a
//! key, and the characters a word is spelled with that no key draws.

use super::keymap::{java_to_lower, java_to_upper, KeyMap};
use super::{Point, Tuning};

const MAX_FINE_POINTS: usize = 1024;
const LOOP_RATIO: f32 = 2.5;
const LOOP_ROUNDNESS: f32 = 0.55;
const FINE_SPACING: f32 = 0.08;
const MAX_WINDOW_POINTS: usize = 64;
const TWO_PI: f32 = 2.0 * std::f64::consts::PI as f32;
const NO_WINDOW: i64 = -1;
const MIN_LOOP_EXTENT_SQ: f32 = 0.3 * 0.3;

#[derive(Default)]
pub struct LoopScan {
    pub fine_x: Vec<f32>,
    pub fine_y: Vec<f32>,
    pub fine_count: usize,
    pub fine_step: f32,
    pub window_from: Vec<usize>,
    pub window_to: Vec<usize>,
    pub window_is_loop: Vec<bool>,
    pub centre_x: f32,
    pub centre_y: f32,
    pub length: f32,
    pub chord: f32,
}

fn distance(a: &Point, b: &Point) -> f32 {
    let dx = b.x - a.x;
    let dy = b.y - a.y;
    (dx * dx + dy * dy).sqrt()
}

impl LoopScan {
    /// The loop and wiggle windows along `path`, in fine samples; returns
    /// how many.
    pub fn find(&mut self, path: &[Point], key_width: f32, tuning: &Tuning) -> usize {
        self.window_from.clear();
        self.window_to.clear();
        self.window_is_loop.clear();
        let reach = tuning.loop_extent.max(tuning.wiggle_extent);
        if reach <= 0.0 {
            return 0;
        }
        self.resample_fine(path, key_width);
        let n = self.fine_count;
        let mut a = 0usize;
        while a + 2 < n {
            let mut window = self.roundest_window(a, reach, tuning);
            if window == NO_WINDOW {
                a += 1;
                continue;
            }
            let mut from = a;
            let mut other = a + 1;
            while (other as i64) < window_end(window) as i64 - 1 {
                let later = self.roundest_window(other, reach, tuning);
                if later != NO_WINDOW && window_roundness(later) > window_roundness(window) {
                    window = later;
                    from = other;
                }
                other += 1;
            }
            let b = window_end(window);
            let extent = self.extent_sq(from, b).sqrt();
            let is_loop = window_roundness(window) >= LOOP_ROUNDNESS && extent <= tuning.loop_extent;
            let wiggle = !is_loop && tuning.wiggle_extent > 0.0 && extent <= tuning.wiggle_extent;
            if !is_loop && !wiggle {
                a += 1;
                continue;
            }
            self.window_from.push(from);
            self.window_to.push(b);
            self.window_is_loop.push(is_loop);
            a = b + 1;
        }
        self.window_from.len()
    }

    /// The window's centroid, path length and chord.
    pub fn measure(&mut self, from: usize, to: usize) {
        let (xs, ys) = (&self.fine_x, &self.fine_y);
        let (mut cx, mut cy, mut path) = (0f32, 0f32, 0f32);
        for j in from..=to {
            cx += xs[j];
            cy += ys[j];
            if j > from {
                let sx = xs[j] - xs[j - 1];
                let sy = ys[j] - ys[j - 1];
                path += (sx * sx + sy * sy).sqrt();
            }
        }
        let points = (to - from + 1) as f32;
        self.centre_x = cx / points;
        self.centre_y = cy / points;
        self.length = path;
        let dx = xs[to] - xs[from];
        let dy = ys[to] - ys[from];
        self.chord = (dx * dx + dy * dy).sqrt();
    }

    /// The key nearest (`x`, `y`) within `radius_sq`, the last of equals, or -1.
    pub fn nearest_key(&self, x: f32, y: f32, keys: &KeyMap, radius_sq: f32) -> i32 {
        let mut own = -1;
        let mut nearest_sq = radius_sq;
        for k in 0..keys.key_count() {
            let kx = x - keys.key_x[k];
            let ky = y - keys.key_y[k];
            let d = kx * kx + ky * ky;
            if d <= nearest_sq {
                nearest_sq = d;
                own = k as i32;
            }
        }
        own
    }

    fn resample_fine(&mut self, path: &[Point], key_width: f32) {
        let mut total_px = 0f32;
        for i in 1..path.len() {
            total_px += distance(&path[i - 1], &path[i]);
        }
        let total = total_px / key_width;
        let spacing = FINE_SPACING.max(total / (MAX_FINE_POINTS - 1) as f32);
        let count = (total / spacing) as i32 as usize + 1;
        if self.fine_x.len() < count {
            self.fine_x.resize(count, 0.0);
            self.fine_y.resize(count, 0.0);
        }
        self.fine_step = spacing;
        let step_px = spacing * key_width;
        let first = &path[0];
        self.fine_x[0] = first.x / key_width;
        self.fine_y[0] = first.y / key_width;
        let mut out = 1;
        let mut index = 0;
        let (mut cx, mut cy) = (first.x, first.y);
        let mut accumulated = 0f32;
        while out < count && index + 1 < path.len() {
            let next = &path[index + 1];
            let segment = ((next.x - cx) * (next.x - cx) + (next.y - cy) * (next.y - cy)).sqrt();
            if accumulated + segment >= step_px && segment > 0.0 {
                let fraction = (step_px - accumulated) / segment;
                cx += fraction * (next.x - cx);
                cy += fraction * (next.y - cy);
                self.fine_x[out] = cx / key_width;
                self.fine_y[out] = cy / key_width;
                out += 1;
                accumulated = 0.0;
            } else {
                accumulated += segment;
                cx = next.x;
                cy = next.y;
                index += 1;
            }
        }
        self.fine_count = out;
    }

    fn roundest_window(&self, a: usize, reach: f32, tuning: &Tuning) -> i64 {
        let n = self.fine_count;
        let (xs, ys) = (&self.fine_x, &self.fine_y);
        let (ax, ay) = (xs[a], ys[a]);
        let reach_sq = reach * reach;
        let mut extent_sq = 0f32;
        let mut length = 0f32;
        let mut twice_area = 0f32;
        let mut best_end: i64 = -1;
        let mut best_roundness = 0f32;
        let mut b = a + 1;
        while b < n && b - a <= MAX_WINDOW_POINTS {
            let (bx, by) = (xs[b], ys[b]);
            let sx = bx - xs[b - 1];
            let sy = by - ys[b - 1];
            length += (sx * sx + sy * sy).sqrt();
            twice_area += xs[b - 1] * by - bx * ys[b - 1];
            for i in a..b {
                let dx = xs[i] - bx;
                let dy = ys[i] - by;
                let d = dx * dx + dy * dy;
                if d > extent_sq {
                    extent_sq = d;
                }
            }
            if extent_sq > reach_sq {
                break;
            }
            if length >= tuning.loop_min_arc
                && extent_sq >= MIN_LOOP_EXTENT_SQ
                && length * length >= LOOP_RATIO * LOOP_RATIO * extent_sq
            {
                let closed = twice_area + (bx * ay - ax * by);
                let dx = bx - ax;
                let dy = by - ay;
                let perimeter = length + (dx * dx + dy * dy).sqrt();
                let roundness = TWO_PI * (if closed < 0.0 { -closed } else { closed }) / (perimeter * perimeter);
                if roundness > best_roundness {
                    best_roundness = roundness;
                    best_end = b as i64;
                }
            }
            b += 1;
        }
        if best_end < 0 {
            NO_WINDOW
        } else {
            (best_end << 32) | (best_roundness.to_bits() as i64 & 0xffff_ffff)
        }
    }

    fn extent_sq(&self, a: usize, b: usize) -> f32 {
        let mut extent = 0f32;
        for j in a + 1..=b {
            for i in a..j {
                let dx = self.fine_x[i] - self.fine_x[j];
                let dy = self.fine_y[i] - self.fine_y[j];
                let d = dx * dx + dy * dy;
                if d > extent {
                    extent = d;
                }
            }
        }
        extent
    }
}

fn window_end(window: i64) -> usize {
    (window >> 32) as usize
}

fn window_roundness(window: i64) -> f32 {
    f32::from_bits(window as i32 as u32)
}

const HYPHEN: i32 = 0x2010;
const NON_BREAKING_HYPHEN: i32 = 0x2011;
const ZERO_WIDTH_NON_JOINER: i32 = 0x200C;

/// `GlideJoiners.isJoiner`: a character a word is spelled with and a finger
/// cannot draw.
pub fn is_joiner(cp: i32) -> bool {
    matches!(
        cp,
        0x2D | HYPHEN | NON_BREAKING_HYPHEN | 0x2E | 0x40 | 0x5F | 0x26 | 0x2B | 0x2F | ZERO_WIDTH_NON_JOINER
    )
}

fn is_hyphen(cp: i32) -> bool {
    cp == 0x2D || cp == HYPHEN || cp == NON_BREAKING_HYPHEN
}

/// The code point before `at` in UTF-16 `word`, as `codePointBefore`.
fn code_point_before(word: &[u16], at: usize) -> i32 {
    let low = word[at - 1];
    if (0xDC00..0xE000).contains(&low) && at >= 2 {
        let high = word[at - 2];
        if (0xD800..0xDC00).contains(&high) {
            return 0x10000 + (((high as i32) - 0xD800) << 10) + ((low as i32) - 0xDC00);
        }
    }
    low as i32
}

/// `regionMatches(ignoreCase = true)` over single UTF-16 units, as Java
/// compares them.
fn same_ignoring_case(a: u16, b: u16) -> bool {
    if a == b {
        return true;
    }
    let (ua, ub) = (java_to_upper(a as i32), java_to_upper(b as i32));
    ua == ub || java_to_lower(ua) == java_to_lower(ub)
}

/// `GlideJoiners.isNonWord`: a list's interrupted word, stray full stop or
/// stutter ("th-the").
pub fn is_non_word(word: &[u16]) -> bool {
    if word.is_empty() {
        return false;
    }
    if is_joiner(code_point_before(word, word.len())) {
        return true;
    }
    let Some(hyphen) = word.iter().position(|&c| is_hyphen(c as i32)) else {
        return false;
    };
    if hyphen == 0 {
        return false;
    }
    let rest = word.len() - hyphen - 1;
    hyphen < rest && (0..hyphen).all(|i| same_ignoring_case(word[hyphen + 1 + i], word[i]))
}

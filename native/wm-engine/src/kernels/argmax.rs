//! The index of each row's largest value: the greedy step of a CTC decode
//! over a recogniser's logits (`PaddleOcrPipeline.ctcDecode` on the Kotlin
//! side, 40 rows of 18,385 classes per piece of a line).
//!
//! The answer is the Kotlin loop's: start at index 0 and move only to a
//! value strictly greater than the best so far. So on a tie the first index
//! wins, `-0.0` and `0.0` tie, a NaN never wins, and a row that starts with
//! a NaN answers 0.
//!
//! Tolerance: 0. Comparisons only, no arithmetic.

#[cfg(target_arch = "aarch64")]
use super::active;

/// The argmax of every `cols`-wide row of `data` into `out`, one per row; a
/// trailing partial row is ignored. Returns how many it wrote.
pub fn argmax_rows(data: &[f32], cols: usize, out: &mut [u32]) -> usize {
    if cols == 0 {
        return 0;
    }
    let rows = (data.len() / cols).min(out.len());
    for (row, slot) in data.chunks_exact(cols).take(rows).zip(out.iter_mut()) {
        *slot = argmax(row);
    }
    rows
}

/// The argmax of one row (see the module comment); 0 for an empty one.
pub fn argmax(row: &[f32]) -> u32 {
    #[cfg(target_arch = "aarch64")]
    if active().neon {
        // SAFETY: NEON is present (checked above).
        return unsafe { neon::argmax(row) };
    }
    scalar::argmax(row)
}

pub mod scalar {
    pub fn argmax(row: &[f32]) -> u32 {
        let Some(&first) = row.first() else { return 0 };
        let (mut best, mut best_value) = (0, first);
        for (k, &value) in row.iter().enumerate().skip(1) {
            if value > best_value {
                best = k;
                best_value = value;
            }
        }
        best as u32
    }
}

#[cfg(target_arch = "aarch64")]
pub mod neon {
    use std::arch::aarch64::*;

    /// Sixteen lanes in four accumulators, each a best value and its index.
    /// Every lane starts at `row[0]` with index 0 and moves only on a strict
    /// `fcmgt`, which is false for a NaN, so each lane holds the first
    /// maximum of the columns it saw. Across lanes the largest value wins
    /// and, among lanes equal to it, the smallest index; a lane that never
    /// moved says 0, which is right, because then nothing beat `row[0]`.
    /// The tail goes through the same strict rule, after every lane.
    ///
    /// A NaN at `row[0]` is the one start no value can beat, so it answers
    /// 0 straight away.
    ///
    /// # Safety
    /// The CPU must have NEON.
    #[target_feature(enable = "neon")]
    pub unsafe fn argmax(row: &[f32]) -> u32 {
        let Some(&first) = row.first() else { return 0 };
        if first.is_nan() {
            return 0;
        }
        let n = row.len();
        let p = row.as_ptr();
        let start = vdupq_n_f32(first);
        let (mut v0, mut v1, mut v2, mut v3) = (start, start, start, start);
        let zero = vdupq_n_u32(0);
        let (mut i0, mut i1, mut i2, mut i3) = (zero, zero, zero, zero);
        let mut at = vld1q_u32([0u32, 1, 2, 3].as_ptr());
        let four = vdupq_n_u32(4);
        let mut k = 0;
        while k + 16 <= n {
            let a = vld1q_f32(p.add(k));
            let b = vld1q_f32(p.add(k + 4));
            let c = vld1q_f32(p.add(k + 8));
            let d = vld1q_f32(p.add(k + 12));
            let (ma, mb, mc, md) = (vcgtq_f32(a, v0), vcgtq_f32(b, v1), vcgtq_f32(c, v2), vcgtq_f32(d, v3));
            v0 = vbslq_f32(ma, a, v0);
            v1 = vbslq_f32(mb, b, v1);
            v2 = vbslq_f32(mc, c, v2);
            v3 = vbslq_f32(md, d, v3);
            let bt = vaddq_u32(at, four);
            let ct = vaddq_u32(bt, four);
            let dt = vaddq_u32(ct, four);
            i0 = vbslq_u32(ma, at, i0);
            i1 = vbslq_u32(mb, bt, i1);
            i2 = vbslq_u32(mc, ct, i2);
            i3 = vbslq_u32(md, dt, i3);
            at = vaddq_u32(dt, four);
            k += 16;
        }
        // No lane holds a NaN, so `fmax` is the true maximum, and `fcmeq`
        // finds every lane tied with it (both zeros included).
        let top = vmaxvq_f32(vmaxq_f32(vmaxq_f32(v0, v1), vmaxq_f32(v2, v3)));
        let top_v = vdupq_n_f32(top);
        let none = vdupq_n_u32(u32::MAX);
        let pick = |v, i| vbslq_u32(vceqq_f32(v, top_v), i, none);
        let firsts = vminq_u32(vminq_u32(pick(v0, i0), pick(v1, i1)), vminq_u32(pick(v2, i2), pick(v3, i3)));
        let (mut best, mut best_value) = (vminvq_u32(firsts) as usize, top);
        for (j, &value) in row.iter().enumerate().skip(k) {
            if value > best_value {
                best = j;
                best_value = value;
            }
        }
        best as u32
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kernels::testing::{Rng, LENGTHS};

    /// Rows with everything the strict rule has to get right: ties at the
    /// top, both zeros, infinities and NaNs, in every position.
    fn awkward(rng: &mut Rng, n: usize) -> Vec<Vec<f32>> {
        let mut rows = vec![rng.floats(n)];
        // Coarse values, so the maximum repeats across lanes and the tail.
        rows.push((0..n).map(|_| (rng.next_u64() % 4) as f32).collect());
        rows.push(vec![0.5; n]);
        rows.push((0..n).map(|i| if i % 3 == 0 { -0.0 } else { 0.0 }).collect());
        rows.push((0..n).map(|i| if i % 2 == 0 { 0.0 } else { -0.0 }).collect());
        rows.push(vec![f32::NEG_INFINITY; n]);
        let mut spiked = rng.floats(n);
        for (i, v) in spiked.iter_mut().enumerate() {
            match rng.next_u64() % 9 {
                0 => *v = f32::NAN,
                1 => *v = f32::INFINITY,
                2 => *v = f32::NEG_INFINITY,
                3 if i % 2 == 1 => *v = 1.0,
                _ => {}
            }
        }
        rows.push(spiked);
        if n > 0 {
            for at in [0, n / 2, n - 1] {
                let mut nan = rng.floats(n);
                nan[at] = f32::NAN;
                rows.push(nan);
                let mut top = vec![-1.0; n];
                top[at] = 2.0;
                top[n - 1 - at] = 2.0;
                rows.push(top);
            }
        }
        rows
    }

    #[test]
    fn the_first_maximum_wins() {
        assert_eq!(0, scalar::argmax(&[]));
        assert_eq!(1, scalar::argmax(&[0.1, 0.9, 0.9, 0.2]));
        assert_eq!(0, scalar::argmax(&[f32::NAN, 5.0]));
        assert_eq!(2, scalar::argmax(&[0.0, f32::NAN, 3.0]));
        assert_eq!(0, scalar::argmax(&[-0.0, 0.0]));
    }

    #[test]
    fn dispatch_matches_the_reference() {
        let mut rng = Rng::new(31);
        for &n in LENGTHS.iter().chain(&[18_385]) {
            for row in awkward(&mut rng, n) {
                assert_eq!(scalar::argmax(&row), argmax(&row), "n={n} {row:?}");
            }
        }
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn neon_matches_the_reference() {
        let mut rng = Rng::new(37);
        for &n in LENGTHS.iter().chain(&[18_385]) {
            for row in awkward(&mut rng, n) {
                assert_eq!(scalar::argmax(&row), unsafe { neon::argmax(&row) }, "n={n} {row:?}");
            }
        }
    }

    #[test]
    fn rows_answer_each_row_and_ignore_a_partial_one() {
        let data = [0.0, 1.0, 0.0, 3.0, 2.0, 1.0, 0.5, 0.5, 0.5, 9.0];
        let mut out = [7u32; 4];
        assert_eq!(3, argmax_rows(&data, 3, &mut out));
        assert_eq!([1, 0, 0, 7], out);
        assert_eq!(0, argmax_rows(&data, 0, &mut out));
        assert_eq!(1, argmax_rows(&data, 3, &mut out[..1]));
    }
}

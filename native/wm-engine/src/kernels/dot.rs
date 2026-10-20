//! Dot products: one query against a matrix of row vectors, the shape of an
//! embedding search (`VectorIndex.score` on the Kotlin side). `f32` rows,
//! and `i8` rows for a quantised index, where `sdot` does sixteen
//! multiply-adds per instruction.
//!
//! Tolerance: the `f32` versions sum in a different order than the
//! reference (four or eight lanes, then across), so they agree within
//! 1e-5 of the sum of the absolute products. The `i8` versions are exact.

#[cfg(target_arch = "aarch64")]
use super::active;

/// `a · b` over the shorter of the two.
pub fn dot_f32(a: &[f32], b: &[f32]) -> f32 {
    let n = a.len().min(b.len());
    let (a, b) = (&a[..n], &b[..n]);
    #[cfg(target_arch = "aarch64")]
    if active().neon {
        // SAFETY: NEON is present (checked above).
        return unsafe { neon::dot_f32(a, b) };
    }
    scalar::dot_f32(a, b)
}

/// `query · row` for every `dim`-wide row of `matrix` into `out`, one per
/// row; a trailing partial row is ignored. Returns how many it wrote.
pub fn dot_rows_f32(query: &[f32], matrix: &[f32], dim: usize, out: &mut [f32]) -> usize {
    if dim == 0 || query.len() < dim {
        return 0;
    }
    let rows = (matrix.len() / dim).min(out.len());
    for (row, slot) in matrix.chunks_exact(dim).take(rows).zip(out.iter_mut()) {
        *slot = dot_f32(&query[..dim], row);
    }
    rows
}

/// `a · b` of two `i8` vectors, exact in `i32` (up to 2^17 elements).
pub fn dot_i8(a: &[i8], b: &[i8]) -> i32 {
    let n = a.len().min(b.len());
    let (a, b) = (&a[..n], &b[..n]);
    #[cfg(target_arch = "aarch64")]
    {
        let features = active();
        if features.dotprod {
            // SAFETY: dotprod is present (checked above).
            return unsafe { dotprod::dot_i8(a, b) };
        }
        if features.neon {
            // SAFETY: NEON is present (checked above).
            return unsafe { neon::dot_i8(a, b) };
        }
    }
    scalar::dot_i8(a, b)
}

pub mod scalar {
    pub fn dot_f32(a: &[f32], b: &[f32]) -> f32 {
        a.iter().zip(b).map(|(x, y)| x * y).sum()
    }

    pub fn dot_i8(a: &[i8], b: &[i8]) -> i32 {
        a.iter().zip(b).map(|(&x, &y)| x as i32 * y as i32).sum()
    }
}

#[cfg(target_arch = "aarch64")]
pub mod neon {
    use std::arch::aarch64::*;

    /// Eight lanes in two accumulators, so consecutive `fmla`s do not wait
    /// on each other; the tail goes through the reference.
    ///
    /// # Safety
    /// The CPU must have NEON. `a` and `b` must be the same length.
    #[target_feature(enable = "neon")]
    pub unsafe fn dot_f32(a: &[f32], b: &[f32]) -> f32 {
        let n = a.len();
        let (pa, pb) = (a.as_ptr(), b.as_ptr());
        let mut acc0 = vdupq_n_f32(0.0);
        let mut acc1 = vdupq_n_f32(0.0);
        let mut i = 0;
        while i + 8 <= n {
            acc0 = vfmaq_f32(acc0, vld1q_f32(pa.add(i)), vld1q_f32(pb.add(i)));
            acc1 = vfmaq_f32(acc1, vld1q_f32(pa.add(i + 4)), vld1q_f32(pb.add(i + 4)));
            i += 8;
        }
        if i + 4 <= n {
            acc0 = vfmaq_f32(acc0, vld1q_f32(pa.add(i)), vld1q_f32(pb.add(i)));
            i += 4;
        }
        vaddvq_f32(vaddq_f32(acc0, acc1)) + super::scalar::dot_f32(&a[i..], &b[i..])
    }

    /// Widening multiplies (`smull`) into `i16`, pairwise-accumulated into
    /// `i32` (`sadalp`). Exact.
    ///
    /// # Safety
    /// The CPU must have NEON. `a` and `b` must be the same length.
    #[target_feature(enable = "neon")]
    pub unsafe fn dot_i8(a: &[i8], b: &[i8]) -> i32 {
        let n = a.len();
        let (pa, pb) = (a.as_ptr(), b.as_ptr());
        let mut acc = vdupq_n_s32(0);
        let mut i = 0;
        while i + 16 <= n {
            let (x, y) = (vld1q_s8(pa.add(i)), vld1q_s8(pb.add(i)));
            acc = vpadalq_s16(acc, vmull_s8(vget_low_s8(x), vget_low_s8(y)));
            acc = vpadalq_s16(acc, vmull_high_s8(x, y));
            i += 16;
        }
        vaddvq_s32(acc) + super::scalar::dot_i8(&a[i..], &b[i..])
    }
}

#[cfg(target_arch = "aarch64")]
pub mod dotprod {
    use std::arch::aarch64::*;

    /// `sdot`: sixteen products summed into four lanes per instruction.
    ///
    /// # Safety
    /// The CPU must have the dotprod extension. `a` and `b` must be the same
    /// length.
    #[target_feature(enable = "neon,dotprod")]
    pub unsafe fn dot_i8(a: &[i8], b: &[i8]) -> i32 {
        let n = a.len();
        let (pa, pb) = (a.as_ptr(), b.as_ptr());
        let mut acc = vdupq_n_s32(0);
        let mut i = 0;
        while i + 16 <= n {
            acc = vdotq_s32(acc, vld1q_s8(pa.add(i)), vld1q_s8(pb.add(i)));
            i += 16;
        }
        vaddvq_s32(acc) + super::scalar::dot_i8(&a[i..], &b[i..])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kernels::testing::{assert_close, Rng, LENGTHS};

    const F32_TOLERANCE: f32 = 1e-5;

    fn abs_sum(a: &[f32], b: &[f32]) -> f32 {
        a.iter().zip(b).map(|(x, y)| (x * y).abs()).sum()
    }

    #[test]
    fn f32_dispatch_matches_the_reference() {
        let mut rng = Rng::new(7);
        for &n in LENGTHS {
            let (a, b) = (rng.floats(n), rng.floats(n));
            let what = format!("dot_f32 n={n}");
            assert_close(scalar::dot_f32(&a, &b), dot_f32(&a, &b), abs_sum(&a, &b), F32_TOLERANCE, &what);
        }
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn f32_neon_matches_the_reference() {
        let mut rng = Rng::new(11);
        for &n in LENGTHS {
            let (a, b) = (rng.floats(n), rng.floats(n));
            let simd = unsafe { neon::dot_f32(&a, &b) };
            let what = format!("neon dot_f32 n={n}");
            assert_close(scalar::dot_f32(&a, &b), simd, abs_sum(&a, &b), F32_TOLERANCE, &what);
        }
    }

    #[test]
    fn rows_score_each_row_and_ignore_a_partial_one() {
        let mut rng = Rng::new(3);
        let dim = 37;
        let query = rng.floats(dim);
        let matrix = rng.floats(dim * 5 + 4);
        let mut out = vec![0.0; 8];
        assert_eq!(5, dot_rows_f32(&query, &matrix, dim, &mut out));
        for (r, &score) in out.iter().take(5).enumerate() {
            let row = &matrix[r * dim..(r + 1) * dim];
            assert_close(scalar::dot_f32(&query, row), score, abs_sum(&query, row), F32_TOLERANCE, "row");
        }
        assert_eq!(0, dot_rows_f32(&query, &matrix, 0, &mut out));
        assert_eq!(2, dot_rows_f32(&query, &matrix, dim, &mut out[..2]));
    }

    #[test]
    fn i8_dispatch_is_exact() {
        let mut rng = Rng::new(5);
        for &n in LENGTHS {
            let (a, b) = (rng.bytes(n), rng.bytes(n));
            assert_eq!(scalar::dot_i8(&a, &b), dot_i8(&a, &b), "dot_i8 n={n}");
        }
        // The extremes, where a 16-bit intermediate would overflow.
        let (a, b) = (vec![-128i8; 1024], vec![-128i8; 1024]);
        assert_eq!(1024 * 16384, dot_i8(&a, &b));
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn i8_neon_and_dotprod_are_exact() {
        let mut rng = Rng::new(13);
        for &n in LENGTHS {
            let (a, b) = (rng.bytes(n), rng.bytes(n));
            let reference = scalar::dot_i8(&a, &b);
            assert_eq!(reference, unsafe { neon::dot_i8(&a, &b) }, "neon dot_i8 n={n}");
            if crate::kernels::detected().dotprod {
                assert_eq!(reference, unsafe { dotprod::dot_i8(&a, &b) }, "dotprod dot_i8 n={n}");
            }
        }
    }
}

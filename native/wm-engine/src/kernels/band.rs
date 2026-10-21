//! The banded min-plus step of the glide decoders: for one sample `j` of a
//! letter's column, the cheapest way in from the band of samples the
//! previous letter may have ended on, each charged for how far the finger
//! travelled against how far apart the keys are (`glide::Decoder::advance`
//! and `build_remain`, `glide::duet::DuetDecoder::advance`). With
//! `gap_window` 14 the band is about thirty samples wide, and these loops
//! are nine tenths of a decode.
//!
//! Each function is the loop it replaced, element for element: the same
//! `f32` operations in the same order (no fused multiply-add), a value
//! skipped when its column or remainder is unreachable, and the smallest
//! value kept. The NEON versions compute four samples at a time and mask
//! the unreachable ones to `cut`; with no NaN anywhere, and no `-0.0`
//! possible (a charge is `+0.0` or positive), the minimum of the same values
//! is the same value whatever order it is taken in.
//!
//! Tolerance: 0.

#[cfg(target_arch = "aarch64")]
use super::active;

/// `min(cut, cols[k] + charge((arc_j - arc[k]) - span, ease))` over every
/// `k` whose `cols[k] < cut`: `Decoder::advance`'s band, past sample 0.
/// `charge(gap, ease)` is `weight * (|gap| - ease)` when that is positive,
/// else 0. Reads `min(cols.len(), arc.len())` samples.
pub fn min_arc(cols: &[f32], arc: &[f32], arc_j: f32, span: f32, ease: f32, weight: f32, cut: f32) -> f32 {
    #[cfg(target_arch = "aarch64")]
    if active().neon {
        // SAFETY: NEON is present (checked above).
        return unsafe { neon::min_arc(cols, arc, arc_j, span, ease, weight, cut) };
    }
    scalar::min_arc(cols, arc, arc_j, span, ease, weight, cut)
}

/// The same with the gap counted in steps: sample `k` lies `steps - k`
/// samples before `j`, so its gap is `(steps - k) as f32 * step - span`
/// (`DuetDecoder::advance`, past sample 0).
pub fn min_steps(cols: &[f32], steps: i32, step: f32, span: f32, ease: f32, weight: f32, cut: f32) -> f32 {
    #[cfg(target_arch = "aarch64")]
    if active().neon {
        // SAFETY: NEON is present (checked above).
        return unsafe { neon::min_steps(cols, steps, step, span, ease, weight, cut) };
    }
    scalar::min_steps(cols, steps, step, span, ease, weight, cut)
}

/// `min(cut, charge((arc[k] - arc_j) - span, ease) + cost[k] + rest[k])` over
/// every `k` whose `rest[k] < cut`: `Decoder::build_remain`'s band, looking
/// ahead from sample `j`, with the point costs and remainders of one key in
/// sample order. Reads the shortest of the three slices.
pub fn min_ahead(cost: &[f32], rest: &[f32], arc: &[f32], arc_j: f32, span: f32, ease: f32, weight: f32, cut: f32) -> f32 {
    #[cfg(target_arch = "aarch64")]
    if active().neon {
        // SAFETY: NEON is present (checked above).
        return unsafe { neon::min_ahead(cost, rest, arc, arc_j, span, ease, weight, cut) };
    }
    scalar::min_ahead(cost, rest, arc, arc_j, span, ease, weight, cut)
}

pub mod scalar {
    #[inline(always)]
    pub fn charge(gap: f32, ease: f32, weight: f32) -> f32 {
        let over = (if gap < 0.0 { -gap } else { gap }) - ease;
        if over > 0.0 {
            weight * over
        } else {
            0.0
        }
    }

    pub fn min_arc(cols: &[f32], arc: &[f32], arc_j: f32, span: f32, ease: f32, weight: f32, cut: f32) -> f32 {
        let mut best = cut;
        for (&at, &a) in cols.iter().zip(arc) {
            if at < cut {
                let travelled = arc_j - a;
                let v = at + charge(travelled - span, ease, weight);
                if v < best {
                    best = v;
                }
            }
        }
        best
    }

    pub fn min_steps(cols: &[f32], steps: i32, step: f32, span: f32, ease: f32, weight: f32, cut: f32) -> f32 {
        let mut best = cut;
        for (k, &at) in cols.iter().enumerate() {
            if at < cut {
                let gap = (steps - k as i32) as f32 * step - span;
                let v = at + charge(gap, ease, weight);
                if v < best {
                    best = v;
                }
            }
        }
        best
    }

    #[allow(clippy::too_many_arguments)]
    pub fn min_ahead(cost: &[f32], rest: &[f32], arc: &[f32], arc_j: f32, span: f32, ease: f32, weight: f32, cut: f32) -> f32 {
        let mut best = cut;
        for ((&c, &r), &a) in cost.iter().zip(rest).zip(arc) {
            if r < cut {
                let v = charge(a - arc_j - span, ease, weight) + c + r;
                if v < best {
                    best = v;
                }
            }
        }
        best
    }
}

#[cfg(target_arch = "aarch64")]
pub mod neon {
    use super::scalar;
    use std::arch::aarch64::*;

    /// `charge` on four lanes: `|gap| - ease`, then `weight * over` where
    /// that is positive and `+0.0` elsewhere. `vabsq` turns a `-0.0` gap
    /// into `+0.0`, which changes nothing: `±0.0 - ease` is `-ease` for any
    /// nonzero ease, and for a zero ease neither sign of zero is positive.
    #[inline(always)]
    unsafe fn charge(gap: float32x4_t, ease: float32x4_t, weight: float32x4_t) -> float32x4_t {
        let over = vsubq_f32(vabsq_f32(gap), ease);
        vbslq_f32(vcgtq_f32(over, vdupq_n_f32(0.0)), vmulq_f32(weight, over), vdupq_n_f32(0.0))
    }

    /// # Safety
    /// The CPU must have NEON.
    #[target_feature(enable = "neon")]
    pub unsafe fn min_arc(cols: &[f32], arc: &[f32], arc_j: f32, span: f32, ease: f32, weight: f32, cut: f32) -> f32 {
        let n = cols.len().min(arc.len());
        let (vj, vs, ve, vw, vc) = (vdupq_n_f32(arc_j), vdupq_n_f32(span), vdupq_n_f32(ease), vdupq_n_f32(weight), vdupq_n_f32(cut));
        let (mut b0, mut b1) = (vc, vc);
        let (c, a) = (cols.as_ptr(), arc.as_ptr());
        let mut k = 0;
        while k + 8 <= n {
            let at0 = vld1q_f32(c.add(k));
            let at1 = vld1q_f32(c.add(k + 4));
            let v0 = vaddq_f32(at0, charge(vsubq_f32(vsubq_f32(vj, vld1q_f32(a.add(k))), vs), ve, vw));
            let v1 = vaddq_f32(at1, charge(vsubq_f32(vsubq_f32(vj, vld1q_f32(a.add(k + 4))), vs), ve, vw));
            b0 = vminq_f32(b0, vbslq_f32(vcltq_f32(at0, vc), v0, vc));
            b1 = vminq_f32(b1, vbslq_f32(vcltq_f32(at1, vc), v1, vc));
            k += 8;
        }
        if k + 4 <= n {
            let at = vld1q_f32(c.add(k));
            let v = vaddq_f32(at, charge(vsubq_f32(vsubq_f32(vj, vld1q_f32(a.add(k))), vs), ve, vw));
            b0 = vminq_f32(b0, vbslq_f32(vcltq_f32(at, vc), v, vc));
            k += 4;
        }
        let best = vminvq_f32(vminq_f32(b0, b1));
        let tail = scalar::min_arc(&cols[k..n], &arc[k..n], arc_j, span, ease, weight, cut);
        if tail < best {
            tail
        } else {
            best
        }
    }

    /// # Safety
    /// The CPU must have NEON.
    #[target_feature(enable = "neon")]
    pub unsafe fn min_steps(cols: &[f32], steps: i32, step: f32, span: f32, ease: f32, weight: f32, cut: f32) -> f32 {
        let n = cols.len();
        let (vstep, vs, ve, vw, vc) = (vdupq_n_f32(step), vdupq_n_f32(span), vdupq_n_f32(ease), vdupq_n_f32(weight), vdupq_n_f32(cut));
        let lanes: [i32; 4] = [0, 1, 2, 3];
        let mut back = vsubq_s32(vdupq_n_s32(steps), vld1q_s32(lanes.as_ptr()));
        let four = vdupq_n_s32(4);
        let mut best_v = vc;
        let c = cols.as_ptr();
        let mut k = 0;
        while k + 4 <= n {
            let at = vld1q_f32(c.add(k));
            let gap = vsubq_f32(vmulq_f32(vcvtq_f32_s32(back), vstep), vs);
            let v = vaddq_f32(at, charge(gap, ve, vw));
            best_v = vminq_f32(best_v, vbslq_f32(vcltq_f32(at, vc), v, vc));
            back = vsubq_s32(back, four);
            k += 4;
        }
        let best = vminvq_f32(best_v);
        let tail = scalar::min_steps(&cols[k..], steps - k as i32, step, span, ease, weight, cut);
        if tail < best {
            tail
        } else {
            best
        }
    }

    /// # Safety
    /// The CPU must have NEON.
    #[allow(clippy::too_many_arguments)]
    #[target_feature(enable = "neon")]
    pub unsafe fn min_ahead(cost: &[f32], rest: &[f32], arc: &[f32], arc_j: f32, span: f32, ease: f32, weight: f32, cut: f32) -> f32 {
        let n = cost.len().min(rest.len()).min(arc.len());
        let (vj, vs, ve, vw, vc) = (vdupq_n_f32(arc_j), vdupq_n_f32(span), vdupq_n_f32(ease), vdupq_n_f32(weight), vdupq_n_f32(cut));
        let mut best_v = vc;
        let (pc, pr, pa) = (cost.as_ptr(), rest.as_ptr(), arc.as_ptr());
        let mut k = 0;
        while k + 4 <= n {
            let r = vld1q_f32(pr.add(k));
            let gap = vsubq_f32(vsubq_f32(vld1q_f32(pa.add(k)), vj), vs);
            let v = vaddq_f32(vaddq_f32(charge(gap, ve, vw), vld1q_f32(pc.add(k))), r);
            best_v = vminq_f32(best_v, vbslq_f32(vcltq_f32(r, vc), v, vc));
            k += 4;
        }
        let best = vminvq_f32(best_v);
        let tail = scalar::min_ahead(&cost[k..n], &rest[k..n], &arc[k..n], arc_j, span, ease, weight, cut);
        if tail < best {
            tail
        } else {
            best
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kernels::testing::{Rng, LENGTHS};

    const CUT: f32 = f32::MAX / 4.0;

    /// A column as the decoders hold one: costs from 0 to a few, some
    /// unreachable, and exact zeros (a sample right on its key).
    fn column(r: &mut Rng, n: usize) -> Vec<f32> {
        (0..n)
            .map(|_| match r.below(10) {
                0 | 1 => CUT,
                2 => 0.0,
                _ => r.f32() * 6.0,
            })
            .collect()
    }

    fn arcs(n: usize, step: f32) -> Vec<f32> {
        (0..n).map(|j| j as f32 * step).collect()
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn neon_matches_the_reference_bit_for_bit() {
        let mut r = Rng::new(41);
        for &n in LENGTHS {
            for round in 0..40 {
                let step = 0.05 + r.f32() * 0.4;
                let cols = column(&mut r, n);
                let rest = column(&mut r, n);
                let cost: Vec<f32> = (0..n).map(|_| if r.below(4) == 0 { 0.0 } else { r.f32() * 9.0 }).collect();
                let arc = arcs(n, step);
                let arc_j = (n as f32 + r.f32() * 4.0) * step;
                let span = r.f32() * 6.0;
                // A zero ease half the time, as most samples have.
                let ease = if round % 2 == 0 { 0.0 } else { r.f32() };
                let weight = 2.0;
                let steps = n as i32 + r.below(5) as i32;
                unsafe {
                    assert_eq!(
                        scalar::min_arc(&cols, &arc, arc_j, span, ease, weight, CUT).to_bits(),
                        neon::min_arc(&cols, &arc, arc_j, span, ease, weight, CUT).to_bits(),
                        "min_arc n={n}"
                    );
                    assert_eq!(
                        scalar::min_steps(&cols, steps, step, span, ease, weight, CUT).to_bits(),
                        neon::min_steps(&cols, steps, step, span, ease, weight, CUT).to_bits(),
                        "min_steps n={n}"
                    );
                    assert_eq!(
                        scalar::min_ahead(&cost, &rest, &arc, arc_j * 0.1, span, ease, weight, CUT).to_bits(),
                        neon::min_ahead(&cost, &rest, &arc, arc_j * 0.1, span, ease, weight, CUT).to_bits(),
                        "min_ahead n={n}"
                    );
                }
            }
        }
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn gaps_of_exactly_the_ease_and_signed_zeros_agree() {
        // Gaps landing on ±ease and on ±0, where the charge's sign tests sit.
        let arc = [0.0f32, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0];
        let cols = [1.0f32, 0.0, 2.0, CUT, 0.25, 3.0, 0.0, 1.5, 0.75];
        for &(arc_j, span, ease) in &[(2.0f32, 0.0f32, 0.0f32), (2.0, 1.0, 1.0), (2.0, 0.5, 0.5), (4.0, 4.0, 0.0), (3.0, 1.0, 0.5)] {
            unsafe {
                assert_eq!(
                    scalar::min_arc(&cols, &arc, arc_j, span, ease, 2.0, CUT).to_bits(),
                    neon::min_arc(&cols, &arc, arc_j, span, ease, 2.0, CUT).to_bits()
                );
                assert_eq!(
                    scalar::min_ahead(&cols, &cols, &arc, arc_j, span, ease, 2.0, CUT).to_bits(),
                    neon::min_ahead(&cols, &cols, &arc, arc_j, span, ease, 2.0, CUT).to_bits()
                );
            }
        }
    }

    #[test]
    fn nothing_reachable_answers_the_cut() {
        let cols = [CUT; 9];
        let arc = arcs(9, 0.3);
        assert_eq!(CUT, min_arc(&cols, &arc, 3.0, 1.0, 0.0, 2.0, CUT));
        assert_eq!(CUT, min_steps(&cols, 12, 0.3, 1.0, 0.0, 2.0, CUT));
        assert_eq!(CUT, min_ahead(&cols, &cols, &arc, 0.0, 1.0, 0.0, 2.0, CUT));
        assert_eq!(CUT, min_arc(&[], &[], 3.0, 1.0, 0.0, 2.0, CUT));
    }

    #[test]
    fn the_charge_is_the_gap_past_the_ease() {
        // One reachable sample 1.0 key short of the span, eased by 0.25.
        let v = min_arc(&[0.5], &[0.0], 1.0, 2.0, 0.25, 2.0, CUT);
        assert_eq!(0.5 + 2.0 * 0.75, v);
    }
}

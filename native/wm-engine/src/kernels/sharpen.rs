//! The photo editor's 3×3 sharpen (`PhotoEditRenderer.sharpenInPlace` on
//! the Kotlin side): each colour channel pushed away from the mean of its
//! four neighbours, alpha kept.
//!
//! Pixels are Android `ARGB_8888` colour ints as `Bitmap.getPixels` gives
//! them (unpremultiplied), so in memory each is the bytes B, G, R, A.
//!
//! Exact: the result is bit for bit Kotlin's. The arithmetic is `f32` in
//! Kotlin's order, `c + a * k / 4` with `k = 4c - l - r - u - d`, then
//! rounded half up and clamped to 0..=255. The NEON version does the same
//! operations on four lanes at a time (a separate multiply and add, never a
//! fused one), and rounds with `fcvtas` (half away from zero), which equals
//! Java's `Math.round` for every value that is not clamped to 0 anyway.

#[cfg(target_arch = "aarch64")]
use super::active;

/// Sharpens rows `first..first + count` of `src`, `width` pixels a row, into
/// `out` (`count` rows). A pixel's neighbours are clamped to the picture:
/// the left of column 0 is itself, and so is the row above `src`'s first
/// row and below its last. So a whole picture is `first = 0, count = rows`,
/// and a band of a larger one passes its output rows plus one row of the
/// picture either side of them. Returns the rows written: 0 when the shape
/// does not fit the slices.
pub fn sharpen_argb(src: &[u32], width: usize, first: usize, count: usize, a: f32, out: &mut [u32]) -> usize {
    if width == 0 {
        return 0;
    }
    let rows = src.len() / width;
    if first.checked_add(count).is_none_or(|end| end > rows) || out.len() < count * width {
        return 0;
    }
    #[cfg(target_arch = "aarch64")]
    if active().neon {
        // SAFETY: NEON is present (checked above), and the shape fits (checked above).
        unsafe { neon::sharpen_argb(src, width, first, count, a, out) };
        return count;
    }
    scalar::sharpen_argb(src, width, first, count, a, out);
    count
}

pub mod scalar {
    /// One channel: Kotlin's `sharpenChannel`, `(c + a * (4c - l - r - u - d) / 4f).roundToInt().coerceIn(0, 255)`.
    #[inline]
    pub fn channel(c: u32, l: u32, r: u32, u: u32, d: u32, a: f32) -> u32 {
        let k = 4 * c as i32 - l as i32 - r as i32 - u as i32 - d as i32;
        let v = c as f32 + a * k as f32 / 4.0;
        // Math.round(float) is round half up; f32::round is half away from
        // zero. They differ only below zero, which clamps to 0 either way.
        // (Kotlin throws on NaN, which a finite amount never produces.)
        v.round().clamp(0.0, 255.0) as u32
    }

    /// One pixel from its centre and four neighbours.
    #[inline]
    pub fn pixel(c: u32, l: u32, r: u32, u: u32, d: u32, a: f32) -> u32 {
        let at = |p: u32, shift: u32| p >> shift & 0xff;
        let ch = |shift: u32| channel(at(c, shift), at(l, shift), at(r, shift), at(u, shift), at(d, shift), a) << shift;
        c & 0xff00_0000 | ch(16) | ch(8) | ch(0)
    }

    /// Pixels `x0..x1` of output row `y` (a row of `src`), into `out_row`.
    pub fn sharpen_span(src: &[u32], width: usize, y: usize, x0: usize, x1: usize, a: f32, out_row: &mut [u32]) {
        let rows = src.len() / width;
        let up = if y == 0 { y } else { y - 1 };
        let down = if y == rows - 1 { y } else { y + 1 };
        let (row, above, below) = (&src[y * width..], &src[up * width..], &src[down * width..]);
        for x in x0..x1 {
            let l = row[x.saturating_sub(1)];
            let r = row[(x + 1).min(width - 1)];
            out_row[x] = pixel(row[x], l, r, above[x], below[x], a);
        }
    }

    /// The reference: the shape is the dispatcher's, already checked.
    pub fn sharpen_argb(src: &[u32], width: usize, first: usize, count: usize, a: f32, out: &mut [u32]) {
        for (i, out_row) in out.chunks_exact_mut(width).take(count).enumerate() {
            sharpen_span(src, width, first + i, 0, width, a, out_row);
        }
    }
}

#[cfg(target_arch = "aarch64")]
pub mod neon {
    use std::arch::aarch64::*;

    /// Sixteen pixels a step, de-interleaved by `ld4` into one register per
    /// channel; the left and right neighbours are the same load one pixel
    /// either side. Columns 0 and `width - 1` (whose neighbour is clamped)
    /// and the tail go through the reference.
    ///
    /// # Safety
    /// The CPU must have NEON, and the shape must fit the slices (as
    /// [`super::sharpen_argb`] checks).
    #[target_feature(enable = "neon")]
    pub unsafe fn sharpen_argb(src: &[u32], width: usize, first: usize, count: usize, a: f32, out: &mut [u32]) {
        let rows = src.len() / width;
        let (va, quarter) = (vdupq_n_f32(a), vdupq_n_f32(0.25));
        for i in 0..count {
            let y = first + i;
            let up = if y == 0 { y } else { y - 1 };
            let down = if y == rows - 1 { y } else { y + 1 };
            let row = src.as_ptr().add(y * width) as *const u8;
            let above = src.as_ptr().add(up * width) as *const u8;
            let below = src.as_ptr().add(down * width) as *const u8;
            let out_row = &mut out[i * width..(i + 1) * width];
            let dst = out_row.as_mut_ptr() as *mut u8;
            let mut x = 1;
            // Pixels x..x + 16 need x + 16 <= width - 1 for their right neighbour.
            while x + 17 <= width {
                let c = vld4q_u8(row.add(4 * x));
                let l = vld4q_u8(row.add(4 * (x - 1)));
                let r = vld4q_u8(row.add(4 * (x + 1)));
                let u = vld4q_u8(above.add(4 * x));
                let d = vld4q_u8(below.add(4 * x));
                let sharp = uint8x16x4_t(
                    channel(c.0, l.0, r.0, u.0, d.0, va, quarter),
                    channel(c.1, l.1, r.1, u.1, d.1, va, quarter),
                    channel(c.2, l.2, r.2, u.2, d.2, va, quarter),
                    c.3,
                );
                vst4q_u8(dst.add(4 * x), sharp);
                x += 16;
            }
            super::scalar::sharpen_span(src, width, y, 0, 1.min(width), a, out_row);
            super::scalar::sharpen_span(src, width, y, x.max(1), width, a, out_row);
        }
    }

    /// One channel of sixteen pixels. `k` is exact in `i16` (|k| <= 1020),
    /// and exact again in `f32`, so only the multiply, the add and the
    /// rounding can round, and each does as Kotlin's does.
    ///
    /// # Safety
    /// The CPU must have NEON.
    #[inline]
    #[target_feature(enable = "neon")]
    pub unsafe fn channel(
        c: uint8x16_t,
        l: uint8x16_t,
        r: uint8x16_t,
        u: uint8x16_t,
        d: uint8x16_t,
        a: float32x4_t,
        quarter: float32x4_t,
    ) -> uint8x16_t {
        let c_lo = vmovl_u8(vget_low_u8(c));
        let c_hi = vmovl_high_u8(c);
        let n_lo = vaddq_u16(vaddl_u8(vget_low_u8(l), vget_low_u8(r)), vaddl_u8(vget_low_u8(u), vget_low_u8(d)));
        let n_hi = vaddq_u16(vaddl_high_u8(l, r), vaddl_high_u8(u, d));
        // 4c - n wraps in u16 to the right i16.
        let k_lo = vreinterpretq_s16_u16(vsubq_u16(vshlq_n_u16::<2>(c_lo), n_lo));
        let k_hi = vreinterpretq_s16_u16(vsubq_u16(vshlq_n_u16::<2>(c_hi), n_hi));
        let lane = |c: uint16x4_t, k: int16x4_t| -> uint16x4_t {
            let cf = vcvtq_f32_u32(vmovl_u16(c));
            let kf = vcvtq_f32_s32(vmovl_s16(k));
            let v = vaddq_f32(cf, vmulq_f32(vmulq_f32(a, kf), quarter));
            // Saturating narrows clamp to 0..=65535, then to 0..=255.
            vqmovun_s32(vcvtaq_s32_f32(v))
        };
        let lo = vcombine_u16(lane(vget_low_u16(c_lo), vget_low_s16(k_lo)), lane(vget_high_u16(c_lo), vget_high_s16(k_lo)));
        let hi = vcombine_u16(lane(vget_low_u16(c_hi), vget_low_s16(k_hi)), lane(vget_high_u16(c_hi), vget_high_s16(k_hi)));
        vcombine_u8(vqmovn_u16(lo), vqmovn_u16(hi))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kernels::testing::Rng;

    /// Kotlin's loop, transcribed with its own names, as a check on the
    /// reference itself (the dispatcher's and NEON's tests compare against
    /// the reference).
    fn kotlin(src: &[u32], w: usize, h: usize, a: f32) -> Vec<u32> {
        let mut out = vec![0; w * h];
        for y in 0..h {
            let up = if y == 0 { y } else { y - 1 };
            let down = if y == h - 1 { y } else { y + 1 };
            for x in 0..w {
                let c = src[y * w + x];
                let l = src[y * w + x.max(1) - 1];
                let r = src[y * w + (w - 1).min(x + 1)];
                let u = src[up * w + x];
                let d = src[down * w + x];
                let ch = |s: u32| scalar::channel(c >> s & 0xff, l >> s & 0xff, r >> s & 0xff, u >> s & 0xff, d >> s & 0xff, a);
                out[y * w + x] = (c >> 24 << 24) | ch(16) << 16 | ch(8) << 8 | ch(0);
            }
        }
        out
    }

    fn picture(rng: &mut Rng, w: usize, h: usize) -> Vec<u32> {
        (0..w * h).map(|_| rng.next_u64() as u32).collect()
    }

    /// Widths that leave every tail length, and pictures too narrow for a
    /// single sixteen-pixel step.
    const WIDTHS: &[usize] = &[1, 2, 3, 4, 15, 16, 17, 18, 19, 20, 31, 32, 33, 34, 35, 47, 48, 49, 50, 64, 100];
    const HEIGHTS: &[usize] = &[1, 2, 3, 5];
    /// The editor's range (0.05 to 1 times 1.6), and some that clamp hard.
    const AMOUNTS: &[f32] = &[0.08, 0.5 * 1.6, 1.6, 0.3333, 7.5];

    #[test]
    fn the_reference_is_kotlins_loop() {
        let mut rng = Rng::new(17);
        for &w in WIDTHS {
            for &h in HEIGHTS {
                let src = picture(&mut rng, w, h);
                for &a in AMOUNTS {
                    let mut out = vec![0; w * h];
                    scalar::sharpen_argb(&src, w, 0, h, a, &mut out);
                    assert_eq!(kotlin(&src, w, h, a), out, "w={w} h={h} a={a}");
                }
            }
        }
    }

    #[test]
    fn rounding_and_clamping_follow_kotlin() {
        // A flat picture is left alone; an isolated bright pixel is pushed
        // up to 255, its neighbours down to 0.
        assert_eq!(100, scalar::channel(100, 100, 100, 100, 100, 1.6));
        assert_eq!(255, scalar::channel(250, 0, 0, 0, 0, 1.6));
        assert_eq!(0, scalar::channel(0, 250, 0, 0, 0, 1.6));
        // 2 + 1 * 2 / 4 = 2.5 rounds up, as Math.round does.
        assert_eq!(3, scalar::channel(2, 1, 1, 1, 1, 1.0));
        // 2 - 0.5 * 4 / 4 = 1.5 rounds up too.
        assert_eq!(2, scalar::channel(2, 3, 3, 3, 3, 0.5));
    }

    #[test]
    fn dispatch_matches_the_reference() {
        let mut rng = Rng::new(23);
        for &w in WIDTHS {
            for &h in HEIGHTS {
                let src = picture(&mut rng, w, h);
                for &a in AMOUNTS {
                    let (mut expected, mut actual) = (vec![0; w * h], vec![0; w * h]);
                    scalar::sharpen_argb(&src, w, 0, h, a, &mut expected);
                    assert_eq!(h, sharpen_argb(&src, w, 0, h, a, &mut actual));
                    assert_eq!(expected, actual, "w={w} h={h} a={a}");
                }
            }
        }
    }

    #[test]
    fn bands_join_into_the_whole_picture() {
        // The Kotlin caller's banding: each band reads one row either side.
        let mut rng = Rng::new(29);
        let (w, h, band, a) = (37, 23, 5, 0.8);
        let src = picture(&mut rng, w, h);
        let mut whole = vec![0; w * h];
        sharpen_argb(&src, w, 0, h, a, &mut whole);
        let mut y0 = 0;
        while y0 < h {
            let y1 = h.min(y0 + band);
            let (top, bottom) = (y0.saturating_sub(1), h.min(y1 + 1));
            let mut out = vec![0; w * (y1 - y0)];
            let written = sharpen_argb(&src[top * w..bottom * w], w, y0 - top, y1 - y0, a, &mut out);
            assert_eq!(y1 - y0, written);
            assert_eq!(&whole[y0 * w..y1 * w], &out[..], "band at {y0}");
            y0 = y1;
        }
    }

    #[test]
    fn a_shape_that_does_not_fit_writes_nothing() {
        let src = vec![0u32; 12];
        let mut out = vec![7u32; 12];
        assert_eq!(0, sharpen_argb(&src, 0, 0, 1, 1.0, &mut out));
        assert_eq!(0, sharpen_argb(&src, 4, 1, 3, 1.0, &mut out));
        assert_eq!(0, sharpen_argb(&src, 4, 0, 3, 1.0, &mut out[..11]));
        assert_eq!(0, sharpen_argb(&src, 4, usize::MAX, 2, 1.0, &mut out));
        assert!(out.iter().all(|&p| p == 7));
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn neon_is_exact() {
        let mut rng = Rng::new(31);
        for &w in WIDTHS {
            for &h in HEIGHTS {
                let src = picture(&mut rng, w, h);
                for &a in AMOUNTS {
                    let (mut expected, mut actual) = (vec![0; w * h], vec![0; w * h]);
                    scalar::sharpen_argb(&src, w, 0, h, a, &mut expected);
                    unsafe { neon::sharpen_argb(&src, w, 0, h, a, &mut actual) };
                    assert_eq!(expected, actual, "w={w} h={h} a={a}");
                }
            }
        }
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn neon_is_exact_on_every_channel_value() {
        // Every centre value against every neighbour sum (0..=1020), at
        // every step of the editor's slider and a few amounts past it: all
        // the rounding and clamping corners, not just the ones random
        // pictures land on.
        let mut amounts: Vec<f32> = (1..=20).map(|step| step as f32 * 0.05 * 1.6).collect();
        amounts.extend_from_slice(&[0.3333, 7.5, 1e-3]);
        let sums: Vec<u32> = (0..=1020).collect();
        for &a in &amounts {
            for c in 0..=255u32 {
                for chunk in sums.chunks(16) {
                    let mut lanes = [[0u8; 16]; 5];
                    for (i, &s) in chunk.iter().enumerate() {
                        lanes[0][i] = c as u8;
                        // Four neighbours summing to s: filled up to 255 in turn.
                        for (n, lane) in lanes[1..].iter_mut().enumerate() {
                            lane[i] = s.saturating_sub(255 * n as u32).min(255) as u8;
                        }
                    }
                    let got: [u8; 16] = unsafe {
                        let v = |i: usize| std::arch::aarch64::vld1q_u8(lanes[i].as_ptr());
                        let quarter = std::arch::aarch64::vdupq_n_f32(0.25);
                        let r = neon::channel(v(0), v(1), v(2), v(3), v(4), std::arch::aarch64::vdupq_n_f32(a), quarter);
                        std::mem::transmute(r)
                    };
                    for (i, &s) in chunk.iter().enumerate() {
                        let n: Vec<u32> = (1..5).map(|n| lanes[n][i] as u32).collect();
                        assert_eq!(s, n.iter().sum::<u32>());
                        let expected = scalar::channel(c, n[0], n[1], n[2], n[3], a);
                        assert_eq!(expected, got[i] as u32, "a={a} c={c} sum={s}");
                    }
                }
            }
        }
    }
}

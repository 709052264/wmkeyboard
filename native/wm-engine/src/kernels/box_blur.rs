//! The frosted-glass bake's blur (`FrostBake.boxBlur` on the Kotlin side):
//! a horizontal then a vertical box of `2 * radius + 1` pixels over ARGB
//! colour ints, each channel summed and divided by the window, edges
//! clamped to their own pixel. The bake runs three of these on a quarter-size
//! copy of the board, 9 to 60 ms in Kotlin on a Helio G99.
//!
//! Integer arithmetic, as the Kotlin's: a window's sums slide one pixel at
//! a time (the entering pixel added, the leaving one taken off) and each
//! output channel is `sum / window`, truncated. The vector versions divide
//! by multiplying with `ceil(2^24 / window)` and shifting right by 24,
//! which is exact for every sum a window of up to 256 pixels can hold
//! (`every_quotient_is_exact` checks them all); a wider window takes the
//! reference. Tolerance: 0.

#[cfg(target_arch = "aarch64")]
use super::active;

/// Windows up to this many pixels take the vector path.
pub const MAX_VECTOR_WINDOW: usize = 256;

/// `passes` rounds of the horizontal then vertical box of `radius` over
/// `pixels` (`width` × `height`, row-major), in place, with `scratch` at
/// least as long. False, with nothing changed, when the arrays are shorter
/// than the picture.
pub fn box_blur_argb(pixels: &mut [u32], width: usize, height: usize, radius: usize, passes: usize, scratch: &mut [u32]) -> bool {
    let Some(n) = width.checked_mul(height) else { return false };
    if pixels.len() < n || scratch.len() < n {
        return false;
    }
    if radius == 0 || n == 0 {
        return true;
    }
    let (pixels, scratch) = (&mut pixels[..n], &mut scratch[..n]);
    for _ in 0..passes {
        #[cfg(target_arch = "aarch64")]
        if active().neon && 2 * radius < MAX_VECTOR_WINDOW {
            // SAFETY: NEON is present (checked above); the slices hold the picture.
            unsafe {
                neon::horizontal(pixels, scratch, width, height, radius);
                neon::vertical(scratch, pixels, width, height, radius);
            }
            continue;
        }
        scalar::horizontal(pixels, scratch, width, height, radius);
        scalar::vertical(scratch, pixels, width, height, radius);
    }
    true
}

/// `ceil(2^24 / window)`: `(sum * m) >> 24` is `sum / window` for every
/// `sum <= 255 * window` while `window <= 256`.
pub fn reciprocal(window: usize) -> u32 {
    (1u32 << 24).div_ceil(window as u32)
}

pub mod scalar {
    /// One line of the Kotlin `blurLines`: `length` pixels `step` apart
    /// from `start`.
    fn line(src: &[u32], dst: &mut [u32], start: usize, step: usize, length: usize, radius: usize) {
        let window = (2 * radius + 1) as u32;
        let at = |i: isize| start + (i.clamp(0, length as isize - 1) as usize) * step;
        let mut sums = [0u32; 4];
        let r = radius as isize;
        for k in -r..=r {
            let p = src[at(k)];
            for (c, sum) in sums.iter_mut().enumerate() {
                *sum += p >> (8 * c) & 0xFF;
            }
        }
        for i in 0..length as isize {
            dst[at(i)] = sums.iter().enumerate().fold(0, |out, (c, &sum)| out | (sum / window) << (8 * c));
            let (leaving, entering) = (src[at(i - r)], src[at(i + r + 1)]);
            for (c, sum) in sums.iter_mut().enumerate() {
                *sum = *sum + (entering >> (8 * c) & 0xFF) - (leaving >> (8 * c) & 0xFF);
            }
        }
    }

    pub fn horizontal(src: &[u32], dst: &mut [u32], width: usize, height: usize, radius: usize) {
        for row in 0..height {
            line(src, dst, row * width, 1, width, radius);
        }
    }

    pub fn vertical(src: &[u32], dst: &mut [u32], width: usize, height: usize, radius: usize) {
        for col in 0..width {
            line(src, dst, col, width, height, radius);
        }
    }
}

#[cfg(target_arch = "aarch64")]
pub mod neon {
    use super::{reciprocal, scalar};
    use std::arch::aarch64::*;

    #[inline(always)]
    fn clamp(i: isize, len: usize) -> usize {
        i.clamp(0, len as isize - 1) as usize
    }

    /// One pixel's four channels as `u32` lanes.
    #[inline(always)]
    unsafe fn lanes(p: u32) -> uint32x4_t {
        vmovl_u16(vget_low_u16(vmovl_u8(vcreate_u8(p as u64))))
    }

    /// Four channel sums back to a pixel, each divided by the window.
    #[inline(always)]
    unsafe fn pixel(sums: uint32x4_t, m: uint32x4_t) -> u32 {
        let q = vshrq_n_u32::<24>(vmulq_u32(sums, m));
        vget_lane_u32::<0>(vreinterpret_u32_u8(vmovn_u16(vcombine_u16(vmovn_u32(q), vdup_n_u16(0)))))
    }

    /// Rows four at a time, one pixel's four channels a vector: the four
    /// rows' windows slide side by side, so their additions overlap. The
    /// last rows that do not make a four go through the reference.
    ///
    /// # Safety
    /// The CPU must have NEON; `src` and `dst` hold `width * height`, and the
    /// window is at most `MAX_VECTOR_WINDOW`.
    #[target_feature(enable = "neon")]
    pub unsafe fn horizontal(src: &[u32], dst: &mut [u32], width: usize, height: usize, radius: usize) {
        let m = vdupq_n_u32(reciprocal(2 * radius + 1));
        let r = radius as isize;
        let (s, d) = (src.as_ptr(), dst.as_mut_ptr());
        let mut row = 0;
        while row + 4 <= height {
            let base = [row * width, (row + 1) * width, (row + 2) * width, (row + 3) * width];
            let mut sums = [vdupq_n_u32(0); 4];
            for k in -r..=r {
                let x = clamp(k, width);
                for q in 0..4 {
                    sums[q] = vaddq_u32(sums[q], lanes(*s.add(base[q] + x)));
                }
            }
            for i in 0..width as isize {
                let (out, leaving, entering) = (i as usize, clamp(i - r, width), clamp(i + r + 1, width));
                for q in 0..4 {
                    *d.add(base[q] + out) = pixel(sums[q], m);
                    sums[q] = vsubq_u32(vaddq_u32(sums[q], lanes(*s.add(base[q] + entering))), lanes(*s.add(base[q] + leaving)));
                }
            }
            row += 4;
        }
        if row < height {
            scalar::horizontal(&src[row * width..], &mut dst[row * width..], width, height - row, radius);
        }
    }

    /// Columns four at a time: sixteen channels in two `u16x8` sums, rows
    /// read whole. A window of at most 256 pixels keeps every sum under
    /// 2^16; the sums wrap in between, which the final value does not see.
    /// The columns left over go through the reference.
    ///
    /// # Safety
    /// As for [`horizontal`].
    #[target_feature(enable = "neon")]
    pub unsafe fn vertical(src: &[u32], dst: &mut [u32], width: usize, height: usize, radius: usize) {
        let m = vdupq_n_u32(reciprocal(2 * radius + 1));
        let r = radius as isize;
        let (s, d) = (src.as_ptr() as *const u8, dst.as_mut_ptr() as *mut u8);
        let row_bytes = width * 4;
        let mut col = 0;
        while col + 4 <= width {
            let at = |y: usize| y * row_bytes + col * 4;
            let (mut lo, mut hi) = (vdupq_n_u16(0), vdupq_n_u16(0));
            for k in -r..=r {
                let v = vld1q_u8(s.add(at(clamp(k, height))));
                lo = vaddw_u8(lo, vget_low_u8(v));
                hi = vaddw_high_u8(hi, v);
            }
            for i in 0..height as isize {
                let q0 = vshrq_n_u32::<24>(vmulq_u32(vmovl_u16(vget_low_u16(lo)), m));
                let q1 = vshrq_n_u32::<24>(vmulq_u32(vmovl_high_u16(lo), m));
                let q2 = vshrq_n_u32::<24>(vmulq_u32(vmovl_u16(vget_low_u16(hi)), m));
                let q3 = vshrq_n_u32::<24>(vmulq_u32(vmovl_high_u16(hi), m));
                let bytes = vcombine_u8(vmovn_u16(vcombine_u16(vmovn_u32(q0), vmovn_u32(q1))), vmovn_u16(vcombine_u16(vmovn_u32(q2), vmovn_u32(q3))));
                vst1q_u8(d.add(at(i as usize)), bytes);
                let entering = vld1q_u8(s.add(at(clamp(i + r + 1, height))));
                let leaving = vld1q_u8(s.add(at(clamp(i - r, height))));
                lo = vsubw_u8(vaddw_u8(lo, vget_low_u8(entering)), vget_low_u8(leaving));
                hi = vsubw_high_u8(vaddw_high_u8(hi, entering), leaving);
            }
            col += 4;
        }
        // The last columns: the reference on the picture's own rows, a
        // column at a time through a narrow copy.
        if col < width {
            let rest = width - col;
            let mut from = vec![0u32; rest * height];
            let mut to = vec![0u32; rest * height];
            for y in 0..height {
                from[y * rest..(y + 1) * rest].copy_from_slice(&src[y * width + col..(y + 1) * width]);
            }
            scalar::vertical(&from, &mut to, rest, height, radius);
            for y in 0..height {
                dst[y * width + col..(y + 1) * width].copy_from_slice(&to[y * rest..(y + 1) * rest]);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kernels::testing::Rng;

    #[test]
    fn every_quotient_is_exact() {
        for window in 1..=MAX_VECTOR_WINDOW {
            let m = reciprocal(window) as u64;
            for sum in 0..=255 * window as u64 {
                assert_eq!(sum / window as u64, (sum * m) >> 24 & 0xFFFF_FFFF, "{sum} / {window}");
                assert!(sum * m < 1 << 32, "{sum} * {m} overflows");
            }
        }
    }

    fn picture(r: &mut Rng, n: usize) -> Vec<u32> {
        (0..n)
            .map(|i| match i % 7 {
                // Saturated and empty pixels, where a channel's sum peaks.
                0 => 0xFFFF_FFFF,
                1 => 0,
                _ => r.next_u64() as u32,
            })
            .collect()
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn neon_matches_the_reference_bit_for_bit() {
        let mut r = Rng::new(31);
        for &(w, h) in &[(1, 1), (1, 9), (9, 1), (3, 5), (4, 4), (5, 3), (7, 11), (16, 9), (17, 6), (67, 13), (270, 212)] {
            for &radius in &[1, 2, 3, 8, 20, 127] {
                let src = picture(&mut r, w * h);
                let (mut want, mut got) = (vec![0; w * h], vec![0; w * h]);
                scalar::horizontal(&src, &mut want, w, h, radius);
                unsafe { neon::horizontal(&src, &mut got, w, h, radius) };
                assert_eq!(want, got, "horizontal {w}x{h} r={radius}");
                scalar::vertical(&src, &mut want, w, h, radius);
                unsafe { neon::vertical(&src, &mut got, w, h, radius) };
                assert_eq!(want, got, "vertical {w}x{h} r={radius}");
            }
        }
    }

    #[test]
    fn three_passes_match_the_reference_and_wide_windows_fall_back() {
        let mut r = Rng::new(37);
        let (w, h) = (45, 23);
        for &radius in &[0, 1, 8, 128, 300] {
            let src = picture(&mut r, w * h);
            let mut want = src.clone();
            let mut tmp = vec![0; w * h];
            if radius > 0 {
                for _ in 0..3 {
                    scalar::horizontal(&want, &mut tmp, w, h, radius);
                    scalar::vertical(&tmp, &mut want, w, h, radius);
                }
            }
            let mut got = src.clone();
            assert!(box_blur_argb(&mut got, w, h, radius, 3, &mut vec![0; w * h]));
            assert_eq!(want, got, "r={radius}");
        }
    }

    #[test]
    fn a_flat_picture_stays_flat_and_short_arrays_are_refused() {
        let mut px = vec![0x80C0_4020u32; 12];
        assert!(box_blur_argb(&mut px, 4, 3, 2, 3, &mut [0; 12]));
        assert!(px.iter().all(|&p| p == 0x80C0_4020));
        assert!(!box_blur_argb(&mut px, 4, 4, 2, 1, &mut [0; 16]));
        assert!(!box_blur_argb(&mut px, 4, 3, 2, 1, &mut [0; 11]));
    }
}

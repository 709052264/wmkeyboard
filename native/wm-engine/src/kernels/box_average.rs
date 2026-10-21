//! Shrinking a photo by box averaging, normalised for a network: the
//! detector input of the scanner's PaddleOCR reader
//! (`PaddleOcrPipeline.detectorInput` on the Kotlin side).
//!
//! Each output pixel `(x, y)` is the mean of the source rectangle
//! `[floor(x / scale), ceil((x + 1) / scale)) × [floor(y / scale),
//! ceil((y + 1) / scale))`, clamped to the photo and at least one pixel
//! wide, so neighbouring boxes share their edge column or row. Its three
//! channels are written BGR, each `(mean / 255 - mean_c) / std_c`. The output
//! is rows of `stride` pixels; everything outside the `out_w × out_h`
//! corner is 0, to the end of the slice.
//!
//! Pixels are ARGB `u32`s, as `Bitmap.getPixels` gives them.
//!
//! Tolerance: 0. The sums are integers, so the order they are added in does
//! not matter, and every float step after them is one IEEE operation in the
//! same order as the reference (and as the Kotlin it was written from).

#[cfg(target_arch = "aarch64")]
use super::active;

/// The shape of one call: the photo, the scale it is fitted at, and the
/// part of the output it fills.
#[derive(Clone, Copy, Debug)]
pub struct Fit {
    pub width: usize,
    pub height: usize,
    /// Output pixels per photo pixel, under 1.
    pub scale: f32,
    pub out_w: usize,
    pub out_h: usize,
    /// Output pixels per row (at least `out_w`).
    pub stride: usize,
}

impl Fit {
    /// Whether the slices are big enough and the scale shrinks: the only
    /// shapes the kernel accepts.
    fn fits(&self, pixels: usize, out: usize) -> bool {
        self.width > 0
            && self.height > 0
            && self.scale > 0.0
            && self.scale < 1.0
            && self.out_w <= self.stride
            && self.width.checked_mul(self.height).is_some_and(|n| n <= pixels)
            && self.stride.checked_mul(self.out_h).and_then(|n| n.checked_mul(3)).is_some_and(|n| n <= out)
    }
}

/// The photo span output pixel `i` averages along one axis of `len` pixels,
/// exactly as the Kotlin computes it: `[from, to)`, never empty.
pub fn span(i: usize, scale: f32, len: usize) -> (usize, usize) {
    let last = len as i32 - 1;
    let from = ((i as i32) as f32 / scale).floor() as i32;
    let from = from.clamp(0, last);
    let to = (((i + 1) as i32) as f32 / scale).ceil() as i32;
    let to = to.clamp(from + 1, len as i32);
    (from as usize, to as usize)
}

/// Fills `out` (see the module comment). Returns false, and writes
/// nothing, when the slices are too small for `fit` or the scale does not
/// shrink.
pub fn box_average_bgr(pixels: &[u32], fit: Fit, mean: [f32; 3], std: [f32; 3], out: &mut [f32]) -> bool {
    if !fit.fits(pixels.len(), out.len()) {
        return false;
    }
    #[cfg(target_arch = "aarch64")]
    if active().neon && neon::accepts(fit) {
        // SAFETY: NEON is present (checked above), and `fit` matches the
        // slices.
        unsafe { neon::box_average_bgr(pixels, fit, mean, std, out) };
        return true;
    }
    scalar::box_average_bgr(pixels, fit, mean, std, out);
    true
}

pub mod scalar {
    use super::{span, Fit};

    /// The reference: a transcription of the Kotlin `average` and the
    /// normalising lines around it.
    pub fn box_average_bgr(pixels: &[u32], fit: Fit, mean: [f32; 3], std: [f32; 3], out: &mut [f32]) {
        out.fill(0.0);
        for y in 0..fit.out_h {
            let (ya, yb) = span(y, fit.scale, fit.height);
            for x in 0..fit.out_w {
                let (xa, xb) = span(x, fit.scale, fit.width);
                let (mut r, mut g, mut b) = (0u64, 0u64, 0u64);
                for row in pixels[ya * fit.width..yb * fit.width].chunks_exact(fit.width) {
                    for &p in &row[xa..xb] {
                        r += ((p >> 16) & 0xff) as u64;
                        g += ((p >> 8) & 0xff) as u64;
                        b += (p & 0xff) as u64;
                    }
                }
                let n = ((xb - xa) * (yb - ya)) as i32 as f32;
                let rgb = [r as f32 / n, g as f32 / n, b as f32 / n];
                let at = (y * fit.stride + x) * 3;
                out[at] = (rgb[2] / 255.0 - mean[0]) / std[0];
                out[at + 1] = (rgb[1] / 255.0 - mean[1]) / std[1];
                out[at + 2] = (rgb[0] / 255.0 - mean[2]) / std[2];
            }
        }
    }
}

#[cfg(target_arch = "aarch64")]
pub mod neon {
    use super::{span, Fit};
    use std::arch::aarch64::*;

    /// The most rows a column sum may cover: 257 × 255 still fits a `u16`.
    const MAX_ROWS: usize = 257;

    /// Whether every box is short enough for the `u16` column sums.
    pub fn accepts(fit: Fit) -> bool {
        (0..fit.out_h).all(|y| {
            let (ya, yb) = span(y, fit.scale, fit.height);
            yb - ya <= MAX_ROWS
        })
    }

    /// Two passes per output row. First the box's rows are summed down each
    /// photo column: `vld4q_u8` splits sixteen pixels into their B, G, R
    /// and A bytes, and `uaddw` widens each into `u16` lanes, so a block of
    /// sixteen columns stays in six registers for all its rows. The sums
    /// are stored interleaved, `[b, g, r, 0]` per column. Then each output
    /// pixel adds its box's columns as one `u32x4` (`uaddw` again), and the
    /// normalising divides and subtracts run on all three channels at once.
    ///
    /// # Safety
    /// The CPU must have NEON; `fit` must pass `Fit::fits` for these slices
    /// and [`accepts`].
    #[target_feature(enable = "neon")]
    pub unsafe fn box_average_bgr(pixels: &[u32], fit: Fit, mean: [f32; 3], std: [f32; 3], out: &mut [f32]) {
        out.fill(0.0);
        let w = fit.width;
        let spans: Vec<(usize, usize)> = (0..fit.out_w).map(|x| span(x, fit.scale, w)).collect();
        // Columns past the last box's end are never read.
        let used = spans.last().map_or(0, |s| s.1);
        let mut columns = vec![0u16; used * 4];
        // Every access below goes through this one pointer.
        let sums = columns.as_mut_ptr();
        let mean = vld1q_f32([mean[0], mean[1], mean[2], 0.0].as_ptr());
        let std = vld1q_f32([std[0], std[1], std[2], 1.0].as_ptr());
        let full = vdupq_n_f32(255.0);
        let base = pixels.as_ptr() as *const u8;
        for y in 0..fit.out_h {
            let (ya, yb) = span(y, fit.scale, fit.height);
            let mut x = 0;
            while x + 16 <= used {
                let zero = vdupq_n_u16(0);
                let (mut b0, mut b1, mut g0, mut g1, mut r0, mut r1) = (zero, zero, zero, zero, zero, zero);
                for row in ya..yb {
                    let p = vld4q_u8(base.add((row * w + x) * 4));
                    b0 = vaddw_u8(b0, vget_low_u8(p.0));
                    b1 = vaddw_high_u8(b1, p.0);
                    g0 = vaddw_u8(g0, vget_low_u8(p.1));
                    g1 = vaddw_high_u8(g1, p.1);
                    r0 = vaddw_u8(r0, vget_low_u8(p.2));
                    r1 = vaddw_high_u8(r1, p.2);
                }
                vst4q_u16(sums.add(x * 4), uint16x8x4_t(b0, g0, r0, zero));
                vst4q_u16(sums.add((x + 8) * 4), uint16x8x4_t(b1, g1, r1, zero));
                x += 16;
            }
            for x in x..used {
                let (mut r, mut g, mut b) = (0u16, 0u16, 0u16);
                for row in ya..yb {
                    let p = pixels[row * w + x];
                    r += ((p >> 16) & 0xff) as u16;
                    g += ((p >> 8) & 0xff) as u16;
                    b += (p & 0xff) as u16;
                }
                sums.add(x * 4).copy_from_nonoverlapping([b, g, r, 0].as_ptr(), 4);
            }
            let rows = (yb - ya) as i32;
            for (x, &(xa, xb)) in spans.iter().enumerate() {
                let mut acc = vdupq_n_u32(0);
                for c in xa..xb {
                    acc = vaddw_u16(acc, vld1_u16(sums.add(c * 4)));
                }
                let n = vdupq_n_f32(((xb - xa) as i32 * rows) as f32);
                let v = vdivq_f32(vsubq_f32(vdivq_f32(vdivq_f32(vcvtq_f32_u32(acc), n), full), mean), std);
                let at = (y * fit.stride + x) * 3;
                out[at] = vgetq_lane_f32::<0>(v);
                out[at + 1] = vgetq_lane_f32::<1>(v);
                out[at + 2] = vgetq_lane_f32::<2>(v);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kernels::testing::Rng;

    const MEAN: [f32; 3] = [0.485, 0.456, 0.406];
    const STD: [f32; 3] = [0.229, 0.224, 0.225];

    fn photo(rng: &mut Rng, w: usize, h: usize) -> Vec<u32> {
        (0..w * h).map(|_| 0xff00_0000 | (rng.next_u64() as u32 & 0x00ff_ffff)).collect()
    }

    /// The Kotlin's fit: the longer side to `side` pixels.
    fn fit(w: usize, h: usize, side: usize) -> Fit {
        let scale = side as f32 / w.max(h) as f32;
        let round = |v: f32| ((v + 0.5).floor() as usize).clamp(1, side);
        Fit { width: w, height: h, scale, out_w: round(w as f32 * scale), out_h: round(h as f32 * scale), stride: side }
    }

    fn run(pixels: &[u32], fit: Fit, f: impl Fn(&[u32], Fit, &mut [f32])) -> Vec<f32> {
        let mut out = vec![f32::NAN; fit.stride * fit.out_h * 3 + 7];
        f(pixels, fit, &mut out);
        out
    }

    fn bits(v: &[f32]) -> Vec<u32> {
        v.iter().map(|x| x.to_bits()).collect()
    }

    /// Sizes that hit every column tail, scales just under 1, odd ratios
    /// and portrait photos.
    const SIZES: &[(usize, usize, usize)] = &[
        (2, 1, 1),
        (17, 5, 16),
        (31, 33, 16),
        (100, 7, 64),
        (641, 480, 640),
        (1000, 999, 640),
        (1203, 1601, 640),
        (2000, 1500, 640),
        (300, 4100, 640),
        (4000, 3000, 640),
    ];

    #[test]
    fn dispatch_is_exact() {
        let mut rng = Rng::new(17);
        for &(w, h, side) in SIZES {
            let pixels = photo(&mut rng, w, h);
            let f = fit(w, h, side);
            let reference = run(&pixels, f, |p, f, o| scalar::box_average_bgr(p, f, MEAN, STD, o));
            let dispatched = run(&pixels, f, |p, f, o| assert!(box_average_bgr(p, f, MEAN, STD, o)));
            assert_eq!(bits(&reference), bits(&dispatched), "{w}x{h} to {side}");
        }
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn neon_is_exact() {
        let mut rng = Rng::new(23);
        for &(w, h, side) in SIZES {
            let f = fit(w, h, side);
            assert!(neon::accepts(f));
            // Random noise, then saturated white, where the u16 sums run highest.
            for pixels in [photo(&mut rng, w, h), vec![0xffff_ffff; w * h]] {
                let reference = run(&pixels, f, |p, f, o| scalar::box_average_bgr(p, f, MEAN, STD, o));
                let simd = run(&pixels, f, |p, f, o| unsafe { neon::box_average_bgr(p, f, MEAN, STD, o) });
                assert_eq!(bits(&reference), bits(&simd), "{w}x{h} to {side}");
            }
        }
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn tall_boxes_stay_within_u16() {
        // 300 photo rows per output row: over MAX_ROWS, so the reference runs.
        let f = fit(4, 3000, 10);
        assert!(!neon::accepts(f));
        let pixels = vec![0xffff_ffff; 4 * 3000];
        let reference = run(&pixels, f, |p, f, o| scalar::box_average_bgr(p, f, MEAN, STD, o));
        let dispatched = run(&pixels, f, |p, f, o| assert!(box_average_bgr(p, f, MEAN, STD, o)));
        assert_eq!(bits(&reference), bits(&dispatched));
    }

    #[test]
    fn the_rest_of_the_output_is_zero() {
        let mut rng = Rng::new(29);
        let f = fit(50, 20, 32);
        let pixels = photo(&mut rng, 50, 20);
        let out = run(&pixels, f, |p, f, o| assert!(box_average_bgr(p, f, MEAN, STD, o)));
        for y in 0..f.out_h {
            for x in f.out_w..f.stride {
                assert_eq!([0.0; 3], out[(y * f.stride + x) * 3..][..3], "({x}, {y})");
            }
        }
        // Past the last row too, as the Kotlin's `fill(0f)` leaves it.
        assert!(out[f.stride * f.out_h * 3..].iter().all(|&v| v == 0.0));
    }

    #[test]
    fn a_flat_photo_averages_to_its_colour() {
        let f = fit(90, 60, 32);
        let pixels = vec![0xff10_2030; 90 * 60];
        let out = run(&pixels, f, |p, f, o| assert!(box_average_bgr(p, f, MEAN, STD, o)));
        let expect = [
            (0x30 as f32 / 255.0 - MEAN[0]) / STD[0],
            (0x20 as f32 / 255.0 - MEAN[1]) / STD[1],
            (0x10 as f32 / 255.0 - MEAN[2]) / STD[2],
        ];
        assert_eq!(expect, out[..3]);
    }

    #[test]
    fn shapes_it_cannot_fill_are_refused() {
        let pixels = vec![0u32; 100];
        let mut out = vec![0f32; 3 * 10 * 10];
        let ok = Fit { width: 10, height: 10, scale: 0.5, out_w: 5, out_h: 5, stride: 10 };
        assert!(box_average_bgr(&pixels, ok, MEAN, STD, &mut out));
        assert!(!box_average_bgr(&pixels, Fit { scale: 1.0, ..ok }, MEAN, STD, &mut out));
        assert!(!box_average_bgr(&pixels, Fit { width: 11, ..ok }, MEAN, STD, &mut out));
        assert!(!box_average_bgr(&pixels, Fit { out_w: 11, ..ok }, MEAN, STD, &mut out));
        assert!(!box_average_bgr(&pixels, Fit { out_h: 11, ..ok }, MEAN, STD, &mut out));
    }
}

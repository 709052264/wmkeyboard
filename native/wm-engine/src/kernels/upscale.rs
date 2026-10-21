//! The upscaler's pixel arithmetic around its network (`UpscaleTiles` and
//! `UpscaleEngine` on the Kotlin side): a tile's input as RGB planes from 0
//! to 1, the network's answer back as opaque colour ints, and the enlarged
//! alpha laid onto the colour. About 45 ms of Kotlin for each ×2 pass over
//! a 512 px sticker on a Helio G99.
//!
//! Each function is the Kotlin loop, value for value: the input divides by
//! 255 (an IEEE division, not a multiply by its reciprocal), the output
//! multiplies by 255 and rounds as `Math.round` does for everything the
//! clamp to 0..255 keeps (`fcvtas` rounds a tie away from zero, which
//! differs from `Math.round` only for negative ties, clamped to 0 either
//! way; a NaN is 0 in both), and the alpha is the integer mean of the grey's
//! three channels. Tolerance: 0.

#[cfg(target_arch = "aarch64")]
use super::active;

/// `UpscaleTiles.input`: the `side` × `side` square at (`x0`, `y0`) of a
/// `width` × `height` ARGB picture into `out` as three planes (R, G, B) of
/// `channel / 255`, places outside the picture taking the nearest edge
/// pixel. False, writing nothing, when the arrays are too short.
pub fn planar_input(pixels: &[u32], width: usize, height: usize, x0: i64, y0: i64, side: usize, out: &mut [f32]) -> bool {
    let plane = side * side;
    if width == 0 || height == 0 || pixels.len() < width * height || out.len() < 3 * plane {
        return false;
    }
    for row in 0..side {
        let sy = (y0 + row as i64).clamp(0, height as i64 - 1) as usize * width;
        let line = &pixels[sy..sy + width];
        let (r, rest) = out.split_at_mut(plane);
        let (g, b) = rest.split_at_mut(plane);
        let at = row * side..(row + 1) * side;
        let (r, g, b) = (&mut r[at.clone()], &mut g[at.clone()], &mut b[at]);
        // The columns that read inside the picture, in one run.
        let first = (-x0).clamp(0, side as i64) as usize;
        scalar::input_edge(line, x0, 0..first, r, g, b);
        #[cfg(target_arch = "aarch64")]
        let last = (width as i64 - x0).clamp(first as i64, side as i64) as usize;
        #[cfg(target_arch = "aarch64")]
        if active().neon && first < last {
            // SAFETY: NEON is present; columns first..last read inside `line`.
            unsafe { neon::input_run(&line[(x0 + first as i64) as usize..], &mut r[first..last], &mut g[first..last], &mut b[first..last]) };
            scalar::input_edge(line, x0, last..side, r, g, b);
            continue;
        }
        scalar::input_edge(line, x0, first..side, r, g, b);
    }
    true
}

/// `UpscaleTiles.output`: the top-left `w` × `h` of the network's answer
/// (three planes of `out_side` × `out_side`) as opaque ARGB into `out`,
/// rows `w` apart. False, writing nothing, when the arrays are too short.
pub fn packed_output(output: &[f32], out_side: usize, w: usize, h: usize, out: &mut [u32]) -> bool {
    let plane = out_side * out_side;
    if w > out_side || h > out_side || output.len() < 3 * plane || out.len() < w * h {
        return false;
    }
    for y in 0..h {
        let i = y * out_side;
        let (r, g, b) = (&output[i..i + w], &output[plane + i..plane + i + w], &output[2 * plane + i..2 * plane + i + w]);
        let row = &mut out[y * w..(y + 1) * w];
        #[cfg(target_arch = "aarch64")]
        if active().neon {
            // SAFETY: NEON is present; all four slices are `w` long.
            unsafe { neon::output_run(r, g, b, row) };
            continue;
        }
        scalar::output_run(r, g, b, row);
    }
    true
}

/// `UpscaleEngine`'s alpha merge: the `w` × `h` tile `rgb` (rows `w` apart)
/// takes as alpha the mean of the grey `alpha` pixel at the same place,
/// `alpha` being rows `alpha_row` apart from index `from`. False, writing
/// nothing, when the arrays are too short.
pub fn merge_alpha(rgb: &mut [u32], w: usize, h: usize, alpha: &[u32], alpha_row: usize, from: usize) -> bool {
    if h == 0 || w == 0 {
        return true;
    }
    if rgb.len() < w * h || w > alpha_row || alpha.len() < from + (h - 1) * alpha_row + w {
        return false;
    }
    for y in 0..h {
        let a = &alpha[from + y * alpha_row..from + y * alpha_row + w];
        let row = &mut rgb[y * w..(y + 1) * w];
        #[cfg(target_arch = "aarch64")]
        if active().neon {
            // SAFETY: NEON is present; both slices are `w` long.
            unsafe { neon::merge_run(row, a) };
            continue;
        }
        scalar::merge_run(row, a);
    }
    true
}

pub mod scalar {
    use std::ops::Range;

    #[inline(always)]
    pub fn channel(v: f32) -> u32 {
        // Kotlin's `(v * 255f).roundToInt().coerceIn(0, 255)`: Math.round
        // is the closest int with ties up, and NaN 0. `x - floor(x)` is
        // exact for every float.
        let x = v * 255.0;
        if x.is_nan() {
            return 0;
        }
        let f = x.floor();
        let r = if x - f >= 0.5 { f + 1.0 } else { f };
        r.clamp(0.0, 255.0) as u32
    }

    pub fn input_edge(line: &[u32], x0: i64, cols: Range<usize>, r: &mut [f32], g: &mut [f32], b: &mut [f32]) {
        let width = line.len() as i64;
        for col in cols {
            let p = line[(x0 + col as i64).clamp(0, width - 1) as usize];
            r[col] = ((p >> 16) & 0xFF) as f32 / 255.0;
            g[col] = ((p >> 8) & 0xFF) as f32 / 255.0;
            b[col] = (p & 0xFF) as f32 / 255.0;
        }
    }

    pub fn output_run(r: &[f32], g: &[f32], b: &[f32], out: &mut [u32]) {
        for (x, slot) in out.iter_mut().enumerate() {
            *slot = 0xFF00_0000 | channel(r[x]) << 16 | channel(g[x]) << 8 | channel(b[x]);
        }
    }

    pub fn merge_run(rgb: &mut [u32], alpha: &[u32]) {
        for (p, &a) in rgb.iter_mut().zip(alpha) {
            let mean = (((a >> 16) & 0xFF) + ((a >> 8) & 0xFF) + (a & 0xFF) + 1) / 3;
            *p = mean << 24 | (*p & 0xFF_FFFF);
        }
    }
}

#[cfg(target_arch = "aarch64")]
pub mod neon {
    use super::scalar;
    use std::arch::aarch64::*;

    /// `line.len() >= r.len()` pixels, four at a time: each channel masked
    /// out, converted exactly to `f32` and divided by 255.
    ///
    /// # Safety
    /// The CPU must have NEON; `line` holds at least `r.len()` pixels and
    /// `g`, `b` are as long as `r`.
    #[target_feature(enable = "neon")]
    pub unsafe fn input_run(line: &[u32], r: &mut [f32], g: &mut [f32], b: &mut [f32]) {
        let n = r.len();
        let (k255, mask) = (vdupq_n_f32(255.0), vdupq_n_u32(0xFF));
        let mut x = 0;
        while x + 4 <= n {
            let p = vld1q_u32(line.as_ptr().add(x));
            vst1q_f32(r.as_mut_ptr().add(x), vdivq_f32(vcvtq_f32_u32(vandq_u32(vshrq_n_u32::<16>(p), mask)), k255));
            vst1q_f32(g.as_mut_ptr().add(x), vdivq_f32(vcvtq_f32_u32(vandq_u32(vshrq_n_u32::<8>(p), mask)), k255));
            vst1q_f32(b.as_mut_ptr().add(x), vdivq_f32(vcvtq_f32_u32(vandq_u32(p, mask)), k255));
            x += 4;
        }
        // The last few, as the reference does them (x0 = 0: `line` starts at column 0 of the run).
        let line = &line[..n];
        scalar::input_edge(line, 0, x..n, r, g, b);
    }

    /// Four channel values to bytes: times 255, `fcvtas` (saturating),
    /// clamped to 0..255.
    #[inline(always)]
    unsafe fn bytes(v: float32x4_t) -> uint32x4_t {
        let rounded = vcvtaq_s32_f32(vmulq_f32(v, vdupq_n_f32(255.0)));
        vreinterpretq_u32_s32(vminq_s32(vmaxq_s32(rounded, vdupq_n_s32(0)), vdupq_n_s32(255)))
    }

    /// # Safety
    /// The CPU must have NEON; the four slices are equally long.
    #[target_feature(enable = "neon")]
    pub unsafe fn output_run(r: &[f32], g: &[f32], b: &[f32], out: &mut [u32]) {
        let n = out.len();
        let opaque = vdupq_n_u32(0xFF00_0000);
        let mut x = 0;
        while x + 4 <= n {
            let rr = bytes(vld1q_f32(r.as_ptr().add(x)));
            let gg = bytes(vld1q_f32(g.as_ptr().add(x)));
            let bb = bytes(vld1q_f32(b.as_ptr().add(x)));
            let p = vorrq_u32(vorrq_u32(opaque, vshlq_n_u32::<16>(rr)), vorrq_u32(vshlq_n_u32::<8>(gg), bb));
            vst1q_u32(out.as_mut_ptr().add(x), p);
            x += 4;
        }
        scalar::output_run(&r[x..], &g[x..], &b[x..], &mut out[x..]);
    }

    /// The mean of three channels plus one, divided by 3 as
    /// `(s * 0xAAAB) >> 17`, which is exact for every `s` up to 98,303
    /// (here at most 766).
    ///
    /// # Safety
    /// The CPU must have NEON; both slices are equally long.
    #[target_feature(enable = "neon")]
    pub unsafe fn merge_run(rgb: &mut [u32], alpha: &[u32]) {
        let n = rgb.len();
        let (mask, third, colour) = (vdupq_n_u32(0xFF), vdupq_n_u32(0xAAAB), vdupq_n_u32(0xFF_FFFF));
        let mut x = 0;
        while x + 4 <= n {
            let a = vld1q_u32(alpha.as_ptr().add(x));
            let s = vaddq_u32(
                vaddq_u32(vandq_u32(vshrq_n_u32::<16>(a), mask), vandq_u32(vshrq_n_u32::<8>(a), mask)),
                vaddq_u32(vandq_u32(a, mask), vdupq_n_u32(1)),
            );
            let mean = vshrq_n_u32::<17>(vmulq_u32(s, third));
            let p = vld1q_u32(rgb.as_ptr().add(x));
            vst1q_u32(rgb.as_mut_ptr().add(x), vorrq_u32(vshlq_n_u32::<24>(mean), vandq_u32(p, colour)));
            x += 4;
        }
        scalar::merge_run(&mut rgb[x..], &alpha[x..]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kernels::testing::Rng;

    /// Java's `Math.round(float)`, by its definition: the closest int, ties
    /// toward positive infinity, NaN 0, saturating.
    fn java_round(x: f32) -> i64 {
        if x.is_nan() {
            return 0;
        }
        let f = x.floor();
        let r = if x - f >= 0.5 { f as f64 + 1.0 } else { f as f64 };
        r.clamp(i32::MIN as f64, i32::MAX as f64) as i64
    }

    #[test]
    fn channel_is_kotlins_rounding() {
        let mut values = vec![f32::NAN, f32::INFINITY, f32::NEG_INFINITY, -0.0, 0.0, 1.0, -1.0, 0.5 / 255.0, 0.49999997, 254.5 / 255.0];
        for i in 0..=255 * 4 {
            // Every quarter step, and either side of it.
            let v = i as f32 / (255.0 * 4.0);
            values.extend([v, f32::from_bits(v.to_bits() + 1), f32::from_bits(v.to_bits().max(1) - 1)]);
        }
        let mut r = Rng::new(43);
        values.extend((0..20000).map(|_| r.unit() * 1.5));
        for &v in &values {
            assert_eq!(java_round(v * 255.0).clamp(0, 255) as u32, scalar::channel(v), "{v}");
            #[cfg(target_arch = "aarch64")]
            {
                let mut out = [0u32; 4];
                unsafe { neon::output_run(&[v; 4], &[v; 4], &[v; 4], &mut out) };
                let c = scalar::channel(v);
                assert_eq!(0xFF00_0000 | c << 16 | c << 8 | c, out[0], "{v}");
            }
        }
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn neon_matches_the_reference_bit_for_bit() {
        let _pinned = crate::kernels::testing::FORCE.lock().unwrap_or_else(|e| e.into_inner());
        let mut r = Rng::new(47);
        for &(w, h) in &[(1, 1), (3, 2), (7, 9), (128, 128), (130, 61)] {
            let pixels: Vec<u32> = (0..w * h).map(|_| r.next_u64() as u32).collect();
            for &(x0, y0, side) in &[(-7i64, -7i64, 21usize), (0, 0, 8), (5, 3, 17), (w as i64 - 3, h as i64 - 2, 20)] {
                let mut want = vec![0f32; 3 * side * side];
                let mut got = want.clone();
                crate::kernels::set_force_scalar(true);
                assert!(planar_input(&pixels, w, h, x0, y0, side, &mut want));
                crate::kernels::set_force_scalar(false);
                assert!(planar_input(&pixels, w, h, x0, y0, side, &mut got));
                assert_eq!(want.iter().map(|v| v.to_bits()).collect::<Vec<_>>(), got.iter().map(|v| v.to_bits()).collect::<Vec<_>>());
            }
        }
        for &n in &[1usize, 3, 4, 5, 17, 256] {
            let output: Vec<f32> = (0..3 * n * n).map(|_| r.unit() * 1.2 + 0.4).collect();
            for &(w, h) in &[(n, n), (n.div_ceil(2), n.saturating_sub(1).max(1))] {
                let (mut want, mut got) = (vec![0u32; w * h], vec![0u32; w * h]);
                for y in 0..h {
                    let i = y * n;
                    let plane = n * n;
                    scalar::output_run(&output[i..i + w], &output[plane + i..plane + i + w], &output[2 * plane + i..2 * plane + i + w], &mut want[y * w..(y + 1) * w]);
                }
                assert!(packed_output(&output, n, w, h, &mut got));
                assert_eq!(want, got, "output {n} {w}x{h}");
                let alpha: Vec<u32> = (0..(w + 3) * h + 2).map(|_| r.next_u64() as u32).collect();
                let mut merged = got.clone();
                for y in 0..h {
                    scalar::merge_run(&mut want[y * w..(y + 1) * w], &alpha[2 + y * (w + 3)..2 + y * (w + 3) + w]);
                }
                assert!(merge_alpha(&mut merged, w, h, &alpha, w + 3, 2));
                assert_eq!(want, merged, "merge {w}x{h}");
            }
        }
    }

    #[test]
    fn every_grey_mean_divides_exactly() {
        for s in 0..=766u32 {
            assert_eq!(s / 3, (s * 0xAAAB) >> 17);
        }
    }

    #[test]
    fn short_arrays_are_refused() {
        assert!(!planar_input(&[0; 3], 2, 2, 0, 0, 2, &mut [0.0; 12]));
        assert!(!planar_input(&[0; 4], 2, 2, 0, 0, 2, &mut [0.0; 11]));
        assert!(!packed_output(&[0.0; 11], 2, 2, 2, &mut [0; 4]));
        assert!(!packed_output(&[0.0; 12], 2, 3, 1, &mut [0; 4]));
        assert!(!merge_alpha(&mut [0; 4], 2, 2, &[0; 4], 3, 0));
    }
}

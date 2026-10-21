//! A decoded video frame's YUV 4:2:0 planes as RGB colour ints: what
//! `WebmFrames.rgbOf` does to each frame of a Telegram video sticker
//! (about ninety a sticker), BT.601 at video range in 8.8 fixed point.
//!
//! The planes are laid out as `android.media.Image` hands them over: each
//! with its own row and pixel stride, chroma at half resolution in both
//! directions, read from a crop rectangle's top-left corner. Pixel
//! `(row, col)` of the output takes luma at
//! `(top + row) * y_row + (left + col) * y_pixel` and chroma at
//! `((top + row) / 2) * c_row + ((left + col) / 2) * c_pixel` in each of
//! the U and V planes, and becomes `r << 16 | g << 8 | b` with the top byte
//! left 0 for the alpha.
//!
//! Tolerance: 0. Integer arithmetic, the same as the Kotlin's.

#[cfg(target_arch = "aarch64")]
use super::active;

/// One plane: its bytes (up to the buffer's limit) and strides.
#[derive(Clone, Copy)]
pub struct Plane<'a> {
    pub data: &'a [u8],
    pub row_stride: usize,
    pub pixel_stride: usize,
}

impl Plane<'_> {
    /// Whether every index the frame reads lies inside the plane.
    fn holds(&self, last_row: usize, last_col: usize) -> bool {
        last_row
            .checked_mul(self.row_stride)
            .and_then(|r| last_col.checked_mul(self.pixel_stride).and_then(|c| r.checked_add(c)))
            .is_some_and(|i| i < self.data.len())
    }
}

/// Where the picture sits in the planes and in the output.
#[derive(Clone, Copy)]
pub struct Frame {
    pub left: usize,
    pub top: usize,
    pub rows: usize,
    pub cols: usize,
    /// Output row length, at least `cols`.
    pub width: usize,
}

/// `rows` × `cols` pixels into `out` (rows `width` apart; the rest of `out`
/// untouched). False, writing nothing, when a read would fall outside a
/// plane or a write outside `out`: the Kotlin's `ByteBuffer.get` would have
/// thrown there.
pub fn yuv420_to_rgb(y: Plane, u: Plane, v: Plane, frame: Frame, out: &mut [i32]) -> bool {
    let Frame { left, top, rows, cols, width } = frame;
    if rows == 0 || cols == 0 {
        return true;
    }
    let (last_row, last_col) = (top + rows - 1, left + cols - 1);
    if cols > width
        || (rows - 1).checked_mul(width).and_then(|r| r.checked_add(cols)).is_none_or(|end| end > out.len())
        || !y.holds(last_row, last_col)
        || !u.holds(last_row / 2, last_col / 2)
        || !v.holds(last_row / 2, last_col / 2)
    {
        return false;
    }
    #[cfg(target_arch = "aarch64")]
    if active().neon && y.pixel_stride == 1 && left % 2 == 0 && matches!((u.pixel_stride, v.pixel_stride), (1, 1) | (2, 2)) {
        for row in 0..rows {
            // SAFETY: NEON is present; the bounds were checked above, and
            // the row's vector reads are checked against the planes in it.
            unsafe { neon::row(y, u, v, top + row, left, cols, &mut out[row * width..row * width + cols]) };
        }
        return true;
    }
    for row in 0..rows {
        scalar::row(y, u, v, top + row, left, 0, &mut out[row * width..row * width + cols]);
    }
    true
}

pub mod scalar {
    use super::Plane;

    #[inline(always)]
    pub fn pixel(luma: u8, cb: u8, cr: u8) -> i32 {
        let (cb, cr) = (cb as i32 - 128, cr as i32 - 128);
        let c = 298 * (luma as i32 - 16);
        let r = ((c + 409 * cr + 128) >> 8).clamp(0, 255);
        let g = ((c - 100 * cb - 208 * cr + 128) >> 8).clamp(0, 255);
        let b = ((c + 516 * cb + 128) >> 8).clamp(0, 255);
        (r << 16) | (g << 8) | b
    }

    /// Columns `from..out.len()` of plane row `at`, from crop column `left`.
    pub fn row(y: Plane, u: Plane, v: Plane, at: usize, left: usize, from: usize, out: &mut [i32]) {
        let y_row = at * y.row_stride;
        let (u_row, v_row) = ((at / 2) * u.row_stride, (at / 2) * v.row_stride);
        for (col, slot) in out.iter_mut().enumerate().skip(from) {
            let x = left + col;
            let luma = y.data[y_row + x * y.pixel_stride];
            let cb = u.data[u_row + (x / 2) * u.pixel_stride];
            let cr = v.data[v_row + (x / 2) * v.pixel_stride];
            *slot = pixel(luma, cb, cr);
        }
    }
}

#[cfg(target_arch = "aarch64")]
pub mod neon {
    use super::{scalar, Plane};
    use std::arch::aarch64::*;

    /// One output row, sixteen pixels a step: sixteen luma bytes and eight
    /// of each chroma (`ld2` keeps every other byte of an interleaved
    /// plane), each chroma byte widened to its two pixels. The sums run in
    /// `i32` lanes as the Kotlin's do (they pass 16 bits), the shift is
    /// arithmetic, and the two saturating narrows clamp to 0..255 exactly
    /// as `coerceIn` does. `st4` writes `b, g, r, 0`: the little-endian
    /// bytes of `r << 16 | g << 8 | b`. Whatever a step's reads would take
    /// past a plane's end goes through the reference instead.
    ///
    /// # Safety
    /// The CPU must have NEON; `y.pixel_stride` is 1, `left` is even, the
    /// chroma pixel strides are both 1 or both 2, and every index the
    /// reference would read for this row is inside its plane.
    #[target_feature(enable = "neon")]
    pub unsafe fn row(y: Plane, u: Plane, v: Plane, at: usize, left: usize, cols: usize, out: &mut [i32]) {
        let y_base = at * y.row_stride + left;
        let c_step = u.pixel_stride;
        let (u_base, v_base) = ((at / 2) * u.row_stride + left / 2 * c_step, (at / 2) * v.row_stride + left / 2 * c_step);
        // A step reads 16 luma bytes and 8 * c_step chroma bytes.
        let mut col = 0;
        while col + 16 <= cols
            && y_base + col + 16 <= y.data.len()
            && u_base + (col / 2 + 8) * c_step <= u.data.len()
            && v_base + (col / 2 + 8) * c_step <= v.data.len()
        {
            let luma = vld1q_u8(y.data.as_ptr().add(y_base + col));
            let (cb, cr) = if c_step == 1 {
                (vld1_u8(u.data.as_ptr().add(u_base + col / 2)), vld1_u8(v.data.as_ptr().add(v_base + col / 2)))
            } else {
                (vld2_u8(u.data.as_ptr().add(u_base + col)).0, vld2_u8(v.data.as_ptr().add(v_base + col)).0)
            };
            // Each chroma byte for its two pixels, as signed offsets from 128.
            let cb = vreinterpretq_s16_u16(vsubq_u16(vmovl_u8(cb), vdupq_n_u16(128)));
            let cr = vreinterpretq_s16_u16(vsubq_u16(vmovl_u8(cr), vdupq_n_u16(128)));
            let (cb_lo, cb_hi) = (vzip1q_s16(cb, cb), vzip2q_s16(cb, cb));
            let (cr_lo, cr_hi) = (vzip1q_s16(cr, cr), vzip2q_s16(cr, cr));
            let l = vreinterpretq_s16_u16(vsubq_u16(vmovl_u8(vget_low_u8(luma)), vdupq_n_u16(16)));
            let h = vreinterpretq_s16_u16(vsubq_u16(vmovl_high_u8(luma), vdupq_n_u16(16)));
            let (r0, g0, b0) = channels(l, cb_lo, cr_lo);
            let (r1, g1, b1) = channels(h, cb_hi, cr_hi);
            let planes = uint8x16x4_t(vcombine_u8(b0, b1), vcombine_u8(g0, g1), vcombine_u8(r0, r1), vdupq_n_u8(0));
            vst4q_u8(out.as_mut_ptr().add(col) as *mut u8, planes);
            col += 16;
        }
        scalar::row(y, u, v, at, left, col, &mut out[..cols]);
    }

    /// Eight pixels' r, g and b, clamped to bytes.
    #[inline(always)]
    unsafe fn channels(y: int16x8_t, cb: int16x8_t, cr: int16x8_t) -> (uint8x8_t, uint8x8_t, uint8x8_t) {
        let round = vdupq_n_s32(128);
        let (yl, yh) = (vmull_n_s16(vget_low_s16(y), 298), vmull_high_n_s16(y, 298));
        let r = (
            vmlal_n_s16(vaddq_s32(yl, round), vget_low_s16(cr), 409),
            vmlal_high_n_s16(vaddq_s32(yh, round), cr, 409),
        );
        let g = (
            vmlsl_n_s16(vmlsl_n_s16(vaddq_s32(yl, round), vget_low_s16(cb), 100), vget_low_s16(cr), 208),
            vmlsl_high_n_s16(vmlsl_high_n_s16(vaddq_s32(yh, round), cb, 100), cr, 208),
        );
        let b = (
            vmlal_n_s16(vaddq_s32(yl, round), vget_low_s16(cb), 516),
            vmlal_high_n_s16(vaddq_s32(yh, round), cb, 516),
        );
        (narrow(r), narrow(g), narrow(b))
    }

    /// `(sum >> 8).coerceIn(0, 255)` for eight sums.
    #[inline(always)]
    unsafe fn narrow((lo, hi): (int32x4_t, int32x4_t)) -> uint8x8_t {
        let shifted = vcombine_u16(vqmovun_s32(vshrq_n_s32::<8>(lo)), vqmovun_s32(vshrq_n_s32::<8>(hi)));
        vqmovn_u16(shifted)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kernels::testing::Rng;

    /// Planes for a `w` × `h` picture: I420 (chroma stride 1) or the
    /// semi-planar layout (U and V interleaved, stride 2, V one byte on),
    /// with row padding, and each plane's buffer ending at its last byte.
    fn planes(r: &mut Rng, w: usize, h: usize, semi: bool, pad: usize) -> (Vec<u8>, usize, Vec<u8>, Vec<u8>, usize, usize) {
        let y_row = w + pad;
        let y: Vec<u8> = (0..y_row * (h - 1) + w).map(|_| r.next_u64() as u8).collect();
        let (cw, ch) = (w.div_ceil(2), h.div_ceil(2));
        if semi {
            let c_row = 2 * cw + pad;
            let uv: Vec<u8> = (0..c_row * (ch - 1) + 2 * cw).map(|_| r.next_u64() as u8).collect();
            let u = uv[..uv.len() - 1].to_vec();
            let v = uv[1..].to_vec();
            (y, y_row, u, v, c_row, 2)
        } else {
            let c_row = cw + pad;
            let mut c = || (0..c_row * (ch - 1) + cw).map(|_| r.next_u64() as u8).collect::<Vec<u8>>();
            let (u, v) = (c(), c());
            (y, y_row, u, v, c_row, 1)
        }
    }

    fn convert(force_reference: bool, y: Plane, u: Plane, v: Plane, frame: Frame, out: &mut [i32]) -> bool {
        if force_reference {
            for row in 0..frame.rows {
                let w = frame.width;
                scalar::row(y, u, v, frame.top + row, frame.left, 0, &mut out[row * w..row * w + frame.cols]);
            }
            true
        } else {
            yuv420_to_rgb(y, u, v, frame, out)
        }
    }

    #[test]
    fn every_size_and_crop_matches_the_reference() {
        let mut r = Rng::new(29);
        for &(w, h) in &[(1, 1), (2, 2), (15, 3), (16, 2), (17, 5), (31, 7), (33, 4), (64, 9), (100, 3), (512, 4)] {
            for semi in [false, true] {
                for pad in [0, 3] {
                    let (yb, y_row, ub, vb, c_row, c_px) = planes(&mut r, w, h, semi, pad);
                    let y = Plane { data: &yb, row_stride: y_row, pixel_stride: 1 };
                    let u = Plane { data: &ub, row_stride: c_row, pixel_stride: c_px };
                    let v = Plane { data: &vb, row_stride: c_row, pixel_stride: c_px };
                    for &(left, top) in &[(0, 0), (1, 1), (2, 0), (0, 1)] {
                        if left >= w || top >= h {
                            continue;
                        }
                        let frame = Frame { left, top, rows: h - top, cols: w - left, width: w + 1 };
                        let mut want = vec![-1; (w + 1) * h];
                        let mut got = want.clone();
                        assert!(convert(true, y, u, v, frame, &mut want));
                        assert!(convert(false, y, u, v, frame, &mut got));
                        assert_eq!(want, got, "{w}x{h} semi={semi} pad={pad} crop=({left},{top})");
                    }
                }
            }
        }
    }

    #[test]
    fn every_luma_and_chroma_triple_matches_the_formula() {
        // 16 pixels a row share one luma ramp and sweep both chroma bytes,
        // so each NEON lane meets every value against the reference.
        let w = 512;
        let y: Vec<u8> = (0..w).map(|i| i as u8).collect();
        for cb in 0..=255u8 {
            let u = vec![cb; w / 2];
            let v: Vec<u8> = (0..w / 2).map(|i| (i * 7 + cb as usize) as u8).collect();
            let frame = Frame { left: 0, top: 0, rows: 1, cols: w, width: w };
            let mut out = vec![0; w];
            assert!(yuv420_to_rgb(
                Plane { data: &y, row_stride: w, pixel_stride: 1 },
                Plane { data: &u, row_stride: w / 2, pixel_stride: 1 },
                Plane { data: &v, row_stride: w / 2, pixel_stride: 1 },
                frame,
                &mut out
            ));
            for (x, &p) in out.iter().enumerate() {
                assert_eq!(scalar::pixel(y[x], u[x / 2], v[x / 2]), p, "y={} cb={cb} cr={}", y[x], v[x / 2]);
            }
        }
    }

    #[test]
    fn reads_past_a_plane_are_refused() {
        let y = vec![0u8; 15];
        let c = vec![128u8; 8];
        let frame = Frame { left: 0, top: 0, rows: 1, cols: 16, width: 16 };
        let mut out = vec![7; 16];
        let plane = |d: &[u8], s| Plane { data: unsafe { std::slice::from_raw_parts(d.as_ptr(), d.len()) }, row_stride: s, pixel_stride: 1 };
        assert!(!yuv420_to_rgb(plane(&y, 16), plane(&c, 8), plane(&c, 8), frame, &mut out));
        assert_eq!(vec![7; 16], out);
        let mut short = vec![0; 15];
        let y = vec![0u8; 16];
        assert!(!yuv420_to_rgb(plane(&y, 16), plane(&c, 8), plane(&c, 8), frame, &mut short));
    }

    #[test]
    fn grey_is_grey() {
        assert_eq!(0, scalar::pixel(16, 128, 128));
        assert_eq!(0xFFFFFF, scalar::pixel(235, 128, 128));
    }
}

//! The GIF encoder's per-pixel loops (`crate::gif`): the bounds of what a
//! frame draws, the colour histogram, and the mapping of pixels to palette
//! indices.
//!
//! Pixels are Android colour ints, `0xAARRGGBB`. A pixel is drawn when its
//! alpha is at least 128, which is its top bit, so the test is a sign test.
//!
//! Exact: integers only, and every version gives the reference's result.
//! The histogram and the index mapping are table scatters and gathers, which
//! NEON cannot do, so the vector versions work out four buckets at a time
//! (shifts, masks and the opacity select) and leave the table accesses to
//! scalar code. The bounds scan tests sixteen pixels at a time and, in a row
//! that draws anything, reads only in from each end.

#[cfg(target_arch = "aarch64")]
use super::active;
use crate::gif::BUCKETS;

/// The smallest rectangle holding every drawn pixel.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Bounds {
    pub left: usize,
    pub top: usize,
    pub width: usize,
    pub height: usize,
}

/// The bounds of the drawn pixels of `argb`, `width` a row; `None` when
/// nothing is drawn.
pub fn bounds(argb: &[u32], width: usize) -> Option<Bounds> {
    #[cfg(target_arch = "aarch64")]
    if active().neon {
        // SAFETY: NEON is present (checked above).
        return unsafe { neon::bounds(argb, width) };
    }
    scalar::bounds(argb, width)
}

/// Adds every drawn pixel of `argb` to `count` and `sums` (red, green, blue)
/// at its bucket. Both hold [`BUCKETS`] entries.
pub fn histogram(argb: &[u32], count: &mut [u32], sums: &mut [[u64; 3]]) {
    let (count, sums) = (&mut count[..BUCKETS], &mut sums[..BUCKETS]);
    #[cfg(target_arch = "aarch64")]
    if active().neon {
        // SAFETY: NEON is present (checked above).
        unsafe { neon::histogram(argb, count, sums) };
        return;
    }
    scalar::histogram(argb, count, sums)
}

/// The palette index of every pixel of `b` in `argb` (`width` a row), row
/// by row into `out` (`b.width * b.height`): `lookup[bucket]` for a drawn
/// pixel, `lookup[BUCKETS]` (transparent) for the rest.
pub fn map_indices(argb: &[u32], width: usize, b: &Bounds, lookup: &[u8], out: &mut [u8]) {
    let lookup = &lookup[..=BUCKETS];
    #[cfg(target_arch = "aarch64")]
    if active().neon {
        // SAFETY: NEON is present (checked above).
        unsafe { neon::map_indices(argb, width, b, lookup, out) };
        return;
    }
    scalar::map_indices(argb, width, b, lookup, out)
}

pub mod scalar {
    use super::Bounds;
    use crate::gif::{bucket, BUCKETS};

    #[inline]
    pub fn drawn(p: u32) -> bool {
        p >> 31 != 0
    }

    pub fn bounds(argb: &[u32], width: usize) -> Option<Bounds> {
        let (mut left, mut top, mut right, mut bottom) = (usize::MAX, usize::MAX, 0, 0);
        for (y, row) in argb.chunks_exact(width).enumerate() {
            for (x, &p) in row.iter().enumerate() {
                if drawn(p) {
                    left = left.min(x);
                    right = right.max(x);
                    top = top.min(y);
                    bottom = bottom.max(y);
                }
            }
        }
        (top != usize::MAX).then(|| Bounds { left, top, width: right - left + 1, height: bottom - top + 1 })
    }

    #[inline]
    pub fn add(p: u32, count: &mut [u32], sums: &mut [[u64; 3]]) {
        let at = bucket(p);
        count[at] += 1;
        let s = &mut sums[at];
        s[0] += (p >> 16 & 0xFF) as u64;
        s[1] += (p >> 8 & 0xFF) as u64;
        s[2] += (p & 0xFF) as u64;
    }

    pub fn histogram(argb: &[u32], count: &mut [u32], sums: &mut [[u64; 3]]) {
        for &p in argb {
            if drawn(p) {
                add(p, count, sums);
            }
        }
    }

    pub fn map_indices(argb: &[u32], width: usize, b: &Bounds, lookup: &[u8], out: &mut [u8]) {
        for (y, out_row) in out.chunks_exact_mut(b.width).take(b.height).enumerate() {
            let row = &argb[(b.top + y) * width + b.left..][..b.width];
            for (o, &p) in out_row.iter_mut().zip(row) {
                *o = lookup[if drawn(p) { bucket(p) } else { BUCKETS }];
            }
        }
    }
}

#[cfg(target_arch = "aarch64")]
pub mod neon {
    use super::{scalar, Bounds};
    use crate::gif::BUCKETS;
    use core::arch::aarch64::*;

    /// Whether any of the 16 pixels at `p` is drawn.
    #[inline]
    #[target_feature(enable = "neon")]
    unsafe fn any_drawn16(p: *const u32) -> bool {
        let v = vorrq_u32(vorrq_u32(vld1q_u32(p), vld1q_u32(p.add(4))), vorrq_u32(vld1q_u32(p.add(8)), vld1q_u32(p.add(12))));
        vmaxvq_u32(v) >> 31 != 0
    }

    /// The first drawn pixel of `row`, if any.
    #[target_feature(enable = "neon")]
    unsafe fn first_drawn(row: &[u32]) -> Option<usize> {
        let mut x = 0;
        while x + 16 <= row.len() && !any_drawn16(row.as_ptr().add(x)) {
            x += 16;
        }
        row[x..].iter().position(|&p| scalar::drawn(p)).map(|i| x + i)
    }

    /// The last drawn pixel of `row`, if any.
    #[target_feature(enable = "neon")]
    unsafe fn last_drawn(row: &[u32]) -> Option<usize> {
        let mut end = row.len();
        while end >= 16 && !any_drawn16(row.as_ptr().add(end - 16)) {
            end -= 16;
        }
        row[..end].iter().rposition(|&p| scalar::drawn(p))
    }

    #[target_feature(enable = "neon")]
    pub unsafe fn bounds(argb: &[u32], width: usize) -> Option<Bounds> {
        let (mut left, mut top, mut right, mut bottom) = (usize::MAX, usize::MAX, 0, 0);
        for (y, row) in argb.chunks_exact(width).enumerate() {
            // An empty row is scanned once; a drawn one only in from each end.
            let Some(first) = first_drawn(row) else { continue };
            let last = last_drawn(&row[first..]).unwrap() + first;
            left = left.min(first);
            right = right.max(last);
            top = top.min(y);
            bottom = y;
        }
        (top != usize::MAX).then(|| Bounds { left, top, width: right - left + 1, height: bottom - top + 1 })
    }

    /// The buckets of four pixels, and `BUCKETS` where a pixel is not drawn.
    #[inline]
    #[target_feature(enable = "neon")]
    unsafe fn buckets4(p: uint32x4_t) -> uint32x4_t {
        let r = vandq_u32(vshrq_n_u32::<9>(p), vdupq_n_u32(0x7C00));
        let g = vandq_u32(vshrq_n_u32::<6>(p), vdupq_n_u32(0x03E0));
        let b = vandq_u32(vshrq_n_u32::<3>(p), vdupq_n_u32(0x001F));
        let drawn = vreinterpretq_u32_s32(vshrq_n_s32::<31>(vreinterpretq_s32_u32(p)));
        vbslq_u32(drawn, vorrq_u32(vorrq_u32(r, g), b), vdupq_n_u32(BUCKETS as u32))
    }

    /// The pixels of one bucket met so far, summed per lane: a sticker's
    /// smooth areas give long runs of one bucket, and a run costs one table
    /// update instead of one per pixel (each of which would wait on the last
    /// when they hit the same entry).
    struct Run {
        bucket: u32,
        n: uint32x4_t,
        r: uint32x4_t,
        g: uint32x4_t,
        b: uint32x4_t,
        chunks: u32,
    }

    impl Run {
        /// Lanes are u32: flushed before 255 * this many could overflow one.
        const MAX_CHUNKS: u32 = 1 << 20;

        #[inline]
        #[target_feature(enable = "neon")]
        unsafe fn new(bucket: u32) -> Run {
            let z = vdupq_n_u32(0);
            Run { bucket, n: z, r: z, g: z, b: z, chunks: 0 }
        }

        /// Adds the lanes of `p` that `mask` selects (all ones or zero).
        #[inline]
        #[target_feature(enable = "neon")]
        unsafe fn add(&mut self, p: uint32x4_t, mask: uint32x4_t) {
            let byte = vdupq_n_u32(0xFF);
            self.n = vsubq_u32(self.n, mask);
            self.r = vaddq_u32(self.r, vandq_u32(vandq_u32(vshrq_n_u32::<16>(p), byte), mask));
            self.g = vaddq_u32(self.g, vandq_u32(vandq_u32(vshrq_n_u32::<8>(p), byte), mask));
            self.b = vaddq_u32(self.b, vandq_u32(vandq_u32(p, byte), mask));
            self.chunks += 1;
        }

        #[inline]
        #[target_feature(enable = "neon")]
        unsafe fn flush(&mut self, count: &mut [u32], sums: &mut [[u64; 3]]) {
            if (self.bucket as usize) < BUCKETS && self.chunks > 0 {
                let at = self.bucket as usize;
                count[at] += vaddvq_u32(self.n);
                let s = &mut sums[at];
                s[0] += vaddlvq_u32(self.r);
                s[1] += vaddlvq_u32(self.g);
                s[2] += vaddlvq_u32(self.b);
            }
            *self = Run::new(self.bucket);
        }
    }

    #[target_feature(enable = "neon")]
    pub unsafe fn histogram(argb: &[u32], count: &mut [u32], sums: &mut [[u64; 3]]) {
        let mut chunks = argb.chunks_exact(4);
        let mut run = Run::new(BUCKETS as u32);
        let mut at = [0u32; 4];
        for c in &mut chunks {
            let p = vld1q_u32(c.as_ptr());
            // A run of four undrawn pixels, the whole margin of a sticker, costs one test.
            if vmaxvq_u32(p) >> 31 == 0 {
                continue;
            }
            let buckets = buckets4(p);
            let same = vceqq_u32(buckets, vdupq_n_u32(run.bucket));
            if vminvq_u32(same) != 0 {
                run.add(p, same);
                if run.chunks == Run::MAX_CHUNKS {
                    run.flush(count, sums);
                }
                continue;
            }
            // Some lanes leave the run: those in it join it, the rest go to
            // the tables, and the last lane's bucket starts the next run.
            run.add(p, same);
            vst1q_u32(at.as_mut_ptr(), buckets);
            for (&p, &at) in c.iter().zip(&at) {
                if (at as usize) < BUCKETS && at != run.bucket {
                    scalar::add(p, count, sums);
                }
            }
            run.flush(count, sums);
            run.bucket = at[3];
        }
        run.flush(count, sums);
        scalar::histogram(chunks.remainder(), count, sums);
    }

    #[target_feature(enable = "neon")]
    pub unsafe fn map_indices(argb: &[u32], width: usize, b: &Bounds, lookup: &[u8], out: &mut [u8]) {
        let mut at = [0u32; 4];
        for (y, out_row) in out.chunks_exact_mut(b.width).take(b.height).enumerate() {
            let row = &argb[(b.top + y) * width + b.left..][..b.width];
            let mut pixels = row.chunks_exact(4);
            let mut outs = out_row.chunks_exact_mut(4);
            for (c, o) in (&mut pixels).zip(&mut outs) {
                vst1q_u32(at.as_mut_ptr(), buckets4(vld1q_u32(c.as_ptr())));
                // `at` is at most BUCKETS, inside `lookup` (the dispatcher sliced it so).
                o[0] = *lookup.get_unchecked(at[0] as usize);
                o[1] = *lookup.get_unchecked(at[1] as usize);
                o[2] = *lookup.get_unchecked(at[2] as usize);
                o[3] = *lookup.get_unchecked(at[3] as usize);
            }
            let tail = Bounds { left: 0, top: 0, width: pixels.remainder().len(), height: 1 };
            if tail.width > 0 {
                scalar::map_indices(pixels.remainder(), tail.width, &tail, lookup, outs.into_remainder());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kernels::testing::{Rng, LENGTHS};

    /// A frame with a drawn blob in a transparent margin, half-opaque edges
    /// either side of 128, and colours spread over many buckets.
    fn frame(rng: &mut Rng, w: usize, h: usize, blob: bool) -> Vec<u32> {
        (0..w * h)
            .map(|i| {
                let (x, y) = ((i % w) as i64, (i / w) as i64);
                let inside = blob && (x - w as i64 / 2).pow(2) + (y - h as i64 / 2).pow(2) <= (w.min(h) as i64 / 3).pow(2);
                let alpha = if inside { 0x7E + (rng.next_u64() % 0x82) as u32 } else if rng.next_u64() % 9 == 0 { 0x7F } else { 0 };
                alpha << 24 | (rng.next_u64() as u32 & 0xFF_FFFF)
            })
            .collect()
    }

    fn shapes() -> Vec<(usize, usize)> {
        let mut s: Vec<(usize, usize)> = LENGTHS.iter().filter(|&&n| n > 0 && n <= 129).map(|&n| (n, 3)).collect();
        s.extend([(1, 1), (17, 40), (64, 64), (100, 37)]);
        s
    }

    #[test]
    fn bounds_match_the_reference() {
        let mut rng = Rng::new(11);
        for (w, h) in shapes() {
            for blob in [false, true] {
                let mut px = frame(&mut rng, w, h, blob);
                let expected = scalar::bounds(&px, w);
                assert_eq!(expected, bounds(&px, w), "{w}x{h}");
                // One drawn pixel anywhere.
                px.iter_mut().for_each(|p| *p &= 0x7FFF_FFFF);
                let at = rng.next_u64() as usize % px.len();
                px[at] |= 0x8000_0000;
                assert_eq!(Some(Bounds { left: at % w, top: at / w, width: 1, height: 1 }), bounds(&px, w), "{w}x{h} at {at}");
            }
        }
    }

    #[test]
    fn histogram_matches_the_reference() {
        let mut rng = Rng::new(12);
        for (w, h) in shapes() {
            let mut px = frame(&mut rng, w, h, true);
            if w % 2 == 1 {
                // Flat patches instead of noise: long runs of one bucket,
                // broken by transparent pixels and lone colours.
                for (i, p) in px.iter_mut().enumerate() {
                    let (x, y) = (i % w, i / w);
                    let flat = 0xFF00_0000 | ((x / 7) as u32 * 0x0A_1F_33 + (y / 5) as u32 * 0x31_07_0B) & 0xFF_FFFF;
                    *p = match rng.next_u64() % 23 {
                        0 => *p & 0x7FFF_FFFF,
                        1 => *p | 0x8000_0000,
                        _ => flat,
                    };
                }
            }
            let (mut c0, mut s0) = (vec![0u32; BUCKETS], vec![[0u64; 3]; BUCKETS]);
            let (mut c1, mut s1) = (vec![0u32; BUCKETS], vec![[0u64; 3]; BUCKETS]);
            scalar::histogram(&px, &mut c0, &mut s0);
            histogram(&px, &mut c1, &mut s1);
            assert!(c0 == c1 && s0 == s1, "{w}x{h}");
        }
    }

    #[test]
    fn index_mapping_matches_the_reference() {
        let mut rng = Rng::new(13);
        let lookup: Vec<u8> = (0..=BUCKETS).map(|i| if i == BUCKETS { 0 } else { 1 + (i % 251) as u8 }).collect();
        for (w, h) in shapes() {
            let px = frame(&mut rng, w, h, true);
            let b = scalar::bounds(&px, w).unwrap_or(Bounds { left: 0, top: 0, width: w, height: h });
            let (mut o0, mut o1) = (vec![0u8; b.width * b.height], vec![0u8; b.width * b.height]);
            scalar::map_indices(&px, w, &b, &lookup, &mut o0);
            map_indices(&px, w, &b, &lookup, &mut o1);
            assert_eq!(o0, o1, "{w}x{h}");
        }
    }
}

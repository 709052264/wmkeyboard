//! Animated GIF writing, a frame at a time, for stickers sent into a field
//! that takes no WebP (`GifEncoder` on the Kotlin side, through JNI).
//!
//! Each frame is a finished picture of the whole canvas as ARGB colour ints.
//! It gets a palette of its own, up to 255 colours chosen by median cut plus
//! index 0 kept for "nothing here", is cropped to the part that has anything
//! in it, and asks for the canvas to be cleared before the next frame, so no
//! frame leans on the one before. Pixels under half opacity are transparent.
//!
//! Exact: the bytes are the Kotlin encoder's for the same frames (that
//! encoder is the oracle `GifParityTest` compares against). Everything is
//! integer arithmetic; the median cut sorts and cuts in the same order, and
//! LZW's output depends only on what its table holds, not on how it is
//! looked up. The per-pixel loops (the bounds, the histogram and the index
//! mapping) are in [`crate::kernels::gif`].

use crate::kernels::gif::{bounds, histogram, map_indices, Bounds};

/// The largest canvas side GIF can declare.
pub const MAX_DIMENSION: usize = 65_535;

/// The byte that ends the file.
pub const TRAILER: u8 = 0x3B;

const EXTENSION: u8 = 0x21;
const GRAPHIC_CONTROL: u8 = 0xF9;
const GRAPHIC_CONTROL_SIZE: u8 = 4;
const APPLICATION: u8 = 0xFF;
const APPLICATION_SIZE: u8 = 11;
const LOOP_BLOCK_SIZE: u8 = 3;
const IMAGE: u8 = 0x2C;
const NO_GLOBAL_TABLE: u8 = 0x70;
const LOCAL_TABLE_FLAG: u8 = 0x80;
const DISPOSE_TO_BACKGROUND: u8 = 2;
const TRANSPARENT_FLAG: u8 = 1;
const MIN_CODE_BITS: u32 = 2;
const MS_PER_TICK: i32 = 10;
const HALF_TICK: i32 = 5;
/// Players treat a delay under two ticks as "unset" and slow it to a tenth of a second.
const MIN_DELAY_MS: i32 = 20;
const MAX_DELAY_MS: i32 = 655_350;

/// Index 0 of every palette: transparent.
pub const TRANSPARENT: u8 = 0;
const MAX_COLOURS: usize = 255;
const BUCKET_BITS: u32 = 5;
/// Colours are counted at five bits a channel.
pub const BUCKETS: usize = 1 << (BUCKET_BITS * 3);
const CHANNEL_MASK: u32 = (1 << BUCKET_BITS) - 1;

fn u16_le(out: &mut Vec<u8>, value: usize) {
    out.push(value as u8);
    out.push((value >> 8) as u8);
}

/// The file's header: the logical screen, no global table, loop for ever.
pub fn header(width: usize, height: usize, out: &mut Vec<u8>) {
    out.extend_from_slice(b"GIF89a");
    u16_le(out, width);
    u16_le(out, height);
    out.push(NO_GLOBAL_TABLE);
    out.push(0); // background index
    out.push(0); // aspect ratio
    out.push(EXTENSION);
    out.push(APPLICATION);
    out.push(APPLICATION_SIZE);
    out.extend_from_slice(b"NETSCAPE2.0");
    out.push(LOOP_BLOCK_SIZE);
    out.push(1);
    u16_le(out, 0);
    out.push(0);
}

/// Encodes frames; holds the tables a frame needs (about a megabyte) so the
/// next one reuses them. One per thread or per animation.
pub struct FrameEncoder {
    palette: Palette,
    lzw: Lzw,
    indices: Vec<u8>,
}

impl Default for FrameEncoder {
    fn default() -> Self {
        Self::new()
    }
}

impl FrameEncoder {
    pub fn new() -> FrameEncoder {
        FrameEncoder { palette: Palette::new(), lzw: Lzw::new(), indices: Vec::new() }
    }

    /// Appends one frame of `width * height` colour ints (`argb.len()` must
    /// be exactly that), shown for `duration_ms`: its graphic control, image
    /// descriptor, local palette and compressed pixels.
    pub fn frame(&mut self, argb: &[u32], width: usize, height: usize, duration_ms: i32, out: &mut Vec<u8>) {
        assert!(width >= 1 && height >= 1 && width <= MAX_DIMENSION && height <= MAX_DIMENSION);
        assert_eq!(argb.len(), width * height, "frame size");
        let b = bounds(argb, width).unwrap_or(Bounds { left: 0, top: 0, width: 1, height: 1 });
        self.palette.build(argb);
        let bits = self.palette.bits;

        // Graphic control: clear to background afterwards, index 0 is transparent.
        out.push(EXTENSION);
        out.push(GRAPHIC_CONTROL);
        out.push(GRAPHIC_CONTROL_SIZE);
        out.push((DISPOSE_TO_BACKGROUND << 2) | TRANSPARENT_FLAG);
        u16_le(out, ((duration_ms.clamp(MIN_DELAY_MS, MAX_DELAY_MS) + HALF_TICK) / MS_PER_TICK) as usize);
        out.push(TRANSPARENT);
        out.push(0);

        out.push(IMAGE);
        u16_le(out, b.left);
        u16_le(out, b.top);
        u16_le(out, b.width);
        u16_le(out, b.height);
        out.push(LOCAL_TABLE_FLAG | (bits - 1) as u8);
        out.extend_from_slice(&self.palette.rgb[..(1 << bits) * 3]);

        self.indices.clear();
        self.indices.resize(b.width * b.height, 0);
        map_indices(argb, width, &b, &self.palette.lookup, &mut self.indices);
        self.lzw.encode(&self.indices, bits.max(MIN_CODE_BITS), out);
    }
}

/// A whole animation in memory: the shape WMK Desktop wants.
pub struct Encoder {
    width: usize,
    height: usize,
    frames: FrameEncoder,
    out: Vec<u8>,
}

impl Encoder {
    pub fn new(width: usize, height: usize) -> Encoder {
        assert!(width >= 1 && height >= 1 && width <= MAX_DIMENSION && height <= MAX_DIMENSION);
        let mut out = Vec::new();
        header(width, height, &mut out);
        Encoder { width, height, frames: FrameEncoder::new(), out }
    }

    pub fn add_frame(&mut self, argb: &[u32], duration_ms: i32) {
        self.frames.frame(argb, self.width, self.height, duration_ms, &mut self.out);
    }

    /// The finished file. One with no frames is still a valid, empty GIF.
    pub fn finish(mut self) -> Vec<u8> {
        self.out.push(TRAILER);
        self.out
    }
}

/// The five-bit-a-channel bucket of an opaque colour int.
#[inline]
pub fn bucket(argb: u32) -> usize {
    ((argb >> 9 & 0x7C00) | (argb >> 6 & 0x03E0) | (argb >> 3 & 0x001F)) as usize
}

#[inline]
fn channel(bucket: u16, which: u32) -> u32 {
    (bucket as u32) >> (BUCKET_BITS * (2 - which)) & CHANNEL_MASK
}

/// A frame's colours: index 0 is transparent, the rest come from a median
/// cut over the frame's opaque pixels, counted per [`bucket`].
struct Palette {
    rgb: [u8; (MAX_COLOURS + 1) * 3],
    bits: u32,
    /// Palette index per bucket, and a last entry, [`TRANSPARENT`], for
    /// pixels under half opacity (see [`map_indices`]).
    lookup: Vec<u8>,
    /// Per bucket: pixels, and the sum of each true channel, so that an
    /// entry is the mean of what it stands for and not a bucket's corner.
    /// Zero between frames.
    count: Vec<u32>,
    sums: Vec<[u64; 3]>,
    /// The buckets in use, then the median cut's boxes: runs of `used`.
    used: Vec<u16>,
    keyed: Vec<u32>,
    bitmap: Vec<u64>,
    boxes: Vec<Cut>,
}

#[derive(Clone, Copy)]
struct Cut {
    from: usize,
    until: usize,
    population: u64,
}

impl Palette {
    fn new() -> Palette {
        Palette {
            rgb: [0; (MAX_COLOURS + 1) * 3],
            bits: 1,
            lookup: vec![TRANSPARENT; BUCKETS + 1],
            count: vec![0; BUCKETS],
            sums: vec![[0; 3]; BUCKETS],
            used: Vec::with_capacity(BUCKETS),
            keyed: Vec::with_capacity(BUCKETS),
            bitmap: vec![0; BUCKETS / 64],
            boxes: Vec::with_capacity(MAX_COLOURS),
        }
    }

    fn build(&mut self, argb: &[u32]) {
        histogram(argb, &mut self.count, &mut self.sums);
        self.used.clear();
        for (at, &n) in self.count.iter().enumerate() {
            if n > 0 {
                self.used.push(at as u16);
            }
        }
        self.split();

        // Entry 0 is the transparent one; the palette is padded to a power
        // of two, which is the only size GIF can declare.
        let boxes = self.boxes.len();
        let mut bits = 1;
        while 1 << bits < boxes + 1 {
            bits += 1;
        }
        self.bits = bits;
        self.rgb[..(1 << bits) * 3].fill(0);
        for (index, b) in self.boxes.iter().enumerate() {
            let (mut n, mut r, mut g, mut bl) = (0u64, 0u64, 0u64, 0u64);
            for &at in &self.used[b.from..b.until] {
                let at = at as usize;
                n += self.count[at] as u64;
                let s = self.sums[at];
                r += s[0];
                g += s[1];
                bl += s[2];
                self.lookup[at] = (index + 1) as u8;
            }
            let entry = (index + 1) * 3;
            self.rgb[entry] = (r / n) as u8;
            self.rgb[entry + 1] = (g / n) as u8;
            self.rgb[entry + 2] = (bl / n) as u8;
        }
        for &at in &self.used {
            self.count[at as usize] = 0;
            self.sums[at as usize] = [0; 3];
        }
    }

    fn population(&self, from: usize, until: usize) -> u64 {
        self.used[from..until].iter().map(|&at| self.count[at as usize] as u64).sum()
    }

    /// Median cut: keep halving the most populous box that still holds more
    /// than one colour.
    fn split(&mut self) {
        self.boxes.clear();
        if self.used.is_empty() {
            return;
        }
        let size = self.used.len();
        self.boxes.push(Cut { from: 0, until: size, population: self.population(0, size) });
        while self.boxes.len() < MAX_COLOURS {
            // The first of the most populous, as a max over the boxes in order.
            let mut at = usize::MAX;
            for (i, b) in self.boxes.iter().enumerate() {
                if b.until - b.from > 1 && (at == usize::MAX || b.population > self.boxes[at].population) {
                    at = i;
                }
            }
            if at == usize::MAX {
                break;
            }
            let Cut { from, until, population } = self.boxes[at];
            // The channel this box is widest along; the first on a tie.
            let (mut lo, mut hi) = ([u32::MAX; 3], [0u32; 3]);
            for &bucket in &self.used[from..until] {
                for which in 0..3 {
                    let v = channel(bucket, which);
                    lo[which as usize] = lo[which as usize].min(v);
                    hi[which as usize] = hi[which as usize].max(v);
                }
            }
            let mut widest = 0;
            for which in 1..3 {
                if hi[which] - lo[which] > hi[widest] - lo[widest] {
                    widest = which;
                }
            }
            self.sort_by_channel(from, until, widest as u32);
            // Cut where half the pixels are on each side, but never so that
            // one side is empty.
            let mut seen = 0u64;
            let mut cut = from + 1;
            for i in from..until - 1 {
                seen += self.count[self.used[i] as usize] as u64;
                cut = i + 1;
                if seen * 2 >= population {
                    break;
                }
            }
            // `seen` is the population of `from..cut`: the loop stopped there.
            self.boxes[at] = Cut { from, until: cut, population: seen };
            self.boxes.push(Cut { from: cut, until, population: population - seen });
        }
    }

    /// Sorts `used[from..until]` by one channel, then by bucket: the
    /// Kotlin encoder's order (a key with the channel on top and the bucket
    /// underneath). Buckets are distinct, so the keys are too: a large run
    /// sorts by setting each key's bit in a bitmap of all 2^15 and reading
    /// the bits back in order, a small one by comparison.
    fn sort_by_channel(&mut self, from: usize, until: usize, which: u32) {
        // The bucket with `which` moved to the top: one more permutation of
        // its 15 bits, so the key is as wide as a bucket.
        let key = |b: u16| -> u32 {
            let b = b as u32;
            let (c, shift) = (channel(b as u16, which), BUCKET_BITS * (2 - which));
            let rest = (b >> (shift + BUCKET_BITS)) << shift | (b & ((1 << shift) - 1));
            c << (BUCKET_BITS * 2) | rest
        };
        let unkey = |k: u32| -> u16 {
            let shift = BUCKET_BITS * (2 - which);
            let (c, rest) = (k >> (BUCKET_BITS * 2), k & ((1 << (BUCKET_BITS * 2)) - 1));
            ((rest >> shift) << (shift + BUCKET_BITS) | c << shift | (rest & ((1 << shift) - 1))) as u16
        };
        let run = &mut self.used[from..until];
        if run.len() <= SORT_BY_BITMAP_FROM {
            self.keyed.clear();
            self.keyed.extend(run.iter().map(|&b| key(b)));
            self.keyed.sort_unstable();
            for (slot, &k) in run.iter_mut().zip(&self.keyed) {
                *slot = unkey(k);
            }
            return;
        }
        let bitmap = &mut self.bitmap;
        let (mut lo, mut hi) = (bitmap.len(), 0);
        for &b in run.iter() {
            let k = key(b) as usize;
            bitmap[k >> 6] |= 1 << (k & 63);
            lo = lo.min(k >> 6);
            hi = hi.max(k >> 6);
        }
        let mut slots = run.iter_mut();
        for (w, word) in bitmap[lo..=hi].iter_mut().enumerate() {
            let mut bits = std::mem::take(word);
            while bits != 0 {
                let k = ((lo + w) << 6) as u32 | bits.trailing_zeros();
                *slots.next().unwrap() = unkey(k);
                bits &= bits - 1;
            }
        }
    }
}

/// Runs longer than this sort through [`Palette::sort_by_channel`]'s bitmap.
const SORT_BY_BITMAP_FROM: usize = 96;

/// GIF's flavour of LZW: variable-width codes, least significant bit first,
/// in blocks of up to 255 bytes.
struct Lzw {
    /// (prefix code, next index) -> code, indexed directly at
    /// `prefix << data_bits | index`; 0 is "none" (no code is 0: they start
    /// past the clear and end codes). Where Kotlin hashes, this is one load
    /// per pixel; it holds 2 MB at 8 bits, of which a frame touches what its
    /// strings reach, and it is cleared through `assigned`, not wholesale.
    next: Vec<u16>,
    assigned: Vec<u32>,
    packed: Vec<u8>,
}

const MAX_CODES: u32 = 4096;
const MAX_DATA_BITS: u32 = 8;
const MAX_BLOCK: usize = 255;

impl Lzw {
    fn new() -> Lzw {
        Lzw { next: vec![0; (MAX_CODES as usize) << MAX_DATA_BITS], assigned: Vec::with_capacity(MAX_CODES as usize), packed: Vec::new() }
    }

    /// Compresses `indices` (at least one, each under `1 << data_bits`),
    /// codes starting one wider than `data_bits`, as the minimum code size
    /// byte, the data blocks and the empty block that ends them.
    fn encode(&mut self, indices: &[u8], data_bits: u32, out: &mut Vec<u8>) {
        let clear = 1u32 << data_bits;
        let end = clear + 1;
        let (next, assigned, packed) = (&mut self.next[..], &mut self.assigned, &mut self.packed);
        packed.clear();
        let mut acc = 0u64;
        let mut bits = 0u32;
        let mut emit = |code: u32, width: u32| {
            acc |= (code as u64) << bits;
            bits += width;
            while bits >= 8 {
                packed.push(acc as u8);
                acc >>= 8;
                bits -= 8;
            }
        };

        let mut width = data_bits + 1;
        let mut code = end + 1;
        emit(clear, width);
        let mut prefix = indices[0] as u32;
        for &index in &indices[1..] {
            let slot = (prefix << data_bits | index as u32) as usize;
            let known = next[slot];
            if known != 0 {
                prefix = known as u32;
                continue;
            }
            emit(prefix, width);
            prefix = index as u32;
            if code < MAX_CODES {
                next[slot] = code as u16;
                assigned.push(slot as u32);
                // The decoder widens one code later than the table grows.
                if code == 1 << width {
                    width += 1;
                }
                code += 1;
            } else {
                emit(clear, width);
                for slot in assigned.drain(..) {
                    next[slot as usize] = 0;
                }
                width = data_bits + 1;
                code = end + 1;
            }
        }
        emit(prefix, width);
        emit(end, width);
        if bits > 0 {
            packed.push(acc as u8);
        }
        for slot in assigned.drain(..) {
            next[slot as usize] = 0;
        }

        out.reserve(packed.len() + packed.len() / MAX_BLOCK + 3);
        out.push(data_bits as u8);
        for block in packed.chunks(MAX_BLOCK) {
            out.push(block.len() as u8);
            out.extend_from_slice(block);
        }
        out.push(0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kernels::testing::Rng;

    /// A plain GIF LZW decoder, for reading back what [`Lzw`] wrote.
    fn unlzw(data: &[u8]) -> (u32, Vec<u8>) {
        let data_bits = data[0] as u32;
        let mut stream = Vec::new();
        let mut at = 1;
        while data[at] != 0 {
            let n = data[at] as usize;
            stream.extend_from_slice(&data[at + 1..at + 1 + n]);
            at += 1 + n;
        }
        assert_eq!(at, data.len() - 1, "one terminator, at the end");
        let clear = 1u32 << data_bits;
        let end = clear + 1;
        let mut dict: Vec<Vec<u8>> = Vec::new();
        let reset = |dict: &mut Vec<Vec<u8>>| {
            dict.clear();
            dict.extend((0..clear).map(|c| vec![c as u8]));
            dict.push(Vec::new());
            dict.push(Vec::new());
        };
        reset(&mut dict);
        let (mut width, mut pos, mut out, mut prev): (u32, usize, Vec<u8>, Option<Vec<u8>>) = (data_bits + 1, 0, Vec::new(), None);
        loop {
            let mut code = 0u32;
            for i in 0..width {
                let bit = stream[(pos + i as usize) / 8] >> ((pos + i as usize) % 8) & 1;
                code |= (bit as u32) << i;
            }
            pos += width as usize;
            if code == clear {
                reset(&mut dict);
                width = data_bits + 1;
                prev = None;
                continue;
            }
            if code == end {
                return (data_bits, out);
            }
            let entry = if (code as usize) < dict.len() {
                dict[code as usize].clone()
            } else {
                let p = prev.clone().unwrap();
                let mut e = p.clone();
                e.push(p[0]);
                e
            };
            if let Some(p) = prev {
                let mut e = p;
                e.push(entry[0]);
                dict.push(e);
            }
            out.extend_from_slice(&entry);
            prev = Some(entry);
            if dict.len() == (1 << width) as usize && width < 12 {
                width += 1;
            }
        }
    }

    #[test]
    fn lzw_reads_back_across_every_code_width_and_table_reset() {
        let mut rng = Rng::new(7);
        for &(n, colours) in &[(1usize, 2u32), (2, 2), (300, 4), (5000, 16), (70_000, 256), (200_000, 7)] {
            let data_bits = (32 - (colours - 1).leading_zeros()).max(2);
            // Runs, so the table fills with long strings, and noise, so it fills fast.
            let indices: Vec<u8> = (0..n)
                .map(|i| if (i / 37) % 2 == 0 { ((i / 500) as u32 % colours) as u8 } else { (rng.next_u64() as u32 % colours) as u8 })
                .collect();
            let mut out = Vec::new();
            Lzw::new().encode(&indices, data_bits, &mut out);
            let (bits, back) = unlzw(&out);
            assert_eq!(data_bits, bits);
            assert_eq!(indices, back, "{n} indices of {colours} colours");
        }
    }

    #[test]
    fn a_frame_is_cropped_to_what_it_draws_with_a_palette_of_its_colours() {
        // A 3 x 2 red block at (5, 2) on a 12 x 8 canvas, one pixel of it
        // under half opacity, and one blue pixel.
        let (red, blue) = (0xFFFF_0000u32, 0xFF00_00FFu32);
        let mut px = vec![0u32; 12 * 8];
        for y in 2..4 {
            for x in 5..8 {
                px[y * 12 + x] = red;
            }
        }
        px[2 * 12 + 5] = 0x7FFF_0000;
        px[3 * 12 + 7] = blue;
        let mut out = Vec::new();
        FrameEncoder::new().frame(&px, 12, 8, 70, &mut out);
        assert_eq!(&out[..8], &[0x21, 0xF9, 4, 9, 7, 0, 0, 0]);
        assert_eq!(&out[8..18], &[0x2C, 5, 0, 2, 0, 3, 0, 2, 0, 0x81]);
        // Transparent and two colours, cut along red (the first of the two
        // widest channels), padded to four entries.
        assert_eq!(&out[18..30], &[0, 0, 0, 0, 0, 0xFF, 0xFF, 0, 0, 0, 0, 0]);
        let (_, indices) = unlzw(&out[30..]);
        assert_eq!(indices, vec![0, 2, 2, 2, 2, 1]);
    }

    #[test]
    fn an_empty_frame_is_one_transparent_pixel() {
        let mut out = Vec::new();
        FrameEncoder::new().frame(&[0; 6], 3, 2, 0, &mut out);
        assert_eq!(&out[4..10], &[2, 0, 0, 0, 0x2C, 0]);
        assert_eq!(&out[10..18], &[0, 0, 0, 1, 0, 1, 0, 0x80]);
        let (_, indices) = unlzw(&out[24..]);
        assert_eq!(indices, vec![0]);
    }

    #[test]
    fn many_colours_make_a_full_palette() {
        let mut rng = Rng::new(3);
        let px: Vec<u32> = (0..64 * 64).map(|_| 0xFF00_0000 | (rng.next_u64() as u32 & 0xFF_FFFF)).collect();
        let mut out = Vec::new();
        let mut encoder = FrameEncoder::new();
        encoder.frame(&px, 64, 64, 40, &mut out);
        assert_eq!(out[17], 0x87);
        // The tables are cleared by what the frame used: the same frame again is the same bytes.
        let mut again = Vec::new();
        encoder.frame(&px, 64, 64, 40, &mut again);
        assert_eq!(out, again);
    }
}

//! Java's `StrictMath`, bit for bit: fdlibm, the library every Java runtime
//! (HotSpot and Android's ART alike) must reproduce exactly for `StrictMath`.
//!
//! The platform `ln` is not one function: Apple's libm, bionic and HotSpot's
//! `Math.log` intrinsic each round about one result in a hundred differently
//! in the last bit. A score that differs by one ulp can reorder two
//! candidates, so the ported prediction code, and the Kotlin code it is
//! checked against, both use these: Kotlin through `StrictMath`, Rust
//! through this module. Then the phone, the JVM evals and WMK Desktop agree
//! everywhere.
//!
//! Transcribed from fdlibm 5.3 (`e_log.c`). Rust never contracts `a * b + c`
//! into a fused multiply-add on its own, which fdlibm's rounding relies on.

const LN2_HI: f64 = 6.93147180369123816490e-01; // 3fe62e42 fee00000
const LN2_LO: f64 = 1.90821492927058770002e-10; // 3dea39ef 35793c76
const TWO54: f64 = 1.80143985094819840000e+16; // 43500000 00000000
const LG1: f64 = 6.666666666666735130e-01; // 3FE55555 55555593
const LG2: f64 = 3.999999999940941908e-01; // 3FD99999 9997FA04
const LG3: f64 = 2.857142874366239149e-01; // 3FD24924 94229359
const LG4: f64 = 2.222219843214978396e-01; // 3FCC71C5 1D8E78AF
const LG5: f64 = 1.818357216161805012e-01; // 3FC74664 96CB03DE
const LG6: f64 = 1.531383769920937332e-01; // 3FC39A09 D078C69F
const LG7: f64 = 1.479819860511658591e-01; // 3FC2F112 DF3E5244

fn hi(x: f64) -> i32 {
    (x.to_bits() >> 32) as i32
}

fn lo(x: f64) -> u32 {
    x.to_bits() as u32
}

fn with_hi(x: f64, hi: i32) -> f64 {
    f64::from_bits(((hi as u32 as u64) << 32) | (x.to_bits() & 0xFFFF_FFFF))
}

/// `StrictMath.log(x)`.
pub fn log(x: f64) -> f64 {
    let mut x = x;
    let mut hx = hi(x);
    let lx = lo(x);
    let mut k: i32 = 0;
    if hx < 0x0010_0000 {
        // x < 2^-1022
        if ((hx & 0x7fff_ffff) as u32 | lx) == 0 {
            return f64::NEG_INFINITY; // log(+-0) = -inf
        }
        if hx < 0 {
            return f64::NAN; // log(-#) = NaN
        }
        k -= 54;
        x *= TWO54; // subnormal: scale up
        hx = hi(x);
    }
    if hx >= 0x7ff0_0000 {
        return x + x;
    }
    k += (hx >> 20) - 1023;
    hx &= 0x000f_ffff;
    let i = (hx + 0x95f64) & 0x10_0000;
    x = with_hi(x, hx | (i ^ 0x3ff0_0000)); // normalise x or x/2
    k += i >> 20;
    let f = x - 1.0;
    if (0x000f_ffff & (2 + hx)) < 3 {
        // |f| < 2^-20
        if f == 0.0 {
            if k == 0 {
                return 0.0;
            }
            let dk = k as f64;
            return dk * LN2_HI + dk * LN2_LO;
        }
        let r = f * f * (0.5 - 0.33333333333333333 * f);
        if k == 0 {
            return f - r;
        }
        let dk = k as f64;
        return dk * LN2_HI - ((r - dk * LN2_LO) - f);
    }
    let s = f / (2.0 + f);
    let dk = k as f64;
    let z = s * s;
    let mut i = hx - 0x6147a;
    let w = z * z;
    let j = 0x6b851 - hx;
    let t1 = w * (LG2 + w * (LG4 + w * LG6));
    let t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)));
    i |= j;
    let r = t2 + t1;
    if i > 0 {
        let hfsq = 0.5 * f * f;
        if k == 0 {
            f - (hfsq - s * (hfsq + r))
        } else {
            dk * LN2_HI - ((hfsq - (s * (hfsq + r) + dk * LN2_LO)) - f)
        }
    } else if k == 0 {
        f - s * (f - r)
    } else {
        dk * LN2_HI - ((s * (f - r) - dk * LN2_LO) - f)
    }
}

/// `StrictMath.log(1.0 + v)` for a count: what the Kotlin walks call
/// `ln1p(v)` (it is `ln(1.0 + v)`, not `log1p`).
pub fn ln1p_count(v: i32) -> f64 {
    log(1.0 + v as f64)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The same hash the generator in the comment below prints, over the
    /// same inputs: every count below two million, a sweep of fractions, and
    /// a million raw bit patterns from a fixed generator.
    ///
    /// ```java
    /// // javac S.java && java S
    /// public class S {
    ///   static long h = 0;
    ///   // NaN payloads differ by CPU even in Java; any NaN counts as one.
    ///   static void mix(double r) { if (r != r) r = Double.NaN; h = h * 1000003 ^ Double.doubleToRawLongBits(r); }
    ///   public static void main(String[] a) {
    ///     for (int v = 0; v < 2_000_000; v++) mix(StrictMath.log(1.0 + v));
    ///     for (int v = 1; v < 400_000; v++) mix(StrictMath.log(v * 0.00025));
    ///     long s = 0x9E3779B97F4A7C15L;
    ///     for (int i = 0; i < 1_000_000; i++) { s ^= s << 13; s ^= s >>> 7; s ^= s << 17; mix(StrictMath.log(Double.longBitsToDouble(s))); }
    ///     System.out.println(Long.toHexString(h)); } }
    /// ```
    #[test]
    fn log_is_strict_math_bit_for_bit() {
        let mut h: i64 = 0;
        let mut mix = |v: f64| {
            let v = if v.is_nan() { f64::NAN } else { v };
            h = h.wrapping_mul(1000003) ^ (v.to_bits() as i64)
        };
        for v in 0..2_000_000 {
            mix(log(1.0 + v as f64));
        }
        for v in 1..400_000 {
            mix(log(v as f64 * 0.00025));
        }
        let mut s: u64 = 0x9E37_79B9_7F4A_7C15;
        for _ in 0..1_000_000 {
            s ^= s << 13;
            s ^= s >> 7;
            s ^= s << 17;
            mix(log(f64::from_bits(s)));
        }
        assert_eq!(STRICT_LOG_HASH, format!("{:x}", h));
    }

    const STRICT_LOG_HASH: &str = "d2dc08f9e66f56fe";

    #[test]
    fn edge_cases() {
        assert_eq!(f64::NEG_INFINITY, log(0.0));
        assert_eq!(f64::NEG_INFINITY, log(-0.0));
        assert!(log(-1.0).is_nan());
        assert!(log(f64::NAN).is_nan());
        assert_eq!(f64::INFINITY, log(f64::INFINITY));
        assert_eq!(0.0, log(1.0));
        assert_eq!(0.0, ln1p_count(0));
    }
}

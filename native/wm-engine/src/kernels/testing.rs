//! What every kernel's parity test needs: seeded inputs and a tolerance
//! check against the scalar reference.

/// Held by every test that flips [`super::set_force_scalar`], so one does
/// not pin another's kernels halfway through.
pub static FORCE: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// A small deterministic generator (xorshift64*), so a failing case
/// reproduces from its seed without a dependency.
pub struct Rng(u64);

impl Rng {
    pub fn new(seed: u64) -> Rng {
        Rng(seed.max(1))
    }

    pub fn next_u64(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.0 = x;
        x.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    /// Uniform in [-1, 1).
    pub fn unit(&mut self) -> f32 {
        (self.next_u64() >> 40) as f32 / (1u64 << 23) as f32 - 1.0
    }

    /// Uniform in [0, 1).
    pub fn f32(&mut self) -> f32 {
        (self.next_u64() >> 40) as f32 / (1u64 << 24) as f32
    }

    /// Uniform in [0, n).
    pub fn below(&mut self, n: u64) -> u64 {
        self.next_u64() % n
    }

    pub fn floats(&mut self, n: usize) -> Vec<f32> {
        (0..n).map(|_| self.unit()).collect()
    }

    pub fn bytes(&mut self, n: usize) -> Vec<i8> {
        (0..n).map(|_| self.next_u64() as i8).collect()
    }
}

/// Lengths that cover the empty case, every tail length below a few vector
/// widths, and a few realistic sizes (an embedding is 256 to 1024 wide).
pub const LENGTHS: &[usize] = &[
    0, 1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129, 255, 256, 384, 768, 1024,
];

/// Asserts `actual` is within `relative` of `expected`, measured against
/// `scale` (the sum of the absolute terms, so a sum that cancels to near
/// zero is not held to an impossible relative bound).
pub fn assert_close(expected: f32, actual: f32, scale: f32, relative: f32, what: &str) {
    let allowed = relative * scale.max(1.0);
    assert!(
        (expected - actual).abs() <= allowed,
        "{what}: reference {expected}, simd {actual}, off by {} (allowed {allowed})",
        (expected - actual).abs()
    );
}

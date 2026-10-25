//! The learned autocorrect gate: how sure the engine is that the best
//! correction it found is the word that was meant.
//!
//! The old gate was one rule, the best candidate's lead over the runner-up
//! against a slider, with surcharges and shapes bolted on around it. This is
//! a logistic model over everything that gate could see at once: the lead,
//! what the edit was (how far, how many, slips, accents, a completion), how
//! long the word is, whether the lists and the user's own words agree, what
//! the context model thinks of the candidate, and how the word was typed.
//! It answers one probability; the caller applies above one threshold and
//! offers above a lower one, and the confidence slider moves the thresholds.
//!
//! The weights are fitted offline by `tools/wmlm/train_confidence.py` on
//! rows the typing harness (`examples/typing_eval.rs`, `DUMP=1`) writes:
//! words from real sentences typed by a scattered finger, and words held out
//! of the list typed right, which is what a false correction looks like.
//! [`WEIGHTS`] and the standardisation below are that script's output,
//! pasted in; nothing here is tuned by hand.

/// One feature vector's width.
pub const FEATURES: usize = 20;

/// What the gate sees about one commit.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Case {
    /// The best candidate's score less the runner-up's (or the solo floor).
    pub margin: f64,
    pub top_score: f64,
    pub top_edit_cost: f64,
    pub top_edits: u32,
    pub top_slips: u32,
    pub top_accents: u32,
    pub top_completed: u32,
    pub top_in_dictionary: bool,
    pub top_in_lexicon: bool,
    /// The lists and the lexicon independently name the same word.
    pub two_sources: bool,
    /// The best reading mixes slips with a real edit.
    pub mixes_slips: bool,
    pub typed_len: usize,
    pub has_runner: bool,
    pub runner_edit_cost: f64,
    pub candidates: usize,
    /// ln P(top | context) from the context model, `None` without one or
    /// for a word it does not know.
    pub lm_top: Option<f32>,
    /// The context model's best log-probability among the candidates.
    pub lm_best: Option<f32>,
    /// ln of the typing-rhythm multiplier (0 at rest).
    pub timing: f64,
    /// The typed spelling is a listed word the engine reads as a stand-in
    /// (an accentless or corpus-typo spelling).
    pub typed_shadowed: bool,
}

impl Case {
    /// The raw feature vector, before standardisation.
    pub fn features(&self) -> [f32; FEATURES] {
        let b = |v: bool| if v { 1.0 } else { 0.0 };
        let lm_top = self.lm_top.unwrap_or(0.0);
        let lm_gap = match (self.lm_top, self.lm_best) {
            (Some(t), Some(b)) => t - b,
            _ => 0.0,
        };
        [
            self.margin.clamp(-5.0, 15.0) as f32,
            self.top_score.clamp(-10.0, 20.0) as f32,
            self.top_edit_cost as f32,
            self.top_edits as f32,
            self.top_slips as f32,
            self.top_accents as f32,
            self.top_completed as f32,
            b(self.top_in_dictionary),
            b(self.top_in_lexicon),
            b(self.two_sources),
            b(self.mixes_slips),
            self.typed_len.min(20) as f32,
            b(self.has_runner),
            self.runner_edit_cost as f32,
            self.candidates.min(8) as f32,
            lm_top.clamp(-20.0, 0.0),
            lm_gap.clamp(-20.0, 0.0),
            b(self.lm_top.is_some()),
            self.timing as f32,
            b(self.typed_shadowed),
        ]
    }
}

/// A fitted gate: standardised features, one tanh hidden layer, a logistic
/// output.
#[derive(Clone, Debug, PartialEq)]
pub struct Confidence {
    pub mean: [f32; FEATURES],
    pub scale: [f32; FEATURES],
    pub w1: Vec<[f32; FEATURES]>,
    pub b1: Vec<f32>,
    pub w2: Vec<f32>,
    pub b2: f32,
}

impl Confidence {
    /// The shipped weights.
    pub fn shipped() -> Confidence {
        Confidence { mean: MEAN, scale: SCALE, w1: W1.to_vec(), b1: B1.to_vec(), w2: W2.to_vec(), b2: B2 }
    }

    /// P(the best candidate is the word meant | what the gate sees).
    pub fn probability(&self, case: &Case) -> f64 {
        let f = case.features();
        let mut z = [0.0f32; FEATURES];
        for i in 0..FEATURES {
            let s = if self.scale[i] > 0.0 { self.scale[i] } else { 1.0 };
            z[i] = (f[i] - self.mean[i]) / s;
        }
        let mut logit = self.b2;
        for (h, row) in self.w1.iter().enumerate() {
            let mut a = self.b1[h];
            for i in 0..FEATURES {
                a += row[i] * z[i];
            }
            logit += self.w2[h] * a.tanh();
        }
        1.0 / (1.0 + (-(logit as f64)).exp())
    }
}

/// The probability a correction has to reach to be applied at the user's
/// confidence `c` (the slider, 1.5 eager to 10 strict, 4 by default):
/// `c / (c + 1)`, so the default asks for four to one, as the old margin
/// gate did on scores.
pub fn apply_threshold(confidence: f64) -> f64 {
    let c = confidence.max(1.0);
    c / (c + 1.0)
}

/// The probability a correction has to reach to be offered as a chip.
pub fn offer_threshold(confidence: f64) -> f64 {
    (apply_threshold(confidence) * 0.6).max(0.35)
}

// The fitted constants. `tools/wmlm/train_confidence.py --rust=<this file>`
// rewrites everything between the markers; until a fit lands, the gate is
// the lead alone, scaled so that four to one on scores is about 0.8.
// FIT BEGIN
// Fitted by tools/wmlm/train_confidence.py on ../../build/corpora/rows_step2000.csv (9598 rows, hidden 16, l2 0.0001).
pub const HIDDEN: usize = 16;
pub const MEAN: [f32; FEATURES] = [2.84003, 7.1113, 0.161343, 1.52344, 1.25505, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 5.28298, 0.586997, 0.313094, 2.7462, -3.91483, -0.430294, 0.498646, 0.0, 0.0];
pub const SCALE: [f32; FEATURES] = [2.41197, 1.40086, 0.373448, 0.903688, 1.13753, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.05527, 0.492373, 0.567166, 2.01985, 4.84328, 1.68208, 0.499998, 1.0, 1.0];
pub const W1: [[f32; FEATURES]; HIDDEN] = [
    [-0.439866, 1.3153, -0.390996, -0.190325, 0.467528, -8.07447e-71, -1.36553e-70, 1.57174e-71, -5.39248e-70, 2.62547e-70, 4.26354e-70, 1.58795, -0.611504, 0.467187, -0.528537, 0.343845, -0.690337, 0.28307, 6.09081e-70, 1.91008e-70],
    [-0.351461, -2.35374, 1.91023, 0.98915, -1.24165, -6.05633e-70, -2.70645e-70, -8.56329e-71, 8.10759e-70, -2.16997e-70, 6.32675e-71, 0.395148, -0.702138, 1.09328, 0.374036, -0.0143952, 0.623142, -0.0479617, 5.17912e-70, 2.46735e-20],
    [-0.401052, -1.14571, -0.976688, 0.373693, -0.553735, 2.09276e-70, 1.11314e-69, -3.78822e-71, -1.06665e-69, 2.61426e-69, -5.26247e-71, -2.24704, 0.209727, -0.752044, -0.255682, 0.0113208, -0.57392, -0.0495656, 9.97318e-70, -7.69542e-71],
    [1.39961, 0.833109, -0.0217178, 0.318517, 0.873701, 9.22277e-70, 2.72865e-70, 7.50535e-70, 3.58038e-72, -9.64984e-71, -3.20135e-71, -0.586349, 0.69866, 0.373994, 0.445479, 0.783834, 0.604543, 0.704697, 3.68465e-70, 5.88044e-70],
    [0.048736, 0.195225, -0.863753, -1.51818, 0.427687, -1.38318e-69, 2.60307e-70, -1.71655e-69, -7.17423e-72, 5.46494e-70, 4.04912e-70, 0.162334, 0.0397493, 0.111005, -1.0597, 0.220552, -0.299667, 0.00634571, -5.78732e-70, -8.42941e-71],
    [0.795745, 0.422914, 1.48981, -0.88233, -1.03511, -5.37543e-70, -8.00682e-70, 6.9047e-70, 1.2457e-69, -1.04049e-69, 1.29352e-65, -0.755609, -1.21784, -0.106963, -2.70925, 0.175119, 0.366092, 0.211673, 4.55935e-70, -2.9685e-70],
    [0.152676, -1.53048, -0.49345, 0.459537, -0.865802, 4.2486e-70, 1.44692e-70, -7.44354e-70, 1.44112e-19, -2.60878e-70, -1.75171e-71, 0.482214, 0.3273, 0.353464, 0.266334, 0.0918494, -0.0534847, 0.0389516, -9.64447e-70, 7.75562e-71],
    [-0.559625, 0.910763, 1.59242, 1.01733, -0.832764, 8.45943e-70, -1.14706e-69, 8.46348e-70, 5.76372e-70, -1.18606e-71, 1.25386e-69, 0.101708, 0.153749, -0.0416936, 0.617044, 0.817259, 0.247846, 0.77937, -4.72738e-70, 3.97383e-71],
    [0.0233023, -1.3732, -0.831808, 0.505997, 1.56825, -5.8698e-70, -7.12058e-70, 3.80145e-70, -7.43914e-70, -9.51429e-71, -2.89585e-70, -0.0782581, 1.89199, -0.0442585, -0.184226, -1.06341, -0.366931, -0.592568, -1.81561e-70, -8.93616e-70],
    [-1.9693, 0.170868, 1.28429, 0.525595, -1.14366, -8.05306e-70, -9.40093e-70, -8.364e-70, 9.68125e-70, -1.24298e-70, 8.94903e-70, 0.285627, -0.173136, -0.378822, -0.559032, 0.284752, -0.0819127, 0.115848, 2.59996e-70, -1.05794e-69],
    [-0.268131, -0.605746, 1.12929, -1.32069, 1.40442, -2.42946e-70, 9.0059e-71, 3.20135e-71, 2.23248e-69, -1.24121e-16, -4.1259e-70, -1.09438, -0.180271, -0.0546878, -0.511768, 0.0880723, 0.266285, 0.129122, 2.82473e-70, -6.25708e-70],
    [0.546619, -0.541345, 0.142414, -0.869237, -1.26199, 1.69682e-69, -4.02422e-70, -8.41229e-70, -2.44932e-70, 2.42471e-70, -2.82453e-55, 1.30197, 2.59605, 0.124343, -1.39691, 0.11903, 0.0444495, 0.0647167, 6.56596e-70, 1.12402e-70],
    [0.256106, 0.312326, 0.00277638, 0.460983, -0.300712, 3.16127e-70, -3.57791e-69, 9.83102e-71, 2.81767e-70, -1.28909e-70, 9.019e-70, -0.318361, 0.293728, 0.175409, -0.0879636, -0.038759, 0.0464833, -1.10907, -2.706e-70, 1.62998e-70],
    [-0.861082, 0.73029, -0.22288, 0.576806, -0.116701, 3.44894e-70, -3.09239e-70, 3.30532e-70, 3.87237e-70, 9.88895e-71, -2.71905e-70, -0.97825, -1.9235, -0.885324, -0.0201361, 0.0806662, -0.487524, 0.150916, 3.69291e-70, -5.65911e-71],
    [-0.963106, -0.567605, 0.000783948, -0.285555, -0.144358, -1.02882e-70, -7.94955e-71, 1.49596e-69, -3.4618e-71, -8.77847e-70, -4.3161e-70, -0.245398, -0.54144, -0.060739, -0.298094, 1.51628, 1.10473, 0.296536, -3.83033e-70, 6.02993e-70],
    [0.568564, -0.361114, 1.32303, 0.710511, -0.955441, 2.45488e-70, 1.0796e-70, 4.61907e-71, 3.50796e-70, -2.59848e-69, 1.16703e-73, -1.57695, 0.422851, 0.82056, -1.11247, 0.166704, 0.238009, 0.274118, -2.00046e-67, -5.26296e-70],
];
pub const B1: [f32; HIDDEN] = [2.44463, -1.34787, 0.115439, 0.835203, -1.23092, 0.299558, 1.04147, 1.72415, 0.536219, -0.186595, -0.399123, 1.73222, 0.506752, -0.589761, -0.149017, 0.239391];
pub const W2: [f32; HIDDEN] = [2.16329, -1.62851, -2.53127, 1.51131, 1.04828, -1.31401, -3.93793, -2.30924, 1.35225, -1.47731, 4.25444, -1.66411, -2.0397, -3.07783, 2.10467, -1.67561];
pub const B2: f32 = 2.31817;

// FIT END

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_wider_lead_is_surer() {
        let c = Confidence::shipped();
        let narrow = Case { margin: 0.5, ..Case::default() };
        let wide = Case { margin: 3.0, ..Case::default() };
        assert!(c.probability(&wide) > c.probability(&narrow));
    }

    #[test]
    fn the_default_slider_asks_four_to_one() {
        assert!((apply_threshold(4.0) - 0.8).abs() < 1e-9);
        assert!(offer_threshold(4.0) < apply_threshold(4.0));
    }
}

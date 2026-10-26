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
pub const FEATURES: usize = 25;

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
    /// ln P(typed spelling | context): what the context model makes of the
    /// word as it stands, `None` when it cannot read it.
    pub lm_typed: Option<f32>,
    /// How many words the context model ranks above the typed spelling here.
    pub lm_typed_rank: Option<u32>,
    /// The entropy of the model's next-word distribution, in nats.
    pub lm_entropy: Option<f32>,
    /// The model's second-best log-probability among the candidates.
    pub lm_second: Option<f32>,
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
        let lm_lead = match (self.lm_best, self.lm_second) {
            (Some(b), Some(s)) => b - s,
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
            self.lm_typed.unwrap_or(0.0).clamp(-20.0, 0.0),
            b(self.lm_typed.is_some()),
            self.lm_typed_rank.map_or(0.0, |r| (r as f32 + 1.0).ln()),
            self.lm_entropy.unwrap_or(0.0).clamp(0.0, 12.0),
            lm_lead.clamp(0.0, 20.0),
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
// Fitted by tools/wmlm/train_confidence.py on ../../build/corpora/rows_v2.csv (9598 rows, hidden 16, l2 0.003).
pub const HIDDEN: usize = 16;
pub const MEAN: [f32; FEATURES] = [2.84003, 7.1113, 0.161343, 1.52344, 1.25505, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 5.28298, 0.586997, 0.313094, 2.7462, -3.83936, -0.461536, 0.498646, 0.0, 0.0, -0.550728, 0.0542821, 0.363191, 2.37828, 1.51369];
pub const SCALE: [f32; FEATURES] = [2.41197, 1.40086, 0.373448, 0.903688, 1.13753, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.05527, 0.492373, 0.567166, 2.01985, 4.94463, 1.80389, 0.499998, 1.0, 1.0, 2.46047, 0.226574, 1.59641, 2.66682, 3.17593];
pub const W1: [[f32; FEATURES]; HIDDEN] = [
    [-0.163152, -0.260589, -0.117572, 0.293779, -0.592409, -1.44305e-70, -1.50246e-70, 2.97897e-06, -9.46843e-71, 2.68984e-70, -2.79431e-70, -0.122271, 0.0658232, -0.0500071, 0.0451894, -0.17061, -0.293966, -0.188817, 3.20789e-70, 6.86505e-71, -0.0735328, 0.107187, 0.0755097, -0.0374537, -0.191963],
    [0.0292903, -0.156966, -0.806766, -0.489282, 0.46709, 1.67085e-70, -1.92438e-70, -6.74883e-71, 9.5475e-70, -1.38783e-70, -1.09483e-70, -0.18674, -0.136907, -0.0195472, -0.0545092, -0.100995, 0.135353, -0.0559196, -6.06271e-70, 8.27735e-17, 0.0580464, -0.076974, -0.0591745, -0.0799182, -0.0798313],
    [-0.0188324, -0.0104523, -0.134334, 0.227514, -0.295538, 1.88926e-71, 1.19145e-69, 5.49346e-70, -8.98188e-70, -2.34108e-69, -4.47438e-70, -0.0871505, 0.0749914, -0.0197096, 0.00527829, -0.0715488, -0.162894, -0.0753913, 9.72609e-70, -1.15845e-71, -0.0555682, 0.0761387, 0.0579426, -0.0266833, -0.107875],
    [0.147576, 0.454182, 0.175978, 8.08777e-06, 0.855982, -1.02582e-69, 8.42096e-70, 3.4343e-70, -1.31703e-70, 1.40455e-70, -2.01344e-70, 0.0392714, 0.144787, 0.0208328, -0.100807, -0.125986, -0.0105504, 0.261808, -6.85875e-70, -4.70606e-70, 0.119933, -0.170182, -0.123982, 0.201255, 0.16171],
    [-0.0134045, -0.000713731, -0.137674, 0.213216, -0.274061, 9.24582e-70, 2.67417e-71, 1.40357e-69, -1.06233e-70, -6.16119e-70, -1.14069e-69, -0.0837069, 0.0765392, -0.0182288, 0.00398717, -0.0674845, -0.15436, -0.0722771, -1.24992e-71, -2.82829e-18, -0.0536056, 0.0731255, 0.0559364, -0.0277512, -0.102236],
    [-0.0107567, 0.00389409, -0.140085, 0.207167, -0.265059, 5.22836e-70, 7.34922e-70, -4.51583e-70, 1.27827e-69, 6.81524e-70, 1.9394e-22, -0.0829969, 0.0776691, -0.0172842, 0.00330556, -0.0662729, -0.150937, -0.0713774, -4.88357e-70, -8.64023e-71, -0.0527628, 0.0718324, 0.0550782, -0.0288154, -0.100095],
    [0.0765593, -0.0494136, 0.820906, 0.339867, -0.609233, -2.88485e-70, -4.51781e-71, -5.41547e-70, 3.07085e-12, -7.97815e-71, -9.27938e-34, -0.325645, -0.202201, -0.00259754, -0.0391358, 0.0372796, -0.128159, 0.0310898, -4.10505e-70, 1.6699e-70, -0.0563148, 0.0726447, 0.0586918, 0.0288867, 0.00301657],
    [0.0213946, 0.0135201, 0.131723, -0.22886, 0.299129, 1.17205e-69, 1.14425e-70, 1.01447e-69, 3.62987e-70, -5.89575e-15, -7.79675e-70, 0.0869572, -0.0735149, 0.0209308, -0.00612767, 0.0712341, 0.16354, 0.0759535, -4.80157e-70, 1.5511e-70, 0.0558076, -0.0766208, -0.0581803, 0.0255307, 0.108816],
    [0.0343261, 0.51448, 0.206995, -0.287331, 0.423684, 3.5202e-70, -2.3047e-70, -5.77829e-70, -4.37629e-70, 6.02596e-71, 3.00837e-70, -0.385287, 0.170744, -0.0345707, -0.041886, 0.321375, 0.307166, 0.0229469, 1.83889e-70, -1.1672e-70, 0.0381042, -0.0516643, -0.0400495, 0.136373, 0.0843074],
    [-0.141783, -0.285747, 0.67232, 0.471926, -0.349011, -1.14113e-69, -6.27465e-70, -6.82771e-70, -2.38813e-70, -2.58951e-70, 4.78421e-70, 0.00695879, -0.16161, -0.0210432, 0.0540508, 0.183842, -0.0240933, 0.144746, -2.52715e-70, 8.90536e-70, -0.0316249, 0.0428024, 0.0317208, 0.136836, 0.0912055],
    [-0.00490318, -0.00375977, -0.592655, -0.3374, 0.371723, -1.6304e-70, -3.79255e-70, -2.23033e-06, -2.73165e-69, -8.08095e-09, -9.99483e-70, 0.0844383, -0.00310824, 0.0027757, -0.0266728, -0.0311615, 0.0838415, -0.027731, 3.17551e-70, -9.44154e-70, 0.0412649, -0.0556129, -0.0421566, -0.0419908, -0.0348568],
    [0.010729, -0.0074235, 0.136065, -0.191273, 0.245373, -4.22268e-70, 1.50094e-70, 7.50172e-70, -7.35275e-70, -1.44632e-69, 1.55961e-23, 0.0761721, -0.0746577, 0.0185341, -0.00415614, 0.0591622, 0.140579, 0.0676682, -1.46339e-70, 1.42779e-70, 0.0502465, -0.0681956, -0.0524574, 0.0261868, 0.0940061],
    [0.00694287, 0.00228204, 0.615212, 0.35014, -0.383608, -5.675e-71, 3.1165e-69, -1.29701e-70, -6.13228e-71, 2.22077e-70, -7.04553e-71, -0.087581, 0.001172, -0.00385296, 0.0247864, 0.0315014, -0.0864731, 0.0283491, -1.15815e-69, -3.12873e-70, -0.0424591, 0.0570506, 0.0434222, 0.0431143, 0.0359392],
    [-0.018744, -0.00804839, -0.132248, 0.219932, -0.285315, -3.76254e-70, 1.582e-70, 2.28096e-70, -2.99623e-70, 3.98443e-71, -1.13478e-70, -0.0831954, 0.0741101, -0.0206713, 0.00547664, -0.0677447, -0.157893, -0.0735101, -4.27214e-70, -1.69682e-70, -0.054463, 0.0745515, 0.0567961, -0.025225, -0.10501],
    [0.00713668, 0.00213932, 0.616831, 0.350957, -0.384543, -5.03207e-71, -8.9043e-71, -1.33351e-69, -3.00514e-70, -1.07493e-69, 3.87655e-70, -0.0881266, 0.000850712, -0.00393983, 0.024554, 0.0314578, -0.0867297, 0.0283167, -2.9393e-70, -3.55836e-70, -0.042512, 0.057116, 0.0434798, 0.043133, 0.0359063],
    [-0.127097, -0.306979, 0.561494, -0.0571036, 0.133861, -4.58429e-70, 1.42401e-70, 1.03335e-70, -2.21621e-70, -1.3902e-69, 1.3527e-70, 0.157268, -0.258895, 0.0371573, 0.0388121, 0.172045, 0.135587, 0.124867, 4.76445e-68, 5.17834e-70, 0.056798, -0.0711474, -0.0605694, 0.120689, 0.091458],
];
pub const B1: [f32; HIDDEN] = [-0.196718, -0.151626, 0.0711245, 0.812062, 0.100052, 0.116092, 0.0757536, -0.05922, -0.651008, 0.642516, -0.265093, -0.118622, 0.262212, 0.0713874, 0.261781, -1.13604];
pub const W2: [f32; HIDDEN] = [-0.887786, 1.04116, -0.509569, 1.09552, -0.482499, -0.472107, -1.09699, 0.511259, 1.01772, -0.911885, 0.712896, 0.436617, -0.743348, -0.493153, -0.745245, 0.858648];
pub const B2: f32 = 1.58312;




// FIT END

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_wider_lead_is_surer() {
        let c = Confidence::shipped();
        let narrow = Case { margin: 0.5, ..fixed_case() };
        let wide = Case { margin: 4.0, ..fixed_case() };
        assert!(c.probability(&wide) > c.probability(&narrow));
    }

    /// The case `ConfidenceTest.kt` scores too; the two ports must agree.
    pub fn fixed_case() -> Case {
        Case {
            margin: 2.3,
            top_score: 7.9,
            top_edit_cost: 0.105,
            top_edits: 1,
            top_slips: 1,
            top_accents: 0,
            top_completed: 0,
            top_in_dictionary: true,
            top_in_lexicon: false,
            two_sources: false,
            mixes_slips: false,
            typed_len: 6,
            has_runner: true,
            runner_edit_cost: 1.6,
            candidates: 4,
            lm_top: Some(-2.0),
            lm_best: Some(-2.0),
            timing: 0.0,
            typed_shadowed: false,
            lm_typed: None,
            lm_typed_rank: None,
            lm_entropy: Some(4.2),
            lm_second: Some(-3.5),
        }
    }

    #[test]
    fn the_fixed_case_scores_what_the_kotlin_port_scores() {
        let p = Confidence::shipped().probability(&fixed_case());
        // Refit the gate and both this number and ConfidenceTest.kt's move.
        assert!((p - 0.933238).abs() < 1e-4, "{p}");
    }

    #[test]
    fn the_default_slider_asks_four_to_one() {
        assert!((apply_threshold(4.0) - 0.8).abs() < 1e-9);
        assert!(offer_threshold(4.0) < apply_threshold(4.0));
    }
}

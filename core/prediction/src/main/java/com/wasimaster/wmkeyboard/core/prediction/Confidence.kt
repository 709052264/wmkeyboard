package com.wasimaster.wmkeyboard.core.prediction

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/**
 * The learned autocorrect gate: how sure the engine is that the best
 * correction it found is the word that was meant.
 *
 * The margin gate was one rule, the best candidate's lead over the runner-up
 * against the slider, with surcharges and shapes bolted on around it. This
 * is a logistic model over everything that gate could see at once: the lead,
 * what the edit was (how far, how many, slips, accents, a completion), how
 * long the word is, whether the lists and the user's own words agree, what
 * the context model thinks of the candidate, and how the word was typed. It
 * answers one probability; [SuggestionEngine] applies above
 * [applyThreshold] and offers above [offerThreshold], and the confidence
 * slider moves both.
 *
 * The weights are `wm-engine`'s `confidence.rs`, number for number: fitted
 * offline by `tools/wmlm/train_confidence.py` on rows the typing harness
 * writes (real sentences typed by a scattered finger, and words held out of
 * the list typed right, which is what a false correction looks like), and
 * pasted into both files. Nothing here is tuned by hand.
 */
object Confidence {

    const val FEATURES = 20

    /** What the gate sees about one commit; see `confidence.rs`'s `Case`. */
    class Case(
        val margin: Double,
        val topScore: Double,
        val topEditCost: Double,
        val topEdits: Int,
        val topSlips: Int,
        val topAccents: Int,
        val topCompleted: Int,
        val topInDictionary: Boolean,
        val topInLexicon: Boolean,
        val twoSources: Boolean,
        val mixesSlips: Boolean,
        val typedLength: Int,
        val hasRunner: Boolean,
        val runnerEditCost: Double,
        val candidates: Int,
        /** ln P(top | context) from the context model, NaN without one. */
        val lmTop: Float,
        /** The context model's best log-probability among the candidates, NaN without one. */
        val lmBest: Float,
        /** ln of the typing-rhythm multiplier (0 at rest). */
        val timing: Double,
        val typedShadowed: Boolean,
    ) {
        fun features(): FloatArray {
            fun b(v: Boolean) = if (v) 1f else 0f
            val lmTopValue = if (lmTop.isNaN()) 0f else lmTop
            val lmGap = if (lmTop.isNaN() || lmBest.isNaN()) 0f else lmTop - lmBest
            return floatArrayOf(
                margin.coerceIn(-5.0, 15.0).toFloat(),
                topScore.coerceIn(-10.0, 20.0).toFloat(),
                topEditCost.toFloat(),
                topEdits.toFloat(),
                topSlips.toFloat(),
                topAccents.toFloat(),
                topCompleted.toFloat(),
                b(topInDictionary),
                b(topInLexicon),
                b(twoSources),
                b(mixesSlips),
                typedLength.coerceAtMost(20).toFloat(),
                b(hasRunner),
                runnerEditCost.toFloat(),
                candidates.coerceAtMost(8).toFloat(),
                lmTopValue.coerceIn(-20f, 0f),
                lmGap.coerceIn(-20f, 0f),
                b(!lmTop.isNaN()),
                timing.toFloat(),
                b(typedShadowed),
            )
        }
    }

    /** P(the best candidate is the word meant | [case]). */
    fun probability(case: Case): Double {
        val f = case.features()
        val z = FloatArray(FEATURES) { i ->
            val s = if (SCALE[i] > 0f) SCALE[i] else 1f
            (f[i] - MEAN[i]) / s
        }
        var logit = B2
        for (h in 0 until HIDDEN) {
            var a = B1[h]
            val row = W1[h]
            for (i in 0 until FEATURES) a += row[i] * z[i]
            logit += W2[h] * tanh(a)
        }
        return 1.0 / (1.0 + exp(-logit.toDouble()))
    }

    /**
     * The probability a correction has to reach to be applied at the user's
     * confidence [confidence] (the slider, 1.5 eager to 10 strict, 4 by
     * default): `c / (c + 1)`, so the default asks for four to one, as the
     * margin gate did on scores.
     */
    fun applyThreshold(confidence: Double): Double {
        val c = confidence.coerceAtLeast(1.0)
        return c / (c + 1.0)
    }

    /** The probability a correction has to reach to be offered as a chip. */
    fun offerThreshold(confidence: Double): Double = (applyThreshold(confidence) * 0.6).coerceAtLeast(0.35)

    /** ln of the timing multiplier, as the gate takes it. */
    fun timingFeature(multiplier: Double): Double = ln(multiplier.coerceAtLeast(1e-6))

    // The fitted constants, identical to confidence.rs: `tools/wmlm/train_confidence.py
    // --kotlin=<this file>` rewrites everything between the markers.
    // FIT BEGIN
    // Fitted by tools/wmlm/train_confidence.py on ../../build/corpora/rows_step2000.csv (9598 rows, hidden 16, l2 0.0001).
    const val HIDDEN = 16
    val MEAN = floatArrayOf(2.84003f, 7.1113f, 0.161343f, 1.52344f, 1.25505f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 5.28298f, 0.586997f, 0.313094f, 2.7462f, -3.91483f, -0.430294f, 0.498646f, 0.0f, 0.0f)
    val SCALE = floatArrayOf(2.41197f, 1.40086f, 0.373448f, 0.903688f, 1.13753f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 2.05527f, 0.492373f, 0.567166f, 2.01985f, 4.84328f, 1.68208f, 0.499998f, 1.0f, 1.0f)
    val W1 = arrayOf(
        floatArrayOf(-0.439866f, 1.3153f, -0.390996f, -0.190325f, 0.467528f, -8.07447e-71f, -1.36553e-70f, 1.57174e-71f, -5.39248e-70f, 2.62547e-70f, 4.26354e-70f, 1.58795f, -0.611504f, 0.467187f, -0.528537f, 0.343845f, -0.690337f, 0.28307f, 6.09081e-70f, 1.91008e-70f),
        floatArrayOf(-0.351461f, -2.35374f, 1.91023f, 0.98915f, -1.24165f, -6.05633e-70f, -2.70645e-70f, -8.56329e-71f, 8.10759e-70f, -2.16997e-70f, 6.32675e-71f, 0.395148f, -0.702138f, 1.09328f, 0.374036f, -0.0143952f, 0.623142f, -0.0479617f, 5.17912e-70f, 2.46735e-20f),
        floatArrayOf(-0.401052f, -1.14571f, -0.976688f, 0.373693f, -0.553735f, 2.09276e-70f, 1.11314e-69f, -3.78822e-71f, -1.06665e-69f, 2.61426e-69f, -5.26247e-71f, -2.24704f, 0.209727f, -0.752044f, -0.255682f, 0.0113208f, -0.57392f, -0.0495656f, 9.97318e-70f, -7.69542e-71f),
        floatArrayOf(1.39961f, 0.833109f, -0.0217178f, 0.318517f, 0.873701f, 9.22277e-70f, 2.72865e-70f, 7.50535e-70f, 3.58038e-72f, -9.64984e-71f, -3.20135e-71f, -0.586349f, 0.69866f, 0.373994f, 0.445479f, 0.783834f, 0.604543f, 0.704697f, 3.68465e-70f, 5.88044e-70f),
        floatArrayOf(0.048736f, 0.195225f, -0.863753f, -1.51818f, 0.427687f, -1.38318e-69f, 2.60307e-70f, -1.71655e-69f, -7.17423e-72f, 5.46494e-70f, 4.04912e-70f, 0.162334f, 0.0397493f, 0.111005f, -1.0597f, 0.220552f, -0.299667f, 0.00634571f, -5.78732e-70f, -8.42941e-71f),
        floatArrayOf(0.795745f, 0.422914f, 1.48981f, -0.88233f, -1.03511f, -5.37543e-70f, -8.00682e-70f, 6.9047e-70f, 1.2457e-69f, -1.04049e-69f, 1.29352e-65f, -0.755609f, -1.21784f, -0.106963f, -2.70925f, 0.175119f, 0.366092f, 0.211673f, 4.55935e-70f, -2.9685e-70f),
        floatArrayOf(0.152676f, -1.53048f, -0.49345f, 0.459537f, -0.865802f, 4.2486e-70f, 1.44692e-70f, -7.44354e-70f, 1.44112e-19f, -2.60878e-70f, -1.75171e-71f, 0.482214f, 0.3273f, 0.353464f, 0.266334f, 0.0918494f, -0.0534847f, 0.0389516f, -9.64447e-70f, 7.75562e-71f),
        floatArrayOf(-0.559625f, 0.910763f, 1.59242f, 1.01733f, -0.832764f, 8.45943e-70f, -1.14706e-69f, 8.46348e-70f, 5.76372e-70f, -1.18606e-71f, 1.25386e-69f, 0.101708f, 0.153749f, -0.0416936f, 0.617044f, 0.817259f, 0.247846f, 0.77937f, -4.72738e-70f, 3.97383e-71f),
        floatArrayOf(0.0233023f, -1.3732f, -0.831808f, 0.505997f, 1.56825f, -5.8698e-70f, -7.12058e-70f, 3.80145e-70f, -7.43914e-70f, -9.51429e-71f, -2.89585e-70f, -0.0782581f, 1.89199f, -0.0442585f, -0.184226f, -1.06341f, -0.366931f, -0.592568f, -1.81561e-70f, -8.93616e-70f),
        floatArrayOf(-1.9693f, 0.170868f, 1.28429f, 0.525595f, -1.14366f, -8.05306e-70f, -9.40093e-70f, -8.364e-70f, 9.68125e-70f, -1.24298e-70f, 8.94903e-70f, 0.285627f, -0.173136f, -0.378822f, -0.559032f, 0.284752f, -0.0819127f, 0.115848f, 2.59996e-70f, -1.05794e-69f),
        floatArrayOf(-0.268131f, -0.605746f, 1.12929f, -1.32069f, 1.40442f, -2.42946e-70f, 9.0059e-71f, 3.20135e-71f, 2.23248e-69f, -1.24121e-16f, -4.1259e-70f, -1.09438f, -0.180271f, -0.0546878f, -0.511768f, 0.0880723f, 0.266285f, 0.129122f, 2.82473e-70f, -6.25708e-70f),
        floatArrayOf(0.546619f, -0.541345f, 0.142414f, -0.869237f, -1.26199f, 1.69682e-69f, -4.02422e-70f, -8.41229e-70f, -2.44932e-70f, 2.42471e-70f, -2.82453e-55f, 1.30197f, 2.59605f, 0.124343f, -1.39691f, 0.11903f, 0.0444495f, 0.0647167f, 6.56596e-70f, 1.12402e-70f),
        floatArrayOf(0.256106f, 0.312326f, 0.00277638f, 0.460983f, -0.300712f, 3.16127e-70f, -3.57791e-69f, 9.83102e-71f, 2.81767e-70f, -1.28909e-70f, 9.019e-70f, -0.318361f, 0.293728f, 0.175409f, -0.0879636f, -0.038759f, 0.0464833f, -1.10907f, -2.706e-70f, 1.62998e-70f),
        floatArrayOf(-0.861082f, 0.73029f, -0.22288f, 0.576806f, -0.116701f, 3.44894e-70f, -3.09239e-70f, 3.30532e-70f, 3.87237e-70f, 9.88895e-71f, -2.71905e-70f, -0.97825f, -1.9235f, -0.885324f, -0.0201361f, 0.0806662f, -0.487524f, 0.150916f, 3.69291e-70f, -5.65911e-71f),
        floatArrayOf(-0.963106f, -0.567605f, 0.000783948f, -0.285555f, -0.144358f, -1.02882e-70f, -7.94955e-71f, 1.49596e-69f, -3.4618e-71f, -8.77847e-70f, -4.3161e-70f, -0.245398f, -0.54144f, -0.060739f, -0.298094f, 1.51628f, 1.10473f, 0.296536f, -3.83033e-70f, 6.02993e-70f),
        floatArrayOf(0.568564f, -0.361114f, 1.32303f, 0.710511f, -0.955441f, 2.45488e-70f, 1.0796e-70f, 4.61907e-71f, 3.50796e-70f, -2.59848e-69f, 1.16703e-73f, -1.57695f, 0.422851f, 0.82056f, -1.11247f, 0.166704f, 0.238009f, 0.274118f, -2.00046e-67f, -5.26296e-70f),
    )
    val B1 = floatArrayOf(2.44463f, -1.34787f, 0.115439f, 0.835203f, -1.23092f, 0.299558f, 1.04147f, 1.72415f, 0.536219f, -0.186595f, -0.399123f, 1.73222f, 0.506752f, -0.589761f, -0.149017f, 0.239391f)
    val W2 = floatArrayOf(2.16329f, -1.62851f, -2.53127f, 1.51131f, 1.04828f, -1.31401f, -3.93793f, -2.30924f, 1.35225f, -1.47731f, 4.25444f, -1.66411f, -2.0397f, -3.07783f, 2.10467f, -1.67561f)
    const val B2 = 2.31817f

    // FIT END
}

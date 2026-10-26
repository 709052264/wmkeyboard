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

    const val FEATURES = 25

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
        /** ln P(typed spelling | context), NaN when the model cannot read it. */
        val lmTyped: Float = Float.NaN,
        /** How many words the model ranks above the typed spelling here, -1 without one. */
        val lmTypedRank: Int = -1,
        /** The entropy of the model's next-word distribution in nats, NaN without one. */
        val lmEntropy: Float = Float.NaN,
        /** The model's second-best log-probability among the candidates, NaN without one. */
        val lmSecond: Float = Float.NaN,
    ) {
        fun features(): FloatArray {
            fun b(v: Boolean) = if (v) 1f else 0f
            val lmTopValue = if (lmTop.isNaN()) 0f else lmTop
            val lmGap = if (lmTop.isNaN() || lmBest.isNaN()) 0f else lmTop - lmBest
            val lmLead = if (lmBest.isNaN() || lmSecond.isNaN()) 0f else lmBest - lmSecond
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
                (if (lmTyped.isNaN()) 0f else lmTyped).coerceIn(-20f, 0f),
                b(!lmTyped.isNaN()),
                if (lmTypedRank < 0) 0f else ln(lmTypedRank + 1f),
                (if (lmEntropy.isNaN()) 0f else lmEntropy).coerceIn(0f, 12f),
                lmLead.coerceIn(0f, 20f),
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
    // Fitted by tools/wmlm/train_confidence.py on ../../build/corpora/rows_v2.csv (9598 rows, hidden 16, l2 0.003).
    const val HIDDEN = 16
    val MEAN = floatArrayOf(2.84003f, 7.1113f, 0.161343f, 1.52344f, 1.25505f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 5.28298f, 0.586997f, 0.313094f, 2.7462f, -3.83936f, -0.461536f, 0.498646f, 0.0f, 0.0f, -0.550728f, 0.0542821f, 0.363191f, 2.37828f, 1.51369f)
    val SCALE = floatArrayOf(2.41197f, 1.40086f, 0.373448f, 0.903688f, 1.13753f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 2.05527f, 0.492373f, 0.567166f, 2.01985f, 4.94463f, 1.80389f, 0.499998f, 1.0f, 1.0f, 2.46047f, 0.226574f, 1.59641f, 2.66682f, 3.17593f)
    val W1 = arrayOf(
        floatArrayOf(-0.163152f, -0.260589f, -0.117572f, 0.293779f, -0.592409f, -1.44305e-70f, -1.50246e-70f, 2.97897e-06f, -9.46843e-71f, 2.68984e-70f, -2.79431e-70f, -0.122271f, 0.0658232f, -0.0500071f, 0.0451894f, -0.17061f, -0.293966f, -0.188817f, 3.20789e-70f, 6.86505e-71f, -0.0735328f, 0.107187f, 0.0755097f, -0.0374537f, -0.191963f),
        floatArrayOf(0.0292903f, -0.156966f, -0.806766f, -0.489282f, 0.46709f, 1.67085e-70f, -1.92438e-70f, -6.74883e-71f, 9.5475e-70f, -1.38783e-70f, -1.09483e-70f, -0.18674f, -0.136907f, -0.0195472f, -0.0545092f, -0.100995f, 0.135353f, -0.0559196f, -6.06271e-70f, 8.27735e-17f, 0.0580464f, -0.076974f, -0.0591745f, -0.0799182f, -0.0798313f),
        floatArrayOf(-0.0188324f, -0.0104523f, -0.134334f, 0.227514f, -0.295538f, 1.88926e-71f, 1.19145e-69f, 5.49346e-70f, -8.98188e-70f, -2.34108e-69f, -4.47438e-70f, -0.0871505f, 0.0749914f, -0.0197096f, 0.00527829f, -0.0715488f, -0.162894f, -0.0753913f, 9.72609e-70f, -1.15845e-71f, -0.0555682f, 0.0761387f, 0.0579426f, -0.0266833f, -0.107875f),
        floatArrayOf(0.147576f, 0.454182f, 0.175978f, 8.08777e-06f, 0.855982f, -1.02582e-69f, 8.42096e-70f, 3.4343e-70f, -1.31703e-70f, 1.40455e-70f, -2.01344e-70f, 0.0392714f, 0.144787f, 0.0208328f, -0.100807f, -0.125986f, -0.0105504f, 0.261808f, -6.85875e-70f, -4.70606e-70f, 0.119933f, -0.170182f, -0.123982f, 0.201255f, 0.16171f),
        floatArrayOf(-0.0134045f, -0.000713731f, -0.137674f, 0.213216f, -0.274061f, 9.24582e-70f, 2.67417e-71f, 1.40357e-69f, -1.06233e-70f, -6.16119e-70f, -1.14069e-69f, -0.0837069f, 0.0765392f, -0.0182288f, 0.00398717f, -0.0674845f, -0.15436f, -0.0722771f, -1.24992e-71f, -2.82829e-18f, -0.0536056f, 0.0731255f, 0.0559364f, -0.0277512f, -0.102236f),
        floatArrayOf(-0.0107567f, 0.00389409f, -0.140085f, 0.207167f, -0.265059f, 5.22836e-70f, 7.34922e-70f, -4.51583e-70f, 1.27827e-69f, 6.81524e-70f, 1.9394e-22f, -0.0829969f, 0.0776691f, -0.0172842f, 0.00330556f, -0.0662729f, -0.150937f, -0.0713774f, -4.88357e-70f, -8.64023e-71f, -0.0527628f, 0.0718324f, 0.0550782f, -0.0288154f, -0.100095f),
        floatArrayOf(0.0765593f, -0.0494136f, 0.820906f, 0.339867f, -0.609233f, -2.88485e-70f, -4.51781e-71f, -5.41547e-70f, 3.07085e-12f, -7.97815e-71f, -9.27938e-34f, -0.325645f, -0.202201f, -0.00259754f, -0.0391358f, 0.0372796f, -0.128159f, 0.0310898f, -4.10505e-70f, 1.6699e-70f, -0.0563148f, 0.0726447f, 0.0586918f, 0.0288867f, 0.00301657f),
        floatArrayOf(0.0213946f, 0.0135201f, 0.131723f, -0.22886f, 0.299129f, 1.17205e-69f, 1.14425e-70f, 1.01447e-69f, 3.62987e-70f, -5.89575e-15f, -7.79675e-70f, 0.0869572f, -0.0735149f, 0.0209308f, -0.00612767f, 0.0712341f, 0.16354f, 0.0759535f, -4.80157e-70f, 1.5511e-70f, 0.0558076f, -0.0766208f, -0.0581803f, 0.0255307f, 0.108816f),
        floatArrayOf(0.0343261f, 0.51448f, 0.206995f, -0.287331f, 0.423684f, 3.5202e-70f, -2.3047e-70f, -5.77829e-70f, -4.37629e-70f, 6.02596e-71f, 3.00837e-70f, -0.385287f, 0.170744f, -0.0345707f, -0.041886f, 0.321375f, 0.307166f, 0.0229469f, 1.83889e-70f, -1.1672e-70f, 0.0381042f, -0.0516643f, -0.0400495f, 0.136373f, 0.0843074f),
        floatArrayOf(-0.141783f, -0.285747f, 0.67232f, 0.471926f, -0.349011f, -1.14113e-69f, -6.27465e-70f, -6.82771e-70f, -2.38813e-70f, -2.58951e-70f, 4.78421e-70f, 0.00695879f, -0.16161f, -0.0210432f, 0.0540508f, 0.183842f, -0.0240933f, 0.144746f, -2.52715e-70f, 8.90536e-70f, -0.0316249f, 0.0428024f, 0.0317208f, 0.136836f, 0.0912055f),
        floatArrayOf(-0.00490318f, -0.00375977f, -0.592655f, -0.3374f, 0.371723f, -1.6304e-70f, -3.79255e-70f, -2.23033e-06f, -2.73165e-69f, -8.08095e-09f, -9.99483e-70f, 0.0844383f, -0.00310824f, 0.0027757f, -0.0266728f, -0.0311615f, 0.0838415f, -0.027731f, 3.17551e-70f, -9.44154e-70f, 0.0412649f, -0.0556129f, -0.0421566f, -0.0419908f, -0.0348568f),
        floatArrayOf(0.010729f, -0.0074235f, 0.136065f, -0.191273f, 0.245373f, -4.22268e-70f, 1.50094e-70f, 7.50172e-70f, -7.35275e-70f, -1.44632e-69f, 1.55961e-23f, 0.0761721f, -0.0746577f, 0.0185341f, -0.00415614f, 0.0591622f, 0.140579f, 0.0676682f, -1.46339e-70f, 1.42779e-70f, 0.0502465f, -0.0681956f, -0.0524574f, 0.0261868f, 0.0940061f),
        floatArrayOf(0.00694287f, 0.00228204f, 0.615212f, 0.35014f, -0.383608f, -5.675e-71f, 3.1165e-69f, -1.29701e-70f, -6.13228e-71f, 2.22077e-70f, -7.04553e-71f, -0.087581f, 0.001172f, -0.00385296f, 0.0247864f, 0.0315014f, -0.0864731f, 0.0283491f, -1.15815e-69f, -3.12873e-70f, -0.0424591f, 0.0570506f, 0.0434222f, 0.0431143f, 0.0359392f),
        floatArrayOf(-0.018744f, -0.00804839f, -0.132248f, 0.219932f, -0.285315f, -3.76254e-70f, 1.582e-70f, 2.28096e-70f, -2.99623e-70f, 3.98443e-71f, -1.13478e-70f, -0.0831954f, 0.0741101f, -0.0206713f, 0.00547664f, -0.0677447f, -0.157893f, -0.0735101f, -4.27214e-70f, -1.69682e-70f, -0.054463f, 0.0745515f, 0.0567961f, -0.025225f, -0.10501f),
        floatArrayOf(0.00713668f, 0.00213932f, 0.616831f, 0.350957f, -0.384543f, -5.03207e-71f, -8.9043e-71f, -1.33351e-69f, -3.00514e-70f, -1.07493e-69f, 3.87655e-70f, -0.0881266f, 0.000850712f, -0.00393983f, 0.024554f, 0.0314578f, -0.0867297f, 0.0283167f, -2.9393e-70f, -3.55836e-70f, -0.042512f, 0.057116f, 0.0434798f, 0.043133f, 0.0359063f),
        floatArrayOf(-0.127097f, -0.306979f, 0.561494f, -0.0571036f, 0.133861f, -4.58429e-70f, 1.42401e-70f, 1.03335e-70f, -2.21621e-70f, -1.3902e-69f, 1.3527e-70f, 0.157268f, -0.258895f, 0.0371573f, 0.0388121f, 0.172045f, 0.135587f, 0.124867f, 4.76445e-68f, 5.17834e-70f, 0.056798f, -0.0711474f, -0.0605694f, 0.120689f, 0.091458f),
    )
    val B1 = floatArrayOf(-0.196718f, -0.151626f, 0.0711245f, 0.812062f, 0.100052f, 0.116092f, 0.0757536f, -0.05922f, -0.651008f, 0.642516f, -0.265093f, -0.118622f, 0.262212f, 0.0713874f, 0.261781f, -1.13604f)
    val W2 = floatArrayOf(-0.887786f, 1.04116f, -0.509569f, 1.09552f, -0.482499f, -0.472107f, -1.09699f, 0.511259f, 1.01772f, -0.911885f, 0.712896f, 0.436617f, -0.743348f, -0.493153f, -0.745245f, 0.858648f)
    const val B2 = 1.58312f




    // FIT END
}

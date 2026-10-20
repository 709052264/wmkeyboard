package com.wasimaster.wmkeyboard.core.imagegen

import java.util.SplittableRandom
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The denoising maths of the sticker generator, written to match diffusers'
 * schedulers number for number (SamplersTest checks them against values
 * diffusers produced). Pure Kotlin: the runtimes only ever see one network
 * call at a time, so cancelling between calls is instant and the same loop
 * drives the CPU and the NPU.
 */
class NoiseSchedule(betaStart: Double, betaEnd: Double, schedule: String, val trainSteps: Int) {

    /** ᾱ_t for t in 0 until [trainSteps]. */
    val alphasCumprod: DoubleArray = run {
        val betas = DoubleArray(trainSteps) { i ->
            when (schedule) {
                "linear" -> betaStart + (betaEnd - betaStart) * i / (trainSteps - 1)
                // "scaled_linear", Stable Diffusion's: linear in √β.
                else -> {
                    val a = sqrt(betaStart)
                    val b = sqrt(betaEnd)
                    val v = a + (b - a) * i / (trainSteps - 1)
                    v * v
                }
            }
        }
        var prod = 1.0
        DoubleArray(trainSteps) { i ->
            prod *= 1.0 - betas[i]
            prod
        }
    }

    /** σ_t = √((1 − ᾱ_t) / ᾱ_t), the noise level in the variance-exploding view. */
    fun sigma(t: Int): Double = sqrt((1 - alphasCumprod[t]) / alphasCumprod[t])

    /**
     * [steps] training timesteps, high to low. "trailing" ends on the last
     * one (what few-step models are distilled for), "leading" starts at 0 and
     * adds [offset] (Stable Diffusion 1.5's own configuration).
     */
    fun timesteps(steps: Int, spacing: String, offset: Int = 1): IntArray = when (spacing) {
        "leading" -> {
            val ratio = trainSteps / steps
            IntArray(steps) { i -> (steps - 1 - i) * ratio + offset }
        }
        else -> {
            val ratio = trainSteps.toDouble() / steps
            IntArray(steps) { i -> (trainSteps - i * ratio).roundToInt() - 1 }
        }
    }
}

/** One denoising run: what to feed the network at each step, and what to make of its answer. */
interface Sampler {
    /** The training timestep of each step, high to low. */
    val timesteps: IntArray

    /** The starting latent from unit Gaussian [noise]. */
    fun start(noise: FloatArray): FloatArray

    /** What the network sees at [step]: [x] scaled as the scheduler wants it. */
    fun modelInput(x: FloatArray, step: Int): FloatArray

    /** The latent after [step], from the network's [output]; [freshNoise] when the scheduler adds noise back. */
    fun step(x: FloatArray, output: FloatArray, step: Int, freshNoise: () -> FloatArray): FloatArray

    /** The clean-image estimate the last [step] made, for a preview. */
    val denoised: FloatArray?
}

/**
 * Diffusers' `EulerDiscreteScheduler` (no churn), for ε- or v-predicting
 * models. With one step at t = 999 it is exactly the one-step sampling
 * SDXS and SD-Turbo are distilled for.
 */
class EulerSampler(
    private val schedule: NoiseSchedule,
    override val timesteps: IntArray,
    private val spacing: String,
    private val vPrediction: Boolean,
) : Sampler {

    private val sigmas = DoubleArray(timesteps.size + 1) { i -> if (i < timesteps.size) schedule.sigma(timesteps[i]) else 0.0 }

    override var denoised: FloatArray? = null
        private set

    override fun start(noise: FloatArray): FloatArray {
        // diffusers: the largest σ for "linspace" and "trailing", √(σ² + 1) otherwise.
        val s = sigmas[0]
        val init = if (spacing == "trailing" || spacing == "linspace") s else sqrt(s * s + 1)
        return FloatArray(noise.size) { (noise[it] * init).toFloat() }
    }

    override fun modelInput(x: FloatArray, step: Int): FloatArray {
        val s = sigmas[step]
        val c = (1.0 / sqrt(s * s + 1)).toFloat()
        return FloatArray(x.size) { x[it] * c }
    }

    override fun step(x: FloatArray, output: FloatArray, step: Int, freshNoise: () -> FloatArray): FloatArray {
        val s = sigmas[step]
        val next = sigmas[step + 1]
        val d = FloatArray(x.size)
        if (vPrediction) {
            val cOut = -s / sqrt(s * s + 1)
            val cSkip = 1 / (s * s + 1)
            for (i in x.indices) d[i] = (output[i] * cOut + x[i] * cSkip).toFloat()
        } else {
            for (i in x.indices) d[i] = (x[i] - s * output[i]).toFloat()
        }
        denoised = d
        val ratio = next / s
        return FloatArray(x.size) { i -> (d[i] + ratio * (x[i] - d[i])).toFloat() }
    }
}

/**
 * Diffusers' `LCMScheduler`, for latent consistency models (an imported
 * LCM checkpoint): a few steps, each predicting the clean image outright and
 * noising it back to the next timestep. Guidance is embedded in the model
 * ([guidanceEmbedding]), so there is no second, negative pass.
 */
class LcmSampler(
    private val schedule: NoiseSchedule,
    steps: Int,
    private val vPrediction: Boolean,
    originalSteps: Int = 50,
) : Sampler {

    override val timesteps: IntArray = run {
        val k = schedule.trainSteps / originalSteps
        val origin = IntArray(originalSteps) { (it + 1) * k - 1 }.reversedArray()
        val n = steps.coerceIn(1, originalSteps)
        IntArray(n) { i -> origin[floor(i * originalSteps.toDouble() / n).toInt()] }
    }

    override var denoised: FloatArray? = null
        private set

    override fun start(noise: FloatArray): FloatArray = noise.copyOf()

    override fun modelInput(x: FloatArray, step: Int): FloatArray = x

    override fun step(x: FloatArray, output: FloatArray, step: Int, freshNoise: () -> FloatArray): FloatArray {
        val t = timesteps[step]
        val alpha = schedule.alphasCumprod[t]
        val beta = 1 - alpha
        val scaled = t * TIMESTEP_SCALING
        val cSkip = SIGMA_DATA * SIGMA_DATA / (scaled * scaled + SIGMA_DATA * SIGMA_DATA)
        val cOut = scaled / sqrt(scaled * scaled + SIGMA_DATA * SIGMA_DATA)
        val sa = sqrt(alpha)
        val sb = sqrt(beta)
        val d = FloatArray(x.size) { i ->
            val x0 = if (vPrediction) sa * x[i] - sb * output[i] else (x[i] - sb * output[i]) / sa
            (cOut * x0 + cSkip * x[i]).toFloat()
        }
        denoised = d
        if (step == timesteps.lastIndex) return d
        val prev = schedule.alphasCumprod[timesteps[step + 1]]
        val noise = freshNoise()
        val a = sqrt(prev)
        val b = sqrt(1 - prev)
        return FloatArray(x.size) { i -> (a * d[i] + b * noise[i]).toFloat() }
    }

    companion object {
        private const val TIMESTEP_SCALING = 10.0
        private const val SIGMA_DATA = 0.5

        /** The guidance scale as an LCM UNet takes it: diffusers' `get_guidance_scale_embedding(w - 1)`. */
        fun guidanceEmbedding(guidance: Float, dim: Int): FloatArray {
            val w = (guidance - 1.0) * 1000.0
            val half = dim / 2
            val step = ln(10_000.0) / (half - 1)
            val out = FloatArray(dim)
            for (i in 0 until half) {
                val v = w * exp(-step * i)
                out[i] = sin(v).toFloat()
                out[half + i] = cos(v).toFloat()
            }
            return out
        }
    }
}

/**
 * Unit Gaussian noise from a seed, the same numbers on every phone and every
 * run: SplittableRandom's doubles through Box-Muller. Not torch's stream, so a
 * seed here does not give the picture the same seed gives in diffusers.
 */
class GaussianNoise(seed: Long) {
    private val random = SplittableRandom(seed)
    private var spare = Double.NaN

    fun next(): Double {
        if (!spare.isNaN()) return spare.also { spare = Double.NaN }
        var u: Double
        do u = random.nextDouble() while (u <= 0.0)
        val v = random.nextDouble()
        val r = sqrt(-2.0 * ln(u))
        spare = r * sin(2 * Math.PI * v)
        return r * cos(2 * Math.PI * v)
    }

    fun floats(n: Int): FloatArray = FloatArray(n) { next().toFloat() }
}

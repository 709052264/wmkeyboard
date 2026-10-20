package com.wasimaster.wmkeyboard.core.imagegen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every sampler against diffusers 0.41 driven by the same fake network (fixtures in sampler-fixtures.tsv). */
class SamplersTest {

    private val schedule = NoiseSchedule(0.00085, 0.012, "scaled_linear", 1000)
    private val noise = floatArrayOf(0.5f, -1.2f, 0.3f, 2.0f, -0.7f, 0.0f, 1.1f, -0.4f)

    private val fixtures: Map<String, Pair<IntArray, FloatArray>> by lazy {
        val stream = javaClass.getResourceAsStream("/imagegen/sampler-fixtures.tsv") ?: error("fixtures missing")
        stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val (name, ts, xs) = line.split('\t')
            val steps = ts.split(',').filter(String::isNotEmpty).map(String::toInt).toIntArray()
            name to (steps to xs.split(',').map(String::toFloat).toFloatArray())
        }
    }

    private fun fake(x: FloatArray, t: Int) = FloatArray(x.size) { 0.3f * x[it] + 0.01f * t / 1000f }

    private fun run(sampler: Sampler, fresh: (Int) -> FloatArray = { FloatArray(noise.size) }): FloatArray {
        var x = sampler.start(noise)
        for (i in sampler.timesteps.indices) {
            val out = fake(sampler.modelInput(x, i), sampler.timesteps[i])
            x = sampler.step(x, out, i) { fresh(i) }
        }
        return x
    }

    private fun check(name: String, sampler: Sampler, fresh: (Int) -> FloatArray = { FloatArray(noise.size) }) {
        val (ts, expected) = fixtures.getValue(name)
        assertArrayEquals(name, ts, sampler.timesteps)
        val got = run(sampler, fresh)
        for (i in expected.indices) assertEquals("$name[$i]", expected[i], got[i], 1e-4f * maxOf(1f, kotlin.math.abs(expected[i])))
    }

    @Test
    fun eulerOneStepTrailing() =
        check("euler_trailing_1", EulerSampler(schedule, intArrayOf(999), "trailing", vPrediction = false))

    @Test
    fun eulerTwentyStepsLeading() =
        check("euler_leading_20", EulerSampler(schedule, schedule.timesteps(20, "leading"), "leading", vPrediction = false))

    @Test
    fun eulerVPredictionTrailing() =
        check("euler_trailing_4_v", EulerSampler(schedule, schedule.timesteps(4, "trailing"), "trailing", vPrediction = true))

    @Test
    fun lcmFourSteps() =
        check("lcm_4", LcmSampler(schedule, 4, vPrediction = false)) { k -> FloatArray(noise.size) { 0.1f * (k + 1) } }

    @Test
    fun lcmGuidanceEmbedding() {
        val (_, expected) = fixtures.getValue("lcm_w_embedding_8_256")
        val e = LcmSampler.guidanceEmbedding(8f, 256)
        val got = intArrayOf(0, 1, 2, 64, 127, 128, 129, 255).map { e[it] }
        for (i in expected.indices) assertEquals(expected[i], got[i], 2e-3f)
    }

    @Test
    fun noiseIsReproducibleAndUnit() {
        val a = GaussianNoise(42).floats(20_000)
        assertArrayEquals(a, GaussianNoise(42).floats(20_000), 0f)
        val mean = a.average()
        val variance = a.sumOf { (it - mean) * (it - mean) } / a.size
        assertEquals(0.0, mean, 0.03)
        assertEquals(1.0, variance, 0.03)
    }
}

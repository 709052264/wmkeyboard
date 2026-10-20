package com.wasimaster.wmkeyboard.core.voice.asr4all

import com.wasimaster.wmkeyboard.core.voice.VoiceStream
import com.wasimaster.wmkeyboard.core.voice.executorch.EtRunner
import com.wasimaster.wmkeyboard.core.voice.executorch.Tensor
import kotlin.math.min

/**
 * One utterance through an asr4all model, fed as the microphone delivers it.
 *
 * The model is a streaming CTC recogniser: each call of the tier's method takes
 * a fixed window of audio plus the caches the previous call returned, and
 * commits a fixed number of 40 ms frames, each a BPE piece or blank. So the
 * transcript grows while the user speaks, at constant cost per second of audio,
 * with no window limit on how long a clip may be. [text] is that raw transcript:
 * lower case, no punctuation.
 *
 * Where the bundle has it, a second pass (PCEC) reads the committed pieces,
 * pooled per token with the acoustic state that produced them, and writes
 * punctuation, capitals and corrections. It trails the raw text by a few
 * tokens; [punctuated] renders what it has settled, plus, at the end, what is
 * still inside it.
 *
 * Not thread-safe: one thread feeds, finishes and reads.
 */
class Asr4AllStream(
    private val runner: EtRunner,
    private val meta: Asr4AllMetadata,
    tierName: String = meta.defaultTier,
    usePcec: Boolean = true,
) : VoiceStream {
    private val tier = meta.tiers.firstOrNull { it.name == tierName } ?: error("no tier $tierName")
    private val shape = runner.shape(tier.name) ?: error("program has no method ${tier.name}")
    private val spf = meta.samplesPerFrame

    /** Samples one call reads: the frames it commits plus its lookahead. */
    private val window = shape.inputs[T_PCM].dims.last().toInt()
    private val windowFrames = window / spf

    /** Frames one call commits. */
    private val commit = shape.outputs[T_LOGITS].dims[1].toInt()
    private val vocabSize = shape.outputs[T_LOGITS].dims.last().toInt()
    private val hiddenDim = if (shape.outputs.size > T_HIDDEN) shape.outputs[T_HIDDEN].dims.last().toInt() else 0

    private val pcec: Pcec? = meta.pcec
        ?.takeIf { usePcec && hiddenDim > 0 && tier.pcecMethod != null && tier.pcecFlush != null }
        ?.let { Pcec(runner, it, tier.pcecMethod!!, tier.pcecFlush!!, tier.pcecCaches, hiddenDim) }

    /** At most how far the raw transcript trails the audio: one window. */
    val latencyMs: Int get() = windowFrames * spf * 1000 / SAMPLE_RATE

    private var filterCache = Tensor.zeros(shape.inputs[T_FILTER])
    private var attnCache = Tensor.zeros(shape.inputs[T_ATTN])

    // Audio from frame [baseFrame] on; what lies before the next window is dropped.
    private var pcm = FloatArray(window * 4)
    private var pcmLength = 0
    private var baseFrame = 0
    private var nextFrame = 0

    private val ids = ArrayList<Int>()

    private val totalSamples: Long get() = baseFrame.toLong() * spf + pcmLength

    /** Adds [count] samples of [chunk] (16 kHz mono, [-1, 1]) and decodes every window now complete. */
    override fun feed(chunk: FloatArray, count: Int) {
        if (pcmLength + count > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, pcmLength + count))
        System.arraycopy(chunk, 0, pcm, pcmLength, count)
        pcmLength += count
        while ((nextFrame.toLong() + windowFrames) * spf <= totalSamples) step()
    }

    /**
     * Decodes what is left, the last window padded with silence, and settles
     * the second pass. Returns the finished transcript: punctuated where the
     * bundle can, raw otherwise.
     */
    override fun finish(): String {
        while (nextFrame.toLong() * spf < totalSamples) step()
        pcec?.flush()
        pcec?.drain()
        return punctuated(tail = true)
    }

    /** The raw transcript so far: pieces with repeats and blanks collapsed, lower case. */
    override fun text(): String {
        val out = StringBuilder()
        var prev = -1
        for (id in ids) {
            if (id != prev && id != meta.blankId && id < meta.vocab.size) out.append(meta.vocab[id])
            prev = id
        }
        return out.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * Punctuated, cased text where the bundle has the second pass, else [text].
     * It trails [text] by a few tokens; [tail] also renders what is still inside
     * the second pass, which an endpoint wants and a live preview can skip.
     */
    fun punctuated(tail: Boolean = false): String {
        val p = pcec ?: return text()
        return p.render(if (tail) p.slots + p.tail() else p.slots)
    }

    private fun step() {
        val start = (nextFrame - baseFrame) * spf
        val win = FloatArray(window)
        val available = min(window, pcmLength - start)
        if (available > 0) System.arraycopy(pcm, start, win, 0, available)
        val outs = runner.run(
            tier.name,
            listOf(Tensor(longArrayOf(1, window.toLong()), win), filterCache, attnCache),
        )
        filterCache = outs[T_FILTER]
        attnCache = outs[T_ATTN]
        val decodedFrames = (totalSamples / spf).toInt()
        val valid = min(commit, decodedFrames - nextFrame).coerceAtLeast(0)
        val logits = outs[T_LOGITS].floats
        val frameIds = IntArray(valid) { f -> argmax(logits, f * vocabSize, vocabSize) }
        for (id in frameIds) ids += id
        if (pcec != null && outs.size > T_HIDDEN && valid > 0) {
            pcec.pool(frameIds, outs[T_HIDDEN].floats, meta.blankId)
            pcec.drain()
        }
        nextFrame += commit
        // Everything before the next window is behind us.
        val drop = (nextFrame - baseFrame) * spf
        if (drop in 1..pcmLength) {
            System.arraycopy(pcm, drop, pcm, 0, pcmLength - drop)
            pcmLength -= drop
            baseFrame = nextFrame
        }
    }

    private fun argmax(values: FloatArray, from: Int, count: Int): Int {
        var best = 0
        var bestValue = values[from]
        for (i in 1 until count) {
            val v = values[from + i]
            if (v > bestValue) {
                bestValue = v
                best = i
            }
        }
        return best
    }

    /**
     * The second pass. Each committed token is pooled with the mean of the
     * hidden state over its frames and the pause that came before it, then fed
     * through the pass in fixed blocks, which writes two output slots per input
     * token: a word or control in each. The pass holds [lookahead] tokens inside
     * it, so its first outputs are warm-up and are dropped.
     */
    private class Pcec(
        private val runner: EtRunner,
        private val meta: Asr4AllMetadata.Pcec,
        private val method: String,
        private val flushMethod: String,
        private val cacheCount: Int,
        private val dim: Int,
    ) {
        private val shape = runner.shape(method) ?: error("program has no method $method")
        private val flushShape = runner.shape(flushMethod) ?: error("program has no method $flushMethod")
        private val blockSize = shape.inputs[0].dims[1].toInt()
        private val lookahead = (flushShape.outputs[0].dims[1].toInt() - 1) / 2 - blockSize
        private val featureCount = shape.inputs.size - cacheCount
        private val wantsAcoustic = featureCount >= 3
        private val wantsKeep = featureCount == 4
        private val cacheSpecs = shape.inputs.subList(featureCount, shape.inputs.size)

        private var cache: List<Tensor> = cacheSpecs.map(Tensor::zeros)
        private val tokens = ArrayList<Int>()
        private val pauses = ArrayList<Int>()
        private val acoustic = ArrayList<FloatArray>()
        val slots = ArrayList<Int>()
        private var consumed = 0

        // The token whose frames are still arriving.
        private var openId = -1
        private var openSum = DoubleArray(dim)
        private var openFrames = 0
        private var openPause = 0
        private var blanks = 0
        private var prev = -1

        fun pool(frameIds: IntArray, hidden: FloatArray, blank: Int) {
            for ((t, id) in frameIds.withIndex()) {
                if (id != prev && id != blank) {
                    // A new token starts on this frame.
                    if (openId >= 0) emit()
                    openId = id
                    openSum = DoubleArray(dim) { hidden[t * dim + it].toDouble() }
                    openFrames = 1
                    openPause = bucket(blanks)
                    blanks = 0
                } else if (openId >= 0) {
                    // A repeat or a blank belongs to the token before it.
                    for (k in 0 until dim) openSum[k] += hidden[t * dim + k]
                    openFrames++
                }
                prev = id
                if (id == blank) blanks++
            }
        }

        fun flush() {
            if (openId >= 0) emit()
        }

        private fun emit() {
            tokens += openId
            acoustic += FloatArray(dim) { (openSum[it] / openFrames).toFloat() }
            pauses += openPause
            openId = -1
        }

        /** Runs every whole block that is ready; the rest waits for more tokens. */
        fun drain() {
            val k = blockSize
            while (tokens.size >= k) {
                val outs = runner.run(method, features(k) + cache)
                val skip = min(2 * k, maxOf(0, 2 * lookahead - 2 * consumed))
                val out = outs[0].longs
                for (i in skip until 2 * k) slots += out[i].toInt()
                cache = outs.takeLast(cacheCount)
                repeat(k) {
                    tokens.removeAt(0)
                    pauses.removeAt(0)
                    acoustic.removeAt(0)
                }
                consumed += k
            }
        }

        /** The last part-block and everything still inside the pass, rendered without changing its state. */
        fun tail(): List<Int> {
            val n = tokens.size
            val countSpec = flushShape.inputs[featureCount]
            val count = Tensor.integers(countSpec, LongArray(0), longArrayOf(n.toLong()))
            val outs = runner.run(flushMethod, features(n) + count + cache)
            val valid = 2 * lookahead + 2 * n + 1
            val skip = maxOf(0, 2 * lookahead - 2 * consumed)
            val out = outs[0].longs
            return (skip until min(valid, out.size)).map { out[it].toInt() }
        }

        /** One block of [blockSize] tokens, the first [real] of them real and the rest padding. */
        private fun features(real: Int): List<Tensor> {
            val k = blockSize
            val n = min(real, tokens.size)
            val ids = LongArray(k) { if (it < n) tokens[it].toLong() else meta.padIn }
            val pause = LongArray(k) { if (it < n && meta.realPause) pauses[it].toLong() else meta.pauseBlackout }
            val shape2 = longArrayOf(1, k.toLong())
            val features = mutableListOf(
                Tensor.integers(shape.inputs[0], shape2, ids),
                Tensor.integers(shape.inputs[1], shape2, pause),
            )
            if (wantsAcoustic) {
                val ac = FloatArray(k * dim)
                for (i in 0 until n) System.arraycopy(acoustic[i], 0, ac, i * dim, dim)
                features += Tensor(longArrayOf(1, k.toLong(), dim.toLong()), ac)
            }
            // How strongly to keep each token as heard: for a biasing decoder
            // this release does not have, so nothing is marked.
            if (wantsKeep) features += Tensor(shape2, FloatArray(k))
            return features
        }

        /** Slots to text: words from pieces, a capital or a mark where a control slot says. */
        fun render(slots: List<Int>): String {
            class Word(val pieces: StringBuilder, val case: Int, var mark: String = "")
            val words = ArrayList<Word>()
            var pendingCase = 0
            var prev = -1
            for (id in slots) {
                if (id == prev || id == meta.blankId) {
                    prev = id
                    continue
                }
                prev = id
                when (id) {
                    meta.cap -> pendingCase = CASE_CAP
                    meta.allCaps -> pendingCase = CASE_UPPER
                    meta.period -> words.lastOrNull()?.mark = "."
                    meta.comma -> words.lastOrNull()?.mark = ","
                    meta.question -> words.lastOrNull()?.mark = "?"
                    else -> {
                        val piece = meta.vocab.getOrNull(id).orEmpty()
                        if (piece.isEmpty()) continue
                        if (piece.startsWith(' ') || words.isEmpty()) {
                            words += Word(StringBuilder(piece), pendingCase)
                            pendingCase = 0
                        } else {
                            words.last().pieces.append(piece)
                        }
                    }
                }
            }
            val out = ArrayList<String>(words.size)
            var sentenceStart = true
            for (w in words) {
                var text = w.pieces.toString().trim()
                if (text.isEmpty()) continue
                text = when {
                    w.case == CASE_UPPER -> text.uppercase()
                    w.case == CASE_CAP || sentenceStart -> text.replaceFirstChar { it.uppercaseChar() }
                    else -> text
                }
                sentenceStart = w.mark == "." || w.mark == "?"
                out += text + w.mark
            }
            return out.joinToString(" ")
        }

        private fun bucket(blankRun: Int): Int = PAUSE_STEPS.count { blankRun >= it }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000

        // The streaming method's tensors, by position, as the bundles are exported.
        const val T_PCM = 0
        const val T_LOGITS = 0
        const val T_FILTER = 1
        const val T_ATTN = 2
        const val T_HIDDEN = 3

        const val CASE_CAP = 1
        const val CASE_UPPER = 2

        /** Blank-run lengths between the pause buckets the second pass was trained on. */
        val PAUSE_STEPS = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128)

        val WHITESPACE = Regex("\\s+")
    }
}

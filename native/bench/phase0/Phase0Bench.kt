// Phase 0 of native/RUST_NEON_PLAN.md: what every NEON candidate costs in
// Kotlin today. Not part of any Gradle build and never shipped: run.sh
// compiles it against a checkout's built module jars, runs it on the host
// JVM, and dexes the same classes for ART on a phone (app_process), so both
// columns of the plan's table time identical code.
//
// Where the production loop sits behind android.graphics or a private
// function (Bitmap scaling, AudioRecord capture, PhotoEditRenderer's
// getPixels bands, ImageGenEngine.toBitmap), the loop body is copied here
// verbatim on plain arrays and says so. Everything else calls the real class.
@file:Suppress("ForbiddenMethodCall", "MagicNumber")

package com.wasimaster.wmkeyboard.bench

import com.wasimaster.wmkeyboard.core.gesture.CtcGlideBeam
import com.wasimaster.wmkeyboard.core.gesture.DuetBeam
import com.wasimaster.wmkeyboard.core.gesture.DuetWorkspace
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideToken
import com.wasimaster.wmkeyboard.core.gesture.GlideWorkspace
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.gesture.SwipeEmissions
import com.wasimaster.wmkeyboard.core.imagegen.EulerSampler
import com.wasimaster.wmkeyboard.core.imagegen.LcmSampler
import com.wasimaster.wmkeyboard.core.imagegen.NoiseSchedule
import com.wasimaster.wmkeyboard.core.media.GifEncoder
import com.wasimaster.wmkeyboard.core.ocr.PaddleOcrPipeline
import com.wasimaster.wmkeyboard.core.ocr.PaddleRuntime
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.semantic.VectorIndex
import com.wasimaster.wmkeyboard.core.stickers.CutoutMask
import com.wasimaster.wmkeyboard.core.voice.WavEncoder
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperMel
import java.io.File
import java.io.OutputStream
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Keeps results alive so no loop is optimised away. */
@Volatile var sink: Any? = null
@Volatile var sinkF = 0f

object Phase0Bench {

    private var budgetScale = 1.0
    private val rows = ArrayList<String>()

    /**
     * Runs [op] until it is warm (at least [minWarm] calls and a second of
     * wall time, so ART's JIT has compiled it), then times up to [maxCalls]
     * calls or three seconds, and prints median and p95 in ms.
     */
    private fun bench(name: String, often: String, minWarm: Int = 20, maxCalls: Int = 400, op: () -> Any?) {
        val warmUntil = System.nanoTime() + (1e9 * budgetScale).toLong()
        var w = 0
        while (w < minWarm || System.nanoTime() < warmUntil) { sink = op(); w++ }
        val times = LongArray(maxCalls)
        var n = 0
        val stopAt = System.nanoTime() + (3e9 * budgetScale).toLong()
        while (n < maxCalls && (n < 15 || System.nanoTime() < stopAt)) {
            val t0 = System.nanoTime()
            sink = op()
            times[n++] = System.nanoTime() - t0
        }
        val sorted = times.copyOf(n).also { it.sort() }
        val med = sorted[n / 2] / 1e6
        val p95 = sorted[min(n - 1, (n * 95) / 100)] / 1e6
        val row = String.format(Locale.ROOT, "%-46s median %9.3f ms  p95 %9.3f ms  n=%-4d  %s", name, med, p95, n, often)
        println(row)
        rows += row
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dict = args.firstOrNull { it.endsWith(".txt") } ?: "app/dictionaries-src/en.txt"
        budgetScale = args.firstOrNull { it.startsWith("budget=") }?.substringAfter('=')?.toDouble() ?: 1.0
        val only = args.firstOrNull { it.startsWith("only=") }?.substringAfter('=')?.split(',')?.toSet()
        println("runtime: ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}, cores ${Runtime.getRuntime().availableProcessors()}")
        fun on(group: String) = only == null || group in only
        if (on("vector")) vectors()
        if (on("gif")) gif()
        if (on("ocr")) ocr()
        if (on("image")) images()
        if (on("glide")) glide(File(dict))
        if (on("audio")) audio()
        println("done")
    }

    // ---- Embedding dot products ------------------------------------------------

    private fun unitVector(r: Random, d: Int): FloatArray {
        val v = FloatArray(d) { r.nextFloat() * 2f - 1f }
        var s = 0f
        for (x in v) s += x * x
        val inv = 1f / sqrt(s)
        for (i in v.indices) v[i] *= inv
        return v
    }

    private fun vectors() {
        val d = 768
        val r = Random(1)
        for (count in intArrayOf(1_000, 5_000, 20_000)) {
            val index = VectorIndex(d)
            val raw = Array(count) { unitVector(r, d) }
            raw.forEachIndexed { i, v -> index.put("item/$i", 0L, v) }
            val q = unitVector(r, d)
            bench("VectorIndex.score ${count}x$d", "per search (300 ms debounce)") { index.score(q) }
            // The same dot products with no Hit objects and no sort: the part
            // NEON would replace.
            val stored = Array(count) { index.vectorOf("item/$it")!! }
            val out = FloatArray(count)
            bench("  dots only ${count}x$d", "share of the above") {
                for (i in 0 until count) {
                    val v = stored[i]
                    var dot = 0f
                    for (k in 0 until d) dot += v[k] * q[k]
                    out[i] = dot
                }
                out
            }
        }
    }

    // ---- GIF encoding -------------------------------------------------------------

    private object Discard : OutputStream() {
        var bytes = 0L
        override fun write(b: Int) { bytes++ }
        override fun write(b: ByteArray, off: Int, len: Int) { bytes += len }
    }

    /**
     * A sticker-like frame: a soft-edged blob on transparent, shaded and
     * textured so its colours fill most of the palette, moving with [phase].
     */
    private fun stickerFrame(side: Int, phase: Int): IntArray {
        val px = IntArray(side * side)
        val c = side / 2f
        val radius = side * 0.42f
        val shift = phase * 0.21f
        for (y in 0 until side) for (x in 0 until side) {
            val dx = x - c - 20f * sin(shift)
            val dy = y - c - 20f * cos(shift)
            val d = sqrt(dx * dx + dy * dy)
            val a = ((radius - d) * 0.25f).coerceIn(0f, 1f)
            if (a <= 0f) continue
            val rr = (128 + 100 * sin(x * 0.031f + shift)).toInt().coerceIn(0, 255)
            val gg = (128 + 100 * sin(y * 0.027f - shift)).toInt().coerceIn(0, 255)
            val bb = (128 + 90 * sin((x + y) * 0.019f + 2 * shift)).toInt().coerceIn(0, 255)
            px[y * side + x] = ((a * 255).toInt() shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return px
    }

    private fun gif() {
        val side = 512
        val frames = Array(30) { stickerFrame(side, it) }
        var f = 0
        val encoder = GifEncoder(Discard, side, side)
        bench("GifEncoder.addFrame 512x512", "per frame; x30-60 per animated sticker sent as GIF") {
            encoder.addFrame(frames[f++ % frames.size], 40)
        }
        bench("GifEncoder whole GIF, 30 frames 512x512", "per animated sticker into a GIF-only field", minWarm = 3, maxCalls = 30) {
            val e = GifEncoder(Discard, side, side)
            for (fr in frames) e.addFrame(fr, 40)
            e.finish()
        }
    }

    // ---- OCR pre/post-processing ---------------------------------------------

    /** PP-OCRv5 mobile's recognizer shape: 40 steps of 18,385 classes. */
    private class FakePaddle(private val lines: Int) : PaddleRuntime {
        override val recognizerSteps = 40
        override val recognizerClasses = 18_385
        private val logits = FloatArray(recognizerSteps * recognizerClasses).also { out ->
            val r = Random(3)
            for (i in out.indices) out[i] = r.nextFloat() * 0.01f
            for (t in 0 until recognizerSteps) out[t * recognizerClasses + if (t % 3 == 0) 0 else 1 + r.nextInt(5000)] = 0.9f
        }

        override fun open(detector: File, recognizer: File, threads: Int) = Unit
        override fun detect(input: FloatArray, output: FloatArray) = textMap(output, lines)
        override fun recognize(input: FloatArray, output: FloatArray) { System.arraycopy(logits, 0, output, 0, output.size) }
        override fun close() = Unit
    }

    /** A detector map with [lines] printed lines of text across a page. */
    private fun textMap(prob: FloatArray, lines: Int) {
        val side = PaddleOcrPipeline.DET_SIDE
        prob.fill(0f)
        val pitch = side / (lines + 1)
        for (l in 0 until lines) {
            val top = pitch * (l + 1) - 6
            val left = 40 + (l * 37) % 80
            val right = side - 40 - (l * 53) % 160
            for (y in top until top + 12) for (x in left until right) prob[y * side + x] = 0.95f
        }
    }

    private fun photo(w: Int, h: Int): IntArray {
        val r = Random(5)
        return IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(0x1000000) }
    }

    private fun ocr() {
        val w = 4000
        val h = 3000
        val pixels = photo(w, h)
        val charset = List(18_383) { "c$it" }
        val detIn = FloatArray(PaddleOcrPipeline.DET_SIDE * PaddleOcrPipeline.DET_SIDE * 3)
        bench("OCR detectorInput 12MP -> 640^2", "per OCR read (once per photo)", minWarm = 3, maxCalls = 30) {
            PaddleOcrPipeline.detectorInput(pixels, w, h, detIn)
        }
        val letterbox = PaddleOcrPipeline.detectorInput(pixels, w, h, detIn)
        val prob = FloatArray(PaddleOcrPipeline.DET_SIDE * PaddleOcrPipeline.DET_SIDE)
        textMap(prob, 25)
        bench("OCR boxes (640^2 map, 25 lines)", "per OCR read") { PaddleOcrPipeline.boxes(prob, letterbox) }
        val boxes = PaddleOcrPipeline.boxes(prob, letterbox)
        val box = boxes.first()
        bench("OCR strip (one line)", "per text line (x25 per page)") { PaddleOcrPipeline.strip(pixels, w, h, box) }
        val strip = PaddleOcrPipeline.strip(pixels, w, h, box)!!
        val recIn = FloatArray(PaddleOcrPipeline.REC_HEIGHT * PaddleOcrPipeline.REC_WIDTH * 3)
        bench("OCR recognizerInput (one 320 px piece)", "per piece (1-3 per line)") {
            PaddleOcrPipeline.recognizerInput(strip, 0, min(strip.width, PaddleOcrPipeline.REC_WIDTH), recIn)
        }
        val fake = FakePaddle(25)
        val recOut = FloatArray(fake.recognizerSteps * fake.recognizerClasses)
        fake.recognize(recIn, recOut)
        bench("OCR ctcDecode 40x18385", "per piece") {
            PaddleOcrPipeline.ctcDecode(recOut, fake.recognizerSteps, fake.recognizerClasses, charset)
        }
        bench("OCR read() minus inference, 12MP, 25 lines", "per OCR read", minWarm = 3, maxCalls = 20) {
            PaddleOcrPipeline.read(fake, pixels, w, h, charset)
        }
    }

    // ---- Sticker cutout, NSFW filter, generator, photo editor ----------------

    private fun images() {
        val cutSide = 320
        val cutPixels = photo(cutSide, cutSide)
        val cutIn = FloatArray(cutPixels.size * 3)
        bench("Cutout normalise 320^2", "per cutout (sticker editor action)") { CutoutMask.normalise(cutPixels, cutIn) }
        val map = FloatArray(cutSide * cutSide) { (it % 997) / 997f }
        bench("Cutout alphaOf 320^2", "per cutout") { CutoutMask.alphaOf(map) }

        // NsfwFilter.unsafe's CHW loop, verbatim, on the 224^2 pixels the
        // native scaler hands it.
        val nsfwSide = 224
        val nsfwPx = photo(nsfwSide, nsfwSide)
        val chw = FloatArray(3 * nsfwSide * nsfwSide)
        bench("NsfwFilter normalise 224^2 (copied loop)", "per generated picture") {
            val plane = nsfwSide * nsfwSide
            for (i in 0 until plane) {
                val p = nsfwPx[i]
                chw[i] = ((p shr 16 and 0xff) / 255f - 0.5f) / 0.5f
                chw[plane + i] = ((p shr 8 and 0xff) / 255f - 0.5f) / 0.5f
                chw[2 * plane + i] = ((p and 0xff) / 255f - 0.5f) / 0.5f
            }
            chw
        }

        // The generator's Kotlin between network calls: one sampler step on a
        // 4x64x64 latent, and ImageGenEngine.toBitmap's loop (copied) at 512^2.
        val schedule = NoiseSchedule(0.00085, 0.012, "scaled_linear", 1000)
        val r = Random(9)
        val latent = FloatArray(4 * 64 * 64) { r.nextFloat() - 0.5f }
        val output = FloatArray(latent.size) { r.nextFloat() - 0.5f }
        val noise = FloatArray(latent.size) { r.nextFloat() - 0.5f }
        val lcm = LcmSampler(schedule, 4, vPrediction = false)
        bench("ImageGen LcmSampler.step 4x64x64", "per UNet step (1-8 per picture)") { lcm.step(latent, output, 0) { noise } }
        val euler = EulerSampler(schedule, intArrayOf(999), "trailing", vPrediction = false)
        bench("ImageGen EulerSampler.step 4x64x64", "per UNet step") { euler.step(latent, output, 0) { noise } }
        val size = 512
        val decoded = FloatArray(3 * size * size) { r.nextFloat() }
        bench("ImageGen toBitmap 512^2 (copied loop)", "per picture + per preview") {
            val plane = size * size
            IntArray(plane) { i ->
                val rr = (decoded[i] * 255f).roundToInt().coerceIn(0, 255)
                val gg = (decoded[plane + i] * 255f).roundToInt().coerceIn(0, 255)
                val bb = (decoded[2 * plane + i] * 255f).roundToInt().coerceIn(0, 255)
                (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }

        // PhotoEditRenderer.sharpenInPlace's inner loop, copied onto one
        // IntArray (the real one bands through getPixels/setPixels, and
        // android.graphics.Color's accessors are the same shifts inlined).
        for ((pw, ph) in listOf(4000 to 3000, 2048 to 1536)) {
            val src = photo(pw, ph)
            val dst = IntArray(src.size)
            bench("PhotoEdit sharpen ${pw}x$ph (copied loop)", if (pw == 2048) "per adjust change in the editor preview, sharpen > 0" else "per photo sent with sharpen > 0") {
                sharpen(src, dst, pw, ph, 0.5f * 1.6f)
            }
        }
    }

    private fun sharpenChannel(c: Int, l: Int, r: Int, u: Int, d: Int, a: Float): Int =
        (c + a * (4 * c - l - r - u - d) / 4f).roundToInt().coerceIn(0, 255)

    private fun sharpen(src: IntArray, out: IntArray, w: Int, h: Int, a: Float): IntArray {
        for (y in 0 until h) {
            val up = if (y == 0) y else y - 1
            val down = if (y == h - 1) y else y + 1
            for (x in 0 until w) {
                val c = src[y * w + x]
                val l = src[y * w + max(0, x - 1)]
                val r = src[y * w + min(w - 1, x + 1)]
                val u = src[up * w + x]
                val d = src[down * w + x]
                out[y * w + x] = (c ushr 24 shl 24) or
                    (sharpenChannel(c shr 16 and 0xff, l shr 16 and 0xff, r shr 16 and 0xff, u shr 16 and 0xff, d shr 16 and 0xff, a) shl 16) or
                    (sharpenChannel(c shr 8 and 0xff, l shr 8 and 0xff, r shr 8 and 0xff, u shr 8 and 0xff, d shr 8 and 0xff, a) shl 8) or
                    sharpenChannel(c and 0xff, l and 0xff, r and 0xff, u and 0xff, d and 0xff, a)
            }
        }
        return out
    }

    // ---- Glide decoders -------------------------------------------------------------

    private const val KEY_W = 60f
    private const val KEY_H = 84f
    private val ROWS = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)

    private val centers: Map<Char, Pair<Float, Float>> = buildMap {
        ROWS.forEachIndexed { row, (letters, offset) ->
            letters.forEachIndexed { i, ch -> put(ch, (offset + i + 0.5f) * KEY_W to (row + 0.5f) * KEY_H) }
        }
    }

    /**
     * A swipe through [word]'s keys: each corner jittered by a fifth of a key,
     * points every 4 ms at a finger speed of about a key width per 35 ms, the
     * touch rate a 240 Hz panel reports.
     */
    private fun swipe(word: String, r: Random): List<GesturePoint> {
        val corners = word.map { ch ->
            val (x, y) = centers.getValue(ch)
            (x + gauss(r) * 0.2f * KEY_W) to (y + gauss(r) * 0.2f * KEY_H)
        }
        val out = ArrayList<GesturePoint>()
        var t = 0L
        out += GesturePoint(corners[0].first, corners[0].second, t)
        for (i in 1 until corners.size) {
            val (ax, ay) = corners[i - 1]
            val (bx, by) = corners[i]
            val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            val steps = max(1, (len / KEY_W * 35f / 4f).toInt())
            for (s in 1..steps) {
                t += 4
                val f = s.toFloat() / steps
                out += GesturePoint(ax + (bx - ax) * f + gauss(r) * 1.5f, ay + (by - ay) * f + gauss(r) * 1.5f, t)
            }
        }
        return out
    }

    private fun gauss(r: Random): Float {
        val u = max(1e-9, r.nextDouble())
        return (sqrt(-2 * ln(u)) * cos(2 * PI * r.nextDouble())).toFloat()
    }

    /** FUTO-encoder-shaped emissions for [path]: per point, letters by distance, blank between keys. */
    private fun emissions(path: List<GesturePoint>): SwipeEmissions {
        val steps = path.size
        val out = FloatArray(steps * SwipeEmissions.CLASSES)
        val letters = ('a'..'z').map { centers.getValue(it) }
        for (t in 0 until steps) {
            val p = path[t]
            var sum = 0.0
            val base = t * SwipeEmissions.CLASSES
            for (k in 0 until 26) {
                val dx = (p.x - letters[k].first) / KEY_W
                val dy = (p.y - letters[k].second) / KEY_H
                val e = exp(-(dx * dx + dy * dy) * 2.0)
                out[base + k] = e.toFloat()
                sum += e
            }
            out[base + SwipeEmissions.BLANK] = 0.6f
            sum += 0.6
            for (k in 0 until SwipeEmissions.CLASSES) out[base + k] = ln(max(1e-6, out[base + k] / sum)).toFloat()
        }
        return SwipeEmissions(steps, out)
    }

    private fun glide(dictFile: File) {
        val entries = dictFile.inputStream().use { DictionaryLoader.loadEntries(it) }
        val trie = Trie().apply { entries.forEach { (word, f) -> insert(word, f) } }
        val sources = trie.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) }
        val keyCenters = centers.map { (ch, xy) -> KeyCenter(ch.code, xy.first, xy.second) }
        val keys = GlideKeyMap.of(keyCenters, KEY_W)
        val r = Random(7)
        val words = entries.map { it.first }
            .filter { w -> w.length in 3..10 && w.all { it in 'a'..'z' } }
            .take(4000).shuffled(r).take(200)
        val paths = words.map { swipe(it, r) }
        println("glide: ${entries.size} words, ${paths.size} swipes, mean ${paths.sumOf { it.size } / paths.size} points")

        val beam = GlideBeam()
        val ws = GlideWorkspace()
        var i = 0
        var hits = 0
        for ((w, p) in words.zip(paths)) if (beam.decode(p, keys, KEY_W, sources, ws).firstOrNull()?.word == w) hits++
        println("glide: GlideBeam top-1 on these swipes ${hits * 100 / words.size}%")
        bench("GlideBeam.decode (finished swipe)", "per swipe at finger-up") {
            beam.decode(paths[i++ % paths.size], keys, KEY_W, sources, ws)
        }
        bench("GlideBeam 6 live previews of one stroke", "per swipe while the finger is down") {
            val p = paths[i++ % paths.size]
            for (step in 1..6) {
                val upTo = p.size * step / 6
                if (upTo >= 3) beam.decode(p.subList(0, upTo), keys, KEY_W, sources, ws)
            }
        }
        // buildCosts' loop (copied; it is private) at the size the decode just
        // used: every resampled point against every key. This is the part a
        // four-lane kernel would take.
        beam.decode(paths[0], keys, KEY_W, sources, ws)
        val n = ws.samples
        val kc = keys.keyCount
        val cost = FloatArray(n * kc)
        val near = BooleanArray(kc)
        val px = ws.pathX.copyOf(n)
        val py = ws.pathY.copyOf(n)
        bench("  buildCosts loop ${n}x$kc (copied)", "share of one decode") {
            for (s in 0 until n) {
                val base = s * kc
                for (k in 0 until kc) {
                    val dx = px[s] - keys.keyX[k]
                    val dy = py[s] - keys.keyY[k]
                    val scaled = (dx * dx + dy * dy) * 2f
                    cost[base + k] = if (scaled > 9f) 9f else scaled
                    if (scaled <= 1f) near[k] = true
                }
            }
            cost
        }

        val ctc = CtcGlideBeam()
        val ems = paths.map { emissions(it) }
        bench("CtcGlideBeam.decode (FUTO engine's beam)", "per swipe at finger-up, FUTO engine only") {
            ctc.decode(ems[i++ % ems.size], sources)
        }

        val duet = DuetBeam()
        val dws = DuetWorkspace()
        val tokens = paths.map { listOf<GlideToken>(GlideToken.Swipe(it)) }
        bench("DuetBeam.decode (one-swipe word)", "per two-thumb word") {
            duet.decode(tokens[i++ % tokens.size], keys, KEY_W, sources, dws)
        }
    }

    // ---- Voice capture glue -------------------------------------------------------

    private fun audio() {
        val r = Random(11)
        val chunk = ShortArray(1600) { (r.nextInt(2000) - 1000).toShort() }
        val raw = FloatArray(chunk.size)
        // WhisperRecorder.capture's per-chunk loop, copied (it reads AudioRecord).
        bench("PCM16 -> float + RMS, 1600 samples (copied)", "per 100 ms audio chunk", minWarm = 2000) {
            var rawSq = 0.0
            for (i in 0 until chunk.size) {
                val s = chunk[i] / 32768f
                raw[i] = s
                rawSq += (s * s).toDouble()
            }
            sinkF = sqrt(rawSq / chunk.size).toFloat()
            raw
        }
        val clip = FloatArray(WhisperMel.SAMPLE_RATE * 5) { (r.nextFloat() - 0.5f) * 0.2f }
        val cols = 1 + WhisperMel.N_FFT / 2
        val filters = melFilters(WhisperMel.N_MEL, cols)
        bench("WhisperMel.compute, 5 s clip, 80 mel", "per utterance, LiteRT Whisper only", minWarm = 3, maxCalls = 40) {
            WhisperMel.compute(clip, filters, cols, WhisperMel.N_MEL)
        }
        bench("WavEncoder.encode, 5 s clip", "per utterance, server engines only") { WavEncoder.encode(clip) }
    }

    /** Triangular bands over [cols] bins, the shape Whisper's filterbank has. */
    private fun melFilters(nMel: Int, cols: Int): FloatArray {
        val f = FloatArray(nMel * cols)
        for (m in 0 until nMel) {
            val centre = (m + 1) * (cols - 2) / (nMel + 1)
            val half = max(1, centre / 12 + 1)
            for (c in max(0, centre - half)..min(cols - 1, centre + half)) {
                f[m * cols + c] = 1f - kotlin.math.abs(c - centre).toFloat() / (half + 1)
            }
        }
        return f
    }
}

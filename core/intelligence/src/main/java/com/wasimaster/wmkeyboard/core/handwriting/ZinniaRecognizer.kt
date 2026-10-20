package com.wasimaster.wmkeyboard.core.handwriting

import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One zinnia model, open: the recogniser [HandwritingRecognizerCache] holds
 * for a Chinese or Japanese zinnia tag, in place of ML Kit's.
 *
 * zinnia reads one character per call, where the panel hands over a line.
 * The line is cut into pieces at its gaps ([ZinniaInk.segment]) and every
 * way of joining neighbouring pieces back into characters is read; the
 * reading the model is surest of leads and the next ones follow it in the
 * strip, so a character whose halves stand apart (硍) and two characters
 * written close together are both one tap from right.
 *
 * Classification is a few milliseconds of arithmetic over the memory-mapped
 * model. It runs on [Dispatchers.Default], one call at a time per model, and
 * [close] waits for the call in flight.
 */
internal class ZinniaRecognizer private constructor(private var handle: Long) : InkRuntime.Recognizer {

    private val lock = Any()

    /** One label and zinnia's score for it; higher is surer. */
    data class Scored(val text: String, val score: Float)

    override suspend fun recognize(
        strokes: List<HwStroke>,
        preContext: String,
        writingAreaWidth: Float,
        writingAreaHeight: Float,
    ): List<String> = withContext(Dispatchers.Default) { read(strokes, writingAreaHeight) }

    /** Candidate texts for [strokes], best first. Synchronous; [recognize] is the panel's way in. */
    fun read(strokes: List<HwStroke>, areaHeight: Float): List<String> {
        val pieces = ZinniaInk.segment(strokes, areaHeight)
        if (pieces.isEmpty()) return emptyList()
        val minSide = ZinniaInk.minSideFor(areaHeight)
        if (pieces.size == 1) return classify(pieces.single(), minSide).map(Scored::text)
        if (pieces.size > MAX_PIECES) {
            // Too long a line to weigh every way of joining it: read each
            // piece as a character, which is what a line that long almost
            // always is.
            val each = pieces.map { classify(it, minSide) }
            if (each.any { it.isEmpty() }) return emptyList()
            return listOf(each.joinToString("") { it.first().text })
        }

        // A gap the segmenter cuts at can fall between two characters or
        // between the two halves of one (石 and 艮 in 硍). Every way of
        // joining neighbouring pieces back into characters is read, up to
        // MAX_SPAN pieces to a character, and the one whose characters
        // zinnia is surest of on average wins. The line read as a single
        // character is one of those ways.
        val sides = pieces.mapNotNull { ZinniaInk.boxOf(it)?.side }.sorted()
        val charMinSide = max(minSide, sides[sides.size / 2] * NEIGHBOUR_SHARE)
        val spans = HashMap<Long, List<Scored>>()
        fun span(from: Int, to: Int): List<Scored> = spans.getOrPut(from.toLong() shl 32 or to.toLong()) {
            val ink = pieces.subList(from, to).flatten()
            classify(ink, if (from == 0 && to == pieces.size) minSide else charMinSide)
        }
        val readings = joinings(pieces.size).mapNotNull { cuts ->
            val parts = cuts.zipWithNext { a, b -> span(a, b) }
            if (parts.any { it.isEmpty() }) return@mapNotNull null
            Reading(parts, parts.map { it.first().score }.average())
        }.sortedByDescending(Reading::score)
        if (readings.isEmpty()) return emptyList()

        val best = readings.first()
        val prefix = best.parts.dropLast(1).joinToString("") { it.first().text }
        return (
            listOf(best.text) +
                readings.drop(1).take(ALTERNATE_READINGS).map(Reading::text) +
                best.parts.last().drop(1).map { prefix + it.text }
            ).distinct()
    }

    /** One way of reading the line: a character per part, each part's labels best first. */
    private class Reading(val parts: List<List<Scored>>, val score: Double) {
        val text: String get() = parts.joinToString("") { it.first().text }
    }

    /**
     * Every way of cutting [n] pieces into runs of 1 to [MAX_SPAN] pieces,
     * as cut positions from 0 to [n]; the whole line as one run is always
     * among them.
     */
    private fun joinings(n: Int): List<List<Int>> {
        val out = mutableListOf(listOf(0, n))
        fun walk(at: Int, cuts: MutableList<Int>) {
            if (at == n) {
                if (cuts.size > 2) out += cuts.toList()
                return
            }
            for (len in 1..minOf(MAX_SPAN, n - at)) {
                cuts += at + len
                walk(at + len, cuts)
                cuts.removeAt(cuts.lastIndex)
            }
        }
        walk(0, mutableListOf(0))
        return out.distinct()
    }

    private fun classify(ink: List<HwStroke>, minSide: Float): List<Scored> {
        val encoded = ZinniaInk.fit(ink, minSide)
        if (encoded.strokeEnds.isEmpty()) return emptyList()
        val scores = FloatArray(NBEST)
        val labels = synchronized(lock) {
            if (handle == 0L) return emptyList()
            ZinniaNative.nativeClassify(handle, ZinniaInk.BOX, encoded.points, encoded.strokeEnds, NBEST, scores)
        } ?: return emptyList()
        return labels.mapIndexedNotNull { i, bytes ->
            val text = String(bytes, Charsets.UTF_8)
            if (text.isEmpty()) null else Scored(text, scores.getOrElse(i) { 0f })
        }
    }

    /** How many characters the model knows; 0 once closed. */
    fun size(): Int = synchronized(lock) { if (handle == 0L) 0 else ZinniaNative.nativeSize(handle) }

    override fun close() {
        synchronized(lock) {
            if (handle != 0L) ZinniaNative.nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        /** Labels asked of zinnia per character; the strip shows the first few. */
        private const val NBEST = 8

        /** A character in a line is taken to be at least this share of its neighbours' typical size. */
        private const val NEIGHBOUR_SHARE = 0.6f

        /** Pieces of one line that are still weighed every way they can be joined. */
        private const val MAX_PIECES = 6

        /** The most pieces one character is put back together from. */
        private const val MAX_SPAN = 3

        /** Other ways of reading the line that follow the best one in the strip. */
        private const val ALTERNATE_READINGS = 2

        /** Opens [model]; null when the library is not here or the file will not load. */
        fun open(model: File): ZinniaRecognizer? {
            if (!ZinniaNative.available || !model.isFile) return null
            val handle = ZinniaNative.nativeOpen(model.absolutePath)
            return if (handle == 0L) null else ZinniaRecognizer(handle)
        }
    }
}

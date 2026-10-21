package com.wasimaster.wmkeyboard.core.semantic

/**
 * Which scored items count as found, and how they sit beside the keyword
 * matches a search box already lists.
 *
 * Cosine scores from this model do not fall to zero for unrelated things:
 * every text sits somewhere between 0.5 and 0.7 from any query, and what
 * marks a real match is standing out from that crowd. So an item is kept when
 * it is close to the best one, above the floor, and clearly above the middle
 * of the pack. With too few items to have a middle, a fixed bar stands in.
 * The numbers were read off the model's scores on mixed English, Bengali and
 * French texts and on COCO photographs (see `models/README.md` in the data
 * repository): related pairs landed at 0.68 and up, unrelated ones mostly
 * under 0.65, pictures a little lower on both sides.
 */
object MeaningRank {

    data class Hit(val key: String, val score: Float)

    /**
     * [floor]: nothing under it, ever. [fromBest]: how far below the best
     * hit a hit may be. [aboveMedian]: how far over the median it must be,
     * once there are [minForMedian] items. [smallSetBar] replaces the median
     * test below that. [limit]: hits kept at most.
     */
    data class Tuning(
        val floor: Float,
        val fromBest: Float,
        val aboveMedian: Float,
        val smallSetBar: Float,
        val minForMedian: Int = 8,
        val limit: Int = 30,
    )

    /** Texts found by a typed query: clips, notes, snippets. */
    val TEXT = Tuning(floor = 0.62f, fromBest = 0.06f, aboveMedian = 0.05f, smallSetBar = 0.67f)

    /** Pictures found by a typed query: stickers, saved GIFs. */
    val PICTURE = Tuning(floor = 0.6f, fromBest = 0.06f, aboveMedian = 0.05f, smallSetBar = 0.65f)

    /**
     * The phone's photos, for the gallery's search: the picture bar, with
     * room for more of them, since a library holds many shots of one thing.
     */
    val PHOTO = PICTURE.copy(limit = 90)

    /**
     * Emoji by their names. Short names all sit close to a short query, so the
     * bar is the median one, and only a handful are worth a place after the
     * keyword hits.
     */
    val EMOJI = Tuning(floor = 0.64f, fromBest = 0.05f, aboveMedian = 0.06f, smallSetBar = 0.7f, limit = 12)

    /** Tags for a picture, picked from the emoji names: stricter still. */
    val TAG = Tuning(floor = 0.62f, fromBest = 0.03f, aboveMedian = 0.06f, smallSetBar = 0.68f, limit = 3)

    /** The hits of [scored] (best first, as [VectorIndex.score] returns them) worth showing. */
    fun select(scored: List<Hit>, tuning: Tuning): List<Hit> {
        if (scored.isEmpty()) return emptyList()
        val bar = bar(scored.first().score, scored.size, tuning) { median(scored) }
        return scored.asSequence().takeWhile { it.score >= bar }.take(tuning.limit).toList()
    }

    /**
     * The score a hit needs, given the [best] of [count] scored items;
     * [median] is asked only when there are enough of them to have one.
     * [VectorIndex.search] reaches the same bar without sorting.
     */
    internal inline fun bar(best: Float, count: Int, tuning: Tuning, median: () -> Float): Float =
        if (count >= tuning.minForMedian) {
            maxOf(tuning.floor, best - tuning.fromBest, median() + tuning.aboveMedian)
        } else {
            maxOf(tuning.floor, tuning.smallSetBar, best - tuning.fromBest)
        }

    /**
     * The keyword matches first, in their own order, then what was found only
     * by meaning, best first. A meaning hit the keywords already found is not
     * listed twice, and one that is no longer there (deleted since it was
     * indexed) is dropped by [exists].
     */
    fun <K> merge(keyword: List<K>, meaning: List<K>, exists: (K) -> Boolean = { true }): List<K> {
        if (meaning.isEmpty()) return keyword
        val seen = HashSet(keyword)
        val out = ArrayList<K>(keyword.size + meaning.size)
        out += keyword
        for (key in meaning) if (exists(key) && seen.add(key)) out += key
        return out
    }

    private fun median(scored: List<Hit>): Float {
        val sorted = scored.map { it.score }.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }
}

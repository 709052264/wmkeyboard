package com.wasimaster.wmkeyboard.core.semantic

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Turns a set of vectors into what something else needs them for, a batch
 * at a time: texts for a text space, picture paths for a picture space.
 * Returns one vector per input in order, or null when the engine cannot run
 * (no model, no memory), which ends the pass without losing what was done.
 */
fun interface Embedder {
    suspend fun embed(inputs: List<String>): List<FloatArray>?
}

/**
 * Keeps a [VectorIndex] in step with the store it mirrors, incrementally:
 * what was deleted goes, what is new or edited is embedded, and what is
 * unchanged is left alone. Runs on whatever coroutine its caller gives it,
 * always a background one: an index pass is never on the typing path.
 */
object MeaningSync {

    /**
     * One thing to index: its [key] in the store, a [stamp] that changes
     * whenever what gets embedded changes, and the [input] itself (the text,
     * or the picture's path).
     */
    data class Item(val key: String, val stamp: Long, val input: String)

    data class Plan(val embed: List<Item>, val remove: Set<String>)

    data class Result(val embedded: Int, val removed: Int, val complete: Boolean)

    /** What [sync] would do, without doing it. */
    fun plan(index: VectorIndex, items: List<Item>): Plan {
        val wanted = LinkedHashMap<String, Item>(items.size * 2)
        for (item in items) wanted[item.key] = item
        val remove = index.keys.filterTo(HashSet()) { it !in wanted }
        val embed = wanted.values.filter { index.stampOf(it.key) != it.stamp }
        return Plan(embed, remove)
    }

    /**
     * Brings [index] up to [items]: drops what is gone, embeds at most
     * [budget] new or changed items in batches of [batch], newest-listed
     * first since a store lists its freshest first. [onBatch] runs after each
     * batch is in, for saving progress. Stops early, keeping what it has,
     * when the embedder fails or the coroutine is cancelled.
     */
    suspend fun sync(
        index: VectorIndex,
        items: List<Item>,
        embedder: Embedder,
        batch: Int = 16,
        budget: Int = Int.MAX_VALUE,
        onBatch: () -> Unit = {},
    ): Result {
        val plan = plan(index, items)
        for (key in plan.remove) index.remove(key)
        var embedded = 0
        val todo = plan.embed.take(budget)
        for (chunk in todo.chunked(batch.coerceAtLeast(1))) {
            currentCoroutineContext().ensureActive()
            val vectors = embedder.embed(chunk.map { it.input })
            if (vectors == null || vectors.size != chunk.size) {
                return Result(embedded, plan.remove.size, complete = false)
            }
            for ((item, vector) in chunk.zip(vectors)) {
                if (vector.size == index.dimensions) {
                    index.put(item.key, item.stamp, vector)
                    embedded++
                }
            }
            onBatch()
        }
        return Result(embedded, plan.remove.size, complete = todo.size == plan.embed.size)
    }

    /**
     * A stamp for a text: changes when the text does, and when [version]
     * does, which is how a new model or a new way of building the text makes
     * every item embed again.
     */
    fun stampOf(text: String, version: Int = 1): Long {
        var h = -0x340d631b7bdddcdbL xor version.toLong() // FNV-1a 64 offset basis
        for (c in text) {
            h = (h xor c.code.toLong()) * 0x100000001b3L
        }
        return h
    }
}

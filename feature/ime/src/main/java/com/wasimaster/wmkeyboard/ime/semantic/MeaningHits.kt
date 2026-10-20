package com.wasimaster.wmkeyboard.ime.semantic

import androidx.compose.runtime.Immutable

/**
 * What search by meaning found for the query in each search box, each list
 * tagged with the query it answers. A box shows a list only while its query
 * still reads the same, so an answer that arrives after more typing is
 * never drawn under the wrong words.
 *
 * Keys are the stores' own ids as strings: a clip's or note's or snippet's
 * id.
 */
@Immutable
data class MeaningHits(
    val clips: Found = Found.NONE,
    val notes: Found = Found.NONE,
    val snippets: Found = Found.NONE,
) {

    @Immutable
    data class Found(val query: String, val keys: List<String>) {
        /** The keys, when [query] is the query they were found for. */
        fun forQuery(query: String): List<String> = if (query.trim() == this.query && keys.isNotEmpty()) keys else emptyList()

        companion object {
            val NONE = Found("", emptyList())
        }
    }

    companion object {
        /**
         * [byWords] (the keyword matches, in their order) followed by what only
         * meaning found, best first, taken from [pool] by [idOf]. Only what
         * is in [pool] is added, so a filter, tab or folder the box is in
         * still holds.
         */
        fun <T> append(byWords: List<T>, pool: List<T>, meaning: List<String>, idOf: (T) -> String): List<T> {
            if (meaning.isEmpty()) return byWords
            val byId = HashMap<String, T>(pool.size * 2)
            for (item in pool) byId[idOf(item)] = item
            val seen = byWords.mapTo(HashSet(), idOf)
            val out = ArrayList<T>(byWords.size + meaning.size)
            out += byWords
            for (key in meaning) {
                val item = byId[key] ?: continue
                if (seen.add(key)) out += item
            }
            return out
        }
    }
}

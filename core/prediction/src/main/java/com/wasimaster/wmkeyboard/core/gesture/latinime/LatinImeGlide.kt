package com.wasimaster.wmkeyboard.core.gesture.latinime

import android.util.Log
import com.android.inputmethod.keyboard.ProximityInfo
import com.android.inputmethod.latin.BinaryDictionary
import com.android.inputmethod.latin.DicTraverseSession
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideDecoder
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideShapeSource
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.TrieWalker
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Google's gesture typing library as a glide engine: the user imported
 * `libjni_latinime.so` ([LatinImeLibrary]), and this reads strokes with it
 * through AOSP LatinIME's JNI interface (the stubs in
 * `com.android.inputmethod`).
 *
 * The library searches a dictionary of its own format, AOSP's version 4,
 * which it writes itself: [LatinImeLexicon] picks the words from the
 * language's lists, this fills an on-memory dictionary with them and has the
 * library flush it under [dictionaryDir], named by the word set's
 * fingerprint, so the next keyboard start opens it instead of building
 * again. The user's own words are added to the open dictionary in memory and
 * never written. Building runs on a thread of its own; until it is done, and
 * for a language with no lists, [decode] answers null and the built-in
 * decoder reads the stroke.
 *
 * The library's candidates then go through the keyboard's own blacklist,
 * context reranker and casing like any other engine's. What it does not get
 * from this keyboard: the look-ahead, loops and pauses read as marks, the
 * learned shapes, and the vocabulary cap. The second look an undo asks for
 * ([decode]'s `deep`) is the same search, since the library returns at most
 * [MAX_RESULTS] words either way.
 *
 * The native dictionary and session are not thread-safe, so every native
 * call happens under this object's lock.
 */
class LatinImeGlide(
    private val noBackupDir: File,
) : GlideDecoder {

    /**
     * The active language, as an AOSP locale string (`en_US`, `de`). The
     * keyboard service sets it before each decode; a change starts the new
     * language's dictionary.
     */
    @Volatile
    var language: String = ""

    private val dictionaryDir: File get() = LatinImeLibrary.dictionaryDir(noBackupDir)

    private val builder: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wm-latinime-dict").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /** One open native dictionary with its search session. */
    private class Open(
        val key: SourcesKey,
        val dict: Long,
        val session: Long,
        val personal: MutableSet<String> = HashSet(),
        var personalWalkers: List<TrieWalker> = emptyList(),
        var personalSyncedAt: Long = 0L,
    )

    /** Which lists a dictionary was built from: the language and the tries, by identity. */
    private class SourcesKey(val language: String, val walkers: List<TrieWalker>) {
        override fun equals(other: Any?): Boolean =
            other is SourcesKey && other.language == language &&
                other.walkers.size == walkers.size && other.walkers.indices.all { other.walkers[it] === walkers[it] }

        override fun hashCode(): Int = language.hashCode() * 31 + walkers.size
    }

    private var open: Open? = null

    /** The key a build is under way for, so a stroke does not queue a second one. */
    @Volatile
    private var building: SourcesKey? = null

    private var proximityKeys: GlideKeyMap? = null
    private var proximityWidth = 0f
    private var board: LatinImeKeyboard? = null
    private var proximity = 0L

    /** Whether a stroke has been read in this process yet; the first is on probation. */
    private var decodedOnce = false

    private val options = IntArray(OPTIONS_SIZE).also {
        it[IS_GESTURE] = 1
        it[WEIGHT_FOR_LOCALE_IN_THOUSANDS] = THOUSAND
    }
    private val inputCodePoints = IntArray(LatinImeLexicon.MAX_WORD_LENGTH)
    private val prevWords = arrayOfNulls<IntArray>(MAX_PREV_WORDS)
    private val prevWordStartsSentence = BooleanArray(MAX_PREV_WORDS)
    private val outCount = IntArray(1)
    private val outCodePoints = IntArray(LatinImeLexicon.MAX_WORD_LENGTH * MAX_RESULTS)
    private val outScores = IntArray(MAX_RESULTS)
    private val outSpaceIndices = IntArray(MAX_RESULTS)
    private val outTypes = IntArray(MAX_RESULTS)
    private val outAutoCommit = IntArray(1)
    private val langModelWeight = FloatArray(1)

    @Synchronized
    override fun decode(
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        sources: List<FuzzyBeamSearch.WalkSource>,
        limit: Int,
        deep: Boolean,
        vocabularyRank: Int,
        lookAhead: Int,
        shapes: GlideShapeSource?,
        tuning: GlideBeam.Tuning,
    ): List<GlideBeam.Candidate>? {
        if (!LatinImeLibrary.isLoaded || path.size < MIN_POINTS || keyWidth <= 0f) return null
        if (LatinImeGeometry.spanKeys(path, keyWidth) < MIN_STROKE_KEYS) return null
        val lists = sources.filter { it.tier == FuzzyBeamSearch.Tier.DICTIONARY }
        if (lists.isEmpty()) return null
        val key = SourcesKey(language, lists.map { it.walker })
        val ready = open
        if (ready == null || ready.key != key) schedule(key, lists)
        // A dictionary for the same language serves while a changed list is
        // rebuilt; another language's never does.
        val dict = ready?.takeIf { it.key.language == key.language } ?: return null
        syncPersonal(dict, sources)
        val proximityInfo = proximityFor(keys, keyWidth) ?: return null
        val trace = LatinImeGeometry.trace(path, board ?: return null) ?: return null
        val run = { search(dict, proximityInfo, trace) }
        if (decodedOnce) run() else LatinImeLibrary.probation(noBackupDir, run).also { decodedOnce = true }
        val personal = dict.personal
        return LatinImeGeometry.candidates(outCodePoints, outScores, outCount[0], LatinImeLexicon.MAX_WORD_LENGTH)
            .take(limit)
            .map { (word, score) ->
                val tier = if (word in personal) FuzzyBeamSearch.Tier.USER else FuzzyBeamSearch.Tier.DICTIONARY
                GlideBeam.Candidate(word, score, -score, tier)
            }
            .ifEmpty { null }
    }

    private fun search(dict: Open, proximityInfo: Long, trace: LatinImeTrace) {
        inputCodePoints.fill(LatinImeGeometry.NOT_A_CODE)
        outCount[0] = 0
        langModelWeight[0] = NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL
        BinaryDictionary.getSuggestionsNative(
            dict.dict, proximityInfo, dict.session,
            trace.xs, trace.ys, trace.times, trace.pointerIds,
            inputCodePoints, trace.size, options,
            // No context: the keyboard's own reranker reads the words around
            // the stroke afterwards, and this dictionary holds no word pairs.
            prevWords, prevWordStartsSentence, 0,
            outCount, outCodePoints, outScores, outSpaceIndices, outTypes,
            outAutoCommit, langModelWeight,
        )
    }

    /** The native key geometry for [keys], rebuilt when the grid changes. */
    private fun proximityFor(keys: GlideKeyMap, keyWidth: Float): Long? {
        if (keys === proximityKeys && keyWidth == proximityWidth && proximity != 0L) return proximity
        if (proximity != 0L) ProximityInfo.releaseProximityInfoNative(proximity)
        proximity = 0L
        proximityKeys = keys
        proximityWidth = keyWidth
        val layout = LatinImeGeometry.keyboard(keys, keyWidth)
        board = layout
        if (layout == null) return null
        proximity = ProximityInfo.setProximityInfoNative(
            layout.width, layout.height, layout.gridWidth, layout.gridHeight,
            layout.keyWidth, layout.keyHeight, layout.proximityChars, layout.keyCount,
            layout.keyX, layout.keyY, layout.keyWidths, layout.keyHeights, layout.codes,
            null, null, null,
        )
        return proximity.takeIf { it != 0L }
    }

    /**
     * Adds the user's words to [dict] in memory when the personal tries have
     * changed, or every [PERSONAL_RESYNC_MS] for a trie that grows in place,
     * on the builder thread so a stroke never waits for it. Words are only
     * ever added: one the user deleted stays until the next keyboard start,
     * and the blacklist keeps it off the strip meanwhile.
     */
    private fun syncPersonal(dict: Open, sources: List<FuzzyBeamSearch.WalkSource>) {
        val personal = sources.filter { it.tier == FuzzyBeamSearch.Tier.USER }
        val walkers = personal.map { it.walker }
        val now = System.currentTimeMillis()
        val same = walkers.size == dict.personalWalkers.size && walkers.indices.all { walkers[it] === dict.personalWalkers[it] }
        if (same && now - dict.personalSyncedAt < PERSONAL_RESYNC_MS) return
        dict.personalWalkers = walkers
        dict.personalSyncedAt = now
        builder.execute {
            // The personal trie may be written while this reads it; a torn
            // read is retried at the next sync rather than trusted.
            @Suppress("TooGenericExceptionCaught")
            val words = try {
                LatinImeLexicon.personalWords(personal)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Personal words changed while being read", e)
                dict.personalSyncedAt = 0L
                return@execute
            }
            synchronized(this) {
                if (open !== dict) return@synchronized
                for (word in words) {
                    if (word in dict.personal) continue
                    val added = BinaryDictionary.addUnigramEntryNative(
                        dict.dict, word.codePoints().toArray(), LatinImeLexicon.PERSONAL_PROBABILITY,
                        null, 0, false, false, false, NOT_A_VALID_TIMESTAMP,
                    )
                    if (added) dict.personal += word
                }
            }
        }
    }

    private fun schedule(key: SourcesKey, lists: List<FuzzyBeamSearch.WalkSource>) {
        if (building == key) return
        building = key
        builder.execute {
            try {
                val built = build(key, lists)
                synchronized(this) {
                    if (built != null) {
                        open?.let(::close)
                        open = built
                    }
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Gesture library dictionary failed", e)
            } finally {
                if (building == key) building = null
            }
        }
    }

    /**
     * Opens the dictionary for [lists], building it first if no earlier
     * start left one with the same words. Off the lock: the build is seconds
     * of native work on its own handle, which no stroke touches.
     */
    private fun build(key: SourcesKey, lists: List<FuzzyBeamSearch.WalkSource>): Open? {
        val words = LatinImeLexicon.dictionaryWords(lists)
        if (words.isEmpty()) return null
        val locale = key.language.ifEmpty { "en" }
        val name = "$locale-${LatinImeLexicon.fingerprint(locale, words)}"
        val root = dictionaryDir.apply { mkdirs() }
        val dir = File(root, "$name.dict")
        val ready = File(root, "$name.ready")
        if (!(dir.isDirectory && ready.isFile)) {
            ready.delete()
            dir.deleteRecursively()
            val written = LatinImeLibrary.probation(noBackupDir) { write(dir, locale, words) }
            if (!written) {
                dir.deleteRecursively()
                return null
            }
            ready.createNewFile()
            // One dictionary per language on disk: the one just built.
            root.listFiles()?.forEach { other ->
                if (other.name.startsWith("$locale-") && !other.name.startsWith("$name.")) other.deleteRecursively()
            }
        }
        val dict = BinaryDictionary.openNative(dir.absolutePath, 0, sizeOf(dir), true)
        if (dict == 0L) {
            ready.delete()
            dir.deleteRecursively()
            return null
        }
        val session = DicTraverseSession.setDicTraverseSessionNative(locale, sizeOf(dir))
        DicTraverseSession.initDicTraverseSessionNative(session, dict, null, 0)
        return Open(key, dict, session)
    }

    /**
     * Fills an on-memory dictionary with [words] and has the library write
     * it to [dir]. Most probable first, so when the library refuses words
     * near its size limit it is the rarest that miss; a garbage collection
     * through the disk compacts it whenever the library asks for one.
     */
    private fun write(dir: File, locale: String, words: List<LatinImeWord>): Boolean {
        val attributes = linkedMapOf(
            "dictionary" to "main:$locale",
            "locale" to locale,
            "version" to "1",
            "date" to (System.currentTimeMillis() / MILLIS).toString(),
            "description" to "WM Keyboard glide dictionary",
        )
        var dict = BinaryDictionary.createOnMemoryNative(
            FORMAT_VERSION_403, locale, attributes.keys.toTypedArray(), attributes.values.toTypedArray(),
        )
        if (dict == 0L) return false
        try {
            var refusedInARow = 0
            for ((i, w) in words.withIndex()) {
                val added = BinaryDictionary.addUnigramEntryNative(
                    dict, w.word.codePoints().toArray(), w.probability, null, 0, false, false, false, NOT_A_VALID_TIMESTAMP,
                )
                refusedInARow = if (added) 0 else refusedInARow + 1
                if (refusedInARow >= MAX_REFUSALS) break
                if (i % GC_CHECK_EVERY == GC_CHECK_EVERY - 1 && BinaryDictionary.needsToRunGCNative(dict, false)) {
                    if (!BinaryDictionary.flushWithGCNative(dict, dir.absolutePath)) return false
                    BinaryDictionary.closeNative(dict)
                    dict = BinaryDictionary.openNative(dir.absolutePath, 0, sizeOf(dir), true)
                    if (dict == 0L) return false
                }
            }
            return BinaryDictionary.flushWithGCNative(dict, dir.absolutePath)
        } finally {
            if (dict != 0L) BinaryDictionary.closeNative(dict)
        }
    }

    private fun close(dict: Open) {
        DicTraverseSession.releaseDicTraverseSessionNative(dict.session)
        BinaryDictionary.closeNative(dict.dict)
    }

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        /**
         * This app's language id (`en`, `pt_br`, `sr-Latn`) as the locale
         * string AOSP's dictionaries carry (`en`, `pt_BR`, `sr`): language,
         * then an upper-case region when there is one. A script subtag is
         * dropped; AOSP's locales never had one.
         */
        fun aospLocale(languageId: String): String {
            val parts = languageId.split('_', '-').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return ""
            val language = parts[0].lowercase()
            val region = parts.drop(1).firstOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }
            return if (region == null) language else "${language}_${region.uppercase()}"
        }

        private const val TAG = "LatinImeGlide"

        /** As the other engines: anything shorter is a tap. */
        private const val MIN_POINTS = 3
        private const val MIN_STROKE_KEYS = 0.5f

        /** `MAX_RESULTS` in the native code; the output arrays must be exactly this long. */
        private const val MAX_RESULTS = 18

        /** `MAX_PREV_WORD_COUNT_FOR_N_GRAM`. */
        private const val MAX_PREV_WORDS = 3

        /** AOSP's dynamic dictionary format, the one the library writes. */
        private const val FORMAT_VERSION_403 = 403L

        /** `NativeSuggestOptions`' slots. */
        private const val IS_GESTURE = 0
        private const val WEIGHT_FOR_LOCALE_IN_THOUSANDS = 4
        private const val OPTIONS_SIZE = 5
        private const val THOUSAND = 1000

        /** `Dictionary.NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL`: let the library choose. */
        private const val NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL = -1f

        private const val NOT_A_VALID_TIMESTAMP = -1
        private const val MILLIS = 1000L

        /** Refusals in a row that mean the dictionary is full, not that one word was odd. */
        private const val MAX_REFUSALS = 64

        /** How often the user's words are looked at again when their trie is the same object. */
        private const val PERSONAL_RESYNC_MS = 60_000L

        /** How often the build asks whether the library wants its buffers compacted. */
        private const val GC_CHECK_EVERY = 10_000
    }
}

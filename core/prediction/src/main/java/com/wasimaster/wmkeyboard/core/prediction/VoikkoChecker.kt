package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.flow.StateFlow

/**
 * The [WordChecker] over Voikko's Finnish dictionary, opened in libvoikko
 * ([VoikkoNative]). Finnish builds words the way German does and inflects
 * them fifteen ways on top, so most of what a Finn types is in no word list:
 * Voikko's morphology accepts `kahvinkeittimessäkin` and `taloissammekin`
 * because it knows the stems, the case endings and the clitics, and Hunspell
 * dictionaries for Finnish are a thin approximation of the same thing.
 *
 * Every call holds one lock, since a Voikko handle keeps a cache between
 * calls. [accepts] runs on the main thread at each space, so it waits a few
 * milliseconds for the lock at most and says no if a suggestion run holds it
 * longer: no worse than the keyboard without Voikko. Both answers are
 * remembered for the last words asked.
 */
class VoikkoChecker(
    private val backend: VoikkoBackend,
    private val handle: Long,
) : WordChecker, Closeable {

    /** A place [hyphenate] may break a word: before the UTF-16 [index], or at it, the character dropped for the hyphen. */
    data class HyphenPoint(val index: Int, val replacesChar: Boolean)

    private val lock = ReentrantLock()

    @Volatile
    private var closed = false

    private val accepted = BoundedCache<String, Boolean>(SPELL_CACHE)
    private val suggested = BoundedCache<String, List<String>>(SUGGEST_CACHE)

    override fun accepts(word: String): Boolean {
        if (!askable(word)) return false
        accepted.get(word)?.let { return it }
        if (!lock.tryLock(SPELL_WAIT_MS, TimeUnit.MILLISECONDS)) return false
        val result = try {
            !closed && backend.spell(handle, word.toByteArray(Charsets.UTF_8))
        } finally {
            lock.unlock()
        }
        accepted.put(word, result)
        return result
    }

    override fun suggestions(word: String, limit: Int): List<String> {
        if (!askable(word) || limit <= 0) return emptyList()
        suggested.get(word)?.let { return it.take(limit) }
        val found = locked { backend.suggest(handle, word.toByteArray(Charsets.UTF_8), SUGGESTIONS_ASKED) }
            ?: return emptyList()
        val list = found.map { String(it, Charsets.UTF_8).trim() }.filter { it.isNotEmpty() && it != word }.distinct()
        suggested.put(word, list)
        return list.take(limit)
    }

    /**
     * Where [word] may be hyphenated, by Voikko's own rules for Finnish
     * (compound boundaries first, then syllables), or null when it cannot
     * say. Blocks behind a suggestion run, so not for the main thread. Not
     * used by the keyboard yet: here for a hyphenating text tool.
     */
    fun hyphenate(word: String): List<HyphenPoint>? {
        if (!askable(word)) return null
        val pattern = locked { backend.hyphenate(handle, word.toByteArray(Charsets.UTF_8)) } ?: return null
        // The pattern has one character per code point; the caller counts in UTF-16 units.
        val out = ArrayList<HyphenPoint>()
        var index = 0
        var point = 0
        while (index < word.length && point < pattern.length) {
            when (pattern[point]) {
                '-' -> out += HyphenPoint(index, replacesChar = false)
                '=' -> out += HyphenPoint(index, replacesChar = true)
            }
            index += Character.charCount(word.codePointAt(index))
            point++
        }
        return out
    }

    /** Frees the dictionary, after any call in progress finishes. */
    override fun close() {
        lock.lock()
        try {
            if (closed) return
            closed = true
            backend.close(handle)
        } finally {
            lock.unlock()
        }
    }

    private inline fun <T> locked(block: () -> T?): T? {
        lock.lock()
        try {
            return if (closed) null else block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Words with no letter in them are left alone (the list and the number
     * paths own those), and a word past this length is a paste.
     */
    private fun askable(word: String): Boolean = word.length <= MAX_WORD_LENGTH && word.any(Char::isLetter)

    /** A small least-recently-used map, safe across the strip's two threads. */
    private class BoundedCache<K, V>(private val capacity: Int) {
        private val map = object : LinkedHashMap<K, V>(capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
        }

        @Synchronized
        fun get(key: K): V? = map[key]

        @Synchronized
        fun put(key: K, value: V) {
            map[key] = value
        }
    }

    companion object {
        /** How long [accepts] waits for a suggestion run before saying no; Hunspell's budget. */
        const val SPELL_WAIT_MS = HunspellChecker.SPELL_WAIT_MS
        const val MAX_WORD_LENGTH = HunspellChecker.MAX_WORD_LENGTH
        private const val SUGGESTIONS_ASKED = 8
        private const val SPELL_CACHE = 256
        private const val SUGGEST_CACHE = 32
        private const val LOAD_FACTOR = 0.75f
    }
}

/**
 * Voikko for the keyboard and the settings screen: whether the library is
 * here, where a language's dictionary lives, and a checker over it. The
 * dictionary is a folder (`5/mor-standard/` with `index.txt`, `mor.vfst` and
 * `autocorr.vfst`) in the language's `dict/<langId>/voikko`, downloaded from
 * [com.wasimaster.wmkeyboard.core.dictionaries.VoikkoDictionaryCatalog] or
 * imported by the user.
 *
 * Where a language has a Voikko dictionary the keyboard checks with it in
 * place of an imported Hunspell one, never beside it: Voikko is the better
 * speller for Finnish, and two of them would only double the memory.
 */
object VoikkoRuntime {
    /** The keyboard's languages libvoikko has a dictionary format for, with the tag it is opened with. */
    private val TAGS = mapOf("fi" to "fi")

    /** Whether [langId] can be checked with Voikko at all. */
    fun supports(langId: String): Boolean = langId in TAGS

    /** Where the library's module stands: the same module as Hunspell's. */
    val moduleState: StateFlow<ModuleState> get() = FeatureModules.spell.state

    /** Whether the library's module is on the device, without loading it. */
    val moduleReady: Boolean get() = FeatureModules.spell.installed

    /** Asks for the library's module. A no-op where it is in the APK. */
    fun requestModule() = FeatureModules.spell.requestInstall()

    /**
     * Whether the library is loaded, loading it if it is here. False on the
     * offline build even though its spell module counts as installed: that
     * build packages Hunspell and divvunspell but never Voikko.
     */
    val available: Boolean get() = VoikkoNative.available

    /** Whether this build can have the library at all, fetched or not. */
    val shipped: Boolean get() = VoikkoNative.shipped

    internal val backend: VoikkoBackend get() = VoikkoNative

    /** The folder libvoikko is pointed at for [langId]. */
    fun dictionaryDir(filesDir: File, langId: String): File = File(File(File(filesDir, "dict"), langId), "voikko")

    /** The files of [langId]'s dictionary, empty when it has none. */
    fun dictionaryFiles(dir: File): List<File> =
        File(dir, FORMAT_DIR).listFiles { f -> f.isDirectory && f.name.startsWith("mor-") }
            .orEmpty()
            .sortedBy { it.name }
            .filter { File(it, "index.txt").isFile }
            .flatMap { variant -> variant.listFiles().orEmpty().filter(File::isFile).sortedBy { it.name } }

    /** Whether [langId] has a dictionary on the device. */
    fun hasDictionary(filesDir: File, langId: String): Boolean =
        supports(langId) && dictionaryFiles(dictionaryDir(filesDir, langId)).isNotEmpty()

    /**
     * A checker over [langId]'s dictionary, or null when it has none, the
     * library is missing, or the dictionary does not load. Maps the
     * transducers rather than reading them: cheap, but call it off the main
     * thread.
     */
    fun open(filesDir: File, langId: String): VoikkoChecker? {
        val tag = TAGS[langId] ?: return null
        if (!hasDictionary(filesDir, langId) || !moduleReady || !available) return null
        val handle = VoikkoNative.open(tag, dictionaryDir(filesDir, langId).path)
        return if (handle == 0L) null else VoikkoChecker(VoikkoNative, handle)
    }

    /** Opens the dictionary under [dir] for [langId] and closes it again: whether it loads. */
    internal fun opens(backend: VoikkoBackend, dir: File, langId: String): Boolean {
        val tag = TAGS[langId] ?: return false
        val handle = backend.open(tag, dir.path)
        if (handle == 0L) return false
        backend.close(handle)
        return true
    }

    /** What [open] would read, so a caller can tell whether a checker it holds is still current. */
    fun signatureOf(filesDir: File, langId: String): String =
        if (!supports(langId)) {
            ""
        } else {
            dictionaryFiles(dictionaryDir(filesDir, langId))
                .joinToString("\n") { "${it.path}:${it.length()}:${it.lastModified()}" }
        }

    /** libvoikko's folder for dictionary format 5, the VFST one. */
    const val FORMAT_DIR = "5"
}

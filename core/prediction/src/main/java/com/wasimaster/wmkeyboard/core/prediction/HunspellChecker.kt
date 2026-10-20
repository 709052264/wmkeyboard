package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.flow.StateFlow

/**
 * The [WordChecker] over one language's imported Hunspell dictionaries: the
 * `.aff` and `.dic` an import keeps beside its list
 * ([CustomDictionaries.hunspellFiles]), opened in the native library.
 *
 * A word is accepted when any of the dictionaries accepts it, and the
 * spellings come from each in turn. Every call holds one lock, since a
 * Hunspell object keeps scratch state between calls. [accepts] runs on the
 * main thread at each space, so it waits a few milliseconds for the lock at
 * most and says no if a suggestion run holds it longer: no worse than the
 * keyboard without Hunspell. Both answers are remembered for the last words
 * asked, because the strip asks about the same word on its quick pass and its
 * full one.
 */
class HunspellChecker(
    private val backend: HunspellBackend,
    dictionaries: List<Pair<Long, Charset>>,
) : WordChecker, Closeable {

    private class Dictionary(val handle: Long, val charset: Charset) {
        /** Only used under [lock]: an encoder is not thread-safe. */
        val encoder: CharsetEncoder = charset.newEncoder()
    }

    private val dictionaries = dictionaries.map { (handle, charset) -> Dictionary(handle, charset) }
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
            !closed && dictionaries.any { d -> encode(d, word)?.let { backend.spell(d.handle, it) } == true }
        } finally {
            lock.unlock()
        }
        accepted.put(word, result)
        return result
    }

    override fun suggestions(word: String, limit: Int): List<String> {
        if (!askable(word) || limit <= 0) return emptyList()
        suggested.get(word)?.let { return it.take(limit) }
        val out = LinkedHashSet<String>()
        lock.lock()
        try {
            if (closed) return emptyList()
            for (d in dictionaries) {
                val bytes = encode(d, word) ?: continue
                val found = backend.suggest(d.handle, bytes, SUGGESTIONS_ASKED) ?: continue
                for (spelling in found) {
                    val text = String(spelling, d.charset).trim()
                    if (text.isNotEmpty() && text != word) out += text
                }
            }
        } finally {
            lock.unlock()
        }
        val list = out.toList()
        suggested.put(word, list)
        return list.take(limit)
    }

    /** Frees the dictionaries, after any call in progress finishes. */
    override fun close() {
        lock.lock()
        try {
            if (closed) return
            closed = true
            for (d in dictionaries) backend.close(d.handle)
        } finally {
            lock.unlock()
        }
    }

    /**
     * [word] in [d]'s encoding, or null when a letter has no place in it: an
     * ISO 8859-1 dictionary has nothing to say about a Cyrillic word.
     */
    private fun encode(d: Dictionary, word: String): ByteArray? = try {
        val buffer: ByteBuffer = d.encoder.reset().encode(CharBuffer.wrap(word))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (_: CharacterCodingException) {
        null
    }

    /** Hunspell's own limit is 100 bytes; a word past this is a paste, not a word. */
    private fun askable(word: String): Boolean = word.isNotBlank() && word.length <= MAX_WORD_LENGTH

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
        /** How long [accepts] waits for a suggestion run on another thread before saying no. */
        const val SPELL_WAIT_MS = 15L
        const val MAX_WORD_LENGTH = 60
        private const val SUGGESTIONS_ASKED = 8
        private const val SPELL_CACHE = 256
        private const val SUGGEST_CACHE = 32
        private const val LOAD_FACTOR = 0.75f

        /**
         * Opens every pair in [files] that the library reads and whose
         * encoding this platform has, or null when none is left.
         */
        fun open(backend: HunspellBackend, files: List<CustomDictionaries.HunspellFiles>): HunspellChecker? {
            val opened = ArrayList<Pair<Long, Charset>>()
            for (pair in files) {
                val handle = backend.open(pair.aff.path, pair.dic.path)
                if (handle == 0L) continue
                val charset = HunspellEncoding.charsetFor(backend.encoding(handle))
                if (charset == null) {
                    backend.close(handle)
                    continue
                }
                opened += handle to charset
            }
            return if (opened.isEmpty()) null else HunspellChecker(backend, opened)
        }
    }
}

/**
 * Hunspell's encoding names (`SET` in an `.aff`) as Java charsets. Hunspell
 * spells ISO 8859 without the first hyphen and Windows code pages its own
 * way, and a dictionary with no `SET` is ISO 8859-1.
 */
object HunspellEncoding {
    private val ISO_8859 = Regex("(?i)iso-?8859-(\\d+)")
    private val ALIASES = mapOf(
        "microsoft-cp1251" to "windows-1251",
        "microsoft-cp1250" to "windows-1250",
        "microsoft-cp1252" to "windows-1252",
        "tis620-2533" to "TIS-620",
    )

    /** The charset [name] means, or null when this platform has none by that name. */
    fun charsetFor(name: String): Charset? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Charsets.ISO_8859_1
        val javaName = ISO_8859.matchEntire(trimmed)?.let { "ISO-8859-${it.groupValues[1]}" }
            ?: ALIASES[trimmed.lowercase()]
            ?: trimmed
        return runCatching { Charset.forName(javaName) }.getOrNull()
    }
}

/**
 * Hunspell for the keyboard and the settings screen: whether the library is
 * here, how to fetch it, and a checker over a language's dictionaries.
 */
object HunspellRuntime {

    /** Where the library's module stands. Always installed where the APK carries it. */
    val moduleState: StateFlow<ModuleState> get() = FeatureModules.spell.state

    /** Whether the library is on the device, without loading it. */
    val moduleReady: Boolean get() = FeatureModules.spell.installed

    /** Asks for the library's module. A no-op where it is in the APK. */
    fun requestModule() = FeatureModules.spell.requestInstall()

    /** Whether the library is loaded, loading it if it is here. */
    val available: Boolean get() = HunspellNative.available

    /** Whether this build has the library at all, fetched or not. */
    val shipped: Boolean get() = HunspellNative.shipped

    /**
     * A checker over [files], or null when there are none, the library is
     * missing, or none of them opened. Reads both files whole: call it off the
     * main thread.
     */
    fun open(files: List<CustomDictionaries.HunspellFiles>): HunspellChecker? {
        if (files.isEmpty() || !moduleReady || !available) return null
        return HunspellChecker.open(HunspellNative, files)
    }

    /** What [open] would read, so a caller can tell whether a checker it holds is still current. */
    fun signatureOf(files: List<CustomDictionaries.HunspellFiles>): String =
        files.joinToString("\n") { f ->
            listOf(f.aff, f.dic).joinToString("\t") { "${it.path}:${it.length()}:${it.lastModified()}" }
        }
}

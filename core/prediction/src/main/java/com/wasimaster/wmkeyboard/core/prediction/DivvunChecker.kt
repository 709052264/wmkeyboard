package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import java.io.Closeable
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.flow.StateFlow

/**
 * The [WordChecker] over one language's GiellaLT speller: a finite-state
 * archive (`.bhfst`) that knows every inflected and compounded form of a
 * morphologically rich language, opened in divvunspell ([DivvunNative]).
 * North Sámi, Greenlandic, Faroese and the other languages GiellaLT builds
 * for make far more forms than a word list can hold, which is the same gap
 * Hunspell fills for German compounds.
 *
 * The speller can be read from several threads at once, so calls share a
 * read lock and only [close] takes the write lock, waiting for a suggestion
 * run to finish before it frees the archive. Checking a word walks a memory
 * map, and the first walk through a part of the archive the phone has not
 * paged in yet can take a while; [accepts] runs on the main thread at each
 * space, so it hands the walk to its own thread and waits a few milliseconds
 * at most. A word it gives up on is answered no, which is the keyboard
 * without a speller, and the walk finishes anyway, so the answer is there the
 * next time the strip asks. Both answers are remembered for the last words
 * asked.
 */
class DivvunChecker(
    private val backend: DivvunBackend,
    private val handle: Long,
) : WordChecker, Closeable {

    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var closed = false

    private val accepted = BoundedCache<String, Boolean>(SPELL_CACHE)
    private val suggested = BoundedCache<String, List<String>>(SUGGEST_CACHE)

    /** Checks in progress, so a word asked twice while its walk runs waits on the one walk. */
    private val pending = HashMap<String, Future<Boolean>>()

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "divvun-check").apply { isDaemon = true }
    }

    override fun accepts(word: String): Boolean {
        if (!askable(word)) return false
        accepted.get(word)?.let { return it }
        val future = try {
            synchronized(pending) { pending.getOrPut(word) { worker.submit(Callable { check(word) }) } }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return false
        }
        return try {
            future.get(SPELL_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            false
        } catch (_: java.util.concurrent.ExecutionException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun check(word: String): Boolean {
        val result = try {
            lock.read { !closed && backend.isCorrect(handle, word) }
        } finally {
            synchronized(pending) { pending.remove(word) }
        }
        accepted.put(word, result)
        return result
    }

    override fun suggestions(word: String, limit: Int): List<String> {
        if (!askable(word) || limit <= 0) return emptyList()
        suggested.get(word)?.let { return it.take(limit) }
        val found = lock.read {
            if (closed) return emptyList()
            backend.suggest(handle, word, SUGGESTIONS_ASKED)
        } ?: return emptyList()
        val list = found.map { it.trim() }.filter { it.isNotEmpty() && it != word }.distinct()
        suggested.put(word, list)
        return list.take(limit)
    }

    /** Frees the archive, after any call in progress finishes. */
    override fun close() {
        worker.shutdown()
        lock.write {
            if (closed) return
            closed = true
            backend.close(handle)
        }
    }

    /**
     * Words with no letter in them are left alone (the speller calls every
     * one of them right), and a word past this length is a paste.
     */
    private fun askable(word: String): Boolean =
        word.length <= MAX_WORD_LENGTH && word.any(Char::isLetter)

    /** A small least-recently-used map, safe across the strip's threads. */
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
        /** How long [accepts] waits for its walk before saying no; the same budget as Hunspell's. */
        const val SPELL_WAIT_MS = HunspellChecker.SPELL_WAIT_MS
        const val MAX_WORD_LENGTH = HunspellChecker.MAX_WORD_LENGTH
        private const val SUGGESTIONS_ASKED = 8
        private const val SPELL_CACHE = 256
        private const val SUGGEST_CACHE = 32
        private const val LOAD_FACTOR = 0.75f
    }
}

/**
 * GiellaLT spellers for the keyboard and the settings screen: whether the
 * library is here, where a language's speller lives, and a checker over it.
 * One speller per language, downloaded from [com.wasimaster.wmkeyboard.core.dictionaries.DivvunSpellerCatalog]
 * or imported by the user, in the language's `dict/<langId>` folder beside
 * its downloaded word list.
 */
object DivvunRuntime {
    /** The two archive kinds divvunspell opens; it tells them apart by extension. */
    private val NAMES = listOf("speller.bhfst", "speller.zhfst")

    /** Where the library's module stands: the same module as Hunspell's. */
    val moduleState: StateFlow<ModuleState> get() = FeatureModules.spell.state

    /** Whether the library is on the device, without loading it. */
    val moduleReady: Boolean get() = FeatureModules.spell.installed

    /** Asks for the library's module. A no-op where it is in the APK. */
    fun requestModule() = FeatureModules.spell.requestInstall()

    /** Whether the library is loaded, loading it if it is here. */
    val available: Boolean get() = DivvunNative.available

    /** Whether this build has the library at all, fetched or not. */
    val shipped: Boolean get() = DivvunNative.shipped

    internal val backend: DivvunBackend get() = DivvunNative

    fun languageDir(filesDir: File, langId: String): File = File(File(filesDir, "dict"), langId)

    /** Where a `.bhfst` for [langId] goes. */
    fun bhfstFile(filesDir: File, langId: String): File = File(languageDir(filesDir, langId), NAMES[0])

    /** Where a `.zhfst` for [langId] goes. */
    fun zhfstFile(filesDir: File, langId: String): File = File(languageDir(filesDir, langId), NAMES[1])

    /** [langId]'s speller, if it has one. */
    fun spellerFile(filesDir: File, langId: String): File? =
        NAMES.map { File(languageDir(filesDir, langId), it) }.firstOrNull { it.isFile }

    /**
     * A checker over [langId]'s speller, or null when it has none, the
     * library is missing, or the archive does not open. Maps the archive
     * rather than reading it: cheap, but call it off the main thread.
     */
    fun open(filesDir: File, cacheDir: File, langId: String): DivvunChecker? {
        val file = spellerFile(filesDir, langId) ?: return null
        if (!moduleReady || !available) return null
        val handle = DivvunNative.open(file.path, cacheDir.path)
        return if (handle == 0L) null else DivvunChecker(DivvunNative, handle)
    }

    /** What [open] would read, so a caller can tell whether a checker it holds is still current. */
    fun signatureOf(filesDir: File, langId: String): String =
        spellerFile(filesDir, langId)?.let { "${it.path}:${it.length()}:${it.lastModified()}" }.orEmpty()
}

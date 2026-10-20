package com.wasimaster.wmkeyboard.core.xlit

import android.content.Context
import android.util.Log
import android.util.LruCache
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * IndicXlit's readings of a word typed on an Indic phonetic layout: what
 * someone typing `bhalobashi` most likely meant in Bengali letters, from a
 * network that learned it from millions of romanized words (see
 * [IndicXlitModels]).
 *
 * Runs on a thread of its own, never the one keys are handled on, and one
 * word at a time: [request] starts a word and drops the one before it if it
 * has not begun, since a keystroke has made it stale. The suggestion pass
 * waits for the answer only as long as its budget ([await]); a late answer
 * is kept for the next pass over the same word. Nothing here downloads
 * anything: without the model or the runtime every call answers nothing.
 */
class IndicXlit(context: Context) {

    private val dir: File = context.applicationContext.noBackupFilesDir
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wm-xlit").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val cache = LruCache<String, List<String>>(CACHE_WORDS)

    // Only touched on [worker].
    private var handle = 0L
    private var vocab: IndicXlitVocab? = null
    private var failedOpen = false

    @Volatile
    private var pending: Future<List<String>>? = null

    @Volatile
    private var pendingKey: String? = null

    /**
     * Starts reading [roman] for [languageId] (which must be one of
     * [IndicXlitModels.LANGUAGES]); the answer is the readings best first.
     * A request already waiting for the worker is cancelled.
     */
    fun request(languageId: String, roman: String): Future<List<String>> {
        val key = "$languageId\u0000$roman"
        cache.get(key)?.let { return CompletableFuture.completedFuture(it) }
        // The same word asked again while it is still running (the space bar
        // waiting on the strip's request): that one, rather than starting over.
        pending?.takeIf { pendingKey == key && !it.isCancelled }?.let { return it }
        pending?.cancel(false)
        val future = worker.submit<List<String>> {
            // Not cached when the model could not run: it may be here for the next word.
            run(languageId, roman)?.also { cache.put(key, it) }.orEmpty()
        }
        pending = future
        pendingKey = key
        return future
    }

    /**
     * What [future] holds within [budgetMs]. Empty when there are no readings,
     * the model is not here, or the budget ran out first.
     */
    fun await(future: Future<List<String>>, budgetMs: Long): List<String> = try {
        future.get(budgetMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        emptyList()
    } catch (_: CancellationException) {
        emptyList()
    } catch (_: ExecutionException) {
        emptyList()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        emptyList()
    }

    /** Frees the model; the next request opens it again. */
    fun release() {
        pending?.cancel(false)
        cache.evictAll()
        worker.execute { close() }
    }

    /** For good: the service is going away. */
    fun shutdown() {
        pending?.cancel(false)
        worker.execute { close() }
        worker.shutdown()
    }

    /** The readings of [roman], or null when the model is not here to ask. */
    private fun run(languageId: String, roman: String): List<String>? {
        if (!IndicXlitModels.supports(languageId) || roman.isEmpty() || roman.length > MAX_LETTERS) return emptyList()
        if (!open()) return null
        val v = vocab ?: return null
        val src = v.encode(languageId, roman) ?: return emptyList()
        val packed = XlitNative.nativeTransliterate(handle, src, v.allowed(languageId), BEAM, 2 * roman.length + 6)
            ?: return null
        return IndicXlitVocab.unpack(packed, v).map { it.first }.filter { it.isNotEmpty() }.distinct()
    }

    private fun open(): Boolean {
        if (handle != 0L) return true
        if (failedOpen || !IndicXlitModels.isDownloaded(dir) || !XlitNative.load()) return false
        return try {
            vocab = IndicXlitModels.file(dir, IndicXlitModels.Part.VOCAB).useLines(block = IndicXlitVocab::parse)
            handle = XlitNative.nativeOpen(
                IndicXlitModels.file(dir, IndicXlitModels.Part.ENCODER).path,
                IndicXlitModels.file(dir, IndicXlitModels.Part.DECODER).path,
                THREADS,
            )
            if (handle == 0L) failedOpen = true
            handle != 0L
        } catch (e: Exception) {
            Log.w(TAG, "could not open IndicXlit", e)
            failedOpen = true
            false
        }
    }

    private fun close() {
        if (handle != 0L) XlitNative.nativeClose(handle)
        handle = 0L
        vocab = null
        failedOpen = false
    }

    companion object {
        private const val TAG = "IndicXlit"

        /**
         * Four readings a word. The strip takes three at most, and measured on
         * held-out words a wider beam moves top-4 by about a point for half as
         * much time again.
         */
        const val BEAM = 4

        /** One thread: the keyboard's own work matters more than this. */
        private const val THREADS = 1

        /** Words longer than this are pasted text, not typing. */
        private const val MAX_LETTERS = 32

        private const val CACHE_WORDS = 256
    }
}

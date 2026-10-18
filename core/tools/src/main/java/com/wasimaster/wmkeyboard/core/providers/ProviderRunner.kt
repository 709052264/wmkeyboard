package com.wasimaster.wmkeyboard.core.providers

import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ToolHttpException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Asks a tool's providers, the same way for every tool.
 *
 * [fallback] walks the list until one answers with something, which is what
 * `DictionaryLookup.resolve` and `CurrencyClient.fetchRates` each did on their
 * own. [all] asks every provider at once for a mixed grid, keeping whatever
 * came back.
 *
 * A provider that could not be reached is left out of the walk for
 * [COOLDOWN_MS], so a service that is down costs one timeout rather than one
 * per keystroke. Only while another provider is there to ask: when every
 * provider is cooling down, they are all asked anyway.
 */
object ProviderRunner {

    sealed interface Outcome<out T> {
        /** [key] is the [ProviderEntry.key] of the provider that answered. */
        data class Found<T>(val value: T, val key: String) : Outcome<T>

        /** Every provider asked answered, and none had anything. */
        data object NotFound : Outcome<Nothing>

        /** No provider could be asked; [error] is the last one's failure, null for an empty list. */
        data class Failed(val error: Throwable?) : Outcome<Nothing>
    }

    const val COOLDOWN_MS = 60_000L

    /** Monotonic milliseconds; a test sets its own. */
    @Volatile
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    @PublishedApi
    internal val failedAt = ConcurrentHashMap<String, Long>()

    /** Whether a failure says the service is away, rather than that this one request was wrong. */
    fun unreachable(t: Throwable): Boolean =
        ToolHttp.isUnreachable(t) || (t is ToolHttpException && t.status == HTTP_TOO_MANY_REQUESTS)

    fun coolingDown(key: String, now: Long = clock()): Boolean {
        val at = failedAt[key] ?: return false
        if (now - at < COOLDOWN_MS) return true
        failedAt.remove(key, at)
        return false
    }

    /** Forgets every cooldown: after the user edits a provider, its next request should be a real one. */
    fun reset(key: String? = null) {
        if (key == null) failedAt.clear() else failedAt.remove(key)
    }

    /**
     * The first answer that is not [isEmpty], from [providers] in order.
     * A provider that throws or answers empty falls through to the next.
     *
     * Inline so the same walk serves blocking clients and suspending ones:
     * [call] runs in the caller's own context, whichever that is.
     */
    inline fun <P, T> fallback(
        providers: List<P>,
        key: (P) -> String,
        isEmpty: (T) -> Boolean = { false },
        cool: (Throwable) -> Boolean = ::unreachable,
        call: (P) -> T,
    ): Outcome<T> {
        if (providers.isEmpty()) return Outcome.Failed(null)
        val now = clock()
        val order = providers.filterNot { coolingDown(key(it), now) }.ifEmpty { providers }
        var answered = false
        var last: Throwable? = null
        for (provider in order) {
            val id = key(provider)
            val value = try {
                call(provider)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (cool(e)) failedAt[id] = clock()
                continue
            }
            failedAt.remove(id)
            answered = true
            if (!isEmpty(value)) return Outcome.Found(value, id)
        }
        return if (answered) Outcome.NotFound else Outcome.Failed(last)
    }

    /** Every provider at once, each answer or failure in the list's order. */
    suspend fun <P, T> all(
        providers: List<P>,
        key: (P) -> String,
        cool: (Throwable) -> Boolean = ::unreachable,
        call: suspend (P) -> T,
    ): List<Pair<P, Result<T>>> = coroutineScope {
        providers.map { provider ->
            async {
                val id = key(provider)
                val result = try {
                    Result.success(call(provider)).also { failedAt.remove(id) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (cool(e)) failedAt[id] = clock()
                    Result.failure(e)
                }
                provider to result
            }
        }.awaitAll()
    }

    /** Round robin over several result lists, so no provider owns the top of a mixed grid. */
    fun <T> interleave(lists: List<List<T>>): List<T> {
        val iterators = lists.map { it.iterator() }
        val merged = ArrayList<T>(lists.sumOf { it.size })
        while (iterators.any { it.hasNext() }) {
            for (iterator in iterators) {
                if (iterator.hasNext()) merged += iterator.next()
            }
        }
        return merged
    }

    private const val HTTP_TOO_MANY_REQUESTS = 429
}

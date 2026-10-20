package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections

/**
 * Every phase [phase] passes through, with the time since the log began. A
 * socket test that only times out on CI said nothing about where it stalled;
 * with this, the failure names the phases it saw on the way.
 */
internal class PhaseLog(scope: CoroutineScope, phase: StateFlow<HaPhase>) {
    private val start = System.nanoTime()
    private val seen = Collections.synchronizedList(mutableListOf<String>())

    init {
        scope.launch { phase.collect { seen += "${(System.nanoTime() - start) / NANOS_PER_MS}ms $it" } }
    }

    override fun toString(): String = synchronized(seen) { seen.joinToString(", ") }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** [block] under a timeout that, when it runs out, fails with what [log] saw. */
internal fun <T> withinTimeout(log: PhaseLog, timeoutMs: Long = 10_000, block: suspend CoroutineScope.() -> T): T =
    try {
        runBlocking { withTimeout(timeoutMs, block) }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("timed out after $timeoutMs ms; phases: $log", e)
    }

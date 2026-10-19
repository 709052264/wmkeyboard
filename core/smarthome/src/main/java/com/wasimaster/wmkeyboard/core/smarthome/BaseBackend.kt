package com.wasimaster.wmkeyboard.core.smarthome

import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaInstance
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaRegistry
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeBackend
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * What the REST-shaped adapters share: the flows, the start/stop/refresh
 * lifecycle, and a connection loop that runs one [session] after another
 * with a backoff between them. A session loads everything, then either
 * listens to the platform's event stream or polls until it fails; a refused
 * key ends the loop with [HaPhase.TOKEN_REJECTED], anything else with
 * [HaPhase.UNREACHABLE] and a retry.
 *
 * [account] is read at the start of every request, so a refreshed token or
 * a changed address needs no restart beyond [update]'s.
 */
abstract class BaseBackend(
    override val key: String,
    override val kind: HomeKind,
    initial: HomeAccount,
    protected val scope: CoroutineScope,
) : HomeBackend {

    @Volatile protected var account: HomeAccount = initial
        private set

    private val _phase = MutableStateFlow(HaPhase.OFFLINE)
    override val phase: StateFlow<HaPhase> = _phase.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    override val entities: StateFlow<Map<String, HaEntity>> = _entities.asStateFlow()

    private val _changes = MutableSharedFlow<Set<String>>(extraBufferCapacity = CHANGE_BUFFER)
    override val changes: SharedFlow<Set<String>> = _changes.asSharedFlow()

    private val _registry = MutableStateFlow(HaRegistry())
    override val registry: StateFlow<HaRegistry> = _registry.asStateFlow()

    private val _instance = MutableStateFlow<HaInstance?>(null)
    override val instance: StateFlow<HaInstance?> = _instance.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private var runner: Job? = null

    val isRunning: Boolean get() = runner?.isActive == true

    // ---- Lifecycle -----------------------------------------------------------

    override fun start() {
        if (isRunning) return
        if (!account.ready) return
        runner = scope.launch { run() }
    }

    override fun stop() {
        runner?.cancel()
        runner = null
        _phase.value = HaPhase.OFFLINE
    }

    override fun refresh() {
        val running = isRunning
        stop()
        if (running) start()
    }

    override fun clear() {
        _entities.value = emptyMap()
        _registry.value = HaRegistry()
        _instance.value = null
        _error.value = null
    }

    override fun update(account: HomeAccount): Boolean {
        val previous = this.account
        this.account = account
        val changed = !sameConnection(previous, account)
        if (changed) {
            val running = isRunning
            stop()
            clear()
            if (running) start()
        }
        return changed
    }

    /**
     * Whether the two credentials reach the same server with the same sign-in.
     * The address and the key, unless a kind says more; not the whole of
     * `extra`, where the hub keeps what it learns (the home's name), and
     * writing that back would restart the connection that learnt it.
     */
    protected open fun sameConnection(a: HomeAccount, b: HomeAccount): Boolean = a.url == b.url && a.apiKey == b.apiKey

    // ---- The loop ------------------------------------------------------------

    private suspend fun run() {
        var backoff = 0
        while (scope.isActive && runner?.isActive == true) {
            _phase.value = HaPhase.CONNECTING
            val outcome = try {
                session()
                _error.value = null
                backoff = 0
                Outcome.ENDED
            } catch (e: CancellationException) {
                throw e
            } catch (e: HomeHttpException) {
                _error.value = e.message
                if (e.tokenRejected) Outcome.REJECTED else Outcome.FAILED
            } catch (e: IOException) {
                _error.value = e.message
                Outcome.FAILED
            } catch (e: IllegalStateException) {
                // A reply the parser could not read: the server is there, the API is not what we expected.
                _error.value = e.message
                Outcome.FAILED
            }
            if (outcome == Outcome.REJECTED) {
                _phase.value = HaPhase.TOKEN_REJECTED
                return
            }
            _phase.value = HaPhase.UNREACHABLE
            delay(BACKOFF_MS[backoff.coerceAtMost(BACKOFF_MS.lastIndex)])
            backoff++
        }
    }

    private enum class Outcome { ENDED, FAILED, REJECTED }

    /**
     * One connection: load everything, publish, then watch (an event stream)
     * or poll until the platform drops us. Returns when the session ended
     * cleanly, throws on failure.
     */
    protected abstract suspend fun session()

    /** Sets the phase a live session runs in: [HaPhase.LIVE] for a stream, [HaPhase.REST_FALLBACK] while polling. */
    protected fun live(phase: HaPhase) {
        _phase.value = phase
    }

    protected fun setRegistry(registry: HaRegistry) {
        _registry.value = registry
    }

    protected fun setInstance(instance: HaInstance?) {
        _instance.value = instance
    }

    protected fun setError(message: String?) {
        _error.value = message
    }

    /** The whole map at once; what differs from the last one pulses. */
    protected fun publish(next: Map<String, HaEntity>) {
        val stamped = next.mapValues { (_, e) -> if (e.home == key) e else e.copy(home = key) }
        val previous = _entities.value
        val changed = LinkedHashSet<String>()
        for ((id, e) in stamped) if (previous[id] != e) changed += id
        for (id in previous.keys) if (id !in stamped) changed += id
        _entities.value = stamped
        if (changed.isNotEmpty()) _changes.tryEmit(changed)
    }

    /** A few entities over the current map. */
    protected fun merge(some: Map<String, HaEntity>) {
        if (some.isEmpty()) return
        publish(_entities.value + some)
    }

    protected fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        publish(_entities.value - ids.toSet())
    }

    /** Polls [tick] every [intervalMs] until cancelled; for a platform with no event stream. */
    protected suspend fun pollForever(intervalMs: Long, tick: suspend () -> Unit) {
        while (scope.isActive) {
            delay(intervalMs)
            tick()
        }
    }

    companion object {
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 15_000, 30_000)
        private const val CHANGE_BUFFER = 256
    }
}

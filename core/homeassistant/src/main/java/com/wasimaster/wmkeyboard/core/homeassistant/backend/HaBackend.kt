package com.wasimaster.wmkeyboard.core.homeassistant.backend

import com.wasimaster.wmkeyboard.core.homeassistant.HaAgent
import com.wasimaster.wmkeyboard.core.homeassistant.HaAssistReply
import com.wasimaster.wmkeyboard.core.homeassistant.HaAuth
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaHttp
import com.wasimaster.wmkeyboard.core.homeassistant.HaHttpException
import com.wasimaster.wmkeyboard.core.homeassistant.HaInstance
import com.wasimaster.wmkeyboard.core.homeassistant.HaNotConnectedException
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaRegistry
import com.wasimaster.wmkeyboard.core.homeassistant.HaRest
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import com.wasimaster.wmkeyboard.core.homeassistant.HaSocket
import com.wasimaster.wmkeyboard.core.homeassistant.HaTokens
import com.wasimaster.wmkeyboard.core.homeassistant.HaTransport
import com.wasimaster.wmkeyboard.core.homeassistant.HaUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException

/**
 * How a Home Assistant credential is read: the home address is the
 * credential's own, the remote one and the sign-in tokens live in `extra`,
 * and a pasted long-lived token is the credential's key.
 */
object HaConnection {

    /** The addresses to try, in order. */
    fun candidates(account: HomeAccount): List<HaUrl> {
        val internal = HaUrl.parse(account.url)
        val external = account.extra(HomeKeys.EXTERNAL_URL)?.let(HaUrl::parse)
        val preferInternal = account.extra(HomeKeys.PREFER_INTERNAL) != "false"
        val ordered = if (preferInternal) listOfNotNull(internal, external) else listOfNotNull(external, internal)
        return ordered.distinct()
    }

    /** The tokens the credential holds, or null when nothing is signed in. */
    fun tokens(account: HomeAccount): HaTokens? {
        if (account.apiKey.isNotBlank()) return HaTokens.longLived(account.apiKey)
        val access = account.extra(HomeKeys.ACCESS_TOKEN) ?: return null
        return HaTokens(
            accessToken = access,
            refreshToken = account.extra(HomeKeys.REFRESH_TOKEN),
            expiresAtMs = account.extra(HomeKeys.EXPIRES_AT)?.toLongOrNull() ?: Long.MAX_VALUE,
            longLived = false,
        )
    }

    /** The `extra` entries a fresh sign-in writes, over whatever the credential had. */
    fun withTokens(extra: Map<String, String>, tokens: HaTokens): Map<String, String> = extra + mapOf(
        HomeKeys.ACCESS_TOKEN to tokens.accessToken,
        HomeKeys.REFRESH_TOKEN to tokens.refreshToken.orEmpty(),
        HomeKeys.EXPIRES_AT to tokens.expiresAtMs.toString(),
    ) - HomeKeys.AUTH_STATE

    /** Signed out: the tokens go, the addresses stay. */
    fun withoutTokens(extra: Map<String, String>): Map<String, String> =
        extra - HomeKeys.ACCESS_TOKEN - HomeKeys.REFRESH_TOKEN - HomeKeys.EXPIRES_AT - HomeKeys.AUTH_STATE

    /** Whether two credentials name the same server and sign-in, so a connection can be kept. */
    fun sameConnection(a: HomeAccount, b: HomeAccount): Boolean =
        candidates(a) == candidates(b) && tokens(a) == tokens(b) && a.extra(HomeKeys.INSTANCE_ID) == b.extra(HomeKeys.INSTANCE_ID)
}

/**
 * Home Assistant as a [HomeBackend]: the WebSocket for the live stream and
 * commands, REST when the socket is down, a token refresh ahead of expiry,
 * and the registry, instance and agents fetched once the socket is up. One
 * instance per listed home; the hub owns when it runs.
 *
 * [onTokens] is told about every refreshed token so the owner can persist it;
 * the backend keeps using the new one either way.
 */
@Suppress("detekt.TooManyFunctions")
class HaBackend(
    override val key: String,
    initial: HomeAccount,
    private val transport: HaTransport,
    private val http: HaHttp,
    private val scope: CoroutineScope,
    private val onTokens: suspend (HaTokens) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : HomeBackend {

    override val kind: HomeKind = HomeKind.HOME_ASSISTANT
    override val features: Set<HomeFeature> = setOf(HomeFeature.ASSIST, HomeFeature.SNAPSHOT, HomeFeature.OPEN_IN_APP, HomeFeature.LIVE)

    @Volatile private var account = initial

    @Volatile private var tokens: HaTokens? = HaConnection.tokens(initial)

    @Volatile private var urlIndex = 0

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

    private val _agents = MutableStateFlow<List<HaAgent>>(emptyList())

    /** The conversation agents the server offers, for the Assist settings. */
    val agents: StateFlow<List<HaAgent>> = _agents.asStateFlow()

    /** The agent Assist asks; blank for the server's default. Set by the owner from its settings. */
    @Volatile var assistAgentId: String = ""

    private var socket: HaSocket? = null
    private var socketJobs: List<Job> = emptyList()
    private var afterAuthJob: Job? = null

    /** States over REST while the socket cannot connect; see [pollRest]. */
    private var restJob: Job? = null

    @Volatile private var restAnswers = false

    /** REST against whichever address the socket last reached, or the first candidate. */
    val rest: HaRest = HaRest(http, { activeUrl() }, { tokens?.accessToken })

    val connectedUrl: HaUrl? get() = socket?.connectedUrl

    val isLive: Boolean get() = socket?.isLive == true

    // ---- Lifecycle -----------------------------------------------------------

    override fun start() {
        if (socket != null) return
        if (tokens == null || HaConnection.candidates(account).isEmpty()) return
        val made = HaSocket(
            transport = transport,
            scope = scope,
            url = { activeUrl() },
            token = { freshAccessToken() },
            home = key,
            events = object : HaSocket.Events {
                override fun onAuthenticated(version: String?) {
                    _error.value = null
                    afterAuth()
                }

                override fun onDropped(cause: Throwable?) {
                    _error.value = cause?.message
                }
            },
        )
        socket = made
        socketJobs = listOf(
            scope.launch {
                made.phase.collect { phase ->
                    // An address that does not answer gives way to the other one for the next try.
                    if (phase == HaPhase.UNREACHABLE && made.connectedUrl == null && HaConnection.candidates(account).size > 1) urlIndex++
                    when (phase) {
                        HaPhase.LIVE, HaPhase.TOKEN_REJECTED, HaPhase.OFFLINE -> {
                            restJob?.cancel()
                            restJob = null
                            restAnswers = false
                        }
                        HaPhase.UNREACHABLE -> if (restJob?.isActive != true) restJob = scope.launch { pollRest() }
                        else -> Unit
                    }
                    // While the socket retries, a server that answers over REST is amber, not red.
                    _phase.value = if (restAnswers && phase in RETRYING) HaPhase.REST_FALLBACK else phase
                }
            },
            scope.launch {
                made.entities.collect { map -> if (map.isNotEmpty()) _entities.value = map }
            },
            scope.launch { made.changes.collect { _changes.tryEmit(it) } },
        )
        made.start()
    }

    override fun stop() {
        val current = socket ?: return
        socketJobs.forEach { it.cancel() }
        socketJobs = emptyList()
        afterAuthJob?.cancel()
        restJob?.cancel()
        restJob = null
        restAnswers = false
        socket = null
        current.stop()
        _phase.value = HaPhase.OFFLINE
    }

    override fun refresh() {
        val running = socket != null
        urlIndex = 0
        stop()
        if (running) start()
    }

    override fun clear() {
        socket?.clear()
        _entities.value = emptyMap()
        _registry.value = HaRegistry()
        _instance.value = null
        _agents.value = emptyList()
        _error.value = null
    }

    override fun update(account: HomeAccount): Boolean {
        val previous = this.account
        this.account = account
        // Against the tokens in use, not the ones stored before: a refresh this
        // backend made comes back through the settings, and must not restart it.
        val changed = HaConnection.candidates(previous) != HaConnection.candidates(account) ||
            previous.extra(HomeKeys.INSTANCE_ID) != account.extra(HomeKeys.INSTANCE_ID) ||
            HaConnection.tokens(account) != tokens
        if (changed) {
            tokens = HaConnection.tokens(account)
            urlIndex = 0
            val running = socket != null
            stop()
            clear()
            if (running) start()
        }
        return changed
    }

    /**
     * The states over plain REST every [REST_POLL_MS] while the socket keeps
     * failing: a proxy or a tunnel that passes HTTP but not WebSockets still
     * gets a panel. Ends when the socket comes up.
     */
    private suspend fun pollRest() {
        while (socket?.isLive != true) {
            restAnswers = try {
                val map = runInterruptible { rest.states() }.associate { it.entityId to it.copy(home = key) }
                if (map.isNotEmpty()) {
                    val before = _entities.value
                    _entities.value = map
                    val changed = map.filter { (id, e) -> before[id] != e }.keys + before.keys.filter { it !in map }
                    if (changed.isNotEmpty()) _changes.tryEmit(changed.toSet())
                }
                true
            } catch (_: IOException) {
                false
            }
            if (restAnswers && socket?.isLive != true) _phase.value = HaPhase.REST_FALLBACK
            delay(REST_POLL_MS)
        }
    }

    // ---- Addresses and tokens -----------------------------------------------

    private fun activeUrl(): HaUrl? {
        socket?.connectedUrl?.let { return it }
        val list = HaConnection.candidates(account)
        if (list.isEmpty()) return null
        return list[urlIndex % list.size]
    }

    /** The address the web links use, preferring the one that answers. */
    override fun webUrl(entityId: String?): String? {
        val base = activeUrl() ?: return null
        return if (entityId != null) "${base.base}/history?entity_id=$entityId" else base.base
    }

    /**
     * The access token to use now: the stored one, or a fresh one when the
     * stored one is about to expire. A refresh that fails with a 400 or 401
     * means the sign-in is dead; the socket then stops with TOKEN_REJECTED.
     */
    private suspend fun freshAccessToken(): String? {
        val current = tokens ?: return null
        if (!current.expiresWithin(now())) return current.accessToken
        val base = activeUrl() ?: return current.accessToken
        return try {
            val refreshed = runInterruptible { HaAuth.refresh(http, base, current, now()) }
            tokens = refreshed
            onTokens(refreshed)
            refreshed.accessToken
        } catch (e: HaHttpException) {
            if (e.status == BAD_REQUEST || e.tokenRejected) null else current.accessToken
        } catch (_: IOException) {
            // The server is off, not the token: try the old one and let the socket report the drop.
            current.accessToken
        }
    }

    /** Once the socket is live: the registry, the instance, the agents. */
    private fun afterAuth() {
        afterAuthJob?.cancel()
        afterAuthJob = scope.launch {
            val s = socket ?: return@launch
            runCatching { s.registry() }.getOrNull()?.let { _registry.value = it }
            runCatching { s.config() }.getOrNull()?.let { _instance.value = it }
            _agents.value = runCatching { s.agents() }.getOrDefault(emptyList())
        }
    }

    // ---- Commands ------------------------------------------------------------

    override suspend fun callService(call: HaServiceCall) {
        val s = socket
        if (s != null && s.isLive) {
            try {
                s.callService(call)
                return
            } catch (_: HaNotConnectedException) {
                // Fell over between the check and the send; REST below.
            }
        }
        runInterruptible { rest.callService(call) }
        // Over REST nothing streams the result back: read it, so the tile is not left claiming.
        if (restJob?.isActive == true) scope.launch { runCatching { states() } }
    }

    override suspend fun states(): Map<String, HaEntity> {
        val s = socket
        val list = if (s != null && s.isLive) {
            try {
                s.states()
            } catch (_: HaNotConnectedException) {
                runInterruptible { rest.states() }
            }
        } else {
            runInterruptible { rest.states() }
        }
        val map = list.associate { it.entityId to it.copy(home = key) }
        if (map.isNotEmpty()) _entities.value = map
        return map
    }

    override suspend fun assist(text: String, language: String?, conversationId: String?): HaAssistReply {
        val agent = assistAgentId.takeIf { it.isNotBlank() }
        val s = socket
        if (s != null && s.isLive) {
            try {
                return s.conversation(text, language, agent, conversationId)
            } catch (_: HaNotConnectedException) {
                // REST below.
            }
        }
        return runInterruptible { rest.conversation(text, language, agent, conversationId) }
    }

    override suspend fun snapshot(entityId: String): ByteArray = runInterruptible { rest.cameraSnapshot(entityId).bytes }

    private companion object {
        const val CHANGE_BUFFER = 256
        const val BAD_REQUEST = 400
        const val REST_POLL_MS = 10_000L
        val RETRYING = setOf(HaPhase.CONNECTING, HaPhase.AUTHENTICATING, HaPhase.UNREACHABLE)
    }
}

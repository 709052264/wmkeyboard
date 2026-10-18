package com.wasimaster.wmkeyboard.ime.homeassistant

import android.content.Context
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.homeassistant.HaAgent
import com.wasimaster.wmkeyboard.core.homeassistant.HaAssistReply
import com.wasimaster.wmkeyboard.core.homeassistant.HaAuth
import com.wasimaster.wmkeyboard.core.homeassistant.HaCache
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaHttpException
import com.wasimaster.wmkeyboard.core.homeassistant.HaInstance
import com.wasimaster.wmkeyboard.core.homeassistant.HaNotConnectedException
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaRegistry
import com.wasimaster.wmkeyboard.core.homeassistant.HaRest
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import com.wasimaster.wmkeyboard.core.homeassistant.HaSnapshot
import com.wasimaster.wmkeyboard.core.homeassistant.HaSocket
import com.wasimaster.wmkeyboard.core.homeassistant.HaTokens
import com.wasimaster.wmkeyboard.core.homeassistant.HaUrl
import com.wasimaster.wmkeyboard.core.homeassistant.OkHttpTransport
import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.HaLinkLifetime
import com.wasimaster.wmkeyboard.core.settings.HomeAssistantSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** What the hub knows, for the panel, the settings page and the AI tools alike. */
data class HaHubState(
    val phase: HaPhase = HaPhase.OFFLINE,
    val instance: HaInstance? = null,
    val entities: Map<String, HaEntity> = emptyMap(),
    val registry: HaRegistry = HaRegistry(),
    val connectedUrl: HaUrl? = null,
    val agents: List<HaAgent> = emptyList(),
    /** The entities came from the cache file; the socket has not confirmed them yet. */
    val fromCache: Boolean = false,
    val lastError: String? = null,
)

/**
 * The one Home Assistant connection of this process, and the rules for when it
 * is up. A singleton for the reason `KdeConnectHub` is: the keyboard, the
 * settings page and the AI's tools all live in one process and must see one
 * socket, one entity map, one token refresh.
 *
 * Whoever needs the server [hold]s it under a reason and [release]s it after;
 * the socket is up while a reason is held, and lingers a minute after the
 * keyboard goes away so an app switch does not reconnect. All bookkeeping is on
 * the main thread; the network is the socket's own and [io]'s.
 */
object HomeAssistantHub {

    enum class Reason {
        /** The keyboard is on screen. Enough under [HaLinkLifetime.KEYBOARD]. */
        KEYBOARD,

        /** The panel is open, a settings screen is testing or browsing, the AI is asking, a snippet wants a state. Always enough. */
        PANEL, SETTINGS, AI, SNIPPET,
    }

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(HaHubState())
    val state: StateFlow<HaHubState> = _state.asStateFlow()

    private val _changes = MutableSharedFlow<Set<String>>(extraBufferCapacity = 256)

    /** Entity ids the server just changed, in order, for the tiles' pulse. */
    val changes: SharedFlow<Set<String>> = _changes.asSharedFlow()

    private var app: Context? = null
    private var repository: SettingsRepository? = null
    private var settings = HomeAssistantSettings()
    private val reasons = HashSet<Reason>()
    private var keyboardLeftAtMs = 0L
    private var lingerJob: Job? = null
    private var batchDepth = 0
    private var reconcileOwed = false

    private var socket: HaSocket? = null
    private var socketJobs: List<Job> = emptyList()
    private var urlIndex = 0
    private var cacheJob: Job? = null
    private var cacheDirty = false
    private var cacheLoaded = false
    private var afterAuthJob: Job? = null

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(InternetGate)
            .addNetworkInterceptor(NetLogInterceptor(NetSource.HOME_ASSISTANT, route = { "/api/websocket" }))
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private val http by lazy { ToolHaHttp(requireNotNull(app).cacheDir) }

    /** REST against whichever address the socket last reached, or the first candidate. */
    val rest: HaRest by lazy { HaRest(http, { activeUrl() }, { settings.accessToken.takeIf { it.isNotBlank() } }) }

    fun attach(context: Context) {
        if (app == null) {
            app = context.applicationContext
            repository = SettingsRepository(context.applicationContext)
        }
    }

    /** Runs [block] and reconciles once at the end, however many holds and settings it changed. */
    fun batch(block: () -> Unit) {
        batchDepth++
        try {
            block()
        } finally {
            batchDepth--
            if (batchDepth == 0 && reconcileOwed) {
                reconcileOwed = false
                reconcile()
            }
        }
    }

    fun applySettings(next: HomeAssistantSettings) {
        if (next == settings) return
        val serverChanged = next.internalUrl != settings.internalUrl || next.externalUrl != settings.externalUrl ||
            next.accessToken != settings.accessToken || next.instanceId != settings.instanceId
        settings = next
        if (serverChanged) {
            // A different house, or a new sign-in: nothing of the old one may show.
            urlIndex = 0
            socket?.clear()
            if (socket?.phase?.value == HaPhase.TOKEN_REJECTED) stopSocket()
        }
        if (!next.hasToken && _state.value.entities.isNotEmpty()) {
            _state.update { HaHubState() }
            app?.let { File(it.filesDir, DIR).deleteRecursively() }
        }
        reconcile()
    }

    fun hold(reason: Reason) {
        if (reasons.add(reason)) {
            if (reason == Reason.KEYBOARD) lingerJob?.cancel()
            reconcile()
        }
    }

    fun release(reason: Reason) {
        if (!reasons.remove(reason)) return
        if (reason == Reason.KEYBOARD) {
            keyboardLeftAtMs = System.currentTimeMillis()
            lingerJob?.cancel()
            lingerJob = main.launch {
                delay(KEYBOARD_LINGER_MS + LINGER_SLACK_MS)
                reconcile()
            }
        }
        reconcile()
    }

    fun setHeld(reason: Reason, on: Boolean) = if (on) hold(reason) else release(reason)

    private fun wanted(): Boolean {
        val context = app ?: return false
        if (!settings.enabled || !settings.hasToken || !settings.hasServer) return false
        if (!DirectBoot.isUserUnlocked(context) || !InternetPermission.granted) return false
        if (reasons.any { it != Reason.KEYBOARD }) return true
        val lingering = System.currentTimeMillis() - keyboardLeftAtMs < KEYBOARD_LINGER_MS
        return when (settings.lifetime) {
            HaLinkLifetime.PANEL -> false
            HaLinkLifetime.KEYBOARD -> Reason.KEYBOARD in reasons || lingering
        }
    }

    private fun reconcile() {
        if (batchDepth > 0) {
            reconcileOwed = true
            return
        }
        loadCacheOnce()
        if (!wanted()) {
            stopSocket()
            return
        }
        if (socket == null) startSocket()
    }

    // ---- The socket --------------------------------------------------------

    private fun candidates(): List<HaUrl> {
        val internal = HaUrl.parse(settings.internalUrl)
        val external = HaUrl.parse(settings.externalUrl)
        val ordered = if (settings.preferInternal) listOfNotNull(internal, external) else listOfNotNull(external, internal)
        return ordered.distinct()
    }

    private fun activeUrl(): HaUrl? {
        socket?.connectedUrl?.let { return it }
        val list = candidates()
        return list.getOrNull(urlIndex % list.size.coerceAtLeast(1))
    }

    private fun startSocket() {
        val made = HaSocket(
            transport = OkHttpTransport(okHttp),
            scope = io,
            url = { activeUrl() },
            token = { freshAccessToken() },
            events = object : HaSocket.Events {
                override fun onAuthenticated(version: String?) {
                    main.launch { afterAuth() }
                }

                override fun onTokenRejected() {
                    main.launch { _state.update { it.copy(lastError = null) } }
                }

                override fun onDropped(cause: Throwable?) {
                    main.launch { _state.update { it.copy(lastError = cause?.message) } }
                }
            },
        )
        socket = made
        socketJobs = listOf(
            main.launch {
                made.phase.collect { phase ->
                    // An address that does not answer gives way to the other one for the next try.
                    if (phase == HaPhase.UNREACHABLE && made.connectedUrl == null && candidates().size > 1) urlIndex++
                    _state.update { it.copy(phase = phase, connectedUrl = made.connectedUrl) }
                }
            },
            main.launch {
                made.entities.collect { map ->
                    if (map.isEmpty()) return@collect
                    _state.update { it.copy(entities = map, fromCache = false) }
                    cacheDirty = true
                }
            },
            main.launch { made.changes.collect { _changes.tryEmit(it) } },
        )
        made.start()
        cacheJob?.cancel()
        cacheJob = io.launch {
            while (true) {
                delay(CACHE_INTERVAL_MS)
                if (cacheDirty) saveCache()
            }
        }
    }

    private fun stopSocket() {
        val current = socket ?: return
        socketJobs.forEach { it.cancel() }
        socketJobs = emptyList()
        afterAuthJob?.cancel()
        cacheJob?.cancel()
        socket = null
        current.stop()
        if (cacheDirty) io.launch { saveCache() }
        _state.update { it.copy(phase = HaPhase.OFFLINE, connectedUrl = null) }
    }

    /** Once the socket is live: the registry, the instance, the agents; and the states if the stream has not arrived yet. */
    private fun afterAuth() {
        afterAuthJob?.cancel()
        afterAuthJob = io.launch {
            val s = socket ?: return@launch
            val registry = runCatching { s.registry() }.getOrNull()
            val instance = runCatching { s.config() }.getOrNull()
            val agents = runCatching { s.agents() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main.immediate) {
                _state.update {
                    it.copy(
                        registry = registry ?: it.registry,
                        instance = instance ?: it.instance,
                        agents = agents,
                    )
                }
            }
            if (instance != null && (instance.name != settings.instanceName)) {
                repository?.setHaInstance(instance.name, settings.instanceId)
            }
            cacheDirty = true
            saveCache()
        }
    }

    // ---- Tokens ------------------------------------------------------------

    /**
     * The access token to use now: the stored one, or a fresh one when the
     * stored one is about to expire. A refresh that fails with a 400 or 401
     * means the sign-in is dead; the socket then stops with TOKEN_REJECTED.
     */
    private suspend fun freshAccessToken(): String? {
        val s = settings
        if (!s.hasToken) return null
        val tokens = HaTokens(
            accessToken = s.accessToken,
            refreshToken = s.refreshToken.takeIf { it.isNotBlank() },
            expiresAtMs = if (s.longLivedToken || s.tokenExpiresAtMs == 0L) Long.MAX_VALUE else s.tokenExpiresAtMs,
            longLived = s.longLivedToken,
        )
        if (!tokens.expiresWithin(System.currentTimeMillis())) return tokens.accessToken
        val base = activeUrl() ?: return tokens.accessToken
        return try {
            val refreshed = withContext(Dispatchers.IO) { HaAuth.refresh(http, base, tokens) }
            repository?.setHaTokens(refreshed.accessToken, refreshed.refreshToken.orEmpty(), refreshed.expiresAtMs, longLived = false)
            settings = settings.copy(accessToken = refreshed.accessToken, tokenExpiresAtMs = refreshed.expiresAtMs)
            refreshed.accessToken
        } catch (e: HaHttpException) {
            if (e.status == BAD_REQUEST || e.tokenRejected) null else tokens.accessToken
        } catch (_: java.io.IOException) {
            // The server is off, not the token: try the old one and let the socket report the drop.
            tokens.accessToken
        }
    }

    // ---- Cache -------------------------------------------------------------

    private fun cacheFile(): File? = app?.let { File(File(it.filesDir, DIR), "cache.json") }

    private fun loadCacheOnce() {
        if (cacheLoaded || !settings.cacheStates || !settings.hasToken) return
        cacheLoaded = true
        val file = cacheFile() ?: return
        io.launch {
            val snap = runCatching { file.takeIf { it.exists() }?.readText()?.let(HaCache::decode) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                _state.update { s ->
                    if (s.entities.isNotEmpty()) s else s.copy(
                        entities = snap.entities.associateBy { it.entityId },
                        registry = snap.registry,
                        instance = snap.instance ?: s.instance,
                        fromCache = true,
                    )
                }
            }
        }
    }

    private fun saveCache() {
        if (!settings.cacheStates) return
        val file = cacheFile() ?: return
        val s = _state.value
        if (s.entities.isEmpty() || s.fromCache) return
        cacheDirty = false
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "cache.json.tmp")
            tmp.writeText(HaCache.encode(HaSnapshot(s.instance, s.entities.values.toList(), s.registry, System.currentTimeMillis())))
            tmp.renameTo(file)
        }
    }

    // ---- Commands ----------------------------------------------------------

    /** Runs a service, over the socket when it is live and over REST when it is not. */
    suspend fun callService(call: HaServiceCall) {
        val s = socket
        if (s != null && s.isLive) {
            try {
                s.callService(call)
                return
            } catch (_: HaNotConnectedException) {
                // Fell over between the check and the send; REST below.
            }
        }
        withContext(Dispatchers.IO) { rest.callService(call) }
    }

    /** One sentence to Assist. */
    suspend fun assist(text: String, language: String?, conversationId: String?): HaAssistReply {
        val agent = settings.assistAgentId.takeIf { it.isNotBlank() }
        val s = socket
        if (s != null && s.isLive) {
            try {
                return s.conversation(text, language, agent, conversationId)
            } catch (_: HaNotConnectedException) {
                // REST below.
            }
        }
        return withContext(Dispatchers.IO) { rest.conversation(text, language, agent, conversationId) }
    }

    /** A camera still, as image bytes. */
    suspend fun snapshot(entityId: String): ByteArray = withContext(Dispatchers.IO) { rest.cameraSnapshot(entityId).bytes }

    /** The states once over REST: for a caller holding [Reason.AI] or [Reason.SNIPPET] that cannot wait for the stream. */
    suspend fun ensureStates(timeoutMs: Long = STATES_TIMEOUT_MS): Map<String, HaEntity> {
        val live = withTimeoutOrNull(timeoutMs) { state.first { it.entities.isNotEmpty() && !it.fromCache } }
        if (live != null) return live.entities
        val fetched = runCatching { withContext(Dispatchers.IO) { rest.states() } }.getOrNull() ?: return state.value.entities
        val map = fetched.associateBy { it.entityId }
        _state.update { it.copy(entities = map, fromCache = false) }
        return map
    }

    /** Try again now: a Retry button, the network came back. */
    fun refresh() {
        urlIndex = 0
        stopSocket()
        reconcile()
    }

    /** The web address of the server for a link, preferring the one that answers. */
    fun webUrl(): HaUrl? = activeUrl()

    const val DIR = "homeassistant"
    const val KEYBOARD_LINGER_MS = 60_000L
    private const val LINGER_SLACK_MS = 250L
    private const val CACHE_INTERVAL_MS = 30_000L
    private const val STATES_TIMEOUT_MS = 4_000L
    private const val CONNECT_TIMEOUT_S = 10L
    private const val BAD_REQUEST = 400
}

package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The wire under [HaSocket]: a WebSocket that delivers text frames. An interface so tests can script a server. */
interface HaTransport {
    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClosed(code: Int, reason: String)
        fun onFailure(t: Throwable)
    }

    interface Connection {
        fun send(text: String): Boolean
        fun close(code: Int = NORMAL_CLOSE, reason: String = "")
    }

    fun connect(url: String, listener: Listener): Connection

    companion object {
        const val NORMAL_CLOSE = 1000
    }
}

/** OkHttp's WebSocket, the one the APK already carries. */
class OkHttpTransport(private val client: OkHttpClient) : HaTransport {
    override fun connect(url: String, listener: HaTransport.Listener): HaTransport.Connection {
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code, reason)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(t)
            },
        )
        return object : HaTransport.Connection {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close(code: Int, reason: String) {
                socket.close(code, reason)
            }
        }
    }
}

/** A command the server answered with `success: false`. */
class HaCommandException(val code: String, message: String) : IOException("$code: $message")

/** A command sent while the socket was not live. */
class HaNotConnectedException : IOException("not connected")

/**
 * Home Assistant's WebSocket API: authenticate, subscribe to every entity's
 * state as a compressed stream, and run commands with correlated replies.
 *
 * [start] keeps a connection up until [stop]: a drop reconnects with a
 * backoff, a refused token stops and reports, so the user is asked once rather
 * than hammered. All state handling runs on one coroutine fed by a channel, so
 * the entity map never sees two writers. [token] is asked for on every attempt,
 * so a refreshed access token is picked up without a restart.
 */
class HaSocket(
    private val transport: HaTransport,
    private val scope: CoroutineScope,
    private val url: () -> HaUrl?,
    private val token: suspend () -> String?,
    /** Stamped on every entity the stream delivers: the home's key, see `HomeRef`. */
    private val home: String = "",
    private val events: Events = object : Events {},
) {
    /** What the owner wants to hear about beyond the flows. */
    interface Events {
        fun onAuthenticated(version: String?) {}
        fun onTokenRejected() {}
        fun onDropped(cause: Throwable?) {}
    }

    private sealed interface Inbound {
        data class Text(val text: String) : Inbound
        data class Closed(val code: Int, val reason: String) : Inbound
        data class Failed(val cause: Throwable) : Inbound
    }

    private val _phase = MutableStateFlow(HaPhase.OFFLINE)
    val phase: StateFlow<HaPhase> = _phase.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    val entities: StateFlow<Map<String, HaEntity>> = _entities.asStateFlow()

    private val _changes = MutableSharedFlow<Set<String>>(extraBufferCapacity = CHANGE_BUFFER)

    /** The entity ids each server event touched, in order: what a tile pulses on. */
    val changes: SharedFlow<Set<String>> = _changes.asSharedFlow()

    @Volatile var haVersion: String? = null
        private set

    /** The address the live connection used, so REST follows the same one. */
    @Volatile var connectedUrl: HaUrl? = null
        private set

    private var runner: Job? = null

    @Volatile private var connection: HaTransport.Connection? = null

    @Volatile private var live = false
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    @Volatile private var subscriptionId = -1

    /** The next entity event is the subscription's first: a whole snapshot, not a change to the old map. */
    @Volatile private var snapshotNext = false
    private var backoffIndex = 0

    val isLive: Boolean get() = live

    fun start() {
        if (runner?.isActive == true) return
        runner = scope.launch { run() }
    }

    fun stop() {
        runner?.cancel()
        runner = null
        connection?.close()
        connection = null
        live = false
        failPending(HaNotConnectedException())
        _phase.value = HaPhase.OFFLINE
    }

    /** Forget the entity map: the owner switched server or signed out. */
    fun clear() {
        _entities.value = emptyMap()
    }

    private suspend fun run() {
        backoffIndex = 0
        while (scope.isActive && runner?.isActive == true) {
            val outcome = attempt()
            live = false
            connection = null
            failPending(HaNotConnectedException())
            when (outcome) {
                Outcome.AUTH_FAILED -> {
                    _phase.value = HaPhase.TOKEN_REJECTED
                    events.onTokenRejected()
                    return
                }
                Outcome.NO_ADDRESS, Outcome.NO_TOKEN -> {
                    _phase.value = HaPhase.OFFLINE
                    return
                }
                Outcome.WAS_LIVE -> backoffIndex = 0
                // The address that last worked did not answer this time (the phone left
                // home, say): forget it, so the owner may try its other address next.
                Outcome.FAILED -> connectedUrl = null
            }
            _phase.value = HaPhase.UNREACHABLE
            delay(BACKOFF_MS[backoffIndex.coerceAtMost(BACKOFF_MS.lastIndex)])
            backoffIndex++
        }
    }

    private enum class Outcome { NO_ADDRESS, NO_TOKEN, AUTH_FAILED, WAS_LIVE, FAILED }

    @Suppress("detekt.LongMethod", "detekt.CyclomaticComplexMethod")
    private suspend fun attempt(): Outcome {
        val target = url() ?: return Outcome.NO_ADDRESS
        val access = token() ?: return Outcome.NO_TOKEN
        _phase.value = HaPhase.CONNECTING
        val inbound = Channel<Inbound>(Channel.UNLIMITED)
        val conn = transport.connect(
            target.websocket,
            object : HaTransport.Listener {
                override fun onOpen() = Unit
                override fun onMessage(text: String) {
                    inbound.trySend(Inbound.Text(text))
                }
                override fun onClosed(code: Int, reason: String) {
                    inbound.trySend(Inbound.Closed(code, reason))
                }
                override fun onFailure(t: Throwable) {
                    inbound.trySend(Inbound.Failed(t))
                }
            },
        )
        connection = conn
        var wasLive = false
        var authFailed = false
        var pinger: Job? = null
        var cause: Throwable? = null
        try {
            val opened = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { inbound.receive() } ?: return Outcome.FAILED
            var next: Inbound? = opened
            while (true) {
                val msg = next ?: break
                when (msg) {
                    is Inbound.Closed -> break
                    is Inbound.Failed -> {
                        cause = msg.cause
                        break
                    }
                    is Inbound.Text -> {
                        val o = runCatching { HaJson.parseToJsonElement(msg.text) as? JsonObject }.getOrNull()
                        when (o?.str("type")) {
                            "auth_required" -> {
                                _phase.value = HaPhase.AUTHENTICATING
                                conn.send(HaJson.encodeToString(JsonObject.serializer(), buildJsonObject {
                                    put("type", "auth")
                                    put("access_token", access)
                                }))
                            }
                            "auth_ok" -> {
                                haVersion = o.str("ha_version")
                                connectedUrl = target
                                live = true
                                wasLive = true
                                nextId.set(1)
                                _phase.value = HaPhase.LIVE
                                events.onAuthenticated(haVersion)
                                scope.launch { runCatching { subscribeEntities() } }
                                pinger = scope.launch { pingLoop(conn) }
                            }
                            "auth_invalid" -> {
                                authFailed = true
                                break
                            }
                            "result" -> onResult(o)
                            "event" -> onEvent(o)
                            "pong" -> onResult(o)
                            else -> Unit
                        }
                    }
                }
                next = if (live) {
                    inbound.receive()
                } else {
                    withTimeoutOrNull(CONNECT_TIMEOUT_MS) { inbound.receive() }
                }
            }
        } finally {
            pinger?.cancel()
            live = false
            runCatching { conn.close() }
            inbound.close()
        }
        if (authFailed) return Outcome.AUTH_FAILED
        if (wasLive) {
            events.onDropped(cause)
            return Outcome.WAS_LIVE
        }
        return Outcome.FAILED
    }

    private suspend fun pingLoop(conn: HaTransport.Connection) {
        while (live) {
            delay(PING_INTERVAL_MS)
            if (!live) return
            val answered = withTimeoutOrNull(PING_TIMEOUT_MS) { runCatching { send("ping") }.isSuccess }
            if (answered != true) {
                runCatching { conn.close(GOING_AWAY, "ping timeout") }
                return
            }
        }
    }

    private fun onResult(o: JsonObject) {
        val id = o.int("id") ?: return
        val waiter = pending.remove(id) ?: return
        if (o.str("type") == "pong" || o.bool("success") == true) {
            waiter.complete(o["result"] ?: JsonObject(emptyMap()))
        } else {
            val err = o.obj("error")
            waiter.completeExceptionally(HaCommandException(err?.str("code") ?: "unknown", err?.str("message") ?: ""))
        }
    }

    private fun onEvent(o: JsonObject) {
        val id = o.int("id") ?: return
        if (id != subscriptionId) return
        val ev = o.obj("event") ?: return
        applyCompressed(ev)
    }

    /** One `subscribe_entities` event: `a` adds, `c` changes, `r` removes. */
    internal fun applyCompressed(ev: JsonObject) {
        // A new subscription opens with every entity under `a`; whatever the old
        // map holds beyond that was deleted while the socket was down.
        val fresh = snapshotNext && ev.obj("a") != null
        if (fresh) snapshotNext = false
        val previous = _entities.value
        val current = if (fresh) LinkedHashMap() else LinkedHashMap(previous)
        val changed = LinkedHashSet<String>()
        ev.obj("a")?.forEach { (id, v) ->
            current[id] = HaEntity.fromCompressed(id, v.jsonObject).copy(home = home)
            changed += id
        }
        ev.obj("c")?.forEach { (id, v) ->
            val old = current[id]
            current[id] = old?.applyCompressedChange(v.jsonObject)
                ?: HaEntity.fromCompressed(id, v.jsonObject.obj("+") ?: JsonObject(emptyMap())).copy(home = home)
            changed += id
        }
        ev.arr("r")?.forEach { id ->
            id.stringContent()?.let {
                current.remove(it)
                changed += it
            }
        }
        if (fresh) previous.keys.filterTo(changed) { it !in current }
        if (changed.isEmpty()) return
        _entities.value = current
        _changes.tryEmit(changed)
    }

    private fun failPending(cause: Throwable) {
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(cause) }
    }

    // ---- Commands ----------------------------------------------------------

    /**
     * Sends a command and waits for its result. Throws [HaNotConnectedException]
     * when the socket is down and [HaCommandException] when the server says no.
     */
    suspend fun send(type: String, build: JsonObjectBuilder.() -> Unit = {}): JsonElement {
        val conn = connection?.takeIf { live } ?: throw HaNotConnectedException()
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<JsonElement>()
        pending[id] = waiter
        val text = HaJson.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("id", id)
            put("type", type)
            build()
        })
        if (!conn.send(text)) {
            pending.remove(id)
            throw HaNotConnectedException()
        }
        return withTimeoutOrNull(COMMAND_TIMEOUT_MS) { waiter.await() } ?: run {
            pending.remove(id)
            throw IOException("timed out: $type")
        }
    }

    private suspend fun subscribeEntities() {
        val conn = connection?.takeIf { live } ?: return
        val id = nextId.getAndIncrement()
        subscriptionId = id
        snapshotNext = true
        val waiter = CompletableDeferred<JsonElement>()
        pending[id] = waiter
        conn.send(HaJson.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("id", id)
            put("type", "subscribe_entities")
        }))
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) { waiter.await() }
    }

    suspend fun callService(call: HaServiceCall): JsonElement = send("call_service") {
        put("domain", call.domain)
        put("service", call.service)
        call.entityId?.let { id -> put("target", buildJsonObject { put("entity_id", id) }) }
        if (call.data.isNotEmpty()) put("service_data", call.data)
    }

    suspend fun registry(): HaRegistry {
        val areas = runCatching { send("config/area_registry/list") }.getOrNull()
        val floors = runCatching { send("config/floor_registry/list") }.getOrNull()
        val devices = runCatching { send("config/device_registry/list") }.getOrNull()
        val entries = runCatching { send("config/entity_registry/list") }.getOrNull()
        return HaRegistry.parse(
            areas as? JsonArray,
            floors as? JsonArray,
            devices as? JsonArray,
            entries as? JsonArray,
        )
    }

    suspend fun config(): HaInstance = HaInstance.parse(send("get_config").jsonObject)

    suspend fun agents(): List<HaAgent> =
        send("conversation/agent/list").jsonObject["agents"]?.jsonArray?.mapNotNull { HaAgent.parse(it.jsonObject) }.orEmpty()

    suspend fun conversation(text: String, language: String?, agentId: String?, conversationId: String?): HaAssistReply =
        HaAssist.parse(
            send("conversation/process") {
                HaAssist.request(text, language, agentId, conversationId).forEach { (k, v) -> put(k, v) }
            }.jsonObject,
        )

    /** Every state once, for a REST-less refresh after a reconnect. */
    suspend fun states(): List<HaEntity> =
        send("get_states").jsonArray.mapNotNull { HaEntity.fromRest(it.jsonObject)?.copy(home = home) }

    companion object {
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 15_000, 30_000)
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val COMMAND_TIMEOUT_MS = 15_000L
        private const val PING_INTERVAL_MS = 30_000L
        private const val PING_TIMEOUT_MS = 10_000L
        private const val GOING_AWAY = 1001
        private const val CHANGE_BUFFER = 256
    }
}

package com.wasimaster.wmkeyboard.ime.homeassistant

import android.content.Context
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.homeassistant.HaAgent
import com.wasimaster.wmkeyboard.core.homeassistant.HaAssistReply
import com.wasimaster.wmkeyboard.core.homeassistant.HaCache
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaInstance
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaRegistry
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import com.wasimaster.wmkeyboard.core.homeassistant.HaSnapshot
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HaBackend
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HaConnection
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeBackend
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeFeature
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeRef
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
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
import java.io.File

/** One listed home as the panel, the settings page and the AI tools see it. */
data class HomeStatus(
    val key: String,
    val kind: HomeKind,
    val name: String,
    val phase: HaPhase = HaPhase.OFFLINE,
    val features: Set<HomeFeature> = emptySet(),
    val instance: HaInstance? = null,
    val error: String? = null,
    /** Its entities came from the cache file; the home has not confirmed them yet. */
    val fromCache: Boolean = false,
) {
    val connected: Boolean get() = phase == HaPhase.LIVE || phase == HaPhase.REST_FALLBACK
}

/** What the hub knows across every home. Entities and registries are keyed by [HomeRef]s and home keys. */
data class HaHubState(
    val homes: List<HomeStatus> = emptyList(),
    /** By [HomeRef]: `homeKey|entity_id`. */
    val entities: Map<String, HaEntity> = emptyMap(),
    /** By home key. */
    val registries: Map<String, HaRegistry> = emptyMap(),
    /** The conversation agents of the home Assist talks to. */
    val agents: List<HaAgent> = emptyList(),
) {
    /** The best phase across the homes: live if any is, else the most hopeful. */
    val phase: HaPhase
        get() = homes.map { it.phase }.minByOrNull { PHASE_RANK.indexOf(it).let { i -> if (i < 0) PHASE_RANK.size else i } } ?: HaPhase.OFFLINE

    val fromCache: Boolean get() = homes.isNotEmpty() && homes.all { it.fromCache || it.phase == HaPhase.OFFLINE } && homes.any { it.fromCache }

    val lastError: String? get() = homes.firstNotNullOfOrNull { it.error }

    /** What the panel calls the place: one home's name, or the names joined. */
    val instanceName: String get() = homes.map { it.name }.filter { it.isNotBlank() }.distinct().joinToString(" · ")

    /** The first home's instance, for the one-home page. */
    val instance: HaInstance? get() = homes.firstNotNullOfOrNull { it.instance }

    fun home(key: String): HomeStatus? = homes.firstOrNull { it.key == key }

    fun registryOf(home: String): HaRegistry = registries[home] ?: EMPTY_REGISTRY

    fun registryOf(entity: HaEntity): HaRegistry = registryOf(entity.home)

    /** The home Assist and the AI's "control your home" talk to: the first Home Assistant. */
    val assistHome: HomeStatus? get() = homes.firstOrNull { HomeFeature.ASSIST in it.features }

    private companion object {
        val PHASE_RANK = listOf(HaPhase.LIVE, HaPhase.REST_FALLBACK, HaPhase.AUTHENTICATING, HaPhase.CONNECTING, HaPhase.UNREACHABLE, HaPhase.TOKEN_REJECTED, HaPhase.OFFLINE)
        val EMPTY_REGISTRY = HaRegistry()
    }
}

/**
 * The one set of smart home connections of this process, and the rules for
 * when they are up. A singleton for the reason `KdeConnectHub` is: the
 * keyboard, the settings page and the AI's tools all live in one process and
 * must see one socket per home, one entity map, one token refresh.
 *
 * Every listed home ([apply]) gets a [HomeBackend]; whoever needs them
 * [hold]s the hub under a reason and [release]s it after. The backends run
 * while a reason is held, and linger a minute after the keyboard goes away so
 * an app switch does not reconnect. All bookkeeping is on the main thread; the
 * network is each backend's own, on [io].
 */
@Suppress("detekt.TooManyFunctions")
object SmartHomeHub {

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

    /** The [HomeRef]s a home just changed, in order, for the tiles' pulse. */
    val changes: SharedFlow<Set<String>> = _changes.asSharedFlow()

    private var app: Context? = null
    private var repository: SettingsRepository? = null
    private var prefs = HomeAssistantSettings()
    private var accounts: List<HomeAccount> = emptyList()
    private val reasons = HashSet<Reason>()
    private var keyboardLeftAtMs = 0L
    private var lingerJob: Job? = null
    private var batchDepth = 0
    private var reconcileOwed = false
    private var cacheJob: Job? = null

    private class Entry(val account: HomeAccount, val backend: HomeBackend) {
        var jobs: List<Job> = emptyList()
        var fromCache = false
        var cacheDirty = false
        var cacheLoaded = false
    }

    private val entries = LinkedHashMap<String, Entry>()

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

    /**
     * The panel preferences and the homes as the settings now have them. A
     * home that left the list stops and its tiles go; one whose sign-in
     * changed starts over; a new one gets a backend.
     */
    fun apply(settings: HomeAssistantSettings, homes: List<HomeAccount>) {
        if (settings == prefs && homes == accounts) return
        prefs = settings
        accounts = homes
        val context = app
        val keys = homes.map { it.key }.toSet()
        for (key in entries.keys.toList()) {
            if (key !in keys) removeEntry(key)
        }
        for (account in homes) {
            val existing = entries[account.key]
            if (existing == null) {
                if (context != null) addEntry(context, account)
            } else if (existing.account != account) {
                val changed = existing.backend.update(account)
                entries[account.key] = Entry(account, existing.backend).also { e ->
                    e.jobs = existing.jobs
                    e.fromCache = if (changed) false else existing.fromCache
                    e.cacheDirty = existing.cacheDirty
                    e.cacheLoaded = existing.cacheLoaded
                }
                if (changed) context?.let { cacheFile(it, account.key).delete() }
            }
        }
        (entries.values.firstOrNull { it.backend is HaBackend }?.backend as? HaBackend)?.assistAgentId = settings.assistAgentId
        recompute()
        reconcile()
    }

    private fun addEntry(context: Context, account: HomeAccount) {
        val backend = HomeBackends.create(
            context, account, io,
            onTokens = { tokens ->
                repository?.updateProviderCredential(account.key) { it.copy(extra = HaConnection.withTokens(it.extra, tokens)) }
            },
            onCertificate = { sha ->
                io.launch { repository?.updateProviderCredential(account.key) { it.copy(extra = it.extra + (HomeKeys.CERT_SHA256 to sha)) } }
            },
        ) ?: return
        val entry = Entry(account, backend)
        entries[account.key] = entry
        entry.jobs = listOf(
            main.launch { backend.phase.collect { recompute() } },
            main.launch {
                backend.entities.collect { map ->
                    if (map.isNotEmpty()) {
                        entry.fromCache = false
                        entry.cacheDirty = true
                    }
                    recompute()
                }
            },
            main.launch { backend.changes.collect { ids -> _changes.tryEmit(ids.map { HomeRef.of(account.key, it) }.toSet()) } },
            main.launch { backend.registry.collect { entry.cacheDirty = true; recompute() } },
            main.launch {
                backend.instance.collect { instance ->
                    recompute()
                    learnInstance(entry, instance)
                }
            },
            main.launch { backend.error.collect { recompute() } },
        )
        loadCacheOnce(context, entry)
    }

    private fun removeEntry(key: String) {
        val entry = entries.remove(key) ?: return
        entry.jobs.forEach { it.cancel() }
        entry.backend.stop()
        entry.backend.clear()
        app?.let { cacheFile(it, key).delete() }
    }

    /** What the home calls itself, kept on the credential so the list and the panel can show it before connecting. */
    private fun learnInstance(entry: Entry, instance: HaInstance?) {
        val name = instance?.name?.takeIf { it.isNotBlank() } ?: return
        val account = entry.account
        if (account.extra(HomeKeys.INSTANCE_NAME) == name && (instance.uuid == null || account.extra(HomeKeys.INSTANCE_ID) == instance.uuid)) return
        io.launch {
            repository?.updateProviderCredential(account.key) { c ->
                val extra = c.extra + (HomeKeys.INSTANCE_NAME to name) + (instance.uuid?.let { HomeKeys.INSTANCE_ID to it } ?: (HomeKeys.INSTANCE_ID to c.extra[HomeKeys.INSTANCE_ID].orEmpty()))
                c.copy(extra = extra.filterValues { it.isNotEmpty() })
            }
        }
    }

    /** Rebuilds the merged state from every backend's flows. */
    private fun recompute() {
        val homes = ArrayList<HomeStatus>(entries.size)
        val entities = LinkedHashMap<String, HaEntity>()
        val registries = LinkedHashMap<String, HaRegistry>()
        var agents: List<HaAgent> = emptyList()
        for ((key, e) in entries) {
            val b = e.backend
            homes += HomeStatus(
                key = key,
                kind = b.kind,
                name = e.account.displayName.ifBlank { b.instance.value?.name.orEmpty() },
                phase = b.phase.value,
                features = b.features,
                instance = b.instance.value,
                error = b.error.value,
                fromCache = e.fromCache,
            )
            for ((id, entity) in b.entities.value) entities[HomeRef.of(key, id)] = if (entity.home == key) entity else entity.copy(home = key)
            registries[key] = b.registry.value
            if (agents.isEmpty() && b is HaBackend) agents = b.agents.value
        }
        _state.value = HaHubState(homes, entities, registries, agents)
    }

    // ---- Holding -------------------------------------------------------------

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
        if (!prefs.enabled || entries.isEmpty()) return false
        if (!DirectBoot.isUserUnlocked(context) || !InternetPermission.granted) return false
        if (reasons.any { it != Reason.KEYBOARD }) return true
        val lingering = System.currentTimeMillis() - keyboardLeftAtMs < KEYBOARD_LINGER_MS
        return when (prefs.lifetime) {
            HaLinkLifetime.PANEL -> false
            HaLinkLifetime.KEYBOARD -> Reason.KEYBOARD in reasons || lingering
        }
    }

    private fun reconcile() {
        if (batchDepth > 0) {
            reconcileOwed = true
            return
        }
        if (!wanted()) {
            stopAll()
            return
        }
        entries.values.forEach { it.backend.start() }
        if (cacheJob?.isActive != true) {
            cacheJob = io.launch {
                while (true) {
                    delay(CACHE_INTERVAL_MS)
                    saveDirtyCaches()
                }
            }
        }
    }

    private fun stopAll() {
        cacheJob?.cancel()
        cacheJob = null
        entries.values.forEach { it.backend.stop() }
        io.launch { saveDirtyCaches() }
    }

    // ---- Cache -------------------------------------------------------------

    private fun cacheFile(context: Context, key: String): File =
        File(File(context.filesDir, DIR), "cache-" + key.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("") + ".json")

    private fun loadCacheOnce(context: Context, entry: Entry) {
        if (entry.cacheLoaded || !prefs.cacheStates) return
        entry.cacheLoaded = true
        val file = cacheFile(context, entry.account.key)
        io.launch {
            val snap = runCatching { file.takeIf { it.exists() }?.readText()?.let(HaCache::decode) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                if (entry.backend.entities.value.isNotEmpty()) return@withContext
                entry.fromCache = true
                cached[entry.account.key] = snap
                recompute()
            }
        }
    }

    /** Snapshots loaded from disk, read into the merged state until the home confirms. */
    private val cached = HashMap<String, HaSnapshot>()

    private fun saveDirtyCaches() {
        if (!prefs.cacheStates) return
        val context = app ?: return
        for ((key, e) in entries.toMap()) {
            if (!e.cacheDirty || e.fromCache) continue
            val entities = e.backend.entities.value
            if (entities.isEmpty()) continue
            e.cacheDirty = false
            val file = cacheFile(context, key)
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(HaCache.encode(HaSnapshot(e.backend.instance.value, entities.values.toList(), e.backend.registry.value, System.currentTimeMillis())))
                tmp.renameTo(file)
            }
        }
    }

    // ---- Commands ----------------------------------------------------------

    private fun backendOf(home: String): HomeBackend? = entries[home]?.backend

    /** Runs [call], whose entity id is the home's own, on [home]. */
    suspend fun callService(home: String, call: HaServiceCall) {
        val backend = backendOf(home) ?: throw com.wasimaster.wmkeyboard.core.homeassistant.HaCommandException("no_home", home)
        backend.callService(call)
    }

    /** One sentence to Assist, on the first home that has one. */
    suspend fun assist(text: String, language: String?, conversationId: String?): HaAssistReply {
        val backend = entries.values.firstOrNull { HomeFeature.ASSIST in it.backend.features }?.backend
            ?: throw com.wasimaster.wmkeyboard.core.homeassistant.HaCommandException("no_assist", "no home with an assistant")
        return backend.assist(text, language, conversationId)
    }

    /** A camera still, as image bytes. */
    suspend fun snapshot(ref: String): ByteArray {
        val backend = backendOf(HomeRef.home(ref)) ?: throw java.io.IOException("no home")
        return backend.snapshot(HomeRef.native(ref))
    }

    /** The states once, fresh: for a caller holding [Reason.AI] or [Reason.SNIPPET] that cannot wait for the streams. */
    suspend fun ensureStates(timeoutMs: Long = STATES_TIMEOUT_MS): Map<String, HaEntity> {
        val live = withTimeoutOrNull(timeoutMs) { state.first { it.entities.isNotEmpty() && !it.fromCache } }
        if (live != null) return live.entities
        for (e in entries.values.toList()) {
            runCatching { withContext(Dispatchers.IO) { e.backend.states() } }
        }
        recompute()
        return state.value.entities
    }

    /** Try again now: a Retry button, the network came back. */
    fun refresh() {
        entries.values.forEach { it.backend.refresh() }
        reconcile()
    }

    /** The web address of the home [ref] belongs to (or the first home's), for a link. */
    fun webUrl(ref: String?): String? {
        val backend = if (ref != null) backendOf(HomeRef.home(ref)) else entries.values.firstOrNull { HomeFeature.OPEN_IN_APP in it.backend.features }?.backend
        return backend?.webUrl(ref?.let(HomeRef::native))
    }

    /** The entity [ref] names, from the live state or the cache. */
    fun entity(ref: String): HaEntity? = state.value.entities[ref] ?: cachedEntity(ref)

    private fun cachedEntity(ref: String): HaEntity? {
        val snap = cached[HomeRef.home(ref)] ?: return null
        val id = HomeRef.native(ref)
        return snap.entities.firstOrNull { it.entityId == id }
    }

    /** Every entity, the cached ones standing in for homes that have not answered yet. */
    fun entitiesWithCache(): Map<String, HaEntity> {
        val s = state.value
        if (cached.isEmpty()) return s.entities
        val out = LinkedHashMap<String, HaEntity>()
        for ((key, snap) in cached) {
            if (entries[key]?.fromCache != true) continue
            for (e in snap.entities) out[HomeRef.of(key, e.entityId)] = e.copy(home = key)
        }
        out.putAll(s.entities)
        return out
    }

    fun cachedRegistry(home: String): HaRegistry? = cached[home]?.takeIf { entries[home]?.fromCache == true }?.registry

    const val DIR = "homeassistant"
    const val KEYBOARD_LINGER_MS = 60_000L
    private const val LINGER_SLACK_MS = 250L
    private const val CACHE_INTERVAL_MS = 30_000L
    private const val STATES_TIMEOUT_MS = 4_000L
}

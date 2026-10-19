package com.wasimaster.wmkeyboard.core.smarthome.smartthings

import com.wasimaster.wmkeyboard.core.homeassistant.HaArea
import com.wasimaster.wmkeyboard.core.homeassistant.HaCommandException
import com.wasimaster.wmkeyboard.core.homeassistant.HaDevice
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntityEntry
import com.wasimaster.wmkeyboard.core.homeassistant.HaInstance
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaRegistry
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import com.wasimaster.wmkeyboard.core.homeassistant.HaTokens
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeFeature
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.smarthome.BaseBackend
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttp
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttpException
import com.wasimaster.wmkeyboard.core.smarthome.slug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * SmartThings as a [HomeBackend]: the cloud REST API, polled while the
 * backend runs, since events need a hosted SmartApp. Every device of the
 * account's locations, rooms as areas, scenes as `scene.` entities.
 *
 * The sign-in is OAuth ([SmartThingsAuth]) with the tokens in the
 * credential's extra, or a pasted personal access token as the key, which
 * SmartThings expires after a day.
 */
class SmartThingsBackend(
    key: String,
    initial: HomeAccount,
    private val http: HomeHttp,
    scope: CoroutineScope,
    private val clientId: String = "",
    private val clientSecret: String = "",
    private val onTokens: suspend (HaTokens) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = POLL_MS,
    private val base: String = API,
) : BaseBackend(key, HomeKind.SMARTTHINGS, initial, scope) {

    override val features: Set<HomeFeature> = emptySet()

    @Volatile private var tokens: HaTokens? = tokensOf(initial)

    /** Entity id to the device it came from, from the last list. */
    private val devices = ConcurrentHashMap<String, StDevice>()
    private val scenes = ConcurrentHashMap<String, SmartThingsMapping.StScene>()

    override fun update(account: HomeAccount): Boolean {
        val changed = super.update(account)
        if (changed) tokens = tokensOf(account)
        return changed
    }

    private fun tokensOf(a: HomeAccount): HaTokens? {
        if (a.apiKey.isNotBlank()) return HaTokens.longLived(a.apiKey)
        val access = a.extra(HomeKeys.ACCESS_TOKEN) ?: return null
        return HaTokens(access, a.extra(HomeKeys.REFRESH_TOKEN), a.extra(HomeKeys.EXPIRES_AT)?.toLongOrNull() ?: Long.MAX_VALUE)
    }

    /** A token this backend refreshed comes back through the settings as the one in use; that is not a new sign-in. */
    override fun sameConnection(a: HomeAccount, b: HomeAccount): Boolean = a.apiKey == b.apiKey &&
        (a.extra(HomeKeys.ACCESS_TOKEN) == b.extra(HomeKeys.ACCESS_TOKEN) || b.extra(HomeKeys.ACCESS_TOKEN) == tokens?.accessToken)

    private suspend fun headers(): Map<String, String> {
        val current = tokens ?: throw HomeHttpException(HomeHttpException.UNAUTHORIZED, "not signed in")
        val token = if (current.expiresWithin(now()) && clientId.isNotBlank()) {
            val refreshed = SmartThingsAuth.refresh(http, clientId, clientSecret, current, now())
            tokens = refreshed
            onTokens(refreshed)
            refreshed.accessToken
        } else {
            current.accessToken
        }
        return mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
    }

    private suspend fun get(path: String): String = http.get("$base$path", headers()).require()

    // ---- Session -------------------------------------------------------------

    override suspend fun session() {
        loadAll()
        live(HaPhase.REST_FALLBACK)
        pollForever(pollMs) { loadDevices() }
    }

    private suspend fun loadAll() {
        val locations = SmartThingsMapping.parseNamed(get("/locations"), "locationId", "name")
        val rooms = locations.flatMap { (locationId, _) ->
            runCatching { SmartThingsMapping.parseNamed(get("/locations/$locationId/rooms"), "roomId", "name") }.getOrDefault(emptyList())
        }
        setInstance(HaInstance(name = locations.firstOrNull()?.second ?: "SmartThings"))
        val list = SmartThingsMapping.parseScenes(runCatching { get("/scenes") }.getOrDefault("{}"))
        scenes.clear()
        list.forEach { scenes["scene.st_" + slug(it.id)] = it }
        loadDevices(rooms)
    }

    private var rooms: List<Pair<String, String>> = emptyList()

    private suspend fun loadDevices(knownRooms: List<Pair<String, String>>? = null) {
        if (knownRooms != null) rooms = knownRooms
        val body = get("/devices?includeStatus=true")
        var parsed = SmartThingsMapping.parseDevices(body)
        // An API that ignored includeStatus leaves every status empty; ask per device then.
        if (parsed.isNotEmpty() && parsed.all { it.status.isEmpty() }) {
            parsed = parsed.map { d ->
                val status = runCatching { SmartThingsMapping.parseStatus(get("/devices/${d.id}/status")) }.getOrNull() ?: d.status
                d.copy(status = status)
            }
        }
        val seconds = now() / MS_PER_SECOND.toDouble()
        val entities = LinkedHashMap<String, HaEntity>()
        val entries = ArrayList<HaEntityEntry>()
        devices.clear()
        for (d in parsed) {
            for (e in SmartThingsMapping.entities(d, seconds)) {
                entities[e.entityId] = e
                devices[e.entityId] = d
                entries += HaEntityEntry(e.entityId, areaId = d.roomId, deviceId = d.id)
            }
        }
        for ((id, scene) in scenes) {
            entities[id] = SmartThingsMapping.sceneEntity(scene, seconds)
            entries += HaEntityEntry(id)
        }
        setRegistry(
            HaRegistry(
                areas = rooms.map { (id, name) -> HaArea(id, name) },
                devices = parsed.map { HaDevice(it.id, it.roomId, it.label) },
                entries = entries,
            ),
        )
        publish(entities)
    }

    // ---- Commands ------------------------------------------------------------

    override suspend fun callService(call: HaServiceCall) {
        val id = call.entityId ?: throw HaCommandException("no_target", "no entity")
        if (call.domain == "scene") {
            val scene = scenes[id] ?: throw HaCommandException("not_found", id)
            http.postJson("$base/scenes/${scene.id}/execute", "{}", headers()).require()
            return
        }
        val device = devices[id] ?: throw HaCommandException("not_found", id)
        val commands = SmartThingsMapping.commands(call, device, entities.value[id])
        if (commands.isEmpty()) throw HaCommandException("unsupported", "${call.domain}.${call.service}")
        http.postJson("$base/devices/${device.id}/commands", SmartThingsMapping.commandsBody(commands), headers()).require()
        // The cloud takes a moment to reflect a command; one quick look confirms the tile.
        delay(CONFIRM_DELAY_MS)
        runCatching {
            val status = SmartThingsMapping.parseStatus(get("/devices/${device.id}/status"))
            val fresh = device.copy(status = status)
            val seconds = now() / MS_PER_SECOND.toDouble()
            merge(SmartThingsMapping.entities(fresh, seconds).associateBy { it.entityId })
            SmartThingsMapping.entities(fresh, seconds).forEach { devices[it.entityId] = fresh }
        }
    }

    override suspend fun states(): Map<String, HaEntity> {
        if (rooms.isEmpty()) loadAll() else loadDevices()
        return entities.value
    }

    companion object {
        const val API = "https://api.smartthings.com/v1"
        const val POLL_MS = 10_000L
        private const val CONFIRM_DELAY_MS = 1_200L
        private const val MS_PER_SECOND = 1000L
    }
}
